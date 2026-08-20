package com.th3web.lean.core

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import com.th3web.lean.LeanApp
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import com.th3web.lean.ui.tr

/**
 * Process-wide façade over [LeanVpnService]. The UI talks only to this object:
 * it observes [state]/[traffic]/[logs] and calls [connect]/[disconnect]. The
 * service pushes updates back in via the internal setters.
 */
object CoreManager {

    /** Sentinel "profile id" meaning: let the core auto-select the fastest server. */
    const val AUTO_PROFILE_ID = "__auto__"

    private val _state = MutableStateFlow<VpnState>(VpnState.Disconnected)
    val state: StateFlow<VpnState> = _state.asStateFlow()

    private val _traffic = MutableStateFlow(TrafficStats())
    val traffic: StateFlow<TrafficStats> = _traffic.asStateFlow()

    private val _logs = MutableStateFlow<List<String>>(emptyList())
    val logs: StateFlow<List<String>> = _logs.asStateFlow()

    // --- Persistent log ring file ------------------------------------------
    // The in-memory _logs buffer is wiped on process death; we mirror every line
    // into a small rotating file in filesDir so LogsScreen still shows history
    // after a restart and the displayed/copied log is meaningful. All file IO is
    // serialized onto a single background thread to keep it off the main thread
    // and free of races. Everything is best-effort: a logging failure never
    // throws into the caller.
    private val logIo = Executors.newSingleThreadExecutor { r ->
        Thread(r, "lean-log-io").apply { isDaemon = true }
    }

    @Volatile private var historyLoaded = false

    /**
     * Bumped every time the user clears the log. [ensureHistoryLoaded] captures this
     * before it reads the file and re-checks it before prepending, so a history load
     * that was already in flight when the user hit "clear" can't resurrect the cleared
     * lines. (clearLogs also re-empties on the same [logIo] thread, ordering its write
     * after any in-flight load.)
     */
    @Volatile private var clearGeneration = 0

    private val logFile: File?
        get() = runCatching { File(LeanApp.instance.filesDir, LOG_FILE_NAME) }.getOrNull()

    private val logFileBackup: File?
        get() = runCatching { File(LeanApp.instance.filesDir, LOG_FILE_NAME + ".1") }.getOrNull()

    /** Seed [_logs] once from the persisted file so history survives a restart. */
    private fun ensureHistoryLoaded() {
        if (historyLoaded) return
        synchronized(this) {
            if (historyLoaded) return
            historyLoaded = true
        }
        val generation = clearGeneration
        logIo.execute {
            runCatching {
                val lines = ArrayList<String>()
                logFileBackup?.takeIf { it.exists() }?.let { lines += it.readLines() }
                logFile?.takeIf { it.exists() }?.let { lines += it.readLines() }
                // If the user cleared the log while we were reading the file, drop the
                // history so we don't resurrect the lines they just cleared.
                if (lines.isNotEmpty() && generation == clearGeneration) {
                    val tail = lines.takeLast(MAX_LOG_LINES)
                    // Prepend history, then keep the cap (live lines that arrived
                    // while we were loading stay newest). Atomic CAS so a live
                    // appendLog racing this prepend (native log tailer vs the
                    // logIo thread) can't lose a line.
                    _logs.update { (tail + it).takeLast(MAX_LOG_LINES) }
                }
            }
        }
    }

    /**
     * Append a BLOCK of lines to the persisted file in one write, rotating it when it
     * grows too large.
     *
     * Takes a list rather than a line because the caller that matters is the native log
     * tailer, which already reads whatever the core produced in the last 500 ms. One line
     * at a time is an open/append/close round per line, and at a chatty log level that is
     * a line per connection, a continuous disk write competing with the tunnel it
     * describes. One `appendText` per poll costs the same as one line did.
     */
    private fun persistLines(lines: List<String>) {
        if (lines.isEmpty()) return
        val block = lines.joinToString(separator = "\n", postfix = "\n")
        logIo.execute {
            runCatching {
                val file = logFile ?: return@execute
                if (file.exists() && file.length() > MAX_LOG_FILE_BYTES) {
                    // renameTo returns false rather than throwing, so a runCatching
                    // around it catches nothing and a failed rotation is retried on every
                    // line while the log grows unbounded. Truncate in place instead.
                    val rotated = logFileBackup?.let { backup ->
                        runCatching { backup.delete() }
                        file.renameTo(backup)
                    } ?: false
                    if (!rotated) runCatching { file.writeText("") }
                }
                file.appendText(block)
            }
        }
    }

    /** Live outbound groups (urltest/selector) with per-node latency, while connected. */
    private val _groups = MutableStateFlow<List<OutboundGroupState>>(emptyList())
    val groups: StateFlow<List<OutboundGroupState>> = _groups.asStateFlow()

    val isActive: Boolean
        get() = state.value.let { it is VpnState.Connected || it is VpnState.Connecting }

    internal fun setState(value: VpnState) { _state.value = value }
    internal fun setTraffic(value: TrafficStats) { _traffic.value = value }
    /**
     * When [groups] last carried a fresh per-node delay. Compared against
     * [com.th3web.lean.data.model.Profile.latencyAtMs] so the row shows whichever
     * measurement is newer, a manual «URL Test» while connected must not stay hidden
     * behind a core reading taken at connect time.
     */
    private val _groupsAtMs = MutableStateFlow(0L)
    val groupsAtMs: StateFlow<Long> = _groupsAtMs.asStateFlow()

    internal fun setGroups(value: List<OutboundGroupState>) {
        _groups.value = value
        // Only a real measurement moves the clock. Publishing the node list at connect
        // time carries delayMs = null for every node, and stamping that would make an
        // empty reading look newer than a ping the user just took.
        if (value.any { g -> g.items.any { it.delayMs != null } }) {
            _groupsAtMs.value = System.currentTimeMillis()
        }
    }

    // --- Per-server ping bridges (set by LeanVpnService while the tunnel is up) ---
    // [probeProtect] = VpnService.protect on a raw probe FileDescriptor, so per-server
    // pings escape the tunnel and measure the real per-node path instead of the proxy
    // egress. Null when disconnected (no tunnel → probes need no protect). Call sites
    // read it without holding a Service reference. [retestAuto] asks the live core to
    // re-run its urltest (the real proxied generate_204 per node).
    @Volatile private var protectHook: ((java.io.FileDescriptor) -> Unit)? = null
    val probeProtect: ((java.io.FileDescriptor) -> Unit)? get() = protectHook
    internal fun setProtectHook(hook: ((java.io.FileDescriptor) -> Unit)?) { protectHook = hook }

    @Volatile private var retestHook: (() -> Unit)? = null
    internal fun setRetestHook(hook: (() -> Unit)?) { retestHook = hook }
    /** Test the currently selected native route. No-op when disconnected. */
    fun retestAuto() { retestHook?.invoke() }
    /** True while the current-route test hook belongs to a live session. */
    val canRetest: Boolean get() = retestHook != null
    @Volatile private var clearNativeLogsHook: (() -> Unit)? = null
    internal fun setClearNativeLogsHook(hook: (() -> Unit)?) { clearNativeLogsHook = hook }
    /**
     * A line the APP itself is saying, as opposed to one tailed out of the core.
     *
     * Kept in a second, small ring as well as in the shared buffer. The two sources share
     * one capped list and are nowhere near equal in volume: a busy core writes thousands
     * of error lines an hour, while the app contributes a handful (which server was
     * chosen, how long the handshake took, why a helper was given up on). Those few are
     * what a diagnostics report is for, and in one shared buffer they are the first thing
     * evicted.
     */
    internal fun appendLog(line: String) {
        synchronized(ownLines) {
            ownLines += line
            if (ownLines.size > MAX_OWN_LOG_LINES) {
                ownLines.subList(0, ownLines.size - MAX_OWN_LOG_LINES).clear()
            }
        }
        appendLogs(listOf(line))
    }

    /** The app's own narration, oldest first. Never crowded out by the core's output. */
    internal fun ownLog(): List<String> = synchronized(ownLines) { ownLines.toList() }

    private val ownLines = mutableListOf<String>()

    /**
     * How many connections the core has reported giving up on, ever, this process.
     *
     * Read by [TunnelStallWatch], which cannot see this failure any other way: when the
     * session to the server is wedged, connections die before they carry a byte, so the
     * per-outbound counters stay flat and the log is the only witness that anything was
     * being asked of the tunnel at all.
     */
    internal fun coreFailureCount(): Long = coreFailures.get()

    private val coreFailures = AtomicLong(0)

    /**
     * Only reasons that mean "the far end stopped answering", the one thing a network
     * reset can actually fix. A refused connection to a local helper port, for instance,
     * is just as fatal and absent: resetting the network would not revive a
     * helper process that is not running, so counting it would buy a scary log line and
     * nothing else.
     */
    private val failureReasons = listOf(
        "no recent network activity",
        "operation was canceled",
        "context deadline exceeded",
        "i/o timeout",
    )

    private fun countFailures(lines: List<String>) {
        var found = 0L
        for (line in lines) {
            if (failureReasons.any { line.contains(it, ignoreCase = true) }) found++
        }
        if (found > 0) coreFailures.addAndGet(found)
    }

    /**
     * Append a batch of lines as one update. The tailer hands over a poll's worth of
     * core output at a time; folding it into a single state update and a single file
     * write is what keeps a chatty core from costing more than the traffic it carries.
     *
     * Trimming to [MAX_LOG_LINES] before the update matters as much as batching: the
     * buffer is a capped list, so `(it + lines).takeLast(cap)` copies the whole cap for
     * every batch. Handing it a batch that is itself longer than the cap, a burst after
     * a stall, would build a huge intermediate list only to throw nearly all of it away.
     */
    internal fun appendLogs(lines: List<String>) {
        if (lines.isEmpty()) return
        countFailures(lines)
        ensureHistoryLoaded()
        val batch = if (lines.size > MAX_LOG_LINES) lines.takeLast(MAX_LOG_LINES) else lines
        // Atomic CAS read-modify-write: this is called concurrently from the native log
        // tailer, service IO coroutines, and platform callbacks. A plain
        // `_logs.value = _logs.value + batch` would drop lines when two threads race.
        _logs.update { (it + batch).takeLast(MAX_LOG_LINES) }
        persistLines(batch)
    }
    fun clearLogs() {
        runCatching { clearNativeLogsHook?.invoke() }
        // Invalidate any in-flight history load so it can't re-prepend cleared lines.
        clearGeneration++
        _logs.value = emptyList()
        historyLoaded = true // nothing left to reload
        logIo.execute {
            // Ordered after any in-flight ensureHistoryLoaded prepend on this same
            // single-thread executor: re-empty so the user's clear always wins even
            // if a load had already passed its generation re-check.
            _logs.value = emptyList()
            runCatching { logFile?.delete() }
            runCatching { logFileBackup?.delete() }
        }
    }

    /**
     * Start the tunnel for [profileId]. A new request supersedes the current generation,
     * whose native instance and TUN are closed before the replacement starts. The VPN
     * consent prompt must already be granted.
     *
     * [restart] forces a rebuild even when [profileId] is already the live tunnel, pass it
     * when the profile's own configuration changed and has to take effect now. Without it,
     * reconnecting to the active profile is a deliberate no-op, so re-selecting the server
     * you are already on does not drop the tunnel.
     */
    fun connect(context: Context, profileId: String, restart: Boolean = false) {
        val intent = Intent(context, LeanVpnService::class.java).apply {
            action = LeanVpnService.ACTION_START
            putExtra(LeanVpnService.EXTRA_PROFILE_ID, profileId)
            putExtra(LeanVpnService.EXTRA_RESTART, restart)
        }
        // Android 12+ throws ForegroundServiceStartNotAllowedException when a foreground
        // service is started from the background without an allowed exemption, e.g. an
        // automation/boot connect before the app has been foregrounded. Catch it so a
        // background trigger can't crash the whole process; surface it as an error.
        try {
            ContextCompat.startForegroundService(context, intent)
        } catch (e: Exception) {
            appendLog(tr("✖ запуск из фона запрещён системой: %s").format(e.message ?: e.javaClass.simpleName))
            setState(VpnState.Error("background start not allowed"))
        }
    }

    fun disconnect(context: Context) {
        // The service is already running (and foreground) when we disconnect, so
        // a plain startService is allowed and, unlike startForegroundService,
        // does not obligate the service to call startForeground before stopping.
        val intent = Intent(context, LeanVpnService::class.java).apply {
            action = LeanVpnService.ACTION_STOP
        }
        try {
            context.startService(intent)
        } catch (e: Exception) {
            appendLog(tr("✖ остановка сервиса отклонена системой: %s").format(e.message ?: e.javaClass.simpleName))
        }
    }

    private const val MAX_LOG_LINES = 500
    /** The app's own lines are few and irreplaceable; the core's are neither. */
    private const val MAX_OWN_LOG_LINES = 120

    /** Persisted log file name (and "<name>.1" for the single rotated backup). */
    private const val LOG_FILE_NAME = "core.log"

    /** Rotate the active log file once it grows past this size (~256 KB per file). */
    private const val MAX_LOG_FILE_BYTES = 256L * 1024L

    init {
        // Load any persisted history as soon as the object is first touched (the UI
        // collecting CoreManager.logs is enough), so LogsScreen shows it after a restart.
        ensureHistoryLoaded()
    }
}

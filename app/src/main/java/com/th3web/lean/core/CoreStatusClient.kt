package com.th3web.lean.core

import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import com.th3web.lean.core.engine.NekoBox
import com.th3web.lean.core.engine.TrafficAccumulator
import com.th3web.lean.data.model.Profile
import com.th3web.lean.ui.tr

class CoreStatusClient(
    parentScope: CoroutineScope,
    private val generation: Long,
    private val isCurrent: (Long) -> Boolean,
    private val box: NekoBox,
    profiles: List<Profile>,
    cacheDir: File,
    /**
     * «Восстанавливать соединение само» — whether the idle health probe runs at all.
     * Defaults to off so a caller that does not own the recovery decision (tests, and
     * anything constructing this for its status feed alone) never silently starts
     * reconnecting a tunnel. [LeanVpnService] passes the live setting.
     */
    private val healthCheckEnabled: () -> Boolean = { false },
    /**
     * True while the core has been handed to sing-box's pause manager (see [DozePause]).
     * A paused core carries nothing by design, so probing it would fail every time and
     * the escalation would reconnect a tunnel that is working exactly as asked.
     */
    private val corePaused: () -> Boolean = { false },
    /**
     * Last rung of the ladder: rebuild the core for the same profile. Invoked at most
     * once per escalation, and only after a reset has been tried and given time.
     */
    private val requestRestart: () -> Unit = {},
    private val verifyTransfer: suspend () -> Boolean? = { null },
    private val onHealthy: () -> Unit = {},
) {
    private val job = SupervisorJob(parentScope.coroutineContext[Job])
    private val scope = CoroutineScope(Dispatchers.IO + job)
    private val closed = AtomicBoolean(false)
    private val selectionRevision = AtomicLong()
    private val accumulator = TrafficAccumulator(System.nanoTime())
    private val logTail = NativeLogTail(File(cacheDir, "neko.log"))
    /**
     * Watches for a tunnel that stays Connected and stops carrying. The counters below
     * are already read once a second for the speed line, so noticing costs nothing, and
     * without it «зависает через какое-то время» has no answer but a manual reconnect.
     */
    private val stall = TunnelStallWatch()

    /**
     * Asks the tunnel whether it still carries, during the silence [stall] cannot read.
     * See [TunnelHealthCheck] for why both exist.
     */
    private val health = TunnelHealthCheck()
    // delayMs starts unknown for every node: this field means "measured through the
    // tunnel", and nothing has been measured yet at connect time. Seeding it from the
    // profile's stored edge-ping made the rows show a connect-time snapshot that no
    // later ping could refresh, and turned a never-probed server into a green ✓ 0 мс.
    // Only [setRetestHook] below, with a real result in hand, fills it in.
    private val nodes = profiles.map { profile ->
        OutboundNode(
            tag = "node-${profile.id}",
            type = profile.outbound.protocol,
            delayMs = null,
        )
    }

    fun start(profileId: String) {
        CoreManager.setGroups(
            if (nodes.size > 1) {
                listOf(
                    OutboundGroupState(
                        tag = if (profileId == CoreManager.AUTO_PROFILE_ID) "auto" else "proxy",
                        selected = if (profileId == CoreManager.AUTO_PROFILE_ID) nodes.firstOrNull()?.tag.orEmpty()
                        else "node-$profileId",
                        items = nodes,
                    ),
                )
            } else {
                emptyList()
            },
        )
        CoreManager.setRetestHook {
            if (isLive()) {
                scope.launch {
                    val revision = selectionRevision.get()
                    // The native urlTest measures whatever outbound the box is currently
                    // routing through, so its result belongs to the SELECTED node, and
                    // publishing it is the only way any node ever gets a real live delay.
                    // A throw (or a negative return) means the node failed the test, which
                    // the rows render as ✗ rather than as a latency.
                    val measured = runCatching { box.urlTest(TEST_URL, TEST_TIMEOUT_MS) }
                        .getOrDefault(-1)
                    if (!isLive() || selectionRevision.get() != revision) return@launch
                    CoreManager.setGroups(
                        CoreManager.groups.value.map { group ->
                            group.copy(
                                items = group.items.map { node ->
                                    if (node.tag == group.selected) node.copy(delayMs = measured) else node
                                },
                            )
                        },
                    )
                }
            }
        }
        CoreManager.setClearNativeLogsHook {
            if (isLive()) scope.launch { runCatching { box.clearLogs() } }
        }
        scope.launch {
            while (isActive && isLive()) {
                val up = runCatching { box.queryStats("proxy", "uplink") }.getOrDefault(0)
                val down = runCatching { box.queryStats("proxy", "downlink") }.getOrDefault(0)
                if (!isActive || !isLive()) break
                val sample = accumulator.add(up, down, System.nanoTime())
                CoreManager.setTraffic(
                    TrafficStats(
                        uplink = sample.uplink,
                        downlink = sample.downlink,
                        uplinkTotal = sample.uplinkTotal,
                        downlinkTotal = sample.downlinkTotal,
                    ),
                )
                val stalled = stall.sample(
                    sample.uplinkTotal,
                    sample.downlinkTotal,
                    CoreManager.coreFailureCount(),
                    System.nanoTime(),
                )
                if (stalled) {
                    CoreManager.appendLog(
                        tr("⚠ туннель не отвечает — сбрасываю соединения и DNS"),
                    )
                    // The same call a network handover makes, and for the same reason: the
                    // sockets and the resolver state are pointing at something that is no
                    // longer answering. It is the least destructive thing that can unstick
                    // this: the tunnel itself stays up and the user keeps their session.
                    runCatching { box.resetNetwork() }
                        .onFailure { error ->
                            CoreManager.appendLog(
                                tr("⚠ сброс не удался: %s")
                                    .format(error.message ?: error.javaClass.simpleName),
                            )
                        }
                }
                // The same counters answer a second question: has anything arrived
                // lately at all? Only silence is worth probing (see [TunnelHealthCheck]),
                // so this is where the clock on it is kept.
                if (sample.downlinkTotal > lastDownlinkTotal) {
                    lastDownlinkTotal = sample.downlinkTotal
                    lastDownlinkAtNanos = System.nanoTime()
                }
                maybeProbeHealth()
                delay(if (LeanForeground.visible) STATUS_INTERVAL_MS else STATUS_IDLE_INTERVAL_MS)
            }
        }
        scope.launch {
            while (isActive && isLive()) {
                CoreManager.appendLogs(logTail.readNewLines())
                delay(if (LeanForeground.visible) LOG_INTERVAL_MS else LOG_IDLE_INTERVAL_MS)
            }
        }
    }

    /** Running totals, to tell "received nothing lately" from "idle for a second". */
    private var lastDownlinkTotal = 0L
    private var lastDownlinkAtNanos = System.nanoTime()
    private var lastProbeAtNanos = System.nanoTime()

    /** One probe at a time, and never on the status loop. See [maybeProbeHealth]. */
    private val probing = AtomicBoolean(false)

    /**
     * Sends the idle health probe when it is due, and acts on the verdict.
     *
     * Three gates before anything leaves the device, each one a case where probing would
     * be wrong rather than merely wasteful:
     *
     *  * the setting is off — the user declined automatic recovery;
     *  * the core is paused for Doze — it carries nothing on purpose;
     *  * the tunnel received something recently — it has already answered.
     *
     * The probe itself is the live core's own `urlTest`, i.e. a real generate_204 through
     * the selected outbound. It is the same call «Проверить текущий маршрут» makes, so it
     * measures the tunnel rather than the phone's connectivity. A successful ping is
     * followed by a sustained transfer before the session is considered healthy.
     */
    private fun maybeProbeHealth() {
        if (!healthCheckEnabled() || corePaused()) return
        // Never on the status loop's own coroutine. box.urlTest is a blocking JNI call
        // with an eight-second budget, and this loop is what feeds the speed line and the
        // stall watch: running the probe inline froze both for the duration, so the UI
        // showed a tunnel that had stopped moving — while measuring one that had not.
        if (!probing.compareAndSet(false, true)) return
        val now = System.nanoTime()
        val idleThreshold = if (LeanForeground.visible) {
            TunnelHealthCheck.IDLE_BEFORE_PROBE_MS
        } else {
            TunnelHealthCheck.IDLE_BEFORE_PROBE_IDLE_MS
        } * 1_000_000L
        if (now - lastDownlinkAtNanos < idleThreshold || now - lastProbeAtNanos < idleThreshold) {
            // Nothing to do yet. The flag has to be released here too, or the first
            // early return would stop every later probe from ever running.
            probing.set(false)
            return
        }
        lastProbeAtNanos = now
        scope.launch {
            try {
                runProbe()
            } finally {
                probing.set(false)
            }
        }
    }

    /** The probe itself, on its own coroutine. See [maybeProbeHealth]. */
    private suspend fun runProbe() {
        if (!canProbeHealth()) return
        val revision = selectionRevision.get()
        val measured = withContext(Dispatchers.IO) {
            runCatching { box.urlTest(TEST_URL, TunnelHealthCheck.PROBE_TIMEOUT_MS) }
                .getOrDefault(-1)
        }
        if (!canProbeHealth() || selectionRevision.get() != revision) {
            health.reset()
            return
        }
        val survived = if (measured < 0) false else try {
            verifyTransfer()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            null
        }
        if (!canProbeHealth() || selectionRevision.get() != revision || survived == null) {
            health.reset()
            return
        }
        if (survived) onHealthy()
        when (health.onProbe(ok = survived)) {
            TunnelHealthCheck.Action.None ->
                if (!survived) {
                    CoreManager.appendLog(
                        tr("⚠ проверка туннеля не прошла (%d подряд)").format(health.failures),
                    )
                }

            TunnelHealthCheck.Action.Reset -> {
                CoreManager.appendLog(
                    tr("⚠ туннель молчит на проверках — сбрасываю соединения и DNS"),
                )
                runCatching { box.resetNetwork() }
                    .onFailure { error ->
                        CoreManager.appendLog(
                            tr("⚠ сброс не удался: %s")
                                .format(error.message ?: error.javaClass.simpleName),
                        )
                    }
            }

            TunnelHealthCheck.Action.Restart -> {
                CoreManager.appendLog(tr("↻ сброс не помог — перезапускаю подключение"))
                runCatching { requestRestart() }
            }
        }
    }

    fun selectorChanged(tag: String, selected: String) {
        if (!isLive()) return
        selectionRevision.incrementAndGet()
        CoreManager.setGroups(
            CoreManager.groups.value.map { group ->
                if (group.tag == tag || group.tag == "auto" && tag == "proxy") {
                    group.copy(selected = selected)
                } else {
                    group
                }
            },
        )
    }

    suspend fun close() {
        if (!closed.compareAndSet(false, true)) return
        CoreManager.setRetestHook(null)
        CoreManager.setClearNativeLogsHook(null)
        job.cancel()
        // Bounded join, not cancelAndJoin. The pollers spend most of their time in
        // `delay`, which cancels instantly, but they can also be parked inside a
        // blocking JNI call into Go (`queryStats` every second, or a `urlTest` the user
        // just triggered, which carries a 10s budget of its own). Coroutine cancellation
        // cannot interrupt a JNI call, so an unbounded join makes disconnect take as
        // long as whatever native call happens to be in flight, intermittently, since it
        // depends on where the poll cycle was caught.
        //
        // Not waiting is safe: `closed` is already true, so isLive() is false, and every
        // publish site re-checks it after its native call returns. A straggler that
        // finishes later therefore writes nothing.
        withTimeoutOrNull(CLOSE_JOIN_TIMEOUT_MS) { job.join() }
        CoreManager.setGroups(emptyList())
        CoreManager.setTraffic(TrafficStats())
    }

    private fun isLive(): Boolean = !closed.get() && isCurrent(generation)

    private fun canProbeHealth(): Boolean = isLive() && healthCheckEnabled() && !corePaused()

    private companion object {
        const val STATUS_INTERVAL_MS = 1_000L
        const val LOG_INTERVAL_MS = 500L

        /**
         * The same two polls once nobody is looking (see [LeanForeground]).
         *
         * Both exist to feed the UI. With the screen away the only consumer left is the
         * notification's speed line, so the rates average over a longer window while the
         * byte totals stay exact: they come from the core's own counters rather than
         * being accumulated from samples, and the per-tick JNI call, file read and state
         * write drop by an order of magnitude.
         */
        const val STATUS_IDLE_INTERVAL_MS = 10_000L
        const val LOG_IDLE_INTERVAL_MS = 8_000L
        const val TEST_TIMEOUT_MS = 10_000
        const val TEST_URL = "https://www.gstatic.com/generate_204"
        /** How long close() waits for pollers that may be inside a blocking JNI call. */
        const val CLOSE_JOIN_TIMEOUT_MS = 300L
    }
}

internal class NativeLogTail(private val file: File) {
    private var offset = 0L

    fun readNewLines(): List<String> {
        if (!file.isFile) return emptyList()
        val length = file.length()
        // Truncated (the core rotated it), start over.
        if (length < offset) offset = 0
        // Nothing new: answer from one stat instead of opening, seeking, reading and
        // closing the file. This runs twice a second for as long as the tunnel is up, and
        // a quiet core (which is the normal state at log level "warn") grows the file
        // not at all, so almost every one of those rounds had nothing to find.
        if (length == offset) return emptyList()
        return runCatching {
            RandomAccessFile(file, "r").use { input ->
                input.seek(offset)
                val lines = buildList {
                    while (true) {
                        val line = input.readLine() ?: break
                        add(
                            CoreStatusLogSanitizer.sanitize(
                                line.toByteArray(Charsets.ISO_8859_1).toString(Charsets.UTF_8),
                            ),
                        )
                    }
                }
                offset = input.filePointer
                lines
            }
        }.getOrDefault(emptyList())
    }

    /**
     * Caps the file, keeping its tail. Returns the bytes reclaimed.
     *
     * Nothing else shortens it: the file is the core's redirected stderr, so it only
     * grows, tens of megabytes on a device left running for weeks. Only the tail is worth
     * anything; it is all [readLastLines] reads for a diagnostics report.
     *
     * The caller must guarantee no core is running in this process, because the writer
     * holds an open descriptor: truncating under it is safe only if that descriptor is in
     * append mode, and this does not get to assume that. See the call site.
     *
     * [keepBytes] rather than a line count, so nothing has to be decoded to decide what
     * survives: the tail is copied as it is.
     */
    fun trimTo(maxBytes: Long, keepBytes: Long): Long {
        if (!file.isFile) return 0
        val before = file.length()
        if (before <= maxBytes) return 0
        return runCatching {
            // Raw bytes, not via readLastLines: that one redacts as it reads,
            // which is right for something being sent somewhere and quite wrong for a
            // rewrite: it would replace the core's own log with our redactions and lose
            // the original for good.
            val tail = RandomAccessFile(file, "r").use { input ->
                val start = (before - keepBytes).coerceAtLeast(0)
                input.seek(start)
                // Whatever line the cut landed in the middle of is dropped, so the file
                // still begins at a line boundary.
                if (start > 0) input.readLine()
                val remaining = (before - input.filePointer).toInt().coerceAtLeast(0)
                ByteArray(remaining).also { input.readFully(it) }
            }
            file.writeBytes(tail)
            offset = file.length()
            before - file.length()
        }.getOrDefault(0)
    }

    fun readLastLines(maxLines: Int): List<String> {
        if (!file.isFile || maxLines <= 0) return emptyList()
        return runCatching {
            RandomAccessFile(file, "r").use { input ->
                val start = (input.length() - MAX_FAILURE_TAIL_BYTES).coerceAtLeast(0)
                input.seek(start)
                if (start > 0) input.readLine()
                val lines = ArrayDeque<String>(maxLines)
                while (true) {
                    val line = input.readLine() ?: break
                    if (lines.size == maxLines) lines.removeFirst()
                    lines.addLast(
                        CoreStatusLogSanitizer.sanitize(
                            line.toByteArray(Charsets.ISO_8859_1).toString(Charsets.UTF_8),
                        ),
                    )
                }
                lines.toList()
            }
        }.getOrDefault(emptyList())
    }

    private companion object {
        const val MAX_FAILURE_TAIL_BYTES = 128L * 1024L
    }
}

internal object CoreStatusLogSanitizer {
    private const val REDACTED = "<redacted>"

    private val authenticationHeader = Regex(
        """(?i)\b(authorization|proxy-authorization|cookie|set-cookie)(\s*:\s*)[^\r\n]*""",
    )
    private val vpnShareUri = Regex(
        """(?i)\b(?:vless|vmess|trojan|ss|shadowsocks|hysteria2|hy2|wireguard|wg)://[^\s]+""",
    )
    private val uriUserInfo = Regex("""(?i)(\b[a-z][a-z0-9+.-]*://)[^/\s@]+@""")
    private val sensitiveAssignment = Regex(
        """(?i)(["']?\b(?:private[_ -]?key|pre[_ -]?shared[_ -]?key|preshared[_ -]?key|psk|password|passwd|token|access[_ -]?token|api[_ -]?key|secret|uuid)\b["']?\s*[=:]\s*)(?:"[^"]*"|'[^']*'|[^\s,;&]+)""",
    )

    fun sanitize(line: String): String {
        var result = line.take(MAX_LOG_LINE_LENGTH)
        result = authenticationHeader.replace(result) { match ->
            "${match.groupValues[1]}${match.groupValues[2]}$REDACTED"
        }
        result = vpnShareUri.replace(result, REDACTED)
        result = uriUserInfo.replace(result) { match ->
            "${match.groupValues[1]}$REDACTED@"
        }
        return sensitiveAssignment.replace(result) { match ->
            "${match.groupValues[1]}$REDACTED"
        }
    }
}

private const val MAX_LOG_LINE_LENGTH = 4_096

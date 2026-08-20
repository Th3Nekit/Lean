package com.th3web.lean.core.engine

import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import com.th3web.lean.core.connection.ConnectionCommand
import com.th3web.lean.core.connection.ConnectionRuntime
import com.th3web.lean.core.connection.ConnectionSession
import com.th3web.lean.core.connection.DesiredConnection
import com.th3web.lean.data.model.Outbound
import com.th3web.lean.data.model.Profile
import com.th3web.lean.core.tun.TunRuntimePolicy

const val AMNEZIA_WG_UNSUPPORTED_MESSAGE =
    "AmneziaWG должен запускаться отдельным движком Amnezia Go"

data class EngineConfig(
    val profileId: String,
    val profiles: List<Profile>,
    val json: String,
    val tunnelPolicy: TunRuntimePolicy? = null,
)

interface NekoCore {
    fun newInstance(config: String): NekoBox
}

interface NekoBox {
    fun setAsMain()
    fun setV2rayStats(tags: String)
    fun start()
    fun close()
    fun queryStats(tag: String, direction: String): Long
    fun selectOutbound(tag: String): Boolean
    fun urlTest(url: String, timeoutMs: Int): Int = -1
    fun clearLogs() = Unit

    /**
     * Tells the core the underlying network changed: closes tracked sockets, re-binds
     * every outbound and endpoint, and (the part nothing else does) drops the DNS cache
     * and closes every DNS transport.
     *
     * Default no-op so fakes in tests need not care.
     */
    fun resetNetwork() = Unit

    /**
     * Hands the core to sing-box's pause manager, and takes it back.
     *
     * Read [com.th3web.lean.core.DozePause] before calling either of these. A paused core
     * does not keep forwarding: the pause manager holds new connections until wake(), so
     * for as long as this is in effect the tunnel carries nothing. The only caller is the
     * opt-in Doze switch, and a test fails the build if that stops being true.
     */
    fun sleep() = Unit

    fun wake() = Unit
}

interface NekoTunnelSession {
    fun begin(generation: Long, config: EngineConfig)
    fun close(generation: Long)
}

fun interface NekoSessionObserver {
    suspend fun close()
}

class NekoEngine(
    private val configProvider: suspend (String) -> EngineConfig,
    private val core: NekoCore,
    private val generationIsCurrent: (Long) -> Boolean,
    private val tunnel: NekoTunnelSession,
    private val profileProvider: suspend (String) -> List<Profile> = { configProvider(it).profiles },
    private val onStarted: (Long, NekoBox, EngineConfig) -> NekoSessionObserver? = { _, _, _ -> null },
    private val discardCoreCache: () -> Boolean = { false },
) : ConnectionRuntime {
    override suspend fun start(command: ConnectionCommand): ConnectionSession {
        val desired = command.desired as? DesiredConnection.Running
            ?: error("Cannot start a stopped connection")
        val profiles = profileProvider(desired.profileId)
        rejectAmnezia(profiles)
        ensureCurrent(command.generation)

        return try {
            startOnce(command, desired)
        } catch (error: Throwable) {
            // The core keeps its selector choice, URL-test results and rule-set downloads
            // in one bbolt file (cache.db). Android kills a VPN service whenever it likes,
            // and a kill landing inside a bbolt write leaves that file structurally broken.
            // From then on every start panics before a single outbound exists:
            //   panic: misplaced bucket header: fakeip_address -> fakeip_metadata
            //   box.Start panic: invalid page type: 13: 10
            //   …cachefile.(*CacheFile).FakeIPMetadata → fakeip.(*Store).Start
            // The user sees «не работает ни один сервер» while the same subscription
            // works in any other client, and nothing short of wiping app data brings it
            // back.
            //
            // The file is pure cache: every byte in it is recoverable by re-deriving it.
            // So on a start failure that smells like a broken cache we delete it and try
            // once more, which turns a permanent brick into one slower connect.
            if (!isCorruptCoreCache(error) || !discardCoreCache()) throw error
            ensureCurrent(command.generation)
            startOnce(command, desired)
        }
    }

    private suspend fun startOnce(
        command: ConnectionCommand,
        desired: DesiredConnection.Running,
    ): ConnectionSession {
        var box: NekoBox? = null
        var observer: NekoSessionObserver? = null
        var tunnelStarted = false
        try {
            val config = configProvider(desired.profileId)
            rejectAmnezia(config.profiles)
            ensureCurrent(command.generation)
            tunnel.begin(command.generation, config)
            tunnelStarted = true
            ensureCurrent(command.generation)
            // Never build a new instance while a previous native call is still inside Go.
            //
            // startBounded/closeBounded abandon their thread on timeout,, so
            // a wedged core cannot hang the coordinator. The cost is that the abandoned
            // call is still running in the Go runtime when the next connect arrives, and
            // creating an instance on top of that ends as:
            //   runtime: g 134: unexpected return pc for runtime.cgocallback
            //   …_cgoexp_…_proxylibcore__NewSingBoxInstance
            //   fatal error: unknown caller pc
            // a Go FATAL error, which no recover(), and none of our try/catch can save;
            // the process simply dies. Waiting here costs a moment on the rare path and
            // removes the overlap entirely.
            awaitNativeQuiet()
            ensureCurrent(command.generation)
            box = core.newInstance(config.json)
            ensureCurrent(command.generation)
            box.setAsMain()
            ensureCurrent(command.generation)
            box.setV2rayStats("proxy")
            ensureCurrent(command.generation)
            // Box.start() runs the whole sing-box bring-up (inbounds, outbounds, DNS
            // bootstrap of every server hostname, and, when a remote rule-set has no
            // cache yet, an HTTP fetch) behind one blocking JNI call into Go. It has no
            // internal deadline we control, and JNI calls are not interruptible, so an
            // unbounded call blocks the caller forever. That caller is
            // ConnectionCoordinator's single actor coroutine, so it wedges the whole
            // machine: no terminal state is published and the Stop queued behind it is
            // never processed. Bounding it here is what turns a hung core into an
            // ordinary Error.
            startBounded(box)
            ensureCurrent(command.generation)
            observer = onStarted(command.generation, box, config)
            ensureCurrent(command.generation)
            return Session(desired.profileId, command.generation, box, observer, tunnel)
        } catch (error: Throwable) {
            runCatching { observer?.close() }
            // Never block the failure path on the native close either. Note it cannot
            // rescue a timed-out start: BoxInstance.Close() takes the same lock Start()
            // holds for its whole duration, so against a genuinely stuck start it just
            // blocks too. Bounding it is what keeps that from hanging US as well; the
            // tunnel generation is released below regardless, so the next connect is
            // never blocked by the abandoned one.
            box?.let { closeBounded(it) }
            if (tunnelStarted) runCatching { tunnel.close(command.generation) }
            throw error
        }
    }

    private suspend fun startBounded(box: NekoBox) {
        val outcome = awaitNative(START_TIMEOUT_MS, "start") { box.start() }
            ?: error(
                "Ядро не запустилось за ${START_TIMEOUT_MS / 1000} с " +
                    "(проверьте доступность сервера и DNS)",
            )
        outcome.getOrThrow()
    }

    private fun ensureCurrent(generation: Long) {
        check(generationIsCurrent(generation)) { "Connection generation $generation is stale" }
    }

    /**
     * Does this failure look like the on-disk core cache is structurally broken?
     *
     * The panic text crosses the JNI boundary intact (`go.Universe$proxyerror: box.Start
     * panic: …`), so matching it is the only signal available, bbolt reports corruption
     * by panicking, not by returning a typed error. The markers below are bbolt's own
     * wording for a damaged b-tree; they cannot occur for a merely unreachable server, so
     * a config or network problem never reaches the cache-wipe path.
     */
    private fun isCorruptCoreCache(error: Throwable): Boolean {
        val text = generateSequence(error) { it.cause }
            .mapNotNull { it.message }
            .joinToString(" ")
            .lowercase()
        return CORRUPT_CACHE_MARKERS.any { it in text }
    }

    private fun rejectAmnezia(profiles: List<Profile>) {
        require(profiles.none { (it.outbound as? Outbound.WireGuard)?.awg != null }) {
            AMNEZIA_WG_UNSUPPORTED_MESSAGE
        }
    }

    private class Session(
        override val profileId: String,
        private val generation: Long,
        private val box: NekoBox,
        private val observer: NekoSessionObserver?,
        private val tunnel: NekoTunnelSession,
    ) : ConnectionSession {
        private val closed = AtomicBoolean()

        override suspend fun close() {
            if (!closed.compareAndSet(false, true)) return
            // Bounded like the native close below, and for the same reason: the observer
            // tears down the status client, whose pollers can be parked inside a blocking
            // JNI call that cancellation cannot interrupt. It bounds itself too, but this
            // is the seam the coordinator's actor actually waits on, so it must not be
            // able to hang here either.
            withTimeoutOrNull(OBSERVER_CLOSE_TIMEOUT_MS) { runCatching { observer?.close() } }
            try {
                // Bounded for the same reason as start: a native close that hangs must
                // not be able to wedge the coordinator's actor (that is what made "Stop"
                // hang forever in "Отключение").
                closeBounded(box)
            } finally {
                tunnel.close(generation)
            }
        }
    }
}

/**
 * bbolt's own vocabulary for a damaged b-tree, as it reaches us through the Go panic text.
 * Narrow: every entry describes on-disk structure, never a network or config
 * fault, so a wipe is only ever triggered by an actually unusable cache file.
 */
private val CORRUPT_CACHE_MARKERS = listOf(
    "invalid page type",
    "misplaced bucket header",
    "invalid page id",
    "page ids overlap",
    "invalid meta page",
    "checksum error",
    "database not open",
    "invalid database",
)

/** How long a single blocking native call may hold up the connection state machine. */
private const val START_TIMEOUT_MS = 30_000L
private const val CLOSE_TIMEOUT_MS = 5_000L
/** Cap on tearing down the status client during a stop. */
private const val OBSERVER_CLOSE_TIMEOUT_MS = 1_000L

private suspend fun closeBounded(box: NekoBox) {
    runCatching { awaitNative(CLOSE_TIMEOUT_MS, "close") { box.close() } }
}

/**
 * Runs one blocking native call on its own daemon thread and awaits it in a cancellable
 * way. Returns null when [timeoutMs] elapses first; the thread is left
 * running, a stuck Go call cannot be interrupted; it can only be unblocked by closing
 * the box, and abandoning the thread is what lets the caller (and therefore the whole
 * connection state machine) make progress regardless.
 */
private suspend fun <T> awaitNative(
    timeoutMs: Long,
    name: String,
    block: () -> T,
): Result<T>? {
    val done = CompletableDeferred<Result<T>>()
    nativeCallsInFlight.incrementAndGet()
    Thread(
        {
            try {
                done.complete(runCatching(block))
            } finally {
                // In the finally, so an abandoned call still reports its own departure
                // when it eventually returns: that is the whole signal awaitNativeQuiet
                // waits on.
                nativeCallsInFlight.decrementAndGet()
            }
        },
        "lean-neko-$name",
    ).apply {
        isDaemon = true
        start()
    }
    return withTimeoutOrNull(timeoutMs) { done.await() }
}

/**
 * Blocking native calls that have not returned yet, including ones already abandoned by
 * their caller. Process-wide because the Go runtime is: two engine instances would still
 * be entering the same one.
 */
private val nativeCallsInFlight = java.util.concurrent.atomic.AtomicInteger(0)

/**
 * Waits, briefly, for the native layer to be idle.
 *
 * Only ever matters after a timeout: in the normal case nothing is in flight and this
 * returns on the first check. Bounded: a call that is wedged forever must not
 * make the app unusable, so after [NATIVE_QUIET_TIMEOUT_MS] we go ahead anyway. That is a
 * deliberate trade: a small risk on a path that is already broken, against a certain
 * inability to ever reconnect.
 */
private suspend fun awaitNativeQuiet() {
    withTimeoutOrNull(NATIVE_QUIET_TIMEOUT_MS) {
        while (nativeCallsInFlight.get() > 0) delay(NATIVE_QUIET_POLL_MS)
    }
}

private const val NATIVE_QUIET_TIMEOUT_MS = 6_000L
private const val NATIVE_QUIET_POLL_MS = 50L

package com.th3web.lean.core

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import com.th3web.lean.LeanApp
import com.th3web.lean.core.engine.LibcoreNekoCore
import com.th3web.lean.core.engine.NekoBox
import com.th3web.lean.core.plugin.PluginSession
import com.th3web.lean.core.plugin.XrayUrlTest
import com.th3web.lean.data.model.Outbound
import com.th3web.lean.data.net.Pinger

/**
 * The «URL Test» ping type, named after NekoBox's own per-profile connectivity
 * test (`add_profile_menu.xml`'s "URL Test" action, `TestInstance.doTest`): boots a
 * throwaway, headless sing-box instance for just this one outbound (see
 * [SingBoxConfig.buildUrlTestJson], no TUN, no other inbound), and times a real
 * HTTP round trip through its actual protocol handshake (TLS/Reality/VMess/etc.),
 * the same number NekoBox reports. Unlike TCP/ICMP this proves the server really
 * works end to end, not just that its port answers a SYN.
 *
 * Heavier than the raw-socket probes, booting a mini core per call is
 * real work, so every call goes through [concurrencyGate], a fixed-size semaphore
 * bounding how many instances run at once regardless of how many callers are
 * probing in parallel. Mirrors NekoBox's own worker-pool test loop
 * (`DataStore.connectionTestConcurrent`, defaulting to 5, matched here) without
 * needing every bulk call-site to manage a worker pool itself: a burst of N
 * `async { UrlTestPinger.measure(...) } .awaitAll()` calls is automatically
 * throttled to [MAX_CONCURRENT] simultaneous native instances.
 */
object UrlTestPinger {

    /** WireGuard/AmneziaWG has no "outbound" form in sing-box (it's an "endpoint"),
     * so there is no protocol-level test to run, callers keep using the existing
     * TCP/UDP probe for those instead of calling [measure]. Delegates to
     * [Pinger.supportsUrlTest] so this and [Pinger.measure]'s own dispatch guard can
     * never drift apart (they did once: the dispatch called the probe for WireGuard
     * anyway, and the -1 it can only ever return made every WG row look dead). */
    fun supports(outbound: Outbound): Boolean = Pinger.supportsUrlTest(outbound)

    /**
     * Measured RTT in ms; -1 when the test RAN and the server failed it; and null when
     * the test could not run at all.
     *
     * That third case is what this is about. Reporting "could not build or start a core instance"
     * as -1 makes a perfectly healthy server render as unreachable, which is
     * what shipping URL Test as the default did: every row showed a dash, and the ping
     * looked broken rather than the probe. A null lets the caller fall back to the raw
     * socket probe and show a real number instead of a lie about the server.
     *
     * Never throws.
     */
    suspend fun measure(outbound: Outbound, pingUrl: String, timeoutMs: Int): Int? {
        if (pingUrl.isBlank()) return null
        // The one protocol measured outside the core. An XHTTP node has no sing-box
        // outbound to build a probe config from, so a headless instance cannot test it,
        // Xray does, in its own process. Everything about the result is the same: ms, -1
        // for a node that failed, null for a probe that could not run.
        if (outbound is Outbound.Vless && PluginSession.needsXray(outbound)) {
            return XrayUrlTest.measure(LeanApp.instance, outbound, pingUrl, timeoutMs)
        }
        if (!supports(outbound)) return null
        return concurrencyGate.withPermit { measureUnbounded(outbound, pingUrl, timeoutMs) }
    }

    private suspend fun measureUnbounded(outbound: Outbound, pingUrl: String, timeoutMs: Int): Int? {
        val app = LeanApp.instance
        val settings = app.settings.state.value
        // null, not -1: a config we could not build or a core that would not instantiate
        // says nothing about the server.
        val config = runCatching { SingBoxConfig.buildUrlTestJson(outbound, settings) }
            .getOrNull() ?: return null
        val box: NekoBox = runCatching { LibcoreNekoCore(app).newInstance(config) }.getOrNull() ?: return null
        return try {
            // Both box.start(), and box.urlTest() are blocking JNI calls into Go with
            // no reliable interior deadline of their own (the exact hazard NekoEngine's
            // own startBounded/closeBounded guard against on the real connection path,
            // a stuck DNS lookup or TLS handshake can wedge the calling thread forever,
            // and coroutine cancellation cannot interrupt a blocking JNI call). The same
            // discipline applies here: abandon rather than hang.
            // A core that will not start is our failure, not the server's.
            val started = awaitNative(START_TIMEOUT_MS) { box.start() }
            if (started == null || started.isFailure) return null
            val tested = awaitNative(timeoutMs + URL_TEST_SLACK_MS) { box.urlTest(pingUrl, timeoutMs) }
            // A throw from urlTest is the instance failing; a negative return is the
            // server failing the test, and only that is a real miss.
            tested?.getOrNull()
        } catch (e: Exception) {
            null
        } finally {
            awaitNative(CLOSE_TIMEOUT_MS) { box.close() }
        }
    }

    private const val MAX_CONCURRENT = 5
    private val concurrencyGate = Semaphore(MAX_CONCURRENT)

    private const val START_TIMEOUT_MS = 10_000L
    private const val CLOSE_TIMEOUT_MS = 5_000L
    private const val URL_TEST_SLACK_MS = 2_000L

    /**
     * Runs one blocking native call on its own daemon thread and awaits it in a
     * cancellable way, returning null when [timeoutMs] elapses first. The thread is
     * left running on timeout, a stuck Go call cannot be interrupted,
     * only unblocked by closing the box, and abandoning the thread is what lets the
     * caller make progress regardless. Duplicated from NekoEngine's private
     * awaitNative (same fix, different call sites/timeouts) rather than shared, to
     * keep this object self-contained.
     */
    private suspend fun <T> awaitNative(timeoutMs: Long, block: () -> T): Result<T>? {
        val done = CompletableDeferred<Result<T>>()
        Thread({ done.complete(runCatching(block)) }, "lean-urltest-probe").apply {
            isDaemon = true
            start()
        }
        return withTimeoutOrNull(timeoutMs) { done.await() }
    }
}

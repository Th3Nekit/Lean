package com.th3web.lean.core

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import libcore.Libcore
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
        // Two gates, and the outer one only matters while a tunnel is up.
        //
        // Five throwaway cores at once is fine on an idle app. It is not fine beside a
        // running tunnel: the probe instances and the live core share this process and the
        // one Go heap, a sixty-server sweep boots sixty of them, and the peak is what the
        // system's low-memory killer reads. A kill there does not look like a failed ping,
        // it looks like the VPN switching itself off — which is exactly how it was
        // reported. Halving the peak costs a slower sweep in the one case where the sweep
        // is not what the user is waiting for.
        return if (CoreManager.isActive) {
            tunnelGate.withPermit {
                concurrencyGate.withPermit { measureUnbounded(outbound, pingUrl, timeoutMs) }
            }
        } else {
            concurrencyGate.withPermit { measureUnbounded(outbound, pingUrl, timeoutMs) }
        }
    }

    private suspend fun measureUnbounded(outbound: Outbound, pingUrl: String, timeoutMs: Int): Int? {
        inFlight.incrementAndGet()
        try {
            return measureInstance(outbound, pingUrl, timeoutMs)
        } finally {
            // The last probe of a sweep hands the Go heap back to the OS. Go keeps freed
            // pages for itself, so sixty probe instances leave the process holding their
            // peak footprint for the rest of its life — next to a live tunnel that is a
            // process the system kills, and a tunnel the user sees drop for no reason.
            // Once per sweep, not once per probe: FreeOSMemory stops the world.
            if (inFlight.decrementAndGet() == 0) releaseProbeMemory()
        }
    }

    /**
     * Returns the Go runtime's unused pages to the OS. Safe to call with a tunnel
     * running: it frees what nothing references and does not touch the live instance.
     *
     * Not a suspend function, deliberately. It is called from a `finally` that also runs
     * when the sweep is cancelled (the user left the screen), and a suspending call in a
     * cancelled coroutine would simply not happen — which is the one case where the
     * memory most needs handing back. A fire-and-forget daemon thread runs regardless,
     * and nothing waits on the result.
     */
    private fun releaseProbeMemory() {
        Thread({ runCatching { Libcore.forceGc() } }, "lean-probe-gc").apply {
            isDaemon = true
            start()
        }
    }

    private suspend fun measureInstance(outbound: Outbound, pingUrl: String, timeoutMs: Int): Int? {
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
            // Two round trips, the first one only to pay for the session.
            //
            // A fresh instance's first urlTest carries the whole bring-up: the server's
            // hostname resolved for the first time, the TCP connect, the protocol's own
            // handshake (TLS/Reality/…), and only then the HTTP request. None of that is
            // latency, and all of it lands on the one number the row shows, which is why
            // a 100 ms server could read as 400. The second test runs over a warm DNS
            // cache and a warm session, which is what the number is supposed to mean.
            //
            // Cheap here: the expensive part of this probe is booting the instance, and
            // that is already paid. Same discipline as
            // [com.th3web.lean.data.net.Pinger.tcpLatency]'s warm double connect and
            // [com.th3web.lean.core.plugin.XrayUrlTest]'s, including the rule that a warm
            // miss never buries a server the cold pass proved alive.
            val warm = awaitNative(timeoutMs + URL_TEST_SLACK_MS) { box.urlTest(pingUrl, timeoutMs) }
            // A throw from urlTest is the instance failing; a negative return is the
            // server failing the test, and only that is a real miss.
            val warmMs = warm?.getOrNull() ?: return null
            if (warmMs < 0) return warmMs
            val measured = awaitNative(timeoutMs + URL_TEST_SLACK_MS) {
                box.urlTest(pingUrl, timeoutMs)
            }?.getOrNull()
            if (measured != null && measured in 1 until warmMs) measured else warmMs
        } catch (e: Exception) {
            null
        } finally {
            awaitNative(CLOSE_TIMEOUT_MS) { box.close() }
        }
    }

    private const val MAX_CONCURRENT = 5
    private val concurrencyGate = Semaphore(MAX_CONCURRENT)

    /** How many probe instances may exist while a tunnel is running. See [measure]. */
    private const val MAX_CONCURRENT_WITH_TUNNEL = 2
    private val tunnelGate = Semaphore(MAX_CONCURRENT_WITH_TUNNEL)

    /** Probes that have not finished, so the last one out can free the Go heap. */
    private val inFlight = java.util.concurrent.atomic.AtomicInteger(0)

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

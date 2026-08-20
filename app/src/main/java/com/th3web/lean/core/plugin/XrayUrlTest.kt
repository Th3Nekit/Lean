package com.th3web.lean.core.plugin

import android.content.Context
import java.io.File
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import com.th3web.lean.core.CoreManager
import com.th3web.lean.data.model.Outbound

/**
 * The «URL Test» ping for the VLESS nodes the core cannot speak.
 *
 * ## Why a whole process just to measure a ping
 *
 * Every other protocol is measured by booting a headless sing-box for the one outbound
 * and timing a real HTTP round trip through its actual handshake
 * (see [com.th3web.lean.core.UrlTestPinger]). An XHTTP node cannot do that: the core has
 * no such transport, so it fell through to the raw TCP probe, which connects to the
 * node's address and stops there.
 *
 * For an ordinary server those two numbers are close. For an XHTTP node they are not
 * remotely the same thing: the address is a CDN edge, so the probe timed the distance to
 * the nearest edge PoP (five to twenty milliseconds), while the traffic actually goes
 * edge → origin → out. Every such node therefore rendered as the fastest server in the
 * list, sorted itself to the top, and poisoned the «Авто» prediction with a number that
 * described the CDN's geography rather than the tunnel.
 *
 * So it is measured the way it is used: a one-shot Xray listening on a local SOCKS port,
 * an HTTP request through it, and the process torn down. Slow and heavyweight, which is
 * why [gate] holds it to two at a time: each probe is a real 35 MB process, and a
 * subscription refresh can ask for thirty at once.
 */
internal object XrayUrlTest {

    /**
     * Milliseconds to the response status line; -1 when the node answered but failed the
     * test; null when the probe could not run at all and the caller should fall back.
     *
     * The null case is load-bearing. Reporting "no binary for this ABI" or "could not
     * spawn" as -1 would paint a healthy server as dead: the caller can still show the
     * raw TCP number, which is at least a real measurement of something.
     */
    suspend fun measure(
        context: Context,
        outbound: Outbound.Vless,
        pingUrl: String,
        timeoutMs: Int,
    ): Int? {
        if (pingUrl.isBlank()) return null
        if (!NativePlugin.Xray.isAvailable(context)) return null
        // Never while a tunnel is up. Unlike the core's own probe, which sing-box
        // protects socket by socket because the config asks it to: a probe process runs
        // as this app's UID with no way to escape the tun, so it would measure the tunnel
        // through itself and, worse, push real traffic through the live one. The caller
        // falls back to the raw socket probe, which does get protected.
        if (CoreManager.isActive) return null
        return gate.withPermit { runProbe(context, outbound, pingUrl, timeoutMs) }
    }

    private suspend fun runProbe(
        context: Context,
        outbound: Outbound.Vless,
        pingUrl: String,
        timeoutMs: Int,
    ): Int? = withContext(Dispatchers.IO) {
        val binary = NativePlugin.Xray.binary(context) ?: return@withContext null
        val port = PluginSession.freePort()
        val dir = File(context.cacheDir, PROBE_DIR).apply { mkdirs() }
        val configFile = File(dir, "xray-probe-$port.json")
        val config = runCatching { PluginConfig.forXrayProbe(outbound, port) }.getOrNull()
            ?: return@withContext null
        runCatching { configFile.writeText(config) }.getOrNull() ?: return@withContext null

        var process: Process? = null
        try {
            process = runCatching {
                ProcessBuilder(listOf(binary.absolutePath, "run", "-c", configFile.absolutePath))
                    // A directory this app owns. Without it the probe inherits whatever
                    // the JVM's happens to be and goes looking for its assets there, which
                    // on a device means a run of SELinux denials against paths belonging
                    // to something else entirely, harmless to the measurement, but noise
                    // in everyone's kernel log that we put there.
                    .directory(dir)
                    // XRAY_LOCATION_ASSET pins the same thing for the geo files. They are
                    // not shipped and the probe config names no geo rule, so
                    // this exists to stop the search, not to satisfy it.
                    .apply { environment()["XRAY_LOCATION_ASSET"] = dir.absolutePath }
                    // Merged and never read: a probe lives for a second or two, far too
                    // short to fill a pipe buffer, and its output is noise next to the one
                    // number we came for.
                    .redirectErrorStream(true)
                    .start()
            }.getOrNull() ?: return@withContext null

            // Nothing to measure until it is listening. A node whose config Xray refuses
            // never gets here, and that is a real answer about the node, but it is
            // reported as "could not run", because the fault may equally be ours.
            //
            // delay(), not Thread.sleep(): the timeout around this can only fire at a
            // suspension point, and a sleeping thread offers none: the wait would never
            // end for a helper that never binds.
            withTimeoutOrNull(READY_TIMEOUT_MS) {
                while (!runCatching { PluginSession.speaksSocks5(port) }.getOrDefault(false)) {
                    delay(READY_POLL_MS)
                }
            } ?: return@withContext null

            request(pingUrl, port, timeoutMs)
        } finally {
            runCatching { process?.destroy() }
            runCatching { configFile.delete() }
        }
    }

    /**
     * One HTTP round trip through the local SOCKS port, timed to the status line.
     *
     * The same shape as the raw-socket HTTP probe: only a clean 204/200
     * counts, because a redirect or any other status is a captive portal or a DPI stub
     * answering on the server's behalf, not the server.
     */
    private fun request(pingUrl: String, port: Int, timeoutMs: Int): Int {
        val proxy = Proxy(Proxy.Type.SOCKS, InetSocketAddress(PluginConfig.LOCALHOST, port))
        val started = System.nanoTime()
        val connection = runCatching {
            (URL(pingUrl).openConnection(proxy) as HttpURLConnection).apply {
                connectTimeout = timeoutMs
                readTimeout = timeoutMs
                instanceFollowRedirects = false
                requestMethod = "GET"
            }
        }.getOrNull() ?: return -1
        return try {
            val code = connection.responseCode
            val elapsed = ((System.nanoTime() - started) / 1_000_000).toInt()
            if (code == 204 || code == 200) elapsed.coerceAtLeast(1) else -1
        } catch (_: Throwable) {
            -1
        } finally {
            runCatching { connection.disconnect() }
        }
    }

    /**
     * Two at a time. Each probe is a whole Xray process, so this is a memory bound as much
     * as a CPU one: the sing-box equivalent runs five, but those are instances inside our
     * own process rather than thirty-five megabytes of someone else's.
     */
    private val gate = Semaphore(2)

    private const val PROBE_DIR = "plugins"
    private const val READY_TIMEOUT_MS = 4_000L
    private const val READY_POLL_MS = 100L
}

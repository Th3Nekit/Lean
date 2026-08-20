package com.th3web.lean.data.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL

/**
 * End-to-end connectivity probe for «Проверить соединение» on Home.
 *
 * When the VPN is up, the app's own traffic is routed through the tunnel, so a
 * plain HTTP GET to the configured ping URL measures real through-the-tunnel
 * reachability, unlike per-server TCP ping, which only touches the server edge.
 *
 * A bare HttpURLConnection (not [Http.getFull]): no x-hwid header
 * quartet, no {hwid} token expansion, just a GET with the shared User-Agent.
 */
object ConnectionChecker {

    sealed interface CheckResult {
        data class Ok(val ms: Int) : CheckResult
        data object Timeout : CheckResult
        data object NoInternet : CheckResult
    }

    suspend fun check(url: String, timeoutMs: Int = 5_000): CheckResult = withContext(Dispatchers.IO) {
        val started = System.nanoTime()
        try {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = timeoutMs
                readTimeout = timeoutMs
                requestMethod = "GET"
                instanceFollowRedirects = false
                setRequestProperty("User-Agent", Http.userAgent)
            }
            try {
                val code = conn.responseCode
                // generate_204-style probes answer 204 (or 200). A 3xx redirect on a
                // URL that should answer 2xx directly means a captive portal / DPI
                // stub intercepted us (i.e. no real tunnel), so it must not read as
                // "Ok N ms". instanceFollowRedirects=false keeps the 3xx visible here;
                // only 200/204 count as a working end-to-end path.
                if (code == 204 || code == 200) {
                    val elapsedMs = ((System.nanoTime() - started) / 1_000_000L).toInt()
                    CheckResult.Ok(elapsedMs.coerceAtLeast(1))
                } else {
                    CheckResult.NoInternet
                }
            } finally {
                conn.disconnect()
            }
        } catch (e: SocketTimeoutException) {
            CheckResult.Timeout
        } catch (e: Exception) {
            CheckResult.NoInternet
        }
    }

    // ---- Sustained (>16 KB) survival check, v2rayTun-style ----

    /** Default sized endpoint for the survival test; served from beyond the exit. */
    fun stressUrl(bytes: Int): String = "https://speed.cloudflare.com/__down?bytes=$bytes"

    /**
     * Bytes to pull for the survival test. 256 KB is decisively past the ~16 KB
     * cliff where RU TSPU tends to tear a proxied connection down (a single small
     * request/ping sails through, then the stream dies once real data flows).
     */
    const val SUSTAINED_TARGET_BYTES = 262_144

    sealed interface SustainedResult {
        /** Full payload arrived, the connection survives real data flow. */
        data class Survived(val bytes: Int, val ms: Int, val kbps: Int) : SustainedResult
        /**
         * The transfer started (handshake ok, some bytes arrived), then the stream
         * died before [SUSTAINED_TARGET_BYTES], the classic TSPU teardown after
         * ~16 KB. [bytes] is how far it got; a small value near the cliff is the tell.
         */
        data class Torn(val bytes: Int, val ms: Int) : SustainedResult
        /** Couldn't even start (no 2xx / connect failure), a dead path, not a teardown. */
        data object Failed : SustainedResult
    }

    /**
     * Downloads [targetBytes] from [url] through the active tunnel and reports
     * whether the whole payload arrived (survived), the stream died mid-transfer
     * (torn, the TSPU-after-16KB signature), or it never started (failed).
     *
     * Unlike [check] (a tiny generate_204 that a single packet satisfies), this
     * forces real sustained data flow, which is what actually trips the TSPU
     * teardown a one-shot ping never sees. Runs over the tunnel (no VpnService
     * .protect), so when connected it measures the proxy path's real endurance.
     */
    suspend fun sustainedCheck(
        url: String = stressUrl(SUSTAINED_TARGET_BYTES),
        targetBytes: Int = SUSTAINED_TARGET_BYTES,
        timeoutMs: Int = 15_000,
    ): SustainedResult = withContext(Dispatchers.IO) {
        val started = System.nanoTime()
        var conn: HttpURLConnection? = null
        try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = timeoutMs
                readTimeout = timeoutMs
                requestMethod = "GET"
                instanceFollowRedirects = false
                setRequestProperty("User-Agent", Http.userAgent)
                setRequestProperty("Accept", "*/*")
            }
            val code = conn.responseCode
            if (code != 200 && code != 206) return@withContext SustainedResult.Failed
            var total = 0
            val buf = ByteArray(16 * 1024)
            // readTimeout bounds each read(), not the whole transfer, so a slow-trickle
            // server (a byte before every timeout) could stall for targetBytes/rate. Cap
            // the whole download by a wall-clock deadline; not reaching target in time is
            // itself the "doesn't sustain" signal (-> Torn below).
            val deadline = started + timeoutMs * 1_000_000L
            val torn = try {
                conn.inputStream.use { input ->
                    while (total < targetBytes) {
                        if (System.nanoTime() > deadline) break
                        val n = input.read(buf)
                        if (n < 0) break // EOF: if before target, the stream was cut short
                        total += n
                    }
                }
                false
            } catch (e: IOException) {
                // Mid-stream I/O failure after a clean handshake = teardown, not a
                // dead path, report how far we got.
                true
            }
            val ms = ((System.nanoTime() - started) / 1_000_000L).toInt().coerceAtLeast(1)
            when {
                total >= targetBytes -> {
                    val kbps = ((total.toLong() * 1000L) / (ms.toLong() * 1024L)).toInt()
                    SustainedResult.Survived(total, ms, kbps.coerceAtLeast(1))
                }
                total > 0 -> SustainedResult.Torn(total, ms) // premature EOF or mid-stream error
                else -> SustainedResult.Failed
            }
        } catch (e: SocketTimeoutException) {
            // A timeout mid-transfer (after 2xx) reads as a teardown; a connect-time
            // timeout leaves conn.responseCode unreached and lands here too, treat as
            // failed-to-start only if nothing was read. We can't tell them apart here,
            // so be conservative: no bytes counted -> Failed.
            SustainedResult.Failed
        } catch (e: Exception) {
            SustainedResult.Failed
        } finally {
            conn?.disconnect()
        }
    }
}

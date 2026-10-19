package com.th3web.lean.data.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.coroutineContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

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

    suspend fun check(
        url: String,
        timeoutMs: Int = 5_000,
        openConnection: (URL) -> HttpURLConnection = { it.openConnection() as HttpURLConnection },
    ): CheckResult = withContext(Dispatchers.IO) {
        val started = System.nanoTime()
        try {
            coroutineContext.ensureActive()
            val conn = openConnection(URL(url)).apply {
                connectTimeout = timeoutMs
                readTimeout = timeoutMs
                requestMethod = "GET"
                instanceFollowRedirects = false
                useCaches = false
                setRequestProperty("Cache-Control", "no-cache, no-store")
                setRequestProperty("User-Agent", Http.userAgent)
            }
            try {
                val code = conn.responseCode
                coroutineContext.ensureActive()
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
        } catch (e: CancellationException) {
            throw e
        } catch (e: SocketTimeoutException) {
            CheckResult.Timeout
        } catch (e: Exception) {
            CheckResult.NoInternet
        }
    }

    fun stressUrl(bytes: Int): String = "https://speed.cloudflare.com/__down?bytes=$bytes"

    const val SUSTAINED_TARGET_BYTES = 262_144

    sealed interface SustainedResult {
        data class Survived(val bytes: Int, val ms: Int, val kbps: Int) : SustainedResult
        /** A partial transfer; this alone does not identify its cause. */
        data class Torn(val bytes: Int, val ms: Int) : SustainedResult
        data object Failed : SustainedResult
    }

    private val transferWorkers = ThreadPoolExecutor(
        0, 2, 30L, TimeUnit.SECONDS, SynchronousQueue<Runnable>(),
        { task -> Thread(task, "lean-transfer-check").apply { isDaemon = true } },
    )

    private suspend fun runTransfer(
        cleanup: () -> Unit,
        block: (isActive: () -> Boolean) -> SustainedResult,
    ): SustainedResult = suspendCancellableCoroutine { continuation ->
        try {
            val future = transferWorkers.submit {
                if (!continuation.isActive) return@submit
                try {
                    continuation.resume(block { continuation.isActive })
                } catch (error: Throwable) {
                    if (continuation.isActive) continuation.resumeWithException(error)
                } finally {
                    cleanup()
                }
            }
            // disconnect() can itself block. Cleanup stays on the worker, and
            // an uninterruptible transport cannot create an unbounded queue.
            continuation.invokeOnCancellation { future.cancel(true) }
        } catch (_: RejectedExecutionException) {
            continuation.resume(SustainedResult.Failed)
        }
    }

    /**
     * Checks payload transfer through the supplied route. The timeout covers
     * connection, response headers and body together; cancellation does not wait
     * for a blocking transport to close. No subscription identifiers are sent.
     */
    suspend fun sustainedCheck(
        url: String = stressUrl(SUSTAINED_TARGET_BYTES),
        targetBytes: Int = SUSTAINED_TARGET_BYTES,
        timeoutMs: Int = 15_000,
        openConnection: (URL) -> HttpURLConnection = { it.openConnection() as HttpURLConnection },
    ): SustainedResult {
        if (targetBytes <= 0 || timeoutMs <= 0) return SustainedResult.Failed
        val started = System.nanoTime()
        val deadline = started + timeoutMs * 1_000_000L
        val transferred = AtomicInteger()
        fun elapsedMs() = ((System.nanoTime() - started) / 1_000_000L).coerceIn(1L, Int.MAX_VALUE.toLong()).toInt()
        fun incomplete(): SustainedResult {
            val bytes = transferred.get()
            return if (bytes > 0) SustainedResult.Torn(bytes, elapsedMs()) else SustainedResult.Failed
        }
        return withTimeoutOrNull(timeoutMs.toLong()) {
            var conn: HttpURLConnection? = null
            var input: InputStream? = null
            runTransfer(cleanup = {
                try {
                    input?.close()
                } finally {
                    conn?.disconnect()
                }
            }) { isActive ->
                try {
                    if (!isActive()) return@runTransfer SustainedResult.Failed
                    val connection = openConnection(URL(url))
                    conn = connection
                    val remaining = deadline - System.nanoTime()
                    if (remaining <= 0 || !isActive()) return@runTransfer incomplete()
                    val socketTimeout = (remaining / 1_000_000L).coerceIn(1L, timeoutMs.toLong()).toInt()
                    connection.apply {
                        connectTimeout = socketTimeout
                        readTimeout = socketTimeout
                        requestMethod = "GET"
                        instanceFollowRedirects = false
                        useCaches = false
                        setRequestProperty("Cache-Control", "no-cache, no-store")
                        setRequestProperty("User-Agent", Http.userAgent)
                        setRequestProperty("Accept", "*/*")
                    }
                    val code = connection.responseCode
                    if (code != 200 && code != 206) return@runTransfer SustainedResult.Failed
                    val buf = ByteArray(16 * 1024)
                    try {
                        val stream = connection.inputStream
                        input = stream
                        while (transferred.get() < targetBytes && isActive() && System.nanoTime() < deadline) {
                            val n = stream.read(buf, 0, minOf(buf.size, targetBytes - transferred.get()))
                            if (n < 0) break
                            transferred.addAndGet(n)
                        }
                    } catch (_: IOException) {
                        // Preserve the byte count for a partial response.
                    }
                    val total = transferred.get()
                    if (total >= targetBytes && isActive() && System.nanoTime() <= deadline) {
                        val ms = elapsedMs()
                        val kbps = (total.toLong() * 1000L / (ms.toLong() * 1024L)).coerceIn(1L, Int.MAX_VALUE.toLong()).toInt()
                        SustainedResult.Survived(total, ms, kbps)
                    } else incomplete()
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    incomplete()
                }
            }
        } ?: incomplete()
    }
}

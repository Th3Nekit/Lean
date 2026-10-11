package com.th3web.lean.data.net

import android.content.Context
import android.net.Network
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import javax.net.ssl.SSLException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/**
 * «Что работает сейчас»: which services this connection can actually reach, and whether
 * the tunnel is what makes the difference.
 *
 * ## The question this answers
 *
 * Every other check in the app is about the tunnel: is it up, is it fast, does it carry
 * 16 KB. None of them answers the question a user in RU actually has, which is "does
 * YouTube work right now, and if not, is it me, my provider, or the VPN?". People answer
 * it by opening five apps in turn and guessing. This answers it in one screen, with
 * evidence.
 *
 * ## How a verdict is reached
 *
 * Each service is probed twice: once over the default route (the tunnel, while one is up)
 * and once bound to the physical network under it ([PhysicalNetwork], the same mechanism
 * [SpeedTest] and the diagnostics sender use). The pair is what makes the verdict
 * meaningful:
 *
 *  * reachable both ways → it works, VPN or not;
 *  * reachable only through the tunnel → **the provider is blocking it**, and the tunnel
 *    is doing its job;
 *  * reachable only directly → the tunnel is breaking it (a dead exit, a routing rule
 *    sending it the wrong way, a service that blocks the exit's address);
 *  * neither → it is down, or nothing on this device reaches the internet at all.
 *
 * ## Why the failure mode is recorded, not just "failed"
 *
 * A reset mid-handshake, a redirect to a provider's notice page and a timeout are three
 * different statements about the path, and the user is entitled to the distinction: the
 * first is DPI, the second is a filter that wants to be seen, the third is a route that
 * goes nowhere. [Outcome] keeps them apart.
 */
object ServiceCheck {

    /**
     * One thing to check.
     *
     * [url] is a small, stable, unauthenticated endpoint on the service's own domain —
     * a generate_204, an API health route or a favicon. It must be on the real domain:
     * probing a CDN alias would answer a question about the CDN, which is exactly the
     * mistake the XHTTP ping used to make.
     */
    data class Service(
        val id: String,
        val name: String,
        val url: String,
    )

    /** The services offered, in the order the screen lists them. */
    val services: List<Service> = listOf(
        Service("youtube", "YouTube", "https://www.youtube.com/generate_204"),
        Service("telegram", "Telegram", "https://web.telegram.org/favicon.ico"),
        Service("discord", "Discord", "https://discord.com/api/v10/gateway"),
        Service("instagram", "Instagram", "https://www.instagram.com/favicon.ico"),
        Service("chatgpt", "ChatGPT", "https://chatgpt.com/favicon.ico"),
        Service("x", "X (Twitter)", "https://x.com/favicon.ico"),
        Service("steam", "Steam", "https://api.steampowered.com/ISteamWebAPIUtil/GetServerInfo/v1/"),
        Service("twitch", "Twitch", "https://www.twitch.tv/favicon.ico"),
        Service("spotify", "Spotify", "https://open.spotify.com/favicon.ico"),
        Service("rutracker", "RuTracker", "https://rutracker.org/favicon.ico"),
    )

    /** What one probe ran into. */
    enum class Outcome {
        /** The service itself answered. */
        Reachable,
        /** The connection was torn down or refused — the DPI signature. */
        Reset,
        /** Answered, but by someone else: a redirect off-domain, i.e. a notice page. */
        Intercepted,
        /** Nothing came back in time. */
        Timeout,
        /** Could not even be attempted (no such network). */
        Skipped,
    }

    data class Probe(val outcome: Outcome, val ms: Int?) {
        val ok: Boolean get() = outcome == Outcome.Reachable
    }

    /** One service, both ways. */
    data class Status(
        val service: Service,
        val viaTunnel: Probe,
        val direct: Probe?,
    ) {
        /** The sentence the row states. */
        val verdict: Verdict
            get() = when {
                viaTunnel.ok && direct?.ok == true -> Verdict.WorksEitherWay
                viaTunnel.ok && direct == null -> Verdict.Works
                viaTunnel.ok -> Verdict.BlockedWithoutVpn
                direct?.ok == true -> Verdict.BrokenByTunnel
                else -> Verdict.Unreachable
            }
    }

    enum class Verdict {
        /** Works, and there was no second route to compare with. */
        Works,
        /** Works with or without the tunnel. */
        WorksEitherWay,
        /** Works only through the tunnel: the provider blocks it. */
        BlockedWithoutVpn,
        /** Works only without the tunnel: the tunnel is the problem. */
        BrokenByTunnel,
        /** Neither route reached it. */
        Unreachable,
    }

    /**
     * Checks every service in [list], reporting each result through [onResult] as it
     * lands rather than once at the end: ten services over a mobile link take seconds,
     * and a screen that shows nothing for seconds reads as broken.
     *
     * [MAX_PARALLEL] at a time — enough to finish quickly, few enough that the probes do
     * not themselves become the congestion they are measuring.
     */
    suspend fun checkAll(
        context: Context,
        list: List<Service> = services,
        includeDirect: Boolean = true,
        timeoutMs: Int = TIMEOUT_MS,
        onResult: (Status) -> Unit = {},
    ): List<Status> = coroutineScope {
        // Resolved once: enumerating networks per probe would be ten identical lookups,
        // and a null here is the ordinary "no tunnel is up" case.
        val physical = if (includeDirect) PhysicalNetwork.find(context) else null
        val gate = Semaphore(MAX_PARALLEL)
        list.map { service ->
            async(Dispatchers.IO) {
                gate.withPermit {
                    val viaTunnel = probe(null, service.url, timeoutMs)
                    val direct = physical?.let { probe(it, service.url, timeoutMs) }
                    Status(service, viaTunnel, direct).also(onResult)
                }
            }
        }.awaitAll()
    }

    /**
     * One request, timed, with the failure classified.
     *
     * `instanceFollowRedirects = false` is load-bearing: a provider's notice page is
     * served as a 302 to a different host, and following it would turn an interception
     * into a cheerful 200. A redirect that stays on the service's own domain is normal
     * (www → m, http → https) and counts as reachable.
     *
     * The User-Agent is a browser's. These endpoints are hit by browsers all day; an
     * unfamiliar client UA earns a 403 from several of them, which would read as a block
     * that is not there.
     */
    private suspend fun probe(network: Network?, url: String, timeoutMs: Int): Probe =
        withContext(Dispatchers.IO) {
            val started = System.nanoTime()
            var conn: HttpURLConnection? = null
            try {
                val target = URL(url)
                conn = (network?.openConnection(target) ?: target.openConnection())
                    as HttpURLConnection
                conn.apply {
                    connectTimeout = timeoutMs
                    readTimeout = timeoutMs
                    requestMethod = "GET"
                    instanceFollowRedirects = false
                    setRequestProperty("User-Agent", BROWSER_UA)
                    setRequestProperty("Accept", "*/*")
                }
                val code = conn.responseCode
                val ms = ((System.nanoTime() - started) / 1_000_000L).toInt().coerceAtLeast(1)
                when {
                    code in 300..399 -> {
                        val location = conn.getHeaderField("Location")
                        val sameSite = location == null ||
                            runCatching { URL(URL(url), location).host }.getOrNull()
                                ?.endsWith(registrableSuffix(target.host)) == true
                        if (sameSite) Probe(Outcome.Reachable, ms) else Probe(Outcome.Intercepted, ms)
                    }
                    // Any answer from the service itself proves the path: a 404 on a
                    // favicon or a 401 from an API is the service talking.
                    code in 200..599 -> Probe(Outcome.Reachable, ms)
                    else -> Probe(Outcome.Reset, ms)
                }
            } catch (_: SocketTimeoutException) {
                Probe(Outcome.Timeout, null)
            } catch (_: SSLException) {
                // A handshake that dies mid-way is the classic DPI teardown; a forged
                // certificate lands here too, and both mean "someone is in the middle".
                Probe(Outcome.Reset, null)
            } catch (_: IOException) {
                Probe(Outcome.Reset, null)
            } catch (_: Throwable) {
                Probe(Outcome.Reset, null)
            } finally {
                runCatching { conn?.disconnect() }
            }
        }

    /**
     * The last two labels of a host, so `m.youtube.com` and `www.youtube.com` compare
     * equal. Deliberately crude: this only has to tell "still the same service" from
     * "a provider's notice page on an unrelated domain", and a public-suffix list would
     * be a dependency for nothing.
     */
    private fun registrableSuffix(host: String): String =
        host.split('.').takeLast(2).joinToString(".")

    /**
     * A current Chrome-on-Android string. It is what these endpoints expect to see, and
     * nothing here is authenticated, so there is no identity in it beyond "a browser".
     */
    private const val BROWSER_UA =
        "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/131.0.0.0 Mobile Safari/537.36"

    /** Four at a time: fast enough to finish in a few seconds, not a burst of its own. */
    private const val MAX_PARALLEL = 4

    /**
     * Per probe. Short on purpose: a service that needs more than four seconds to answer
     * a favicon is not working, whatever the reason, and ten services behind a long
     * timeout would make the screen feel stuck.
     */
    private const val TIMEOUT_MS = 4_000
}

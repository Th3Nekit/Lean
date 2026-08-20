package com.th3web.lean.data.net

import android.os.Build
import android.util.Log
import com.th3web.lean.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.Locale

/** Minimal HTTP GET used for subscription fetch (no external dependency). */
object Http {
    private const val TAG = "Http"
    // Subscription panels often 301 http->https; HttpURLConnection refuses to cross
    // schemes even with instanceFollowRedirects=true, so getFull follows manually,
    // bounded by a hop count + a total deadline.
    private const val MAX_REDIRECTS = 5
    private const val TOTAL_DEADLINE_MS = 45_000L

    /** Body + lower-cased response headers (for profile-web-page-url etc.). */
    data class Response(val body: String, val headers: Map<String, String>)

    /** Set once at startup ([com.th3web.lean.LeanApp]); sent with every request. */
    @Volatile var hwid: String = ""
    @Volatile var userAgent: String = "Lean/${BuildConfig.VERSION_NAME}"
    @Volatile var deviceLabel: String = ""

    /**
     * Opt-in gate for the x-hwid header quartet (Incy `send_hwid` pref); mirrored
     * from Settings.sendHwid by LeanApp. Does not affect the {hwid} URL token,
     * a token in the URL is explicit user/panel intent.
     *
     * Assumption (unverified against the real th3web panel): the header name
     * (`x-hwid`), whether the whole quartet (x-hwid/x-device-os/x-ver-os/
     * x-device-model) is required, the HWID derivation (see [com.th3web.lean.data.HwId])
     * and the {hwid}/{HWID} URL token are all Lean's own guess at the panel's
     * gating contract. If the panel expects a different name/format, device
     * binding & quota limits silently no-op and the subscription may come back
     * empty/truncated without an error. The diagnostic log emitted by [getFull]
     * (UA + whether x-hwid was sent + response format/length) makes such a
     * silent truncation visible; confirm the contract with a live test account
     * and pin it here once known.
     */
    @Volatile var sendHwid: Boolean = true

    /** App version for x-app-version; set in LeanApp next to hwid/userAgent. */
    @Volatile var appVersion: String = ""

    /**
     * Client-specific extra request headers set by the active spoof profile
     * (e.g. Happ's `X-Bundle-ID` / `X-API-Version`); empty for Lean/custom UAs.
     * Mirrored from [com.th3web.lean.data.ClientSpoof] by LeanApp on every settings
     * change. Applied last so a spoof header can add to (never silently break) the
     * fixed identity set.
     */
    @Volatile var extraHeaders: Map<String, String> = emptyMap()

    suspend fun get(url: String): Result<String> = getFull(url).map { it.body }

    /** Apply the full header set + UA to a freshly opened connection, identical on every hop. */
    private fun HttpURLConnection.applyHeaders(ua: String, hwidSent: Boolean) {
        connectTimeout = 15_000
        readTimeout = 15_000
        // Follow redirects manually (HttpURLConnection won't cross http<->https even with
        // instanceFollowRedirects=true, which silently failed panels that 301 http->https):
        // keep the platform follower OFF so every 3xx surfaces to our loop.
        instanceFollowRedirects = false
        setRequestProperty("User-Agent", ua)
        setRequestProperty("Accept", "*/*")
        setRequestProperty("Accept-Language", Locale.getDefault().toLanguageTag()) // "ru-RU"
        setRequestProperty("x-client", "LEAN")
        setRequestProperty("x-device-locale", Locale.getDefault().toString())      // "ru_RU"
        if (appVersion.isNotBlank()) setRequestProperty("x-app-version", appVersion)
        if (hwidSent) {
            setRequestProperty("x-hwid", hwid)
            setRequestProperty("x-device-os", "Android")
            setRequestProperty("x-ver-os", Build.VERSION.RELEASE ?: "")
            if (deviceLabel.isNotBlank()) setRequestProperty("x-device-model", deviceLabel)
        }
        // Active spoof profile's client-specific headers (e.g. Happ X-Bundle-ID).
        extraHeaders.forEach { (k, v) -> setRequestProperty(k, v) }
    }

    /**
     * [userAgentOverride]: one-off User-Agent for this request only (the UA-fallback
     * retry in ProfileRepository.fetchSub); null -> the global [userAgent]. The
     * x-hwid quartet and all other headers are sent identically either way.
     *
     * Follows http<->https (and same-scheme) redirects manually, bounded by
     * [MAX_REDIRECTS] + [TOTAL_DEADLINE_MS]. The non-redirect path returns on the first
     * 2xx hop.
     */
    suspend fun getFull(url: String, userAgentOverride: String? = null): Result<Response> =
        withContext(Dispatchers.IO) { runCatching { fetch(url, userAgentOverride) } }

    private fun fetch(url: String, userAgentOverride: String?): Response {
        val ua = userAgentOverride ?: userAgent
        val identitySent = sendHwid && hwid.isNotBlank()
        // Fragment is client-side only (Incy pre-request rule): strip before fetching.
        // Allow the panel to embed the hwid in the path/query via a {hwid} token.
        var currentUrl = url.substringBefore('#').replace("{hwid}", hwid).replace("{HWID}", hwid)
        // The host the user actually asked for. The identity headers below describe the
        // device (a stable hardware id, the OS version, the model), and a redirect can
        // point anywhere at all, so they follow it only while it stays on that
        // host. The case they exist for is unaffected: a panel answering http:// with a
        // 301 to its own https:// keeps the same host, and so keeps its gating headers.
        val originHost = runCatching { URL(currentUrl).host }.getOrNull().orEmpty()
        val deadline = System.currentTimeMillis() + TOTAL_DEADLINE_MS
        var hops = 0
        while (true) {
            if (System.currentTimeMillis() >= deadline) error("redirect timeout")
            // Clamp each hop's connect/read timeout to the remaining total budget so a
            // panel that stalls near the per-op timeout on every hop can't blow past
            // TOTAL_DEADLINE_MS (the deadline check above only fires between hops).
            val remaining = (deadline - System.currentTimeMillis()).coerceAtLeast(1L).toInt()
            val onOriginHost = originHost.isNotEmpty() &&
                runCatching { URL(currentUrl).host }.getOrNull().equals(originHost, ignoreCase = true)
            val sendIdentity = identitySent && onOriginHost
            val conn = (URL(currentUrl).openConnection() as HttpURLConnection).apply {
                applyHeaders(ua, sendIdentity)
                connectTimeout = minOf(connectTimeout, remaining)
                readTimeout = minOf(readTimeout, remaining)
            }
            try {
                val code = conn.responseCode
                // Follow http<->https (and same-scheme) redirects manually, re-sending the
                // header set (incl. the x-hwid the panel may gate on) on every hop that
                // stays on the host the user asked for.
                if (code in 300..399 && code != 304) {
                    val location = conn.getHeaderField("Location") ?: error("HTTP $code (no Location)")
                    if (++hops > MAX_REDIRECTS) error("too many redirects")
                    currentUrl = URL(URL(currentUrl), location).toString() // resolve relative Location
                    continue
                }
                if (code !in 200..299) error("HTTP $code")
                // Byte-safe read: strict UTF-8 first; on failure fall back to ISO-8859-1,
                // which maps bytes 1:1 to chars, reversible via toByteArray(ISO_8859_1),
                // so binary bodies (e.g. "gzip:" subscription payloads) survive losslessly.
                val bytes = conn.inputStream.use { it.readBytes() }
                val body = runCatching {
                    Charsets.UTF_8.newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT)
                        .decode(ByteBuffer.wrap(bytes)).toString()
                }.getOrElse { String(bytes, Charsets.ISO_8859_1) }
                val headers = buildMap {
                    conn.headerFields.forEach { (k, v) ->
                        if (k != null && v.isNotEmpty()) put(k.lowercase(), v.first())
                    }
                }
                // Diagnostic for the UA-gating / HWID assumptions: the UA presented,
                // whether x-hwid travelled, the HTTP code, body length, a coarse format
                // sniff, and the redirect hop count. BOM (U+FEFF) is not isWhitespace,
                // so strip it before sniffing (same guard Subscriptions uses).
                val sniffed = body.removePrefix("\uFEFF").trimStart().firstOrNull()
                val format = when (sniffed) {
                    '[', '{' -> "json"
                    null -> "empty"
                    else -> "base64/text"
                }
                Log.i(
                    TAG,
                    "subscription fetch: ua='$ua' hwid=${if (sendIdentity) "sent" else "off"} " +
                        "code=$code bodyLen=${body.length} format=$format hops=$hops",
                )
                return Response(body, headers)
            } finally {
                conn.disconnect()
            }
        }
    }
}

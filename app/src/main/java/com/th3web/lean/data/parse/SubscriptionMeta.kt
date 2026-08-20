package com.th3web.lean.data.parse

/**
 * Subscription metadata scraping, Incy's response-header + body-directive rules
 * in one place. The header map handed in here is already lower-cased by
 * [com.th3web.lean.data.net.Http.getFull]. Header values win; body `#…:`
 * directives ([BodyDirectives]) fill any gaps (see [withFallback]).
 */

/** Parsed `subscription-userinfo` header. All values bytes; expire = Unix seconds. */
data class SubscriptionUserInfo(
    val upload: Long?,
    val download: Long?,
    val total: Long?,
    val expire: Long?,
)

/** Everything scraped from response headers, with body-directive fallback merged in. */
data class SubscriptionMeta(
    val title: String? = null,
    /** profile-description header, or the '|' tail of profile-title (Incy subtitle). */
    val description: String? = null,
    val updateIntervalMs: Long? = null,
    val webPageUrl: String? = null,
    val supportUrl: String? = null,
    val announce: String? = null,
    val userInfo: SubscriptionUserInfo? = null,
)

/** Directives extracted from `#…:` comment lines in the body. */
data class BodyDirectives(
    val title: String? = null,
    /** '|' tail / extra lines of #profile-title: (Incy subtitle rule). */
    val description: String? = null,
    val updateIntervalMs: Long? = null,
    val webPageUrl: String? = null,
    val supportUrl: String? = null,
    val announce: String? = null,
)

/** "base64:<payload>" values used by profile-title / announce. */
fun maybeBase64(v: String): String {
    val t = v.trim()
    return if (t.startsWith("base64:", ignoreCase = true))
        decodeBase64Tolerant(t.substring(7)) ?: t
    else t
}

/**
 * Normalize an expiry timestamp to Unix seconds, the unit [Profile.expireEpochSec]
 * (and its daysLeft/isExpired math) expects. Single source of truth so the
 * `subscription-userinfo` `expire=` and the standalone `profile-expire` header
 * agree exactly.
 *  - <= 0  → null (expire=0 / negative == "no expiry"; absent never reaches here);
 *  - > 32_000_000_000 → treated as epoch-MILLIseconds, divided by 1000 → seconds
 *    (32e9 s ≈ year 3983, so any larger value is unambiguously ms, never seconds).
 */
fun normalizeExpire(n: Long?): Long? = when {
    n == null -> null
    n <= 0L -> null
    n > 32_000_000_000L -> n / 1000
    else -> n
}

/**
 * Parse the `subscription-userinfo` header. Incy rules, exactly:
 *  - whole value trimmed == "0" → reject (panels send a bare "0" for "no data").
 *    This is the whole header equalling "0"; a `total=0` *inside* the
 *    key=value pairs is distinct and means "unlimited" (handled downstream where
 *    total == 0 renders as ∞). A panel that sends just "total=0" (no other keys)
 *    therefore parses as a valid unlimited plan, not as no-data. (assumption to
 *    confirm against th3web's live header: bare "0" == no-data, "total=0" within
 *    pairs == unlimited.)
 *  - split ';' → split '=' (limit 2) → lowercase key, trim value, toLongOrNull;
 *  - expire: normalized via [normalizeExpire] (<=0 → null; ms → seconds).
 */
fun parseUserInfo(raw: String?): SubscriptionUserInfo? {
    val v = raw?.trim() ?: return null
    if (v.isEmpty() || v == "0") return null
    var up: Long? = null
    var down: Long? = null
    var total: Long? = null
    var exp: Long? = null
    v.split(';').forEach { part ->
        val kv = part.split('=', limit = 2)
        if (kv.size != 2) return@forEach
        val n = kv[1].trim().toLongOrNull() ?: return@forEach
        when (kv[0].trim().lowercase()) {
            "upload" -> up = n
            "download" -> down = n
            "total" -> total = n
            "expire" -> exp = normalizeExpire(n)
        }
    }
    return if (up != null || down != null || total != null || exp != null)
        SubscriptionUserInfo(up, down, total, exp) else null
}

/** Build [SubscriptionMeta] from already-lower-cased response headers. */
fun parseSubscriptionHeaders(h: Map<String, String>): SubscriptionMeta {
    // profile-title may carry "Title|Subtitle" (Incy): '|' head → title, tail → description.
    val rawTitle = (h["profile-title"] ?: h["subscription-name"])?.let { maybeBase64(it) }
    // Some panels report expiry only via a standalone `profile-expire` header
    // (Unix seconds or ms), separate from subscription-userinfo's `expire=`. Fold
    // it in so a date still shows. The userinfo `expire=` always wins when present;
    // profile-expire fills the gap. If userinfo carried bytes but no expire, we
    // keep those bytes and just add the expire, userInfo stays header-sourced.
    val userInfo = parseUserInfo(h["subscription-userinfo"])
    val headerExpire = normalizeExpire(h["profile-expire"]?.trim()?.toLongOrNull())
    val mergedUserInfo = when {
        userInfo == null && headerExpire == null -> null
        userInfo == null -> SubscriptionUserInfo(null, null, null, headerExpire)
        userInfo.expire == null -> userInfo.copy(expire = headerExpire)
        else -> userInfo
    }
    return SubscriptionMeta(
        title = rawTitle?.substringBefore('|')?.trim()?.ifEmpty { null },
        description = h["profile-description"]?.let { maybeBase64(it) }?.trim()?.ifEmpty { null }
            ?: rawTitle?.substringAfter('|', "")?.trim()?.ifEmpty { null },
        updateIntervalMs = h["profile-update-interval"]?.trim()?.toLongOrNull()
            ?.takeIf { it > 0 }?.times(3_600_000L), // header value is HOURS
        webPageUrl = (h["profile-web-page-url"] ?: h["homepage"])?.trim()?.ifEmpty { null },
        supportUrl = h["support-url"]?.trim()?.ifEmpty { null },
        announce = h["announce"]?.let { maybeBase64(it) }?.trim()?.ifEmpty { null },
        userInfo = mergedUserInfo,
    )
}

/** Header wins; body directive fills gaps. userInfo only ever comes from headers. */
fun SubscriptionMeta.withFallback(d: BodyDirectives) = SubscriptionMeta(
    title = title ?: d.title,
    description = description ?: d.description,
    updateIntervalMs = updateIntervalMs ?: d.updateIntervalMs,
    webPageUrl = webPageUrl ?: d.webPageUrl,
    supportUrl = supportUrl ?: d.supportUrl,
    announce = announce ?: d.announce,
    userInfo = userInfo,
)

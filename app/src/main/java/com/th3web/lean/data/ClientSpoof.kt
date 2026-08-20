package com.th3web.lean.data

import android.content.Context

/**
 * Resolves a stored UA-preset token (`Settings.userAgent`) into the full spoof
 * identity presented on subscription requests: the wire User-Agent, a
 * client-shaped hwid, and any client-specific extra headers.
 *
 * Panels gate/format the returned server list by both User-Agent and hardware id,
 * so impersonating a client convincingly means matching its id shape too (not the
 * raw ANDROID_ID). Tokens:
 *  - ""                       → Lean default UA + Lean/v2rayTun-shaped hex hwid
 *  - "happ:<platform>"        → `Happ/<ver>/<platform>/<uaId>` + Happ-shaped hwid +
 *                               Happ's X-Bundle-ID / X-API-Version headers.
 *                               <platform> ∈ Android | ios | Windows, the segments a
 *                               panel's request log actually contains, each with its own
 *                               version and id length.
 *  - "v2raytun"               → v2rayTun UA + upper-hex-16 hwid
 *  - any other literal        → sent verbatim (custom / v2rayNG / Hiddify / …) with
 *                               the default hex hwid
 *
 * Happ sends `Happ/<ver>/<platform>/<id>` with the headers X-HWID, X-Bundle-ID,
 * X-Device-OS, X-Ver-OS, X-Device-model and X-API-Version, as a panel's request log
 * shows them. Lean already sends the x-device-os/x-ver-os/x-device-model trio; only
 * X-Bundle-ID and X-API-Version are added here. HTTP header names are case-insensitive,
 * so Lean's lower-case `x-hwid` matches Happ's `X-HWID`.
 */
object ClientSpoof {
    /**
     * Happ's version and its id length, per platform, both taken from a panel's real
     * request log rather than guessed.
     *
     * Happ does not ship one version across platforms: the same week showed Android on
     * 4.1.0, iOS on 5.2.0 and Windows on 3.3.6. A single constant therefore described no
     * real client at all, and the id length differs too (see [HwId.happUaId]).
     */
    private val HAPP_PLATFORMS = mapOf(
        "Android" to HappShape(version = "4.1.0", idDigits = 20),
        "ios" to HappShape(version = "5.2.0", idDigits = 13),
        "Windows" to HappShape(version = "3.3.6", idDigits = 13),
    )

    /**
     * Platforms this build no longer offers, mapped to the ones it does.
     *
     * Earlier releases offered `chrome-android` / `chrome-win` / `chrome-linux`, and those
     * tokens are still sitting in settings on upgraded installs. No panel log contains any
     * of them, so leaving them alone would keep emitting a UA that matches nothing, the
     * exact opposite of what picking a spoof is for. Mapping them to the nearest real
     * platform keeps the user's intent (mobile vs desktop), and produces a UA a panel can
     * actually recognise.
     */
    private val HAPP_LEGACY_PLATFORMS = mapOf(
        "chrome-android" to "Android",
        "chrome-win" to "Windows",
        "chrome-linux" to "Windows",
    )

    /** Anything else unknown: the most common platform, so the UA is never unmatched. */
    private const val HAPP_DEFAULT_PLATFORM = "Android"

    private data class HappShape(val version: String, val idDigits: Int)

    /**
     * v2rayTun sends the platform, not a version, `v2raytun/android`, verbatim. A
     * plausible-looking `v2rayTun/3.0.0` matches nothing a panel has ever been sent.
     */
    private const val V2RAYTUN_UA = "v2raytun/android"

    data class Resolved(
        val userAgent: String,
        val hwid: String,
        val extraHeaders: Map<String, String>,
    )

    /** Just the wire User-Agent for a stored token (used for UI display). Empty = [defaultUa]. */
    fun resolveUa(token: String, defaultUa: String, context: Context): String = when {
        token.startsWith("happ:") -> {
            val stored = token.substringAfter("happ:")
            // Normalise before building the string: an unknown segment would travel into
            // the UA verbatim and match nothing on the far side.
            val platform = HAPP_LEGACY_PLATFORMS[stored]
                ?: stored.takeIf { it in HAPP_PLATFORMS }
                ?: HAPP_DEFAULT_PLATFORM
            val shape = requireNotNull(HAPP_PLATFORMS[platform])
            "Happ/${shape.version}/$platform/${HwId.happUaId(context, shape.idDigits)}"
        }
        token == "v2raytun" -> V2RAYTUN_UA
        token.isEmpty() -> defaultUa
        else -> token
    }

    /** Full spoof identity: UA + client-shaped hwid + client-specific extra headers. */
    fun resolve(token: String, defaultUa: String, context: Context): Resolved = when {
        token.startsWith("happ:") -> Resolved(
            userAgent = resolveUa(token, defaultUa, context),
            hwid = HwId.happHwid(context),
            extraHeaders = mapOf(
                "X-Bundle-ID" to "su.happ.proxyutility",
                "X-API-Version" to "1.0",
            ),
        )
        token == "v2raytun" -> Resolved(V2RAYTUN_UA, HwId.get(context), emptyMap())
        else -> Resolved(resolveUa(token, defaultUa, context), HwId.get(context), emptyMap())
    }
}

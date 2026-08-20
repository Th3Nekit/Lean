package com.th3web.lean.ui.screen

import com.th3web.lean.core.VpnState
import java.util.Locale

internal const val TELEGRAM_BOT_TITLE = "Lean VPN в Telegram"
internal const val TELEGRAM_BOT_BODY =
    "Оформите подписку и управляйте доступом в боте."
internal const val TELEGRAM_BOT_CTA = "Открыть бота"
internal const val TELEGRAM_BOT_URL = "https://t.me/VPN_Lean_bot"

internal enum class PublicConnectionError(val messageKey: String) {
    VpnPermission("Разрешите VPN-подключение"),
    ServerSettings("Проверьте настройки сервера"),
    Network("Не удалось связаться с сервером"),
    Connection("Не удалось подключиться"),
}

internal fun publicConnectionError(rawMessage: String): PublicConnectionError {
    val normalized = rawMessage.lowercase(Locale.ROOT)
    return when {
        listOf("permission", "vpnservice.prepare", "vpn permission", "user denied")
            .any(normalized::contains) -> PublicConnectionError.VpnPermission

        listOf("invalid", "config", "outbound", "profile", "unsupported", "parse")
            .any(normalized::contains) -> PublicConnectionError.ServerSettings

        listOf(
            "network",
            "timeout",
            "timed out",
            "dns",
            "lookup",
            "resolve",
            "unreachable",
            "socket",
            "route",
            "handshake",
            "tls",
            "dial",
        ).any(normalized::contains) -> PublicConnectionError.Network

        else -> PublicConnectionError.Connection
    }
}

internal enum class ConnectHeroMotion {
    Static,
    Connecting,
    Connected,
}

/**
 * Which frame driver, if any, the connect hero should run.
 *
 * [animationsEnabled] is the resolved answer, «Анимации» combined with the
 * system animator scale (see `motionAllowed()`), not the platform flag on its
 * own. That distinction is the whole of knob 8.2: the system setting is a floor
 * the user may lift, so a battery-saver ROM no longer decides here on their
 * behalf. Keeping the decision in one pure function is why the hero's three
 * frame drivers can never disagree about whether motion is on.
 */
internal fun connectHeroMotion(
    state: VpnState,
    animationsEnabled: Boolean,
): ConnectHeroMotion {
    if (!animationsEnabled) return ConnectHeroMotion.Static
    return when (state) {
        is VpnState.Connecting, is VpnState.Stopping -> ConnectHeroMotion.Connecting
        is VpnState.Connected -> ConnectHeroMotion.Connected
        is VpnState.Disconnected, is VpnState.Error -> ConnectHeroMotion.Static
    }
}

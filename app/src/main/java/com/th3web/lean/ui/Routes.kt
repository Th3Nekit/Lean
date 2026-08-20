package com.th3web.lean.ui

/**
 * Navigation routes. Home is the root; Servers and Settings are pushed from
 * Home's top-bar actions. Settings funnels into four consolidated hubs
 * (appearance / connection / provider / about), each of which deep-pushes the
 * live sub-screens (language, DNS, IP type, per-app, ping, backup, logs, about).
 */
object Routes {
    const val HOME = "home"
    const val SERVERS = "servers"
    const val SETTINGS = "settings"

    const val LOGS = "logs"
    /** Builder for an olcRTC server, the one protocol with no host:port to type. */
    const val OLCRTC_NEW = "servers/olcrtc/new"
    const val ABOUT = "about"

    /** The bundled licence texts, reachable from «О программе». */
    const val LICENSES = "about/licenses"

    // Settings hubs (Lean redesign, 4 consolidated hubs)
    const val HUB_APPEARANCE = "settings/appearance"
    const val HUB_CONNECTION = "settings/connection_hub"
    const val HUB_PROVIDER = "settings/provider_hub"
    const val HUB_ABOUT = "settings/about_hub"

    // «Оформление» detail screens, pushed from the appearance hub. The tab root owns the
    // look as a whole (preset, theme, accent); each of these owns one section of it.
    const val APPEARANCE_COLOR = "settings/appearance/color"
    const val APPEARANCE_FONTS = "settings/appearance/fonts"
    const val APPEARANCE_SHAPE = "settings/appearance/shape"
    const val APPEARANCE_HOME = "settings/appearance/home"
    const val APPEARANCE_SERVERS = "settings/appearance/servers"
    const val APPEARANCE_MOTION = "settings/appearance/motion"
    const val APPEARANCE_SYSTEM = "settings/appearance/system"
    const val APPEARANCE_LAB = "settings/appearance/lab"
    const val APPEARANCE_ROLES = "settings/appearance/roles"

    // Settings sub-screens
    const val LANGUAGE = "settings/language"
    const val PER_APP = "settings/per_app"
    const val DNS = "settings/dns"
    const val IP_TYPE = "settings/ip_type"
    const val PING = "settings/ping"
    const val BACKUP = "settings/backup"
    const val RULE_SETS = "settings/rule_sets"
    const val TUN_STACK = "settings/tun_stack"
}

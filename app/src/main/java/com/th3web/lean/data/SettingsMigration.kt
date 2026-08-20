package com.th3web.lean.data

import androidx.datastore.core.DataMigration
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey

internal object SettingsKeys {
    val SCHEMA_VERSION = intPreferencesKey("settings_schema_version")
    val SELECTED = stringPreferencesKey("selected_profile_id")
    val ROUTING = stringPreferencesKey("routing_mode")
    val BYPASS_LAN = booleanPreferencesKey("bypass_lan")
    val SERVICE_MODE = stringPreferencesKey("service_mode")
    val PROXY_PORT = intPreferencesKey("proxy_port")
    val PROXY_ALLOW_LAN = booleanPreferencesKey("proxy_allow_lan")
    val IPV6 = booleanPreferencesKey("ipv6")
    val TUN_STACK = stringPreferencesKey("tun_stack")
    val TUN_MTU = intPreferencesKey("tun_mtu")
    val WG_MTU = intPreferencesKey("wireguard_mtu")
    val SNIFF_ENABLED = booleanPreferencesKey("sniff_enabled")
    val SNIFF_OVERRIDE_DESTINATION = booleanPreferencesKey("sniff_override_destination")
    val SNIFF_RESOLVE_DESTINATION = booleanPreferencesKey("sniff_resolve_destination")
    val DNS_ROUTING = booleanPreferencesKey("dns_routing")
    val FAKE_DNS = booleanPreferencesKey("fake_dns")
    val RESET_CONNECTIONS_ON_NETWORK_CHANGE = booleanPreferencesKey("reset_connections_on_network_change")
    val ALLOW_INSECURE = booleanPreferencesKey("allow_insecure")
    val REMOTE_DNS = stringPreferencesKey("remote_dns")
    val DIRECT_DNS = stringPreferencesKey("direct_dns")
    val ACCENT = longPreferencesKey("accent_color")
    val THEME_MODE = stringPreferencesKey("theme_mode")
    val LANGUAGE = stringPreferencesKey("language")
    val PERAPP_MODE = stringPreferencesKey("perapp_mode")
    val PERAPP_PKGS = stringSetPreferencesKey("perapp_packages")
    val AUTO_CONNECT = booleanPreferencesKey("auto_connect")
    val AUTO_FAILOVER = booleanPreferencesKey("auto_failover")
    val LOG_LEVEL = stringPreferencesKey("log_level")
    val KILL_SWITCH = booleanPreferencesKey("kill_switch")
    val DOZE_PAUSE = booleanPreferencesKey("doze_pause")
    val BATTERY_WARNING_HIDDEN = booleanPreferencesKey("battery_warning_hidden")
    val MUX = booleanPreferencesKey("mux")
    val FRAGMENT = booleanPreferencesKey("fragment")
    val AUTO_UPDATE = booleanPreferencesKey("auto_update_sub")
    val CHECK_APP_UPDATES = booleanPreferencesKey("check_app_updates")
    val SEND_HWID = booleanPreferencesKey("send_hwid")
    val PING_PROTOCOL = stringPreferencesKey("ping_protocol")
    val PING_URL = stringPreferencesKey("ping_url")
    val PING_TIMEOUT = intPreferencesKey("ping_timeout")
    val ACTIVE_CONNECTION_TEST_TIMEOUT = intPreferencesKey("active_connection_test_timeout")
    val IP_STRATEGY = stringPreferencesKey("ip_strategy")
    val SERVER_SORT = stringPreferencesKey("server_sort_order")
    val PING_ON_LAUNCH = booleanPreferencesKey("ping_on_launch")
    val PING_ON_UPDATE = booleanPreferencesKey("ping_on_update")
    val BG_REFRESH_MIN = intPreferencesKey("auto_update_interval")
    val SHOW_SPEED_NOTIF = booleanPreferencesKey("show_speed_notification")
    val USER_AGENT = stringPreferencesKey("subscription_user_agent")
    val APP_ICON = stringPreferencesKey("app_icon")
    val CRASH_REPORTING = booleanPreferencesKey("crash_reporting")
    val TCP_FAST_OPEN = booleanPreferencesKey("tcp_fast_open")
    val UTLS_FP = stringPreferencesKey("utls_fingerprint")
    val SNI_OVERRIDE = stringPreferencesKey("sni_override")
    val RU_DIRECT = booleanPreferencesKey("ru_direct")
    val CUSTOM_RULE_SETS = stringSetPreferencesKey("custom_rule_sets")
    val CUSTOM_RULE_SETS_STR = stringPreferencesKey("custom_rule_sets_v2")

    // ---- «Оформление» ----
    //
    // A key's name and its TYPE are both permanent once shipped. `p[key]` casts, and a
    // ClassCastException raised inside the repository's `.map {}` is not an IOException,
    // so the `.catch` there rethrows, the Eagerly-started stateIn never restarts its
    // completed upstream, and settings stay unreadable for the rest of the process. The
    // only survivable fix is a second key of the new type read alongside the old one,
    // which is what the CUSTOM_RULE_SETS/CUSTOM_RULE_SETS_STR pair is.
    //
    // Every scalar here is an Int (percent or step), never a Float: the appearance spec
    // is compared structurally to suppress recomposition, and integers make that
    // comparison exact instead of nearly-exact.
    val APPEARANCE_PREVIEW = booleanPreferencesKey("appearance_preview")
    val APPEARANCE_PRESET = stringPreferencesKey("appearance_preset")
    val CUSTOM_PRESETS = stringPreferencesKey("custom_presets_json")
    val ACCENT_RECENT = stringPreferencesKey("accent_recent")
    val THEME_SCHEDULE = booleanPreferencesKey("theme_schedule")
    val THEME_SCHED_FROM = intPreferencesKey("theme_sched_from")
    val THEME_SCHED_TO = intPreferencesKey("theme_sched_to")
    val THEME_SCHED_MODE = stringPreferencesKey("theme_sched_mode")
    val CONTRAST_LEVEL = intPreferencesKey("contrast_level")
    val AMOLED_DEPTH = stringPreferencesKey("amoled_depth")
    val AMOLED_TINT = booleanPreferencesKey("amoled_tint")
    val ACCENT_SOURCE = stringPreferencesKey("accent_source")
    val ACCENT_CHROMA = intPreferencesKey("accent_chroma")
    val SURFACE_TINT = intPreferencesKey("surface_tint")
    val CONNECTED_MODE = stringPreferencesKey("connected_mode")
    val ERROR_COLOR = stringPreferencesKey("error_color")
    val WORDMARK_ACCENT = booleanPreferencesKey("wordmark_accent")
    val ROLE_OVERRIDES = stringPreferencesKey("role_overrides_json")
    val FONT_DISPLAY = stringPreferencesKey("font_display")
    val FONT_BODY = stringPreferencesKey("font_body")
    val TEXT_SCALE = intPreferencesKey("text_scale")
    val FONT_WEIGHT_DELTA = intPreferencesKey("font_weight_delta")
    val TABULAR_NUMS = booleanPreferencesKey("tabular_nums")
    val SECTION_CAPS = booleanPreferencesKey("section_caps")
    val CORNER_STYLE = stringPreferencesKey("corner_style")
    val UI_DENSITY = stringPreferencesKey("ui_density")
    val OUTLINE_WEIGHT = stringPreferencesKey("outline_weight")
    val SHOW_DIVIDERS = booleanPreferencesKey("show_dividers")
    val DIVIDER_INDENT = stringPreferencesKey("divider_indent")
    val CARD_SHADOW = stringPreferencesKey("card_shadow")
    val HERO_STYLE = stringPreferencesKey("hero_style")
    val HERO_SIZE = intPreferencesKey("hero_size")
    val HERO_GLYPH = stringPreferencesKey("hero_glyph")
    val HERO_BREATH = booleanPreferencesKey("hero_breath")
    val HERO_FLOATING = booleanPreferencesKey("hero_floating")
    val TRAFFIC_ROW = stringPreferencesKey("traffic_row")
    val QUICK_PEEK = intPreferencesKey("quick_peek")
    val HOME_BLOCKS = intPreferencesKey("home_blocks")
    val CUR_SRV_LABEL = stringPreferencesKey("cur_srv_label")
    val LATENCY_PALETTE = stringPreferencesKey("latency_palette")
    val LAT_T1 = intPreferencesKey("lat_t1")
    val LAT_T2 = intPreferencesKey("lat_t2")
    val LAT_T3 = intPreferencesKey("lat_t3")
    val LATENCY_METER = stringPreferencesKey("latency_meter")
    val SHOW_TAGS = booleanPreferencesKey("show_tags")
    val SERVER_TAG_KINDS = stringPreferencesKey("server_tag_kinds")
    val SERVER_ROW = stringPreferencesKey("server_row")
    val SELECTION_CUE = stringPreferencesKey("selection_cue")
    val SELECTION_WASH = intPreferencesKey("selection_wash")
    val MOTION_LEVEL = stringPreferencesKey("motion_level")
    val RESPECT_SYS_ANIM = booleanPreferencesKey("respect_sys_anim")
    val BANNER_SHEEN = booleanPreferencesKey("banner_sheen")
    val COLOR_CROSSFADE = stringPreferencesKey("color_crossfade")
    val HAPTICS = stringPreferencesKey("haptics")
    val BG_STYLE = stringPreferencesKey("bg_style")
    val BG_IMAGE_DIM = intPreferencesKey("bg_image_dim")
    val BG_IMAGE_BLUR = intPreferencesKey("bg_image_blur")
    val BG_IMAGE_SATURATION = intPreferencesKey("bg_image_saturation")
    val BG_IMAGE_ZOOM = intPreferencesKey("bg_image_zoom")
    val BG_IMAGE_ALIGN = stringPreferencesKey("bg_image_align")
    val GLASS_PANELS = booleanPreferencesKey("glass_panels")
    val GLASS_TINT = intPreferencesKey("glass_tint")
    val SYSBAR_INK = stringPreferencesKey("sysbar_ink")
    val SPLASH_THEME = booleanPreferencesKey("splash_theme")

    /** See Settings.wgDnsThroughTunnel. */
    val WG_DNS_TUNNEL = booleanPreferencesKey("wg_dns_through_tunnel")
}

object SettingsMigration : DataMigration<Preferences> {
    override suspend fun shouldMigrate(currentData: Preferences): Boolean =
        (currentData[SettingsKeys.SCHEMA_VERSION] ?: 0) < SettingsDefaults.SCHEMA_VERSION

    override suspend fun migrate(currentData: Preferences): Preferences {
        val preferences = currentData.toMutablePreferences()
        var version = preferences[SettingsKeys.SCHEMA_VERSION] ?: 0
        while (version < SettingsDefaults.SCHEMA_VERSION) {
            when (version) {
                0 -> {
                    migrateToVersion1(preferences)
                    version = 1
                }
                1 -> {
                    migrateToVersion2(preferences)
                    version = 2
                }
                2 -> {
                    migrateToVersion3(preferences)
                    version = 3
                }
                3 -> {
                    migrateToVersion4(preferences)
                    version = 4
                }
                4 -> {
                    migrateToVersion5(preferences)
                    version = 5
                }
                5 -> {
                    migrateToVersion6(preferences)
                    version = 6
                }
                6 -> {
                    migrateToVersion7(preferences)
                    version = 7
                }
                7 -> {
                    migrateToVersion8(preferences)
                    version = 8
                }
                else -> error("Unsupported settings schema version: $version")
            }
            preferences[SettingsKeys.SCHEMA_VERSION] = version
        }
        return preferences.toPreferences()
    }

    override suspend fun cleanUp() = Unit

    /** The NekoBox-derived values that v1 wrote verbatim into every install. */
    private const val NEKO_DIRECT_DNS = "https://223.5.5.5/dns-query"
    private const val NEKO_SERVER_DOMAIN_STRATEGY = "prefer_ipv4"

    /**
     * v1 persisted NekoBox's own defaults, so changing the constants alone does not reach
     * anyone who already launched that build: the stored value wins. This step swapped
     * both to conservative values while the bootstrap slot still had to be self-dialable
     * (a hostname resolver there was a cycle the core refused to start).
     *
     * Superseded by [migrateToVersion3], which hands them back to NekoBox's values now
     * that `dns-local` resolves hostname resolvers. Kept so the 1→2→3 chain replays in
     * order; for an install coming all the way from v1 the two steps cancel out.
     *
     * Only the exact NekoBox value is replaced, so anything the user picked themselves
     * (a custom IP, their own DoH, an explicit strategy) is preserved.
     */
    private fun migrateToVersion2(preferences: androidx.datastore.preferences.core.MutablePreferences) {
        if (preferences[SettingsKeys.DIRECT_DNS] == NEKO_DIRECT_DNS) {
            preferences[SettingsKeys.DIRECT_DNS] = SettingsDefaults.DIRECT_DNS
        }
        if (preferences[SettingsKeys.IP_STRATEGY] == NEKO_SERVER_DOMAIN_STRATEGY) {
            preferences[SettingsKeys.IP_STRATEGY] = SettingsDefaults.SERVER_DOMAIN_STRATEGY
        }
    }

    /** What v2 shipped as the direct resolver, and what v3 hands back to NekoBox's own. */
    private const val V2_DIRECT_DNS = "local"

    /**
     * Owner's call: align the base configuration with the reference client (NekoBox). As
     * with v2, only the value the previous build shipped is rewritten, a resolver the
     * user picked in Настройки is left untouched.
     *
     * SingBoxConfig no longer needs the bootstrap slot to be self-dialable: hostname
     * resolvers now get a `domain_resolver` pointing at the platform resolver
     * (`dns-local`), the same arrangement NekoBox uses, so this value cannot cycle.
     *
     * ip_strategy is not touched. It is the user's «Тип IP» preference for
     * resolving domains inside the tunnel, where "auto" (A-only while the TUN is v4-only)
     * is the correct default; NekoBox's prefer_ipv4 belongs to the separate
     * server-dialing axis, which has no stored setting of its own.
     */
    private fun migrateToVersion3(preferences: androidx.datastore.preferences.core.MutablePreferences) {
        if (preferences[SettingsKeys.DIRECT_DNS] == V2_DIRECT_DNS) {
            preferences[SettingsKeys.DIRECT_DNS] = SettingsDefaults.DIRECT_DNS
        }
    }

    /** What v3 shipped as the direct resolver, for parity with the reference client. */
    private const val V3_DIRECT_DNS = "https://223.5.5.5/dns-query"

    /**
     * Reverts v3's bootstrap resolver, whose trade-off is not cosmetic: `dns-direct` is
     * dialed over
     * the RAW physical network before any tunnel exists, and on a restrictive/"whitelist"
     * mobile tariff, common in RU, where only specific destinations are reachable and
     * everything else is blocked until a VPN is up, a bootstrap query to an arbitrary
     * Chinese IP is exactly the traffic such a network drops. With the bootstrap resolver
     * unreachable the server's own hostname never resolves, `route.default_domain_resolver`
     * never completes, and no profile can connect on that network at all, the one thing
     * these tariffs need a VPN for is the one thing v3 had just broken.
     *
     * Same discipline as v2/v3: only the exact value v3 shipped is rewritten, so a resolver
     * the user picked themselves is untouched.
     */
    private fun migrateToVersion4(preferences: androidx.datastore.preferences.core.MutablePreferences) {
        if (preferences[SettingsKeys.DIRECT_DNS] == V3_DIRECT_DNS) {
            preferences[SettingsKeys.DIRECT_DNS] = SettingsDefaults.DIRECT_DNS
        }
    }

    /** The unused Cloudflare captive-portal URL v1-v4 shipped as the dormant GET/HEAD
     * fallback target (per-server ping defaulted to TCP back then, v6 moves that). */
    private const val V4_PING_URL = "http://cp.cloudflare.com/"

    /**
     * Moves the shared GET/HEAD + «Проверить соединение» target to a purpose-built
     * connectivity-check endpoint (GrapheneOS's own generate_204) instead of Cloudflare's
     * plain-HTTP captive-portal URL. Only the URL, the protocol default moved separately
     * in v6. Same discipline as v2-v4: only the exact value v1-v4 shipped is rewritten,
     * so a URL the user picked in Настройки is untouched.
     */
    private fun migrateToVersion5(preferences: androidx.datastore.preferences.core.MutablePreferences) {
        if (preferences[SettingsKeys.PING_URL] == V4_PING_URL) {
            preferences[SettingsKeys.PING_URL] = SettingsDefaults.TEST_URL
        }
    }

    /** The per-server ping protocol every build up to v5 shipped: a bare TCP connect. */
    private const val V5_PING_PROTOCOL = "TCP"

    /** The core log level every build up to v6 shipped: sing-box's own chatty default. */
    private const val V6_LOG_LEVEL = "info"

    /**
     * Owner's call: default per-server ping to «URL Test», the real protocol-level
     * measurement through a throwaway core instance (see [SettingsDefaults.PING_PROTOCOL]
     * for why, and [com.th3web.lean.core.UrlTestPinger] for how), instead of a bare TCP
     * connect, which can only prove a port answers a SYN.
     *
     * Same discipline as v2-v5: only the exact value every previous build shipped ("TCP")
     * is rewritten, so ICMP/GET/HEAD, or TCP itself if the user picked it
     * from the protocol row, survive untouched. A user who chose TCP while it was
     * already the default is indistinguishable from one who never touched it, and moving
     * such a user is what a default-change migration is for.
     */
    private fun migrateToVersion6(preferences: androidx.datastore.preferences.core.MutablePreferences) {
        if (preferences[SettingsKeys.PING_PROTOCOL] == V5_PING_PROTOCOL) {
            preferences[SettingsKeys.PING_PROTOCOL] = SettingsDefaults.PING_PROTOCOL
        }
    }

    /**
     * The local proxy listener gained settings of its own.
     *
     * `service_mode` already existed and already defaulted to "vpn": it was written on
     * every install and read by nothing. Giving it a second and third value ("proxy",
     * "vpn_proxy") is what turns it on, and these two are the knobs that mode needs.
     * Existing installs keep the TUN they have; only the new keys are seeded.
     */
    private fun migrateToVersion8(preferences: androidx.datastore.preferences.core.MutablePreferences) {
        preferences.putIfMissing(SettingsKeys.PROXY_PORT, SettingsDefaults.PROXY_PORT)
        preferences.putIfMissing(SettingsKeys.PROXY_ALLOW_LAN, SettingsDefaults.PROXY_ALLOW_LAN)
    }

    /**
     * Moves the core log level off "info", which was the default up to schema 6 and is
     * a performance problem rather than a preference: at info the core narrates every
     * connection, and on a busy app that write load eventually stalls the tunnel while
     * it still reports «подключено» (see SettingsDefaults.LOG_LEVEL).
     *
     * Only the old default is rewritten; debug, trace and error are deliberate choices
     * and survive. A user who wants info can set it again in «Лаборатория», and schema 7
     * will not undo it twice.
     */
    private fun migrateToVersion7(preferences: androidx.datastore.preferences.core.MutablePreferences) {
        if (preferences[SettingsKeys.LOG_LEVEL] == V6_LOG_LEVEL) {
            preferences[SettingsKeys.LOG_LEVEL] = SettingsDefaults.LOG_LEVEL
        }
    }

    private fun migrateToVersion1(preferences: androidx.datastore.preferences.core.MutablePreferences) {
        preferences.putIfMissing(SettingsKeys.ROUTING, RoutingMode.RULE.name)
        preferences.putIfMissing(SettingsKeys.BYPASS_LAN, true)
        preferences.putIfMissing(SettingsKeys.SERVICE_MODE, SettingsDefaults.SERVICE_MODE)
        preferences.putIfMissing(SettingsKeys.IPV6, SettingsDefaults.IPV6)
        preferences.putIfMissing(SettingsKeys.TUN_STACK, SettingsDefaults.TUN_STACK)
        preferences.putIfMissing(SettingsKeys.TUN_MTU, SettingsDefaults.TUN_MTU)
        preferences.putIfMissing(SettingsKeys.WG_MTU, SettingsDefaults.WG_MTU)
        preferences.putIfMissing(SettingsKeys.SNIFF_ENABLED, SettingsDefaults.SNIFF_ENABLED)
        preferences.putIfMissing(SettingsKeys.SNIFF_OVERRIDE_DESTINATION, SettingsDefaults.SNIFF_OVERRIDE_DESTINATION)
        preferences.putIfMissing(SettingsKeys.SNIFF_RESOLVE_DESTINATION, SettingsDefaults.SNIFF_RESOLVE_DESTINATION)
        preferences.putIfMissing(SettingsKeys.DNS_ROUTING, SettingsDefaults.DNS_ROUTING)
        preferences.putIfMissing(SettingsKeys.FAKE_DNS, SettingsDefaults.FAKE_DNS)
        preferences.putIfMissing(SettingsKeys.RESET_CONNECTIONS_ON_NETWORK_CHANGE, SettingsDefaults.RESET_CONNECTIONS_ON_NETWORK_CHANGE)
        preferences.putIfMissing(SettingsKeys.ALLOW_INSECURE, SettingsDefaults.ALLOW_INSECURE)
        preferences.putIfMissing(SettingsKeys.REMOTE_DNS, SettingsDefaults.REMOTE_DNS)
        preferences.putIfMissing(SettingsKeys.DIRECT_DNS, SettingsDefaults.DIRECT_DNS)
        preferences.putIfMissing(SettingsKeys.ACCENT, SettingsDefaults.ACCENT_LEGACY_DEFAULT)
        preferences.putIfMissing(SettingsKeys.THEME_MODE, "dark")
        preferences.putIfMissing(SettingsKeys.LANGUAGE, "system")
        preferences.putIfMissing(SettingsKeys.PERAPP_MODE, PerAppMode.OFF.name)
        preferences.putIfMissing(SettingsKeys.PERAPP_PKGS, emptySet())
        preferences.putIfMissing(SettingsKeys.AUTO_CONNECT, false)
        preferences.putIfMissing(SettingsKeys.LOG_LEVEL, "info")
        preferences.putIfMissing(SettingsKeys.KILL_SWITCH, false)
        preferences.putIfMissing(SettingsKeys.DOZE_PAUSE, false)
        preferences.putIfMissing(SettingsKeys.BATTERY_WARNING_HIDDEN, false)
        preferences.putIfMissing(SettingsKeys.MUX, false)
        preferences.putIfMissing(SettingsKeys.FRAGMENT, false)
        preferences.putIfMissing(SettingsKeys.AUTO_UPDATE, false)
        preferences.putIfMissing(SettingsKeys.CHECK_APP_UPDATES, true)
        preferences.putIfMissing(SettingsKeys.SEND_HWID, true)
        preferences.putIfMissing(SettingsKeys.PING_PROTOCOL, SettingsDefaults.PING_PROTOCOL)
        preferences.putIfMissing(SettingsKeys.PING_URL, SettingsDefaults.TEST_URL)
        preferences.putIfMissing(SettingsKeys.PING_TIMEOUT, SettingsDefaults.PROFILE_TEST_TIMEOUT_MS)
        preferences.putIfMissing(SettingsKeys.ACTIVE_CONNECTION_TEST_TIMEOUT, SettingsDefaults.ACTIVE_CONNECTION_TEST_TIMEOUT_MS)
        preferences.putIfMissing(SettingsKeys.IP_STRATEGY, SettingsDefaults.SERVER_DOMAIN_STRATEGY)
        preferences.putIfMissing(SettingsKeys.SERVER_SORT, "default")
        preferences.putIfMissing(SettingsKeys.PING_ON_LAUNCH, true)
        preferences.putIfMissing(SettingsKeys.PING_ON_UPDATE, true)
        preferences.putIfMissing(SettingsKeys.BG_REFRESH_MIN, 0)
        preferences.putIfMissing(SettingsKeys.SHOW_SPEED_NOTIF, true)
        preferences.putIfMissing(SettingsKeys.USER_AGENT, "")
        preferences.putIfMissing(SettingsKeys.APP_ICON, "default")
        preferences.putIfMissing(SettingsKeys.CRASH_REPORTING, false)
        preferences.putIfMissing(SettingsKeys.TCP_FAST_OPEN, false)
        preferences.putIfMissing(SettingsKeys.UTLS_FP, "chrome")
        preferences.putIfMissing(SettingsKeys.SNI_OVERRIDE, "")
        preferences.putIfMissing(SettingsKeys.RU_DIRECT, false)
        // Appearance keys, parity only, never correctness. Nothing reads a materialised
        // default: the repository resolves every one of these through `?:` / a
        // normalising `when`, so an install that never runs this step behaves
        // identically. They are listed so this step keeps describing the full key set,
        // which is what makes a missing key visible in review.
        preferences.putIfMissing(SettingsKeys.APPEARANCE_PREVIEW, true)
        preferences.putIfMissing(SettingsKeys.APPEARANCE_PRESET, "custom")
        preferences.putIfMissing(SettingsKeys.CUSTOM_PRESETS, "")
        preferences.putIfMissing(SettingsKeys.ACCENT_RECENT, "")
        preferences.putIfMissing(SettingsKeys.THEME_SCHEDULE, false)
        preferences.putIfMissing(SettingsKeys.THEME_SCHED_FROM, SettingsDefaults.THEME_SCHED_FROM)
        preferences.putIfMissing(SettingsKeys.THEME_SCHED_TO, SettingsDefaults.THEME_SCHED_TO)
        preferences.putIfMissing(SettingsKeys.THEME_SCHED_MODE, "amoled")
        preferences.putIfMissing(SettingsKeys.CONTRAST_LEVEL, 0)
        preferences.putIfMissing(SettingsKeys.AMOLED_DEPTH, "absolute")
        preferences.putIfMissing(SettingsKeys.AMOLED_TINT, false)
        preferences.putIfMissing(SettingsKeys.ACCENT_SOURCE, "preset")
        preferences.putIfMissing(SettingsKeys.ACCENT_CHROMA, SettingsDefaults.ACCENT_CHROMA)
        preferences.putIfMissing(SettingsKeys.SURFACE_TINT, SettingsDefaults.SURFACE_TINT)
        preferences.putIfMissing(SettingsKeys.CONNECTED_MODE, "sage")
        preferences.putIfMissing(SettingsKeys.ERROR_COLOR, "coral")
        preferences.putIfMissing(SettingsKeys.WORDMARK_ACCENT, true)
        preferences.putIfMissing(SettingsKeys.ROLE_OVERRIDES, "")
        preferences.putIfMissing(SettingsKeys.FONT_DISPLAY, "unbounded")
        preferences.putIfMissing(SettingsKeys.FONT_BODY, "onest")
        preferences.putIfMissing(SettingsKeys.TEXT_SCALE, 100)
        preferences.putIfMissing(SettingsKeys.FONT_WEIGHT_DELTA, 0)
        preferences.putIfMissing(SettingsKeys.TABULAR_NUMS, true)
        preferences.putIfMissing(SettingsKeys.SECTION_CAPS, true)
        preferences.putIfMissing(SettingsKeys.CORNER_STYLE, "normal")
        preferences.putIfMissing(SettingsKeys.UI_DENSITY, "normal")
        preferences.putIfMissing(SettingsKeys.OUTLINE_WEIGHT, "thin")
        preferences.putIfMissing(SettingsKeys.SHOW_DIVIDERS, true)
        preferences.putIfMissing(SettingsKeys.DIVIDER_INDENT, "inset")
        preferences.putIfMissing(SettingsKeys.CARD_SHADOW, "soft")
        preferences.putIfMissing(SettingsKeys.HERO_STYLE, "ring")
        preferences.putIfMissing(SettingsKeys.HERO_SIZE, 100)
        preferences.putIfMissing(SettingsKeys.HERO_GLYPH, "power")
        preferences.putIfMissing(SettingsKeys.HERO_BREATH, true)
        preferences.putIfMissing(SettingsKeys.HERO_FLOATING, false)
        preferences.putIfMissing(SettingsKeys.TRAFFIC_ROW, "large")
        preferences.putIfMissing(SettingsKeys.QUICK_PEEK, SettingsDefaults.QUICK_PEEK)
        preferences.putIfMissing(SettingsKeys.HOME_BLOCKS, SettingsDefaults.HOME_BLOCKS)
        preferences.putIfMissing(SettingsKeys.CUR_SRV_LABEL, "name")
        preferences.putIfMissing(SettingsKeys.LATENCY_PALETTE, "accent")
        preferences.putIfMissing(SettingsKeys.LAT_T1, SettingsDefaults.LAT_T1)
        preferences.putIfMissing(SettingsKeys.LAT_T2, SettingsDefaults.LAT_T2)
        preferences.putIfMissing(SettingsKeys.LAT_T3, SettingsDefaults.LAT_T3)
        preferences.putIfMissing(SettingsKeys.LATENCY_METER, "bars_ms")
        preferences.putIfMissing(SettingsKeys.SHOW_TAGS, true)
        preferences.putIfMissing(SettingsKeys.SERVER_TAG_KINDS, SettingsDefaults.SERVER_TAG_KINDS)
        preferences.putIfMissing(SettingsKeys.SERVER_ROW, "normal")
        preferences.putIfMissing(SettingsKeys.SELECTION_CUE, "both")
        preferences.putIfMissing(SettingsKeys.SELECTION_WASH, SettingsDefaults.SELECTION_WASH)
        preferences.putIfMissing(SettingsKeys.MOTION_LEVEL, "normal")
        preferences.putIfMissing(SettingsKeys.RESPECT_SYS_ANIM, true)
        preferences.putIfMissing(SettingsKeys.BANNER_SHEEN, true)
        preferences.putIfMissing(SettingsKeys.COLOR_CROSSFADE, "on")
        preferences.putIfMissing(SettingsKeys.HAPTICS, "normal")
        preferences.putIfMissing(SettingsKeys.BG_STYLE, "flat")
        preferences.putIfMissing(SettingsKeys.BG_IMAGE_DIM, SettingsDefaults.BG_IMAGE_DIM)
        preferences.putIfMissing(SettingsKeys.BG_IMAGE_BLUR, SettingsDefaults.BG_IMAGE_BLUR)
        preferences.putIfMissing(SettingsKeys.BG_IMAGE_SATURATION, SettingsDefaults.BG_IMAGE_SATURATION)
        preferences.putIfMissing(SettingsKeys.BG_IMAGE_ZOOM, SettingsDefaults.BG_IMAGE_ZOOM)
        preferences.putIfMissing(SettingsKeys.BG_IMAGE_ALIGN, SettingsDefaults.BG_IMAGE_ALIGN)
        preferences.putIfMissing(SettingsKeys.GLASS_PANELS, false)
        preferences.putIfMissing(SettingsKeys.GLASS_TINT, SettingsDefaults.GLASS_TINT)
        preferences.putIfMissing(SettingsKeys.SYSBAR_INK, "auto")
        preferences.putIfMissing(SettingsKeys.SPLASH_THEME, true)
        preferences.putIfMissing(SettingsKeys.WG_DNS_TUNNEL, true)
        // Keep v2 absent while legacy data exists so the repository fallback remains visible.
        if (!preferences.contains(SettingsKeys.CUSTOM_RULE_SETS)) {
            preferences.putIfMissing(SettingsKeys.CUSTOM_RULE_SETS_STR, "")
        }
    }

    private fun <T> androidx.datastore.preferences.core.MutablePreferences.putIfMissing(
        key: Preferences.Key<T>,
        value: T,
    ) {
        if (!contains(key)) this[key] = value
    }
}

package com.th3web.lean.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import androidx.datastore.preferences.core.emptyPreferences
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.flow.stateIn
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import com.th3web.lean.data.net.CrashReporter

@JvmInline
internal value class SelectedProfileWriteToken(val revision: Long)

private val selectedProfileRevisionKey = longPreferencesKey("internal_selected_profile_revision")

@Serializable
enum class RoutingMode { RULE, GLOBAL }

@Serializable
enum class PerAppMode { OFF, INCLUDE, EXCLUDE }

/** Immutable snapshot of user settings. */
@Serializable
data class Settings(
    val selectedProfileId: String? = null,
    val routingMode: RoutingMode = RoutingMode.RULE,
    val bypassLan: Boolean = true,
    val ipv6: Boolean = SettingsDefaults.IPV6,
    val allowInsecure: Boolean = SettingsDefaults.ALLOW_INSECURE,
    val remoteDns: String = SettingsDefaults.REMOTE_DNS,
    val directDns: String = SettingsDefaults.DIRECT_DNS,
    val accentColor: Long = SettingsDefaults.ACCENT_LEGACY_DEFAULT, // white liquid (monochrome default)
    val themeMode: String = "dark", // dark | amoled | light | system (unknown normalizes to dark)
    val language: String = "system", // system | ru | en
    val perAppMode: PerAppMode = PerAppMode.OFF,
    val perAppPackages: Set<String> = emptySet(),
    val autoConnect: Boolean = false,
    /**
     * «Автопереключение (Beta)», reconnect on a drop, and move to another server
     * when this one will not come back. Off by default: it acts on its own, and a
     * VPN switching servers unasked is a surprise the user has to opt into.
     */
    @SerialName("auto_failover") val autoFailover: Boolean = false,
    val logLevel: String = SettingsDefaults.LOG_LEVEL,
    val killSwitch: Boolean = false,
    /**
     * «Спать в глубоком сне», opt-in, and off by default for a reason.
     *
     * Pausing the core does not merely stop its timers: sing-box's pause manager
     * holds new connections while paused, so for as long as the system keeps the
     * device in Doze the tunnel carries nothing. That is a real battery saving and a
     * real cost, which is why it is the user's choice and never a default. Bound to
     * Doze itself, never to the screen: a dark screen is not an idle phone.
     */
    val dozePause: Boolean = false,
    /**
     * The user has dismissed the «Система может усыпить Lean» card by hand.
     *
     * Needed because the system's own answer cannot be trusted everywhere:
     * PowerManager.isIgnoringBatteryOptimizations keeps reporting false on several
     * vendor ROMs even after the exemption is granted, and on those the whitelist
     * that actually governs whether an app survives is the vendor's, which no API
     * exposes at all. So the warning could not be got rid of by doing what it asked.
     */
    val batteryWarningHidden: Boolean = false,
    val mux: Boolean = false,
    val fragment: Boolean = false,
    val autoUpdate: Boolean = false,
    /** Check the public release repo for a newer app build on launch (APK, not subscription). */
    val checkAppUpdates: Boolean = true,
    /** Send the x-hwid header quartet with subscription requests (Incy `send_hwid`). */
    val sendHwid: Boolean = true,
    val pingProtocol: String = SettingsDefaults.PING_PROTOCOL, // TCP | ICMP | GET | HEAD | URLTEST
    val pingUrl: String = SettingsDefaults.TEST_URL,
    val pingTimeoutMs: Int = SettingsDefaults.PROFILE_TEST_TIMEOUT_MS,
    val ipStrategy: String = SettingsDefaults.SERVER_DOMAIN_STRATEGY, // auto | prefer_ipv4 | prefer_ipv6 | ipv4_only | ipv6_only
    val serverSort: String = "default", // default | ping | name
    val pingOnLaunch: Boolean = true,    // feature 4: re-ping ALL servers on app start
    val pingOnUpdate: Boolean = true,    // feature 4: re-ping a sub's servers after its refresh
    val bgRefreshMinutes: Int = 0,       // feature 8: 0 = Off; allowed values 0,30,60,120,360,720,1440
    val showSpeedInNotification: Boolean = true, // live ↓/↑ speed in the ongoing notification
    /**
     * User-Agent presented on subscription requests (Http.userAgent). Panels
     * gate the returned server list by UA, so this is user-spoofable from the
     * Provider hub; empty = the dynamic default "Lean/<versionName>" (resolved in
     * LeanApp from BuildConfig), so it never goes stale on a version bump. Panels
     * recognise the "Lean" prefix regardless of the version suffix.
     */
    val userAgent: String = "",
    /**
     * WireGuard tunnel MTU (applied to the WG endpoint and the matched TUN MTU,
     * see SingBoxConfig). Default 1280 (IPv6 min) never fragments on any path,
     * the fix for slow WireGuard; raise it (1408/1420) for more throughput on a
     * clean network. Changeable in Connection settings.
     */
    val wgMtu: Int = SettingsDefaults.WG_MTU,
    /**
     * Launcher-icon variant: default | outline | black | accent, the manifest
     * activity-alias quartet (core/AppIcon.kt). MainActivity re-asserts the
     * matching alias on launch, so a restored backup converges too.
     */
    val appIcon: String = "default",
    /**
     * Opt-in: persist and upload a redacted, bounded stacktrace plus the in-memory
     * tunnel log tail. Default OFF; disabling deletes any pending report.
     */
    val crashReporting: Boolean = false,
    // ---- Client-side traffic masking (core-level, no server changes) ----
    /**
     * TCP Fast Open on the TCP outbounds (VLESS/VMess/Trojan). Default OFF: TFO folds
     * the first bytes into the SYN, which some DPI/whitelist paths drop → "connects but
     * no traffic" (the same failure that killed Shadowsocks). v2rayNG defaults it off too,
     * so a config that works there but not in Lean is often just this. Opt-in for the rare
     * clean network where it saves an RTT.
     */
    val tcpFastOpen: Boolean = false,
    /**
     * uTLS ClientHello fingerprint mimicked on TCP-TLS outbounds to defeat JA3/JA4, one
     * of chrome|firefox|safari|ios|android|edge|360|qq|random|randomized. Default chrome.
     * (Reality always keeps a fingerprint; QUIC never uses uTLS.)
     */
    val utlsFingerprint: String = "chrome",
    /**
     * Client-side SNI camouflage: when non-blank, overrides the TLS server_name on every
     * non-Reality TLS outbound with this whitelisted domain (e.g. avito.st / vk.com), so the
     * ClientHello looks like an allowed site under SNI-filtering whitelists, no server
     * change needed. Pair with «Небезопасный TLS» (the cert won't match). Reality nodes
     * ignore it (their SNI is the server's stolen-cert domain).
     */
    val sniOverride: String = "",
    /**
     * GeoIP/GeoSite routing: send Russian sites & IPs straight out (not through the
     * foreign exit), faster RU content, less correlation, complements the per-app split.
     * Adds .ru/.su domain rules (immediate) + remote geoip-ru/geosite-category-ru rule-sets
     * (the full list, downloaded through the proxy and cached). Default off.
     */
    val ruDirect: Boolean = false,
    /**
     * User-supplied custom rule-set URLs (binary .srs geoip/geosite), like Incy/Happ
     * "add your own geoip". Each is routed direct (same "go straight out" semantics
     * as the built-in RU sets), downloaded through the proxy and cached. Empty by
     * default; active whenever non-empty (independent of [ruDirect]). New field has a
     * default, the backward-compat invariant (a no-default field wipes the store).
     */
    val customRuleSets: List<String> = emptyList(),
    @SerialName("service_mode") val serviceMode: String = SettingsDefaults.SERVICE_MODE,
    @SerialName("proxy_port") val proxyPort: Int = SettingsDefaults.PROXY_PORT,
    @SerialName("proxy_allow_lan") val proxyAllowLan: Boolean = SettingsDefaults.PROXY_ALLOW_LAN,
    @SerialName("tun_stack") val tunStack: String = SettingsDefaults.TUN_STACK,
    @SerialName("tun_mtu") val tunMtu: Int = SettingsDefaults.TUN_MTU,
    @SerialName("sniff_enabled") val sniffEnabled: Boolean = SettingsDefaults.SNIFF_ENABLED,
    @SerialName("sniff_override_destination") val sniffOverrideDestination: Boolean = SettingsDefaults.SNIFF_OVERRIDE_DESTINATION,
    @SerialName("sniff_resolve_destination") val sniffResolveDestination: Boolean = SettingsDefaults.SNIFF_RESOLVE_DESTINATION,
    @SerialName("dns_routing") val dnsRouting: Boolean = SettingsDefaults.DNS_ROUTING,
    @SerialName("fake_dns") val fakeDns: Boolean = SettingsDefaults.FAKE_DNS,
    @SerialName("reset_connections_on_network_change") val resetConnectionsOnNetworkChange: Boolean = SettingsDefaults.RESET_CONNECTIONS_ON_NETWORK_CHANGE,
    @SerialName("active_connection_test_timeout") val activeConnectionTestTimeoutMs: Int = SettingsDefaults.ACTIVE_CONNECTION_TEST_TIMEOUT_MS,
    // ---- «Оформление» ----
    //
    // Everything below is resolved (clamped, normalised) by the read mapping, so a value
    // read from here is always safe to hand a Dp, an alpha or a font weight. The
    // shareable subset also lives in [AppearanceProfile]; the two field lists mirror each
    // other name-for-name so a divergence is visible by eye.
    //
    // Same invariant as every field above: a Kotlin default is mandatory. Without one,
    // SettingsSnapshotStore.load()'s runCatching collapses the whole mirror to defaults
    // forever, and every previously exported backup decodes as Backup.Corrupt.
    /** Show the live preview pinned under the Оформление app bar. Costs two frame drivers. */
    val appearancePreview: Boolean = true,
    /** Label of the last applied look, or "custom" once any single knob is touched. */
    val appearancePreset: String = "custom",
    /** «Мои образы», up to ten user-saved looks, persisted as JSON in one preference. */
    val customPresets: List<NamedAppearance> = emptyList(),
    /** Most-recently-used custom accents for the picker, newest first, capped at six. */
    val accentRecent: List<Long> = emptyList(),
    val themeSchedule: Boolean = false,
    /** Minutes from midnight. Only consulted while [themeSchedule] is on. */
    val themeSchedFrom: Int = SettingsDefaults.THEME_SCHED_FROM,
    val themeSchedTo: Int = SettingsDefaults.THEME_SCHED_TO,
    val themeSchedMode: String = "amoled", // dark | amoled
    /** -2..+2, in steps of 4% lightness, applied inside the palette resolver. */
    val contrastLevel: Int = 0,
    val amoledDepth: String = "absolute", // absolute | soft
    /**
     * Let the accent tint AMOLED surfaces. Off reproduces today's look: the canvas stays
     * pure #000000 either way (tintNeutral leaves a zero-luminance base alone), so this
     * only reaches the raised cards.
     */
    val amoledTint: Boolean = false,
    val accentSource: String = "preset", // preset | custom | wallpaper
    /** Saturation ceiling for a synthesized accent, percent of the Steel template's. */
    val accentChroma: Int = SettingsDefaults.ACCENT_CHROMA,
    /** Neutral-surface tint amount in percent, the old LeanNeutralTintAmount, unfrozen. */
    val surfaceTint: Int = SettingsDefaults.SURFACE_TINT,
    /** sage | accent. Default keeps the one security-positive colour off the accent axis. */
    val connectedMode: String = "sage",
    val errorColor: String = "coral", // coral | crimson | amber
    val wordmarkAccent: Boolean = true,
    /** Ten semantic slots (see [AppearanceRoles]); unknown keys are dropped on read. */
    val roleOverrides: Map<String, Long> = emptyMap(),
    val fontDisplay: String = "unbounded", // unbounded | onest | system | mono
    val fontBody: String = "onest",
    /** Percent, one of 90/95/100/110/120, Typography only, never LocalDensity. */
    val textScale: Int = 100,
    val fontWeightDelta: Int = 0, // -100 | 0 | +100
    val tabularNums: Boolean = true,
    val sectionCaps: Boolean = true,
    val cornerStyle: String = "normal", // sharp | crisp | normal | soft | round
    val uiDensity: String = "normal", // compact | normal | comfortable
    val outlineWeight: String = "thin", // none | thin | strong
    val showDividers: Boolean = true,
    val dividerIndent: String = "inset", // inset | full
    val cardShadow: String = "soft", // none | soft | deep
    val heroStyle: String = "ring", // ring | disc | pulse | minimal
    val heroSize: Int = 100, // 85 | 100 | 115 percent
    val heroGlyph: String = "power", // power | shield | globe | pulse
    val heroBreath: Boolean = true,
    /** Compact connect control that appears once the hero scrolls out of view. */
    val heroFloating: Boolean = false,
    val trafficRow: String = "large", // hidden | compact | large
    /** Rows a collapsed quick-pick group shows; 0 hides the block. */
    val quickPeek: Int = SettingsDefaults.QUICK_PEEK,
    /** Bitmask of the four optional home blocks. */
    val homeBlocks: Int = SettingsDefaults.HOME_BLOCKS,
    val currentServerLabel: String = "name", // name | name_proto | hidden
    val latencyPalette: String = "accent", // accent | traffic | mono | gradient
    /** Latency tier boundaries in ms, kept strictly ascending by the read mapping. */
    val latT1: Int = SettingsDefaults.LAT_T1,
    val latT2: Int = SettingsDefaults.LAT_T2,
    val latT3: Int = SettingsDefaults.LAT_T3,
    val latencyMeter: String = "bars_ms", // bars_ms | ms | bars
    val showTags: Boolean = true,
    /** Which [com.th3web.lean.ui.components.TagKind]s a server row states, as a flag
     * string of p/s/t. See AppearanceNorm.serverTagKinds. */
    val serverTagKinds: String = SettingsDefaults.SERVER_TAG_KINDS,
    val serverRow: String = "normal", // compact | normal | detailed
    val selectionCue: String = "both", // stripe | wash | both | none
    /** Selected-row wash alpha in percent. */
    val selectionWash: Int = SettingsDefaults.SELECTION_WASH,
    val motionLevel: String = "normal", // off | calm | normal | lively
    /**
     * Whether the system's animator scale still vetoes in-app animation. Leaving it on
     * keeps today's behaviour; turning it off makes it a floor, so a user on a
     * battery-saver ROM can have the app animate anyway.
     */
    val respectSystemAnimations: Boolean = true,
    val bannerSheen: Boolean = true,
    val colorCrossfade: String = "on", // on | off
    val haptics: String = "normal", // none | light | normal
    val bgStyle: String = "flat", // flat | vignette | gradient | grain | image
    /** Scrim weight over «своя картинка», percent, see LeanBackground. */
    val bgImageDim: Int = SettingsDefaults.BG_IMAGE_DIM,
    /** Soft-focus strength over «своя картинка», percent. */
    val bgImageBlur: Int = SettingsDefaults.BG_IMAGE_BLUR,
    /** Colour retained in the background, percent (0 = grayscale). */
    val bgImageSaturation: Int = SettingsDefaults.BG_IMAGE_SATURATION,
    /** Extra magnification over cover-fit, percent. */
    val bgImageZoom: Int = SettingsDefaults.BG_IMAGE_ZOOM,
    /** Which part of a taller-than-screen picture stays visible: top | center | bottom. */
    val bgImageAlign: String = SettingsDefaults.BG_IMAGE_ALIGN,
    /** Panels show the blurred backdrop through themselves. */
    val glassPanels: Boolean = false,
    /** How opaque a glass panel stays, percent. */
    val glassTint: Int = SettingsDefaults.GLASS_TINT,
    val sysbarInk: String = "auto", // auto | light | dark
    /** Pick the launch window background from the stored theme instead of one fixed colour. */
    val splashTheme: Boolean = true,

    /**
     * Whether a WireGuard endpoint resolves destination domains through the tunnel.
     *
     * Only plain WireGuard is affected. A WG tunnel carries packets, not names, so the
     * client must resolve each destination itself before sending it, unlike VLESS/Trojan/
     * Shadowsocks, which hand the proxy a hostname and never resolve anything locally.
     * AmneziaWG runs on its own native core and never reaches this DNS module at all.
     *
     * True (default) keeps those lookups inside the tunnel, so the exit resolves them and
     * nothing about the browsing leaves the device in the clear. False is the reference
     * client's behaviour: they go to the direct resolver, which is faster and gives
     * local-CDN answers, at the cost of showing that resolver every domain visited
     * together with the real IP.
     */
    val wgDnsThroughTunnel: Boolean = true,
)

internal val Context.dataStore: DataStore<Preferences> by preferencesDataStore(
    name = "lean_settings",
    produceMigrations = { listOf(SettingsMigration) },
    // Self-heal a corrupt/unreadable prefs file instead of permanently masking it as
    // all-defaults (which silently dropped every saved setting, 4pda: "всё вернулось
    // в дефолт"). On CorruptionException DataStore atomically rewrites an empty store,
    // so subsequent reads and edit writes succeed again and new settings persist.
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
)

class SettingsRepository(
    private val context: Context,
    private val store: DataStore<Preferences> = context.dataStore,
) {
    private val snapshotStore = SettingsSnapshotStore(context)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val upstream: Flow<Settings> = store.data
        // A terminating `.catch` alone would freeze the live [state] StateFlow forever
        // after a single transient IOException (locked file, EACCES, low-storage): catch
        // emits once then completes, and an Eagerly stateIn never restarts a completed
        // upstream. So first retry a transient read error (re-collects the cold DataStore
        // flow once the file is readable again): this keeps live updates flowing. Only
        // when retries are exhausted does `.catch` fall back to empty prefs (→ all
        // defaults), which also keeps the synchronous [initial] `first()` from blocking
        // forever on a permanently broken store. Corruption is already self-healed by the
        // ReplaceFileCorruptionHandler above.
        .retryWhen { e, attempt ->
            if (e is java.io.IOException && attempt < 3) { delay(200L * (attempt + 1)); true } else false
        }
        .catch { e -> if (e is java.io.IOException) emit(emptyPreferences()) else throw e }
        .map { p ->
        // Hoisted because the triple is clamped in order: each bound is coerced above
        // the one below it, so a reordered pair (a hand-edited backup, an older build's
        // values) can never leave a latency tier unreachable.
        val latT1 = AppearanceNorm.latT1(p[SettingsKeys.LAT_T1])
        val latT2 = AppearanceNorm.latT2(p[SettingsKeys.LAT_T2], latT1)
        val latT3 = AppearanceNorm.latT3(p[SettingsKeys.LAT_T3], latT2)
        Settings(
            selectedProfileId = p[SettingsKeys.SELECTED],
            routingMode = p[SettingsKeys.ROUTING]?.let { runCatching { RoutingMode.valueOf(it) }.getOrNull() }
                ?: RoutingMode.RULE,
            bypassLan = p[SettingsKeys.BYPASS_LAN] ?: true,
            serviceMode = p[SettingsKeys.SERVICE_MODE] ?: SettingsDefaults.SERVICE_MODE,
            proxyPort = p[SettingsKeys.PROXY_PORT] ?: SettingsDefaults.PROXY_PORT,
            proxyAllowLan = p[SettingsKeys.PROXY_ALLOW_LAN] ?: SettingsDefaults.PROXY_ALLOW_LAN,
            ipv6 = p[SettingsKeys.IPV6] ?: SettingsDefaults.IPV6,
            tunStack = p[SettingsKeys.TUN_STACK] ?: SettingsDefaults.TUN_STACK,
            tunMtu = p[SettingsKeys.TUN_MTU] ?: SettingsDefaults.TUN_MTU,
            wgMtu = p[SettingsKeys.WG_MTU] ?: SettingsDefaults.WG_MTU,
            sniffEnabled = p[SettingsKeys.SNIFF_ENABLED] ?: SettingsDefaults.SNIFF_ENABLED,
            sniffOverrideDestination = p[SettingsKeys.SNIFF_OVERRIDE_DESTINATION] ?: SettingsDefaults.SNIFF_OVERRIDE_DESTINATION,
            sniffResolveDestination = p[SettingsKeys.SNIFF_RESOLVE_DESTINATION] ?: SettingsDefaults.SNIFF_RESOLVE_DESTINATION,
            dnsRouting = p[SettingsKeys.DNS_ROUTING] ?: SettingsDefaults.DNS_ROUTING,
            fakeDns = p[SettingsKeys.FAKE_DNS] ?: SettingsDefaults.FAKE_DNS,
            resetConnectionsOnNetworkChange = p[SettingsKeys.RESET_CONNECTIONS_ON_NETWORK_CHANGE] ?: SettingsDefaults.RESET_CONNECTIONS_ON_NETWORK_CHANGE,
            autoFailover = p[SettingsKeys.AUTO_FAILOVER] ?: false,
            allowInsecure = p[SettingsKeys.ALLOW_INSECURE] ?: SettingsDefaults.ALLOW_INSECURE,
            remoteDns = p[SettingsKeys.REMOTE_DNS] ?: SettingsDefaults.REMOTE_DNS,
            directDns = p[SettingsKeys.DIRECT_DNS] ?: SettingsDefaults.DIRECT_DNS,
            accentColor = p[SettingsKeys.ACCENT] ?: SettingsDefaults.ACCENT_LEGACY_DEFAULT,
            // Closed value sets resolve through AppearanceNorm, so this fallback, the
            // one that actually runs, and the AppearanceProfile decoder can never
            // disagree about which strings are legal.
            themeMode = AppearanceNorm.themeMode(p[SettingsKeys.THEME_MODE]),
            language = p[SettingsKeys.LANGUAGE] ?: "system",
            perAppMode = p[SettingsKeys.PERAPP_MODE]?.let { runCatching { PerAppMode.valueOf(it) }.getOrNull() }
                ?: PerAppMode.OFF,
            perAppPackages = p[SettingsKeys.PERAPP_PKGS] ?: emptySet(),
            autoConnect = p[SettingsKeys.AUTO_CONNECT] ?: false,
            logLevel = AppearanceNorm.logLevel(p[SettingsKeys.LOG_LEVEL]),
            killSwitch = p[SettingsKeys.KILL_SWITCH] ?: false,
            dozePause = p[SettingsKeys.DOZE_PAUSE] ?: false,
            batteryWarningHidden = p[SettingsKeys.BATTERY_WARNING_HIDDEN] ?: false,
            mux = p[SettingsKeys.MUX] ?: false,
            fragment = p[SettingsKeys.FRAGMENT] ?: false,
            autoUpdate = p[SettingsKeys.AUTO_UPDATE] ?: false,
            checkAppUpdates = p[SettingsKeys.CHECK_APP_UPDATES] ?: true,
            sendHwid = p[SettingsKeys.SEND_HWID] ?: true,
            pingProtocol = p[SettingsKeys.PING_PROTOCOL] ?: SettingsDefaults.PING_PROTOCOL,
            pingUrl = p[SettingsKeys.PING_URL] ?: SettingsDefaults.TEST_URL,
            pingTimeoutMs = p[SettingsKeys.PING_TIMEOUT] ?: SettingsDefaults.PROFILE_TEST_TIMEOUT_MS,
            activeConnectionTestTimeoutMs = p[SettingsKeys.ACTIVE_CONNECTION_TEST_TIMEOUT] ?: SettingsDefaults.ACTIVE_CONNECTION_TEST_TIMEOUT_MS,
            ipStrategy = p[SettingsKeys.IP_STRATEGY] ?: SettingsDefaults.SERVER_DOMAIN_STRATEGY,
            serverSort = p[SettingsKeys.SERVER_SORT] ?: "default",
            pingOnLaunch = p[SettingsKeys.PING_ON_LAUNCH] ?: true,
            pingOnUpdate = p[SettingsKeys.PING_ON_UPDATE] ?: true,
            bgRefreshMinutes = p[SettingsKeys.BG_REFRESH_MIN] ?: 0,
            showSpeedInNotification = p[SettingsKeys.SHOW_SPEED_NOTIF] ?: true,
            userAgent = p[SettingsKeys.USER_AGENT] ?: "",
            appIcon = AppearanceNorm.appIcon(p[SettingsKeys.APP_ICON]),
            crashReporting = p[SettingsKeys.CRASH_REPORTING] ?: false,
            tcpFastOpen = p[SettingsKeys.TCP_FAST_OPEN] ?: false,
            utlsFingerprint = p[SettingsKeys.UTLS_FP] ?: "chrome",
            sniOverride = p[SettingsKeys.SNI_OVERRIDE] ?: "",
            ruDirect = p[SettingsKeys.RU_DIRECT] ?: false,
            // Newline-joined string preserves order + duplicates (a Set lost both,
            // reshuffling the RuleSetsScreen text field on reopen). Fall back to the
            // legacy Set key read with its own type, reading it as a String would
            // throw ClassCastException and crash settings load for upgraders.
            customRuleSets = p[SettingsKeys.CUSTOM_RULE_SETS_STR]
                ?.lines()?.map { it.trim() }?.filter { it.startsWith("http") }
                ?: (p[SettingsKeys.CUSTOM_RULE_SETS] ?: emptySet()).map { it.trim() }.filter { it.startsWith("http") },
            appearancePreview = p[SettingsKeys.APPEARANCE_PREVIEW] ?: true,
            // Free-form: a saved look carries the name the user typed, so there is no
            // closed set to normalise against, only a length ceiling.
            appearancePreset = p[SettingsKeys.APPEARANCE_PRESET]?.take(64) ?: "custom",
            customPresets = decodeCustomPresets(p[SettingsKeys.CUSTOM_PRESETS]),
            accentRecent = parseRecentAccents(p[SettingsKeys.ACCENT_RECENT]),
            themeSchedule = p[SettingsKeys.THEME_SCHEDULE] ?: false,
            themeSchedFrom = AppearanceNorm.minuteOfDay(p[SettingsKeys.THEME_SCHED_FROM], SettingsDefaults.THEME_SCHED_FROM),
            themeSchedTo = AppearanceNorm.minuteOfDay(p[SettingsKeys.THEME_SCHED_TO], SettingsDefaults.THEME_SCHED_TO),
            themeSchedMode = AppearanceNorm.themeSchedMode(p[SettingsKeys.THEME_SCHED_MODE]),
            contrastLevel = AppearanceNorm.contrastLevel(p[SettingsKeys.CONTRAST_LEVEL]),
            amoledDepth = AppearanceNorm.amoledDepth(p[SettingsKeys.AMOLED_DEPTH]),
            amoledTint = p[SettingsKeys.AMOLED_TINT] ?: false,
            accentSource = AppearanceNorm.accentSource(p[SettingsKeys.ACCENT_SOURCE]),
            accentChroma = AppearanceNorm.accentChroma(p[SettingsKeys.ACCENT_CHROMA]),
            surfaceTint = AppearanceNorm.surfaceTint(p[SettingsKeys.SURFACE_TINT]),
            connectedMode = AppearanceNorm.connectedMode(p[SettingsKeys.CONNECTED_MODE]),
            errorColor = AppearanceNorm.errorColor(p[SettingsKeys.ERROR_COLOR]),
            wordmarkAccent = p[SettingsKeys.WORDMARK_ACCENT] ?: true,
            roleOverrides = decodeRoleOverrides(p[SettingsKeys.ROLE_OVERRIDES]),
            fontDisplay = AppearanceNorm.fontFamily(p[SettingsKeys.FONT_DISPLAY], "unbounded"),
            fontBody = AppearanceNorm.fontFamily(p[SettingsKeys.FONT_BODY], "onest"),
            textScale = AppearanceNorm.textScale(p[SettingsKeys.TEXT_SCALE]),
            fontWeightDelta = AppearanceNorm.fontWeightDelta(p[SettingsKeys.FONT_WEIGHT_DELTA]),
            tabularNums = p[SettingsKeys.TABULAR_NUMS] ?: true,
            sectionCaps = p[SettingsKeys.SECTION_CAPS] ?: true,
            cornerStyle = AppearanceNorm.cornerStyle(p[SettingsKeys.CORNER_STYLE]),
            uiDensity = AppearanceNorm.uiDensity(p[SettingsKeys.UI_DENSITY]),
            outlineWeight = AppearanceNorm.outlineWeight(p[SettingsKeys.OUTLINE_WEIGHT]),
            showDividers = p[SettingsKeys.SHOW_DIVIDERS] ?: true,
            dividerIndent = AppearanceNorm.dividerIndent(p[SettingsKeys.DIVIDER_INDENT]),
            cardShadow = AppearanceNorm.cardShadow(p[SettingsKeys.CARD_SHADOW]),
            heroStyle = AppearanceNorm.heroStyle(p[SettingsKeys.HERO_STYLE]),
            heroSize = AppearanceNorm.heroSize(p[SettingsKeys.HERO_SIZE]),
            heroGlyph = AppearanceNorm.heroGlyph(p[SettingsKeys.HERO_GLYPH]),
            heroBreath = p[SettingsKeys.HERO_BREATH] ?: true,
            heroFloating = p[SettingsKeys.HERO_FLOATING] ?: false,
            trafficRow = AppearanceNorm.trafficRow(p[SettingsKeys.TRAFFIC_ROW]),
            quickPeek = AppearanceNorm.quickPeek(p[SettingsKeys.QUICK_PEEK]),
            homeBlocks = AppearanceNorm.homeBlocks(p[SettingsKeys.HOME_BLOCKS]),
            currentServerLabel = AppearanceNorm.currentServerLabel(p[SettingsKeys.CUR_SRV_LABEL]),
            latencyPalette = AppearanceNorm.latencyPalette(p[SettingsKeys.LATENCY_PALETTE]),
            latT1 = latT1,
            latT2 = latT2,
            latT3 = latT3,
            latencyMeter = AppearanceNorm.latencyMeter(p[SettingsKeys.LATENCY_METER]),
            showTags = p[SettingsKeys.SHOW_TAGS] ?: true,
            serverTagKinds = AppearanceNorm.serverTagKinds(p[SettingsKeys.SERVER_TAG_KINDS]),
            serverRow = AppearanceNorm.serverRow(p[SettingsKeys.SERVER_ROW]),
            selectionCue = AppearanceNorm.selectionCue(p[SettingsKeys.SELECTION_CUE]),
            selectionWash = AppearanceNorm.selectionWash(p[SettingsKeys.SELECTION_WASH]),
            motionLevel = AppearanceNorm.motionLevel(p[SettingsKeys.MOTION_LEVEL]),
            respectSystemAnimations = p[SettingsKeys.RESPECT_SYS_ANIM] ?: true,
            bannerSheen = p[SettingsKeys.BANNER_SHEEN] ?: true,
            colorCrossfade = AppearanceNorm.colorCrossfade(p[SettingsKeys.COLOR_CROSSFADE]),
            haptics = AppearanceNorm.haptics(p[SettingsKeys.HAPTICS]),
            bgStyle = AppearanceNorm.bgStyle(p[SettingsKeys.BG_STYLE]),
            bgImageDim = AppearanceNorm.bgImageDim(p[SettingsKeys.BG_IMAGE_DIM]),
            bgImageBlur = AppearanceNorm.bgImageBlur(p[SettingsKeys.BG_IMAGE_BLUR]),
            bgImageSaturation = AppearanceNorm.bgImageSaturation(p[SettingsKeys.BG_IMAGE_SATURATION]),
            bgImageZoom = AppearanceNorm.bgImageZoom(p[SettingsKeys.BG_IMAGE_ZOOM]),
            bgImageAlign = AppearanceNorm.bgImageAlign(p[SettingsKeys.BG_IMAGE_ALIGN]),
            glassPanels = p[SettingsKeys.GLASS_PANELS] ?: false,
            glassTint = AppearanceNorm.glassTint(p[SettingsKeys.GLASS_TINT]),
            sysbarInk = AppearanceNorm.sysbarInk(p[SettingsKeys.SYSBAR_INK]),
            splashTheme = p[SettingsKeys.SPLASH_THEME] ?: true,
            wgDnsThroughTunnel = p[SettingsKeys.WG_DNS_TUNNEL] ?: true,
        )
    }

    /**
     * Small synchronous mirror used for first-frame/theme and cold QS-tile reads.
     * It never opens DataStore on the main thread: the canonical DataStore flow
     * refreshes this mirror from [scope] after every successful emission.
     */
    val initial: Settings = snapshotStore.load()

    /**
     * Hot, always-current settings. Unlike [initial], a value cached once for the
     * whole process, [state].value reflects live changes. Background entry points
     * that cannot suspend (the QS tile's synchronous connect path) must read this,
     * not [initial]: otherwise, after the user picks a different server while the
     * process stays alive, the tile would still act on the selection captured when
     * the process first started (the "QS connects the wrong server" bug). Seeded
     * with [initial] so a cold start (a tile tap spins the process up) already has
     * the persisted value on the very first synchronous read.
     */
    val state: StateFlow<Settings> = upstream
        .onEach(snapshotStore::save)
        .stateIn(scope, SharingStarted.Eagerly, initial)

    /**
     * Canonical settings stream. Suspending callers wait for DataStore's first
     * emission instead of acting on a startup-mirror fallback during migration.
     */
    val flow: Flow<Settings> = upstream

    suspend fun setSelectedProfile(id: String?) {
        store.edit { p ->
            p.writeSelectedProfile(id)
            p.advanceSelectedProfileRevision()
        }
    }

    internal suspend fun writeSelectedProfileOwned(id: String?): SelectedProfileWriteToken {
        var token: SelectedProfileWriteToken? = null
        store.edit { p ->
            p.writeSelectedProfile(id)
            token = SelectedProfileWriteToken(p.advanceSelectedProfileRevision())
        }
        return checkNotNull(token)
    }

    internal suspend fun restoreSelectedProfileIfOwned(
        token: SelectedProfileWriteToken,
        id: String?,
    ): Boolean {
        var restored = false
        store.edit { p ->
            if (p[selectedProfileRevisionKey] == token.revision) {
                p.writeSelectedProfile(id)
                p.advanceSelectedProfileRevision()
                restored = true
            }
        }
        return restored
    }

    suspend fun setRoutingMode(mode: RoutingMode) = edit { it[SettingsKeys.ROUTING] = mode.name }
    suspend fun setBypassLan(value: Boolean) = edit { it[SettingsKeys.BYPASS_LAN] = value }
    suspend fun setIpv6(value: Boolean) = edit { it[SettingsKeys.IPV6] = value }
    suspend fun setAllowInsecure(value: Boolean) = edit { it[SettingsKeys.ALLOW_INSECURE] = value }
    suspend fun setRemoteDns(value: String) = edit { it[SettingsKeys.REMOTE_DNS] = value }
    suspend fun setDirectDns(value: String) = edit { it[SettingsKeys.DIRECT_DNS] = value }

    suspend fun setWgDnsThroughTunnel(value: Boolean) = edit { it[SettingsKeys.WG_DNS_TUNNEL] = value }
    suspend fun setAccentColor(argb: Long) = edit { it[SettingsKeys.ACCENT] = argb }
    suspend fun setPerAppMode(mode: PerAppMode) = edit { it[SettingsKeys.PERAPP_MODE] = mode.name }
    suspend fun setPerAppPackages(pkgs: Set<String>) = edit { it[SettingsKeys.PERAPP_PKGS] = pkgs }
    suspend fun setAutoConnect(value: Boolean) = edit { it[SettingsKeys.AUTO_CONNECT] = value }
    suspend fun setAutoFailover(value: Boolean) = edit { it[SettingsKeys.AUTO_FAILOVER] = value }
    suspend fun setLogLevel(value: String) = edit { it[SettingsKeys.LOG_LEVEL] = value }
    suspend fun setKillSwitch(value: Boolean) = edit { it[SettingsKeys.KILL_SWITCH] = value }
    suspend fun setDozePause(value: Boolean) = edit { it[SettingsKeys.DOZE_PAUSE] = value }
    suspend fun setBatteryWarningHidden(value: Boolean) =
        edit { it[SettingsKeys.BATTERY_WARNING_HIDDEN] = value }
    suspend fun setMux(value: Boolean) = edit { it[SettingsKeys.MUX] = value }
    suspend fun setFragment(value: Boolean) = edit { it[SettingsKeys.FRAGMENT] = value }
    suspend fun setAutoUpdate(value: Boolean) = edit { it[SettingsKeys.AUTO_UPDATE] = value }
    suspend fun setCheckAppUpdates(value: Boolean) = edit { it[SettingsKeys.CHECK_APP_UPDATES] = value }
    suspend fun setSendHwid(value: Boolean) = edit { it[SettingsKeys.SEND_HWID] = value }
    suspend fun setPingProtocol(value: String) = edit { it[SettingsKeys.PING_PROTOCOL] = value }
    suspend fun setPingUrl(value: String) = edit { it[SettingsKeys.PING_URL] = value }
    suspend fun setPingTimeout(value: Int) = edit { it[SettingsKeys.PING_TIMEOUT] = value }
    suspend fun setWgMtu(value: Int) = edit { it[SettingsKeys.WG_MTU] = value }
    suspend fun setIpStrategy(value: String) = edit { it[SettingsKeys.IP_STRATEGY] = value }
    suspend fun setTunStack(value: String) = edit { it[SettingsKeys.TUN_STACK] = value }
    suspend fun setServiceMode(value: String) = edit { it[SettingsKeys.SERVICE_MODE] = value }
    suspend fun setProxyPort(value: Int) = edit { it[SettingsKeys.PROXY_PORT] = value }
    suspend fun setProxyAllowLan(value: Boolean) = edit { it[SettingsKeys.PROXY_ALLOW_LAN] = value }
    suspend fun setThemeMode(value: String) = edit { it[SettingsKeys.THEME_MODE] = value }
    suspend fun setLanguage(value: String) = edit { it[SettingsKeys.LANGUAGE] = value }
    suspend fun setServerSort(value: String) = edit { it[SettingsKeys.SERVER_SORT] = value }
    suspend fun setPingOnLaunch(value: Boolean) = edit { it[SettingsKeys.PING_ON_LAUNCH] = value }
    suspend fun setPingOnUpdate(value: Boolean) = edit { it[SettingsKeys.PING_ON_UPDATE] = value }
    suspend fun setBgRefreshMinutes(value: Int) = edit { it[SettingsKeys.BG_REFRESH_MIN] = value }
    suspend fun setShowSpeedInNotification(value: Boolean) = edit { it[SettingsKeys.SHOW_SPEED_NOTIF] = value }
    suspend fun setUserAgent(value: String) = edit { it[SettingsKeys.USER_AGENT] = value }
    suspend fun setAppIcon(value: String) = edit { it[SettingsKeys.APP_ICON] = value }
    suspend fun setCrashReporting(value: Boolean) {
        if (!value) CrashReporter.setEnabled(context, false)
        edit { it[SettingsKeys.CRASH_REPORTING] = value }
        if (value) CrashReporter.setEnabled(context, true)
    }
    suspend fun setTcpFastOpen(value: Boolean) = edit { it[SettingsKeys.TCP_FAST_OPEN] = value }
    suspend fun setUtlsFingerprint(value: String) = edit { it[SettingsKeys.UTLS_FP] = value }
    suspend fun setSniOverride(value: String) = edit { it[SettingsKeys.SNI_OVERRIDE] = value.trim() }
    suspend fun setRuDirect(value: Boolean) = edit { it[SettingsKeys.RU_DIRECT] = value }
    suspend fun setCustomRuleSets(urls: List<String>) = edit { it[SettingsKeys.CUSTOM_RULE_SETS_STR] = urls.map { u -> u.trim() }.filter { u -> u.startsWith("http") }.joinToString("\n") }

    // ---- «Оформление» ----
    //
    // One setter per knob, because 56 of the 60 controls are discrete taps. The four
    // sliders must call theirs only from onValueChangeFinished: every write is a
    // DataStore file rewrite plus a full JSON encode plus a synchronous
    // SharedPreferences.commit() with an fsync, which is fine once per gesture and
    // ruinous once per frame.
    suspend fun setAppearancePreview(value: Boolean) = edit { it[SettingsKeys.APPEARANCE_PREVIEW] = value }
    suspend fun setAppearancePreset(value: String) = edit { it[SettingsKeys.APPEARANCE_PRESET] = value.take(64) }
    suspend fun setCustomPresets(presets: List<NamedAppearance>) = edit { it[SettingsKeys.CUSTOM_PRESETS] = encodeCustomPresets(presets) }
    suspend fun setAccentRecent(colors: List<Long>) = edit { it[SettingsKeys.ACCENT_RECENT] = formatRecentAccents(colors) }
    suspend fun setThemeSchedule(value: Boolean) = edit { it[SettingsKeys.THEME_SCHEDULE] = value }
    suspend fun setThemeSchedFrom(minutes: Int) = edit { it[SettingsKeys.THEME_SCHED_FROM] = minutes }
    suspend fun setThemeSchedTo(minutes: Int) = edit { it[SettingsKeys.THEME_SCHED_TO] = minutes }
    suspend fun setThemeSchedMode(value: String) = edit { it[SettingsKeys.THEME_SCHED_MODE] = value }
    suspend fun setContrastLevel(value: Int) = edit { it[SettingsKeys.CONTRAST_LEVEL] = value }
    suspend fun setAmoledDepth(value: String) = edit { it[SettingsKeys.AMOLED_DEPTH] = value }
    suspend fun setAmoledTint(value: Boolean) = edit { it[SettingsKeys.AMOLED_TINT] = value }
    suspend fun setAccentSource(value: String) = edit { it[SettingsKeys.ACCENT_SOURCE] = value }
    suspend fun setAccentChroma(value: Int) = edit { it[SettingsKeys.ACCENT_CHROMA] = value }
    suspend fun setSurfaceTint(value: Int) = edit { it[SettingsKeys.SURFACE_TINT] = value }
    suspend fun setConnectedMode(value: String) = edit { it[SettingsKeys.CONNECTED_MODE] = value }
    suspend fun setErrorColor(value: String) = edit { it[SettingsKeys.ERROR_COLOR] = value }
    suspend fun setWordmarkAccent(value: Boolean) = edit { it[SettingsKeys.WORDMARK_ACCENT] = value }
    suspend fun setRoleOverrides(overrides: Map<String, Long>) = edit { it[SettingsKeys.ROLE_OVERRIDES] = encodeRoleOverrides(overrides) }
    suspend fun setFontDisplay(value: String) = edit { it[SettingsKeys.FONT_DISPLAY] = value }
    suspend fun setFontBody(value: String) = edit { it[SettingsKeys.FONT_BODY] = value }
    suspend fun setTextScale(value: Int) = edit { it[SettingsKeys.TEXT_SCALE] = value }
    suspend fun setFontWeightDelta(value: Int) = edit { it[SettingsKeys.FONT_WEIGHT_DELTA] = value }
    suspend fun setTabularNums(value: Boolean) = edit { it[SettingsKeys.TABULAR_NUMS] = value }
    suspend fun setSectionCaps(value: Boolean) = edit { it[SettingsKeys.SECTION_CAPS] = value }
    suspend fun setCornerStyle(value: String) = edit { it[SettingsKeys.CORNER_STYLE] = value }
    suspend fun setUiDensity(value: String) = edit { it[SettingsKeys.UI_DENSITY] = value }
    suspend fun setOutlineWeight(value: String) = edit { it[SettingsKeys.OUTLINE_WEIGHT] = value }
    suspend fun setShowDividers(value: Boolean) = edit { it[SettingsKeys.SHOW_DIVIDERS] = value }
    suspend fun setDividerIndent(value: String) = edit { it[SettingsKeys.DIVIDER_INDENT] = value }
    suspend fun setCardShadow(value: String) = edit { it[SettingsKeys.CARD_SHADOW] = value }
    suspend fun setHeroStyle(value: String) = edit { it[SettingsKeys.HERO_STYLE] = value }
    suspend fun setHeroSize(value: Int) = edit { it[SettingsKeys.HERO_SIZE] = value }
    suspend fun setHeroGlyph(value: String) = edit { it[SettingsKeys.HERO_GLYPH] = value }
    suspend fun setHeroBreath(value: Boolean) = edit { it[SettingsKeys.HERO_BREATH] = value }
    suspend fun setHeroFloating(value: Boolean) = edit { it[SettingsKeys.HERO_FLOATING] = value }
    suspend fun setTrafficRow(value: String) = edit { it[SettingsKeys.TRAFFIC_ROW] = value }
    suspend fun setQuickPeek(value: Int) = edit { it[SettingsKeys.QUICK_PEEK] = value }
    suspend fun setHomeBlocks(mask: Int) = edit { it[SettingsKeys.HOME_BLOCKS] = mask }
    suspend fun setCurrentServerLabel(value: String) = edit { it[SettingsKeys.CUR_SRV_LABEL] = value }
    suspend fun setLatencyPalette(value: String) = edit { it[SettingsKeys.LATENCY_PALETTE] = value }
    suspend fun setLatencyThresholds(t1: Int, t2: Int, t3: Int) = edit {
        it[SettingsKeys.LAT_T1] = t1
        it[SettingsKeys.LAT_T2] = t2
        it[SettingsKeys.LAT_T3] = t3
    }
    suspend fun setLatencyMeter(value: String) = edit { it[SettingsKeys.LATENCY_METER] = value }
    suspend fun setShowTags(value: Boolean) = edit { it[SettingsKeys.SHOW_TAGS] = value }
    suspend fun setServerTagKinds(value: String) = edit { it[SettingsKeys.SERVER_TAG_KINDS] = value }
    suspend fun setServerRow(value: String) = edit { it[SettingsKeys.SERVER_ROW] = value }
    suspend fun setSelectionCue(value: String) = edit { it[SettingsKeys.SELECTION_CUE] = value }
    suspend fun setSelectionWash(value: Int) = edit { it[SettingsKeys.SELECTION_WASH] = value }
    suspend fun setMotionLevel(value: String) = edit { it[SettingsKeys.MOTION_LEVEL] = value }
    suspend fun setRespectSystemAnimations(value: Boolean) = edit { it[SettingsKeys.RESPECT_SYS_ANIM] = value }
    suspend fun setBannerSheen(value: Boolean) = edit { it[SettingsKeys.BANNER_SHEEN] = value }
    suspend fun setColorCrossfade(value: String) = edit { it[SettingsKeys.COLOR_CROSSFADE] = value }
    suspend fun setHaptics(value: String) = edit { it[SettingsKeys.HAPTICS] = value }
    suspend fun setBgStyle(value: String) = edit { it[SettingsKeys.BG_STYLE] = value }
    suspend fun setBgImageDim(value: Int) = edit { it[SettingsKeys.BG_IMAGE_DIM] = value }
    suspend fun setBgImageBlur(value: Int) = edit { it[SettingsKeys.BG_IMAGE_BLUR] = value }
    suspend fun setBgImageSaturation(value: Int) = edit { it[SettingsKeys.BG_IMAGE_SATURATION] = value }
    suspend fun setBgImageZoom(value: Int) = edit { it[SettingsKeys.BG_IMAGE_ZOOM] = value }
    suspend fun setBgImageAlign(value: String) = edit { it[SettingsKeys.BG_IMAGE_ALIGN] = value }
    suspend fun setGlassPanels(value: Boolean) = edit { it[SettingsKeys.GLASS_PANELS] = value }
    suspend fun setGlassTint(value: Int) = edit { it[SettingsKeys.GLASS_TINT] = value }
    suspend fun setSysbarInk(value: String) = edit { it[SettingsKeys.SYSBAR_INK] = value }
    suspend fun setSplashTheme(value: Boolean) = edit { it[SettingsKeys.SPLASH_THEME] = value }

    /**
     * Apply a whole look (a preset tap, a pasted share code, «Сбросить оформление») in
     * one transaction.
     *
     * The single edit is not a micro-optimisation: 53 individual setters would
     * be 53 DataStore emissions, 53 JSON encodes, 53 fsynced mirror commits and 53 theme
     * rebuilds, most of them showing a half-applied look on screen. One edit is one
     * emission and one recomposition.
     *
     * [preset] is written in the same transaction so the "which look is active" label can
     * never disagree with the look itself. Pass the preset's name when applying a named
     * look; leave it at "custom" for a pasted code or a hand-built profile.
     *
     * The profile is sanitized here as well as on decode, so even a caller holding a
     * hand-built AppearanceProfile cannot land an out-of-range number in the store.
     */
    suspend fun applyAppearance(profile: AppearanceProfile, preset: String = "custom") {
        val p = profile.sanitized()
        edit { prefs ->
            prefs[SettingsKeys.APPEARANCE_PRESET] = preset.take(64)
            prefs[SettingsKeys.THEME_MODE] = p.themeMode
            prefs[SettingsKeys.THEME_SCHEDULE] = p.themeSchedule
            prefs[SettingsKeys.THEME_SCHED_FROM] = p.themeSchedFrom
            prefs[SettingsKeys.THEME_SCHED_TO] = p.themeSchedTo
            prefs[SettingsKeys.THEME_SCHED_MODE] = p.themeSchedMode
            prefs[SettingsKeys.CONTRAST_LEVEL] = p.contrastLevel
            prefs[SettingsKeys.AMOLED_DEPTH] = p.amoledDepth
            prefs[SettingsKeys.AMOLED_TINT] = p.amoledTint
            prefs[SettingsKeys.ACCENT_SOURCE] = p.accentSource
            prefs[SettingsKeys.ACCENT] = p.accentColor
            prefs[SettingsKeys.ACCENT_CHROMA] = p.accentChroma
            prefs[SettingsKeys.SURFACE_TINT] = p.surfaceTint
            prefs[SettingsKeys.CONNECTED_MODE] = p.connectedMode
            prefs[SettingsKeys.ERROR_COLOR] = p.errorColor
            prefs[SettingsKeys.WORDMARK_ACCENT] = p.wordmarkAccent
            prefs[SettingsKeys.ROLE_OVERRIDES] = encodeRoleOverrides(p.roleOverrides)
            prefs[SettingsKeys.FONT_DISPLAY] = p.fontDisplay
            prefs[SettingsKeys.FONT_BODY] = p.fontBody
            prefs[SettingsKeys.TEXT_SCALE] = p.textScale
            prefs[SettingsKeys.FONT_WEIGHT_DELTA] = p.fontWeightDelta
            prefs[SettingsKeys.TABULAR_NUMS] = p.tabularNums
            prefs[SettingsKeys.SECTION_CAPS] = p.sectionCaps
            prefs[SettingsKeys.CORNER_STYLE] = p.cornerStyle
            prefs[SettingsKeys.UI_DENSITY] = p.uiDensity
            prefs[SettingsKeys.OUTLINE_WEIGHT] = p.outlineWeight
            prefs[SettingsKeys.SHOW_DIVIDERS] = p.showDividers
            prefs[SettingsKeys.DIVIDER_INDENT] = p.dividerIndent
            prefs[SettingsKeys.CARD_SHADOW] = p.cardShadow
            prefs[SettingsKeys.HERO_STYLE] = p.heroStyle
            prefs[SettingsKeys.HERO_SIZE] = p.heroSize
            prefs[SettingsKeys.HERO_GLYPH] = p.heroGlyph
            prefs[SettingsKeys.HERO_BREATH] = p.heroBreath
            prefs[SettingsKeys.HERO_FLOATING] = p.heroFloating
            prefs[SettingsKeys.TRAFFIC_ROW] = p.trafficRow
            prefs[SettingsKeys.QUICK_PEEK] = p.quickPeek
            prefs[SettingsKeys.HOME_BLOCKS] = p.homeBlocks
            prefs[SettingsKeys.CUR_SRV_LABEL] = p.currentServerLabel
            prefs[SettingsKeys.LATENCY_PALETTE] = p.latencyPalette
            prefs[SettingsKeys.LAT_T1] = p.latT1
            prefs[SettingsKeys.LAT_T2] = p.latT2
            prefs[SettingsKeys.LAT_T3] = p.latT3
            prefs[SettingsKeys.LATENCY_METER] = p.latencyMeter
            prefs[SettingsKeys.SHOW_TAGS] = p.showTags
            prefs[SettingsKeys.SERVER_TAG_KINDS] = p.serverTagKinds
            prefs[SettingsKeys.SERVER_ROW] = p.serverRow
            prefs[SettingsKeys.SELECTION_CUE] = p.selectionCue
            prefs[SettingsKeys.SELECTION_WASH] = p.selectionWash
            prefs[SettingsKeys.MOTION_LEVEL] = p.motionLevel
            prefs[SettingsKeys.RESPECT_SYS_ANIM] = p.respectSystemAnimations
            prefs[SettingsKeys.BANNER_SHEEN] = p.bannerSheen
            prefs[SettingsKeys.COLOR_CROSSFADE] = p.colorCrossfade
            prefs[SettingsKeys.HAPTICS] = p.haptics
            prefs[SettingsKeys.BG_STYLE] = p.bgStyle
            prefs[SettingsKeys.BG_IMAGE_DIM] = p.bgImageDim
            prefs[SettingsKeys.BG_IMAGE_BLUR] = p.bgImageBlur
            prefs[SettingsKeys.BG_IMAGE_SATURATION] = p.bgImageSaturation
            prefs[SettingsKeys.BG_IMAGE_ZOOM] = p.bgImageZoom
            prefs[SettingsKeys.BG_IMAGE_ALIGN] = p.bgImageAlign
            prefs[SettingsKeys.GLASS_PANELS] = p.glassPanels
            prefs[SettingsKeys.GLASS_TINT] = p.glassTint
            prefs[SettingsKeys.SYSBAR_INK] = p.sysbarInk
            prefs[SettingsKeys.SPLASH_THEME] = p.splashTheme
        }
    }

    /**
     * Backup "Replace" mode: writes every resolved setting in one DataStore
     * transaction. Legacy backups only replace fields that were actually present;
     * current backups pass null and replace the complete settings snapshot.
     */
    suspend fun setAll(imported: Settings, importedFields: Set<String>? = null) {
        val s = SettingsRestore.merge(
            current = if (importedFields == null) imported else upstream.first(),
            imported = imported,
            importedFields = importedFields,
        )
        if (!s.crashReporting) CrashReporter.setEnabled(context, false)
        edit { p ->
            p[SettingsKeys.SCHEMA_VERSION] = SettingsDefaults.SCHEMA_VERSION
            p.writeSelectedProfile(s.selectedProfileId)
            p.advanceSelectedProfileRevision()
            p[SettingsKeys.ROUTING] = s.routingMode.name
            p[SettingsKeys.BYPASS_LAN] = s.bypassLan
            p[SettingsKeys.SERVICE_MODE] = s.serviceMode
            p[SettingsKeys.PROXY_PORT] = s.proxyPort
            p[SettingsKeys.PROXY_ALLOW_LAN] = s.proxyAllowLan
            p[SettingsKeys.IPV6] = s.ipv6
            p[SettingsKeys.TUN_STACK] = s.tunStack
            p[SettingsKeys.TUN_MTU] = s.tunMtu
            p[SettingsKeys.ALLOW_INSECURE] = s.allowInsecure
            p[SettingsKeys.REMOTE_DNS] = s.remoteDns
            p[SettingsKeys.DIRECT_DNS] = s.directDns
            p[SettingsKeys.ACCENT] = s.accentColor
            p[SettingsKeys.THEME_MODE] = s.themeMode
            p[SettingsKeys.LANGUAGE] = s.language
            p[SettingsKeys.PERAPP_MODE] = s.perAppMode.name
            p[SettingsKeys.PERAPP_PKGS] = s.perAppPackages
            p[SettingsKeys.AUTO_CONNECT] = s.autoConnect
            p[SettingsKeys.LOG_LEVEL] = s.logLevel
            p[SettingsKeys.KILL_SWITCH] = s.killSwitch
            p[SettingsKeys.DOZE_PAUSE] = s.dozePause
            p[SettingsKeys.BATTERY_WARNING_HIDDEN] = s.batteryWarningHidden
            p[SettingsKeys.MUX] = s.mux
            p[SettingsKeys.FRAGMENT] = s.fragment
            p[SettingsKeys.AUTO_UPDATE] = s.autoUpdate
            p[SettingsKeys.CHECK_APP_UPDATES] = s.checkAppUpdates
            p[SettingsKeys.SEND_HWID] = s.sendHwid
            p[SettingsKeys.PING_PROTOCOL] = s.pingProtocol
            p[SettingsKeys.PING_URL] = s.pingUrl
            p[SettingsKeys.PING_TIMEOUT] = s.pingTimeoutMs
            p[SettingsKeys.ACTIVE_CONNECTION_TEST_TIMEOUT] = s.activeConnectionTestTimeoutMs
            p[SettingsKeys.IP_STRATEGY] = s.ipStrategy
            p[SettingsKeys.SERVER_SORT] = s.serverSort
            p[SettingsKeys.PING_ON_LAUNCH] = s.pingOnLaunch
            p[SettingsKeys.PING_ON_UPDATE] = s.pingOnUpdate
            p[SettingsKeys.BG_REFRESH_MIN] = s.bgRefreshMinutes
            p[SettingsKeys.SHOW_SPEED_NOTIF] = s.showSpeedInNotification
            p[SettingsKeys.USER_AGENT] = s.userAgent
            p[SettingsKeys.WG_MTU] = s.wgMtu
            p[SettingsKeys.SNIFF_ENABLED] = s.sniffEnabled
            p[SettingsKeys.SNIFF_OVERRIDE_DESTINATION] = s.sniffOverrideDestination
            p[SettingsKeys.SNIFF_RESOLVE_DESTINATION] = s.sniffResolveDestination
            p[SettingsKeys.DNS_ROUTING] = s.dnsRouting
            p[SettingsKeys.FAKE_DNS] = s.fakeDns
            p[SettingsKeys.RESET_CONNECTIONS_ON_NETWORK_CHANGE] = s.resetConnectionsOnNetworkChange
            p[SettingsKeys.AUTO_FAILOVER] = s.autoFailover
            p[SettingsKeys.APP_ICON] = s.appIcon
            p[SettingsKeys.CRASH_REPORTING] = s.crashReporting
            p[SettingsKeys.TCP_FAST_OPEN] = s.tcpFastOpen
            p[SettingsKeys.UTLS_FP] = s.utlsFingerprint
            p[SettingsKeys.SNI_OVERRIDE] = s.sniOverride
            p[SettingsKeys.RU_DIRECT] = s.ruDirect
            p[SettingsKeys.CUSTOM_RULE_SETS_STR] = s.customRuleSets.map { it.trim() }.filter { it.startsWith("http") }.joinToString("\n")
            // A field missing from here is the one silent failure in this file: it round
            // trips through the backup JSON perfectly, so import "works", and it simply
            // never reaches DataStore, the user restores a backup and that one setting
            // is quietly the default. SetAllCoversEveryFieldTest walks the serial
            // descriptor and fails on the omission.
            p[SettingsKeys.APPEARANCE_PREVIEW] = s.appearancePreview
            p[SettingsKeys.APPEARANCE_PRESET] = s.appearancePreset
            p[SettingsKeys.CUSTOM_PRESETS] = encodeCustomPresets(s.customPresets)
            p[SettingsKeys.ACCENT_RECENT] = formatRecentAccents(s.accentRecent)
            p[SettingsKeys.THEME_SCHEDULE] = s.themeSchedule
            p[SettingsKeys.THEME_SCHED_FROM] = s.themeSchedFrom
            p[SettingsKeys.THEME_SCHED_TO] = s.themeSchedTo
            p[SettingsKeys.THEME_SCHED_MODE] = s.themeSchedMode
            p[SettingsKeys.CONTRAST_LEVEL] = s.contrastLevel
            p[SettingsKeys.AMOLED_DEPTH] = s.amoledDepth
            p[SettingsKeys.AMOLED_TINT] = s.amoledTint
            p[SettingsKeys.ACCENT_SOURCE] = s.accentSource
            p[SettingsKeys.ACCENT_CHROMA] = s.accentChroma
            p[SettingsKeys.SURFACE_TINT] = s.surfaceTint
            p[SettingsKeys.CONNECTED_MODE] = s.connectedMode
            p[SettingsKeys.ERROR_COLOR] = s.errorColor
            p[SettingsKeys.WORDMARK_ACCENT] = s.wordmarkAccent
            p[SettingsKeys.ROLE_OVERRIDES] = encodeRoleOverrides(s.roleOverrides)
            p[SettingsKeys.FONT_DISPLAY] = s.fontDisplay
            p[SettingsKeys.FONT_BODY] = s.fontBody
            p[SettingsKeys.TEXT_SCALE] = s.textScale
            p[SettingsKeys.FONT_WEIGHT_DELTA] = s.fontWeightDelta
            p[SettingsKeys.TABULAR_NUMS] = s.tabularNums
            p[SettingsKeys.SECTION_CAPS] = s.sectionCaps
            p[SettingsKeys.CORNER_STYLE] = s.cornerStyle
            p[SettingsKeys.UI_DENSITY] = s.uiDensity
            p[SettingsKeys.OUTLINE_WEIGHT] = s.outlineWeight
            p[SettingsKeys.SHOW_DIVIDERS] = s.showDividers
            p[SettingsKeys.DIVIDER_INDENT] = s.dividerIndent
            p[SettingsKeys.CARD_SHADOW] = s.cardShadow
            p[SettingsKeys.HERO_STYLE] = s.heroStyle
            p[SettingsKeys.HERO_SIZE] = s.heroSize
            p[SettingsKeys.HERO_GLYPH] = s.heroGlyph
            p[SettingsKeys.HERO_BREATH] = s.heroBreath
            p[SettingsKeys.HERO_FLOATING] = s.heroFloating
            p[SettingsKeys.TRAFFIC_ROW] = s.trafficRow
            p[SettingsKeys.QUICK_PEEK] = s.quickPeek
            p[SettingsKeys.HOME_BLOCKS] = s.homeBlocks
            p[SettingsKeys.CUR_SRV_LABEL] = s.currentServerLabel
            p[SettingsKeys.LATENCY_PALETTE] = s.latencyPalette
            p[SettingsKeys.LAT_T1] = s.latT1
            p[SettingsKeys.LAT_T2] = s.latT2
            p[SettingsKeys.LAT_T3] = s.latT3
            p[SettingsKeys.LATENCY_METER] = s.latencyMeter
            p[SettingsKeys.SHOW_TAGS] = s.showTags
            p[SettingsKeys.SERVER_TAG_KINDS] = s.serverTagKinds
            p[SettingsKeys.SERVER_ROW] = s.serverRow
            p[SettingsKeys.SELECTION_CUE] = s.selectionCue
            p[SettingsKeys.SELECTION_WASH] = s.selectionWash
            p[SettingsKeys.MOTION_LEVEL] = s.motionLevel
            p[SettingsKeys.RESPECT_SYS_ANIM] = s.respectSystemAnimations
            p[SettingsKeys.BANNER_SHEEN] = s.bannerSheen
            p[SettingsKeys.COLOR_CROSSFADE] = s.colorCrossfade
            p[SettingsKeys.HAPTICS] = s.haptics
            p[SettingsKeys.BG_STYLE] = s.bgStyle
            p[SettingsKeys.BG_IMAGE_DIM] = s.bgImageDim
            p[SettingsKeys.BG_IMAGE_BLUR] = s.bgImageBlur
            p[SettingsKeys.BG_IMAGE_SATURATION] = s.bgImageSaturation
            p[SettingsKeys.BG_IMAGE_ZOOM] = s.bgImageZoom
            p[SettingsKeys.BG_IMAGE_ALIGN] = s.bgImageAlign
            p[SettingsKeys.GLASS_PANELS] = s.glassPanels
            p[SettingsKeys.GLASS_TINT] = s.glassTint
            p[SettingsKeys.SYSBAR_INK] = s.sysbarInk
            p[SettingsKeys.SPLASH_THEME] = s.splashTheme
            p[SettingsKeys.WG_DNS_TUNNEL] = s.wgDnsThroughTunnel
        }
        if (s.crashReporting) CrashReporter.setEnabled(context, true)
    }

    private suspend fun edit(block: (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        store.edit(block)
    }

    private fun androidx.datastore.preferences.core.MutablePreferences.writeSelectedProfile(
        id: String?,
    ) {
        if (id == null) remove(SettingsKeys.SELECTED) else this[SettingsKeys.SELECTED] = id
    }

    private fun androidx.datastore.preferences.core.MutablePreferences.advanceSelectedProfileRevision(): Long {
        val revision = (this[selectedProfileRevisionKey] ?: 0L) + 1L
        this[selectedProfileRevisionKey] = revision
        return revision
    }
}

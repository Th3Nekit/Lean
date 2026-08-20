package com.th3web.lean

import android.app.Activity
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.addCallback
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.lifecycle.lifecycleScope
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import com.th3web.lean.core.Automation
import com.th3web.lean.core.CoreManager
import com.th3web.lean.core.UrlTestPinger
import com.th3web.lean.data.net.BACKGROUND_FLUSH_MS
import com.th3web.lean.data.net.Pinger
import com.th3web.lean.data.net.runPingBurst
import com.th3web.lean.data.resolveProfileSelection
import com.th3web.lean.ui.LaunchScreen
import com.th3web.lean.ui.LeanRoot
import com.th3web.lean.ui.theme.LeanAppearance
import com.th3web.lean.ui.theme.LeanColors
import com.th3web.lean.ui.theme.LeanOptions
import com.th3web.lean.ui.theme.LeanTheme
import com.th3web.lean.ui.theme.appearanceSpec
import com.th3web.lean.ui.theme.nightWindowNow
import com.th3web.lean.ui.theme.wallpaperSeedOrNull
import com.th3web.lean.ui.tr

class MainActivity : ComponentActivity() {

    /**
     * Pending `lean://` deep-link verb ("connect" | "disconnect" | "toggle"),
     * fed from onCreate/onNewIntent (singleTask), and consumed by composition.
     */
    private var deepLinkVerb by mutableStateOf<String?>(null)

    /**
     * Pending subscription-import request from a `lean://import-sub` /
     * `lean://install-config` (Hiddify-style alias) link, fed from
     * onCreate/onNewIntent and consumed once by composition.
     * [url] is the already-URL-decoded subscription URL; [name] is optional.
     *
     * Anti-theft: the distributed URL already carries the {hwid} token + x-hwid
     * header (Http/HwId), so the panel binds the subscription to this device,
     * this link merely delivers that URL untouched; nothing here weakens it.
     */
    private var deepLinkImport by mutableStateOf<ImportRequest?>(null)

    private data class ImportRequest(val url: String, val name: String)

    override fun onCreate(savedInstanceState: Bundle?) {
        // Resolve the persisted look once, before anything draws, from the same
        // synchronously-read snapshot LeanTheme seeds itself with, so the window
        // chrome, the system-bar ink and every theme global agree from frame zero.
        val initial = LeanApp.instance.settings.initial
        val systemDark = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        val spec = initial.appearanceSpec(
            systemDark = systemDark,
            nightNow = nightWindowNow(initial),
            wallpaperSeed = wallpaperSeedOrNull(this, initial),
        )
        // The window background must match the chosen column, and it can only be set
        // before super.onCreate installs the decor. Otherwise a light-theme cold start
        // flashes the dark canvas first. values-night cannot do this: it follows the
        // system's dark mode, not our themeMode.
        if (spec.splashTheme) {
            setTheme(
                when (spec.mode) {
                    "light" -> R.style.Theme_Lean_Light
                    "amoled" -> R.style.Theme_Lean_Amoled
                    else -> R.style.Theme_Lean
                },
            )
        }
        applyEdgeToEdge(lightTheme = spec.light, ink = spec.sysbarInk)
        // Pre-seed every theme global before the first composition (idempotent;
        // LeanTheme's SideEffect re-applies the same spec and returns immediately) so
        // the first pass already sees the saved look instead of the shipping defaults.
        LeanAppearance.apply(spec)
        // Do not reconcile the launcher-icon alias here. The component
        // enabled-state is durable in PackageManager and is set live by the
        // Appearance picker (AppIcon.apply). [initial] is a process-lifetime
        // cached snapshot, so after the user changes the icon, the process
        // stays alive (DONT_KILL_APP): a re-entry's onCreate would read the
        // stale old value and disable the very alias this launch came through,
        // reverting the icon and crashing the relaunch. The picker is the single
        // source of truth; no launch-time re-apply is needed.
        super.onCreate(savedInstanceState)
        // Only parse the launch Intent on a fresh start: onCreate re-runs on every
        // recreation (rotation, consent dialog) with the same retained Intent, which
        // re-fired the connect/import, and addSubscription persists a fresh UUID
        // before fetch, so a deep-link sub got duplicated on every rotation. A new
        // deep link delivered to the running task already comes via onNewIntent.
        if (savedInstanceState == null) {
            deepLinkVerb = parseDeepLink(intent)
            deepLinkImport = parseImportLink(intent)
        }
        // Back on the root Home destination: background the task instead of
        // finishing, so the session view (state, quick-pick, ticking traffic)
        // survives an accidental back press. Registered before composition, so
        // the NavHost's own OnBackPressedCallback, added later, therefore
        // higher priority, and enabled only while inner screens can pop, keeps
        // owning (predictive) back on Servers/Settings/etc.; this one only
        // fires when nothing else consumes back, i.e. at root Home.
        onBackPressedDispatcher.addCallback(this) { moveTaskToBack(true) }
        setContent {
            LeanTheme {
                val context = LocalContext.current
                // rememberSaveable (not remember): survives an activity recreation
                // behind the system VPN-consent dialog, so approving consent still
                // connects the intended profile instead of no-op'ing on a lost id.
                var pendingProfileId by rememberSaveable { mutableStateOf<String?>(null) }

                // Follow live theme flips (Appearance hub): both of these are
                // snapshot state written by LeanTheme, so this recomposes on change
                // and re-skins the status/navigation-bar icon appearance. Reading
                // LeanOptions here is what keeps the «Системные панели» knob out of a
                // second settings collector, never collect the flow in a composable.
                val lightBars = LeanColors.light
                val barInk = LeanOptions.sysbarInk
                LaunchedEffect(lightBars, barInk) {
                    applyEdgeToEdge(lightTheme = lightBars, ink = barInk)
                }

                // VPN consent: prepare() returns an Intent the first time; we
                // connect once the user approves the system VPN dialog.
                val vpnConsent = rememberLauncherForActivityResult(
                    ActivityResultContracts.StartActivityForResult(),
                ) { result ->
                    if (result.resultCode == Activity.RESULT_OK) {
                        pendingProfileId?.let { CoreManager.connect(context, it) }
                    }
                    pendingProfileId = null
                }

                // Hoisted consent path, shared by the UI connect button and
                // the lean:// deep links (shortcuts route through deep links).
                val startConnect: (String) -> Unit = { profileId ->
                    val prepare = VpnService.prepare(context)
                    if (prepare != null) {
                        pendingProfileId = profileId
                        vpnConsent.launch(prepare)
                    } else {
                        CoreManager.connect(context, profileId)
                    }
                }

                // Ask for notification permission once (Android 13+), so the
                // foreground-service status notification is visible.
                val notifPermission = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestPermission(),
                ) { }
                LaunchedEffect(Unit) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        notifPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                    }
                }

                // On launch: re-ping, refresh subscriptions and/or auto-connect, per settings.
                LaunchedEffect(Unit) {
                    val app = LeanApp.instance
                    val s = app.settings.flow.first()
                    // «URL Test» measures through its own standalone core instance rather
                    // than a raw socket, so unlike TCP/ICMP/GET/HEAD it is unaffected by
                    // the unreliable protect() that makes the other probes meaningless
                    // while the tunnel is up, both bursts below exempt it from that gate.
                    val urlTestSelected = s.pingProtocol.equals(Pinger.URL_TEST_PROTOCOL, ignoreCase = true)
                    if (s.pingOnLaunch) {
                        // Re-ping all servers on app start (stale latencies, not
                        // just the untested ones the screens already cover),
                        // automatic, so excluded-from-test servers are skipped.
                        // Measure in parallel, then write every latency in one
                        // store emission (per-server writes thrashed the list).
                        // This is the single launch-time ping burst, the Home VM
                        // no longer also fires pingMissing() at init.
                        val targets = if (CoreManager.isActive && !urlTestSelected) {
                            emptyList()
                        } else {
                            app.profiles.state.value.profiles.filterNot { it.excludedFromTest }
                        }
                        if (targets.isNotEmpty()) {
                            val byId = targets.associateBy { it.id }
                            launch {
                                // Let the first frames land before the sweep starts. This
                                // effect runs while the launch screen is still up and the
                                // home screen is being composed for the first time, and a
                                // burst kicked off right here competed with exactly those
                                // frames, the reported "интерфейс лагает первые 10 секунд".
                                delay(LAUNCH_PING_SETTLE_MS)
                                // Shared with the Servers screen so both paths are bounded
                                // and run off the main thread. Unbounded, on the
                                // composition dispatcher, a large subscription means one
                                // coroutine per server resuming on the UI thread, and
                                // nothing published until the last
                                // probe returned.
                                runPingBurst(
                                    ids = targets.map { it.id },
                                    flushEveryMs = BACKGROUND_FLUSH_MS,
                                    measure = { id ->
                                        val p = byId.getValue(id)
                                        // url-overload: GET/HEAD honour the configured
                                        // pingUrl (real HTTP) instead of degrading to TCP,
                                        // and URL Test gets its real per-server probe here
                                        // too (automatic pings use the selected protocol
                                        // like any other path; the cost is bounded by
                                        // UrlTestPinger's own instance cap).
                                        Pinger.measure(
                                            p.outbound.server, p.outbound.serverPort, s.pingProtocol, s.pingTimeoutMs, s.pingUrl,
                                            udpService = Pinger.isUdpService(p.outbound), protect = CoreManager.probeProtect,
                                            outbound = p.outbound, urlTestProbe = UrlTestPinger::measure,
                                        )
                                    },
                                    publish = { app.profiles.updateLatencies(it) },
                                )
                                // Once, at the end: a full store serialization per batch
                                // is what made this sweep visible in the UI.
                                app.profiles.persistLatencies()
                            }
                        }
                    }
                    if (s.autoUpdate) {
                        val now = System.currentTimeMillis()
                        app.profiles.state.value.subscriptions.forEach { sub ->
                            // Honour the provider-pushed interval (15-min floor so a
                            // short interval never hammers the panel), but never let it
                            // hold the usage counter hostage.
                            //
                            // The interval a panel pushes is about its server list, which
                            // changes rarely, Remnawave commonly asks for a day. The
                            // traffic figure on the card comes from the same response and
                            // goes stale in minutes, so honouring the list interval for it
                            // meant the counter only ever moved when the user pulled to
                            // refresh by hand. Capped at USAGE_STALE_MS, the list still
                            // refreshes no more often than a few times an hour and the
                            // counter tracks reality.
                            val interval = sub.updateIntervalMs.takeIf { it > 0 }
                                ?.coerceAtLeast(900_000L)
                                ?.coerceAtMost(USAGE_STALE_MS)
                            if (interval == null || now - sub.lastUpdated >= interval) {
                                launch {
                                    val ok = app.profiles.updateSubscription(sub.id).isSuccess
                                    // Ping-on-update: re-ping this sub's freshly
                                    // reconciled servers after a successful refresh
                                    // (automatic, excluded-from-test skipped).
                                    // Connected/connecting routes the raw-socket probes
                                    // through the tunnel instead of past it (protect() is
                                    // unreliable there), so a measured "latency" would
                                    // silently be the proxy's own RTT, not the server's.
                                    // URL Test is exempt (own standalone core instance).
                                    if (ok && s.pingOnUpdate && (!CoreManager.isActive || urlTestSelected)) {
                                        val subTargets = app.profiles.state.value.profiles
                                            .filter { it.subscriptionId == sub.id && !it.excludedFromTest }
                                        if (subTargets.isNotEmpty()) {
                                            // Same bounded, off-main burst as the launch
                                            // one. Writes stay coalesced, runPingBurst
                                            // batches them on a clock, which is what keeps
                                            // recomposition from thrashing while still
                                            // showing progress on a long list.
                                            val subById = subTargets.associateBy { it.id }
                                            runPingBurst(
                                                ids = subTargets.map { it.id },
                                                flushEveryMs = BACKGROUND_FLUSH_MS,
                                                measure = { id ->
                                                    val p = subById.getValue(id)
                                                    Pinger.measure(
                                                        p.outbound.server, p.outbound.serverPort, s.pingProtocol, s.pingTimeoutMs, s.pingUrl,
                                                        udpService = Pinger.isUdpService(p.outbound), protect = CoreManager.probeProtect,
                                                        outbound = p.outbound, urlTestProbe = UrlTestPinger::measure,
                                                    )
                                                },
                                                publish = { app.profiles.updateLatencies(it) },
                                            )
                                            app.profiles.persistLatencies()
                                        }
                                    }
                                }
                            }
                        }
                    }
                    if (s.autoConnect && deepLinkVerb == null && !CoreManager.isActive) {
                        val pid = resolveProfileSelection(
                            savedId = s.selectedProfileId,
                            profiles = app.profiles.state.value.profiles,
                        )
                        // Only silently auto-connect when VPN consent is already granted.
                        if (pid != null && VpnService.prepare(context) == null) {
                            CoreManager.connect(context, pid)
                        }
                    }
                }

                // Deep links: lean://connect | lean://disconnect | lean://toggle.
                // Connect goes through the hoisted consent path, so a first-run
                // deep link still surfaces the system VPN dialog.
                val verb = deepLinkVerb
                LaunchedEffect(verb) {
                    if (verb == null) return@LaunchedEffect
                    // This effect runs only after composition is in place, so the
                    // vpnConsent launcher is already registered when startConnect
                    // fires the system VPN dialog. Consume the verb after dispatch so
                    // it can't be lost while the launcher is still being attached; a
                    // re-delivered verb still re-triggers because onNewIntent re-sets it.
                    when (verb) {
                        "disconnect" -> CoreManager.disconnect(context)
                        "connect" -> if (!CoreManager.isActive) {
                            Automation.resolveProfileId(LeanApp.instance)?.let(startConnect)
                        }
                        "toggle" -> if (CoreManager.isActive) {
                            CoreManager.disconnect(context)
                        } else {
                            Automation.resolveProfileId(LeanApp.instance)?.let(startConnect)
                        }
                    }
                    deepLinkVerb = null // consume AFTER the action is dispatched
                }

                // Deep link: lean://import-sub?url=<enc>&name=<opt> (and the
                // Hiddify-style alias lean://install-config/?url=...). Adds the
                // subscription via the existing repository API on a coroutine,
                // then confirms with a Toast. A missing/blank url no-ops (the
                // parser already returns null), so a malformed link is harmless.
                val importReq = deepLinkImport
                LaunchedEffect(importReq) {
                    if (importReq == null) return@LaunchedEffect
                    // Repository signature is addSubscription(name, url), name first.
                    val result = LeanApp.instance.profiles.addSubscription(importReq.name, importReq.url)
                    val msg = result.fold(
                        onSuccess = { count -> tr("Подписка добавлена: %d серверов").format(count) },
                        onFailure = { tr("Не удалось импортировать подписку") },
                    )
                    Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                    deepLinkImport = null // consume AFTER the import is dispatched
                }

                Box {
                    LeanRoot(
                        onConnect = startConnect,
                        onDisconnect = { CoreManager.disconnect(context) },
                    )
                    LaunchScreen()
                }
            }
        }
    }

    /**
     * Returning to the app re-checks the subscription usage counter.
     *
     * The launch effect only runs on a cold start, so during a long session the figure
     * would sit at whatever it was when the app was opened. The repository
     * applies the staleness floor and de-duplicates against the launch sweep, so this
     * costs nothing when the data is already fresh.
     */
    override fun onResume() {
        super.onResume()
        lifecycleScope.launch {
            val settings = LeanApp.instance.settings.state.value
            if (settings.autoUpdate) {
                LeanApp.instance.profiles.refreshStaleSubscriptions(USAGE_STALE_MS)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        parseDeepLink(intent)?.let { deepLinkVerb = it }
        parseImportLink(intent)?.let { deepLinkImport = it }
    }

    /**
     * Transparent edge-to-edge system bars with theme-matched icon ink:
     * [lightTheme] picks the light style (dark icons on the light canvas),
     * otherwise the dark style (light icons on the dark/AMOLED canvas).
     * Safe to call repeatedly, enableEdgeToEdge just re-applies the styles.
     *
     * [ink] is «Системные панели»: `auto` follows the canvas as above, while `light`
     * and `dark` name the icon colour outright. The override earns its keep on shells
     * that paint their own scrim behind the bars, there the ink that matches our canvas
     * is the one that disappears.
     */
    private fun applyEdgeToEdge(lightTheme: Boolean, ink: String = "auto") {
        val transparent = android.graphics.Color.TRANSPARENT
        // SystemBarStyle.light() = dark icons; .dark() = light icons.
        val darkIcons = when (ink) {
            "light" -> false
            "dark" -> true
            else -> lightTheme
        }
        if (darkIcons) {
            enableEdgeToEdge(
                statusBarStyle = SystemBarStyle.light(transparent, transparent),
                navigationBarStyle = SystemBarStyle.light(transparent, transparent),
            )
        } else {
            enableEdgeToEdge(
                statusBarStyle = SystemBarStyle.dark(transparent),
                navigationBarStyle = SystemBarStyle.dark(transparent),
            )
        }
    }

    /** lean://connect|disconnect|toggle → verb; anything else → null. */
    private fun parseDeepLink(intent: Intent?): String? {
        val data = intent?.data ?: return null
        if (intent.action != Intent.ACTION_VIEW) return null
        if (!"lean".equals(data.scheme, ignoreCase = true)) return null
        return data.host?.lowercase()
            ?.takeIf { it == "connect" || it == "disconnect" || it == "toggle" }
    }

    /**
     * lean://import-sub?url=<enc>&name=<opt> and the Hiddify-style alias
     * lean://install-config/?url=... → an [ImportRequest]; anything else → null.
     *
     * The 'url' query param is the (percent-encoded) subscription URL; Uri's
     * getQueryParameter already URL-decodes it, so the value handed to the
     * repository is the real URL. A missing/blank url returns null → graceful
     * no-op. 'name' is optional and defaults to empty (the repository then
     * derives a display name from the URL).
     */
    private fun parseImportLink(intent: Intent?): ImportRequest? {
        val data = intent?.data ?: return null
        if (intent.action != Intent.ACTION_VIEW) return null
        if (!"lean".equals(data.scheme, ignoreCase = true)) return null
        val host = data.host?.lowercase()
        if (host != "import-sub" && host != "install-config") return null
        // getQueryParameter decodes percent-encoding for us.
        val url = data.getQueryParameter("url")?.trim().orEmpty()
        if (url.isBlank()) return null
        val name = data.getQueryParameter("name")?.trim().orEmpty()
        return ImportRequest(url = url, name = name)
    }
}

/**
 * How long the launch-time ping sweep waits before it starts.
 *
 * Long enough for the launch screen to hand over and the home screen's first frames to
 * land. The sweep is bounded and runs off the main thread now, but it still competes for
 * the same CPU on a cold start, and this is a list the user has not even seen yet.
 */
private const val LAUNCH_PING_SETTLE_MS = 900L

/**
 * How stale the subscription usage counter may get before the app re-asks.
 *
 * Also the cap on a provider push interval: that interval describes the server list and is
 * commonly a day, which must not keep the traffic figure frozen for a day with it. Fifteen
 * minutes is the existing anti-hammering floor, so capping at it adds no load beyond what
 * a launch refresh could already do.
 */
private const val USAGE_STALE_MS = 900_000L

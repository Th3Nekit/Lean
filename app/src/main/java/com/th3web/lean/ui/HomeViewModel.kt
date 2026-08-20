package com.th3web.lean.ui

import android.widget.Toast
import androidx.annotation.VisibleForTesting
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import com.th3web.lean.LeanApp
import com.th3web.lean.core.CoreManager
import com.th3web.lean.core.TrafficStats
import com.th3web.lean.core.UrlTestPinger
import com.th3web.lean.core.VpnState
import com.th3web.lean.data.StoreData
import com.th3web.lean.data.model.Profile
import com.th3web.lean.data.model.Subscription
import com.th3web.lean.data.net.ConnectionChecker
import com.th3web.lean.data.net.Pinger
import com.th3web.lean.data.net.PingState
import com.th3web.lean.data.net.runPingBurst
import com.th3web.lean.data.resolveProfileSelection

/**
 * State of the Home «Проверить соединение» action, an end-to-end HTTP GET to
 * the configured test URL. When the VPN is up, app traffic is tunneled, so this
 * measures real through-the-tunnel connectivity (unlike per-server ping).
 */
sealed interface ConnCheckState {
    data object Idle : ConnCheckState
    data object Checking : ConnCheckState
    /** Reachable (basic GET ok), but the >16 KB survival test couldn't run/finish. */
    data class Ok(val ms: Int) : ConnCheckState
    /** Second phase: pulling >16 KB through the tunnel to test TSPU survival. */
    data object Stressing : ConnCheckState
    /** Reachable and the full >16 KB payload arrived, the connection endures. */
    data class Survived(val ms: Int, val kbps: Int) : ConnCheckState
    /** Reachable but the stream died after [kb] KB, the TSPU-after-16KB teardown. */
    data class Torn(val kb: Int) : ConnCheckState
    data object Timeout : ConnCheckState
    data object Offline : ConnCheckState
}

/**
 * One Home quick-pick group: a subscription's servers, or the manual
 * pseudo-group ([HomeViewModel.MANUAL_GROUP_ID]) for servers added by hand,
 * pre-sorted fastest-first by [buildQuickGroups]. @Immutable for the same
 * Compose-skippability reason as [Profile]: all fields are vals of stable
 * types and instances are replaced, never mutated, so the grouped lazy rows
 * stay cheap.
 */
@Immutable
data class QuickGroup(
    /** Subscription id, or [HomeViewModel.MANUAL_GROUP_ID] for manual servers. */
    val id: String,
    /**
     * Subscription display name; "" for the manual pseudo-group, the screen
     * renders «Свои серверы» itself so the label tracks a live language flip
     * (a tr() baked in here would freeze at flow-build time).
     */
    val name: String,
    /** The group's servers, fastest first (untested/unreachable last). */
    val servers: List<Profile>,
    /**
     * The backing [Subscription] for a real group, the screen's group header
     * (a SubscriptionCard) reads its meta/announce/traffic and drives refresh
     * from its id. Null for the manual pseudo-group. [Subscription] is itself
     * @Immutable, so this keeps [QuickGroup] Compose-stable.
     */
    val subscription: Subscription? = null,
)

/**
 * Backs the Home (connect) screen: live [state]/[traffic] from [CoreManager],
 * the [profiles] list with [selectedId], and on-demand latency testing, the
 * server-list-with-ping behaviour ported from Incy.
 */
class HomeViewModel : ViewModel() {

    companion object {
        /** Pseudo-subscription id for the manual-servers group in [quickGroups]. */
        const val MANUAL_GROUP_ID = "__manual__"

        /**
         * Pseudo-subscription id for the starred-servers group in [quickGroups].
         *
         * Its members also stay in their own subscription group: favourites
         * is a shortcut to servers the user picked out, not a place they are moved to,
         * and removing them from their source group would make that group look like it
         * had lost servers.
         */
        const val FAVORITES_GROUP_ID = "__favorites__"

        /**
         * Timeout for the end-to-end «Проверить соединение» HTTP check. Fixed and
         * independent of `settings.pingTimeoutMs` (which tunes per-server probes):
         * a generous-but-bounded budget that won't strand a slow tunnel yet keeps
         * the Home button responsive.
         */
        private const val CONN_CHECK_TIMEOUT_MS = 5_000
    }

    private val app = LeanApp.instance

    val state: StateFlow<VpnState> = CoreManager.state
    val traffic: StateFlow<TrafficStats> = CoreManager.traffic

    /**
     * When connected in "Авто" mode, the node the core's urltest currently picked,
     * as "<name> · <delay> мс" (null otherwise). The node tag is "node-<profileId>"
     * (stable, see [SingBoxConfig]), so the name is resolved by id: it stays
     * correct even if the list is reconciled/reordered/removed after connecting
     * (index lookups would point at whatever now sits at that slot).
     */
    val autoStatus: StateFlow<String?> =
        combine(CoreManager.groups, app.profiles.state) { groups, store ->
            val auto = groups.firstOrNull { it.tag == "auto" } ?: return@combine null
            val sel = auto.selected
            val id = sel.removePrefix("node-")
            val name = store.profiles.firstOrNull { it.id == id }?.name ?: sel
            val ms = auto.items.firstOrNull { it.tag == sel }?.delayMs?.takeIf { it >= 0 }
            if (ms != null) "$name · $ms мс" else name
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val profiles: StateFlow<List<Profile>> =
        app.profiles.state.map { it.profiles }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * Which server "Авто" will use, so the user always sees a name when "Авто" is
     * selected, not only once the core's urltest reports a pick. When connected
     * and the live pick is known, this is exactly [autoStatus] ("<name> · <ms> мс").
     * Otherwise it predicts the offline-fastest server (lowest non-negative
     * [Profile.latencyMs], skipping [Profile.excludedFromTest]) as "≈ <name>", so
     * the prediction matches the urltest's lowest-ping target. Null only when
     * there are no servers to choose from.
     */
    val autoPick: StateFlow<String?> =
        combine(state, autoStatus, app.profiles.state) { vpnState, status, store ->
            if (vpnState is VpnState.Connected && status != null) {
                status
            } else {
                val fastest = store.profiles
                    .filterNot { it.excludedFromTest }
                    .filter { (it.latencyMs ?: -1) >= 0 }
                    .minByOrNull { it.latencyMs ?: Int.MAX_VALUE }
                // Fall back to the first NON-excluded server when nothing has a
                // measured ping yet, so "Авто" names a target the urltest can
                // actually pick (the urltest config drops excluded servers). Last
                // resort is the unfiltered first, mirroring urltest's all-excluded
                // fallback.
                    ?: store.profiles.filterNot { it.excludedFromTest }.firstOrNull()
                    ?: store.profiles.firstOrNull()
                fastest?.let { tr("≈ %s").format(it.name) }
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val selectedId: StateFlow<String?> =
        combine(app.profiles.state, app.settings.flow) { store, settings ->
            resolveProfileSelection(settings.selectedProfileId, store.profiles)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** The selected server, or the first one if none is explicitly chosen. */
    val selected: StateFlow<Profile?> =
        combine(app.profiles.state, selectedId) { store, id ->
            store.profiles.find { it.id == id }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /**
     * Subscription-grouped quick-pick for Home: one [QuickGroup] per
     * subscription that has servers, plus the manual pseudo-group. The group
     * hosting the selected server leads; the rest order by their best ping;
     * Servers inside each group are fastest first (see [buildQuickGroups]).
     * Derived here, once per store/selection emission, never inside lazy item
     * lambdas: the ping burst replaces store.profiles wholesale, and per-row
     * grouping work would thrash recomposition during the burst.
     */
    val quickGroups: StateFlow<List<QuickGroup>> =
        combine(app.profiles.state, selectedId) { store, sel ->
            buildQuickGroups(store, sel)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _connCheck = MutableStateFlow<ConnCheckState>(ConnCheckState.Idle)

    /** Live state of the «Проверить соединение» action; see [ConnCheckState]. */
    val connCheck: StateFlow<ConnCheckState> = _connCheck.asStateFlow()

    private val _pinging = MutableStateFlow(false)

    /**
     * True while any ping burst ([pingAll]/[pingGroup]/[pingMissing]) is running,
     * so the Home ping pills can grey out and stop accepting taps (mirrors the
     * Servers tab's `pinging` guard). Also serves as a re-entry mutex: each burst
     * early-returns while this is true, so repeated taps can't launch overlapping
     * bursts of the same servers.
     */
    val pinging: StateFlow<Boolean> = _pinging.asStateFlow()

    private var pingJob: Job? = null

    /**
     * Says out loud when the measurement shown is not the one that was asked for.
     *
     * «URL Test» is the only probe that answers "does this node actually carry traffic".
     * When it cannot run, the number that appears comes from a plain TCP connect, which
     * succeeds against almost any open port, so a list of dead nodes reads as brisk and
     * green unless the substitution is stated.
     */
    private fun reportSubstitutions() {
        val count = PingState.substituted.value
        if (count <= 0) return
        CoreManager.appendLog(
            tr("⚠ URL Test не запустился для %d серверов — там показан обычный TCP-пинг, он не проверяет, что узел реально работает")
                .format(count),
        )
    }

    /**
     * Stops a sweep in progress.
     *
     * A burst of sixty probes at a few seconds each runs for over a minute, and every
     * entry point early-returns while one is running, so without this nothing else could
     * be tried until it finished. Whatever it already measured is kept; those writes are
     * persisted as they happen.
     */
    fun cancelPing() {
        pingJob?.cancel()
        pingJob = null
        PingState.clear()
    }


    private var connCheckJob: Job? = null

    // No init ping: the launch-time ping is fired once by MainActivity (gated by
    // pingOnLaunch, batched into a single store write). The VM reads the same
    // store, so the quick-pick latencies/"Авто" hint fill in when that burst
    // lands, without a second, racing burst from here. [pingMissing] stays
    // available for explicit refreshes.

    /**
     * Select a server (or [CoreManager.AUTO_PROFILE_ID]). Always persists the
     * choice; when the tunnel is already up (or coming up) it also re-issues
     * [CoreManager.connect] so the running core hot-reloads onto the new
     * profile in place, no teardown, no extra tap. When inactive nothing
     * connects: the selection just waits for the connect button.
     */
    fun select(id: String) {
        viewModelScope.launch {
            app.settings.setSelectedProfile(id)
            if (CoreManager.isActive) {
                CoreManager.connect(app, id)
            }
        }
    }

    /**
     * Ping only servers whose latency is still unknown (cheap, idempotent on
     * re-open). Automatic, servers excluded from the speed test are skipped.
     */
    fun pingMissing() {
        if (_pinging.value) return
        // Connected/connecting routes the raw-socket probes through the tunnel instead
        // of past it (protect() is unreliable there), so a measured "latency" would
        // silently be the proxy's own RTT, not the server's. Automatic call, skip
        // quietly, no toast (see pingAll/pingGroup/pingOne for the explicit,
        // user-facing version of this same guard). URL Test is exempt, see pingAll for
        // why, and note that this path is the automatic one: it needs no user action, so
        // when the exemption was unsound it broke the tunnel all by itself.
        if (CoreManager.isActive && !isUrlTestProtocol()) return
        pingJob = viewModelScope.launch {
            _pinging.value = true
            PingState.beginSweep()
            try {
                val s = app.settings.flow.first()
                val targets = app.profiles.state.value.profiles
                    .filter { it.latencyMs == null && !it.excludedFromTest }
                if (targets.isEmpty()) return@launch
                // Measure in parallel, then write all latencies in one store emission
                // (was one per server → a recomposition storm during the burst).
                val byId = targets.associateBy { it.id }
                runPingBurst(
                    ids = targets.map { it.id },
                    measure = { id ->
                        PingState.probing(id) {
                        val p = byId.getValue(id)
                        Pinger.measure(
                            p.outbound.server, p.outbound.serverPort, s.pingProtocol, s.pingTimeoutMs, s.pingUrl,
                            udpService = Pinger.isUdpService(p.outbound), protect = CoreManager.probeProtect,
                            outbound = p.outbound, urlTestProbe = UrlTestPinger::measure,
                        )
                        }
                    },
                    publish = { app.profiles.updateLatencies(it) },
                )
                app.profiles.persistLatencies()
            } finally {
                _pinging.value = false
                PingState.clear()
                reportSubstitutions()
            }
        }
    }

    /**
     * Ping every server in parallel and persist the measured latency. Bulk,
     * servers excluded from the speed test are skipped ([pingOne] still works).
     */
    fun pingAll() {
        if (_pinging.value) return
        // «URL Test» is exempt from the VPN-active gate because its standalone core
        // instance protect()s its sockets exactly like the main connection does, so it
        // dials past the active tunnel instead of through it.
        //
        // Read that as a condition, not a property of being standalone. Being a separate
        // instance buys nothing on its own: sing-box only attaches the protect Control
        // when the config asks for it, and SingBoxConfig.buildUrlTestConfig originally
        // did not. The exemption was therefore false, and since pingMissing applies it
        // automatically, every probe looped back through the live tunnel and killed all
        // traffic on it. If that flag ever leaves the probe config, this gate has to come
        // back with it, SingBoxConfigTest pins the flag so it cannot leave quietly.
        if (CoreManager.isActive && !isUrlTestProtocol()) {
            Toast.makeText(app, tr("Пинг недоступен, пока активен VPN"), Toast.LENGTH_SHORT).show()
            return
        }
        pingJob = viewModelScope.launch {
            _pinging.value = true
            PingState.beginSweep()
            try {
                val s = app.settings.flow.first()
                // Read the live source store (like [pingMissing]) rather than the
                // WhileSubscribed-derived [profiles] flow, whose `.value` can be the
                // stale initial emptyList() when nothing is collecting it.
                val targets = app.profiles.state.value.profiles.filterNot { it.excludedFromTest }
                if (targets.isEmpty()) return@launch
                val byId = targets.associateBy { it.id }
                runPingBurst(
                    ids = targets.map { it.id },
                    measure = { id ->
                        PingState.probing(id) {
                        val p = byId.getValue(id)
                        Pinger.measure(
                            p.outbound.server, p.outbound.serverPort, s.pingProtocol, s.pingTimeoutMs, s.pingUrl,
                            udpService = Pinger.isUdpService(p.outbound), protect = CoreManager.probeProtect,
                            outbound = p.outbound, urlTestProbe = UrlTestPinger::measure,
                        )
                        }
                    },
                    publish = { app.profiles.updateLatencies(it) },
                )
                app.profiles.persistLatencies()
            } finally {
                _pinging.value = false
                PingState.clear()
                reportSubstitutions()
            }
        }
    }

    /**
     * Ping every server of one quick-pick group in parallel and persist the
     * measured latencies, the same machinery as [pingAll], just scoped to the
     * passed [servers] (a [QuickGroup.servers] list). Like [pingAll] it skips
     * [Profile.excludedFromTest] and writes all latencies in one store emission
     * (so the group's rows re-sort once, not per-server). The header's «проверить
     * пинг» button wires straight to this with its own group's servers.
     */
    fun pingGroup(servers: List<Profile>) {
        if (_pinging.value) return
        // See pingAll's comment: URL Test is exempt from the VPN-active gate.
        if (CoreManager.isActive && !isUrlTestProtocol()) {
            Toast.makeText(app, tr("Пинг недоступен, пока активен VPN"), Toast.LENGTH_SHORT).show()
            return
        }
        pingJob = viewModelScope.launch {
            _pinging.value = true
            PingState.beginSweep()
            try {
                val s = app.settings.flow.first()
                val targets = servers.filterNot { it.excludedFromTest }
                if (targets.isEmpty()) return@launch
                val byId = targets.associateBy { it.id }
                runPingBurst(
                    ids = targets.map { it.id },
                    measure = { id ->
                        PingState.probing(id) {
                        val p = byId.getValue(id)
                        Pinger.measure(
                            p.outbound.server, p.outbound.serverPort, s.pingProtocol, s.pingTimeoutMs, s.pingUrl,
                            udpService = Pinger.isUdpService(p.outbound), protect = CoreManager.probeProtect,
                            outbound = p.outbound, urlTestProbe = UrlTestPinger::measure,
                        )
                        }
                    },
                    publish = { app.profiles.updateLatencies(it) },
                )
                app.profiles.persistLatencies()
            } finally {
                _pinging.value = false
                PingState.clear()
                reportSubstitutions()
            }
        }
    }

    /**
     * Ping a single server (per-row «Проверить пинг»). Manual and explicit, so
     * it ignores [Profile.excludedFromTest].
     */
    fun pingOne(profile: Profile) {
        // See pingAll's comment: URL Test is exempt from the VPN-active gate.
        if (CoreManager.isActive && !isUrlTestProtocol()) {
            Toast.makeText(app, tr("Пинг недоступен, пока активен VPN"), Toast.LENGTH_SHORT).show()
            return
        }
        viewModelScope.launch {
            val s = app.settings.flow.first()
            val ms = Pinger.measure(
                profile.outbound.server, profile.outbound.serverPort, s.pingProtocol, s.pingTimeoutMs, s.pingUrl,
                udpService = Pinger.isUdpService(profile.outbound), protect = CoreManager.probeProtect,
                outbound = profile.outbound, urlTestProbe = UrlTestPinger::measure,
            )
            app.profiles.updateLatency(profile.id, ms)
        }
    }

    private fun isUrlTestProtocol(): Boolean =
        app.settings.state.value.pingProtocol.equals(Pinger.URL_TEST_PROTOCOL, ignoreCase = true)

    /**
     * End-to-end connectivity check: HTTP GET to `settings.pingUrl` through the
     * active tunnel. Cycles Checking → Ok/Timeout/Offline, then auto-resets to
     * Idle after ~4 s. Re-tapping restarts the check (previous run cancelled).
     */
    fun checkConnection() {
        connCheckJob?.cancel()
        connCheckJob = viewModelScope.launch {
            _connCheck.value = ConnCheckState.Checking
            val s = app.settings.flow.first()
            // Fixed, independent budget for the end-to-end check, not
            // s.pingTimeoutMs: that knob tunes per-server ICMP/TCP probes (where a
            // user may set 10s for slow ICMP, or 1500ms for snappy sorting) and
            // letting it drive the Home button would either stall it for 10s or
            // time out a healthy-but-slow tunnel at 1500ms.
            val result = ConnectionChecker.check(s.pingUrl, CONN_CHECK_TIMEOUT_MS)
            when (result) {
                is ConnectionChecker.CheckResult.Timeout -> _connCheck.value = ConnCheckState.Timeout
                is ConnectionChecker.CheckResult.NoInternet -> _connCheck.value = ConnCheckState.Offline
                is ConnectionChecker.CheckResult.Ok -> {
                    // Reachable, now force real data flow to see if it survives past the
                    // ~16 KB TSPU cliff (a single generate_204 never trips it).
                    _connCheck.value = ConnCheckState.Stressing
                    _connCheck.value = when (val sust = ConnectionChecker.sustainedCheck()) {
                        is ConnectionChecker.SustainedResult.Survived ->
                            ConnCheckState.Survived(result.ms, sust.kbps)
                        is ConnectionChecker.SustainedResult.Torn ->
                            ConnCheckState.Torn(sust.bytes / 1024)
                        // Reachable but the sized endpoint didn't answer (e.g. blocked),
                        // fall back to the plain reachable result rather than a false "torn".
                        ConnectionChecker.SustainedResult.Failed -> ConnCheckState.Ok(result.ms)
                    }
                }
            }
            delay(5_000)
            _connCheck.value = ConnCheckState.Idle
        }
    }
}

/**
 * Within-group quick-pick order: strictly latency ascending, with untested
 * (null), and unreachable (-1) servers last, mirrors the "ping" branch of
 * ServersScreen.sortProfiles (no favorites-first: a starred slow server must
 * not outrank a faster one in a speed-ordered list).
 */
private val QuickFastestFirst =
    compareBy<Profile> { it.latencyMs == null || it.latencyMs < 0 } // untested/unreachable last
        .thenBy { it.latencyMs ?: Int.MAX_VALUE }

/**
 * Builds the Home quick-pick grouping from one store snapshot:
 *
 *  - one [QuickGroup] per subscription that has servers (empty subs are noise
 *    in a picker, so they emit nothing), plus the manual pseudo-group
 *    ([HomeViewModel.MANUAL_GROUP_ID]) for servers added by hand;
 *  - servers inside a group sort fastest-first ([QuickFastestFirst]);
 *  - the group hosting [selectedId] goes first; the remaining groups order by
 *    their best (minimum) live latency, a group with any live ping beats one
 *    with only nulls/timeouts, all-untested groups sink to the bottom.
 *
 * Selecting «Авто» ([CoreManager.AUTO_PROFILE_ID]) matches no group, so all
 * groups order purely by speed, exactly the intent.
 */
@VisibleForTesting
internal fun buildQuickGroups(store: StoreData, selectedId: String?): List<QuickGroup> {
    if (store.profiles.isEmpty()) return emptyList()
    val bySub = store.profiles.groupBy { it.subscriptionId }
    val groups = buildList {
        store.subscriptions.forEach { sub ->
            val servers = bySub[sub.id].orEmpty()
            if (servers.isNotEmpty()) {
                add(QuickGroup(id = sub.id, name = sub.displayName, servers = servers.sortedWith(QuickFastestFirst), subscription = sub))
            }
        }
        bySub[null]?.takeIf { it.isNotEmpty() }?.let { manual ->
            add(QuickGroup(id = HomeViewModel.MANUAL_GROUP_ID, name = "", servers = manual.sortedWith(QuickFastestFirst)))
        }
    }
    // Rows are already fastest-first, so the head's latency is the group's best
    // ping (null when even the head is untested/unreachable, i.e. the whole
    // group is). Precomputed once so the comparator stays allocation-cheap.
    val bestMs = groups.associate { g ->
        g.id to g.servers.first().latencyMs?.takeIf { it >= 0 }
    }
    val sorted = groups.sortedWith(
        compareByDescending<QuickGroup> { g -> selectedId != null && g.servers.any { it.id == selectedId } }
            .thenBy { bestMs[it.id] == null } // groups with no live ping sink
            .thenBy { bestMs[it.id] ?: Int.MAX_VALUE },
    )
    // Favourites lead, and are pinned there rather than sorted with the rest: the user
    // starred these to reach them quickly, so a group whose whole purpose is "the ones I
    // chose" must not drift down the screen because some other group happened to ping
    // better. Built from the same profiles, so a starred server appears both here and in
    // its own group.
    val favorites = store.profiles.filter { it.favorite }
    if (favorites.isEmpty()) return sorted
    return buildList {
        add(
            QuickGroup(
                id = HomeViewModel.FAVORITES_GROUP_ID,
                name = "",
                servers = favorites.sortedWith(QuickFastestFirst),
            ),
        )
        addAll(sorted)
    }
}

package com.th3web.lean.ui.screen

import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.th3web.lean.LeanApp
import com.th3web.lean.core.CoreManager
import com.th3web.lean.core.UrlTestPinger
import com.th3web.lean.core.VpnState
import com.th3web.lean.data.Settings
import com.th3web.lean.data.model.Folder
import com.th3web.lean.data.model.Profile
import com.th3web.lean.data.model.Subscription
import com.th3web.lean.data.net.PingState
import com.th3web.lean.data.net.Pinger
import com.th3web.lean.data.net.runPingBurst
import com.th3web.lean.data.parse.ShareLinks
import com.th3web.lean.data.resolveProfileSelection
import com.th3web.lean.ui.AWG_EDITOR_DESCRIPTION
import com.th3web.lean.ui.Routes
import com.th3web.lean.ui.components.LatencyMeter
import com.th3web.lean.ui.components.LeanBadge
import com.th3web.lean.ui.components.PingGlyph
import com.th3web.lean.ui.components.ServerRow
import com.th3web.lean.ui.components.SubscriptionCard
import com.th3web.lean.ui.components.SubscriptionServerRow
import com.th3web.lean.ui.components.TelegramPromoBanner
import com.th3web.lean.ui.components.latencyTier
import com.th3web.lean.ui.components.leanSectionStyle
import com.th3web.lean.ui.components.leanSectionText
import com.th3web.lean.ui.components.rememberLeanClipboard
import com.th3web.lean.ui.icons.LeanIcon
import com.th3web.lean.ui.icons.LeanIconImage
import com.th3web.lean.ui.openUrl
import com.th3web.lean.ui.theme.LeanColors
import com.th3web.lean.ui.theme.LeanCorner
import com.th3web.lean.ui.theme.LeanMetrics
import com.th3web.lean.ui.theme.LeanMotion
import com.th3web.lean.ui.theme.LeanOptions
import com.th3web.lean.ui.theme.LeanType
import com.th3web.lean.ui.theme.leanBackground
import com.th3web.lean.ui.theme.leanGlass
import com.th3web.lean.ui.theme.motionAllowed
import com.th3web.lean.ui.tr
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServersScreen(onBack: () -> Unit, onNavigate: (String) -> Unit = {}) {
    val app = LeanApp.instance
    val store by app.profiles.state.collectAsStateWithLifecycle()
    // Hot [state] (not the cold [flow] with a default initialValue): on a config change
    // (screen rotation) a cold flow re-subscribes and emits the DEFAULT Settings() for the
    // first frame until DataStore re-reads from disk, which flashed serverSort back to
    // «По умолчанию» (the "rotation resets my ping sort" bug). [state] already holds the
    // persisted value, so the first post-rotation frame is already correct.
    val settings by app.settings.state.collectAsStateWithLifecycle()
    val vpnState by CoreManager.state.collectAsStateWithLifecycle()
    val coreGroups by CoreManager.groups.collectAsStateWithLifecycle()
    // Real proxied per-node delays from the core's urltest ("auto" group), keyed by
    // profile id, only while connected. The rows prefer this over the edge probe so the
    // shown latency reflects whether the node actually carries traffic (delayMs < 0 = ✗).
    val liveDelays = remember(vpnState, coreGroups) {
        if (vpnState is VpnState.Connected) {
            // Nodes with no measurement yet are dropped, not mapped to a number: a row
            // with no live result must fall back to its own ping instead of claiming an
            // authoritative one it doesn't have.
            coreGroups.firstOrNull { it.tag == "auto" }?.items.orEmpty()
                .mapNotNull { node -> node.delayMs?.let { node.tag.removePrefix("node-") to it } }
                .toMap()
        } else {
            emptyMap()
        }
    }
    val scope = rememberCoroutineScope()
    val clipboard = rememberLeanClipboard()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    // Short transient toast helper, replaces a previous in-flight message so
    // rapid actions don't queue up.
    fun toast(message: String) {
        scope.launch {
            snackbar.currentSnackbarData?.dismiss()
            snackbar.showSnackbar(message)
        }
    }

    // Import a WireGuard / AmneziaWG «.conf» from the system file picker. «.conf»
    // has no registered MIME type, so we accept the broad set and parse by content
    // (the core's WgConfig sniffs the «[Interface]» header). The file is read and
    // imported off the main thread; feedback mirrors the snackbar pattern above.
    val importConfLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            // Resolve a profile name from the display name (strip a trailing
            // «.conf»); blank → the core falls back to the peer host.
            val displayName = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                        val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (idx >= 0 && cursor.moveToFirst()) cursor.getString(idx) else null
                    }
                }.getOrNull()
            }
            val name = displayName.orEmpty().substringBeforeLast('.').trim()
            val text = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                }.getOrNull()
            }
            if (text.isNullOrBlank()) {
                toast(tr("Не удалось разобрать файл"))
                return@launch
            }
            val result = app.profiles.importConfFile(text, name)
            when {
                result.count <= 0 -> toast(tr("Не удалось разобрать файл"))
                // AmneziaWG is now a first-class endpoint, so the import is a plain
                // success, just name the protocol that was recognised.
                result.isAmnezia -> toast(tr("Импортирован сервер AmneziaWG"))
                // A .conf carries exactly one peer; anything importing several servers
                // came from a config file that holds a list, so say how many rather than
                // naming a protocol that may not be the only one in the file.
                result.count == 1 -> toast(tr("Импортирован 1 сервер"))
                else -> toast("${tr("Импортировано серверов")}: ${result.count}")
            }
        }
    }

    // Search + protocol filter survive rotation / back-stack restore.
    var query by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableStateOf<String?>(null) }
    var showAddSub by remember { mutableStateOf(false) }
    var addSubError by remember { mutableStateOf(false) }
    // In-flight guards for the two add dialogs. A subscription fetch has a 45 s budget
    // and the dialogs showed no busy state, so «Добавить» stayed live for the whole
    // wait: a second tap started a second add, and each one persists a fresh
    // subscription row before the network call, duplicates that outlive a cancel.
    var addSubBusy by remember { mutableStateOf(false) }
    var showAddServer by remember { mutableStateOf(false) }
    var addServerError by remember { mutableStateOf(false) }
    var addServerBusy by remember { mutableStateOf(false) }

    // Context-menu driven dialogs.
    var editSub by remember { mutableStateOf<Subscription?>(null) }
    var editSubError by remember { mutableStateOf(false) }
    var deleteSub by remember { mutableStateOf<Subscription?>(null) }
    var renameTarget by remember { mutableStateOf<Profile?>(null) }
    var awgTarget by remember { mutableStateOf<Profile?>(null) }
    // Per-subscription "is refreshing" set, so the card's refresh glyph spins
    // for the real duration of updateSubscription (not a fixed 600ms burst).
    val refreshingSubs = remember { mutableStateMapOf<String, Boolean>() }

    val selectedId = resolveProfileSelection(settings.selectedProfileId, store.profiles)
    val protocols = remember(store.profiles) { store.profiles.map { it.outbound.protocol }.distinct() }

    // Per-subscription expand state lives on the screen, not in [SubscriptionCard]:
    // the screen owns the row items and decides whether to emit them. A missing key
    // means expanded, so a subscription nobody has touched stays open;
    // [isSubExpanded]/[toggleSub] are the only readers of that rule.
    //
    // Plain remember, not rememberSaveable: a SnapshotStateMap has no default Saver.
    val expandedSubs = remember { mutableStateMapOf<String, Boolean>() }
    fun isSubExpanded(id: String) = expandedSubs[id] ?: true
    fun toggleSub(id: String) { expandedSubs[id] = !isSubExpanded(id) }

    // Folders follow the same rule as subscriptions: absent means open, so one made just
    // now is not hiding what the user has only this second put into it.
    var newFolderName by remember { mutableStateOf("") }
    var showAddFolder by remember { mutableStateOf(false) }
    var renameFolderTarget by remember { mutableStateOf<Folder?>(null) }
    var deleteFolderTarget by remember { mutableStateOf<Folder?>(null) }
    var moveSubTarget by remember { mutableStateOf<Subscription?>(null) }

    val expandedFolders = remember { mutableStateMapOf<String, Boolean>() }
    fun isFolderExpanded(id: String) = expandedFolders[id] ?: true
    fun toggleFolder(id: String) { expandedFolders[id] = !isFolderExpanded(id) }

    // One control for the whole section. It collapses while anything is still open and
    // expands once everything is shut, so the same tap always does the thing the list is
    // not already doing, and a provider with fifty servers can be put away in one go.
    // Folders count too: with everything filed away, the section is one tap from tidy.
    val allSubsCollapsed = store.subscriptions.isNotEmpty() &&
        store.subscriptions.none { isSubExpanded(it.id) } &&
        store.folders.none { expandedFolders[it.id] ?: true }
    fun toggleAllSubs() {
        val expand = allSubsCollapsed
        store.subscriptions.forEach { expandedSubs[it.id] = expand }
        store.folders.forEach { expandedFolders[it.id] = expand }
    }

    // Group the whole profile list by subscriptionId once per store change, so
    // the manual list, each sub's slice, and each sub's total count index into
    // this map instead of re-scanning the full list per consumer (was O(subs ×
    // profiles) every recomposition; the ping burst replaces store.profiles on
    // every per-server write, so that scan ran N times).
    val bySub = remember(store.profiles) { store.profiles.groupBy { it.subscriptionId } }

    // Drop a stale protocol filter: if the selected protocol no longer exists
    // (all its servers were deleted / it vanished after a refresh), its chip is
    // gone too, without this the list would be stuck empty with no way back
    // except «Все».
    LaunchedEffect(protocols) {
        if (filter != null && filter !in protocols) filter = null
    }

    fun matches(p: Profile) =
        (filter == null || p.outbound.protocol == filter) &&
            (query.isBlank() || p.name.contains(query, true) || p.outbound.server.contains(query, true))

    // Single select path for every row (manual, subscription, «Авто»): always
    // persists the choice; when the tunnel is already up (or coming up) it also
    // re-issues CoreManager.connect so the running core hot-reloads onto the
    // new profile in place, no teardown, no extra tap. When inactive nothing
    // connects: the selection just waits for the Home connect button. Mirrors
    // HomeViewModel.select (this screen has no HomeViewModel instance).
    fun selectServer(id: String) {
        scope.launch {
            app.settings.setSelectedProfile(id)
            if (CoreManager.isActive) {
                CoreManager.connect(app, id)
            }
        }
    }

    // Single ping-all path (top bar, hero re-ping and the labelled button all
    // share it). Bulk, servers excluded from the speed test are skipped; the
    // per-row «Проверить пинг» (pingOne) still tests them on demand.
    var pinging by remember { mutableStateOf(false) }
    var pingJob by remember { mutableStateOf<Job?>(null) }
    // While the tunnel is up the raw-socket probes (TCP/ICMP/UDP) run through it rather
    // than past it (protect() is unreliable there), so a latency measured then is the
    // proxy's RTT wearing the server's name. One guard here covers every caller, the
    // explicit buttons and the automatic sweeps alike.
    //
    // «URL Test» is exempt: it dials over its own standalone core instance rather than
    // those raw sockets, so it measures correctly whatever the VPN state, and its cost is
    // bounded by UrlTestPinger's own concurrency limit.
    suspend fun pingProfiles(profiles: List<Profile>) {
        val currentSettings = app.settings.state.value
        val isUrlTest = currentSettings.pingProtocol.equals(Pinger.URL_TEST_PROTOCOL, ignoreCase = true)
        if (CoreManager.isActive && !isUrlTest) return
        val targets = profiles.filterNot { it.excludedFromTest }
        if (targets.isEmpty()) return
        val byId = targets.associateBy { it.id }
        runPingBurst(
            ids = targets.map { it.id },
            measure = { id ->
                val p = byId.getValue(id)
                Pinger.measure(
                    p.outbound.server,
                    p.outbound.serverPort,
                    currentSettings.pingProtocol,
                    currentSettings.pingTimeoutMs,
                    currentSettings.pingUrl,
                    udpService = Pinger.isUdpService(p.outbound),
                    protect = CoreManager.probeProtect,
                    outbound = p.outbound,
                    urlTestProbe = UrlTestPinger::measure,
                )
            },
            publish = { app.profiles.updateLatencies(it) },
        )
        // One disk flush for the whole sweep, not one per batch.
        app.profiles.persistLatencies()
    }

    fun pingAll() {
        // Set the guard synchronously (not inside the launched coroutine, which the Main
        // dispatcher runs on the next frame), so rapid taps before that frame can't each
        // start a full parallel burst.
        if (pinging) return
        val isUrlTest = settings.pingProtocol.equals(Pinger.URL_TEST_PROTOCOL, ignoreCase = true)
        if (CoreManager.isActive && !isUrlTest) {
            toast(tr("Пинг недоступен, пока активен VPN"))
            return
        }
        pinging = true
        pingJob = scope.launch {
            try {
                pingProfiles(app.profiles.state.value.profiles)
            } finally {
                pinging = false
                pingJob = null
            }
        }
    }

    // A sweep over a long list takes a while, and until now the only way out was to wait
    // it out: every entry point refused to start a second one, so a wrong protocol or
    // timeout could not be corrected either. Cancelling leaves the results already
    // published in place — they were written per batch, not at the end.
    fun cancelPingAll() {
        pingJob?.cancel()
        pingJob = null
        pinging = false
    }

    // Per-group sweeps, keyed by whatever the caller calls a group: a subscription id or
    // a folder id. Each keeps its own Job so one running sweep does not disable the button
    // on a different card, and so a second tap on the same card stops that sweep — the
    // one on a fifty-server provider is long enough that waiting it out is not an answer.
    val groupPingJobs = remember { mutableStateMapOf<String, Job>() }
    fun isGroupPinging(key: String) = groupPingJobs.containsKey(key)

    fun toggleGroupPing(key: String, profiles: List<Profile>) {
        groupPingJobs.remove(key)?.let {
            it.cancel()
            return
        }
        val isUrlTest = settings.pingProtocol.equals(Pinger.URL_TEST_PROTOCOL, ignoreCase = true)
        if (CoreManager.isActive && !isUrlTest) {
            toast(tr("Пинг недоступен, пока активен VPN"))
            return
        }
        if (profiles.isEmpty()) return
        groupPingJobs[key] = scope.launch {
            try {
                pingProfiles(profiles)
            } finally {
                groupPingJobs.remove(key)
            }
        }
    }

    // Ping just one server (per-row context menu «Проверить пинг»).
    fun pingOne(p: Profile) {
        val isUrlTest = settings.pingProtocol.equals(Pinger.URL_TEST_PROTOCOL, ignoreCase = true)
        if (CoreManager.isActive && !isUrlTest) {
            toast(tr("Пинг недоступен, пока активен VPN"))
            return
        }
        scope.launch {
            val ms = Pinger.measure(
                p.outbound.server, p.outbound.serverPort, settings.pingProtocol, settings.pingTimeoutMs, settings.pingUrl,
                udpService = Pinger.isUdpService(p.outbound), protect = CoreManager.probeProtect,
                outbound = p.outbound, urlTestProbe = UrlTestPinger::measure,
            )
            app.profiles.updateLatency(p.id, ms)
        }
    }

    // Copy a server's share-link (best-effort serializer; null = not representable).
    fun copyLink(p: Profile) {
        val link = ShareLinks.toShareLink(p)
        if (link != null) {
            clipboard.copy(link)
            toast(tr("Ссылка скопирована"))
        } else {
            toast(tr("Ссылку нельзя сформировать"))
        }
    }

    val manual = remember(bySub, filter, query, settings.serverSort) {
        sortProfiles(bySub[null].orEmpty().filter { matches(it) }, settings.serverSort)
    }
    // The AutoHero previews what the core's urltest will pick, excluded-from-
    // test servers can never win the auto race, so they don't compete here.
    // derivedStateOf so AutoHero only recomposes when the winning profile changes
    // (not on every intermediate per-server latency write during the ping burst).
    val best by remember {
        derivedStateOf {
            store.profiles.filter { (it.latencyMs ?: -1) >= 0 && !it.excludedFromTest }.minByOrNull { it.latencyMs ?: Int.MAX_VALUE }
        }
    }

    // Per-subscription view data, computed once per (grouping / filter / query /
    // sort / subscriptions) change rather than inside each items{} lambda. Every
    // per-server ping replaces store.profiles, so a sort left in the lambda re-runs
    // once per subscription per result. [filter]/[query] are in the key because
    // [matches] closes over them.
    val subViews = remember(bySub, store.subscriptions, filter, query, settings.serverSort) {
        store.subscriptions.map { sub ->
            val slice = bySub[sub.id].orEmpty()
            // Filtered + sorted view for the rows…
            val servers = sortProfiles(slice.filter { matches(it) }, settings.serverSort)
            // …but count the full subscription, not the filtered/searched view,
            // a filter must not make the meta line lie ("1 серверов" of 20).
            SubView(sub = sub, servers = servers, totalCount = slice.size)
        }
    }

    // Split once, in store order, so a subscription appears under exactly one header.
    // A folderId pointing at a folder that no longer exists reads as "loose" rather than
    // vanishing: the row has to stay reachable whatever the store says.
    val folderIds = remember(store.folders) { store.folders.map { it.id }.toSet() }
    val folderViews = remember(subViews, store.folders, folderIds) {
        store.folders
            .sortedWith(compareBy({ it.order }, { it.name.lowercase() }))
            .map { folder -> FolderView(folder, subViews.filter { it.sub.folderId == folder.id }) }
    }
    val looseSubViews = remember(subViews, folderIds) {
        subViews.filter { it.sub.folderId.isEmpty() || it.sub.folderId !in folderIds }
    }

    // Auto-ping on open (Settings.pingOnLaunch = "re-ping servers on app start").
    // Keyed on Unit so it runs every time the screen is entered, and re-tests
    // both untested (null), and previously-unreachable (-1) servers, a server
    // that timed out last time gets another chance without the manual button.
    // Automatic, so excluded-from-test servers are skipped.
    LaunchedEffect(Unit) {
        if (!settings.pingOnLaunch) return@LaunchedEffect
        val targets = app.profiles.state.value.profiles
            .filter { (it.latencyMs == null || it.latencyMs!! < 0) && !it.excludedFromTest }
        if (targets.isEmpty()) return@LaunchedEffect
        pingProfiles(targets)
    }

    // Read in composition, not inside the LazyColumn content builder below. The builder
    // would track it correctly either way, but every other input to that list arrives as a
    // plain capture and one that did not would be the odd one out to debug.
    val showBanner = LeanOptions.showBannerBlock
    // The rows animate the meter of whichever server is being measured right now.
    val pingingIds by PingState.inFlight.collectAsStateWithLifecycle()

    Scaffold(
        // The canvas is «Фон приложения» now, so the container is transparent and the
        // modifier under it paints, flat (today), vignette, gradient or grain. The top
        // bar goes transparent for the same reason: an opaque slab would cut the texture
        // off at the status bar.
        modifier = Modifier.leanBackground(),
        containerColor = Color.Transparent,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        LeanIconImage(LeanIcon.Back, tint = LeanColors.TextPrimary, modifier = Modifier.size(22.dp))
                    }
                },
                // No fontWeight override: titleLarge is already Bold on the brand face,
                // and an inline weight would silently outrank «Жирность».
                title = { Text(tr("Серверы")) },
                actions = {
                    // Sort lives in the in-body SortChipsRow (more discoverable);
                    // the top bar keeps only the ping-all refresh action.
                    IconButton(onClick = { if (pinging) cancelPingAll() else pingAll() }) {
                        if (pinging) {
                            Icon(
                                Icons.Outlined.Close,
                                contentDescription = tr("Отменить проверку"),
                                tint = LeanColors.TextSecondary,
                                modifier = Modifier.size(22.dp),
                            )
                        } else {
                            LeanIconImage(LeanIcon.Refresh, tint = LeanColors.TextSecondary, modifier = Modifier.size(22.dp))
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent,
                    scrolledContainerColor = LeanColors.Surface,
                    titleContentColor = LeanColors.TextPrimary,
                    navigationIconContentColor = LeanColors.TextPrimary,
                    actionIconContentColor = LeanColors.TextSecondary,
                ),
            )
        },
    ) { padding ->
        LazyColumn(
            // No stretch at the ends, see HomeScreen.
            overscrollEffect = null,
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(0.dp),
        ) {
            // Moved here from Home (owner's call): the promo talks about taking out
            // a subscription, and subscriptions are managed on this screen, on Home
            // it only crowded the connect button.
            //
            // «Блоки на главном → Баннер Telegram» drops the item, not just the banner.
            // The banner self-gates as well, but a self-gated banner would leave this
            // spacer behind as an orphan gap that reads as a layout bug.
            if (showBanner) {
                item(key = "telegram-promo", contentType = "promo") {
                    TelegramPromoBanner(onClick = { context.openUrl(TELEGRAM_BOT_URL) })
                    Spacer(Modifier.height(12.dp))
                }
            }

            // Search / filter pills / sort split into independently-keyed items
            // so a keystroke (query change) only recomposes the search field,
            // not the protocol-pill row or sort chips, and so inserting the
            // subscription items below never re-creates these header widgets.
            item(key = "search", contentType = "search") {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text(tr("Поиск серверов…"), color = LeanColors.TextTertiary) },
                    leadingIcon = { LeanIconImage(LeanIcon.Search, tint = LeanColors.TextSecondary, modifier = Modifier.size(19.dp)) },
                    trailingIcon = if (query.isNotEmpty()) {
                        {
                            IconButton(onClick = { query = "" }) {
                                Icon(
                                    Icons.Outlined.Close,
                                    contentDescription = tr("Очистить"),
                                    tint = LeanColors.TextSecondary,
                                    modifier = Modifier.size(19.dp),
                                )
                            }
                        }
                    } else {
                        null
                    },
                    singleLine = true,
                    shape = LeanCorner.Input,
                )
            }
            item(key = "filter-pills", contentType = "filter") {
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FilterPill(tr("Все"), filter == null) { filter = null }
                    protocols.forEach { proto -> FilterPill(proto, filter == proto) { filter = proto } }
                }
            }
            item(key = "sort-chips", contentType = "sort") {
                // Visible, labelled sort control in the list body, the single
                // discoverable Settings.serverSort selector.
                SortChipsRow(current = settings.serverSort) { mode ->
                    scope.launch { app.settings.setServerSort(mode) }
                }
            }

            // Auto / fastest hero
            item(key = "auto-hero", contentType = "hero") {
                AutoHero(
                    selected = selectedId == CoreManager.AUTO_PROFILE_ID,
                    bestName = best?.name,
                    bestMs = best?.latencyMs,
                    onSelect = { selectServer(CoreManager.AUTO_PROFILE_ID) },
                    onReping = ::pingAll,
                )
                Spacer(Modifier.height(14.dp))
            }

            // Labelled ping-all action (Incy's explicit "Ping" button convention)
            item(key = "ping-all", contentType = "ping-button") {
                FilledTonalButton(
                    onClick = { if (pinging) cancelPingAll() else pingAll() },
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.medium,
                    contentPadding = PaddingValues(vertical = 13.dp),
                ) {
                    LeanIconImage(LeanIcon.Pulse, tint = MaterialTheme.colorScheme.onSecondaryContainer, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(if (pinging) tr("Отменить проверку") else tr("Проверить пинг"))
                }
                Spacer(Modifier.height(14.dp))
            }

            // Subscriptions, flattened into the outer LazyColumn so the rows
            // virtualize. Each subscription emits: (a) one header item (the
            // SubscriptionCard chrome), (b) when expanded, its servers as their
            // own lazy items (so only on-screen rows compose, not all 50 at
            // once inside a single item), then (c) a trailing spacer item.
            //
            // A folder emits the same three-part shape for each subscription filed in it,
            // which is why this is one function called from both places rather than the
            // block copied twice.
            fun LazyListScope.subscriptionItems(view: SubView) {
                val sub = view.sub
                item(key = "sub-${sub.id}", contentType = "subscription") {
                    // Provider description when present; otherwise the full
                    // count. Computed here (not in the memo), so it tracks an
                    // I18n.lang flip: the heavy sort stays memoized above.
                    val metaLine = sub.description.ifBlank { tr("%d серверов").format(view.totalCount) }
                    SubscriptionCard(
                        modifier = Modifier.animateItem(),
                        name = sub.displayName,
                        meta = metaLine,
                        expanded = isSubExpanded(sub.id),
                        onToggleExpanded = { toggleSub(sub.id) },
                        // Card-level selection cue: this sub hosts the selected
                        // server. Computed against the full subscription slice
                        // (bySub), not the filtered/searched view.servers, a
                        // protocol filter or search query that hides the selected
                        // server must not drop the card's active outline.
                        active = selectedId != null && bySub[sub.id].orEmpty().any { it.id == selectedId },
                        isEmpty = view.totalCount == 0,
                        // Never fetched successfully: the card says so instead of the
                        // bare "no servers", which reads as the app losing them.
                        fetchFailed = view.totalCount == 0 && sub.lastUpdated == 0L,
                        announce = sub.announce,
                        refreshing = refreshingSubs[sub.id] == true,
                        pinging = isGroupPinging(sub.id),
                        onPing = { toggleGroupPing(sub.id, bySub[sub.id].orEmpty()) },
                        onRefresh = {
                            scope.launch {
                                refreshingSubs[sub.id] = true
                                val result = try {
                                    app.profiles.updateSubscription(sub.id)
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (e: Exception) {
                                    Result.failure(e)
                                } finally {
                                    refreshingSubs[sub.id] = false
                                }
                                if (result.isFailure) {
                                    toast(tr("Не удалось обновить подписку"))
                                }
                                pingProfiles(
                                    refreshedProfilesForPing(
                                        refreshSucceeded = result.isSuccess,
                                        pingOnUpdate = app.settings.state.value.pingOnUpdate,
                                        subscriptionId = sub.id,
                                        profiles = app.profiles.state.value.profiles,
                                    ),
                                )
                            }
                        },
                        usedBytes = sub.usedBytes,
                        totalBytes = sub.totalBytes,
                        expireEpochSec = sub.expireEpochSec,
                        onEdit = {
                            editSubError = false
                            editSub = sub
                        },
                        onCopyUrl = {
                            clipboard.copy(sub.url)
                            toast(tr("Ссылка скопирована"))
                        },
                        onDelete = { deleteSub = sub },
                            onMoveToFolder = { moveSubTarget = sub },
                        // Whole-subscription exclusion. True only when every server in
                        // it is excluded, so a subscription with a few hand-excluded
                        // servers still reads as taking part, which it does.
                        excludedFromTest = bySub[sub.id].orEmpty()
                            .let { it.isNotEmpty() && it.all(Profile::excludedFromTest) },
                        onToggleExcludedFromTest = {
                            val servers = bySub[sub.id].orEmpty()
                            val exclude = servers.any { !it.excludedFromTest }
                            scope.launch {
                                app.profiles.setSubscriptionExcludedFromTest(sub.id, exclude)
                            }
                            toast(
                                if (exclude) {
                                    tr("«%s» больше не участвует в тесте скорости").format(sub.name)
                                } else {
                                    tr("«%s» снова участвует в тесте скорости").format(sub.name)
                                },
                            )
                        },
                    )
                }
                // Server rows as their own lazy items, only when expanded, so
                // a collapsed sub contributes zero row compositions and an
                // expanded one only composes the rows currently on screen.
                if (isSubExpanded(sub.id)) {
                    // Section-scoped key, for the same reason the Home list needs
                    // one: every section is flattened into this single LazyColumn,
                    // so a key only has to be unique per section to look right and
                    // must be unique per list to not throw.
                    items(
                        view.servers,
                        key = { "sub-${sub.id}/${it.id}" },
                        contentType = { "server" },
                    ) { p ->
                        SubscriptionServerRow(
                            probing = p.id in pingingIds,
                            profile = p,
                            selected = p.id == selectedId,
                            onSelect = { selectServer(p.id) },
                            onPing = { pingOne(p) },
                            onRename = { renameTarget = p },
                            onCopyLink = { copyLink(p) },
                            onToggleFavorite = { scope.launch { app.profiles.toggleFavorite(p.id) } },
                            onToggleExcludeFromTest = { scope.launch { app.profiles.setExcludedFromTest(p.id, !p.excludedFromTest) } },
                            onEditAwg = { awgTarget = p },
                            liveDelayMs = liveDelays[p.id],
                        )
                    }
                }
                item(key = "sub-gap-${sub.id}", contentType = "gap") {
                    // When expanded, a bottom-rounded surfaceContainer strip
                    // closes the grouped card (header top-rounded + rows on
                    // surface + this footer = one continuous card).
                    if (isSubExpanded(sub.id)) {
                        // LeanCorner.CardBottom, not a 22dp literal: it has to stay
                        // the exact mirror of the header's CardTop, and the pair used
                        // to be the only radii in the app that ignored the ladder.
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .height(14.dp)
                                .leanGlass(LeanCorner.CardBottom, MaterialTheme.colorScheme.surfaceContainer),
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                }
            }

            if (store.subscriptions.isNotEmpty() || store.folders.isNotEmpty()) {
                item(key = "subs-label", contentType = "label") {
                    SectionLabelWithCollapse(
                        text = tr("Подписки"),
                        collapsed = allSubsCollapsed,
                        onToggle = ::toggleAllSubs,
                    )
                }

                // Folders first, then whatever was never filed. A folder that is empty
                // still draws: it is the thing the user just made, and a control that
                // leaves no trace until it is used reads as broken.
                folderViews.forEach { fv ->
                    item(key = "folder-${fv.folder.id}", contentType = "folder") {
                        FolderCard(
                            modifier = Modifier.animateItem(),
                            name = fv.folder.name,
                            count = fv.subs.size,
                            expanded = isFolderExpanded(fv.folder.id),
                            pinging = isGroupPinging(fv.folder.id),
                            onToggleExpanded = { toggleFolder(fv.folder.id) },
                            onPing = {
                                toggleGroupPing(
                                    fv.folder.id,
                                    fv.subs.flatMap { bySub[it.sub.id].orEmpty() },
                                )
                            },
                            onRename = { renameFolderTarget = fv.folder },
                            onDelete = { deleteFolderTarget = fv.folder },
                        )
                    }
                    if (isFolderExpanded(fv.folder.id)) {
                        fv.subs.forEach { subscriptionItems(it) }
                    }
                    item(key = "folder-gap-${fv.folder.id}", contentType = "gap") {
                        Spacer(Modifier.height(12.dp).animateItem())
                    }
                }

                looseSubViews.forEach { subscriptionItems(it) }
            }

            item(key = "add-sub", contentType = "add-row") {
                AddRow(tr("Добавить подписку")) {
                    addSubError = false
                    showAddSub = true
                }
                Spacer(Modifier.height(6.dp))
            }

            // Only once there is something to file. A folder with nothing to put in it is
            // an empty box, and offering one before the first subscription exists teaches
            // the wrong order of operations.
            if (store.subscriptions.isNotEmpty()) {
                item(key = "add-folder", contentType = "add-row") {
                    AddRow(tr("Создать папку")) {
                        newFolderName = ""
                        showAddFolder = true
                    }
                    Spacer(Modifier.height(6.dp))
                }
            }

            // Manual servers
            item(key = "manual-label", contentType = "label") { SectionLabel(tr("Свои серверы")) }
            if (manual.isEmpty()) {
                item(key = "manual-empty", contentType = "empty") {
                    Box(Modifier.fillMaxWidth().padding(vertical = 10.dp), contentAlignment = Alignment.Center) {
                        Text(
                            if (store.profiles.isEmpty()) tr("Серверов нет — добавьте подписку или сервер.") else tr("Своих серверов нет."),
                            color = LeanColors.TextSecondary,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            } else {
                // Standalone (Card) rows, a different shape from the nested
                // subscription rows, so a distinct contentType keeps the lazy
                // layout from trying to reuse a nested row's composition here.
                items(manual, key = { "manual/${it.id}" }, contentType = { "manual-server" }) { p ->
                    ServerRow(
                        probing = p.id in pingingIds,
                        profile = p,
                        selected = p.id == selectedId,
                        onClick = { selectServer(p.id) },
                        onDelete = { scope.launch { app.profiles.deleteProfile(p.id) } },
                        onPing = { pingOne(p) },
                        onRename = { renameTarget = p },
                        onCopyLink = { copyLink(p) },
                        onToggleFavorite = { scope.launch { app.profiles.toggleFavorite(p.id) } },
                        onToggleExcludeFromTest = { scope.launch { app.profiles.setExcludedFromTest(p.id, !p.excludedFromTest) } },
                        onEditAwg = { awgTarget = p },
                        liveDelayMs = liveDelays[p.id],
                    )
                    // «Строки списка» tunes the gap between standalone rows apart from
                    // the settings screens, people want this list denser than those.
                    Spacer(Modifier.height(LeanMetrics.serverRowGap))
                }
            }

            item(key = "add-server", contentType = "add-row") {
                AddRow(tr("Добавить сервер вручную")) {
                    addServerError = false
                    showAddServer = true
                }
                Spacer(Modifier.height(6.dp))
                // WireGuard/AmneziaWG «.conf», plus the helper protocols' own config
                // files (mieru JSON, naive JSON, a mihomo YAML with mieru proxies).
                // Everything is parsed by content, so the picker only has to let the
                // file through, «.conf» has no registered MIME type at all, hence the
                // broad set.
                // olcRTC gets its own entry: it has no host:port to type, so the
                // generic sheet above has no shape for it, a room, a shared key, a
                // provider and a transport instead.
                AddRow(tr("Создать сервер olcRTC")) { onNavigate(Routes.OLCRTC_NEW) }
                Spacer(Modifier.height(6.dp))
                AddRow(tr("Импорт из файла (.conf, .json, .yaml)")) {
                    importConfLauncher.launch(
                        arrayOf(
                            "application/octet-stream",
                            "application/json",
                            "text/plain",
                            "*/*",
                        ),
                    )
                }
                Spacer(Modifier.height(16.dp))
            }
        }
    }

    if (showAddSub) {
        AddSubscriptionDialog(
            error = addSubError,
            onDismiss = {
                showAddSub = false
                addSubError = false
            },
            busy = addSubBusy,
            onConfirm = { name, url ->
                if (url.isBlank() || addSubBusy) return@AddSubscriptionDialog
                scope.launch {
                    addSubBusy = true
                    val result = try {
                        app.profiles.addSubscription(name, url)
                    } finally {
                        addSubBusy = false
                    }
                    // addSubscription saves the URL before it fetches, so a failure has
                    // still added the subscription. Keeping the dialog open used to hide
                    // that: the user closed it believing nothing happened, and found a
                    // subscription there anyway. Say what actually happened and close.
                    showAddSub = false
                    addSubError = false
                    if (result.isSuccess) {
                        toast(tr("Добавлено серверов: %d").format(result.getOrDefault(0)))
                    } else {
                        toast(tr("Не удалось получить подписку — добавили её, попробуйте обновить"))
                    }
                }
            },
        )
    }

    if (showAddServer) {
        AddServerDialog(
            error = addServerError,
            onDismiss = {
                showAddServer = false
                addServerError = false
            },
            // importFromText returns the count of recognized links; 0 means
            // nothing was parsed, keep the dialog open with an inline error
            // instead of silently closing on garbage input.
            busy = addServerBusy,
            onConfirm = { text ->
                if (text.isBlank() || addServerBusy) return@AddServerDialog
                scope.launch {
                    addServerBusy = true
                    val n = try {
                        app.profiles.importFromText(text)
                    } finally {
                        addServerBusy = false
                    }
                    if (n > 0) {
                        showAddServer = false
                        addServerError = false
                        toast(tr("Добавлено серверов: %d").format(n))
                    } else {
                        addServerError = true
                    }
                }
            },
        )
    }

    // «Изменить», rename and/or re-point a subscription. The repo call is
    // atomic: a failed fetch of the new URL changes nothing, and the dialog
    // stays open with an inline error.
    editSub?.let { sub ->
        EditSubscriptionDialog(
            // A blank-named sub is persisted with name == url (addSubscription's
            // ifBlank { url } fallback). The card renders sub.displayName (which
            // hides the URL), but the raw sub.name is the URL, so show an empty
            // field instead of prefilling the URL as the name. The onConfirm
            // trim/ifBlank path re-derives name == url correctly on save.
            initialName = if (sub.name == sub.url) "" else sub.name,
            initialUrl = sub.url,
            error = editSubError,
            onDismiss = {
                editSub = null
                editSubError = false
            },
            onConfirm = { name, url ->
                scope.launch {
                    val urlChanged = url.trim() != sub.url
                    val result = app.profiles.editSubscription(sub.id, name, url)
                    if (result.isSuccess) {
                        editSub = null
                        editSubError = false
                        // Ping-on-update also applies when the URL was re-pointed
                        // (the sub's profiles were replaced from the new source).
                        // Automatic, excluded-from-test servers skipped.
                        if (urlChanged && app.settings.state.value.pingOnUpdate) {
                            pingProfiles(app.profiles.state.value.profiles.filter { it.subscriptionId == sub.id })
                        }
                    } else {
                        editSubError = true
                    }
                }
            },
        )
    }

    // «Удалить», confirm before dropping the subscription and its servers.
    deleteSub?.let { sub ->
        AlertDialog(
            onDismissRequest = { deleteSub = null },
            title = { Text(tr("Удалить подписку?")) },
            text = {
                Text(
                    tr("Подписка «%s» и все её серверы будут удалены.").format(sub.displayName),
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        deleteSub = null
                        scope.launch { app.profiles.deleteSubscription(sub.id) }
                    },
                ) { Text(tr("Удалить"), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { deleteSub = null }) { Text(tr("Отмена"), color = LeanColors.TextSecondary) } },
        )
    }

    // «Переименовать», durable for manual servers; best-effort for
    // subscription servers (the next refresh restores the provider name).
    if (showAddFolder) {
        FolderNameDialog(
            title = tr("Новая папка"),
            initialName = newFolderName,
            onDismiss = { showAddFolder = false },
            onConfirm = { name ->
                showAddFolder = false
                if (name.isNotBlank()) scope.launch { app.profiles.addFolder(name) }
            },
        )
    }

    renameFolderTarget?.let { folder ->
        FolderNameDialog(
            title = tr("Переименовать папку"),
            initialName = folder.name,
            onDismiss = { renameFolderTarget = null },
            onConfirm = { name ->
                renameFolderTarget = null
                if (name.isNotBlank()) scope.launch { app.profiles.renameFolder(folder.id, name) }
            },
        )
    }

    // Says what survives, because "Удалить папку" on its own reads as though the
    // subscriptions inside go with it.
    deleteFolderTarget?.let { folder ->
        AlertDialog(
            onDismissRequest = { deleteFolderTarget = null },
            title = { Text(tr("Удалить папку «%s»?").format(folder.name)) },
            text = { Text(tr("Подписки останутся — они вернутся в общий список.")) },
            confirmButton = {
                TextButton(onClick = {
                    deleteFolderTarget = null
                    scope.launch { app.profiles.deleteFolder(folder.id) }
                }) { Text(tr("Удалить"), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { deleteFolderTarget = null }) {
                    Text(tr("Отмена"), color = LeanColors.TextSecondary)
                }
            },
        )
    }

    moveSubTarget?.let { sub ->
        MoveToFolderDialog(
            folders = store.folders.sortedWith(compareBy({ it.order }, { it.name.lowercase() })),
            currentFolderId = sub.folderId,
            onDismiss = { moveSubTarget = null },
            onPick = { folderId ->
                moveSubTarget = null
                scope.launch { app.profiles.moveSubscriptionToFolder(sub.id, folderId) }
            },
            onCreateNew = {
                moveSubTarget = null
                newFolderName = ""
                showAddFolder = true
            },
        )
    }

    renameTarget?.let { p ->
        RenameServerDialog(
            initialName = p.name,
            onDismiss = { renameTarget = null },
            onConfirm = { name ->
                renameTarget = null
                scope.launch { app.profiles.renameProfile(p.id, name) }
            },
        )
    }

    // «Настроить AmneziaWG», toggle the awg endpoint / edit obfuscation params on a
    // WireGuard profile. Applied live when this profile is the active tunnel.
    awgTarget?.let { p ->
        val wg = p.outbound as? com.th3web.lean.data.model.Outbound.WireGuard
        if (wg == null) {
            awgTarget = null
        } else {
            AwgTuneDialog(
                initial = wg.awg,
                onDismiss = { awgTarget = null },
                onApply = { params ->
                    awgTarget = null
                    scope.launch {
                        app.profiles.setAwgParams(p.id, params)
                        // restart = true: this profile is the live tunnel and the point of
                        // the dialog is to apply the change now. Without it the coordinator
                        // treats "connect to what you are already on" as a no-op.
                        if (CoreManager.isActive && selectedId == p.id) {
                            CoreManager.connect(app, p.id, restart = true)
                        }
                    }
                },
            )
        }
    }
}

/**
 * Pre-computed per-subscription view data (memoized at screen scope), so the
 * filtered/sorted [servers] list and the full [totalCount] are derived once per
 * store/filter/sort change instead of inside each lazy item on every
 * recomposition. The screen indexes this when emitting the flattened rows. (The
 * meta-line string is formatted at the call site so it tracks an I18n.lang flip.)
 */
private data class SubView(
    val sub: Subscription,
    val servers: List<Profile>,
    val totalCount: Int,
)

/** A folder and the subscriptions filed in it, already in display order. */
private data class FolderView(
    val folder: Folder,
    val subs: List<SubView>,
)

/**
 * Sort modes mirror Incy's `server_sort_order`: default (store order) | ping |
 * name. Favorites-first is the first comparator tier in "default" and "name",
 * starred servers float to the top, then the mode's own ordering applies within
 * each tier (the sort is stable, so "default" keeps store order inside tiers).
 * "ping" is the exception: it sorts strictly by latency (untested/unreachable
 * last), a favourited slow server must not outrank a faster non-favourite.
 */
private fun sortProfiles(list: List<Profile>, mode: String): List<Profile> {
    val fav = compareByDescending<Profile> { it.favorite }
    return when (mode) {
        "ping" -> list.sortedWith(
            compareBy<Profile> { it.latencyMs == null || it.latencyMs < 0 } // untested/unreachable last
                .thenBy { it.latencyMs ?: Int.MAX_VALUE },
        )
        "name" -> list.sortedWith(fav.thenBy { it.name.lowercase() })
        else -> list.sortedWith(fav)
    }
}

/**
 * In-body sort selector: a Sort icon + one MD3 FilterChip per mode
 * («По умолчанию» · «По пингу» · «По имени»). The single sort control (the
 * duplicate top-bar dropdown was removed), writes the persisted
 * Settings.serverSort, and [sortProfiles] does the actual ordering (ping =
 * latency ascending, untested last).
 */
@Composable
private fun SortChipsRow(current: String, onSelect: (String) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(bottom = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LeanIconImage(LeanIcon.Sort, tint = LeanColors.TextSecondary, modifier = Modifier.size(18.dp))
        listOf(
            "default" to tr("По умолчанию"),
            "ping" to tr("По пингу"),
            "name" to tr("По имени"),
        ).forEach { (mode, label) ->
            FilterChip(
                selected = current == mode,
                onClick = { onSelect(mode) },
                label = { Text(label) },
                leadingIcon = if (current == mode) {
                    { LeanIconImage(LeanIcon.Check, tint = LeanColors.Accent, modifier = Modifier.size(16.dp)) }
                } else {
                    null
                },
            )
        }
    }
}

@Composable
private fun AddServerDialog(
    error: Boolean,
    busy: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var text by remember { mutableStateOf("") }
    val clipboard = rememberLeanClipboard()
    AlertDialog(
        onDismissRequest = onDismiss,
        // Dressed in the app's own tokens rather than Material's defaults: a stock M3
        // container at a stock radius reads as a dialog from another app.
        containerColor = LeanColors.Surface,
        titleContentColor = LeanColors.TextPrimary,
        textContentColor = LeanColors.TextSecondary,
        shape = LeanCorner.Sheet,
        title = { Text(tr("Добавить сервер")) },
        text = {
            Column {
                Text(
                    tr("Вставьте ссылку или несколько строк — можно и base64."),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(6.dp))
                // The scheme list belongs here, not in the sentence: it is a reference a
                // user scans, and inside the paragraph it made a wall of punctuation.
                Text(
                    SUPPORTED_LINK_SCHEMES,
                    style = MaterialTheme.typography.labelSmall,
                    color = LeanColors.TextTertiary,
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2,
                    maxLines = 5,
                    placeholder = { Text("vless://…", color = LeanColors.TextTertiary) },
                    shape = LeanCorner.Input,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = LeanColors.Accent,
                        unfocusedBorderColor = LeanColors.Outline,
                        focusedTextColor = LeanColors.TextPrimary,
                        unfocusedTextColor = LeanColors.TextPrimary,
                        cursorColor = LeanColors.Accent,
                    ),
                )
                Spacer(Modifier.height(10.dp))
                // One-tap clipboard import.
                OutlinedButton(
                    onClick = { clipboard.paste { pasted -> text = pasted } },
                    modifier = Modifier.fillMaxWidth(),
                    shape = LeanCorner.Button,
                    border = BorderStroke(1.dp, LeanColors.Outline),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = LeanColors.TextPrimary),
                ) { Text(tr("Вставить из буфера")) }
                if (error) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        tr("Не распознано ни одной ссылки."),
                        color = LeanColors.Error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(text) }, enabled = !busy) {
                Text(
                    if (busy) tr("Загрузка…") else tr("Добавить"),
                    color = if (busy) LeanColors.TextTertiary else LeanColors.Accent,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !busy) {
                Text(tr("Отмена"), color = LeanColors.TextSecondary)
            }
        },
    )
}

/** Quiet MD3 section caption, primary-tinted label (accent-budget structure moment). */
@Composable
private fun SectionLabel(text: String) {
    Text(
        leanSectionText(text),
        color = LeanColors.Accent,
        style = leanSectionStyle(),
        modifier = Modifier.fillMaxWidth().padding(start = 4.dp, top = 6.dp, bottom = 9.dp),
    )
}

/**
 * A section label with one action parked at its right edge.
 *
 * The label keeps its own padding rather than taking the Row's, so it sits exactly where
 * a plain [SectionLabel] does and the two read as one heading style. [collapsed] flips the
 * chevron, so the control says which way it will move the list.
 */
@Composable
private fun SectionLabelWithCollapse(text: String, collapsed: Boolean, onToggle: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            leanSectionText(text),
            color = LeanColors.Accent,
            style = leanSectionStyle(),
            modifier = Modifier.weight(1f).padding(start = 4.dp, top = 6.dp, bottom = 9.dp),
        )
        IconButton(onClick = onToggle, modifier = Modifier.size(32.dp)) {
            LeanIconImage(
                LeanIcon.Chev,
                tint = LeanColors.TextTertiary,
                modifier = Modifier
                    .size(18.dp)
                    .rotate(if (collapsed) -90f else 90f),
            )
        }
    }
}

@Composable
private fun AutoHero(
    selected: Boolean,
    bestName: String?,
    bestMs: Int?,
    onSelect: () -> Unit,
    onReping: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val container by animateColorAsState(
        targetValue = if (selected) scheme.secondaryContainer else LeanColors.Surface,
        // The fourth and last of the app's crossfaded colours (the other three are the
        // connect hero's). «Плавные переходы цвета → выкл» snaps them, which is what makes
        // the rest of the UI (which has always snapped) stop looking out of step.
        //
        // Gated on «Анимации» as well, in the shape the hero uses: `off` must mean
        // every tween in the app collapses to snap(), and one survivor on another screen is
        // how a motion setting ends up looking half-implemented.
        animationSpec = if (motionAllowed() && LeanOptions.colorCrossfade) {
            LeanMotion.tween<Color>(200)
        } else {
            snap<Color>()
        },
        label = "autoHeroContainer",
    )
    val autoShape = MaterialTheme.shapes.medium
    Card(
        onClick = onSelect,
        // Glass like the server rows below it: this tile was the last opaque slab on the
        // screen. The Card's own fill goes transparent so it does not cover the backdrop
        // the glass just drew; leanGlass falls back to exactly [container] when there is
        // no wallpaper, so the selected/unselected crossfade is unchanged.
        modifier = Modifier.fillMaxWidth().leanGlass(autoShape, container),
        shape = autoShape,
        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                LeanBadge(LeanIcon.Pulse, tint = LeanColors.Accent, size = 36.dp)
                Spacer(Modifier.width(12.dp))
                Text(
                    tr("Авто · быстрейший"),
                    color = if (selected) MaterialTheme.colorScheme.onSecondaryContainer else LeanColors.TextPrimary,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                if (selected) {
                    LeanIconImage(LeanIcon.Check, tint = LeanColors.Accent, modifier = Modifier.size(24.dp))
                    Spacer(Modifier.width(4.dp))
                }
                IconButton(onClick = onReping) {
                    LeanIconImage(LeanIcon.Refresh, tint = LeanColors.TextSecondary, modifier = Modifier.size(18.dp))
                }
            }
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    // "По последнему пингу", not "сейчас быстрейший": this is the
                    // local TCP-connect winner. The core re-tests via its own
                    // urltest (HTTP through the proxy) at connect time and may
                    // land on a different node, see the subtitle below.
                    bestName?.let { tr("Быстрейший по пингу: %s").format(it) } ?: tr("Нет данных о пинге"),
                    color = LeanColors.TextSecondary,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
                val (c, bars) = latencyTier(
                    ms = bestMs,
                    accent = scheme.primary,
                )
                LatencyMeter(bestMs, c, bars)
            }
            Spacer(Modifier.height(6.dp))
            Text(tr("Ядро само выберет лучший сервер по пингу при подключении"), color = LeanColors.TextTertiary, style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
private fun AddRow(label: String, onClick: () -> Unit) {
    val outline = LeanMetrics.outlineWidth
    OutlinedButton(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        // null, not a zero-width stroke, OutlinedButton feeds it straight to
        // Modifier.border, which still installs a draw node at 0dp.
        border = if (outline > 0.dp) BorderStroke(outline, MaterialTheme.colorScheme.outline) else null,
        colors = ButtonDefaults.outlinedButtonColors(contentColor = LeanColors.TextSecondary),
        contentPadding = PaddingValues(vertical = 13.dp),
    ) {
        LeanIconImage(LeanIcon.Plus, tint = LeanColors.TextSecondary, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(label)
    }
}

@Composable
private fun AddSubscriptionDialog(
    error: Boolean,
    busy: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (String, String) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    val clipboard = rememberLeanClipboard()
    val scheme = MaterialTheme.colorScheme

    // The link is the only thing that matters, so it comes first and gets the focus.
    // Name was on top before, which asked the user to invent one for a provider they had
    // not added yet — and almost every subscription carries its own title anyway.
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

    val looksLikeLink = url.isBlank() || url.trim().let {
        it.startsWith("http://", true) || it.startsWith("https://", true) || it.startsWith("ss://", true) ||
            it.startsWith("vless://", true) || it.startsWith("vmess://", true) || it.startsWith("trojan://", true)
    }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(tr("Новая подписка")) },
        text = {
            Column {
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    modifier = Modifier.fillMaxWidth().focusRequester(focus),
                    singleLine = true,
                    enabled = !busy,
                    isError = !looksLikeLink,
                    label = { Text(tr("Ссылка подписки")) },
                    placeholder = { Text("https://…", color = LeanColors.TextTertiary) },
                    shape = LeanCorner.Input,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
                    // Paste lives inside the field instead of taking a full-width button
                    // of its own: it acts on this field, and the old button said so only
                    // by sitting near it.
                    trailingIcon = {
                        IconButton(
                            onClick = { clipboard.paste { pasted -> url = pasted.trim() } },
                            enabled = !busy,
                        ) {
                            LeanIconImage(
                                LeanIcon.Layers,
                                tint = LeanColors.TextSecondary,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    },
                )
                if (!looksLikeLink) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        tr("Похоже, это не ссылка. Обычно она начинается с https://"),
                        color = LeanColors.TextTertiary,
                        style = LeanType.meta,
                    )
                }

                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    enabled = !busy,
                    label = { Text(tr("Название")) },
                    placeholder = { Text(tr("Возьмётся из подписки"), color = LeanColors.TextTertiary) },
                    shape = LeanCorner.Input,
                )

                // A fetch over a slow link takes seconds, and a dialog that only greys
                // its button out during them reads as having ignored the tap.
                if (busy) {
                    Spacer(Modifier.height(14.dp))
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Spacer(Modifier.height(8.dp))
                    Text(
                        tr("Загружаем список серверов…"),
                        color = LeanColors.TextSecondary,
                        style = LeanType.meta,
                    )
                }

                if (error) {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        tr("Не удалось получить подписку. Проверьте ссылку и подключение к сети."),
                        color = scheme.error,
                        style = LeanType.meta,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(name, url) }, enabled = !busy && url.isNotBlank()) {
                Text(
                    tr("Добавить"),
                    color = if (busy || url.isBlank()) LeanColors.TextTertiary else LeanColors.Accent,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !busy) {
                Text(tr("Отмена"), color = LeanColors.TextSecondary)
            }
        },
    )
}

/**
 * Clone of [AddSubscriptionDialog], prefilled, «Изменить» from the sub-level
 * ⋮ menu. Same URL → rename only (no network); new URL → atomic re-fetch in
 * the repo. [error] keeps the dialog open with an inline message when the new
 * URL could not be fetched (nothing was changed).
 */
@Composable
private fun EditSubscriptionDialog(
    initialName: String,
    initialUrl: String,
    error: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (String, String) -> Unit,
) {
    var name by remember(initialName) { mutableStateOf(initialName) }
    var url by remember(initialUrl) { mutableStateOf(initialUrl) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr("Изменить подписку")) },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    placeholder = { Text(tr("Название (необязательно)"), color = LeanColors.TextTertiary) },
                    shape = LeanCorner.Input,
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    placeholder = { Text(tr("https://… ссылка подписки"), color = LeanColors.TextTertiary) },
                    shape = LeanCorner.Input,
                )
                if (error) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        tr("Не удалось загрузить подписку — изменения не сохранены."),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(name, url) }) { Text(tr("Сохранить"), color = LeanColors.Accent) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("Отмена"), color = LeanColors.TextSecondary) } },
    )
}

/** «Переименовать» from the per-server context menu. Blank input = no-op in the repo. */
/** Create or rename a folder. One dialog for both, only the title differs. */
@Composable
private fun FolderNameDialog(
    title: String,
    initialName: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var name by remember(initialName) { mutableStateOf(initialName) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                placeholder = { Text(tr("Название папки"), color = LeanColors.TextTertiary) },
                shape = LeanCorner.Input,
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(name.trim()) }, enabled = name.isNotBlank()) {
                Text(tr("Сохранить"), color = LeanColors.Accent)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("Отмена"), color = LeanColors.TextSecondary) } },
    )
}

/**
 * Pick the folder a subscription belongs to.
 *
 * «Без папки» is an entry rather than a separate menu action, because taking something
 * out of a folder is the same decision as putting it in another one. The current folder
 * is marked, so the list also answers "where is this one now".
 */
@Composable
private fun MoveToFolderDialog(
    folders: List<Folder>,
    currentFolderId: String,
    onDismiss: () -> Unit,
    onPick: (String) -> Unit,
    onCreateNew: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr("Переместить в папку")) },
        text = {
            Column(Modifier.fillMaxWidth()) {
                FolderPickRow(tr("Без папки"), selected = currentFolderId.isEmpty()) { onPick("") }
                folders.forEach { folder ->
                    FolderPickRow(folder.name, selected = folder.id == currentFolderId) { onPick(folder.id) }
                }
                TextButton(onClick = onCreateNew, modifier = Modifier.fillMaxWidth()) {
                    Text(tr("Создать папку"), color = LeanColors.Accent)
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("Отмена"), color = LeanColors.TextSecondary) } },
    )
}

@Composable
private fun FolderPickRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 12.dp),
    ) {
        Text(label, style = LeanType.rowTitle, color = LeanColors.TextPrimary, modifier = Modifier.weight(1f))
        if (selected) {
            LeanIconImage(LeanIcon.Check, tint = LeanColors.Accent, modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
private fun RenameServerDialog(
    initialName: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var name by remember(initialName) { mutableStateOf(initialName) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr("Переименовать сервер")) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                placeholder = { Text(tr("Название"), color = LeanColors.TextTertiary) },
                shape = LeanCorner.Input,
            )
        },
        confirmButton = { TextButton(onClick = { onConfirm(name) }) { Text(tr("Сохранить"), color = LeanColors.Accent) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("Отмена"), color = LeanColors.TextSecondary) } },
    )
}

/**
 * «Настроить AmneziaWG», turns the awg endpoint on/off for a WireGuard profile and
 * edits its obfuscation. Junk (Jc/Jmin/Jmax) is client-side only and masks the WG
 * handshake from DPI against any server; S1/S2 and the H1-H4 magic headers must match
 * the server. Switch off => null => plain wireguard. Switch on with blank fields =>
 * AmneziaParams() => awg endpoint with no obfuscation. S3/S4 and I1-I5 (rarely edited
 * by hand) are preserved from the existing config.
 */
@Composable
private fun AwgTuneDialog(
    initial: com.th3web.lean.data.model.AmneziaParams?,
    onDismiss: () -> Unit,
    onApply: (com.th3web.lean.data.model.AmneziaParams?) -> Unit,
) {
    var on by remember { mutableStateOf(initial != null) }
    fun ns(i: Int) = if (i == 0) "" else i.toString()
    var jc by remember { mutableStateOf(ns(initial?.jc ?: 0)) }
    var jmin by remember { mutableStateOf(ns(initial?.jmin ?: 0)) }
    var jmax by remember { mutableStateOf(ns(initial?.jmax ?: 0)) }
    var s1 by remember { mutableStateOf(ns(initial?.s1 ?: 0)) }
    var s2 by remember { mutableStateOf(ns(initial?.s2 ?: 0)) }
    var h1 by remember { mutableStateOf(initial?.h1 ?: "") }
    var h2 by remember { mutableStateOf(initial?.h2 ?: "") }
    var h3 by remember { mutableStateOf(initial?.h3 ?: "") }
    var h4 by remember { mutableStateOf(initial?.h4 ?: "") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr("Настроить AmneziaWG")) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(tr("Режим AmneziaWG"), modifier = Modifier.weight(1f), color = LeanColors.TextPrimary)
                    Switch(checked = on, onCheckedChange = { on = it })
                }
                Text(
                    tr(AWG_EDITOR_DESCRIPTION),
                    style = MaterialTheme.typography.bodySmall,
                    color = LeanColors.TextTertiary,
                )
                if (on) {
                    Spacer(Modifier.height(4.dp))
                    TextButton(onClick = { jc = "4"; jmin = "40"; jmax = "70" }) {
                        Text(tr("Пресет: обход DPI (junk)"), color = LeanColors.Accent)
                    }
                    AwgField("Jc", jc) { jc = it }
                    AwgField("Jmin", jmin) { jmin = it }
                    AwgField("Jmax", jmax) { jmax = it }
                    AwgField("S1", s1) { s1 = it }
                    AwgField("S2", s2) { s2 = it }
                    AwgField("H1", h1) { h1 = it }
                    AwgField("H2", h2) { h2 = it }
                    AwgField("H3", h3) { h3 = it }
                    AwgField("H4", h4) { h4 = it }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (!on) {
                    onApply(null)
                } else {
                    onApply(
                        com.th3web.lean.data.model.AmneziaParams(
                            jc = jc.toIntOrNull() ?: 0,
                            jmin = jmin.toIntOrNull() ?: 0,
                            jmax = jmax.toIntOrNull() ?: 0,
                            s1 = s1.toIntOrNull() ?: 0,
                            s2 = s2.toIntOrNull() ?: 0,
                            s3 = initial?.s3 ?: 0,
                            s4 = initial?.s4 ?: 0,
                            h1 = h1.trim(), h2 = h2.trim(), h3 = h3.trim(), h4 = h4.trim(),
                            i1 = initial?.i1 ?: "", i2 = initial?.i2 ?: "", i3 = initial?.i3 ?: "",
                            i4 = initial?.i4 ?: "", i5 = initial?.i5 ?: "",
                        ),
                    )
                }
            }) { Text(tr("Применить"), color = LeanColors.Accent) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("Отмена"), color = LeanColors.TextSecondary) } },
    )
}

/** One labelled field in [AwgTuneDialog]; blank => the knob is omitted from the config. */
@Composable
private fun AwgField(label: String, value: String, onValueChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        label = { Text(label) },
        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
        shape = LeanCorner.Input,
    )
}

@Composable
private fun FilterPill(text: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(text) },
    )
}

/**
 * Every scheme [com.th3web.lean.data.parse.ShareLinks] accepts, as a scannable line.
 *
 * Kept beside the dialog that shows it so the two cannot drift: a scheme added to the
 * parser but missing here is a format users are never told works.
 */
private const val SUPPORTED_LINK_SCHEMES =
    "vless · vmess · trojan · ss · hysteria2 · tuic · naive · mieru · olcrtc"

/**
 * A folder header: a name, how many subscriptions are inside, and a menu.
 *
 * Deliberately lighter than [SubscriptionCard] — flat, no traffic strip, no glass. A
 * folder is a divider the user drew, and drawing it as loudly as the things it contains
 * would make the list read as two levels of the same thing.
 */
@Composable
private fun FolderCard(
    modifier: Modifier = Modifier,
    name: String,
    count: Int,
    expanded: Boolean,
    pinging: Boolean,
    onToggleExpanded: () -> Unit,
    onPing: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    var menuOpen by remember { mutableStateOf(false) }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .clickable(onClick = onToggleExpanded)
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        LeanIconImage(
            LeanIcon.Layers,
            tint = LeanColors.Accent,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(name, style = LeanType.rowTitle, color = LeanColors.TextPrimary)
            Text(
                tr("%d подписок").format(count),
                style = LeanType.meta,
                color = LeanColors.TextSecondary,
            )
        }
        // Pings every server of every subscription inside, and stops on a second tap.
        IconButton(onClick = onPing) {
            PingGlyph(pinging)
        }
        Box {
            IconButton(onClick = { menuOpen = true }) {
                LeanIconImage(LeanIcon.Dots, tint = LeanColors.TextSecondary, modifier = Modifier.size(20.dp))
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(
                    text = { Text(tr("Переименовать")) },
                    onClick = { menuOpen = false; onRename() },
                )
                DropdownMenuItem(
                    text = { Text(tr("Удалить папку"), color = scheme.error) },
                    onClick = { menuOpen = false; onDelete() },
                )
            }
        }
        // Animated, so the header shows the list moving rather than snapping.
        val chevron by animateFloatAsState(
            targetValue = if (expanded) 90f else -90f,
            animationSpec = LeanMotion.tween(200),
            label = "folder-chevron",
        )
        LeanIconImage(
            LeanIcon.Chev,
            tint = LeanColors.TextTertiary,
            modifier = Modifier.size(16.dp).rotate(chevron),
        )
    }
}

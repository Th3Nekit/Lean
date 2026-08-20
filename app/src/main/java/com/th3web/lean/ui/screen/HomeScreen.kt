package com.th3web.lean.ui.screen

import android.widget.Toast
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.EaseInOutSine
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.snap
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.StateFlow
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import com.th3web.lean.LeanApp
import com.th3web.lean.R
import com.th3web.lean.core.CoreManager
import com.th3web.lean.core.TrafficStats
import com.th3web.lean.core.VpnState
import com.th3web.lean.data.model.Profile
import com.th3web.lean.data.parse.ShareLinks
import com.th3web.lean.ui.ConnCheckState
import com.th3web.lean.ui.HomeViewModel
import com.th3web.lean.ui.components.rememberLeanClipboard
import com.th3web.lean.ui.formatBytes
import com.th3web.lean.ui.openUrl
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.contentColorFor
import androidx.compose.runtime.derivedStateOf
import androidx.compose.ui.graphics.RectangleShape
import com.th3web.lean.ui.theme.leanGlass
import com.th3web.lean.data.net.PingState
import com.th3web.lean.ui.tr
import com.th3web.lean.ui.components.BackgroundWorkCard
import com.th3web.lean.ui.components.LeanBadge
import com.th3web.lean.ui.components.LeanDivider
import com.th3web.lean.ui.components.PingGlyph
import com.th3web.lean.ui.components.SubscriptionCard
import com.th3web.lean.ui.components.SubscriptionServerRow
import com.th3web.lean.ui.icons.LeanIcon
import com.th3web.lean.ui.icons.LeanIconImage
import com.th3web.lean.ui.theme.LeanColors
import com.th3web.lean.ui.theme.leanBackground
import com.th3web.lean.ui.theme.LeanCorner
import com.th3web.lean.ui.theme.LeanMetrics
import com.th3web.lean.ui.theme.LeanMotion
import com.th3web.lean.ui.theme.LeanOptions
import com.th3web.lean.ui.theme.LeanType
import com.th3web.lean.ui.theme.depthShadow
import com.th3web.lean.ui.theme.motionAllowed
import com.th3web.lean.ui.theme.rememberTapHaptic
import com.th3web.lean.ui.theme.systemAnimatorsEnabled

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onConnect: (String) -> Unit,
    onDisconnect: () -> Unit,
    onOpenServers: () -> Unit,
    onOpenSettings: () -> Unit,
    vm: HomeViewModel = viewModel(),
) {
    val app = LeanApp.instance
    val state by vm.state.collectAsStateWithLifecycle()
    val selected by vm.selected.collectAsStateWithLifecycle()
    val selectedId by vm.selectedId.collectAsStateWithLifecycle()
    val profiles by vm.profiles.collectAsStateWithLifecycle()
    val autoPick by vm.autoPick.collectAsStateWithLifecycle()
    val connCheck by vm.connCheck.collectAsStateWithLifecycle()
    // Subscription-grouped quick-pick, pre-grouped and pre-sorted in the VM
    // (one memoized derivation per store/selection emission, nothing heavy
    // ever runs inside the lazy item lambdas below).
    val quickGroups by vm.quickGroups.collectAsStateWithLifecycle()
    // True while any ping burst runs, greys out the ping pills and (with the
    // VM's re-entry guard) blocks overlapping bursts from repeated taps.
    val pinging by vm.pinging.collectAsStateWithLifecycle()
    // Which servers are being measured at this instant, so their meters can say so.
    val pingingIds by PingState.inFlight.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    // Per-group expand state for the quick-pick, owned by the screen so the rows
    // past the peek are emitted as lazy items only while that group is expanded.
    // Every group starts in the peek state, Home leads with the
    // connect hero plus each group's `LeanOptions.quickPeek` fastest servers (the
    // selected one surfaced, see [peekServers]); the full list unfolds on
    // demand. Persisted across rotation/process-restore as a plain list of
    // expanded ids (a Set has no default Bundle saver, hence listSaver).
    var expandedGroupIds by rememberSaveable(
        stateSaver = listSaver<Set<String>, String>(
            save = { it.toList() },
            restore = { it.toSet() },
        ),
    ) { mutableStateOf(emptySet()) }
    fun isGroupExpanded(id: String) = id in expandedGroupIds
    fun toggleGroup(id: String) {
        expandedGroupIds = if (id in expandedGroupIds) expandedGroupIds - id else expandedGroupIds + id
    }
    // Per-subscription "is refreshing", so the group header's refresh glyph
    // spins for the real duration of updateSubscription (Servers-tab pattern).
    val refreshingSubs = remember { mutableStateMapOf<String, Boolean>() }

    val clipboard = rememberLeanClipboard()
    val context = LocalContext.current
    // Per-row «Скопировать ссылку», silent here (no snackbar host on Home;
    // Android 13+ shows the system clipboard overlay as feedback).
    fun copyLink(p: Profile) {
        ShareLinks.toShareLink(p)?.let { clipboard.copy(it) }
    }
    val isAuto = selectedId == CoreManager.AUTO_PROFILE_ID
    val canConnect = (isAuto && profiles.isNotEmpty()) || selected != null
    // Active tunnel must always offer a disconnect, even if the selected server
    // (or the whole list) was deleted mid-session and canConnect went false.
    val active = state is VpnState.Connected || state is VpnState.Connecting

    // «Главный экран» knobs, read in composition rather than inside the LazyColumn
    // content builder. The builder runs inside a derivedStateOf and would track
    // them correctly either way, but every other input to the list arrives as a
    // plain capture and one that did not would be the odd one out to debug.
    val quickPeek = LeanOptions.quickPeek
    // A peek of zero has no collapsed state left to show, so it means "hide the
    // block" rather than "show a group with nothing in it".
    val showQuickPick = LeanOptions.showQuickPickBlock && quickPeek > 0
    // «Подписка»: the group header cards. Off leaves a flat run of server rows,
    // the closing footer strip goes with the header, or the card would end in a
    // rounded lip belonging to a header that is not there.
    val showGroupChrome = LeanOptions.showSubscriptionBlock

    val listState = rememberLazyListState()
    // derivedStateOf, not a plain read: scrolling changes the offset on every frame, and
    // reading it directly would recompose the whole screen continuously. Only the
    // boolean crossing matters here, so recomposition happens twice per scroll instead.
    // Index > 0 alone is not enough: the hero item is tall, so it is still partly on
    // screen well after the list has moved off item 0.
    val heroScrolledAway by remember {
        derivedStateOf {
            listState.firstVisibleItemIndex > 0 ||
                listState.firstVisibleItemScrollOffset > HeroHiddenThresholdPx
        }
    }

    Scaffold(
        // «Фон приложения» paints the canvas; the Scaffold and its top bar go
        // transparent so a vignette / gradient / grain pass is not covered by two
        // opaque fills drawn on top of it. On `flat` (the default) transparent
        // over LeanColors.Background is the same pixel as before.
        modifier = Modifier.fillMaxSize().leanBackground(),
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Image(
                            painter = painterResource(R.mipmap.ic_launcher_fg),
                            contentDescription = null,
                            modifier = Modifier.size(30.dp),
                            // The launcher glyph is light ink on transparent,
                            // invisible on the light theme's off-white canvas, so
                            // re-ink it to the live onSurface token there. Dark /
                            // AMOLED keep the original artwork (null = no filter).
                            colorFilter = if (LeanColors.light) ColorFilter.tint(LeanColors.TextPrimary) else null,
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("Lean", style = LeanType.appTitle)
                    }
                },
                actions = {
                    IconButton(onClick = onOpenServers) {
                        LeanIconImage(LeanIcon.Servers, tint = LeanColors.TextSecondary, modifier = Modifier.size(22.dp))
                    }
                    IconButton(onClick = onOpenSettings) {
                        LeanIconImage(LeanIcon.Gear, tint = LeanColors.TextSecondary, modifier = Modifier.size(22.dp))
                    }
                    Spacer(Modifier.width(4.dp))
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
      Box(Modifier.fillMaxSize().padding(padding)) {
        LazyColumn(
            state = listState,
            // No stretch at the ends of the list: it is a layer-wide RenderEffect, so
            // every frame of the bounce re-composites everything inside it, on the busiest
            // screen in the app.
            overscrollEffect = null,
            modifier = Modifier.fillMaxSize(),
            // The floating connect button is an overlay, so it covers whatever the list
            // ends on. Reserving its height as extra bottom padding lets the list scroll
            // clear of it, keeping the last row reachable and changing nothing when the
            // button is off, where the extra is zero.
            contentPadding = PaddingValues(
                start = 18.dp,
                end = 18.dp,
                top = 8.dp,
                bottom = 8.dp + if (LeanOptions.heroFloating) FloatingConnectReserve else 0.dp,
            ),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(0.dp),
        ) {
            item {
                Spacer(Modifier.height(8.dp))
                ConnectButton(
                    state = state,
                    // Disconnect stays reachable while active; new connects need a target.
                    // During Stopping the button is blocked so a tap can't race teardown.
                    enabled = (active || canConnect) && state !is VpnState.Stopping,
                    onToggle = {
                        when (state) {
                            is VpnState.Connected, is VpnState.Connecting -> onDisconnect()
                            // Tearing down: ignore taps until the coordinator reaches Disconnected.
                            is VpnState.Stopping -> {}
                            else -> {
                                val id = if (isAuto) CoreManager.AUTO_PROFILE_ID else selected?.id
                                id?.let { onConnect(it) }
                            }
                        }
                    },
                )
                Spacer(Modifier.height(18.dp))
                Text(
                    statusLabel(state),
                    color = statusColor(state),
                    style = MaterialTheme.typography.titleMedium,
                )
                // which server this connection uses, under the status label. For AUTO
                // this is shown even while disconnected: autoPick is the live picked
                // node when connected ("<name> · <ms> мс"), and otherwise a prediction
                // ("≈ <name>", the offline-fastest server), so selecting «Авто»
                // always names a target. For a single server the name only appears
                // once connected (the chosen row already shows its own selection in
                // the quick-pick below). Reads selected/autoStatus/autoPick, no
                // traffic read, so the 1 Hz traffic tick can't recompose this line.
                if (isAuto && autoPick != null) {
                    Spacer(Modifier.height(8.dp))
                    CurrentServerLabel(isAuto = true, autoStatus = autoPick, selectedName = null, proto = null)
                }
                if (state is VpnState.Connected) {
                    if (!isAuto) {
                        Spacer(Modifier.height(8.dp))
                        CurrentServerLabel(
                            isAuto = false,
                            autoStatus = null,
                            selectedName = selected?.name,
                            // Resolved here because the protocol lives on the profile,
                            // which the label itself never sees; it only renders text.
                            proto = selected?.outbound?.protocol,
                        )
                    }
                    // A hidden block must take its own spacing with it, leaving the
                    // gap behind reads as a layout bug rather than as a setting.
                    if (LeanOptions.trafficRow != "hidden") {
                        Spacer(Modifier.height(12.dp))
                        // The flow, not a value collected up in [HomeScreen]. Traffic
                        // ticks once a second, and a read at screen level made that
                        // tick recompose the whole screen, including the LazyColumn's
                        // content builder, every second, in the middle of a fling.
                        // Collected here, the tick reaches these two numbers and
                        // nothing else; while disconnected nothing collects it at all.
                        TrafficRow(vm.traffic)
                    }
                    if (LeanOptions.showConnectionTestBlock) {
                        Spacer(Modifier.height(14.dp))
                        ConnectionCheckButton(connCheck) { vm.checkConnection() }
                    }
                }
                Spacer(Modifier.height(22.dp))
            }

            // «Авто · быстрейший», demoted from a full-width card to a compact
            // self-sized pill (the LazyColumn centres it), so it stops crowding
            // the grouped list. It still selects AUTO and reflects the selected
            // state; the live pick already shows under the status label via
            // [CurrentServerLabel], so the pill carries no subtitle.
            if (profiles.isNotEmpty()) {
                item(key = "auto-pill", contentType = "auto-pill") {
                    // The AutoPill sits centred with a small, unobtrusive
                    // global «проверить все» ping-all pill beside it (the owner:
                    // "где-то на главной но не мешала, чтобы для пинга всех"),
                    // a compact icon pill, not a full-width button. Tapping it
                    // pings every server (vm.pingAll, skipping excluded ones).
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        AutoPill(
                            selected = isAuto,
                            onClick = { vm.select(CoreManager.AUTO_PROFILE_ID) },
                        )
                        // While a sweep runs the same control stops it. There was no
                        // way to: a burst of sixty probes runs for over a minute and
                        // every other ping entry point refuses to start while one is
                        // going, so a wrong setting could not be corrected until it
                        // finished on its own.
                        PingAllPill(
                            running = pinging,
                            onClick = { if (pinging) vm.cancelPing() else vm.pingAll() },
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                }
            }

            // Directly under the ping pill, because this is the one setting people never
            // found: it lives two screens deep in Соединение, and the reports that came
            // back were «через время само отваливается» rather than "something is off in
            // settings". The card removes itself as soon as the exemption is granted.
            item(key = "background-work", contentType = "background-work") {
                BackgroundWorkCard(Modifier.padding(bottom = 12.dp))
            }

            if (profiles.isEmpty()) {
                item {
                    Box(Modifier.fillMaxWidth().padding(vertical = 22.dp), contentAlignment = Alignment.Center) {
                        Text(
                            tr("Серверов пока нет — добавьте на экране «Серверы»."),
                            color = LeanColors.TextSecondary,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            } else if (showQuickPick) {
                // Quick pick, grouped by subscription; the grouping and ordering live in
                // HomeViewModel.quickGroups. Each group wears the Servers tab's grouped
                // card: a header, a peek of up to [quickPeek] servers, a «Показать все»
                // toggle when there is more, and a footer strip closing the card.
                //
                // The visible slice is computed here rather than inside the item lambdas,
                // and every row is its own keyed lazy item so the list virtualizes.
                quickGroups.forEach { group ->
                    val sub = group.subscription
                    val canExpand = group.servers.size > quickPeek
                    // A group too small to peek-truncate counts as expanded: all
                    // rows show and the chevron/toggle disappears (expandable).
                    val groupExpanded = !canExpand || isGroupExpanded(group.id)
                    val visibleServers =
                        if (groupExpanded) group.servers else peekServers(group.servers, selectedId, quickPeek)
                    if (showGroupChrome) {
                        item(key = "qp-header-${group.id}", contentType = "qp-header") {
                            if (sub != null) {
                                SubscriptionCard(
                                    name = sub.displayName,
                                    // Provider description when present; otherwise the
                                    // server count, mirrors the Servers tab meta line.
                                    meta = sub.description.ifBlank { tr("%d серверов").format(group.servers.size) },
                                    expanded = groupExpanded,
                                    onToggleExpanded = { toggleGroup(group.id) },
                                    // The attached card draws no border, so the card-
                                    // level selection cue is moot on Home: the peek
                                    // always contains the selected row, which carries
                                    // its own tonal wash.
                                    active = false,
                                    // Empty groups are never emitted (buildQuickGroups
                                    // drops subscriptions without servers).
                                    isEmpty = false,
                                    announce = sub.announce,
                                    refreshing = refreshingSubs[sub.id] == true,
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
                                                Toast.makeText(
                                                    context,
                                                    tr("Не удалось обновить подписку"),
                                                    Toast.LENGTH_SHORT,
                                                ).show()
                                            }
                                            refreshedProfilesForPing(
                                                refreshSucceeded = result.isSuccess,
                                                pingOnUpdate = app.settings.state.value.pingOnUpdate,
                                                subscriptionId = sub.id,
                                                profiles = app.profiles.state.value.profiles,
                                            ).takeIf { it.isNotEmpty() }?.let(vm::pingGroup)
                                        }
                                    },
                                    usedBytes = sub.usedBytes,
                                    totalBytes = sub.totalBytes,
                                    expireEpochSec = sub.expireEpochSec,
                                    // «проверить пинг» scoped to this group's servers
                                    // (reuses pingAll's machinery in the VM, skipping
                                    // servers excluded from the test). group.servers is
                                    // already a stable list off quickGroups.
                                    onPing = { vm.pingGroup(group.servers) },
                                    // Without this the header's ping button stayed lit
                                    // while a burst ran and silently ate every tap
                                    // (the VM drops re-entrant bursts): it read as a
                                    // dead button you could spam.
                                    pinging = pinging,
                                    expandable = canExpand,
                                    // Peek rows follow this header in every state,
                                    // keep the attached (top-rounded, borderless)
                                    // chrome even while collapsed.
                                    attachedBelow = true,
                                )
                            } else {
                                // Both header-less pseudo-groups resolve their label here
                                // (not in the flow), so it tracks a live language flip.
                                val isFavorites = group.id == HomeViewModel.FAVORITES_GROUP_ID
                                ManualGroupCard(
                                    name = if (isFavorites) tr("Избранное") else tr("Свои серверы"),
                                    meta = tr("%d серверов").format(group.servers.size),
                                    expanded = groupExpanded,
                                    expandable = canExpand,
                                    onToggleExpanded = { toggleGroup(group.id) },
                                    onPing = { vm.pingGroup(group.servers) },
                                    pingEnabled = !pinging,
                                )
                            }
                        }
                    }
                    // Keyed by group + profile, not by profile alone. Every group is
                    // flattened into this one LazyColumn, and a starred server appears
                    // twice, once under «Избранное» and once in its own
                    // group (see FAVORITES_GROUP_ID). A bare profile id is therefore
                    // unique within a group but not within the list, and Compose throws
                    // "Key was already used" the moment a favourite is set, which is
                    // exactly the crash-on-scroll this produced.
                    items(
                        visibleServers,
                        key = { "${group.id}/${it.id}" },
                        contentType = { "qp-server" },
                    ) { p ->
                        // Same long-press menu actions as the Servers screen
                        // rows (minus rename/delete: those stay on the
                        // primary screen).
                        SubscriptionServerRow(
                            probing = p.id in pingingIds,
                            profile = p,
                            selected = p.id == selectedId,
                            onSelect = { vm.select(p.id) },
                            onPing = { vm.pingOne(p) },
                            onCopyLink = { copyLink(p) },
                            onToggleFavorite = { scope.launch { app.profiles.toggleFavorite(p.id) } },
                            onToggleExcludeFromTest = { scope.launch { app.profiles.setExcludedFromTest(p.id, !p.excludedFromTest) } },
                        )
                    }
                    if (canExpand) {
                        item(key = "qp-more-${group.id}", contentType = "qp-more") {
                            ShowAllToggleRow(
                                expanded = groupExpanded,
                                total = group.servers.size,
                                onToggle = { toggleGroup(group.id) },
                            )
                        }
                    }
                    item(key = "qp-gap-${group.id}", contentType = "qp-gap") {
                        // A bottom-rounded surfaceContainer strip closes the
                        // grouped card (header top-rounded + rows on surface +
                        // this footer = one continuous card), so it is emitted on
                        // exactly the condition the header is. The radius comes
                        // from the live ladder, not a literal: this was one of
                        // the four raw 22.dp seams that would have ignored
                        // «Скругление» entirely.
                        if (showGroupChrome) {
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
            }

            item {
                Spacer(Modifier.height(2.dp))
                AllServersRow(onClick = onOpenServers)
                Spacer(Modifier.height(16.dp))
            }
        }

        // «Кнопка при прокрутке»: the hero is the first item, so on a long server list
        // it scrolls away and connecting means scrolling back to the top. A compact
        // control fades in once it is genuinely out of view and carries exactly the same
        // action, so the primary thing the app does is never more than one tap away.
        if (LeanOptions.heroFloating) {
            FloatingConnectButton(
                visible = heroScrolledAway,
                state = state,
                enabled = (active || canConnect) && state !is VpnState.Stopping,
                onToggle = {
                    when (state) {
                        is VpnState.Connected, is VpnState.Connecting -> onDisconnect()
                        is VpnState.Stopping -> {}
                        else -> {
                            val id = if (isAuto) CoreManager.AUTO_PROFILE_ID else selected?.id
                            id?.let { onConnect(it) }
                        }
                    }
                },
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
      }
    }
}

/**
 * «Проверить соединение»: an end-to-end HTTP GET through the active
 * tunnel, cycling Проверка… → «%d мс» → Таймаут / Нет интернета. Rendered only
 * while connected: that is when app traffic actually rides the tunnel.
 */
@Composable
private fun ConnectionCheckButton(check: ConnCheckState, onCheck: () -> Unit) {
    val label = when (check) {
        is ConnCheckState.Idle -> tr("Проверить соединение")
        is ConnCheckState.Checking -> tr("Проверка…")
        is ConnCheckState.Stressing -> tr("Нагрузка >16 КБ…")
        is ConnCheckState.Ok -> tr("%d мс").format(check.ms)
        is ConnCheckState.Survived -> tr("%d мс · держит %d КБ/с").format(check.ms, check.kbps)
        is ConnCheckState.Torn -> tr("рвётся после %d КБ").format(check.kb)
        is ConnCheckState.Timeout -> tr("Таймаут")
        is ConnCheckState.Offline -> tr("Нет интернета")
    }
    val tint = when (check) {
        is ConnCheckState.Ok, is ConnCheckState.Survived -> LeanColors.Connected
        is ConnCheckState.Torn, is ConnCheckState.Timeout, is ConnCheckState.Offline -> MaterialTheme.colorScheme.error
        is ConnCheckState.Checking, is ConnCheckState.Stressing -> LeanColors.TextSecondary
        else -> MaterialTheme.colorScheme.onSecondaryContainer
    }
    FilledTonalButton(
        onClick = onCheck,
        enabled = check !is ConnCheckState.Checking && check !is ConnCheckState.Stressing,
        shape = MaterialTheme.shapes.small,
    ) {
        LeanIconImage(LeanIcon.Pulse, tint = tint, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(7.dp))
        Text(label, color = tint)
    }
}

/**
 * Header for the manual pseudo-group, so «Свои серверы» reads as one more grouped card in
 * the quick pick.
 *
 * Mirrors [SubscriptionCard]'s attached chrome, top-only rounding, no border, the
 * collapse chevron, minus the refresh and overflow actions, which manual servers have
 * nothing to do with. A group whose peek already shows every server passes
 * [expandable] = false and stops responding to taps.
 */
@Composable
private fun ManualGroupCard(
    name: String,
    meta: String,
    expanded: Boolean,
    expandable: Boolean,
    onToggleExpanded: () -> Unit,
    onPing: () -> Unit,
    pingEnabled: Boolean = true,
) {
    val scheme = MaterialTheme.colorScheme
    // LeanIcon.Chev points right: 0° = collapsed/peek, 90° = expanded/down.
    val chevRot by animateFloatAsState(
        targetValue = if (expanded) 90f else 0f,
        animationSpec = LeanMotion.tween(220, FastOutSlowInEasing),
        label = "manualChev",
    )
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .depthShadow(LeanCorner.Card)
            .leanGlass(LeanCorner.CardTop, scheme.surfaceContainer),
        // Top-only Card radius off the live ladder, the second of Home's two raw
        // 22.dp seams. Its footer twin is [LeanCorner.CardBottom] in the builder.
        shape = LeanCorner.CardTop,
        // Transparent because leanGlass paints this card; it falls back to exactly this
        // surface when glass is off. Missed in the first pass, which left «Свои серверы»
        // (and the favourites group, which shares this composable) as the one opaque
        // header among glass ones.
        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .then(if (expandable) Modifier.clickable { onToggleExpanded() } else Modifier)
                .padding(16.dp),
            // CenterVertically (unlike SubscriptionCard's Top): the meta is a
            // fixed single line, so even with the trailing ping IconButton
            // nothing can grow the row downward: the cluster stays one line tall.
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LeanBadge(LeanIcon.Servers, tint = LeanColors.Accent)
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    name,
                    color = LeanColors.TextPrimary,
                    style = LeanType.cardName,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(4.dp))
                Text(meta, color = LeanColors.TextSecondary, style = LeanType.meta, maxLines = 1)
            }
            // «проверить пинг» for the manual group's servers, the manual
            // pseudo-group has no refresh/⋮ actions a real subscription carries,
            // so this is its only header action. Same Pulse glyph as the real
            // SubscriptionCard's ping button.
            IconButton(onClick = onPing, enabled = pingEnabled) {
                PingGlyph(active = !pingEnabled)
            }
            if (expandable) {
                Spacer(Modifier.width(4.dp))
                LeanIconImage(
                    LeanIcon.Chev,
                    tint = LeanColors.TextTertiary,
                    modifier = Modifier
                        .size(16.dp)
                        .rotate(chevRot),
                )
            }
        }
    }
}

/**
 * The peek's «Показать все (N)» / «Свернуть» toggle, a quiet full-width text
 * row continuing the group card's `surfaceContainer` between the last visible
 * server row and the bottom footer strip. Same [LeanDivider] hanging rule as
 * the server rows above it; accent-tinted meta type, centered, an affordance,
 * not a button (no fill, no border, ripple only). Emitted only for groups with
 * more servers than the peek shows (`LeanOptions.quickPeek`).
 */
@Composable
private fun ShowAllToggleRow(expanded: Boolean, total: Int, onToggle: () -> Unit) {
    Column(Modifier.fillMaxWidth().leanGlass(RectangleShape, MaterialTheme.colorScheme.surfaceContainer)) {
        LeanDivider()
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onToggle() }
                .padding(vertical = 11.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                if (expanded) tr("Свернуть") else tr("Показать все (%d)").format(total),
                color = LeanColors.Accent,
                style = LeanType.meta,
            )
        }
    }
}

@Composable
private fun AllServersRow(onClick: () -> Unit) {
    FilledTonalButton(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        contentPadding = PaddingValues(vertical = 13.dp),
    ) {
        LeanIconImage(LeanIcon.Servers, tint = MaterialTheme.colorScheme.onSecondaryContainer, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(tr("Все серверы"))
    }
}

/**
 * «Авто · быстрейший», demoted from the old full-width AutoRow card to a
 * compact self-sized pill so it takes minimal vertical space above the grouped
 * quick-pick. Selection is carried tonally, `secondaryContainer` fill, a quiet
 * `primary @ 0.40` outline (the SubscriptionCard active-sub cue), and a small
 * check glyph; at rest a `SurfaceVariant` pill with the standard 1dp
 * `outlineVariant` hairline. Plain derived colors, no animateColorAsState
 * (the pill exists once, but the cheap-row rule stays the house style).
 */
@Composable
private fun AutoPill(selected: Boolean, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val fill = if (selected) scheme.secondaryContainer else LeanColors.SurfaceVariant
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = Color.Transparent,
        border = BorderStroke(
            1.dp,
            if (selected) scheme.primary.copy(alpha = 0.40f) else scheme.outlineVariant,
        ),
    ) {
        // Glass goes inside the Surface, not on its modifier.
        //
        // A clickable Surface expands its layout bounds to the 48dp minimum touch target
        // while still drawing its background and border at the smaller visual size. A
        // modifier passed to the Surface sits outside that expansion, so the glass came
        // out taller and wider than the outline around it. The content, on the other
        // hand, fills exactly the area the Surface paints, which is what the fragment
        // has to line up with.
        Row(
            modifier = Modifier
                .leanGlass(CircleShape, fill)
                .padding(horizontal = 14.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LeanIconImage(
                LeanIcon.Speed,
                tint = if (selected) LeanColors.Accent else LeanColors.TextSecondary,
                modifier = Modifier.size(14.dp),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                tr("Авто · быстрейший"),
                color = if (selected) scheme.onSecondaryContainer else LeanColors.TextSecondary,
                style = LeanType.chip,
            )
            if (selected) {
                Spacer(Modifier.width(6.dp))
                LeanIconImage(LeanIcon.Check, tint = LeanColors.Accent, modifier = Modifier.size(14.dp))
            }
        }
    }
}

/**
 * Small, unobtrusive global ping-all affordance for Home, a compact circular
 * `SurfaceVariant` icon pill (the AutoPill's resting chrome, glyph-only) sitting
 * beside the AutoPill so "ping every server" is reachable without a full-width
 * button crowding the grouped list. Carries only the shared [LeanIcon.Pulse]
 * "ping" glyph; the per-group headers cover scoped pings. Tapping it starts a sweep, and
 * tapping it again stops one: a sixty-server burst runs for over a minute and every other
 * ping entry point refuses to start while it does, so without a stop a wrong setting could
 * not be corrected until it finished on its own.
 */
@Composable
private fun PingAllPill(running: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = Color.Transparent,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Box(
            // Inside the Surface for the same reason as AutoPill above: a modifier on the
            // Surface itself would cover the 48dp touch target rather than the circle the
            // border draws.
            modifier = Modifier
                .leanGlass(CircleShape, LeanColors.SurfaceVariant)
                .padding(7.dp),
            contentAlignment = Alignment.Center,
        ) {
            // Pulses while a burst runs, and stays tappable: that tap is now the way
            // to stop it, which is why this exists at all for the change.
            PingGlyph(active = running, size = 16.dp)
        }
    }
}

/**
 * Every dp the connect hero owns, for one («Кнопка подключения» × «Размер кнопки»
 * × «Значок кнопки») combination.
 *
 * The defaults are the baseline hero; the other three styles are stated in
 * [connectHeroStyle] as deltas from it, so a change to the baseline cannot leave them
 * behind.
 *
 * [ringGap] above zero draws a second, quieter ring inside the first («Пульс»); every
 * other style leaves it at zero. [filled] false drops the tonal container entirely
 * («Минимал»), and the ripple still lands because a transparent `Surface` is still a
 * `Surface`.
 */
@Immutable
private data class ConnectHeroStyle(
    val icon: LeanIcon,
    val frame: Dp = 252.dp,
    val ring: Dp = 224.dp,
    val disc: Dp = 204.dp,
    val glyph: Dp = 78.dp,
    val strokeIdle: Dp = 1.5.dp,
    val strokeActive: Dp = 2.dp,
    val strokeSweep: Dp = 3.dp,
    val ringGap: Dp = 0.dp,
    val filled: Boolean = true,
)

/**
 * [scale] («Размер кнопки», 0.85 / 1 / 1.15) multiplies the sizes only: strokes
 * keep their tuned widths so a small hero still draws a crisp hairline instead of
 * a 1.3dp smudge, while [ConnectHeroStyle.ringGap] rides along because it is
 * concentric geometry, not a weight.
 *
 * A pure function of three resolved values rather than a reader of
 * [LeanOptions]: the state reads stay visible at the one call site, where the
 * recomposition they trigger is obvious.
 */
private fun connectHeroStyle(style: String, glyph: String, scale: Float): ConnectHeroStyle {
    val icon = when (glyph) {
        "shield" -> LeanIcon.Shield
        "globe" -> LeanIcon.Globe
        "pulse" -> LeanIcon.Pulse
        else -> LeanIcon.Power
    }
    // «Кольцо», the defaults, i.e. the hero exactly as it ships today.
    val shipping = ConnectHeroStyle(icon = icon)
    val base = when (style) {
        // Solid tonal disc, no ring at rest. The active stroke survives on
        // purpose: it is the only thing that tells «подключено» from «ошибка»,
        // and dropping it would make a failed tunnel look like an idle one.
        "disc" -> shipping.copy(disc = 216.dp, glyph = 84.dp, strokeIdle = 0.dp)
        // Two concentric rings around a tighter disc.
        "pulse" -> shipping.copy(ring = 232.dp, disc = 196.dp, glyph = 76.dp, ringGap = 12.dp)
        // Glyph and hairline only, no container in any state.
        "minimal" -> shipping.copy(
            frame = 236.dp,
            ring = 216.dp,
            disc = 216.dp,
            glyph = 92.dp,
            strokeIdle = 1.dp,
            strokeActive = 1.dp,
            strokeSweep = 2.dp,
            filled = false,
        )
        else -> shipping
    }
    if (scale == 1f) return base
    return base.copy(
        frame = base.frame * scale,
        ring = base.ring * scale,
        disc = base.disc * scale,
        glyph = base.glyph * scale,
        ringGap = base.ringGap * scale,
    )
}

/**
 * MD3 connect hero: a tonal circle inside a hit/halo frame whose container + ring
 * colors carry state. Disconnected = surfaceContainerHigh with an outlineVariant
 * ring; connecting = a single rotating primary sweep arc; connected =
 * primaryContainer with a breathing primary ring; error = error ring. No blur, no
 * glow halos, ripple + tonal color are the affordance.
 *
 * All geometry now arrives as a [ConnectHeroStyle]; this composable draws it.
 */
@Composable
private fun ConnectButton(state: VpnState, enabled: Boolean, onToggle: () -> Unit) {
    val on = state is VpnState.Connected
    val connecting = state is VpnState.Connecting || state is VpnState.Stopping
    val error = state is VpnState.Error
    val animate = motionAllowed()
    val motion = connectHeroMotion(state, animate)
    val style = connectHeroStyle(LeanOptions.heroStyle, LeanOptions.heroGlyph, LeanMetrics.heroScale)
    val haptic = rememberTapHaptic()

    // one spec for all three hero colours. «Плавные переходы цвета» and the motion
    // level both land here, and either one off snaps. This is the documented
    // asymmetry the knob exists for: the hero was the only thing in the app that
    // crossfaded on a theme change while everything around it clicked over.
    val colorSpec: FiniteAnimationSpec<Color> =
        if (animate && LeanOptions.colorCrossfade) LeanMotion.tween(300) else snap()

    val container by animateColorAsState(
        targetValue = if (on) MaterialTheme.colorScheme.primaryContainer else LeanColors.SurfaceVariant,
        animationSpec = colorSpec,
        label = "connectContainer",
    )
    val ringColor by animateColorAsState(
        targetValue = when {
            error -> LeanColors.Error
            on -> LeanColors.Accent
            else -> LeanColors.Outline
        },
        animationSpec = colorSpec,
        label = "connectRing",
    )
    val glyph by animateColorAsState(
        targetValue = when {
            on -> MaterialTheme.colorScheme.onPrimaryContainer
            connecting -> LeanColors.AccentDim
            error -> LeanColors.Error
            else -> LeanColors.TextSecondary
        },
        animationSpec = colorSpec,
        label = "connectGlyph",
    )

    val sweepAngle = if (motion == ConnectHeroMotion.Connecting) {
        connectingSweepAngle()
    } else {
        0f
    }
    // «Дыхание при подключении», the app's longest-lived frame driver: it runs
    // for as long as the tunnel is up. Off must remove the transition, not slow
    // it, which is why the knob is tested here and not inside the helper.
    val breath = if (motion == ConnectHeroMotion.Connected && LeanOptions.heroBreath) {
        connectedBreathAlpha()
    } else {
        1f
    }

    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.97f else 1f,
        animationSpec = LeanMotion.spring(),
        label = "connectScale",
    )

    Box(Modifier.size(style.frame), contentAlignment = Alignment.Center) {
        Box(
            modifier = Modifier
                .size(style.ring)
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                }
                .drawBehind {
                    if (connecting) {
                        val stroke = style.strokeSweep.toPx()
                        drawArc(
                            color = LeanColors.Accent,
                            startAngle = sweepAngle,
                            sweepAngle = 100f,
                            useCenter = false,
                            topLeft = Offset(stroke / 2f, stroke / 2f),
                            size = Size(size.width - stroke, size.height - stroke),
                            style = Stroke(width = stroke, cap = StrokeCap.Round),
                        )
                    } else {
                        val stroke = (if (on || error) style.strokeActive else style.strokeIdle).toPx()
                        // «Диск» has no resting ring at all, a zero-width Stroke
                        // still rasterises a hairline, so skip the draw outright.
                        if (stroke > 0f) {
                            val alpha = if (on) breath else 1f
                            val ink = ringColor.copy(alpha = ringColor.alpha * alpha)
                            val radius = (size.minDimension - stroke) / 2f
                            drawCircle(color = ink, radius = radius, style = Stroke(width = stroke))
                            val gap = style.ringGap.toPx()
                            if (gap > 0f) {
                                // The inner ring of «Пульс», quieter so the pair
                                // reads as one cue with depth rather than as two.
                                drawCircle(
                                    color = ink.copy(alpha = ink.alpha * 0.5f),
                                    radius = radius - gap,
                                    style = Stroke(width = stroke),
                                )
                            }
                        }
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            Surface(
                onClick = {
                    haptic()
                    onToggle()
                },
                enabled = enabled,
                shape = CircleShape,
                // Transparent, because «Стекло» paints the disc itself below, an opaque
                // colour here would cover the backdrop it just drew. The state colour is
                // not lost: it becomes the glass tint, so the disc still reads green when
                // connected and amber while connecting, just with the picture behind it.
                color = Color.Transparent,
                interactionSource = interaction,
                modifier = Modifier
                    .size(style.disc)
                    .leanGlass(CircleShape, if (style.filled) container else Color.Transparent),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    LeanIconImage(style.icon, tint = glyph, modifier = Modifier.size(style.glyph))
                }
            }
        }
    }
}

/** Called only while [ConnectHeroMotion.Connecting], so the transition exists only then. */
@Composable
private fun connectingSweepAngle(): Float {
    val transition = rememberInfiniteTransition(label = "connectSweep")
    val sweepAngle by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = LeanMotion.loop(1200, LinearEasing),
        label = "sweep",
    )
    return sweepAngle
}

/** Called only while connected and «Дыхание при подключении» is on, see [ConnectButton]. */
@Composable
private fun connectedBreathAlpha(): Float {
    val transition = rememberInfiniteTransition(label = "connectedBreath")
    val breath by transition.animateFloat(
        initialValue = 0.55f,
        targetValue = 1f,
        animationSpec = LeanMotion.loop(2400, EaseInOutSine, RepeatMode.Reverse),
        label = "breath",
    )
    return breath
}

/**
 * The platform animator scale, under the name Home has always published it. The
 * read itself now lives in `Motion.kt`, next to «Учитывать системные настройки
 * анимаций» (the knob that decides whether it still gets a vote), so a policy
 * and its input cannot drift apart across two files. Prefer `motionAllowed()`:
 * this value alone is no longer the answer to "may we animate".
 */
internal fun systemAnimationsEnabled(context: android.content.Context): Boolean =
    systemAnimatorsEnabled(context)

/**
 * Secondary line under the big status label showing which server is in use. The
 * caller passes the resolved text via [autoStatus] (for AUTO) or [selectedName]:
 * for AUTO that is autoPick, the live picked node ("<name> · <ms> мс") when
 * connected, else the predicted offline-fastest ("≈ <name>"), so a target shows
 * even before connecting; for a single server it is its own name. Falls back to
 * «Авто · быстрейший» only if an AUTO caller ever passes a null status. Quieter
 * than the title, uses the 15sp cardName role, single line, centered, ellipsis.
 *
 * «Подпись текущего сервера» (`name` / `name_proto` / `hidden`) is applied here.
 * [proto] is only ever appended in the non-AUTO case: the AUTO line is a
 * pre-formatted status that already carries the picked node's latency, and
 * gluing a protocol onto it would push a typical name past one line for no gain.
 */
@Composable
private fun CurrentServerLabel(
    isAuto: Boolean,
    autoStatus: String?,
    selectedName: String?,
    proto: String?,
) {
    if (LeanOptions.currentServerLabel == "hidden") return
    val name = if (isAuto) autoStatus ?: tr("Авто · быстрейший") else selectedName
    if (name.isNullOrBlank()) return
    val text = if (!isAuto && LeanOptions.currentServerLabel == "name_proto" && !proto.isNullOrBlank()) {
        "$name · $proto"
    } else {
        name
    }
    // Quiet, subordinate to the big status label and the 24sp speed numbers, uses
    // the cardName server-name role (Unbounded 700, 15/20), so a typical full name
    // like "H2Germany | 🇩🇪 wLTE | H2" fits on one line at normal phone width.
    // Centered and forced to a single line, a long name ellipsizes instead of
    // wrapping onto a second line.
    Text(
        text,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
        color = LeanColors.TextPrimary,
        style = LeanType.cardName,
        textAlign = TextAlign.Center,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

/**
 * «Строка трафика», `large` (24sp heroNumber over a quiet total) or `compact`
 * (one 17sp statValue line per direction, rate and total joined). `hidden` never
 * reaches here; the caller drops the row and its leading gap.
 *
 * Compact keeps the totals rather than dropping them: halving the block's height
 * is what it is for, and a session total that vanishes on a density setting is a
 * missing number, not a denser one.
 */
@Composable
private fun TrafficRow(flow: StateFlow<TrafficStats>) {
    val traffic by flow.collectAsStateWithLifecycle()
    val compact = LeanOptions.trafficRow == "compact"
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
        TrafficCell("↑", formatRate(traffic.uplink), formatBytes(traffic.uplinkTotal), compact)
        TrafficCell("↓", formatRate(traffic.downlink), formatBytes(traffic.downlinkTotal), compact)
    }
}

@Composable
private fun TrafficCell(arrow: String, rate: String, total: String, compact: Boolean) {
    if (compact) {
        Text("$arrow $rate · $total", color = LeanColors.TextPrimary, style = LeanType.statValue)
        return
    }
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text("$arrow $rate", color = LeanColors.TextPrimary, style = LeanType.heroNumber)
        Text(
            total,
            color = LeanColors.TextSecondary,
            style = LeanType.valuePill,
        )
    }
}

/**
 * The up-to-[peek] servers a collapsed quick-pick group shows.
 * [servers] is already fastest-first (HomeViewModel.quickGroups), so the peek
 * is simply its head, except when this group hosts the selected server deeper
 * in the list: the user's active choice must stay visible while collapsed, so
 * it takes the peek's last slot. Being outside the fastest head it is slower
 * than everything kept above it, so fastest-first order still holds. Groups
 * not hosting the selection just show their [peek] fastest.
 * Called from the LazyColumn content builder (never from item lambdas) and
 * only for groups large enough to truncate, a short slice per group.
 *
 * A [peek] of zero hides the whole block at the call site and never gets here;
 * the guard is kept anyway because `take(-1)` throws rather than clamping, and a
 * slider that reaches 0 is one refactor away from reaching this function.
 */
private fun peekServers(servers: List<Profile>, selectedId: String?, peek: Int): List<Profile> {
    if (peek <= 0) return emptyList()
    if (servers.size <= peek) return servers
    val head = servers.take(peek)
    if (selectedId == null || head.any { it.id == selectedId }) return head
    val selected = servers.firstOrNull { it.id == selectedId } ?: return head
    return servers.take(peek - 1) + selected
}

private fun statusLabel(state: VpnState): String = when (state) {
    is VpnState.Disconnected -> tr("Нажмите для подключения")
    is VpnState.Connecting -> tr("Подключение…")
    is VpnState.Connected -> tr("Подключено")
    is VpnState.Stopping -> tr("Отключение…")
    is VpnState.Error -> tr(publicConnectionError(state.message).messageKey)
}

internal fun refreshedProfilesForPing(
    refreshSucceeded: Boolean,
    pingOnUpdate: Boolean,
    subscriptionId: String,
    profiles: List<Profile>,
): List<Profile> {
    if (!refreshSucceeded || !pingOnUpdate) return emptyList()
    return profiles.filter { it.subscriptionId == subscriptionId }
}

private fun statusColor(state: VpnState) = when (state) {
    is VpnState.Connected -> LeanColors.Connected
    is VpnState.Connecting, is VpnState.Stopping -> LeanColors.Connecting
    is VpnState.Error -> LeanColors.Error
    else -> LeanColors.TextSecondary
}

// formatBytes is the canonical com.th3web.lean.ui.formatBytes (imported): clamps
// negatives, integer KB/MB, Locale-pinned decimals, matches the notification's
// speed line. The old private copy here diverged ("512.0 KB" vs "512 KB").
private fun formatRate(bytesPerSec: Long): String = "${formatBytes(bytesPerSec)}/s"

/** Scroll distance past which the hero counts as gone and the floating control appears. */
private const val HeroHiddenThresholdPx = 320

/**
 * The compact stand-in for the connect hero, shown while the hero itself is scrolled out
 * of view.
 *
 * A small pill rather than a shrunken copy of the hero: at this size the
 * hero's ring, glyph and breathing motion turn into noise, and the only thing still worth
 * carrying is the state colour plus what a tap will do. It states the action («Подключить»
 * / «Отключить»), not the state, because the status is already legible from the colour and
 * the user reaching for it wants the verb.
 */
@Composable
private fun BoxScope.FloatingConnectButton(
    visible: Boolean,
    state: VpnState,
    enabled: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val animate = motionAllowed()
    AnimatedVisibility(
        visible = visible,
        enter = if (animate) fadeIn() + slideInVertically { it } else fadeIn(tween(0)),
        exit = if (animate) fadeOut() + slideOutVertically { it } else fadeOut(tween(0)),
        modifier = modifier,
    ) {
        val on = state is VpnState.Connected
        val busy = state is VpnState.Connecting || state is VpnState.Stopping
        val container = when {
            state is VpnState.Error -> LeanColors.Error
            on -> LeanColors.Connected
            busy -> LeanColors.Connecting
            else -> LeanColors.Accent
        }
        val haptic = rememberTapHaptic()
        Box(Modifier.padding(bottom = 20.dp)) {
            Button(
                onClick = { haptic(); onToggle() },
                enabled = enabled,
                shape = LeanCorner.Button,
                colors = ButtonDefaults.buttonColors(
                    containerColor = container,
                    contentColor = contentColorFor(container),
                ),
            ) {
                Text(
                    if (on || busy) tr("Отключить") else tr("Подключить"),
                    style = LeanType.rowTitle,
                )
            }
        }
    }
}

/**
 * Room kept free at the bottom of Home for the floating connect button: its own height
 * plus the gap it sits in, so the last list row clears it rather than hiding under it.
 */
private val FloatingConnectReserve = 76.dp

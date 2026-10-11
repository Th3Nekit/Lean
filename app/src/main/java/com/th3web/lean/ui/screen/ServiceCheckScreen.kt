package com.th3web.lean.ui.screen

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.th3web.lean.data.net.ServiceCheck
import com.th3web.lean.ui.ServiceCheckViewModel
import com.th3web.lean.ui.components.LeanDivider
import com.th3web.lean.ui.components.LeanGroup
import com.th3web.lean.ui.theme.LeanColors
import com.th3web.lean.ui.theme.LeanCorner
import com.th3web.lean.ui.theme.LeanMotion
import com.th3web.lean.ui.theme.LeanType
import com.th3web.lean.ui.theme.leanBackground
import com.th3web.lean.ui.theme.rememberTapHaptic
import com.th3web.lean.ui.tr

/**
 * «Что работает сейчас»: the ten services people actually open, checked both through the
 * tunnel and past it, with the difference stated.
 *
 * The screen replaces the ritual of opening five apps in turn to find out whether the VPN
 * is helping. Each row says one of five things, and the useful ones are the two that
 * assign blame: «только через VPN» (the provider blocks it, the tunnel is earning its
 * keep) and «ломается в туннеле» (the exit is the problem, switch servers). See
 * [ServiceCheck] for how a verdict is reached.
 *
 * The check starts by itself on first open: the user came here for an answer, and making
 * them press a button to get one would be the screen asking a question back.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServiceCheckScreen(
    onBack: () -> Unit,
    onOpenDiagnostics: () -> Unit = {},
    vm: ServiceCheckViewModel = viewModel(),
) {
    val results by vm.results.collectAsStateWithLifecycle()
    val running by vm.running.collectAsStateWithLifecycle()
    val pending by vm.pending.collectAsStateWithLifecycle()
    val hadTunnel by vm.hadTunnel.collectAsStateWithLifecycle()
    val tap = rememberTapHaptic()

    LaunchedEffect(Unit) {
        if (results.isEmpty() && !running) vm.start()
    }

    Scaffold(
        modifier = Modifier.fillMaxSize().leanBackground(),
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                title = { Text(tr("Что работает"), style = LeanType.screenTitle) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Outlined.ArrowBack,
                            contentDescription = tr("Назад"),
                            tint = LeanColors.TextPrimary,
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { tap(); if (running) vm.stop() else vm.start() }) {
                        RefreshDot(running)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent,
                    scrolledContainerColor = LeanColors.Surface,
                    titleContentColor = LeanColors.TextPrimary,
                ),
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item(key = "summary") {
                SummaryCard(vm.summary, hadTunnel, running)
            }
            items(ServiceCheck.services, key = { it.id }) { service ->
                val status = results.firstOrNull { it.service.id == service.id }
                ServiceRow(
                    name = service.name,
                    status = status,
                    waiting = service.id in pending,
                )
            }
            item(key = "why") {
                // The natural next question when a row says something is blocked: what
                // exactly is the provider doing, and what setting answers it.
                DiagnosticsRow(onClick = onOpenDiagnostics)
            }
        }
    }
}

/**
 * The headline, which is the only part most people will read.
 *
 * Phrased as a count rather than a percentage: "8 из 10" is a fact about this list, while
 * "80 %" invites a precision the list does not have. The two assignment lines appear only
 * when they have something to say.
 */
@Composable
private fun SummaryCard(
    summary: ServiceCheckViewModel.Summary,
    hadTunnel: Boolean,
    running: Boolean,
) {
    LeanGroup {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Text(
                if (running) {
                    tr("Проверяю сервисы…")
                } else {
                    tr("Работает %d из %d").format(summary.working, summary.total)
                },
                style = LeanType.cardName,
                color = LeanColors.TextPrimary,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                when {
                    !hadTunnel -> tr("Замер без VPN: это то, что открывается напрямую.")
                    summary.onlyViaVpn > 0 && summary.brokenByTunnel > 0 ->
                        tr("%d только через VPN, %d ломается в туннеле.")
                            .format(summary.onlyViaVpn, summary.brokenByTunnel)
                    summary.onlyViaVpn > 0 ->
                        tr("%d из них открывается только через VPN.").format(summary.onlyViaVpn)
                    summary.brokenByTunnel > 0 ->
                        tr("%d ломается именно в туннеле — смените сервер.")
                            .format(summary.brokenByTunnel)
                    else -> tr("Туннель ничего не ломает и ничего не чинит: всё открывается и так.")
                },
                style = LeanType.meta,
                color = LeanColors.TextSecondary,
            )
        }
    }
}

/**
 * One service.
 *
 * While a row waits it shows a breathing dot rather than a spinner: ten spinners at once
 * is a screen full of motion, while a slow pulse on each row reads as "these are being
 * worked on" without competing for attention. The pulse exists only while the row waits,
 * so nothing animates once the list is settled (an infinite transition costs a frame
 * callback for as long as it is composed, which is the house rule in [LeanMotion]).
 */
@Composable
private fun ServiceRow(name: String, status: ServiceCheck.Status?, waiting: Boolean) {
    val verdict = status?.verdict
    val color by animateColorAsState(
        targetValue = when (verdict) {
            ServiceCheck.Verdict.Works, ServiceCheck.Verdict.WorksEitherWay -> LeanColors.Connected
            ServiceCheck.Verdict.BlockedWithoutVpn -> LeanColors.Accent
            ServiceCheck.Verdict.BrokenByTunnel -> LeanColors.Ember
            ServiceCheck.Verdict.Unreachable -> LeanColors.Error
            null -> LeanColors.TextTertiary
        },
        animationSpec = LeanMotion.tween(200, EaseOutStrongServices),
        label = "service-color",
    )
    LeanGroup {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StatusDot(color = color, pulsing = waiting)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(name, style = LeanType.rowTitle, color = LeanColors.TextPrimary)
                Text(
                    verdictText(verdict, waiting),
                    style = LeanType.meta,
                    color = LeanColors.TextSecondary,
                )
            }
            val ms = status?.viaTunnel?.ms ?: status?.direct?.ms
            if (ms != null) {
                Text("$ms ${tr("мс")}", style = LeanType.msReadout, color = LeanColors.TextTertiary)
            }
        }
    }
}

@Composable
private fun StatusDot(color: Color, pulsing: Boolean) {
    // The pulse exists only while the row waits: an infinite transition requests a frame
    // for as long as it is composed, so it must not outlive the thing it describes (the
    // house idiom, see LeanMotion.loop).
    val alpha = if (pulsing && LeanMotion.enabled) {
        val transition = rememberInfiniteTransition(label = "service-pulse")
        val value by transition.animateFloat(
            initialValue = 0.35f,
            targetValue = 1f,
            animationSpec = LeanMotion.loop(900, EaseOutStrongServices, RepeatMode.Reverse),
            label = "service-pulse-alpha",
        )
        value
    } else {
        1f
    }
    Box(Modifier.size(10.dp).alpha(alpha).clip(CircleShape).background(color))
}

/** The way on to «Почему не работает», phrased as the question the list provokes. */
@Composable
private fun DiagnosticsRow(onClick: () -> Unit) {
    Surface(
        color = LeanColors.Surface,
        shape = LeanCorner.Card,
        modifier = Modifier
            .fillMaxWidth()
            .clip(LeanCorner.Card)
            .clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    tr("Почему не работает"),
                    style = LeanType.rowTitle,
                    color = LeanColors.TextPrimary,
                )
                Text(
                    tr("Измерить, что делает провайдер, и подобрать настройки"),
                    style = LeanType.meta,
                    color = LeanColors.TextSecondary,
                )
            }
            Text("›", style = LeanType.rowTitle, color = LeanColors.TextTertiary)
        }
    }
}

@Composable
private fun RefreshDot(running: Boolean) {
    Surface(
        color = if (running) LeanColors.Accent else LeanColors.SurfaceVariant,
        shape = LeanCorner.Badge,
        modifier = Modifier.size(28.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                if (running) "■" else "↻",
                style = LeanType.chip,
                color = if (running) LeanColors.OnAccent else LeanColors.TextSecondary,
            )
        }
    }
}

@Composable
private fun verdictText(verdict: ServiceCheck.Verdict?, waiting: Boolean): String = when {
    waiting -> tr("проверяю…")
    verdict == null -> tr("не проверялось")
    verdict == ServiceCheck.Verdict.Works -> tr("открывается")
    verdict == ServiceCheck.Verdict.WorksEitherWay -> tr("работает и так, и через VPN")
    verdict == ServiceCheck.Verdict.BlockedWithoutVpn -> tr("только через VPN")
    verdict == ServiceCheck.Verdict.BrokenByTunnel -> tr("ломается в туннеле")
    else -> tr("недоступно")
}

/** The same strong ease-out the speed screen uses; see the note there. */
private val EaseOutStrongServices = CubicBezierEasing(0.23f, 1f, 0.32f, 1f)

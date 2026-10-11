package com.th3web.lean.ui.screen

import androidx.compose.animation.core.CubicBezierEasing
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.animation.core.animateFloatAsState
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.th3web.lean.data.net.DpiProbe
import com.th3web.lean.ui.DiagnosticsViewModel
import com.th3web.lean.ui.components.LeanDivider
import com.th3web.lean.ui.components.LeanGroup
import com.th3web.lean.ui.components.LeanSectionLabel
import com.th3web.lean.ui.theme.LeanColors
import com.th3web.lean.ui.theme.LeanCorner
import com.th3web.lean.ui.theme.LeanMotion
import com.th3web.lean.ui.theme.LeanType
import com.th3web.lean.ui.theme.leanBackground
import com.th3web.lean.ui.theme.rememberTapHaptic
import com.th3web.lean.ui.tr

/**
 * «Почему не работает»: five measurements of what this provider does to a connection, and
 * the settings that follow from them.
 *
 * The screen exists because the support conversation it replaces is always the same one.
 * A user says "не работает", and the next twenty messages are guesses: try another
 * server, turn on fragmentation, change DNS, switch protocol. Every one of those guesses
 * corresponds to a fault that can be measured in under a minute ([DpiProbe]), so the
 * screen measures them and then offers to apply exactly what the evidence calls for —
 * nothing more, which is why a clean network comes back with "дело не в провайдере" and
 * no buttons.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiagnosticsScreen(
    onBack: () -> Unit,
    vm: DiagnosticsViewModel = viewModel(),
) {
    val findings by vm.findings.collectAsStateWithLifecycle()
    val running by vm.running.collectAsStateWithLifecycle()
    val applied by vm.applied.collectAsStateWithLifecycle()
    val tap = rememberTapHaptic()

    Scaffold(
        modifier = Modifier.fillMaxSize().leanBackground(),
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                title = { Text(tr("Почему не работает"), style = LeanType.screenTitle) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Outlined.ArrowBack,
                            contentDescription = tr("Назад"),
                            tint = LeanColors.TextPrimary,
                        )
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
            item(key = "intro") {
                LeanGroup {
                    Column(Modifier.fillMaxWidth().padding(16.dp)) {
                        Text(
                            tr("Проверка идёт по обычной сети, минуя туннель"),
                            style = LeanType.rowTitle,
                            color = LeanColors.TextPrimary,
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            tr("Так видно, что именно делает провайдер: рвёт TLS по имени, глушит UDP, подменяет DNS или обрывает соединение на объёме. VPN выключать не нужно."),
                            style = LeanType.meta,
                            color = LeanColors.TextSecondary,
                        )
                    }
                }
            }
            item(key = "run") {
                ActionButton(
                    label = tr(
                        when {
                            running -> "Остановить"
                            findings.isEmpty() -> "Проверить сеть"
                            else -> "Проверить снова"
                        },
                    ),
                    primary = !running,
                ) {
                    tap()
                    if (running) vm.stop() else vm.start()
                }
            }
            if (findings.isNotEmpty()) {
                item(key = "findings-label") { LeanSectionLabel(tr("Что измерено")) }
                items(findings, key = { it.id }) { finding -> FindingRow(finding) }
            }
            val advice = vm.advice
            if (advice.isNotEmpty() && !running) {
                item(key = "advice") {
                    AdviceCard(
                        advice = advice,
                        canApply = vm.canApply,
                        applied = applied,
                        onApply = { tap(); vm.applyRecommended() },
                    )
                }
            }
        }
    }
}

/** One measurement: a coloured dot, what was tested, and the evidence under it. */
@Composable
private fun FindingRow(finding: DpiProbe.Finding) {
    val color = when (finding.verdict) {
        DpiProbe.Verdict.Clear -> LeanColors.Connected
        DpiProbe.Verdict.Blocked -> LeanColors.Error
        DpiProbe.Verdict.Unknown -> LeanColors.TextTertiary
    }
    // No enter transition here, deliberately: rows are added to the list as each probe
    // lands, and AnimatedVisibility given a constant `true` composes already-visible and
    // plays nothing — it would be decoration that does not exist. The list's own item
    // placement animates them in.
    run {
        LeanGroup {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 13.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Box(Modifier.padding(top = 5.dp).size(10.dp).clip(CircleShape).background(color))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        tr(finding.title),
                        style = LeanType.rowTitle,
                        color = LeanColors.TextPrimary,
                    )
                    Text(
                        tr(finding.detail),
                        style = LeanType.meta,
                        color = LeanColors.TextSecondary,
                    )
                }
            }
        }
    }
}

/**
 * The advice, and the one button that acts on it.
 *
 * The button appears only when at least one line is actionable, and what it writes is
 * derived from the findings rather than from a fixed list, so «Применить» cannot turn on
 * fragmentation on a network where nothing was found to be filtered. After it has run the
 * card says what is left for the user to do — reconnect — because a setting that needs a
 * new session and does not say so reads as a button that did nothing.
 */
@Composable
private fun AdviceCard(
    advice: List<DiagnosticsViewModel.Advice>,
    canApply: Boolean,
    applied: Boolean,
    onApply: () -> Unit,
) {
    LeanGroup {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Text(tr("Что делать"), style = LeanType.cardName, color = LeanColors.TextPrimary)
            Spacer(Modifier.height(8.dp))
            advice.forEachIndexed { index, item ->
                if (index > 0) Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.Top) {
                    Text(
                        "•",
                        style = LeanType.meta,
                        color = if (item.actionable) LeanColors.Accent else LeanColors.TextTertiary,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        tr(item.text),
                        style = LeanType.meta,
                        color = LeanColors.TextSecondary,
                    )
                }
            }
        }
        if (canApply || applied) {
            LeanDivider()
            if (applied) {
                Text(
                    tr("Настройки применены — переподключитесь, чтобы они вступили в силу."),
                    style = LeanType.meta,
                    color = LeanColors.Connected,
                    modifier = Modifier.padding(16.dp),
                )
            } else {
                Box(Modifier.padding(12.dp)) {
                    ActionButton(label = tr("Применить рекомендации"), primary = true, onClick = onApply)
                }
            }
        }
    }
}

/**
 * The screen's button.
 *
 * 0.97 on press over 140 ms, the same feedback the speed screen's button gives: a
 * pressable thing has to answer the finger, and the scale is small enough to read as
 * acknowledgement rather than as motion.
 */
@Composable
private fun ActionButton(label: String, primary: Boolean, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val press by animateFloatAsState(
        targetValue = if (pressed) 0.97f else 1f,
        animationSpec = LeanMotion.tween(140, EaseOutStrongDiag),
        label = "diag-press",
    )
    Surface(
        color = if (primary) LeanColors.Accent else LeanColors.SurfaceVariant,
        shape = LeanCorner.Button,
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp)
            .scale(press)
            .clip(LeanCorner.Button)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                label,
                style = LeanType.rowTitle,
                color = if (primary) LeanColors.OnAccent else LeanColors.TextPrimary,
            )
        }
    }
}

/** The same strong ease-out the other new screens use; see the note in SpeedTestScreen. */
private val EaseOutStrongDiag = CubicBezierEasing(0.23f, 1f, 0.32f, 1f)

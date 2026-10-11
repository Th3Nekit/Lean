package com.th3web.lean.ui.screen

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.DeleteSweep
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.th3web.lean.data.SpeedTestHistoryStore
import com.th3web.lean.data.net.SpeedTest
import com.th3web.lean.ui.SpeedTestViewModel
import com.th3web.lean.ui.components.LeanDivider
import com.th3web.lean.ui.components.LeanGroup
import com.th3web.lean.ui.components.LeanSectionLabel
import com.th3web.lean.ui.components.SegmentedControl
import com.th3web.lean.ui.theme.LeanColors
import com.th3web.lean.ui.theme.LeanCorner
import com.th3web.lean.ui.theme.LeanMetrics
import com.th3web.lean.ui.theme.LeanMotion
import com.th3web.lean.ui.theme.LeanType
import com.th3web.lean.ui.theme.leanBackground
import com.th3web.lean.ui.theme.leanGlass
import com.th3web.lean.ui.theme.rememberTapHaptic
import com.th3web.lean.ui.tr
import java.util.Calendar
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sin

/**
 * «Скорость»: what this connection carries, to the nearest Speedtest server, and what the
 * tunnel costs.
 *
 * Three decisions shape it, and each one came from the previous version being wrong:
 *
 *  * **The target is found, not guessed.** Hand-picked public hosts were unreachable from
 *    RU half the time and accepted no uploads at all. The screen now uses Ookla's own
 *    server directory and times the nearest few (see [SpeedTest]), so «Отдача» is a real
 *    number and the target is near wherever the traffic actually leaves from.
 *  * **The dial scales to the connection.** A fixed 0–300 Мбит/с arc pins the needle on a
 *    gigabit line and crushes the useful range on a mobile one. The scale steps through
 *    10 / 25 / 50 … 10 000 Мбит/с, picking the first step that fits, and the readout
 *    switches to Гбит/с past a thousand. It is inherited from the previous run and zoomed
 *    rather than cut, because re-scaling a dial mid-run is what made the needle lurch
 *    backwards while the number beside it was climbing.
 *  * **The numbers are the screen.** They are set large enough to read at arm's length,
 *    because that is what someone running a speed test is looking at.
 *  * **What gets measured is the user's choice.** With a tunnel up the screen can answer
 *    two questions, and answering both costs twice the time and twice the traffic. See
 *    [ScopeCard].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SpeedTestScreen(
    onBack: () -> Unit,
    vm: SpeedTestViewModel = viewModel(),
) {
    val stage by vm.stage.collectAsStateWithLifecycle()
    val primary by vm.primary.collectAsStateWithLifecycle()
    val direct by vm.direct.collectAsStateWithLifecycle()
    val runs by vm.runs.collectAsStateWithLifecycle()
    val servers by vm.servers.collectAsStateWithLifecycle()
    val pinned by vm.pinned.collectAsStateWithLifecycle()
    val active by vm.active.collectAsStateWithLifecycle()
    val hadTunnel by vm.hadTunnel.collectAsStateWithLifecycle()
    val dialPeak by vm.dialPeak.collectAsStateWithLifecycle()
    val scope by vm.scope.collectAsStateWithLifecycle()
    val tunnelUp by vm.tunnelUp.collectAsStateWithLifecycle()
    val running = vm.running
    val tap = rememberTapHaptic()

    // The chooser needs the list before the first run, and discovery is one small
    // request, so it happens on open rather than behind a button.
    LaunchedEffect(Unit) { vm.loadServers() }

    val inFlight = stage as? SpeedTestViewModel.Stage.Running
    // No phase yet means the run is still choosing a target: nothing is being measured,
    // but something is happening and the screen has to be able to say which.
    val searching = inFlight != null && inFlight.phase == null
    val live = inFlight?.mbps
    val shownRate = live ?: primary?.downloadMbps ?: 0.0
    // The scale the dial wants. It steps, and it only ever grows within a run — the
    // ViewModel also carries it over between runs, so it rarely has to grow at all.
    val scaleMax = remember(dialPeak) { dialMax(dialPeak) }

    Scaffold(
        modifier = Modifier.fillMaxSize().leanBackground(),
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                title = { Text(tr("Скорость"), style = LeanType.screenTitle) },
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
                    AnimatedVisibility(visible = runs.isNotEmpty(), enter = fadeIn(), exit = fadeOut()) {
                        IconButton(onClick = { tap(); vm.clearHistory() }) {
                            Icon(
                                Icons.Outlined.DeleteSweep,
                                contentDescription = tr("Очистить историю"),
                                tint = LeanColors.TextSecondary,
                            )
                        }
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
            // No side gutter on the list itself. The target row scrolls sideways and has
            // to run off both edges to say so, which it cannot do from inside the list's
            // own padding; so the gutter moves into the items, as [Gutters].
            contentPadding = PaddingValues(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            // The gauge is a wide layer; a stretch overscroll would re-composite it on
            // every frame of the bounce for no information gained.
            overscrollEffect = null,
        ) {
            item(key = "gauge") {
                // Full-bleed on purpose: the dial is the screen, and it carries its own
                // inset for the tick labels.
                SpeedGauge(
                    mbps = shownRate,
                    scaleMax = scaleMax,
                    progress = inFlight?.overall ?: 0f,
                    caption = phaseCaption(stage, hadTunnel),
                    running = running,
                )
            }
            item(key = "server") {
                Gutters { ServerLine(active = active, pinned = pinned, discovering = searching) }
            }
            item(key = "action") {
                Gutters {
                    RunButton(running = running) {
                        tap()
                        if (running) vm.stop() else vm.start()
                    }
                }
            }
            // The figures come before the choosers once there are figures: a run's
            // numbers are what the screen is for, and two cards of controls between the
            // dial and them is enough to push them off a phone screen.
            primary?.let { result ->
                item(key = "figures") { Gutters { Reveal { FiguresCard(result, hadTunnel) } } }
            }
            if (primary != null && direct != null) {
                item(key = "comparison") {
                    Gutters {
                        Reveal(delayStep = 1) {
                            ComparisonCard(primary!!, direct!!, vm.downloadDeltaPercent)
                        }
                    }
                }
            }
            item(key = "scope-label") { Gutters { LeanSectionLabel(tr("Что мерить")) } }
            item(key = "scope") {
                Gutters {
                    ScopeCard(
                        scope = scope,
                        tunnelUp = tunnelUp,
                        onSelect = { tap(); vm.selectScope(it) },
                    )
                }
            }
            if (servers.isNotEmpty()) {
                item(key = "targets-label") { Gutters { LeanSectionLabel(tr("Куда мерить")) } }
                item(key = "targets") {
                    TargetPicker(
                        servers = servers,
                        pinned = pinned,
                        enabled = !running,
                        onSelect = { tap(); vm.selectServer(it) },
                    )
                }
            }
            if (primary == null && !running) {
                item(key = "hint") { Gutters { HintCard() } }
            }
            if (runs.isNotEmpty()) {
                item(key = "history-label") { Gutters { LeanSectionLabel(tr("История")) } }
                item(key = "spark") { Gutters { HistorySpark(runs) } }
                itemsIndexed(runs, key = { _, entry -> entry.atMillis }) { index, entry ->
                    Gutters { Reveal(delayStep = index.coerceAtMost(4)) { HistoryRow(entry) } }
                }
            }
        }
    }
}

/**
 * The side gutter every item keeps from the screen edge.
 *
 * It lives here rather than on the list because one item — the row of targets — is a
 * horizontal scroller, and a scroller that stops 16dp short of both edges looks like a
 * mistake instead of looking scrollable. Compose has no way to opt one item out of a
 * list's `contentPadding`, so the padding belongs to the items that want it.
 */
@Composable
private fun Gutters(content: @Composable () -> Unit) {
    Box(Modifier.padding(horizontal = GUTTER)) { content() }
}

/**
 * The dial: a 240° arc the live rate fills, scaled to [scaleMax], with the readout inside.
 *
 * Within one scale the mapping is a gentle power curve rather than linear — the lower
 * third of any range is where the differences people care about live (8 vs 25 Мбит/с on a
 * mobile link), and a linear sweep spends most of the arc on the top half that a given
 * connection never reaches. The scale itself changes in steps, so the ticks stay round
 * numbers instead of drifting with every sample, and it **zooms** between those steps
 * rather than cutting, because a cut moves the needle backwards.
 *
 * The fill is a spring, not a tween: samples arrive three times a second and a spring
 * keeps its velocity when the next one retargets it mid-flight, so the needle reads as one
 * continuous movement. Bounce stays low — this is an instrument, not a toy. The readout
 * follows the same spring, so digits and arc can never disagree.
 *
 * A spring can only smooth what it is given, though, so everything it is given is
 * smoothed first. The samples come pre-smoothed from upstream (`RateSmoother`), because a
 * raw per-tick rate swings by a factor of several on a steady link and at «Анимации: off»
 * there is no animation to hide that with. The scale is carried over between runs, so it
 * does not step up three times in the first seconds of one. And [progress] is the run's
 * own monotonic progress, on a clock rather than on the samples, because it used to be the
 * current phase's fraction — which restarts six times in a two-leg run and stands still
 * through every gap between them.
 *
 * Drawn in [Modifier.drawBehind], so a new sample is a draw-phase invalidation and the
 * text inside never recomposes for it.
 */
@Composable
private fun SpeedGauge(
    mbps: Double,
    scaleMax: Double,
    progress: Float,
    caption: String,
    running: Boolean,
) {
    // The scale steps, and every step moves the needle BACKWARDS while the number beside
    // it is going up: measured on a 300-Мбит/с line, the needle fell nineteen points of
    // the arc as the rate passed 96 Мбит/с, and again fifteen as it passed 255. Two
    // separate things were needed to stop that. The ViewModel carries the range over
    // between runs, so the dial rarely has to re-scale at all; and when it does, the scale
    // itself is animated here, in log space — where one step is the same size whatever the
    // range — so the ticks zoom out around a needle that glides instead of snapping.
    val zoom by animateFloatAsState(
        targetValue = ln(scaleMax.toFloat().coerceAtLeast(1f)),
        animationSpec = LeanMotion.tween(SCALE_TWEEN_MS, EaseOutStrong),
        label = "speed-scale",
    )
    val scale = exp(zoom).toDouble()
    val sweepTarget = dialFraction(mbps, scale)
    val sweep by animateFloatAsState(
        targetValue = sweepTarget,
        animationSpec = LeanMotion.spring(stiffness = Spring.StiffnessLow),
        label = "speed-sweep",
    )
    val shown by animateFloatAsState(
        targetValue = mbps.toFloat(),
        animationSpec = LeanMotion.spring(stiffness = Spring.StiffnessLow),
        label = "speed-readout",
    )
    // The run's own progress, which only ever grows, so this is one steady crawl rather
    // than the six forward-and-back jumps a per-phase fraction produced. Linear, and just
    // longer than the sampling interval: the next value arrives as this one lands, so the
    // hairline moves continuously instead of easing to a stop three times a second.
    val crawl by animateFloatAsState(
        targetValue = progress,
        animationSpec = LeanMotion.tween(PROGRESS_TWEEN_MS, LinearEasing),
        label = "speed-progress",
    )
    val hot = LeanColors.Accent
    val cool = LeanColors.Connected
    val track = LeanColors.BarUnlit
    val tickInk = LeanColors.TextTertiary
    val labels = remember(scale) { dialLabels(scale) }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(286.dp)
            .drawBehind {
                val stroke = 18.dp.toPx()
                val inset = stroke / 2 + 22.dp.toPx()
                val diameter = minOf(size.width - inset * 2, (size.height - inset) * 1.55f)
                val topLeft = Offset((size.width - diameter) / 2f, inset)
                val arcSize = Size(diameter, diameter)
                val center = Offset(topLeft.x + diameter / 2f, topLeft.y + diameter / 2f)

                drawTicks(center, diameter / 2f, stroke, tickInk, labels)

                drawArc(
                    color = track,
                    startAngle = GAUGE_START_ANGLE,
                    sweepAngle = GAUGE_SWEEP,
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    style = Stroke(width = stroke, cap = StrokeCap.Round),
                )
                if (sweep > 0.001f) {
                    val brush = Brush.linearGradient(
                        colors = listOf(cool, hot),
                        start = Offset(topLeft.x, center.y),
                        end = Offset(topLeft.x + diameter, center.y),
                    )
                    // A wider, dimmer pass under the fill: it reads as the instrument
                    // being lit rather than as a second ring, and it is one extra arc on
                    // a layer that is already dirty.
                    drawArc(
                        brush = brush,
                        startAngle = GAUGE_START_ANGLE,
                        sweepAngle = GAUGE_SWEEP * sweep,
                        useCenter = false,
                        topLeft = topLeft,
                        size = arcSize,
                        alpha = 0.20f,
                        style = Stroke(width = stroke * 2.2f, cap = StrokeCap.Round),
                    )
                    drawArc(
                        brush = brush,
                        startAngle = GAUGE_START_ANGLE,
                        sweepAngle = GAUGE_SWEEP * sweep,
                        useCenter = false,
                        topLeft = topLeft,
                        size = arcSize,
                        style = Stroke(width = stroke, cap = StrokeCap.Round),
                    )
                }
                // How far this leg of the run has got, as a hairline inside the dial —
                // something the rate itself cannot say.
                if (running && crawl > 0.001f) {
                    drawArc(
                        color = hot,
                        startAngle = GAUGE_START_ANGLE,
                        sweepAngle = GAUGE_SWEEP * crawl,
                        useCenter = false,
                        topLeft = Offset(topLeft.x + stroke * 1.7f, topLeft.y + stroke * 1.7f),
                        size = Size(diameter - stroke * 3.4f, diameter - stroke * 3.4f),
                        alpha = 0.45f,
                        style = Stroke(width = 2.5f, cap = StrokeCap.Round),
                    )
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(top = 26.dp),
        ) {
            val rate = formatRate(shown.toDouble())
            Text(
                rate.value,
                style = LeanType.heroNumber.copy(
                    fontSize = 64.sp,
                    lineHeight = 66.sp,
                    fontWeight = FontWeight.SemiBold,
                ),
                color = LeanColors.TextPrimary,
            )
            Text(
                tr(rate.unit),
                style = LeanType.cardName.copy(fontSize = 18.sp),
                color = LeanColors.TextSecondary,
            )
            Spacer(Modifier.height(10.dp))
            Text(
                caption,
                style = LeanType.rowTitle,
                color = if (running) hot else LeanColors.TextTertiary,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/**
 * Marks along the sweep, labelled at the round numbers.
 *
 * Labels are drawn with the native canvas because Compose's text API is not available
 * inside a draw-phase modifier without a measurer, and a dial whose ticks carry no numbers
 * is a progress bar wearing a gauge's clothes.
 */
private fun DrawScope.drawTicks(
    center: Offset,
    radius: Float,
    stroke: Float,
    color: Color,
    labels: List<Pair<Float, String>>,
) {
    val outer = radius + stroke * 0.95f
    val inner = radius + stroke * 0.5f
    labels.forEach { (fraction, text) ->
        val angle = Math.toRadians((GAUGE_START_ANGLE + GAUGE_SWEEP * fraction).toDouble())
        val dx = cos(angle).toFloat()
        val dy = sin(angle).toFloat()
        val major = text.isNotEmpty()
        drawLine(
            color = color,
            start = Offset(center.x + dx * inner, center.y + dy * inner),
            end = Offset(center.x + dx * outer, center.y + dy * outer),
            strokeWidth = if (major) 3f else 1.5f,
            cap = StrokeCap.Round,
            alpha = if (major) 0.9f else 0.5f,
        )
        if (major) {
            val textRadius = outer + 13.dp.toPx()
            drawContext.canvas.nativeCanvas.drawText(
                text,
                center.x + dx * textRadius,
                center.y + dy * textRadius + 4.dp.toPx(),
                android.graphics.Paint().apply {
                    this.color = color.copy(alpha = 0.85f).toArgb()
                    textSize = 10.dp.toPx()
                    textAlign = android.graphics.Paint.Align.CENTER
                    isAntiAlias = true
                },
            )
        }
    }
}

/** Which server this run measured against, and whether it was chosen or found. */
@Composable
private fun ServerLine(
    active: SpeedTest.Server?,
    pinned: SpeedTest.Server?,
    discovering: Boolean,
) {
    val text = when {
        discovering -> tr("Ищу ближайший сервер…")
        active == null -> tr("Сервер выберется автоматически")
        else -> "${active.sponsor} · ${active.place}"
    }
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(6.dp)
                .clip(CircleShape)
                .background(if (discovering) LeanColors.Accent else LeanColors.TextTertiary),
        )
        Spacer(Modifier.width(8.dp))
        Text(text, style = LeanType.meta, color = LeanColors.TextSecondary, modifier = Modifier.weight(1f))
        if (active != null && pinned == null && !discovering) {
            Text(tr("авто"), style = LeanType.meta, color = LeanColors.TextTertiary)
        }
    }
}

/**
 * Where to measure to: «Авто» plus the servers nearest this connection.
 *
 * A scrolling row of chips rather than a segmented control — the list is a dozen entries
 * long with a sponsor and a city in each, which no equal-width strip survives at any text
 * scale. It carries the side gutter itself ([GUTTER]) so the chips can run off both edges,
 * which is the only honest way for a row to say that there is more of it.
 *
 * The chosen chip is scrolled to: with «Авто» first and a dozen entries behind it, a
 * target pinned last visit would otherwise be somewhere off the right-hand edge, and a
 * chooser that hides what is chosen reads as a chooser that forgot.
 */
@Composable
private fun TargetPicker(
    servers: List<SpeedTest.Server>,
    pinned: SpeedTest.Server?,
    enabled: Boolean,
    onSelect: (SpeedTest.Server?) -> Unit,
) {
    val state = rememberLazyListState()
    val chosen = remember(servers, pinned) {
        pinned?.let { target ->
            servers.indexOfFirst { it.id == target.id }.takeIf { it >= 0 }?.plus(1)
        } ?: 0
    }
    LaunchedEffect(chosen) { state.animateScrollToItem(chosen) }
    LazyRow(
        state = state,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(horizontal = GUTTER),
    ) {
        item(key = "auto") {
            TargetChip(
                title = tr("Авто"),
                subtitle = tr("ближайший по отклику"),
                active = pinned == null,
                enabled = enabled,
                onClick = { onSelect(null) },
            )
        }
        items(servers, key = { it.id }) { server ->
            TargetChip(
                title = server.sponsor,
                subtitle = if (server.distanceKm > 0) {
                    "${server.place} · ${server.distanceKm} ${tr("км")}"
                } else {
                    server.place
                },
                active = pinned?.id == server.id,
                enabled = enabled,
                onClick = { onSelect(server) },
            )
        }
    }
}

@Composable
private fun TargetChip(
    title: String,
    subtitle: String,
    active: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val border by animateFloatAsState(
        targetValue = if (active) 1f else 0f,
        animationSpec = LeanMotion.tween(180, EaseOutStrong),
        label = "target-border",
    )
    Surface(
        // Transparent, because «Стекло» paints the chip below. An opaque colour here
        // covers the fragment of wallpaper the glass just drew, which is what made this
        // row the one thing on the screen that stayed a solid block at every glass
        // density — a dozen dark bricks in a line, which is exactly how it looked.
        color = Color.Transparent,
        shape = LeanCorner.Tag,
        border = BorderStroke(1.dp, lerp(LeanColors.Hairline, LeanColors.Accent, border)),
        modifier = Modifier.clip(LeanCorner.Tag).clickable(enabled = enabled, onClick = onClick),
    ) {
        Column(
            Modifier
                .leanGlass(
                    LeanCorner.Tag,
                    if (active) LeanColors.SurfaceVariant else LeanColors.Surface,
                )
                // The selected wash goes over the glass, at the same «Сила выделения»
                // every other selected surface in the app uses, so the chosen chip reads
                // as chosen without going opaque again.
                .then(
                    if (active) {
                        Modifier.background(
                            LeanColors.Accent.copy(alpha = LeanMetrics.selectionWash),
                        )
                    } else {
                        Modifier
                    },
                )
                .padding(horizontal = 14.dp, vertical = 9.dp),
        ) {
            Text(
                title,
                style = LeanType.chip,
                color = if (active) LeanColors.TextPrimary else LeanColors.TextSecondary,
            )
            Text(subtitle, style = LeanType.meta, color = LeanColors.TextTertiary)
        }
    }
}

/**
 * The one button on the screen. Pressed state is a 0.97 scale over 140 ms: pressable
 * things have to answer the finger, and the scale is small enough to read as feedback
 * rather than as motion.
 */
@Composable
private fun RunButton(running: Boolean, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val press by animateFloatAsState(
        targetValue = if (pressed) 0.97f else 1f,
        animationSpec = LeanMotion.tween(140, EaseOutStrong),
        label = "run-press",
    )
    Surface(
        // Transparent for the same reason as the chips: the fill is painted by «Стекло»
        // on the box inside, which is what lets the one solid rectangle on the screen
        // carry the wallpaper like every card around it. The connect disc on «Главная»
        // is built the same way.
        color = Color.Transparent,
        shape = LeanCorner.Button,
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp)
            .scale(press)
            .clip(LeanCorner.Button)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .leanGlass(
                    LeanCorner.Button,
                    if (running) LeanColors.SurfaceVariant else LeanColors.Accent,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                tr(if (running) "Остановить" else "Запустить тест"),
                style = LeanType.cardName,
                color = if (running) LeanColors.TextPrimary else LeanColors.OnAccent,
            )
        }
    }
}

/**
 * What the next run measures.
 *
 * With a tunnel up the screen can answer two different questions, and answering both
 * takes twice the time and twice the traffic — which is a choice to offer, not one to
 * make for someone on a mobile plan. The hint under the control says what each option
 * costs, because "Сравнить" sounds free and is not.
 *
 * Without a tunnel there is exactly one measurable path, so the control is absent rather
 * than disabled: three greyed-out options are a worse explanation than one sentence.
 */
@Composable
private fun ScopeCard(
    scope: SpeedTestViewModel.Scope,
    tunnelUp: Boolean,
    onSelect: (SpeedTestViewModel.Scope) -> Unit,
) {
    LeanGroup {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            if (tunnelUp) {
                SegmentedControl(
                    // Spelled out rather than mapped, in [SCOPE_ORDER]'s order.
                    options = listOf(tr("Через туннель"), tr("В обход"), tr("Сравнить")),
                    selectedIndex = SCOPE_ORDER.indexOf(scope).coerceAtLeast(0),
                    onSelect = { index -> onSelect(SCOPE_ORDER[index]) },
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    tr(scopeHint(scope)),
                    style = LeanType.meta,
                    color = LeanColors.TextSecondary,
                )
            } else {
                Text(
                    tr("Туннель выключен, так что мерить можно только саму сеть телефона. Включи VPN, и появится выбор: через туннель, в обход или оба замера для сравнения."),
                    style = LeanType.meta,
                    color = LeanColors.TextSecondary,
                )
            }
        }
    }
}

/**
 * The options, in the order the strip shows them: one path, the other, then both. The
 * labels live at the call site, so this order and that list have to agree.
 */
private val SCOPE_ORDER = listOf(
    SpeedTestViewModel.Scope.Tunnel,
    SpeedTestViewModel.Scope.Direct,
    SpeedTestViewModel.Scope.Both,
)

/** What the chosen option will actually do, in one line. */
private fun scopeHint(scope: SpeedTestViewModel.Scope): String = when (scope) {
    SpeedTestViewModel.Scope.Tunnel -> "Один замер по тому пути, которым трафик идёт прямо сейчас."
    SpeedTestViewModel.Scope.Direct -> "Один замер в обход туннеля: что канал отдаёт без VPN."
    SpeedTestViewModel.Scope.Both -> "Два замера подряд, через туннель и в обход, по одному и тому же серверу. Времени и трафика уйдёт вдвое больше."
}

/** The four figures one run produced, as a single card of labelled values. */
@Composable
private fun FiguresCard(result: SpeedTest.Result, hadTunnel: Boolean) {
    LeanGroup {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 16.dp)) {
            Figure(tr("Приём"), formatRate(result.downloadMbps), Modifier.weight(1f))
            Figure(tr("Отдача"), formatRate(result.uploadMbps), Modifier.weight(1f))
        }
        // A dash on its own tells the user nothing and looks like the app's fault. When a
        // direction has no number the test knows why, so it says so.
        Note(tr("Приём не измерен"), result.downloadNote)
        Note(tr("Отдача не измерена"), result.uploadNote)
        LeanDivider()
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 16.dp)) {
            Figure(
                tr("Задержка"),
                Rate(result.pingMs?.toString() ?: DASH, "мс"),
                Modifier.weight(1f),
            )
            Figure(
                tr("Джиттер"),
                Rate(result.jitterMs?.toString() ?: DASH, "мс"),
                Modifier.weight(1f),
            )
        }
        if (!hadTunnel) {
            LeanDivider()
            // Which link, when there was a tunnel to go around: the figure means nothing
            // without it. A direct leg that landed on the cellular radio while the tunnel
            // ran over Wi-Fi read as a tunnel twenty times faster than its own underlay,
            // and nothing on the screen said so.
            Text(
                result.path?.let { tr("Замер в обход туннеля, по сети: %s").format(tr(it)) }
                    ?: tr("Замер без туннеля: это скорость самой сети телефона."),
                style = LeanType.meta,
                color = LeanColors.TextTertiary,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            )
        }
    }
}

/** One line explaining a direction that produced no figure. Absent when it produced one. */
@Composable
private fun Note(what: String, reason: String?) {
    if (reason == null) return
    Text(
        "$what: ${tr(reason)}",
        style = LeanType.meta,
        color = LeanColors.TextTertiary,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
    )
}

@Composable
private fun Figure(label: String, rate: Rate, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(label, style = LeanType.meta, color = LeanColors.TextSecondary)
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                rate.value,
                style = LeanType.statValue.copy(fontSize = 28.sp, fontWeight = FontWeight.SemiBold),
                color = LeanColors.TextPrimary,
            )
            Spacer(Modifier.width(5.dp))
            Text(
                tr(rate.unit),
                style = LeanType.meta,
                color = LeanColors.TextTertiary,
                modifier = Modifier.padding(bottom = 3.dp),
            )
        }
    }
}

/**
 * The pair, and what it means.
 *
 * The percentage is the whole point of the card: it turns two numbers nobody can hold in
 * their head into the one sentence the user wanted («теряю 16 %»). Stated as a loss when
 * the tunnel is slower and as a gain when it is faster, which does happen on carriers that
 * shape plain traffic harder than tunnelled traffic.
 */
@Composable
private fun ComparisonCard(
    tunnel: SpeedTest.Result,
    direct: SpeedTest.Result,
    deltaPercent: Int?,
) {
    LeanGroup {
        ComparisonRow(tr("Через туннель"), tunnel, LeanColors.Accent)
        LeanDivider()
        ComparisonRow(tr("Напрямую"), direct, LeanColors.TextSecondary)
        if (deltaPercent != null) {
            LeanDivider()
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    tr(if (deltaPercent < 0) "Потеря скорости" else "Быстрее через туннель"),
                    style = LeanType.rowTitle,
                    color = LeanColors.TextPrimary,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    "%s%d %%".format(if (deltaPercent < 0) "−" else "+", abs(deltaPercent)),
                    style = LeanType.statValue.copy(fontSize = 24.sp, fontWeight = FontWeight.SemiBold),
                    color = when {
                        deltaPercent >= 0 -> LeanColors.Connected
                        deltaPercent > -35 -> LeanColors.TextPrimary
                        else -> LeanColors.Error
                    },
                )
            }
        }
    }
}

@Composable
private fun ComparisonRow(label: String, result: SpeedTest.Result, accent: Color) {
    val down = formatRate(result.downloadMbps)
    val up = formatRate(result.uploadMbps)
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = LeanType.rowTitle, color = accent, modifier = Modifier.weight(1f))
            Text(
                "↓ %s %s   ↑ %s %s".format(down.value, tr(down.unit), up.value, tr(up.unit)),
                style = LeanType.msReadout,
                color = LeanColors.TextPrimary,
            )
        }
        Text(
            // The link is part of the identity of a measurement, not a detail: the two
            // rows of this card are only comparable if they ran over the same one.
            listOfNotNull(
                "${result.server.sponsor} · ${result.server.place}",
                result.path?.let { tr(it) },
            ).joinToString(" · "),
            style = LeanType.meta,
            color = LeanColors.TextTertiary,
        )
    }
}

/**
 * The history as a shape before it is a list.
 *
 * Twenty rows of numbers do not answer "is it getting worse"; a line does, at a glance,
 * and it costs one Canvas. Oldest on the left, which is the direction people read a
 * timeline in — the stored list is newest-first, so it is drawn reversed.
 */
@Composable
private fun HistorySpark(runs: List<SpeedTestHistoryStore.Entry>) {
    val points = remember(runs) {
        runs.mapNotNull { it.downloadMbps }.reversed().takeLast(SPARK_POINTS)
    }
    if (points.size < 2) return
    val accent = LeanColors.Accent
    val track = LeanColors.Hairline
    LeanGroup {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Text(tr("Приём, последние замеры"), style = LeanType.meta, color = LeanColors.TextSecondary)
            Spacer(Modifier.height(10.dp))
            Canvas(Modifier.fillMaxWidth().height(56.dp)) {
                val max = (points.maxOrNull() ?: 1.0).toFloat().coerceAtLeast(1f)
                val stepX = size.width / (points.size - 1).coerceAtLeast(1)
                drawLine(
                    color = track,
                    start = Offset(0f, size.height),
                    end = Offset(size.width, size.height),
                    strokeWidth = 1.5f,
                )
                var previous: Offset? = null
                points.forEachIndexed { index, value ->
                    val point = Offset(
                        x = stepX * index,
                        y = size.height - (value.toFloat() / max) * size.height * 0.92f,
                    )
                    previous?.let {
                        drawLine(accent, it, point, strokeWidth = 3f, cap = StrokeCap.Round)
                    }
                    previous = point
                }
                previous?.let { drawCircle(accent, radius = 4.5f, center = it) }
            }
        }
    }
}

@Composable
private fun HistoryRow(entry: SpeedTestHistoryStore.Entry) {
    val down = formatRate(entry.downloadMbps)
    val up = formatRate(entry.uploadMbps)
    LeanGroup {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    entry.serverName.ifBlank { tr("без туннеля") },
                    style = LeanType.rowTitle,
                    color = LeanColors.TextPrimary,
                )
                Text(
                    listOf(formatClock(entry.atMillis), entry.endpointLabel)
                        .filter { it.isNotBlank() }
                        .joinToString(" · "),
                    style = LeanType.meta,
                    color = LeanColors.TextTertiary,
                )
            }
            Text(
                "↓ %s   ↑ %s".format(down.value, up.value),
                style = LeanType.msReadout,
                color = LeanColors.TextSecondary,
            )
        }
    }
}

/** Shown before the first run: what the test does, and what it will cost in traffic. */
@Composable
private fun HintCard() {
    LeanGroup {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Text(
                tr("Замер идёт по серверам Speedtest в четыре потока."),
                style = LeanType.rowTitle,
                color = LeanColors.TextPrimary,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                tr("Сервер подбирается по отклику, а в России берётся из встроенного списка — каталог Speedtest оттуда недоступен. Что мерить, решаешь выше: сравнение идёт по одному и тому же серверу, чтобы разница была про туннель, а не про расстояние. На мобильном тарифе один замер — это сотни мегабайт."),
                style = LeanType.meta,
                color = LeanColors.TextSecondary,
            )
        }
    }
}

/**
 * Entry for the cards a finished run adds: a short slide with a fade, staggered by
 * [delayStep], so several cards read as one thing arriving instead of a jump. Nothing
 * waits on it — the content is interactive immediately.
 */
@Composable
private fun Reveal(delayStep: Int = 0, content: @Composable () -> Unit) {
    // visible has to start false and flip: AnimatedVisibility given a constant `true`
    // composes already-visible and plays no enter transition at all.
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (delayStep > 0) kotlinx.coroutines.delay(delayStep * STAGGER_MS)
        shown = true
    }
    AnimatedVisibility(
        visible = shown,
        enter = fadeIn(animationSpec = LeanMotion.tween(220, EaseOutStrong)) +
            slideInVertically(animationSpec = LeanMotion.tween(220, EaseOutStrong)) { it / 6 },
        exit = fadeOut(animationSpec = LeanMotion.tween(120, EaseOutStrong)),
    ) {
        content()
    }
}

@Composable
private fun phaseCaption(stage: SpeedTestViewModel.Stage, hadTunnel: Boolean): String = when (stage) {
    SpeedTestViewModel.Stage.Idle -> tr("Готов к замеру")
    SpeedTestViewModel.Stage.Done -> tr("Замер завершён")
    is SpeedTestViewModel.Stage.Running -> {
        val phase = stage.phase
        // The run has started but has not picked a target yet. No path to name in that
        // case: it is the path itself that is being decided.
        if (phase == null) {
            tr("Ищу сервер…")
        } else {
            val what = when (phase) {
                SpeedTest.Phase.Latency -> tr("Задержка…")
                SpeedTest.Phase.Download -> tr("Приём…")
                SpeedTest.Phase.Upload -> tr("Отдача…")
            }
            val where = when {
                !hadTunnel -> tr("без туннеля")
                stage.viaTunnel -> tr("через туннель")
                else -> tr("напрямую")
            }
            "$what · $where"
        }
    }
}

/** A rate split into the number and its unit, so the two can be styled apart. */
private data class Rate(val value: String, val unit: String)

/**
 * Megabits, or gigabits past a thousand.
 *
 * Significant digits, not a fixed precision: "9.84" and "842" and "2.41" all read at a
 * glance, while "842.37 Мбит/с" claims an accuracy an eight-second transfer does not have.
 */
private fun formatRate(mbps: Double?): Rate = when {
    mbps == null -> Rate(DASH, "Мбит/с")
    mbps >= 1000 -> Rate("%.2f".format(mbps / 1000), "Гбит/с")
    mbps >= 100 -> Rate("%.0f".format(mbps), "Мбит/с")
    mbps >= 10 -> Rate("%.1f".format(mbps), "Мбит/с")
    else -> Rate("%.2f".format(mbps), "Мбит/с")
}

/**
 * The dial's top end for a run that peaked at [peak].
 *
 * Fixed steps, so the scale is a property of the connection rather than of the last
 * sample: a needle whose maximum slides continuously is unreadable, and one pinned at the
 * top on a fast line is useless. Ten gigabit is the last step because that is where
 * consumer hardware stops.
 */
internal fun dialMax(peak: Double): Double =
    DIAL_STEPS.firstOrNull { peak <= it * 0.92 } ?: DIAL_STEPS.last()

/**
 * Where [mbps] sits on the arc, 0..1.
 *
 * A power curve of 0.6 gives the bottom of the range about half the sweep. It is the same
 * compromise a car's speedometer makes: the part you actually live in gets the room.
 */
internal fun dialFraction(mbps: Double, scaleMax: Double): Float {
    if (scaleMax <= 0.0) return 0f
    val ratio = (mbps / scaleMax).coerceIn(0.0, 1.0)
    return ratio.pow(DIAL_CURVE).toFloat()
}

/**
 * Tick positions for a scale, with labels on the ones worth naming.
 *
 * The labelled values are the round numbers inside the current scale; the rest are
 * unlabelled minor marks, placed on the curve so they land where the fill actually
 * reaches those values.
 *
 * Only round numbers are labelled — the scale itself is never printed. While the dial is
 * zooming between two ranges the scale is whatever the animation is passing through, and
 * a tick that read "137" for a third of a second would be noise. The ranges are all in
 * [DIAL_LABEL_VALUES], so the top of the arc still carries its number once the zoom
 * settles; the tolerance is there because `exp(ln(250))` is not 250.
 */
internal fun dialLabels(scaleMax: Double): List<Pair<Float, String>> {
    val majors = DIAL_LABEL_VALUES.filter { it <= scaleMax * LABEL_SLACK }
    val marks = mutableListOf<Pair<Float, String>>()
    marks += 0f to "0"
    majors.distinct().forEach { value ->
        val fraction = dialFraction(value, scaleMax)
        val label = when {
            value >= 1000 -> "%.0fG".format(value / 1000)
            else -> "%.0f".format(value)
        }
        marks += fraction to label
    }
    // Minor marks between the labelled ones, for a dial that does not look sparse.
    for (index in 1 until MINOR_TICKS) {
        val fraction = index / MINOR_TICKS.toFloat()
        if (marks.none { abs(it.first - fraction) < 0.035f }) marks += fraction to ""
    }
    return marks.sortedBy { it.first }
}

private fun formatClock(atMillis: Long): String {
    val calendar = Calendar.getInstance().apply { timeInMillis = atMillis }
    return "%02d:%02d".format(calendar.get(Calendar.HOUR_OF_DAY), calendar.get(Calendar.MINUTE))
}

/**
 * The strong ease-out the app's transitions use in spirit: starts fast, so the interface
 * answers before the eye has finished moving. Compose's stock curves are too weak to read
 * as intentional at these durations.
 */
private val EaseOutStrong = CubicBezierEasing(0.23f, 1f, 0.32f, 1f)

/** An en dash, the app's "not measured" glyph, never a zero. */
private const val DASH = "—"

/**
 * How long the progress hairline takes to reach a new value. A touch longer than the
 * interval the value itself is refreshed at ([SpeedTestViewModel] ticks it ten times a
 * second), so one value's motion is still running when the next arrives and the crawl
 * never eases to a stop mid-run. It used to be three times this, because the value used
 * to arrive three times a second and only while bytes were moving.
 */
private const val PROGRESS_TWEEN_MS = 160

/**
 * The side gutter, carried by the items instead of the list; see [Gutters].
 */
private val GUTTER = 16.dp

/** 240° of sweep, opening downwards, so the gap sits under the readout. */
private const val GAUGE_START_ANGLE = 150f
private const val GAUGE_SWEEP = 240f

/** Dial ranges, in Мбит/с. Internal, like the three functions above: pinned by tests. */
internal val DIAL_STEPS = listOf(10.0, 25.0, 50.0, 100.0, 250.0, 500.0, 1000.0, 2500.0, 5000.0, 10000.0)

/**
 * Values worth printing on the arc when they fall inside the current range. Every entry of
 * [DIAL_STEPS] is in here, so whatever range the dial settles on, the end of the arc is
 * labelled.
 */
private val DIAL_LABEL_VALUES =
    listOf(1.0, 5.0, 10.0, 25.0, 50.0, 100.0, 250.0, 500.0, 1000.0, 2500.0, 5000.0, 10000.0)

/** Rounding slack for "does this label fit in the current scale"; see [dialLabels]. */
private const val LABEL_SLACK = 1.002

/**
 * How long the dial takes to zoom to a new range. Long enough to read as the instrument
 * changing scale rather than as the needle falling over; short enough that the number and
 * the arc are not telling different stories for long.
 */
private const val SCALE_TWEEN_MS = 520

private const val DIAL_CURVE = 0.6
private const val MINOR_TICKS = 16

/** How many runs the sparkline shows. More than this and the line is noise. */
private const val SPARK_POINTS = 12

/** Between staggered cards. Short enough to read as one arrival, per the house rule. */
private const val STAGGER_MS = 40L

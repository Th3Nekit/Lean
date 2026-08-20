package com.th3web.lean.ui.screen.appearance

import androidx.compose.animation.core.EaseInOutSine
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.th3web.lean.core.VpnState
import com.th3web.lean.ui.formatBytes
import com.th3web.lean.ui.icons.LeanIcon
import com.th3web.lean.ui.icons.LeanIconImage
import com.th3web.lean.ui.theme.AppearanceSpec
import com.th3web.lean.ui.theme.BackgroundImage
import com.th3web.lean.ui.theme.backgroundImagePlan
import com.th3web.lean.ui.theme.drawBackgroundImage
import com.th3web.lean.ui.theme.LeanPalette
import com.th3web.lean.ui.theme.leanShapes
import com.th3web.lean.ui.theme.leanTypography
import com.th3web.lean.ui.tr

/**
 * The «Витрина», a working miniature of the app, drawn in a look that is not the app's.
 *
 * the one rule this file exists to keep: it reads no theme global. Not `LeanColors`, not
 * `LeanCorner`, not `LeanType`, not `LeanMetrics`. Everything it draws comes from the
 * [palette] and [spec] it is handed, and its content sits inside its own nested
 * [MaterialTheme] built from the same pair.
 *
 * That is what lets a preset thumbnail and a slider still under the finger render a look
 * the app is not wearing. The alternative, publishing the draft to the globals and letting
 * the real components draw it, would repaint the entire screen behind the sheet on every
 * frame of a drag: `LeanColors` is read ~314 times across 19 files and is snapshot state,
 * not a CompositionLocal, so there is no scope in which a draft could be confined.
 *
 * It is also why the ~three scenes below are hand-drawn instead of calling `ServerRow`,
 * `SubscriptionCard` or `LeanBadge`: those read the globals, so inside the preview they
 * would silently render the live look next to the draft one, the exact "preview lies"
 * Failure this whole design is built to make impossible. The cost is this file; the cost of
 * the alternative is a lie the user only discovers after committing.
 *
 * The nested theme is not decoration either: reading `MaterialTheme.shapes` and
 * `MaterialTheme.typography` inside it is how «Скругление», «Шрифт» and «Размер текста»
 * reach the miniature without a second lookup table, B1's `leanTypography` and B2's
 * `leanShapes` feed both the app and the preview from one place.
 */
@Composable
fun AppearancePreview(
    palette: LeanPalette,
    spec: AppearanceSpec,
    modifier: Modifier = Modifier,
    scene: Int = PreviewScene.HOME,
    state: VpnState = VpnState.Disconnected,
    animate: Boolean = false,
    compact: Boolean = false,
    debug: Boolean = false,
    height: Dp = PreviewHeight,
    onHeroClick: (() -> Unit)? = null,
) {
    // Two gates, both required. [animate] is the caller's, «Показывать витрину» off, or a
    // thumbnail, means no frame driver on a settings screen at all. `motionEnabled` is the
    // look's: a preset with «Анимации → выкл» must preview as still, or the switch appears
    // not to work. The transitions below are then created only when both hold, never
    // created-and-ignored, the RefreshGlyph rule (an infinite transition requests frames
    // for as long as it is composed, whatever its output is used for).
    val moving = animate && spec.motionEnabled
    // Memoised for the same reason `LeanTheme` memoises them: `MaterialTheme` provides
    // these through CompositionLocals, so a fresh `Shapes`/`Typography` instance per
    // recomposition invalidates every reader in the subtree. A carousel of seven live
    // preset cards recomposes as it scrolls, and each rebuild is fifteen `TextStyle`
    // allocations that compare unequal to the fifteen before them.
    val shapes = remember(spec.corner) { leanShapes(spec.corner) }
    val typography = remember(spec) { leanTypography(spec) }
    MaterialTheme(
        colorScheme = palette.scheme,
        shapes = shapes,
        typography = typography,
    ) {
        val frame = MaterialTheme.shapes.large
        // heightIn, not height. [height] is a floor that keeps the frame from jumping as the
        // scene changes; it is not a cap, because the content inside is measured in `sp` and
        // therefore grows with both «Размер текста» and the device's own font scale. A fixed
        // height would clip the traffic row off the bottom for anyone running large system
        // fonts, silently, and only for them.
        Box(
            modifier = modifier
                .fillMaxWidth()
                .heightIn(min = height)
                .clip(frame)
                .background(palette.background)
                // The showcase exists to answer "what will this look like", so a picture
                // background has to appear in it, otherwise the one setting whose result
                // is hardest to imagine is the one the preview stays silent about. Drawn
                // through the same planner the real canvas uses, so the crop, zoom, tint
                // and blur shown here are the ones the home screen will use.
                .drawBehind {
                    if (spec.bgStyle == "image") {
                        val image = BackgroundImage.forBlur(spec.bgImageBlur)
                        if (image != null) {
                            drawBackgroundImage(backgroundImagePlan(image, size, palette.background))
                        }
                    }
                }
                .rim(rimWidth(spec), palette.outline, frame),
            contentAlignment = Alignment.Center,
        ) {
            when (scene) {
                PreviewScene.SERVERS -> ServersScene(palette, spec, compact)
                PreviewScene.SETTINGS -> SettingsScene(palette, spec, compact)
                else -> HomeScene(palette, spec, state, moving, compact, onHeroClick)
            }
            if (debug) DebugOverlay(palette, spec)
        }
    }
}

/** Which miniature to draw. Plain ints so the caller's segmented control indexes straight in. */
object PreviewScene {
    const val HOME = 0
    const val SERVERS = 1
    const val SETTINGS = 2
    const val COUNT = 3
}

/**
 * The next state in the demo cycle, for the tap-the-hero affordance.
 *
 * Lives here rather than in the tab because the cycle is a property of the showcase: it
 * exists so the reserved sage «Подключено» ink and the error ink can be seen without
 * raising a tunnel or breaking one, and those two are the whole reason to cycle at all.
 */
fun nextPreviewState(state: VpnState): VpnState = when (state) {
    is VpnState.Disconnected -> VpnState.Connecting
    is VpnState.Connecting -> PreviewConnected
    is VpnState.Connected -> PreviewError
    else -> VpnState.Disconnected
}

/**
 * Floor for the full showcase, roughly the natural height of its tallest scene, so
 * switching «Сцена» does not resize the header while the user is comparing looks.
 */
val PreviewHeight = 216.dp

/** Floor for a preset thumbnail: the reduced hero plus one line, with room for large fonts. */
val PreviewThumbHeight = 104.dp

// ── Scenes ───────────────────────────────────────────────────────────────────

@Composable
private fun HomeScene(
    palette: LeanPalette,
    spec: AppearanceSpec,
    state: VpnState,
    moving: Boolean,
    compact: Boolean,
    onHeroClick: (() -> Unit)?,
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(scenePad(spec, compact)),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        PreviewHero(palette, spec, state, moving, compact, onHeroClick)
        Spacer(Modifier.height(if (compact) 6.dp else 10.dp))
        Text(
            statusLabel(state),
            color = statusInk(palette, state),
            style = if (compact) MaterialTheme.typography.labelMedium else MaterialTheme.typography.titleLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (!compact && spec.currentServerLabel != "hidden") {
            Spacer(Modifier.height(4.dp))
            Text(
                if (spec.currentServerLabel == "name_proto") "$SampleServer · VLESS" else SampleServer,
                color = palette.textPrimary,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (!compact && spec.trafficRow != "hidden") {
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                TrafficCell("↑", SampleUpRate, SampleUpTotal, palette, spec)
                TrafficCell("↓", SampleDownRate, SampleDownTotal, palette, spec)
            }
        }
    }
}

@Composable
private fun ServersScene(palette: LeanPalette, spec: AppearanceSpec, compact: Boolean) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(scenePad(spec, compact)),
        verticalArrangement = Arrangement.spacedBy(if (spec.serverRow == "compact") 5.dp else 8.dp),
    ) {
        PreviewServerRow(palette, spec, SampleServer, 74, SampleTagsFast, selected = true, compact = compact)
        PreviewServerRow(palette, spec, SampleServer2, 168, SampleTagsQuic, selected = false, compact = compact)
        if (!compact) {
            PreviewServerRow(palette, spec, SampleServer3, 421, SampleTagsSlow, selected = false, compact = false)
        }
    }
}

@Composable
private fun SettingsScene(palette: LeanPalette, spec: AppearanceSpec, compact: Boolean) {
    val card = MaterialTheme.shapes.large
    Column(modifier = Modifier.fillMaxWidth().padding(scenePad(spec, compact))) {
        Text(
            // «Подписи разделов заглавными» is two lines of code and changes the character
            // of the whole interface, so it earns the one label this scene carries.
            if (spec.sectionCaps) tr("Внешний вид").uppercase() else tr("Внешний вид"),
            color = palette.accent,
            style = MaterialTheme.typography.labelMedium,
        )
        Spacer(Modifier.height(6.dp))
        Column(
            Modifier
                .fillMaxWidth()
                .clip(card)
                .background(palette.surface)
                .rim(rimWidth(spec), palette.outline, card),
        ) {
            PreviewSettingsRow(palette, spec, LeanIcon.Palette, tr("Тема оформления")) {
                PreviewValuePill(palette, spec.mode.uppercase())
            }
            if (!compact) {
                if (spec.showDividers) PreviewDivider(palette, spec)
                PreviewSettingsRow(palette, spec, LeanIcon.Power, tr("Соединение")) {
                    // Stock M3, inside the nested theme it already resolves against the
                    // draft scheme, so it is one of the few real widgets that can be used
                    // here without leaking the live look.
                    Switch(checked = true, onCheckedChange = null)
                }
            }
        }
    }
}

// ── Home pieces ──────────────────────────────────────────────────────────────

/**
 * The connect hero, at a quarter scale. Same `drawBehind` recipe as the real button, a
 * stroked circle at rest, a 100° sweeping arc while connecting, because the four
 * «Кнопка подключения» styles differ only in stroke, fill and ring count, and that is
 * exactly what this draws.
 */
@Composable
private fun PreviewHero(
    palette: LeanPalette,
    spec: AppearanceSpec,
    state: VpnState,
    moving: Boolean,
    compact: Boolean,
    onClick: (() -> Unit)?,
) {
    val on = state is VpnState.Connected
    val connecting = state is VpnState.Connecting || state is VpnState.Stopping
    val error = state is VpnState.Error
    val minimal = spec.heroStyle == "minimal"

    val outer = (if (compact) CompactHeroSize else HeroSize) * spec.heroScale
    val sweep = if (moving && connecting) previewSweep(spec) else 0f
    val breath = if (moving && on && spec.heroBreath) previewBreath(spec) else 1f

    val ring = when {
        error -> palette.error
        on -> palette.accent
        else -> palette.outline
    }
    val glyphInk = when {
        on -> palette.scheme.onPrimaryContainer
        connecting -> palette.accentDim
        error -> palette.error
        else -> palette.textSecondary
    }
    val discFill = when {
        minimal -> Color.Transparent
        on -> palette.scheme.primaryContainer
        else -> palette.surfaceVariant
    }

    Box(
        modifier = Modifier
            .size(outer)
            .then(if (onClick == null) Modifier else Modifier.clip(CircleShape).clickable(onClick = onClick))
            .drawBehind {
                if (connecting) {
                    val stroke = RingStrokeConnecting.toPx()
                    drawArc(
                        color = palette.accent,
                        startAngle = sweep,
                        sweepAngle = 100f,
                        useCenter = false,
                        topLeft = Offset(stroke / 2f, stroke / 2f),
                        size = Size(size.width - stroke, size.height - stroke),
                        style = Stroke(width = stroke, cap = StrokeCap.Round),
                    )
                    return@drawBehind
                }
                // «Кнопка подключения → диск» is the one style with no ring at all.
                if (spec.heroStyle == "disc") return@drawBehind
                val stroke = when {
                    minimal -> RingStrokeMinimal
                    on || error -> RingStrokeActive
                    else -> RingStrokeIdle
                }.toPx()
                val alpha = ring.alpha * breath
                drawCircle(
                    color = ring.copy(alpha = alpha),
                    radius = (size.minDimension - stroke) / 2f,
                    style = Stroke(width = stroke),
                )
                if (spec.heroStyle == "pulse") {
                    drawCircle(
                        color = ring.copy(alpha = alpha * PulseInnerAlpha),
                        radius = (size.minDimension - stroke) / 2f - PulseGap.toPx(),
                        style = Stroke(width = stroke),
                    )
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier.size(outer * DiscRatio).clip(CircleShape).background(discFill),
            contentAlignment = Alignment.Center,
        ) {
            LeanIconImage(
                glyphFor(spec.heroGlyph),
                tint = glyphInk,
                modifier = Modifier.size(outer * GlyphRatio),
            )
        }
    }
}

@Composable
private fun previewSweep(spec: AppearanceSpec): Float {
    val transition = rememberInfiniteTransition(label = "previewSweep")
    val angle by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(scaledMs(SweepMs, spec), easing = LinearEasing)),
        label = "previewSweepAngle",
    )
    return angle
}

@Composable
private fun previewBreath(spec: AppearanceSpec): Float {
    val transition = rememberInfiniteTransition(label = "previewBreath")
    val breath by transition.animateFloat(
        initialValue = BreathFloor,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            tween(scaledMs(BreathMs, spec), easing = EaseInOutSine),
            RepeatMode.Reverse,
        ),
        label = "previewBreathAlpha",
    )
    return breath
}

@Composable
private fun TrafficCell(arrow: String, rate: Long, total: Long, palette: LeanPalette, spec: AppearanceSpec) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            "$arrow ${formatBytes(rate)}/s",
            color = palette.textPrimary,
            style = if (spec.trafficRow == "compact") {
                MaterialTheme.typography.titleMedium
            } else {
                MaterialTheme.typography.titleLarge
            },
            maxLines = 1,
        )
        Text(formatBytes(total), color = palette.textSecondary, style = MaterialTheme.typography.bodySmall, maxLines = 1)
    }
}

// ── Server-list pieces ───────────────────────────────────────────────────────

@Composable
private fun PreviewServerRow(
    palette: LeanPalette,
    spec: AppearanceSpec,
    name: String,
    latencyMs: Int,
    tags: List<String>,
    selected: Boolean,
    compact: Boolean,
) {
    val shape = MaterialTheme.shapes.medium
    val striped = selected && (spec.selectionCue == "stripe" || spec.selectionCue == "both")
    val washed = selected && spec.selectionWash > 0f
    Box(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(palette.surface)
            .then(if (washed) Modifier.background(palette.accent.copy(alpha = spec.selectionWash)) else Modifier)
            .rim(rimWidth(spec), if (selected) palette.accent else palette.outline, shape),
    ) {
        if (striped) {
            Box(
                Modifier
                    .align(Alignment.CenterStart)
                    .width(StripeWidth)
                    .height(StripeHeight)
                    .clip(CircleShape)
                    .background(palette.accent),
            )
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = rowPadV(spec)),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    name,
                    color = if (selected) palette.accent else palette.textPrimary,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (spec.showTags && spec.serverRow != "compact" && !compact) {
                    Spacer(Modifier.height(2.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        tags.forEachIndexed { i, label ->
                            PreviewTag(palette, spec, label, prominent = i == 0)
                        }
                    }
                }
            }
            Spacer(Modifier.width(8.dp))
            PreviewLatency(palette, spec, latencyMs)
        }
    }
}

/**
 * The four-bar meter and its readout.
 *
 * The tier is picked here, from [LeanPalette]'s own ramp and [AppearanceSpec]'s own
 * thresholds, rather than by calling `latencyTier`, that one resolves against the live
 * `LeanColors` ramp, so a preview using it would show the app's current «Цвет пинга»
 * next to the draft's everything-else. Reading the ramp off the palette makes «Цвет пинга»
 * and «Пороги пинга» previewable for free.
 */
@Composable
private fun PreviewLatency(palette: LeanPalette, spec: AppearanceSpec, ms: Int) {
    val (ink, bars) = previewTier(palette, spec, ms)
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (spec.latencyMeter != "ms") {
            Canvas(Modifier.size(width = MeterWidth, height = MeterHeight)) {
                val barW = MeterBarWidth.toPx()
                val gap = MeterBarGap.toPx()
                val cap = CornerRadius(barW / 2f, barW / 2f)
                MeterBarFractions.forEachIndexed { i, f ->
                    val h = size.height * f
                    drawRoundRect(
                        color = if (i < bars) ink else palette.barUnlit,
                        topLeft = Offset(i * (barW + gap), size.height - h),
                        size = Size(barW, h),
                        cornerRadius = cap,
                    )
                }
            }
        }
        if (spec.latencyMeter != "bars") {
            Spacer(Modifier.width(5.dp))
            Text("$ms ms", color = palette.textSecondary, style = MaterialTheme.typography.labelSmall, maxLines = 1)
        }
    }
}

private fun previewTier(palette: LeanPalette, spec: AppearanceSpec, ms: Int): Pair<Color, Int> = when {
    ms < 0 -> palette.latencyNone to 0
    ms <= spec.latT1 -> palette.latencyTier1 to 4
    ms <= spec.latT2 -> palette.latencyTier2 to 3
    ms <= spec.latT3 -> palette.latencyTier3 to 2
    else -> palette.latencyTier4 to 1
}

@Composable
private fun PreviewTag(palette: LeanPalette, spec: AppearanceSpec, text: String, prominent: Boolean) {
    val shape = MaterialTheme.shapes.extraSmall
    Box(
        Modifier
            .clip(shape)
            .then(
                if (prominent) {
                    Modifier.background(palette.tagGhost)
                } else {
                    Modifier.rim(rimWidth(spec), palette.outline, shape)
                },
            )
            .padding(horizontal = 5.dp, vertical = 1.dp),
    ) {
        Text(
            text,
            color = if (prominent) palette.tagInk else palette.textSecondary,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
        )
    }
}

// ── Settings pieces ──────────────────────────────────────────────────────────

@Composable
private fun PreviewSettingsRow(
    palette: LeanPalette,
    spec: AppearanceSpec,
    icon: LeanIcon,
    title: String,
    trailing: @Composable () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = rowPadV(spec)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val badge = badgeSize(spec)
        Box(
            Modifier.size(badge).clip(MaterialTheme.shapes.small).background(palette.surfaceElevated),
            contentAlignment = Alignment.Center,
        ) {
            LeanIconImage(icon, tint = palette.accent, modifier = Modifier.size(badge * BadgeGlyphRatio))
        }
        Spacer(Modifier.width(8.dp))
        Text(
            title,
            color = palette.textPrimary,
            style = MaterialTheme.typography.bodyLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        trailing()
    }
}

@Composable
private fun PreviewValuePill(palette: LeanPalette, value: String) {
    Box(
        Modifier
            .clip(MaterialTheme.shapes.extraSmall)
            .background(palette.surfaceElevated)
            .padding(horizontal = 8.dp, vertical = 3.dp),
    ) {
        Text(value, color = palette.textSecondary, style = MaterialTheme.typography.labelSmall, maxLines = 1)
    }
}

/** The hairline, at the indent «Отступ разделителей» asks for, derived from the badge, as the real one is. */
@Composable
private fun PreviewDivider(palette: LeanPalette, spec: AppearanceSpec) {
    val indent = if (spec.dividerIndent == "full") 0.dp else badgeSize(spec) + 18.dp
    Box(
        Modifier
            .fillMaxWidth()
            .padding(start = indent)
            .height(1.dp)
            .background(palette.hairline),
    )
}

// ── Debug readout («Показать ключ оформления») ────────────────────────────────

/**
 * The look, printed. A screenshot of this replaces the twenty clarifying questions a
 * support thread otherwise needs to reconstruct what the user is actually looking at.
 */
@Composable
private fun BoxScope.DebugOverlay(palette: LeanPalette, spec: AppearanceSpec) {
    Column(
        Modifier
            .align(Alignment.BottomStart)
            .fillMaxWidth()
            .background(palette.background.copy(alpha = DebugScrimAlpha))
            .padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        Text(
            "${spec.mode}/${spec.corner}/${spec.density} c${spec.contrastLevel} " +
                "t${(spec.surfaceTint * 100f).toInt()} ${spec.fontDisplay}+${spec.fontBody} ${spec.textScale}%",
            color = palette.textTertiary,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            "${hexOf(palette.background)} ${hexOf(palette.surface)} ${hexOf(palette.accent)} " +
                "${hexOf(palette.textPrimary)} ${hexOf(palette.outline)}",
            color = palette.textTertiary,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private fun hexOf(color: Color): String = "#%06X".format(color.toArgb().toLong() and 0xFFFFFFL)

// ── Shared resolution ────────────────────────────────────────────────────────
//
// These re-derive from [AppearanceSpec] what LeanMetrics publishes for the live app. That
// duplication is intentional, not an oversight: LeanMetrics mirrors the look the app is
// wearing, and the preview's whole job is to draw one it is not.

private fun rimWidth(spec: AppearanceSpec): Dp = when (spec.outlineWeight) {
    "none" -> 0.dp
    "strong" -> 1.5.dp
    else -> 1.dp
}

/** A thumbnail has no room to spend on density; only the full showcase previews «Плотность». */
private fun scenePad(spec: AppearanceSpec, compact: Boolean): Dp = when {
    compact -> 8.dp
    spec.density == "compact" -> 10.dp
    spec.density == "comfortable" -> 18.dp
    else -> 14.dp
}

private fun rowPadV(spec: AppearanceSpec): Dp = when (spec.density) {
    "compact" -> 6.dp
    "comfortable" -> 12.dp
    else -> 9.dp
}

private fun badgeSize(spec: AppearanceSpec): Dp = when (spec.density) {
    "compact" -> 26.dp
    "comfortable" -> 34.dp
    else -> 30.dp
}

/** A zero-width border still allocates a draw node, and «Контуры → нет» means none. */
private fun Modifier.rim(width: Dp, color: Color, shape: Shape): Modifier =
    if (width <= 0.dp) this else border(width, color, shape)

private fun glyphFor(key: String): LeanIcon = when (key) {
    "shield" -> LeanIcon.Shield
    "globe" -> LeanIcon.Globe
    "pulse" -> LeanIcon.Pulse
    else -> LeanIcon.Power
}

private fun statusLabel(state: VpnState): String = when (state) {
    is VpnState.Connected -> tr("Подключено")
    is VpnState.Connecting -> tr("Подключение…")
    is VpnState.Stopping -> tr("Отключение…")
    is VpnState.Error -> tr("Ошибка")
    else -> tr("Нажмите для подключения")
}

/**
 * Connected inks the status line with [LeanPalette.connected], the reserved sage. It is
 * the app's one reassuring colour and the only thing «Цвет „Подключено"» moves, so
 * the showcase is the only place it can be judged without actually raising a tunnel.
 */
private fun statusInk(palette: LeanPalette, state: VpnState): Color = when (state) {
    is VpnState.Connected -> palette.connected
    is VpnState.Connecting, is VpnState.Stopping -> palette.connecting
    is VpnState.Error -> palette.error
    else -> palette.textSecondary
}

/** Duration under the look's own «Анимации» multiplier; floored so a tween can never be 0ms. */
private fun scaledMs(ms: Int, spec: AppearanceSpec): Int =
    (ms * spec.motionScale).toInt().coerceAtLeast(1)

/**
 * The two demo states that carry a payload, hoisted so the cycle allocates nothing and two
 * consecutive taps compare equal instead of recomposing the whole miniature.
 *
 * Public because a thumbnail wants [PreviewConnected] specifically: at rest the hero is all
 * neutrals (outline ring, `surfaceVariant` disc, secondary glyph), so a disconnected
 * miniature shows a preset's canvas and corners but not one pixel of its accent, which is
 * the thing most presets differ by.
 */
val PreviewConnected: VpnState = VpnState.Connected("preview")
val PreviewError: VpnState = VpnState.Error("preview")

// Hero geometry, the real button's 224/204/78dp at roughly 2.8x down.
private val HeroSize = 80.dp
private val CompactHeroSize = 46.dp
private const val DiscRatio = 204f / 224f
private const val GlyphRatio = 78f / 224f
private val RingStrokeConnecting = 3.dp
private val RingStrokeActive = 2.dp
private val RingStrokeIdle = 1.5.dp
private val RingStrokeMinimal = 1.dp
private val PulseGap = 5.dp
private const val PulseInnerAlpha = 0.35f
private const val BreathFloor = 0.55f
private const val SweepMs = 1_200
private const val BreathMs = 2_400

// Server-row geometry, the real row's 4/32dp stripe, scaled to the miniature.
private val StripeWidth = 3.dp
private val StripeHeight = 20.dp
private val MeterWidth = 17.dp
private val MeterHeight = 12.dp
private val MeterBarWidth = 2.5.dp
private val MeterBarGap = 2.dp
private val MeterBarFractions = listOf(0.34f, 0.55f, 0.76f, 1f)

private const val BadgeGlyphRatio = 0.52f
private const val DebugScrimAlpha = 0.82f

// Sample content. Place names and protocol tags, never UI copy: the miniature must show
// the user's fonts and inks, not invent strings the tab would then have to translate.
private const val SampleServer = "Frankfurt"
private const val SampleServer2 = "Zürich"
private const val SampleServer3 = "Amsterdam"
private val SampleTagsFast = listOf("🛡 VLESS", "REALITY")
private val SampleTagsQuic = listOf("⚡ Hysteria2", "TLS")
private val SampleTagsSlow = listOf("🛡 Trojan")
private const val SampleUpRate = 1_248_000L
private const val SampleDownRate = 8_930_000L
private const val SampleUpTotal = 2_147_483_648L
private const val SampleDownTotal = 17_179_869_184L

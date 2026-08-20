package com.th3web.lean.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.th3web.lean.data.model.Outbound
import com.th3web.lean.data.model.Profile
import com.th3web.lean.data.parse.ShareLinks
import com.th3web.lean.ui.theme.LeanColors
import com.th3web.lean.ui.theme.LeanCorner
import com.th3web.lean.ui.theme.LeanMetrics
import com.th3web.lean.ui.theme.LeanOptions
import com.th3web.lean.ui.theme.LeanPalette
import com.th3web.lean.ui.theme.LeanType
import com.th3web.lean.ui.theme.leanGlass
import com.th3web.lean.ui.theme.leanOutline
import com.th3web.lean.ui.theme.motionAllowed
import com.th3web.lean.ui.tr

// ---------------------------------------------------------------------------
// MD3 latency ladder, the four lit tiers plus the null tier live in
// [LeanColors.LatencyTier1]..[LeanColors.LatencyTier4] / [LeanColors.LatencyNone].
//
// They are snapshot state because three knobs reach them: «Цвет пинга» picks the ramp,
// «Контрастность» moves every tier, and a role override can repoint the accent the
// default ramp is built from. A file-level copy of any of them would be captured at class
// load and frozen on whatever look happened to be active first, a bug that compiles
// clean and only shows on a live settings change. Never hoist a LeanColors read to file
// scope.
//
// The ramp is monotonic in luminance and carries quality by brightness and bar count
// rather than by hue, unless the user asks for traffic lights.
// ---------------------------------------------------------------------------

/**
 * One server entry as an MD3 list row.
 *
 * Selection is a CUE, not a background swap: selected and unselected rows keep the same
 * base surface, and what marks the active server is layered on top of it, a leading
 * stripe, a crisper outline, a subtle accent wash and an accent title. «Выделение
 * сервера» / «Сила выделения» tune that stack through [LeanMetrics.selectionStripeWidth]
 * and [LeanMetrics.selectionWash], which are read rather than re-derived from the setting.
 *
 * Every selection colour is a plain derived [Color] with no `animateColorAsState`: one row
 * changes at a time, so a fade is invisible, while the per-row Animatable and coroutine it
 * costs are paid on every row that scrolls into view.
 *
 * Standalone it is a [Box] on `surfaceContainer`; nested inside a [SubscriptionCard] it is
 * a flat transparent row with a small horizontal inset.
 *
 * The row shows the name, the protocol/security/transport [TagBadge]s that «Плашки» keeps,
 * and a [LatencyMeter], or a muted block glyph when [Profile.excludedFromTest], since a
 * server the speed test skips has no number worth showing. The host is never rendered.
 *
 * Long-press opens the row menu, and only when at least one of its callbacks is non-null.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ServerRow(
    profile: Profile,
    selected: Boolean,
    onClick: () -> Unit,
    onDelete: (() -> Unit)? = null,
    nested: Boolean = false,
    onPing: (() -> Unit)? = null,
    onRename: (() -> Unit)? = null,
    onCopyLink: (() -> Unit)? = null,
    onToggleFavorite: (() -> Unit)? = null,
    onToggleExcludeFromTest: (() -> Unit)? = null,
    onEditAwg: (() -> Unit)? = null,
    // Real proxied per-node delay from the core's urltest, set only while connected and
    // when this node is in the urltest group: >=0 ms = works ✓, <0 = tested-failed ✗.
    // When non-null it overrides the edge-probe latencyMs, the authoritative "does it
    // actually proxy" signal rather than mere edge reachability.
    liveDelayMs: Int? = null,
    /**
     * This row is being measured right now, and its meter says so.
     *
     * A sweep probes sixteen at a time across a list that can be sixty long, so without
     * this every row looked identical for the whole minute it ran and there was no way to
     * tell what had been done, what was in progress and what had not been reached.
     */
    probing: Boolean = false,
) {
    val scheme = MaterialTheme.colorScheme
    // Per-row derived work memoized so 50 rows stay cheap. The keys are the resolved
    // inks, not the settings that produced them: «Цвет пинга», «Контрастность», a role
    // override and an accent change all land on these five tokens, so keying on the
    // tokens catches every one of them, and catches the next axis too, without anyone
    // remembering to add a key. A key list narrower than what the lambda reads is the
    // stale-memo bug: up to 50 rows keep their first-composition colours until each one
    // scrolls off and back.
    val (latColor, bars) = remember(
        profile.latencyMs,
        scheme.primary,
        LeanColors.LatencyTier1,
        LeanColors.LatencyTier2,
        LeanColors.LatencyTier3,
        LeanColors.LatencyTier4,
        LeanColors.LatencyNone,
        LeanOptions.latT1,
        LeanOptions.latT2,
        LeanOptions.latT3,
    ) {
        latencyTier(profile.latencyMs, accent = scheme.primary)
    }
    // Same rule for the tags: [tagsFor] bakes Ember / Blue / TextSecondary into the list
    // it returns, so those three inks are the keys, `LeanColors.light` alone missed an
    // accent seed change and every contrast step.
    val tags = remember(
        profile.outbound,
        LeanOptions.showTags,
        LeanOptions.serverTagKinds,
        LeanColors.Ember,
        LeanColors.Blue,
        LeanColors.TextSecondary,
    ) {
        if (LeanOptions.showTags) {
            tagsFor(profile.outbound).filter { it.kind in keptTagKinds(LeanOptions.serverTagKinds) }
        } else {
            emptyList()
        }
    }
    // Collapse a doubled country flag (some providers (e.g. nimarko) emit the same
    // flag twice in the name) at display time, so it fixes servers already stored too,
    // not only newly-parsed ones (a subscription refresh keeps the old name to preserve
    // renames, so a parse-time-only dedupe would miss every existing server).
    val displayName = remember(profile.name) { ShareLinks.dedupeFlags(profile.name) }
    // Selection colours are plain derived values, no animateColorAsState. A
    // per-row Animatable + launched coroutine was being created on every row
    // appearance (the fast-scroll churn); selection flips one row at a time, so
    // the lost 200ms cross-fade is imperceptible. The wash is read only on the
    // `selected` branch, so the 49 unselected rows never allocate it.
    val titleColor = if (selected) scheme.primary else LeanColors.TextPrimary
    val wash = LeanMetrics.selectionWash
    // Read inline, never hoisted: the ladder is snapshot state now, and a file-level
    // `private val SelectedRowShape = LeanCorner.Row` would hand every row the corner
    // radius that was active when this class first loaded.
    val rowShape = if (nested) LeanCorner.Button else LeanCorner.Row
    val outline = LeanMetrics.outlineWidth
    // The name gets a second line only when it has somewhere to go: a larger text scale
    // eats the width the ellipsis would otherwise hide.
    val titleLines = if (LeanOptions.textScale >= 110 || LeanOptions.serverRow == "detailed") 2 else 1

    val hasMenu = onPing != null || onRename != null || onToggleFavorite != null ||
        onToggleExcludeFromTest != null || onCopyLink != null || onDelete != null ||
        (onEditAwg != null && profile.outbound is Outbound.WireGuard)
    var menuOpen by remember { mutableStateOf(false) }

    // Hand-built row layout instead of MD3 [ListItem]: ListItem computes slot
    // paddings, min-heights and its own colour logic on every compose, material
    // overhead paid 50× during a fling. A plain Row+Column composes/measures far
    // cheaper, so a row entering the viewport on scroll lands without a hitch. It is
    // also what lets «Строки списка» tune the server list apart from the settings rows:
    // people want this list denser than a settings screen, and nothing here fights a
    // component's own minimum height.
    val rowContent: @Composable () -> Unit = {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = LeanMetrics.serverRowPadV),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // No leading glyph: the protocol family marker now lives inside the
            // first tag in the bottom tag row (⚡ Hysteria2 / 🛡 VLESS), so the
            // name column starts cleanly at the row's leading edge.
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        displayName,
                        modifier = Modifier.weight(1f, fill = false),
                        color = titleColor,
                        style = LeanType.rowTitle,
                        maxLines = titleLines,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (profile.favorite) {
                        // Quiet favorite cue, 12dp accent star.
                        Icon(
                            Icons.Filled.Star,
                            contentDescription = null,
                            tint = scheme.primary,
                            modifier = Modifier.size(12.dp),
                        )
                    }
                }
                // With «Теги протоколов» off the gap goes too, rather than leaving 3dp
                // of blank space in every row.
                if (tags.isNotEmpty()) {
                    Spacer(Modifier.height(3.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        tags.forEach { TagBadge(it.label, it.color) }
                    }
                }
            }
            Spacer(Modifier.width(12.dp))
            val live = liveDelayMs
            when {
                // Connected: the real proxied per-node result from the core's urltest
                // (✓ + ms when it works, ✗ + dash when the node tested-failed), the
                // authoritative "does it carry traffic" signal, not edge reachability.
                live != null -> {
                    val works = live >= 0
                    val (lc, lb) = latencyTier(
                        ms = if (works) live else null,
                        accent = scheme.primary,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            if (works) Icons.Filled.Check else Icons.Filled.Close,
                            contentDescription = null,
                            tint = if (works) scheme.primary else LeanColors.TextTertiary,
                            modifier = Modifier.size(13.dp),
                        )
                        Spacer(Modifier.width(5.dp))
                        LatencyMeter(if (works) live else null, lc, lb)
                    }
                }
                // Out of the speed test, a quiet Block glyph + dash instead of a stale
                // latency number the bulk pings will never refresh.
                profile.excludedFromTest -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Outlined.Block,
                            contentDescription = null,
                            tint = LeanColors.TextTertiary,
                            modifier = Modifier.size(14.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text("—", color = LeanColors.TextTertiary, style = LeanType.msReadout)
                    }
                }
                else -> LatencyMeter(profile.latencyMs, latColor, bars, probing = probing)
            }
        }
    }

    // Per-row context menu (long-press). Item order:
    // ping · rename · favorite · exclude-from-test · copy link · delete (error tint).
    val contextMenu: @Composable () -> Unit = {
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            onPing?.let { cb ->
                DropdownMenuItem(
                    text = { Text(tr("Проверить пинг")) },
                    onClick = {
                        menuOpen = false
                        cb()
                    },
                )
            }
            onRename?.let { cb ->
                DropdownMenuItem(
                    text = { Text(tr("Переименовать")) },
                    onClick = {
                        menuOpen = false
                        cb()
                    },
                )
            }
            // AmneziaWG obfuscation editor, only meaningful for WireGuard profiles.
            (profile.outbound as? Outbound.WireGuard)?.let { wg ->
                onEditAwg?.let { cb ->
                    DropdownMenuItem(
                        text = {
                            Text(if (wg.awg != null) tr("AmneziaWG: обфускация") else tr("Настроить AmneziaWG"))
                        },
                        onClick = {
                            menuOpen = false
                            cb()
                        },
                    )
                }
            }
            onToggleFavorite?.let { cb ->
                DropdownMenuItem(
                    text = {
                        Text(if (profile.favorite) tr("Убрать из избранного") else tr("В избранное"))
                    },
                    leadingIcon = {
                        Icon(
                            if (profile.favorite) Icons.Filled.Star else Icons.Outlined.StarBorder,
                            contentDescription = null,
                            tint = if (profile.favorite) scheme.primary else LeanColors.TextSecondary,
                            modifier = Modifier.size(20.dp),
                        )
                    },
                    onClick = {
                        menuOpen = false
                        cb()
                    },
                )
            }
            onToggleExcludeFromTest?.let { cb ->
                DropdownMenuItem(
                    text = {
                        Text(
                            if (profile.excludedFromTest) tr("Вернуть в тест скорости")
                            else tr("Исключить из теста скорости"),
                        )
                    },
                    leadingIcon = {
                        Icon(
                            if (profile.excludedFromTest) Icons.Filled.Speed else Icons.Outlined.Block,
                            contentDescription = null,
                            tint = LeanColors.TextSecondary,
                            modifier = Modifier.size(20.dp),
                        )
                    },
                    onClick = {
                        menuOpen = false
                        cb()
                    },
                )
            }
            onCopyLink?.let { cb ->
                DropdownMenuItem(
                    text = { Text(tr("Скопировать ссылку")) },
                    onClick = {
                        menuOpen = false
                        cb()
                    },
                )
            }
            onDelete?.let { cb ->
                DropdownMenuItem(
                    text = { Text(tr("Удалить"), color = scheme.error) },
                    leadingIcon = {
                        Icon(
                            Icons.Outlined.Delete,
                            contentDescription = null,
                            tint = scheme.error,
                            modifier = Modifier.size(20.dp),
                        )
                    },
                    onClick = {
                        menuOpen = false
                        cb()
                    },
                )
            }
        }
    }

    if (nested) {
        // Flat transparent row inside the subscription card, clipped to
        // LeanCorner.Button with a 6dp inset. The base background stays
        // transparent whether selected or not, selection is the accent stack
        // (subtle wash + thin primary outline + leading stripe), never a
        // background swap. Plain values: no animateColorAsState per row.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 6.dp)
                .clip(rowShape)
                .then(
                    // Two independent cues: «Выделение сервера» can drop the wash
                    // (selectionWash == 0), and keep the rim, so they cannot share a branch.
                    if (selected && wash > 0f) {
                        Modifier.background(scheme.primary.copy(alpha = wash))
                    } else {
                        Modifier
                    },
                )
                .then(
                    if (selected) Modifier.leanOutline(rowShape, scheme.primary, outline) else Modifier,
                )
                .combinedClickable(
                    onClick = onClick,
                    onLongClick = if (hasMenu) ({ menuOpen = true }) else null,
                ),
        ) {
            if (selected) SelectionStripe()
            rowContent()
            if (menuOpen) contextMenu()
        }
    } else {
        // Standalone: a plain Box on the unchanged surfaceContainer (no Card /
        // Surface / elevation pass per row). Selected and unselected share the
        // same fill; selection is the accent stack layered on top, a subtle
        // primary wash, a crisper primary outline than the quiet outlineVariant
        // hairline, and the leading stripe. All plain values.
        val borderColor = if (selected) scheme.primary else scheme.outlineVariant
        // The selected rim has always been 1.5× the idle one; scaling rather than
        // hardcoding keeps that ratio under «Контуры» and keeps it at zero under "нет".
        val borderWidth = if (selected) outline * 1.5f else outline
        Box(
            modifier = Modifier
                .fillMaxWidth()
                // No .clip(rowShape) here: leanGlass clips to the same shape as its first
                // act, and a rounded clip is the expensive kind, one per row, on every
                // row, for nothing. Everything that must be clipped (the selection wash,
                // the outline, the ripple) still comes after it.
                // A standalone row is a panel (the Servers tab draws them as separate
                // cards), so it takes glass like the grouped ones; the selection wash
                // stays on top of it, which is what keeps the selected row readable
                // against a picture.
                .leanGlass(rowShape, scheme.surfaceContainer)
                .then(
                    if (selected && wash > 0f) {
                        Modifier.background(scheme.primary.copy(alpha = wash))
                    } else {
                        Modifier
                    },
                )
                .leanOutline(rowShape, borderColor, borderWidth)
                .combinedClickable(
                    onClick = onClick,
                    onLongClick = if (hasMenu) ({ menuOpen = true }) else null,
                ),
        ) {
            if (selected) SelectionStripe()
            rowContent()
            if (menuOpen) contextMenu()
        }
    }
}

/**
 * Bold leading accent stripe, the primary "this one is selected" affordance, pinned to
 * the leading edge in `scheme.primary`. Shared by both row variants so the cue reads
 * identically inside and outside a subscription.
 *
 * Its geometry is [LeanMetrics.selectionStripeWidth]/[LeanMetrics.selectionStripeHeight],
 * and a width of 0 means «Выделение сервера» chose a cue without a stripe, so it draws
 * nothing rather than a hairline.
 */
@Composable
private fun BoxScope.SelectionStripe() {
    val width = LeanMetrics.selectionStripeWidth
    if (width <= 0.dp) return
    Box(
        Modifier
            .align(Alignment.CenterStart)
            .padding(vertical = 8.dp)
            .width(width)
            .height(LeanMetrics.selectionStripeHeight)
            .clip(LeanCorner.BarFill)
            .background(MaterialTheme.colorScheme.primary),
    )
}

/**
 * Dense inline tag, kept instead of M3 chips, which are too tall for in-title
 * use. Corner [LeanCorner.Tag], [LeanType.tagBadge] caps. Two variants
 * derived from the ink's alpha (signature stays frozen):
 *
 *  - **ghost** (`color.alpha >= 0.7`): fill [LeanColors.TagGhost], ink = passed
 *   color (e.g. [LeanColors.TagInk] or the Home screen's premium ink);
 *  - **outline** (`color.alpha < 0.7`): no fill, an `outlineVariant` rim at the «Контуры»
 *   width ([LeanColors.Outline]), ink = passed color.
 */
@Composable
fun TagBadge(text: String, color: Color) {
    val ghost = color.alpha >= 0.7f
    Box(
        modifier = Modifier
            .clip(LeanCorner.Tag)
            .then(
                if (ghost) {
                    Modifier.background(LeanColors.TagGhost)
                } else {
                    Modifier.leanOutline(LeanCorner.Tag, LeanColors.Outline)
                },
            )
            .padding(horizontal = 7.dp, vertical = 2.dp),
    ) {
        Text(
            text,
            color = color,
            style = LeanType.tagBadge,
            maxLines = if (LeanOptions.textScale >= 110) 2 else 1,
        )
    }
}

/**
 * «Индикатор пинга», 4 ascending signal bars (3dp wide, [LeanCorner.BarFill] caps,
 * heights 5/8/11/15dp, 3dp gaps) and/or the tabular ms readout, per
 * [LeanOptions.latencyMeter]: `bars_ms` (both) · `ms` (number only) · `bars` (bars only,
 * for people who read the number as noise).
 *
 * Unlit bars are [LeanColors.BarUnlit], read here, inside the composable. It used to be
 * a `private val BarUnlitColor = LeanColors.BarUnlit` at file scope, which froze the
 * colour at class load the moment that token became state. The ms readout is always
 * [LeanColors.TextSecondary] regardless of tier, bars carry quality, the number stays
 * quiet.
 */
@Composable
fun LatencyMeter(latencyMs: Int?, color: Color?, bars: Int, probing: Boolean = false) {
    val mode = LeanOptions.latencyMeter
    // A wave running up the bars while this server is the one being measured. Cheap by
    // construction: one animated float feeding the existing Canvas, no extra nodes, and
    // it exists only for the rows actually in flight.
    val sweep = if (probing && motionAllowed()) {
        rememberInfiniteTransition(label = "probing").animateFloat(
            initialValue = 0f,
            targetValue = BarHeightsDp.size + 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(900, easing = LinearEasing),
                repeatMode = RepeatMode.Restart,
            ),
            label = "probingSweep",
        ).value
    } else {
        null
    }
    val showBars = mode != "ms"
    val showReadout = mode != "bars"
    // A server that was tested and did not answer is a different fact from one that was
    // never tested, and until now both rendered as four grey bars and a dash, so a dead
    // server was indistinguishable from an untouched one. A negative latency is the
    // "probe ran, nothing answered" signal ([com.th3web.lean.data.net.Pinger] returns -1);
    // null still means "no measurement yet" and keeps the plain grey meter.
    val unreachable = latencyMs != null && latencyMs < 0
    // Kept at the weight of the unlit bars and only pulled toward the error hue, so the
    // row reads as "measured, no answer" rather than shouting like a failure banner.
    val unlit = if (unreachable) lerp(LeanColors.BarUnlit, LeanColors.Error, 0.45f) else LeanColors.BarUnlit
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (showBars) {
            // one Canvas draws all four bars (one draw node) instead of four
            // layout+draw Box nodes per row, and BarHeights is hoisted so no Dp
            // list is allocated per recomposition. Bars are bottom-aligned, 3dp
            // wide, 3dp-gapped, with BarFill caps; lit bars take [color],
            // unlit take BarUnlit.
            val litColor = color ?: unlit
            Canvas(Modifier.size(width = LatencyMeterWidth, height = MaxBarHeight)) {
                val barW = BarWidthDp.toPx()
                val gap = BarGapDp.toPx()
                val capPx = BarCapRadiusDp.dp.toPx()
                val capR = CornerRadius(capPx, capPx)
                val full = size.height
                BarHeightsDp.forEachIndexed { i, h ->
                    val barH = h.toPx()
                    // While probing the wave decides which bar is lit, so the meter reads
                    // as "working on it" rather than as a measurement that already exists.
                    val lit = if (sweep != null) i == sweep.toInt() else i < bars && color != null
                    drawRoundRect(
                        color = if (lit) litColor else unlit,
                        topLeft = Offset(i * (barW + gap), full - barH),
                        size = Size(barW, barH),
                        cornerRadius = capR,
                    )
                }
            }
            // The cross sits after the bars, in the same ink, so "no connection to this
            // server" is legible even where the bar tint alone would not be, a colour
            // shift on 3dp-wide bars is easy to miss, and impossible to see at all for a
            // red-blind reader. Drawn rather than typed so it lines up with the bar caps
            // and follows the meter's own geometry instead of a font's metrics.
            if (unreachable) {
                Spacer(Modifier.width(5.dp))
                Canvas(Modifier.size(CrossSizeDp)) {
                    val inset = CrossInsetDp.toPx()
                    val stroke = CrossStrokeDp.toPx()
                    val a = Offset(inset, inset)
                    val b = Offset(size.width - inset, size.height - inset)
                    drawLine(unlit, a, b, strokeWidth = stroke, cap = StrokeCap.Round)
                    drawLine(
                        unlit,
                        Offset(size.width - inset, inset),
                        Offset(inset, size.height - inset),
                        strokeWidth = stroke,
                        cap = StrokeCap.Round,
                    )
                }
            }
        }
        if (showBars && showReadout) Spacer(Modifier.width(8.dp))
        if (showReadout) {
            val label = if (sweep != null) "…" else if (latencyMs == null || latencyMs < 0) "—" else "$latencyMs ms"
            Text(
                label,
                color = LeanColors.TextSecondary,
                style = LeanType.msReadout,
            )
        }
    }
}

// LatencyMeter geometry, hoisted to file scope so nothing is allocated per row
// recomposition (was a fresh listOf(...) of Dp every pass). Plain Dp constants, not
// theme reads, which is what makes hoisting them safe here and unsafe for colours.
private val BarHeightsDp = listOf(5.dp, 8.dp, 11.dp, 15.dp)
private val MaxBarHeight = 15.dp
private val BarWidthDp = 3.dp
private val BarGapDp = 3.dp
// 4 bars × 3dp + 3 gaps × 3dp = 21dp total meter width.
private val LatencyMeterWidth = 21.dp
// The unreachable cross: sized to the tallest bar so it reads as part of the meter.
private val CrossSizeDp = 11.dp
private val CrossInsetDp = 1.dp
private val CrossStrokeDp = 1.6.dp
// BarFill corner is 6dp; the bars are only 3dp wide, so a 1.5dp radius keeps
// the caps proportional (a 6dp radius on a 3dp bar would over-round). px is
// resolved against the canvas density at draw time below.
private const val BarCapRadiusDp = 1.5f

/**
 * Latency → (ramp colour, lit bar count). Monotonic luminance ladder:
 *
 *  ≤[t1] → tier 1, 4 bars · ≤[t2] → tier 2, 3 bars · ≤[t3] → tier 3, 2 bars ·
 *  >[t3] → tier 4, 1 bar · null/timeout → [LeanColors.LatencyNone], 0 bars, readout "-".
 *
 * null = untested, <0 = unreachable. The thresholds are «Пороги пинга»: 120ms as
 * "excellent" is fantasy on a bad mobile network, and the tiers were the one part of the
 * ladder nobody could reach.
 *
 * @param palette a draft palette to read the tiers from instead of the published tokens,
 * how the Оформление preview renders a look that is not the active one without writing a
 * single global.
 * @param accent tier 1 of the DEFAULT ramp, taken from the caller because
 * `MaterialTheme.colorScheme` carries a new seed one frame before the `LeanColors` mirror
 * does (the mirror is published from a `SideEffect` that runs after composition). The
 * other three ramps have already decided what "fastest" looks like and ignore it.
 */
fun latencyTier(
    ms: Int?,
    accent: Color = LeanColors.Accent,
    palette: LeanPalette? = null,
    t1: Int = LeanOptions.latT1,
    t2: Int = LeanOptions.latT2,
    t3: Int = LeanOptions.latT3,
): Pair<Color?, Int> {
    val tier1 = when {
        palette != null -> palette.latencyTier1
        LeanOptions.latencyPalette == "accent" -> accent
        else -> LeanColors.LatencyTier1
    }
    return when {
        ms == null || ms < 0 -> (palette?.latencyNone ?: LeanColors.LatencyNone) to 0
        ms <= t1 -> tier1 to 4
        ms <= t2 -> (palette?.latencyTier2 ?: LeanColors.LatencyTier2) to 3
        ms <= t3 -> (palette?.latencyTier3 ?: LeanColors.LatencyTier3) to 2
        else -> (palette?.latencyTier4 ?: LeanColors.LatencyTier4) to 1
    }
}

/**
 * The kind of thing a server tag states. Tags are grouped by kind rather than emitted
 * ad hoc so «Плашки» can filter them by meaning, the user picks what a row should
 * state about a server, not which literal words survive.
 */
enum class TagKind { Protocol, Security, Transport }

/**
 * Decodes «Плашки» flag string (p/s/t) into the kinds a row may state. Kept next to
 * [TagKind] rather than in the settings layer so the letters and the enum cannot drift
 * apart unnoticed.
 */
fun keptTagKinds(flags: String): Set<TagKind> = buildSet {
    if ('p' in flags) add(TagKind.Protocol)
    if ('s' in flags) add(TagKind.Security)
    if ('t' in flags) add(TagKind.Transport)
}

/** A server tag: what it says, what kind of fact it is, and the ink it carries. */
data class ServerTag(val label: String, val kind: TagKind, val color: Color)

/**
 * Protocol / security / transport badges for a server.
 *
 * Every server answers the same three questions in the same order, what protocol, how it
 * is secured, and over what transport. One arbitrary follow-up tag per protocol (REALITY
 * for one VLESS, WS for another) makes rows incomparable, because the reader cannot tell
 * which fact is being stated.
 *
 * No emoji prefix: it duplicates what the protocol name says and reads as decoration at
 * tag size. The family hue stays ([LeanColors.Ember] for the QUIC family,
 * [LeanColors.Blue] for the TCP one), which is the part that carries information.
 *
 * Security and transport tags keep the quiet outline variant (`TextSecondary @ 0.6`).
 * The three inks baked in here are why every memoised caller must key on them rather
 * than on `LeanColors.light`.
 */
fun tagsFor(o: Outbound): List<ServerTag> {
    val isQuic = o is Outbound.Hysteria2 || o is Outbound.Hysteria || o is Outbound.Tuic
    val familyColor = if (isQuic) LeanColors.Ember else LeanColors.Blue
    val quiet = LeanColors.TextSecondary.copy(alpha = 0.6f)
    val tags = mutableListOf(ServerTag(o.protocol, TagKind.Protocol, familyColor))

    fun security(label: String) = tags.add(ServerTag(label, TagKind.Security, quiet))
    fun transport(label: String) = tags.add(ServerTag(label, TagKind.Transport, quiet))

    // The stream transport, for the protocols that have a choice of one. A null/blank
    // transport (or the explicit "tcp") is raw TCP, stated rather than left blank so a
    // plain node is distinguishable from one whose transport simply wasn't rendered.
    fun streamTransport(type: String?) = transport(
        when (type?.lowercase()) {
            null, "", "tcp" -> "TCP"
            "h2" -> "HTTP"
            "httpupgrade" -> "HTTPUPGRADE"
            else -> type.uppercase()
        },
    )

    when (o) {
        is Outbound.Vless -> {
            security(if (o.tls?.reality != null) "REALITY" else if (o.tls != null) "TLS" else "NONE")
            streamTransport(o.transport?.type)
        }
        is Outbound.Vmess -> {
            security(if (o.tls?.reality != null) "REALITY" else if (o.tls != null) "TLS" else "NONE")
            streamTransport(o.transport?.type)
        }
        is Outbound.Trojan -> {
            security("TLS")
            streamTransport(o.transport?.type)
        }
        is Outbound.Hysteria2 -> {
            security("TLS")
            // sing-box ships only the Salamander obfuscator for hy2; it wraps the QUIC
            // stream, so it belongs on the transport axis rather than the security one.
            transport(if (o.obfsType.isNotEmpty()) "QUIC · SALAMANDER" else "QUIC")
        }
        is Outbound.Hysteria -> {
            security("TLS")
            transport(if (o.obfs.isNotEmpty()) "QUIC · XPLUS" else "QUIC")
        }
        is Outbound.Tuic -> {
            security("TLS")
            transport("QUIC")
        }
        is Outbound.Shadowsocks -> {
            // The cipher is the security layer for Shadowsocks: there is no TLS.
            security(o.method.uppercase())
            transport(if (o.plugin.isNotEmpty()) "TCP · ${o.plugin.uppercase()}" else "TCP")
        }
        is Outbound.WireGuard -> {
            // WireGuard's own Noise handshake is not optional, so naming a cipher would
            // add nothing; AWG is the junk-packet obfuscation layer over the UDP flow.
            security("NOISE")
            transport(if (o.awg != null) "UDP · AWG" else "UDP")
        }
        is Outbound.Naive -> {
            // Naive's security is a genuine browser TLS stack (Chromium's), which is the
            // whole point, so the transport axis carries which HTTP version tunnels it.
            security("TLS")
            transport(if (o.proto.equals("quic", ignoreCase = true)) "HTTP/3 · QUIC" else "HTTP/2")
        }
        is Outbound.Mieru -> {
            // Mieru encrypts with its own AEAD construction and has no TLS layer at all;
            // naming it that way is more honest than borrowing "TLS".
            security("AEAD")
            transport(o.transport.uppercase())
        }
        is Outbound.Olcrtc -> {
            // The carrier is what matters here, not a cipher name: the traffic is a real
            // video call on a real service, which is the whole reason it gets through.
            security("XChaCha20")
            transport(o.provider.replaceFirstChar(Char::titlecase))
        }
    }
    return tags
}

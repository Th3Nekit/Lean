package com.th3web.lean.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

// ── The five hand-tuned ladders ──────────────────────────────────────────────
//
// Five tables, not A multiplier. The ladder carries a concentricity law (inner radius =
// outer radius − gap) that a uniform scale breaks at both ends: scaling 28→36 drags
// BarFill 6→7.7 and puts the traffic bar's caps past half its own 6dp height, while
// scaling down collapses Tag and BarFill onto the same value and loses the step
// altogether. Each table is authored so the gaps stay whole and both ends stay legible.

private class CornerLadder(
    val sheet: Dp,
    val card: Dp,
    val topBar: Dp,
    val row: Dp,
    val input: Dp,
    val button: Dp,
    val badge: Dp,
    val valuePill: Dp,
    val tag: Dp,
    val barFill: Dp,
)

/** Nearly square, the floor, where the concentric gaps close to 1-2dp. */
private val SharpLadder = CornerLadder(
    sheet = 12.dp,
    card = 10.dp,
    topBar = 10.dp,
    row = 8.dp,
    input = 8.dp,
    button = 6.dp,
    badge = 5.dp,
    valuePill = 4.dp,
    tag = 3.dp,
    barFill = 2.dp,
)

private val CrispLadder = CornerLadder(
    sheet = 18.dp,
    card = 14.dp,
    topBar = 13.dp,
    row = 11.dp,
    input = 10.dp,
    button = 9.dp,
    badge = 7.dp,
    valuePill = 6.dp,
    tag = 5.dp,
    barFill = 4.dp,
)

/** The shipping ladder, «Сталь·Ночь» reproduces today's look because of these numbers. */
private val NormalLadder = CornerLadder(
    sheet = 28.dp,
    card = 22.dp,
    topBar = 20.dp,
    row = 16.dp,
    input = 15.dp,
    button = 13.dp,
    badge = 11.dp,
    valuePill = 9.dp,
    tag = 7.dp,
    barFill = 6.dp,
)

private val SoftLadder = CornerLadder(
    sheet = 32.dp,
    card = 26.dp,
    topBar = 24.dp,
    row = 20.dp,
    input = 18.dp,
    button = 16.dp,
    badge = 13.dp,
    valuePill = 11.dp,
    tag = 9.dp,
    barFill = 7.dp,
)

/**
 * The ceiling. BarFill stops at 8dp instead of continuing the ramp: the traffic bar is
 * 6dp tall and the latency bars 3dp wide, so past this the caps are already stadiums and
 * the extra radius only eats the fill at low percentages.
 */
private val RoundLadder = CornerLadder(
    sheet = 36.dp,
    card = 30.dp,
    topBar = 28.dp,
    row = 24.dp,
    input = 22.dp,
    button = 20.dp,
    badge = 16.dp,
    valuePill = 13.dp,
    tag = 11.dp,
    barFill = 8.dp,
)

private fun ladderFor(style: String): CornerLadder = when (style) {
    "sharp" -> SharpLadder
    "crisp" -> CrispLadder
    "soft" -> SoftLadder
    "round" -> RoundLadder
    else -> NormalLadder
}

private fun topOnly(radius: Dp) = RoundedCornerShape(topStart = radius, topEnd = radius)

private fun bottomOnly(radius: Dp) = RoundedCornerShape(bottomStart = radius, bottomEnd = radius)

/**
 * The corner radii the whole interface is built from.
 *
 * Every corner radius in the app must resolve to a token from this ladder; raw
 * `RoundedCornerShape(n.dp)` literals do not belong anywhere else.
 *
 * Sheet, sheets / dialogs (SurfaceElevated base + glass overlay)
 * Card, cards, groups (depthShadow + glass)
 * TopBar, top bar slab, settings hub tiles
 * Row, server rows, dashed add-rows, mini favorite cards
 * Input, search / URL inputs
 * Button, buttons, compact inputs
 * Badge, LeanBadge icon containers
 * ValuePill, value pills (expiry date, NavItem trailing values)
 * Tag, TagBadge (ghost / outline)
 * BarFill, traffic-bar fill, latency-meter bar caps
 * Pill, stadium (100%): chips, segmented controls, filter pills
 *
 * Concentric nesting law: inner radius = outer radius − gap. A 38dp badge inside a
 * 16dp-corner row with 12dp padding lands on Badge 11,, never by eye.
 * When nesting, pick the ladder token closest to (outer − gap); do not invent
 * intermediate radii.
 *
 * Since «Оформление → Скругление» the tokens are snapshot state, repointed by
 * [apply]. They must therefore be read inside a composable: a file-level
 * `private val x = LeanCorner.Row` freezes at class load on whatever style happened to be
 * active first, it compiles clean, warns about nothing, and misbehaves only on a live
 * settings change. ServerRow shipped exactly that bug twice.
 */
object LeanCorner {
    var Sheet by mutableStateOf(RoundedCornerShape(NormalLadder.sheet))
    var Card by mutableStateOf(RoundedCornerShape(NormalLadder.card))
    var TopBar by mutableStateOf(RoundedCornerShape(NormalLadder.topBar))
    var Row by mutableStateOf(RoundedCornerShape(NormalLadder.row))
    var Input by mutableStateOf(RoundedCornerShape(NormalLadder.input))
    var Button by mutableStateOf(RoundedCornerShape(NormalLadder.button))
    var Badge by mutableStateOf(RoundedCornerShape(NormalLadder.badge))
    var ValuePill by mutableStateOf(RoundedCornerShape(NormalLadder.valuePill))
    var Tag by mutableStateOf(RoundedCornerShape(NormalLadder.tag))
    var BarFill by mutableStateOf(RoundedCornerShape(NormalLadder.barFill))

    /**
     * The [Card] radius as a raw [Dp].
     *
     * A grouped card that continues into rows below it is rounded at one end only, and a
     * half-rounded shape cannot be derived from a finished `RoundedCornerShape`, so the
     * number itself has to be a token too, or the four call-sites that need it go back to
     * hardcoding 22dp and become the only things in the app ignoring the knob.
     */
    var CardRadius by mutableStateOf(NormalLadder.card)

    /** Card rounded at the top only, a header with rows attached under it. */
    var CardTop by mutableStateOf(topOnly(NormalLadder.card))

    /** Card rounded at the bottom only, the strip that closes such a group. */
    var CardBottom by mutableStateOf(bottomOnly(NormalLadder.card))

    /** Stadium. Outside the ladder: 100% is a shape, not a radius. */
    val Pill = RoundedCornerShape(100)
}

/** «Скругление», repoint the whole ladder to one of the five tables above. */
internal fun LeanCorner.apply(style: String) {
    val ladder = ladderFor(style)
    Sheet = RoundedCornerShape(ladder.sheet)
    Card = RoundedCornerShape(ladder.card)
    TopBar = RoundedCornerShape(ladder.topBar)
    Row = RoundedCornerShape(ladder.row)
    Input = RoundedCornerShape(ladder.input)
    Button = RoundedCornerShape(ladder.button)
    Badge = RoundedCornerShape(ladder.badge)
    ValuePill = RoundedCornerShape(ladder.valuePill)
    Tag = RoundedCornerShape(ladder.tag)
    BarFill = RoundedCornerShape(ladder.barFill)
    CardRadius = ladder.card
    CardTop = topOnly(ladder.card)
    CardBottom = bottomOnly(ladder.card)
}

/**
 * Material shape slots for [style], mapped onto the same ladder so any Material component
 * picking up theme shapes stays on-scale.
 *
 * pure, and built from the table rather than from the [LeanCorner] tokens:
 * `LeanTheme` calls this inside `remember(spec)` during composition, while the tokens are
 * published from a `SideEffect` that runs after it. Reading the tokens here would hand
 * Material the previous style for one frame on every change, and would give the
 * Оформление preview the live look instead of the draft one it was asked to draw.
 */
internal fun leanShapes(style: String): Shapes {
    val ladder = ladderFor(style)
    return Shapes(
        extraSmall = RoundedCornerShape(ladder.valuePill),
        small = RoundedCornerShape(ladder.button),
        medium = RoundedCornerShape(ladder.row),
        large = RoundedCornerShape(ladder.card),
        extraLarge = RoundedCornerShape(ladder.sheet),
    )
}

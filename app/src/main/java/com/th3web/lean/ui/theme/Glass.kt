package com.th3web.lean.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * A hairline at the width «Контуры» is currently set to, or nothing at all when that
 * width is zero.
 *
 * The zero case has to skip the modifier rather than pass `0.dp` to it: `Modifier.border`
 * still installs a draw node at zero width and, depending on the shape's outline path,
 * can still emit a device-pixel stroke. The one place the app decides "is there a rim
 * here at all": every rimmed surface goes through it.
 */
fun Modifier.leanOutline(
    shape: Shape,
    color: Color,
    width: Dp = LeanMetrics.outlineWidth,
): Modifier = if (width <= 0.dp) this else border(width, color, shape)

/**
 * MD3 surface pane, the former "liquid glass" recipe, rebuilt on tonal
 * surfaces: clip to [shape], fill with `surfaceContainer`
 * ([LeanColors.Surface]), and rim with a [leanOutline] hairline (outlineVariant
 * by default). The hairline is what keeps panes legible on the AMOLED
 * true-black canvas. No backdrop blur, no gradients, no sheen.
 */
fun Modifier.glass(
    shape: Shape,
    border: Color = LeanColors.GlassBorder,
): Modifier = this
    .clip(shape)
    .background(LeanColors.Surface)
    .leanOutline(shape, border)

/**
 * The only selected-state recipe in the app, an MD3 selected container:
 * `surfaceContainerHigh` base ([LeanColors.SurfaceVariant]) under an [accent] wash at
 * «Сила выделения», rimmed by a quiet [accent] stroke at [LeanMetrics.accentBorderAlpha].
 * With the default accent (primary) this reads as the standard MD3 selection; legacy
 * alias tints passed by call-sites yield whisper-quiet per-tile washes until those
 * screens are reskinned.
 */
fun Modifier.glassAccent(
    shape: Shape,
    accent: Color = LeanColors.Accent,
): Modifier = this
    .clip(shape)
    .background(LeanColors.SurfaceVariant)
    .background(accent.copy(alpha = LeanMetrics.selectionWash))
    .leanOutline(shape, accent.copy(alpha = LeanMetrics.accentBorderAlpha))

/**
 * MD3 depth cue, a shadow at the elevation «Тень карточек» asks for (0 / 2 / 6dp),
 * clipped to nothing: near-invisible on the dark canvas, free to render; on AMOLED the
 * hairlines carry separation instead; on the light canvas it reads as the standard soft
 * MD3 elevation shadow, which is what light mode wants.
 * Chain before [glass]: `Modifier.depthShadow(shape).glass(shape)`.
 *
 * `ambientColor`/`spotColor` are left at their platform defaults.
 * `LeanColors.DepthShadowInk` carries a 0.25 alpha (it dates from the retired glow
 * design), and Android multiplies that straight into the shadow, passing it would
 * quarter the shipping shadow for a token nothing currently modulates.
 */
fun Modifier.depthShadow(shape: Shape): Modifier {
    val elevation = LeanMetrics.shadowElevation
    if (elevation <= 0.dp) return this
    // A shadow belongs to a surface. At zero glass density there is no surface, the panel
    // draws nothing and the background shows through, so the shadow was left tracing the
    // outline of something that is not there: a thick dark halo around the shape, most
    // visible around the subscription header, which is the one card that carries both a
    // shadow and the widest radius.
    if (glassPanelIsInvisible()) return this
    return shadow(elevation = elevation, shape = shape, clip = false)
}


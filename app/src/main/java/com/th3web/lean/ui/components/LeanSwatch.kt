package com.th3web.lean.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.th3web.lean.ui.icons.LeanIcon
import com.th3web.lean.ui.icons.LeanIconImage
import com.th3web.lean.ui.theme.LeanAccent
import com.th3web.lean.ui.theme.LeanColors
import com.th3web.lean.ui.tr

/**
 * One colour swatch, a filled circle inside a ring that thickens and takes the fill's own
 * colour when [selected], plus a check glyph on the selected one.
 *
 * Generalised out of the AppearanceHub's seven fixed accent tiles: «Оформление» also needs
 * to show a colour the user invented (which has no [LeanAccent] and no name), the six
 * recent picks inside the colour dialog, and the «＋» tile that opens it. Those differ only
 * in what fills the circle and how big it is, so they are one composable with parameters
 * rather than four near-identical private ones.
 *
 * [checkInk] defaults to whichever of near-black / near-white reads on [fill]: a
 * user-picked colour has no tuned `onPrimary` partner, and a check that vanishes on light
 * seeds is worse than none.
 */
@Composable
fun LeanSwatch(
    fill: Color,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 36.dp,
    checkInk: Color = inkOn(fill),
    label: String? = null,
) {
    Box(
        modifier
            .size(size)
            .clip(CircleShape)
            .border(
                width = if (selected) 2.dp else 1.dp,
                color = if (selected) fill else LeanColors.Outline,
                shape = CircleShape,
            )
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(size * RingInsetRatio)
            .then(label?.let { d -> Modifier.semantics { contentDescription = d } } ?: Modifier),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier.fillMaxSize().clip(CircleShape).background(fill),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) {
                LeanIconImage(LeanIcon.Check, tint = checkInk, modifier = Modifier.size(size * CheckRatio))
            }
        }
    }
}

/**
 * A seed accent as a swatch, previewing the tone the active column will really use, the
 * T80 pastel on the dark/AMOLED canvases, the hand-tuned tone-40 light roles on the light
 * one. Showing the stored seed instead would promise a colour the app never draws.
 */
@Composable
fun LeanAccentSwatch(
    accent: LeanAccent,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 36.dp,
    label: String? = null,
) {
    val light = LeanColors.light
    LeanSwatch(
        fill = if (light) accent.lightPrimary else accent.primary,
        selected = selected,
        onClick = onClick,
        modifier = modifier,
        size = size,
        checkInk = if (light) accent.lightOnPrimary else accent.onPrimary,
        // A synthesised accent's nameRu is its "#RRGGBB", which tr() passes straight through.
        label = label ?: tr(accent.nameRu),
    )
}

/**
 * The «＋» tile that opens the colour dialog, the same circle geometry as a real swatch so
 * the row keeps one rhythm, but hollow (a dashed-add affordance in circle form) rather than
 * filled with a colour it does not have.
 */
@Composable
fun LeanAddSwatch(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 36.dp,
    label: String? = null,
) {
    Box(
        modifier
            .size(size)
            .clip(CircleShape)
            .border(1.dp, LeanColors.Outline, CircleShape)
            .clickable(role = Role.Button, onClick = onClick)
            .then(label?.let { d -> Modifier.semantics { contentDescription = d } } ?: Modifier),
        contentAlignment = Alignment.Center,
    ) {
        LeanIconImage(LeanIcon.Plus, tint = LeanColors.TextSecondary, modifier = Modifier.size(size * PlusRatio))
    }
}

/** Readable ink for an arbitrary user-picked fill, near-black on bright, near-white on dark. */
private fun inkOn(fill: Color): Color =
    if (fill.luminance() > 0.42f) Color.Black.copy(alpha = 0.72f) else Color.White.copy(alpha = 0.88f)

// Ratios, not literals, so a 24dp swatch in the dialog and a 36dp one in the hub stay the
// same drawing at two scales.
private const val RingInsetRatio = 5f / 36f
private const val CheckRatio = 14f / 36f
private const val PlusRatio = 16f / 36f

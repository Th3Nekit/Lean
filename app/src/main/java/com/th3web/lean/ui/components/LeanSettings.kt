package com.th3web.lean.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import com.th3web.lean.ui.icons.LeanIcon
import com.th3web.lean.ui.icons.LeanIconImage
import com.th3web.lean.ui.theme.LeanColors
import com.th3web.lean.ui.theme.LeanCorner
import com.th3web.lean.ui.theme.LeanMetrics
import com.th3web.lean.ui.theme.LeanOptions
import com.th3web.lean.ui.theme.LeanType
import com.th3web.lean.ui.theme.leanGlass

/**
 * Settings building blocks: LeanGroup / LeanNavItem /
 * LeanToggleItem / LeanSectionLabel / LeanDivider plus the [LeanBadge] icon
 * tile. Idiomatic MD3: groups are `surfaceContainer` cards, rows are stock
 * [ListItem]s with the default ripple, toggles are stock scheme [Switch]es
 * (checked track = primary), and hue lives only in the quietly tinted badge
 * glyphs on neutral tiles.
 *
 * Every «Оформление» knob these blocks answer to is read from [LeanMetrics] /
 * [LeanOptions] inside the composable, never hoisted to a file-level `val`, which would
 * freeze it at class load on whatever look was active first.
 */

/**
 * Rounded-square icon tile: neutral [LeanColors.SurfaceElevated] fill,
 * [LeanCorner.Badge] (11dp), no border; the glyph carries the (whisper-quiet)
 * tint passed by the caller, the signature restrained look.
 *
 * The default size is «Плотность» (34 / 38 / 44dp), and is the loudest thing that knob
 * does, every settings row, every hub tile and every subscription header leads with one.
 * Call-sites that pass an explicit size (the 42dp hub tiles, the 36dp Auto hero) are
 * making a composition decision, not a density one, and keep it.
 */
@Composable
fun LeanBadge(icon: LeanIcon, tint: Color, size: Dp = LeanMetrics.badgeSize) {
    Box(
        modifier = Modifier
            .size(size)
            .clip(LeanCorner.Badge)
            .background(LeanColors.SurfaceElevated),
        contentAlignment = Alignment.Center,
    ) {
        LeanIconImage(icon, tint = tint, modifier = Modifier.size(size * 0.52f))
    }
}

/**
 * An MD3 card grouping settings rows, `shapes.large` on `surfaceContainer` with an
 * `outlineVariant` hairline at the «Контуры» width, no shadow.
 */
@Composable
fun LeanGroup(content: @Composable ColumnScope.() -> Unit) {
    val outline = LeanMetrics.outlineWidth
    val shape = MaterialTheme.shapes.large
    Card(
        // «Стекло» paints the card itself, so the Card's own container must go
        // transparent, otherwise the opaque fill sits on top of the backdrop the glass
        // just drew and nothing shows through. [leanGlass] falls back to exactly this
        // fill when glass is off or there is no picture to see through.
        modifier = Modifier
            .fillMaxWidth()
            .leanGlass(shape, MaterialTheme.colorScheme.surfaceContainer),
        shape = shape,
        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
        // null, not a zero-width stroke: Card hands the border straight to Modifier.border,
        // which still installs a draw node at 0dp.
        border = if (outline > 0.dp) {
            BorderStroke(outline, MaterialTheme.colorScheme.outlineVariant)
        } else {
            null
        },
        content = content,
    )
}

/**
 * «Подписи разделов заглавными», decided in one place, three composables render a
 * section label (this file, the Servers screen, the traffic strip), and a knob that
 * reached two of them would be worse than no knob.
 */
internal fun leanSectionText(text: String): String =
    if (LeanOptions.sectionCaps) text.uppercase() else text

/**
 * The tracking travels with the casing. 0.13em is part of the caps treatment, carried
 * over onto a lower-case label it reads as artificially spaced-out text rather than as a
 * label. `Unspecified` resolves to zero at layout, which is also how the style avoids
 * naming an `sp` literal outside Type.kt.
 */
internal fun leanSectionStyle(): TextStyle = if (LeanOptions.sectionCaps) {
    LeanType.sectionLabel
} else {
    LeanType.sectionLabel.copy(letterSpacing = TextUnit.Unspecified)
}

/**
 * section label, [LeanType.sectionLabel] in [LeanColors.Accent] (primary): the
 * quiet accent structure moment. Sits on the raw background, outside cards; 24dp section
 * break above, 8dp to the first card below.
 */
@Composable
fun LeanSectionLabel(text: String) {
    Text(
        leanSectionText(text),
        color = LeanColors.Accent,
        style = leanSectionStyle(),
        modifier = Modifier.padding(start = LeanMetrics.sectionPad, top = 24.dp, bottom = 8.dp),
    )
}

/**
 * Trailing value pill: [LeanCorner.ValuePill] (9dp), neutral
 * [LeanColors.SurfaceElevated] fill, no border, [LeanType.valuePill] (tnum)
 * in [LeanColors.TextSecondary].
 */
@Composable
private fun ValuePill(value: String) {
    Box(
        Modifier
            .clip(LeanCorner.ValuePill)
            .background(LeanColors.SurfaceElevated)
            .padding(horizontal = 10.dp, vertical = 4.dp),
    ) {
        Text(value, color = LeanColors.TextSecondary, style = LeanType.valuePill)
    }
}

/** What [LeanMetrics.rowPadV] holds at «обычная», the zero point of the delta below. */
private val StockRowPadV = 12.dp

/**
 * The row padding «Плотность» is currently asking for, expressed as what can be added to
 * a stock [ListItem].
 *
 * [ListItem] enforces its own 56/72/88dp minimum height and exposes no way to lower it,
 * so the compact step cannot come from here: it is carried by the smaller [LeanBadge],
 * the tighter section padding and the shorter divider inset instead. Growing works
 * fine, which is the direction `comfortable` needs.
 */
private fun rowExtraPadding(): Dp = (LeanMetrics.rowPadV - StockRowPadV).coerceAtLeast(0.dp)

/**
 * Navigation row: a transparent [ListItem] on a default-ripple
 * clickable, leading [LeanBadge], headline [LeanType.rowTitle], supporting
 * [LeanType.meta], trailing [ValuePill] + 16dp chevron in
 * [LeanColors.TextTertiary].
 */
@Composable
fun LeanNavItem(
    icon: LeanIcon,
    tint: Color,
    title: String,
    subtitle: String? = null,
    value: String? = null,
    onClick: () -> Unit,
) {
    ListItem(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            // After clickable, so the ripple still covers the whole row.
            .padding(vertical = rowExtraPadding()),
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        leadingContent = { LeanBadge(icon, tint) },
        headlineContent = { Text(title, color = LeanColors.TextPrimary, style = LeanType.rowTitle) },
        supportingContent = subtitle?.let {
            { Text(it, color = LeanColors.TextSecondary, style = LeanType.meta) }
        },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (value != null) {
                    ValuePill(value)
                    Spacer(Modifier.width(8.dp))
                }
                LeanIconImage(LeanIcon.Chev, tint = LeanColors.TextTertiary, modifier = Modifier.size(16.dp))
            }
        },
    )
}

/**
 * Toggle row: same transparent [ListItem] with a stock scheme [Switch]
 * (no color overrides, checked track = primary). The whole row is toggleable
 * with [Role.Switch] semantics, and flipping it is one of the two places in the app that
 * speak to the vibrator (see [leanHaptic]).
 */
@Composable
fun LeanToggleItem(
    icon: LeanIcon,
    tint: Color,
    title: String,
    subtitle: String? = null,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    val strength = LeanOptions.haptics
    ListItem(
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(
                value = checked,
                role = Role.Switch,
                onValueChange = {
                    haptics.leanHaptic(strength)
                    onCheckedChange(it)
                },
            )
            .padding(vertical = rowExtraPadding()),
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        leadingContent = { LeanBadge(icon, tint) },
        headlineContent = { Text(title, color = LeanColors.TextPrimary, style = LeanType.rowTitle) },
        supportingContent = subtitle?.let {
            { Text(it, color = LeanColors.TextSecondary, style = LeanType.meta) }
        },
        trailingContent = { Switch(checked = checked, onCheckedChange = null) },
    )
}

/**
 * Single-choice row, the vertical alternative to [SegmentedControl] for a set of
 * options that a horizontal strip cannot serve: more than 3-4 of them, labels too long to
 * survive one line, or options that each need a sentence of their own to be choosable at
 * all. Same transparent [ListItem] as [LeanNavItem]/[LeanToggleItem] with a stock scheme
 * [RadioButton] trailing it, so a card of these reads as one more settings group rather
 * than as a new kind of control.
 *
 * Being a plain composition of the same primitives is the whole of it: it inherits every
 * «Оформление» knob for free, [LeanBadge] follows «Плотность», [LeanType.rowTitle]/
 * [LeanType.meta] follow «Шрифты»/«Размер текста»/«Жирность», the [RadioButton] and the
 * badge tint follow the accent, and the surrounding [LeanGroup] carries «Скругления» and
 * «Контуры». Nothing here hardcodes a size, colour or shape that a knob owns.
 *
 * The whole row is the target with [Role.RadioButton] semantics, and it taps the vibrator
 * exactly like [LeanToggleItem] does: a selection is a state change too.
 */
@Composable
fun LeanChoiceItem(
    icon: LeanIcon,
    tint: Color,
    title: String,
    subtitle: String? = null,
    selected: Boolean,
    onSelect: () -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    val strength = LeanOptions.haptics
    ListItem(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(
                selected = selected,
                role = Role.RadioButton,
                onClick = {
                    // Re-picking the current option is not a state change, so it must not
                    // buzz: the tick is feedback for something having changed.
                    if (!selected) {
                        haptics.leanHaptic(strength)
                        onSelect()
                    }
                },
            )
            .padding(vertical = rowExtraPadding()),
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        leadingContent = { LeanBadge(icon, tint) },
        headlineContent = { Text(title, color = LeanColors.TextPrimary, style = LeanType.rowTitle) },
        supportingContent = subtitle?.let {
            { Text(it, color = LeanColors.TextSecondary, style = LeanType.meta) }
        },
        // onClick = null: the row above already owns the click and the semantics, so the
        // control must not announce itself as a second, separately-focusable target.
        trailingContent = { RadioButton(selected = selected, onClick = null) },
    )
}

/**
 * «Вибро-отклик», the app's only mapping from the setting to a platform effect.
 *
 * `LongPress` is the firm confirmation tick a state change deserves; `TextHandleMove` is
 * the platform's lightest defined effect and the only honest "light" available without
 * dropping to `Vibrator` and an API-level ladder.
 */
internal fun HapticFeedback.leanHaptic(strength: String) {
    when (strength) {
        "none" -> Unit
        "light" -> performHapticFeedback(HapticFeedbackType.TextHandleMove)
        else -> performHapticFeedback(HapticFeedbackType.LongPress)
    }
}

/**
 * Hairline rule at the content indent, dividers start at the text column, not the badge
 * column, and the indent is derived from the badge size so it keeps doing that at every
 * density (a fixed 62dp drifts off the text the moment the badge is 44dp).
 *
 * With «Разделители» off it becomes a [Spacer] of the same 1dp: hiding the rules must not
 * re-flow every card they used to separate.
 */
@Composable
fun LeanDivider() {
    if (!LeanOptions.showDividers) {
        Spacer(Modifier.height(1.dp))
        return
    }
    HorizontalDivider(
        color = LeanColors.Hairline,
        thickness = 1.dp,
        modifier = Modifier.padding(start = LeanMetrics.dividerIndent),
    )
}

package com.th3web.lean.ui.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import com.th3web.lean.ui.theme.LeanOptions
import com.th3web.lean.ui.theme.LeanType

/**
 * Canonical MD3 segmented control: a stock
 * [SingleChoiceSegmentedButtonRow] of [SegmentedButton]s. Colors come straight
 * from the scheme defaults, active = `secondaryContainer` /
 * `onSecondaryContainer`, inactive transparent / `onSurface`, border
 * `outline`.
 *
 * «Оформление» makes four- and five-segment rows the norm (Тема ×4, Контрастность ×5,
 * Кнопка подключения ×4), and at «Размер текста» 120 they overflow. Two defences, in
 * order of how much room they win:
 *
 * 1. **Drop the MD3 check icon past three segments.** It costs ~24dp of every segment,
 *    selected or not (the slot is reserved), and on a row this narrow the label already
 *    says which one is chosen, the container fill says it again.
 * 2. **Step the label down a notch** when the row is crowded or the text scale is up. A
 *    clipped label is a broken control; a slightly smaller one is not.
 */
@Composable
fun SegmentedControl(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val crowded = options.size > 3
    val style = if (crowded || LeanOptions.textScale >= 110) tightChipStyle() else LeanType.chip
    SingleChoiceSegmentedButtonRow(modifier = modifier.fillMaxWidth()) {
        options.forEachIndexed { i, label ->
            SegmentedButton(
                selected = i == selectedIndex,
                onClick = { onSelect(i) },
                shape = SegmentedButtonDefaults.itemShape(index = i, count = options.size),
                // An empty slot rather than a different composable: the parameter has no
                // "none" value, and an empty lambda is what collapses the reserved width.
                icon = { if (!crowded) SegmentedButtonDefaults.Icon(i == selectedIndex) },
            ) {
                Text(label, style = style, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/**
 * [LeanType.chip] one notch down, derived rather than declared: the role is rebuilt by
 * «Шрифты», so a second hardcoded size here would stop following it the first time the
 * user changed families or weight.
 */
private fun tightChipStyle(): TextStyle = LeanType.chip.copy(
    fontSize = LeanType.chip.fontSize * TightChipScale,
    lineHeight = LeanType.chip.lineHeight * TightChipScale,
)

private const val TightChipScale = 0.88f

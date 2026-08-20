package com.th3web.lean.ui.screen.appearance

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.th3web.lean.LeanApp
import com.th3web.lean.data.AppearanceRanges
import com.th3web.lean.ui.components.LeanDivider
import com.th3web.lean.ui.components.LeanGroup
import com.th3web.lean.ui.components.LeanNavItem
import com.th3web.lean.ui.components.LeanToggleItem
import com.th3web.lean.ui.icons.LeanIcon
import com.th3web.lean.ui.screen.AppearanceHeader
import com.th3web.lean.ui.screen.HubScaffold
import com.th3web.lean.ui.screen.KnobHint
import com.th3web.lean.ui.screen.KnobSegments
import com.th3web.lean.ui.screen.rememberAppearanceEditor
import com.th3web.lean.ui.screen.rememberLook
import com.th3web.lean.ui.theme.LeanColors
import com.th3web.lean.ui.theme.LeanFonts
import com.th3web.lean.ui.theme.LeanType
import com.th3web.lean.ui.tr

/**
 * «Шрифты», two families, a size ladder, a weight delta and two typographic switches.
 *
 * Size is five discrete steps, not a slider: a new size is a new `TextStyle` identity, which
 * throws away every cached text layout in the app. Five taps are fine; ninety frames of drag
 * are not.
 */
@Composable
fun AppearanceFontsScreen(onBack: () -> Unit) {
    val repo = LeanApp.instance.settings
    val settings by repo.state.collectAsStateWithLifecycle()
    val editor = rememberAppearanceEditor(settings)
    val look = rememberLook(settings)
    var picking by remember { mutableStateOf<String?>(null) }

    HubScaffold(
        tr("Шрифты"),
        onBack,
        header = { AppearanceHeader(look, settings.appearancePreview, initialScene = PreviewScene.SETTINGS) },
    ) {
        Spacer(Modifier.height(8.dp))
        LeanGroup {
            LeanNavItem(
                LeanIcon.Lang, LeanColors.Accent, tr("Шрифт заголовков"),
                value = tr(familyLabel(settings.fontDisplay)),
            ) { picking = DISPLAY }
            LeanDivider()
            LeanNavItem(
                LeanIcon.Lang, LeanColors.Blue, tr("Шрифт текста"),
                value = tr(familyLabel(settings.fontBody)),
            ) { picking = BODY }
        }
        KnobHint(tr("«Системный» и «Моноширинный» берутся у устройства — ноль килобайт в приложении и гарантированная кириллица."))

        KnobSegments(
            tr("Размер текста"),
            AppearanceRanges.TEXT_SCALE_STEPS.map { "$it%" },
            AppearanceRanges.TEXT_SCALE_STEPS.indexOf(settings.textScale).coerceAtLeast(0),
        ) { i -> editor.edit { setTextScale(AppearanceRanges.TEXT_SCALE_STEPS[i]) } }
        KnobHint(tr("Масштабируется только текст — кольцо подключения и отступы остаются на месте."))

        KnobSegments(
            tr("Жирность"),
            listOf(tr("Тоньше"), tr("Норма"), tr("Жирнее")),
            AppearanceRanges.FONT_WEIGHT_STEPS.indexOf(settings.fontWeightDelta).coerceAtLeast(0),
        ) { i -> editor.edit { setFontWeightDelta(AppearanceRanges.FONT_WEIGHT_STEPS[i]) } }
        KnobHint(tr("В сборке лежат не все начертания: Unbounded — только полужирные, Onest — от обычного до жирного. На краях шкалы шрифт останется прежним."))

        Spacer(Modifier.height(14.dp))
        LeanGroup {
            LeanToggleItem(
                LeanIcon.Speed, LeanColors.Blue, tr("Табличные цифры"),
                tr("Цифры одной ширины — пинг и трафик не дёргаются"), settings.tabularNums,
            ) { on -> editor.edit { setTabularNums(on) } }
            LeanDivider()
            LeanToggleItem(
                LeanIcon.Layers, LeanColors.Violet, tr("Подписи разделов заглавными"),
                tr("КАК ЭТА или как эта"), settings.sectionCaps,
            ) { on -> editor.edit { setSectionCaps(on) } }
        }
    }

    picking?.let { role ->
        val current = if (role == DISPLAY) settings.fontDisplay else settings.fontBody
        AlertDialog(
            onDismissRequest = { picking = null },
            title = { Text(if (role == DISPLAY) tr("Шрифт заголовков") else tr("Шрифт текста")) },
            text = {
                Column {
                    FONT_FAMILIES.forEach { (key, nameRu) ->
                        FontRow(key, tr(nameRu), current == key) {
                            editor.edit {
                                if (role == DISPLAY) setFontDisplay(key) else setFontBody(key)
                            }
                            picking = null
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { picking = null }) { Text(tr("Отмена"), color = LeanColors.TextSecondary) }
            },
        )
    }
}

/**
 * A family option rendered in that family. Copying the row role rather than declaring a
 * style keeps the sample following «Размер текста» and «Жирность» too, so the dialog shows
 * the actual combination the user would get.
 */
@Composable
private fun FontRow(key: String, label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            color = LeanColors.TextPrimary,
            style = LeanType.rowTitle.copy(fontFamily = LeanFonts.familyFor(key)),
            modifier = Modifier.weight(1f),
        )
        RadioButton(selected = selected, onClick = null)
    }
}

private const val DISPLAY = "display"
private const val BODY = "body"

private val FONT_FAMILIES: List<Pair<String, String>> = listOf(
    "unbounded" to "Unbounded",
    "onest" to "Onest",
    "system" to "Системный",
    "mono" to "Моноширинный",
)

private fun familyLabel(key: String): String =
    FONT_FAMILIES.firstOrNull { it.first == key }?.second ?: key

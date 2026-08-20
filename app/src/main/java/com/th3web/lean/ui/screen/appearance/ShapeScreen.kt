package com.th3web.lean.ui.screen.appearance

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.th3web.lean.LeanApp
import com.th3web.lean.ui.components.LeanGroup
import com.th3web.lean.ui.components.LeanToggleItem
import com.th3web.lean.ui.icons.LeanIcon
import com.th3web.lean.ui.screen.AppearanceHeader
import com.th3web.lean.ui.screen.HubScaffold
import com.th3web.lean.ui.screen.KnobHint
import com.th3web.lean.ui.screen.KnobSegments
import com.th3web.lean.ui.screen.rememberAppearanceEditor
import com.th3web.lean.ui.screen.rememberLook
import com.th3web.lean.ui.theme.LeanColors
import com.th3web.lean.ui.tr

/**
 * «Форма и плотность», the corner ladder, the row rhythm, and how visible the structure is.
 *
 * Every knob here is a closed set of hand-authored values, not a multiplier. The corner
 * ladder in particular is five separate tables: it obeys a concentricity law (an inner
 * radius is its outer minus the gap between them) that a uniform scale breaks at both ends.
 */
@Composable
fun AppearanceShapeScreen(onBack: () -> Unit) {
    val repo = LeanApp.instance.settings
    val settings by repo.state.collectAsStateWithLifecycle()
    val editor = rememberAppearanceEditor(settings)
    val look = rememberLook(settings)

    HubScaffold(
        tr("Форма и плотность"),
        onBack,
        header = { AppearanceHeader(look, settings.appearancePreview, initialScene = PreviewScene.SETTINGS) },
    ) {
        KnobSegments(
            tr("Скругление"),
            listOf(tr("Острое"), tr("Чёткое"), tr("Обычное"), tr("Мягкое"), tr("Круглое")),
            CORNER_STYLES.indexOf(settings.cornerStyle).coerceAtLeast(0),
        ) { i -> editor.edit { setCornerStyle(CORNER_STYLES[i]) } }

        KnobSegments(
            tr("Плотность"),
            listOf(tr("Плотно"), tr("Обычно"), tr("Просторно")),
            DENSITIES.indexOf(settings.uiDensity).coerceAtLeast(0),
        ) { i -> editor.edit { setUiDensity(DENSITIES[i]) } }
        KnobHint(tr("Меняет значки, отступы разделов и разделителей. Высота строк настроек ограничена снизу системным минимумом, поэтому «Плотно» сжимает всё остальное."))

        KnobSegments(
            tr("Контуры"),
            listOf(tr("Нет"), tr("Тонкие"), tr("Заметные")),
            OUTLINES.indexOf(settings.outlineWeight).coerceAtLeast(0),
        ) { i -> editor.edit { setOutlineWeight(OUTLINES[i]) } }

        KnobSegments(
            tr("Тень карточек"),
            listOf(tr("Нет"), tr("Мягкая"), tr("Глубокая")),
            SHADOWS.indexOf(settings.cardShadow).coerceAtLeast(0),
        ) { i -> editor.edit { setCardShadow(SHADOWS[i]) } }

        Spacer(Modifier.height(14.dp))
        LeanGroup {
            LeanToggleItem(
                LeanIcon.Layers, LeanColors.Accent, tr("Разделители"),
                tr("Волосяные линии между строками"), settings.showDividers,
            ) { on -> editor.edit { setShowDividers(on) } }
        }
        if (settings.showDividers) {
            KnobSegments(
                tr("Отступ разделителей"),
                listOf(tr("От текста"), tr("Во всю ширину")),
                if (settings.dividerIndent == "full") 1 else 0,
            ) { i -> editor.edit { setDividerIndent(if (i == 1) "full" else "inset") } }
        }
    }
}

private val CORNER_STYLES = listOf("sharp", "crisp", "normal", "soft", "round")
private val DENSITIES = listOf("compact", "normal", "comfortable")
private val OUTLINES = listOf("none", "thin", "strong")
private val SHADOWS = listOf("none", "soft", "deep")

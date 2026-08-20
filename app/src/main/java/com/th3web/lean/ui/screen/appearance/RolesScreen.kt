package com.th3web.lean.ui.screen.appearance

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.th3web.lean.LeanApp
import com.th3web.lean.data.AppearanceRoles
import com.th3web.lean.ui.components.ColorPickerDialog
import com.th3web.lean.ui.components.LeanGroup
import com.th3web.lean.ui.components.LeanNavItem
import com.th3web.lean.ui.components.LeanSwatch
import com.th3web.lean.ui.icons.LeanIcon
import com.th3web.lean.ui.screen.AppearanceHeader
import com.th3web.lean.ui.screen.HubScaffold
import com.th3web.lean.ui.screen.KnobHint
import com.th3web.lean.ui.screen.rememberAppearanceEditor
import com.th3web.lean.ui.screen.rememberLook
import com.th3web.lean.ui.theme.LeanColors
import com.th3web.lean.ui.theme.LeanPalette
import com.th3web.lean.ui.tr

/**
 * «Цвета по ролям», ten semantic slots, not twenty-seven tokens.
 *
 * Twenty-seven tokens times four themes is a machine for producing unreadable screenshots
 * in a support thread. Ten slots each cover the group of tokens that always moved together
 * anyway, every row shows the colour the app is really drawing (an override or the resolved
 * default), and a slot whose contrast against its partner falls below 3:1 says so, a
 * warning, not a refusal, because a deliberate low-contrast look is still the user's call.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AppearanceRolesScreen(onBack: () -> Unit) {
    val repo = LeanApp.instance.settings
    val settings by repo.state.collectAsStateWithLifecycle()
    val editor = rememberAppearanceEditor(settings)
    val look = rememberLook(settings)
    var editingRole by remember { mutableStateOf<String?>(null) }

    HubScaffold(
        tr("Цвета по ролям"),
        onBack,
        header = { AppearanceHeader(look, settings.appearancePreview, initialScene = PreviewScene.SETTINGS) },
    ) {
        KnobHint(tr("Тап — выбрать цвет, долгий тап — вернуть его к теме."))
        Spacer(Modifier.height(12.dp))
        ROLES.forEach { (key, nameRu) ->
            val overridden = settings.roleOverrides[key]
            val ink = overridden?.let { Color(it.toInt()) } ?: roleInk(look.palette, key)
            val partner = contrastPartner(look.palette, key)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .combinedClickable(
                        onClick = { editingRole = key },
                        onLongClick = {
                            editor.edit { setRoleOverrides(settings.roleOverrides - key) }
                        },
                    )
                    .padding(horizontal = 4.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                LeanSwatch(
                    fill = ink,
                    selected = overridden != null,
                    onClick = { editingRole = key },
                    size = 32.dp,
                    label = tr(nameRu),
                )
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(tr(nameRu), color = LeanColors.TextPrimary, style = MaterialTheme.typography.bodyLarge)
                    Text(
                        hexOf(ink),
                        color = LeanColors.TextTertiary,
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
                if (partner != null && contrastRatio(ink, partner) < MinContrast) {
                    Text(
                        tr("Плохо читается"),
                        color = LeanColors.Ember,
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }
        }

        Spacer(Modifier.height(16.dp))
        LeanGroup {
            LeanNavItem(
                LeanIcon.Refresh, LeanColors.EmberRed, tr("Сбросить все роли"),
                subtitle = tr("Вернуть все десять цветов к теме"),
            ) { editor.edit { setRoleOverrides(emptyMap()) } }
        }
    }

    editingRole?.let { key ->
        val current = settings.roleOverrides[key] ?: (roleInk(look.palette, key).toArgb().toLong() and 0xFFFFFFFFL)
        ColorPickerDialog(
            initial = current,
            title = tr(ROLES.first { it.first == key }.second),
            recent = settings.accentRecent,
            chromaClamp = settings.accentChroma,
            onDismiss = { editingRole = null },
            onConfirm = { argb ->
                editor.edit { setRoleOverrides(settings.roleOverrides + (key to argb)) }
                editingRole = null
            },
        )
    }
}

/** The ten slots, in the order they build a screen up from its canvas. */
private val ROLES: List<Pair<String, String>> = listOf(
    AppearanceRoles.BACKGROUND to "Фон",
    AppearanceRoles.SURFACE to "Карточки",
    AppearanceRoles.PANEL to "Панели",
    AppearanceRoles.OUTLINE to "Контуры",
    AppearanceRoles.ACCENT to "Акцент",
    AppearanceRoles.TEXT_PRIMARY to "Основной текст",
    AppearanceRoles.TEXT_SECONDARY to "Второстепенный текст",
    AppearanceRoles.CONNECTED to "Подключено",
    AppearanceRoles.ERROR to "Ошибка",
    AppearanceRoles.TAG to "Теги",
)

/**
 * The one token of each slot that a swatch can show.
 *
 * A slot covers a group, «Панели» is `SurfaceVariant` + `SurfaceElevated` + two MD3
 * container roles, and the override is applied to the whole group inside the palette
 * resolver. This picks the member that best represents the group visually; it is a display
 * decision, not a second definition of what a slot means.
 */
private fun roleInk(palette: LeanPalette, key: String): Color = when (key) {
    AppearanceRoles.BACKGROUND -> palette.background
    AppearanceRoles.SURFACE -> palette.surface
    AppearanceRoles.PANEL -> palette.surfaceVariant
    AppearanceRoles.OUTLINE -> palette.outline
    AppearanceRoles.ACCENT -> palette.accent
    AppearanceRoles.TEXT_PRIMARY -> palette.textPrimary
    AppearanceRoles.TEXT_SECONDARY -> palette.textSecondary
    AppearanceRoles.CONNECTED -> palette.connected
    AppearanceRoles.ERROR -> palette.error
    else -> palette.tagInk
}

/**
 * What each slot has to stay legible against, or null when nothing meaningful reads on it.
 *
 * A canvas is judged against the text that sits on it; an ink against the canvas. «Контуры»
 * is excluded: a hairline is a hair away from its background, the
 * shipping default already sits well under 3:1, so checking it would put a permanent
 * warning next to a colour that is correct.
 */
private fun contrastPartner(palette: LeanPalette, key: String): Color? = when (key) {
    AppearanceRoles.BACKGROUND, AppearanceRoles.SURFACE, AppearanceRoles.PANEL -> palette.textPrimary
    AppearanceRoles.OUTLINE -> null
    else -> palette.background
}

/** WCAG relative-luminance contrast, the same formula the accessibility guidance uses. */
private fun contrastRatio(a: Color, b: Color): Float {
    val la = a.luminance() + 0.05f
    val lb = b.luminance() + 0.05f
    return if (la > lb) la / lb else lb / la
}

private fun hexOf(color: Color): String = "#%06X".format(color.toArgb().toLong() and 0xFFFFFFL)

/** Below this a pairing is called out. 3:1 is the WCAG floor for large text and UI parts. */
private const val MinContrast = 3f

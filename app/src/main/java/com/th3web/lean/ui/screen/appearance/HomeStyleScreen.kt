package com.th3web.lean.ui.screen.appearance

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import com.th3web.lean.BuildConfig
import com.th3web.lean.data.AppearanceRanges
import com.th3web.lean.ui.components.LeanDivider
import com.th3web.lean.ui.components.LeanGroup
import com.th3web.lean.ui.components.LeanNavItem
import com.th3web.lean.ui.components.LeanSectionLabel
import com.th3web.lean.ui.components.LeanSlider
import com.th3web.lean.ui.components.LeanToggleItem
import com.th3web.lean.ui.icons.LeanIcon
import com.th3web.lean.ui.screen.AppearanceHeader
import com.th3web.lean.ui.screen.HubScaffold
import com.th3web.lean.ui.screen.KnobHint
import com.th3web.lean.ui.screen.KnobSegments
import com.th3web.lean.ui.screen.RadioRow
import com.th3web.lean.ui.screen.rememberAppearanceEditor
import com.th3web.lean.ui.screen.rememberLook
import com.th3web.lean.ui.theme.HomeBlock
import com.th3web.lean.ui.theme.LeanColors
import com.th3web.lean.ui.tr

/**
 * «Главный экран», the connect hero and what surrounds it.
 *
 * The showcase is pinned on the home scene here, and tapping its hero cycles
 * disconnected → connecting → connected → error: the four hero styles differ mostly in what
 * they do while a tunnel is coming up, which is the one moment nobody wants to reproduce by
 * actually connecting four times.
 */
@Composable
fun AppearanceHomeStyleScreen(onBack: () -> Unit) {
    val repo = LeanApp.instance.settings
    val settings by repo.state.collectAsStateWithLifecycle()
    val editor = rememberAppearanceEditor(settings)
    // The rail's draft clears when its own committed value lands, not on release, releasing
    // first would show the old number for the frames the DataStore write takes.
    var peekDraft by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(settings.quickPeek) { peekDraft = null }
    val shown = remember(settings, peekDraft) {
        settings.copy(quickPeek = peekDraft ?: settings.quickPeek)
    }
    val look = rememberLook(shown)
    var pickingGlyph by remember { mutableStateOf(false) }
    var pickingBlocks by remember { mutableStateOf(false) }

    HubScaffold(
        tr("Главный экран"),
        onBack,
        header = { AppearanceHeader(look, settings.appearancePreview) },
    ) {
        KnobSegments(
            tr("Кнопка подключения"),
            listOf(tr("Кольцо"), tr("Диск"), tr("Пульс"), tr("Минимум")),
            HERO_STYLES.indexOf(settings.heroStyle).coerceAtLeast(0),
        ) { i -> editor.edit { setHeroStyle(HERO_STYLES[i]) } }

        KnobSegments(
            tr("Размер кнопки"),
            AppearanceRanges.HERO_SIZE_STEPS.map { "$it%" },
            AppearanceRanges.HERO_SIZE_STEPS.indexOf(settings.heroSize).coerceAtLeast(0),
        ) { i -> editor.edit { setHeroSize(AppearanceRanges.HERO_SIZE_STEPS[i]) } }
        KnobHint(tr("На небольших экранах кольцо занимает половину «Главного» — здесь его можно ужать."))

        Spacer(Modifier.height(14.dp))
        LeanGroup {
            LeanNavItem(
                LeanIcon.Power, LeanColors.Accent, tr("Значок кнопки"),
                value = tr(glyphLabel(settings.heroGlyph)),
            ) { pickingGlyph = true }
            LeanDivider()
            LeanToggleItem(
                LeanIcon.Pulse, LeanColors.Blue, tr("Дыхание при подключении"),
                tr("Кольцо медленно пульсирует, пока туннель поднят"), settings.heroBreath,
            ) { on -> editor.edit { setHeroBreath(on) } }
            LeanDivider()
            LeanToggleItem(
                LeanIcon.Power, LeanColors.Violet, tr("Кнопка при прокрутке"),
                tr("Компактная кнопка снизу, когда основная ушла вверх"), settings.heroFloating,
            ) { on -> editor.edit { setHeroFloating(on) } }
        }

        KnobSegments(
            tr("Строка трафика"),
            listOf(tr("Скрыть"), tr("Компактно"), tr("Крупно")),
            TRAFFIC_ROWS.indexOf(settings.trafficRow).coerceAtLeast(0),
        ) { i -> editor.edit { setTrafficRow(TRAFFIC_ROWS[i]) } }

        KnobSegments(
            tr("Подпись текущего сервера"),
            listOf(tr("Имя"), tr("Имя и протокол"), tr("Скрыть")),
            SERVER_LABELS.indexOf(settings.currentServerLabel).coerceAtLeast(0),
        ) { i -> editor.edit { setCurrentServerLabel(SERVER_LABELS[i]) } }

        LeanSectionLabel(tr("Быстрый выбор"))
        LeanSlider(
            value = shown.quickPeek,
            onValueChange = { v -> peekDraft = v },
            onValueChangeFinished = { v ->
                peekDraft = v
                editor.edit { setQuickPeek(v) }
            },
            range = AppearanceRanges.QUICK_PEEK_MIN..AppearanceRanges.QUICK_PEEK_MAX,
            valueLabel = shown.quickPeek.toString(),
        )
        KnobHint(tr("Сколько серверов видно на «Главном» до кнопки «Показать все». Ноль убирает блок целиком."))

        Spacer(Modifier.height(14.dp))
        LeanGroup {
            LeanNavItem(
                LeanIcon.Apps, LeanColors.Violet, tr("Блоки на главном"),
                subtitle = tr("Что показывать под кнопкой подключения"),
                value = "${blockCount(settings.homeBlocks)}/4",
            ) { pickingBlocks = true }
        }
    }

    if (pickingGlyph) {
        AlertDialog(
            onDismissRequest = { pickingGlyph = false },
            title = { Text(tr("Значок кнопки")) },
            text = {
                Column {
                    HERO_GLYPHS.forEach { (key, nameRu) ->
                        RadioRow(tr(nameRu), settings.heroGlyph == key) {
                            editor.edit { setHeroGlyph(key) }
                            pickingGlyph = false
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { pickingGlyph = false }) { Text(tr("Отмена"), color = LeanColors.TextSecondary) }
            },
        )
    }
    if (pickingBlocks) {
        AlertDialog(
            onDismissRequest = { pickingBlocks = false },
            title = { Text(tr("Блоки на главном")) },
            text = {
                Column {
                    HOME_BLOCKS.forEach { (bit, nameRu) ->
                        BlockRow(tr(nameRu), (settings.homeBlocks and bit) != 0) { on ->
                            val mask = if (on) (settings.homeBlocks or bit) else (settings.homeBlocks and bit.inv())
                            editor.edit { setHomeBlocks(mask) }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { pickingBlocks = false }) { Text("OK") }
            },
        )
    }
}

/** A switch row for the block mask; the dialog stays open so all four can be set in one visit. */
@Composable
private fun BlockRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = LeanColors.TextPrimary, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = null)
    }
}

private val HERO_STYLES = listOf("ring", "disc", "pulse", "minimal")
private val TRAFFIC_ROWS = listOf("hidden", "compact", "large")
private val SERVER_LABELS = listOf("name", "name_proto", "hidden")

private val HERO_GLYPHS: List<Pair<String, String>> = listOf(
    "power" to "Питание",
    "shield" to "Щит",
    "globe" to "Глобус",
    "pulse" to "Пульс",
)

/**
 * The blocks «Главный экран» can turn off.
 *
 * The Telegram banner is absent from the build that carries no promotion: a switch for
 * something that is not there reads as broken, and its counter would say one more block
 * exists than the screen can show.
 */
private val HOME_BLOCKS: List<Pair<Int, String>> = buildList {
    add(HomeBlock.SUBSCRIPTION to "Подписка")
    add(HomeBlock.QUICK_PICK to "Быстрый выбор")
    add(HomeBlock.CONNECTION_TEST to "Проверка соединения")
    if (BuildConfig.SHOWS_PROMO) add(HomeBlock.BANNER to "Баннер Telegram")
}

private fun glyphLabel(key: String): String =
    HERO_GLYPHS.firstOrNull { it.first == key }?.second ?: "Питание"

private fun blockCount(mask: Int): Int = HOME_BLOCKS.count { (mask and it.first) != 0 }

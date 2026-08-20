package com.th3web.lean.ui.screen.appearance

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.th3web.lean.LeanApp
import com.th3web.lean.data.AppearanceRanges
import com.th3web.lean.data.Settings
import com.th3web.lean.ui.components.LeanGroup
import com.th3web.lean.ui.components.LeanNavItem
import com.th3web.lean.ui.components.LeanSectionLabel
import com.th3web.lean.ui.components.LeanSlider
import com.th3web.lean.ui.components.LeanDivider
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
 * «Список серверов», how a row reads at a glance: its ping, its tags, and how the selected
 * one announces itself.
 *
 * The showcase opens on the servers scene, where all three sample rows sit in different
 * latency tiers, «Цвет пинга» and «Пороги пинга» are only judgeable side by side.
 */
@Composable
fun AppearanceServersStyleScreen(onBack: () -> Unit) {
    val repo = LeanApp.instance.settings
    val settings by repo.state.collectAsStateWithLifecycle()
    val editor = rememberAppearanceEditor(settings)
    // The rail's draft clears when its own committed value lands, not on release, releasing
    // first would show the old number for the frames the DataStore write takes.
    var washDraft by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(settings.selectionWash) { washDraft = null }
    val shown = remember(settings, washDraft) {
        settings.copy(selectionWash = washDraft ?: settings.selectionWash)
    }
    val look = rememberLook(shown)
    var editingThresholds by remember { mutableStateOf(false) }

    HubScaffold(
        tr("Список серверов"),
        onBack,
        header = { AppearanceHeader(look, settings.appearancePreview, initialScene = PreviewScene.SERVERS) },
    ) {
        KnobSegments(
            tr("Цвет пинга"),
            listOf(tr("Акцент"), tr("Светофор"), tr("Без цвета"), tr("Градиент")),
            LATENCY_PALETTES.indexOf(settings.latencyPalette).coerceAtLeast(0),
        ) { i -> editor.edit { setLatencyPalette(LATENCY_PALETTES[i]) } }

        KnobSegments(
            tr("Индикатор пинга"),
            listOf(tr("Шкала и мс"), tr("Только мс"), tr("Только шкала")),
            LATENCY_METERS.indexOf(settings.latencyMeter).coerceAtLeast(0),
        ) { i -> editor.edit { setLatencyMeter(LATENCY_METERS[i]) } }

        Spacer(Modifier.height(14.dp))
        LeanGroup {
            LeanNavItem(
                LeanIcon.Speed, LeanColors.Accent, tr("Пороги пинга"),
                subtitle = tr("Границы между делениями шкалы"),
                value = "${settings.latT1}/${settings.latT2}/${settings.latT3}",
            ) { editingThresholds = true }
        }

        KnobSegments(
            tr("Строки списка"),
            listOf(tr("Плотно"), tr("Обычно"), tr("Подробно")),
            SERVER_ROWS.indexOf(settings.serverRow).coerceAtLeast(0),
        ) { i -> editor.edit { setServerRow(SERVER_ROWS[i]) } }

        Spacer(Modifier.height(14.dp))
        LeanGroup {
            LeanToggleItem(
                LeanIcon.Layers, LeanColors.Blue, tr("Теги протоколов"),
                tr("Плашки под именем сервера"), settings.showTags,
            ) { on -> editor.edit { setShowTags(on) } }
            // Kind toggles only make sense while the tags themselves are on; showing
            // them regardless would offer a choice with no visible effect.
            if (settings.showTags) {
                val kinds = settings.serverTagKinds
                // The last kind cannot be switched off: an empty set would blank every
                // row while the master toggle still reads on, which looks like a bug
                // rather than a setting. Turning them all off is what «Теги протоколов»
                // above is for.
                fun toggle(flag: Char, on: Boolean) {
                    val next = if (on) (kinds + flag) else kinds.filter { it != flag }
                    if (next.isNotEmpty()) editor.edit { setServerTagKinds(next.toString()) }
                }
                LeanDivider()
                LeanToggleItem(
                    LeanIcon.Hub, LeanColors.Accent, tr("Протокол"),
                    tr("VLESS, Trojan, Hysteria2…"), 'p' in kinds,
                ) { on -> toggle('p', on) }
                LeanDivider()
                LeanToggleItem(
                    LeanIcon.Shield, LeanColors.Violet, tr("Шифрование"),
                    tr("REALITY, TLS, шифр"), 's' in kinds,
                ) { on -> toggle('s', on) }
                LeanDivider()
                LeanToggleItem(
                    LeanIcon.Route, LeanColors.Ember, tr("Транспорт"),
                    tr("TCP, WS, gRPC, QUIC…"), 't' in kinds,
                ) { on -> toggle('t', on) }
            }
        }

        KnobSegments(
            tr("Выделение сервера"),
            listOf(tr("Полоса"), tr("Заливка"), tr("И то и то"), tr("Нет")),
            SELECTION_CUES.indexOf(settings.selectionCue).coerceAtLeast(0),
        ) { i -> editor.edit { setSelectionCue(SELECTION_CUES[i]) } }

        if (settings.selectionCue == "wash" || settings.selectionCue == "both") {
            LeanSectionLabel(tr("Сила выделения"))
            LeanSlider(
                value = shown.selectionWash,
                onValueChange = { v -> washDraft = v },
                onValueChangeFinished = { v ->
                    washDraft = v
                    editor.edit { setSelectionWash(v) }
                },
                range = AppearanceRanges.SELECTION_WASH_MIN..AppearanceRanges.SELECTION_WASH_MAX,
                valueLabel = "${shown.selectionWash}%",
            )
            KnobHint(tr("Насколько заметно акцент заливает выбранную строку."))
        }
    }

    if (editingThresholds) {
        ThresholdDialog(
            settings = settings,
            onDismiss = { editingThresholds = false },
            onConfirm = { t1, t2, t3 ->
                editor.edit { setLatencyThresholds(t1, t2, t3) }
                editingThresholds = false
            },
        )
    }
}

/**
 * The three tier boundaries as plain numbers.
 *
 * Text fields rather than sliders: the useful range runs to five seconds, so a slider would
 * either carry hundreds of tick marks or lose the ten-millisecond precision that makes the
 * setting worth having. Out-of-order values are not rejected here: the settings read
 * mapping clamps the triple in ascending order, so no tier can ever become unreachable.
 */
@Composable
private fun ThresholdDialog(
    settings: Settings,
    onDismiss: () -> Unit,
    onConfirm: (Int, Int, Int) -> Unit,
) {
    var t1 by remember { mutableStateOf(settings.latT1.toString()) }
    var t2 by remember { mutableStateOf(settings.latT2.toString()) }
    var t3 by remember { mutableStateOf(settings.latT3.toString()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr("Пороги пинга")) },
        text = {
            Column {
                ThresholdField(tr("Отлично, до"), t1) { t1 = it }
                Spacer(Modifier.height(8.dp))
                ThresholdField(tr("Хорошо, до"), t2) { t2 = it }
                Spacer(Modifier.height(8.dp))
                ThresholdField(tr("Терпимо, до"), t3) { t3 = it }
                Spacer(Modifier.height(8.dp))
                Text(
                    tr("Всё, что медленнее последнего порога, красится последним делением. На мобильной сети 120 мс — уже хороший результат, а не отличный."),
                    color = LeanColors.TextSecondary,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onConfirm(
                    t1.toIntOrNull() ?: settings.latT1,
                    t2.toIntOrNull() ?: settings.latT2,
                    t3.toIntOrNull() ?: settings.latT3,
                )
            }) { Text(tr("Применить")) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(tr("Отмена"), color = LeanColors.TextSecondary) }
        },
    )
}

@Composable
private fun ThresholdField(label: String, value: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        // Digits only: the field feeds a comparison against a measured latency, and a stray
        // character would silently fall back to the previous value on confirm.
        onValueChange = { raw -> onChange(raw.filter { it.isDigit() }.take(4)) },
        singleLine = true,
        label = { Text(label) },
        suffix = { Text(tr("мс"), color = LeanColors.TextTertiary) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth(),
    )
}

private val LATENCY_PALETTES = listOf("accent", "traffic", "mono", "gradient")
private val LATENCY_METERS = listOf("bars_ms", "ms", "bars")
private val SERVER_ROWS = listOf("compact", "normal", "detailed")
private val SELECTION_CUES = listOf("stripe", "wash", "both", "none")

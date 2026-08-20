package com.th3web.lean.ui.screen.appearance

import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import com.th3web.lean.LeanApp
import com.th3web.lean.data.AppearanceRanges
import com.th3web.lean.data.Settings
import com.th3web.lean.ui.Routes
import com.th3web.lean.ui.components.ColorPickerDialog
import com.th3web.lean.ui.components.LeanDivider
import com.th3web.lean.ui.components.LeanGroup
import com.th3web.lean.ui.components.LeanNavItem
import com.th3web.lean.ui.components.LeanSectionLabel
import com.th3web.lean.ui.components.LeanSlider
import com.th3web.lean.ui.components.LeanSwatch
import com.th3web.lean.ui.components.LeanToggleItem
import com.th3web.lean.ui.icons.LeanIcon
import com.th3web.lean.ui.screen.HubScaffold
import com.th3web.lean.ui.screen.AppearanceHeader
import com.th3web.lean.ui.screen.KnobHint
import com.th3web.lean.ui.screen.KnobSegments
import com.th3web.lean.ui.screen.rememberAppearanceEditor
import com.th3web.lean.ui.screen.rememberLook
import com.th3web.lean.ui.theme.LeanColors
import com.th3web.lean.ui.theme.LeanType
import com.th3web.lean.ui.theme.leanPalette
import com.th3web.lean.ui.tr

/**
 * «Цвет», where the accent comes from, how loud it is, and the three inks that are not
 * allowed to follow it blindly.
 *
 * The two sliders here are the reason this screen holds a draft: they move a colour, and a
 * colour is what the showcase exists to show. A drag never writes, it moves a
 * screen-local number the showcase resolves against, so the app behind the sheet stays on
 * the committed look until the finger lifts.
 */
@Composable
fun AppearanceColorScreen(onBack: () -> Unit, onNavigate: (String) -> Unit) {
    val repo = LeanApp.instance.settings
    val settings by repo.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val editor = rememberAppearanceEditor(settings)
    // One draft Int per rail, not a whole draft Settings: an unrelated emission during a
    // drag would make a snapshot copy stale, and a single field cannot go stale. Each draft
    // clears itself when its own committed value arrives, which is what keeps the thumb from
    // snapping back to the old number for the frames DataStore needs to land the write.
    var chromaDraft by remember { mutableStateOf<Int?>(null) }
    var tintDraft by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(settings.accentChroma) { chromaDraft = null }
    LaunchedEffect(settings.surfaceTint) { tintDraft = null }
    val shown = remember(settings, chromaDraft, tintDraft) {
        settings.copy(
            accentChroma = chromaDraft ?: settings.accentChroma,
            surfaceTint = tintDraft ?: settings.surfaceTint,
        )
    }
    val look = rememberLook(shown)
    var showPicker by remember { mutableStateOf(false) }

    HubScaffold(
        tr("Цвет"),
        onBack,
        header = { AppearanceHeader(look, settings.appearancePreview) },
    ) {
        // Below API 27 there is no permission-free way to read the wallpaper's colours, so
        // the segment is absent rather than present-and-disabled, an option that cannot
        // work should not be offered.
        val sources = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            ACCENT_SOURCES
        } else {
            ACCENT_SOURCES.dropLast(1)
        }
        KnobSegments(
            tr("Источник цвета"),
            sources.map { tr(it.second) },
            sources.indexOfFirst { it.first == settings.accentSource }.coerceAtLeast(0),
        ) { i -> editor.edit { setAccentSource(sources[i].first) } }
        KnobHint(tr("«Обои» берут один цвет из обоев и пропускают его через ту же палитру — AMOLED-лестница и светлая колонка продолжают работать."))

        Spacer(Modifier.height(14.dp))
        LeanGroup {
            LeanNavItem(
                LeanIcon.Palette, LeanColors.Accent, tr("Свой цвет"),
                subtitle = tr("Оттенок и насыщенность; палитра достроится сама"),
                value = tr(look.spec.accent.nameRu),
            ) { showPicker = true }
        }

        LeanSectionLabel(tr("Приглушённость"))
        LeanSlider(
            value = shown.accentChroma,
            onValueChange = { v -> chromaDraft = v },
            onValueChangeFinished = { v ->
                chromaDraft = v
                editor.edit { setAccentChroma(v) }
            },
            range = AppearanceRanges.ACCENT_CHROMA_MIN..AppearanceRanges.ACCENT_CHROMA_MAX,
            step = 5,
            valueLabel = "${shown.accentChroma}%",
        )
        KnobHint(tr("Потолок насыщенности синтезированного акцента. Вся палитра Lean построена приглушённой — выше 70% цвет начинает спорить с текстом."))

        LeanSectionLabel(tr("Оттенок поверхностей"))
        LeanSlider(
            value = shown.surfaceTint,
            onValueChange = { v -> tintDraft = v },
            onValueChangeFinished = { v ->
                tintDraft = v
                editor.edit { setSurfaceTint(v) }
            },
            range = AppearanceRanges.SURFACE_TINT_MIN..AppearanceRanges.SURFACE_TINT_MAX,
            step = 2,
            valueLabel = "${shown.surfaceTint}%",
        )
        KnobHint(tr("Сколько акцента примешано в серые поверхности. Светлота не меняется, поэтому контраст остаётся прежним; на светлой теме значение ограничено 12%."))

        KnobSegments(
            tr("Цвет «Подключено»"),
            listOf(tr("Шалфей"), tr("Акцент")),
            if (settings.connectedMode == "accent") 1 else 0,
        ) { i -> editor.edit { setConnectedMode(if (i == 1) "accent" else "sage") } }
        if (look.spec.connectedVetoed) {
            KnobHint(tr("Акцент слишком близок к цвету ошибки — «Подключено» осталось шалфейным, иначе успех выглядел бы как сбой."))
        } else {
            KnobHint(tr("Единственный цвет, означающий «всё хорошо». По умолчанию он не следует за акцентом намеренно."))
        }

        LeanSectionLabel(tr("Цвет ошибок"))
        // Resolved by asking the palette resolver for each candidate, so these squares are
        // the exact ink the app would draw, including the light column and «Контрастность».
        val errorInks = remember(look.spec) {
            ERROR_COLORS.map { it.first to leanPalette(look.spec.copy(errorColor = it.first)).error }
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            errorInks.forEachIndexed { i, (key, ink) ->
                // LeanSwatch's `label` is a contentDescription, not a caption, so the row
                // would otherwise be three unnamed circles, the check mark tells you
                // which is picked but nothing tells you what any of them are called.
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    LeanSwatch(
                        fill = ink,
                        selected = settings.errorColor == key,
                        onClick = { editor.edit { setErrorColor(key) } },
                        label = tr(ERROR_COLORS[i].second),
                    )
                    Spacer(Modifier.height(5.dp))
                    Text(
                        tr(ERROR_COLORS[i].second),
                        color = if (settings.errorColor == key) LeanColors.TextSecondary else LeanColors.TextTertiary,
                        style = LeanType.meta,
                    )
                }
            }
        }

        Spacer(Modifier.height(16.dp))
        LeanGroup {
            LeanToggleItem(
                LeanIcon.Palette, LeanColors.Accent, tr("Логотип по акценту"),
                tr("Надпись Lean красится градиентом из акцента"), settings.wordmarkAccent,
            ) { on -> editor.edit { setWordmarkAccent(on) } }
            LeanDivider()
            LeanNavItem(
                LeanIcon.Palette, LeanColors.Violet, tr("Цвета по ролям"),
                subtitle = tr("Десять смысловых слотов вместо всех токенов"),
                value = roleOverridesLabel(settings),
            ) { onNavigate(Routes.APPEARANCE_ROLES) }
        }

        // The wordmark is not in the showcase (it lives in two app bars), so the knob
        // above gets its sample here, in the live look.
        Spacer(Modifier.height(18.dp))
        Text("Lean", style = LeanType.appTitle, modifier = Modifier.padding(start = 4.dp))
        Spacer(Modifier.height(4.dp))
        Text(
            tr("Так выглядит логотип с текущими настройками"),
            color = LeanColors.TextTertiary,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(start = 4.dp),
        )
    }

    if (showPicker) {
        ColorPickerDialog(
            initial = settings.accentColor,
            title = tr("Свой цвет"),
            recent = settings.accentRecent,
            chromaClamp = settings.accentChroma,
            onDismiss = { showPicker = false },
            onConfirm = { argb ->
                editor.edit { setAccentSource("custom"); setAccentColor(argb) }
                scope.launch { repo.setAccentRecent(listOf(argb) + settings.accentRecent) }
                showPicker = false
            },
        )
    }
}

/** Stored value → RU label, in segment order. «Обои» is last so a pre-27 device can drop it. */
private val ACCENT_SOURCES: List<Pair<String, String>> = listOf(
    "preset" to "Из набора",
    "custom" to "Свой",
    "wallpaper" to "Обои",
)

private val ERROR_COLORS: List<Pair<String, String>> = listOf(
    "coral" to "Коралл",
    "crimson" to "Алый",
    "amber" to "Янтарь",
)

private fun roleOverridesLabel(s: Settings): String =
    if (s.roleOverrides.isEmpty()) tr("выкл") else tr("%d шт.").format(s.roleOverrides.size)

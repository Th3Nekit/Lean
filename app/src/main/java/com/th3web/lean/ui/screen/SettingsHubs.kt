package com.th3web.lean.ui.screen

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import com.th3web.lean.BuildConfig
import com.th3web.lean.LeanApp
import com.th3web.lean.R
import com.th3web.lean.core.AppIcon
import com.th3web.lean.core.SingBoxConfig
import com.th3web.lean.core.VpnState
import com.th3web.lean.data.AppearanceProfile
import com.th3web.lean.data.AppearancePresets
import com.th3web.lean.data.AppearanceRanges
import com.th3web.lean.data.ClientSpoof
import com.th3web.lean.data.NamedAppearance
import com.th3web.lean.data.decodeAppearance
import com.th3web.lean.data.encode
import com.th3web.lean.data.net.CrashReporter
import com.th3web.lean.data.net.Pinger
import com.th3web.lean.data.net.UpdateChecker
import com.th3web.lean.data.RoutingMode
import com.th3web.lean.data.Settings
import com.th3web.lean.data.SettingsDefaults
import com.th3web.lean.data.SettingsRepository
import com.th3web.lean.data.toAppearanceProfile
import com.th3web.lean.ui.Routes
import com.th3web.lean.ui.SETTINGS_RUNTIME_VERSION
import com.th3web.lean.ui.components.rememberLeanClipboard
import com.th3web.lean.ui.openUrl
import com.th3web.lean.ui.tr
import com.th3web.lean.ui.components.dontKillMyAppUrl
import com.th3web.lean.ui.components.isIgnoringBatteryOptimisations
import com.th3web.lean.ui.components.requestIgnoreBatteryOptimisations
import com.th3web.lean.ui.components.ColorPickerDialog
import com.th3web.lean.ui.components.LeanAccentSwatch
import com.th3web.lean.ui.components.LeanAddSwatch
import com.th3web.lean.ui.components.LeanChoiceItem
import com.th3web.lean.ui.components.LeanDivider
import com.th3web.lean.ui.components.LeanGroup
import com.th3web.lean.ui.components.LeanNavItem
import com.th3web.lean.ui.components.LeanSectionLabel
import com.th3web.lean.ui.components.LeanSlider
import com.th3web.lean.ui.components.LeanToggleItem
import com.th3web.lean.ui.components.PresetCard
import com.th3web.lean.ui.components.SegmentedControl
import com.th3web.lean.ui.components.rememberPresetSpec
import com.th3web.lean.ui.icons.LeanIcon
import com.th3web.lean.ui.icons.LeanIconImage
import com.th3web.lean.ui.screen.appearance.AppearancePreview
import com.th3web.lean.ui.screen.appearance.PreviewScene
import com.th3web.lean.ui.screen.appearance.PreviewThumbHeight
import com.th3web.lean.ui.screen.appearance.nextPreviewState
import com.th3web.lean.ui.theme.AppearanceSpec
import com.th3web.lean.ui.theme.LeanAccents
import com.th3web.lean.ui.theme.LeanColors
import com.th3web.lean.ui.theme.LeanCorner
import com.th3web.lean.ui.theme.LeanPalette
import com.th3web.lean.ui.theme.appearanceSpec
import com.th3web.lean.ui.theme.leanPalette
import com.th3web.lean.ui.theme.motionAllowed
import com.th3web.lean.ui.theme.rememberNightWindow
import com.th3web.lean.ui.theme.rememberWallpaperSeed

/**
 * Shared push-screen scaffold, `internal` so sibling screens (BackupScreen, the nine
 * «Оформление» screens) reuse it.
 *
 * [header] renders between the app bar and the scrolling column, i.e. pinned: the
 * «Витрина» has to stay visible while the user scrolls past the knob that moves it,
 * otherwise the live preview previews nothing. It defaults to null, so every other hub
 * lays out exactly as before.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun HubScaffold(
    title: String,
    onBack: () -> Unit,
    header: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Scaffold(
        containerColor = LeanColors.Background,
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        LeanIconImage(LeanIcon.Back, tint = LeanColors.TextPrimary, modifier = Modifier.size(22.dp))
                    }
                },
                // No fontWeight parameter: it would outrank titleLarge's own weight and so
                // silently ignore «Жирность». titleLarge is already bold.
                title = { Text(title) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = LeanColors.Background,
                    scrolledContainerColor = LeanColors.Surface,
                    titleContentColor = LeanColors.TextPrimary,
                    navigationIconContentColor = LeanColors.TextPrimary,
                    actionIconContentColor = LeanColors.TextSecondary,
                ),
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            header?.invoke()
            Column(
                modifier = Modifier
                    // weight, not fillMaxSize: the scrolling column takes whatever the
                    // header left. With no header that is the whole viewport, i.e. exactly
                    // the previous layout.
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp),
            ) {
                content()
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

/**
 * The root of «Оформление»: the pinned showcase, the preset carousel, the theme decisions
 * that belong at the top level, and the doors into the nine detail screens.
 *
 * Everything narrower than "which look am I wearing" lives one push away,.
 * This screen is a `Column(verticalScroll)` (it composes its whole content eagerly) so
 * sixty knobs here would be sixty knobs measured on every frame of a scroll.
 */
@Composable
fun AppearanceHub(onBack: () -> Unit, onNavigate: (String) -> Unit) {
    val repo = LeanApp.instance.settings
    val settings by repo.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val editor = rememberAppearanceEditor(settings)
    val look = rememberLook(settings)
    val systemDark = isSystemInDarkTheme()
    // Structural equality against the applied look, not the stored label: a fresh install
    // wears «Сталь·Ночь» while the label still says "custom", and after any single knob the
    // profile stops matching whatever preset the label names.
    val activeProfile = remember(settings) { settings.toAppearanceProfile() }

    var showCustomColor by remember { mutableStateOf(false) }
    var showCode by remember { mutableStateOf(false) }
    var showReset by remember { mutableStateOf(false) }
    var savingPreset by remember { mutableStateOf(false) }
    var editingPreset by remember { mutableStateOf<NamedAppearance?>(null) }
    var renamingPreset by remember { mutableStateOf<NamedAppearance?>(null) }
    var editingFrom by remember { mutableStateOf(false) }
    var editingTo by remember { mutableStateOf(false) }

    HubScaffold(
        tr("Внешний вид"),
        onBack,
        header = { AppearanceHeader(look, settings.appearancePreview, sceneSwitcher = true) },
    ) {
        LeanSectionLabel(tr("Готовые образы"))
        LazyRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = PaddingValues(horizontal = 2.dp),
        ) {
            items(AppearancePresets.all, key = { "built:${it.name}" }) { named ->
                PresetCard(
                    name = tr(named.name),
                    spec = rememberPresetSpec(named.profile, systemDark),
                    selected = named.profile == activeProfile,
                    onClick = { scope.launch { repo.applyAppearance(named.profile, named.name) } },
                )
            }
            items(settings.customPresets, key = { "own:${it.name}" }) { named ->
                PresetCard(
                    name = named.name,
                    spec = rememberPresetSpec(named.profile, systemDark),
                    selected = named.profile == activeProfile,
                    onClick = { scope.launch { repo.applyAppearance(named.profile, named.name) } },
                    onLongClick = { editingPreset = named },
                )
            }
        }
        // Both interactions are load-bearing here, not just the long-press: a plain tap
        // on any card (built-in or saved) already calls applyAppearance and is how a
        // saved look gets restored, but that was never said out loud, only the
        // secondary long-press action was, so "how do I get my saved look back" read as
        // a missing feature rather than an undocumented tap.
        KnobHint(tr("Сейчас: %s. Тап — применить. Долгий тап по своему образу — переименовать или удалить.").format(presetLabel(settings)))

        Spacer(Modifier.height(14.dp))
        LeanGroup {
            LeanNavItem(
                LeanIcon.Plus, LeanColors.Accent, tr("Сохранить образ"),
                subtitle = tr("Запомнить текущее оформление под своим именем"),
                value = "${settings.customPresets.size}/${AppearanceRanges.CUSTOM_PRESET_MAX}",
            ) { savingPreset = true }
            LeanDivider()
            LeanNavItem(
                LeanIcon.Route, LeanColors.Blue, tr("Код оформления"),
                subtitle = tr("Короткая строка — поделиться образом или применить чужой"),
            ) { showCode = true }
        }

        KnobSegments(
            tr("Тема"),
            listOf(tr("Обычная"), "AMOLED", tr("Светлая"), tr("Системная")),
            THEME_MODES.indexOf(settings.themeMode).coerceAtLeast(0),
        ) { i -> editor.edit { setThemeMode(THEME_MODES[i]) } }

        // The two AMOLED knobs only exist on the AMOLED canvas, including when the night
        // schedule is what put us there, which is why this asks the resolved look.
        if (look.spec.amoled) {
            KnobSegments(
                tr("Глубина чёрного"),
                listOf(tr("Абсолютная"), tr("Мягкая")),
                if (settings.amoledDepth == "soft") 1 else 0,
            ) { i -> editor.edit { setAmoledDepth(if (i == 1) "soft" else "absolute") } }
            KnobHint(tr("«Мягкая» поднимает карточки на ступень — для матриц с заметным ореолом вокруг чёрного."))
            Spacer(Modifier.height(12.dp))
            LeanGroup {
                LeanToggleItem(
                    LeanIcon.Palette, LeanColors.Accent, tr("Оттенок на AMOLED"),
                    tr("Разрешить акценту подкрашивать поднятые карточки"), settings.amoledTint,
                ) { on -> editor.edit { setAmoledTint(on) } }
            }
        }

        KnobSegments(
            tr("Контрастность"),
            CONTRAST_LABELS,
            settings.contrastLevel - AppearanceRanges.CONTRAST_MIN,
        ) { i -> editor.edit { setContrastLevel(i + AppearanceRanges.CONTRAST_MIN) } }
        KnobHint(tr("Шаг — 4% светлоты. Двигает фон, карточки, текст и шкалу пинга разом."))

        Spacer(Modifier.height(14.dp))
        LeanGroup {
            LeanToggleItem(
                LeanIcon.Cloud, LeanColors.Violet, tr("Ночью по расписанию"),
                tr("Переключать тему по часам"), settings.themeSchedule,
            ) { on -> editor.edit { setThemeSchedule(on) } }
            if (settings.themeSchedule) {
                LeanDivider()
                LeanNavItem(LeanIcon.Info, LeanColors.Blue, tr("Начало ночи"), value = clockLabel(settings.themeSchedFrom)) { editingFrom = true }
                LeanDivider()
                LeanNavItem(LeanIcon.Info, LeanColors.Blue, tr("Конец ночи"), value = clockLabel(settings.themeSchedTo)) { editingTo = true }
            }
        }
        if (settings.themeSchedule) {
            KnobSegments(
                tr("Тема ночью"),
                listOf(tr("Обычная"), "AMOLED"),
                if (settings.themeSchedMode == "dark") 0 else 1,
            ) { i -> editor.edit { setThemeSchedMode(if (i == 0) "dark" else "amoled") } }
        }

        LeanSectionLabel(tr("Цвет акцента"))
        // The chroma clamp is part of the answer: without it the row would highlight, and
        // the caption would name, a colour more saturated than the app will draw.
        val currentAccent = LeanAccents.resolve(settings.accentColor, settings.accentChroma)
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 2.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            LeanAccents.all.forEach { a ->
                LeanAccentSwatch(
                    accent = a,
                    selected = a.seed == currentAccent.seed,
                    onClick = { editor.edit { setAccentSource("preset"); setAccentColor(a.seed) } },
                    size = AccentSwatchSize,
                )
            }
            LeanAddSwatch(onClick = { showCustomColor = true }, size = AccentSwatchSize, label = tr("Свой цвет"))
        }
        Spacer(Modifier.height(8.dp))
        Text(
            // A synthesised accent's nameRu is its "#RRGGBB"; tr() returns an unknown key
            // verbatim, so the hex prints itself and needs no EN pair.
            tr(currentAccent.nameRu),
            color = LeanColors.TextTertiary,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(start = 4.dp),
        )

        LeanSectionLabel(tr("Разделы"))
        LeanGroup {
            LeanNavItem(LeanIcon.Palette, LeanColors.Accent, tr("Цвет"), subtitle = tr("Источник, приглушённость, оттенок поверхностей")) { onNavigate(Routes.APPEARANCE_COLOR) }
            LeanDivider()
            LeanNavItem(LeanIcon.Lang, LeanColors.Blue, tr("Шрифты"), subtitle = tr("Гарнитуры, размер, жирность")) { onNavigate(Routes.APPEARANCE_FONTS) }
            LeanDivider()
            LeanNavItem(LeanIcon.Layers, LeanColors.Violet, tr("Форма и плотность"), subtitle = tr("Скругление, отступы, контуры, тени")) { onNavigate(Routes.APPEARANCE_SHAPE) }
            LeanDivider()
            LeanNavItem(LeanIcon.Power, LeanColors.Accent, tr("Главный экран"), subtitle = tr("Кнопка подключения, трафик, блоки")) { onNavigate(Routes.APPEARANCE_HOME) }
            LeanDivider()
            LeanNavItem(LeanIcon.Servers, LeanColors.Blue, tr("Список серверов"), subtitle = tr("Пинг, теги, выделение")) { onNavigate(Routes.APPEARANCE_SERVERS) }
            LeanDivider()
            LeanNavItem(LeanIcon.Pulse, LeanColors.Ember, tr("Движение"), subtitle = tr("Анимации, переходы, вибро-отклик")) { onNavigate(Routes.APPEARANCE_MOTION) }
            LeanDivider()
            LeanNavItem(LeanIcon.Gear, LeanColors.TextSecondary, tr("Фон и система"), subtitle = tr("Фон, системные панели, иконка, язык")) { onNavigate(Routes.APPEARANCE_SYSTEM) }
            LeanDivider()
            LeanNavItem(LeanIcon.Split, LeanColors.EmberRed, tr("Экспериментально"), subtitle = tr("Цвета по ролям, уровень логов, сброс")) { onNavigate(Routes.APPEARANCE_LAB) }
        }

        LeanSectionLabel(tr("Витрина"))
        LeanGroup {
            LeanToggleItem(
                LeanIcon.Palette, LeanColors.Accent, tr("Показывать витрину"),
                tr("Живой образец под шапкой. Выключение снимает две постоянные анимации."),
                settings.appearancePreview,
            ) { on -> scope.launch { repo.setAppearancePreview(on) } }
        }

        Spacer(Modifier.height(14.dp))
        LeanGroup {
            LeanNavItem(
                LeanIcon.Refresh, LeanColors.EmberRed, tr("Сбросить оформление"),
                subtitle = tr("Вернуть образ «Сталь·Ночь»"),
            ) { showReset = true }
        }
    }

    if (showCustomColor) {
        ColorPickerDialog(
            initial = settings.accentColor,
            title = tr("Свой цвет"),
            recent = settings.accentRecent,
            chromaClamp = settings.accentChroma,
            onDismiss = { showCustomColor = false },
            onConfirm = { argb ->
                editor.edit { setAccentSource("custom"); setAccentColor(argb) }
                scope.launch { repo.setAccentRecent(listOf(argb) + settings.accentRecent) }
                showCustomColor = false
            },
        )
    }
    if (savingPreset) {
        PresetNameDialog(
            title = tr("Сохранить образ"),
            initial = "",
            onDismiss = { savingPreset = false },
            onConfirm = { name ->
                val saved = NamedAppearance(name, activeProfile)
                scope.launch {
                    // takeLast, not take: the repository caps the library at
                    // CUSTOM_PRESET_MAX by keeping the first entries, so an eleventh save
                    // appended at the end would be dropped on write and the button would
                    // silently do nothing at exactly 10/10. Rolling the oldest off instead
                    // is what the recent-accent list already does at its own cap.
                    repo.setCustomPresets(
                        (settings.customPresets.filterNot { it.name == name } + saved)
                            .takeLast(AppearanceRanges.CUSTOM_PRESET_MAX),
                    )
                    repo.setAppearancePreset(name)
                }
                savingPreset = false
            },
        )
    }
    renamingPreset?.let { target ->
        PresetNameDialog(
            title = tr("Переименовать"),
            initial = target.name,
            onDismiss = { renamingPreset = null },
            onConfirm = { name ->
                scope.launch {
                    // Renaming onto an existing name replaces it rather than producing a
                    // twin: the carousel keys its cards by name, and LazyRow throws on a
                    // repeated key, a crash reachable by renaming one saved look to the
                    // name of another. Same rule as saving, which also overwrites a
                    // same-named look.
                    repo.setCustomPresets(
                        settings.customPresets
                            .filterNot { it.name == name && it.name != target.name }
                            .map { if (it.name == target.name) it.copy(name = name) else it },
                    )
                    if (settings.appearancePreset == target.name) repo.setAppearancePreset(name)
                }
                renamingPreset = null
            },
        )
    }
    editingPreset?.let { target ->
        AlertDialog(
            onDismissRequest = { editingPreset = null },
            title = { Text(target.name) },
            text = {
                Column {
                    TextButton(onClick = {
                        editingPreset = null
                        renamingPreset = target
                    }) { Text(tr("Переименовать")) }
                    TextButton(onClick = {
                        scope.launch {
                            repo.setCustomPresets(settings.customPresets.filterNot { it.name == target.name })
                            // The label is what «Сейчас: …» reads. Left pointing at a look
                            // the user just deleted, the caption keeps naming a card that
                            // is no longer in the carousel. The applied appearance does
                            // not change, only the name we claim for it.
                            if (settings.appearancePreset == target.name) {
                                repo.setAppearancePreset(CUSTOM_PRESET)
                            }
                        }
                        editingPreset = null
                    }) { Text(tr("Удалить"), color = LeanColors.Error) }
                }
            },
            confirmButton = {
                TextButton(onClick = { editingPreset = null }) { Text(tr("Отмена"), color = LeanColors.TextSecondary) }
            },
        )
    }
    if (showCode) {
        AppearanceCodeDialog(
            current = activeProfile,
            systemDark = systemDark,
            onDismiss = { showCode = false },
            onApply = { profile ->
                scope.launch { repo.applyAppearance(profile) }
                showCode = false
            },
        )
    }
    if (showReset) {
        AlertDialog(
            onDismissRequest = { showReset = false },
            title = { Text(tr("Сбросить оформление")) },
            text = { Text(tr("Все шестьдесят настроек оформления вернутся к образу «Сталь·Ночь». Серверы, подписки и соединение не затрагиваются.")) },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch { repo.applyAppearance(AppearanceProfile.Default, AppearancePresets.Steel.name) }
                    showReset = false
                }) { Text(tr("Сбросить")) }
            },
            dismissButton = {
                TextButton(onClick = { showReset = false }) { Text(tr("Отмена"), color = LeanColors.TextSecondary) }
            },
        )
    }
    if (editingFrom) {
        MinuteDialog(tr("Начало ночи"), settings.themeSchedFrom, onDismiss = { editingFrom = false }) { m ->
            editor.edit { setThemeSchedFrom(m) }
            editingFrom = false
        }
    }
    if (editingTo) {
        MinuteDialog(tr("Конец ночи"), settings.themeSchedTo, onDismiss = { editingTo = false }) { m ->
            editor.edit { setThemeSchedTo(m) }
            editingTo = false
        }
    }
}

/** Segment order of «Тема»; the index is the segment, the string is the stored value. */
private val THEME_MODES = listOf("dark", "amoled", "light", "system")

/**
 * «Контрастность» as five neutral marks rather than five words. Five translated labels do
 * not fit one row at «Размер текста» 120, and the direction is the only thing they carry.
 */
private val CONTRAST_LABELS = listOf("−−", "−", "•", "+", "++")

/** Eight tiles have to share the row the seven accents used to have to themselves. */
private val AccentSwatchSize = 34.dp

/** What the tab currently calls the applied look; "custom" reads as «Свой». */
private fun presetLabel(s: Settings): String =
    if (s.appearancePreset == CUSTOM_PRESET) tr("Свой") else tr(s.appearancePreset)

private fun clockLabel(minutes: Int): String = "%02d:%02d".format(minutes / 60, minutes % 60)

/** Name a saved look. Blank is refused rather than silently stored as an unnameable card. */
@Composable
private fun PresetNameDialog(
    title: String,
    initial: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var name by remember(initial) { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it.take(PresetNameMax) },
                singleLine = true,
                placeholder = { Text(tr("Название"), color = LeanColors.TextTertiary) },
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(name.trim()) }, enabled = name.isNotBlank()) {
                Text(tr("Сохранить"))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(tr("Отмена"), color = LeanColors.TextSecondary) }
        },
    )
}

private const val PresetNameMax = 40

/**
 * «Код оформления», the look as a paste-able string, both directions.
 *
 * An incoming code is previewed before it is applied and nothing is written until the user
 * confirms: a code is a stranger's fifty numbers, and the alternative is finding out what
 * they did by wearing them.
 */
@Composable
private fun AppearanceCodeDialog(
    current: AppearanceProfile,
    systemDark: Boolean,
    onDismiss: () -> Unit,
    onApply: (AppearanceProfile) -> Unit,
) {
    val context = LocalContext.current
    val clipboard = rememberLeanClipboard()
    val code = remember(current) { current.encode() }
    var incoming by remember { mutableStateOf("") }
    val decoded = remember(incoming) { decodeAppearance(incoming) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr("Код оформления")) },
        text = {
            Column {
                Text(
                    code,
                    color = LeanColors.TextSecondary,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(6.dp))
                TextButton(onClick = {
                    clipboard.copy(code)
                    Toast.makeText(context, tr("Скопировано"), Toast.LENGTH_SHORT).show()
                }) { Text(tr("Скопировать значение")) }
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(
                    value = incoming,
                    onValueChange = { incoming = it },
                    singleLine = true,
                    placeholder = { Text("LEAN1:…", color = LeanColors.TextTertiary) },
                    modifier = Modifier.fillMaxWidth(),
                )
                if (decoded != null) {
                    Spacer(Modifier.height(10.dp))
                    val spec = rememberPresetSpec(decoded, systemDark)
                    AppearancePreview(
                        palette = remember(spec) { leanPalette(spec) },
                        spec = spec,
                        compact = true,
                        height = PreviewThumbHeight,
                    )
                } else if (incoming.isNotBlank()) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        tr("Это не код оформления Lean"),
                        color = LeanColors.Error,
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { decoded?.let(onApply) }, enabled = decoded != null) {
                Text(tr("Применить"))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(tr("Отмена"), color = LeanColors.TextSecondary) }
        },
    )
}

/**
 * A time of day as two sliders instead of an M3 `TimePicker`.
 *
 * The picker is 350dp of dial and does not fit an `AlertDialog`'s text slot at large font
 * scales; two rails do, they need no experimental opt-in, and the draft is dialog-local so
 * nothing is persisted until «Применить».
 */
@Composable
private fun MinuteDialog(
    title: String,
    minutes: Int,
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit,
) {
    var draft by remember(minutes) { mutableStateOf(minutes) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                Text(clockLabel(draft), color = LeanColors.Accent, style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.height(12.dp))
                LeanSlider(
                    value = draft / 60,
                    onValueChange = { h -> draft = h * 60 + draft % 60 },
                    onValueChangeFinished = { h -> draft = h * 60 + draft % 60 },
                    range = 0..23,
                    label = tr("Часы"),
                )
                Spacer(Modifier.height(6.dp))
                LeanSlider(
                    // Five-minute steps: a schedule boundary is never worth 60 tick marks.
                    value = draft % 60 / MinuteStep,
                    onValueChange = { m -> draft = draft / 60 * 60 + m * MinuteStep },
                    onValueChangeFinished = { m -> draft = draft / 60 * 60 + m * MinuteStep },
                    range = 0..(60 / MinuteStep - 1),
                    label = tr("Минуты"),
                )
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(draft) }) { Text(tr("Применить")) } },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(tr("Отмена"), color = LeanColors.TextSecondary) }
        },
    )
}

private const val MinuteStep = 5

// ── Shared «Оформление» plumbing, used by the hub and the nine push screens ───

/** The stored label of an un-named look. Also what every single-knob write restores. */
internal const val CUSTOM_PRESET = "custom"

/** A resolved look plus the palette it renders as, what the showcase is drawn from. */
@Immutable
internal data class AppearanceLook(val spec: AppearanceSpec, val palette: LeanPalette)

/**
 * Resolve a settings snapshot the way `LeanTheme` resolves the live one.
 *
 * Screens pass either the committed settings or a screen-local draft (a slider under the
 * finger), and get back a look the showcase can draw without publishing anything, which is
 * the only reason a draft can exist at all: `LeanColors` is snapshot state read ~314 times
 * across the app, so a published draft would repaint every screen behind this one.
 */
@Composable
internal fun rememberLook(settings: Settings): AppearanceLook {
    val systemDark = isSystemInDarkTheme()
    val nightNow = rememberNightWindow(settings)
    val wallpaperSeed = rememberWallpaperSeed(settings)
    return remember(settings, systemDark, nightNow, wallpaperSeed) {
        val spec = settings.appearanceSpec(systemDark, nightNow, wallpaperSeed)
        AppearanceLook(spec, leanPalette(spec))
    }
}

/**
 * The pinned showcase, for [HubScaffold]'s header slot.
 *
 * Renders nothing when «Показывать витрину» is off, not a hidden composable, no state, no
 * frame drivers. The demo state cycles on tap so the reserved sage «Подключено» and the
 * error ink can be judged without raising or breaking a tunnel.
 */
@Composable
internal fun AppearanceHeader(
    look: AppearanceLook,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    initialScene: Int = PreviewScene.HOME,
    sceneSwitcher: Boolean = false,
    debug: Boolean = false,
) {
    if (!enabled) return
    var scene by rememberSaveable { mutableStateOf(initialScene) }
    var demo by remember { mutableStateOf<VpnState>(VpnState.Disconnected) }
    Column(modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        AppearancePreview(
            palette = look.palette,
            spec = look.spec,
            scene = scene,
            state = demo,
            // The caller's gate is "may this screen animate"; the look's own «Анимации»
            // is folded in by the preview itself.
            animate = motionAllowed(),
            debug = debug,
            onHeroClick = { demo = nextPreviewState(demo) },
        )
        if (sceneSwitcher) {
            Spacer(Modifier.height(8.dp))
            SegmentedControl(
                listOf(tr("Главный"), tr("Серверы"), tr("Настройки")),
                scene.coerceIn(0, PreviewScene.COUNT - 1),
                { scene = it },
            )
        }
    }
}

/**
 * The one writer for every individual «Оформление» knob.
 *
 * The preset label is the reason this type exists. Only `applyAppearance` writes it, so a
 * knob that called the repository directly would leave the tab naming a preset the user has
 * since edited, and there are sixty knobs, i.e. sixty chances to forget. Routing them all
 * through here makes "touched a knob ⇒ свой образ" a property of the tab instead of sixty
 * remembered call-sites.
 *
 * Knobs that are not part of a look (the launcher icon, the language, the log level, the
 * showcase toggle) do not come through here: they are not in
 * [AppearanceProfile], so a preset does not carry them and touching one does not break it.
 */
internal class AppearanceEditor(
    private val repo: SettingsRepository,
    private val scope: CoroutineScope,
    private val preset: String,
) {
    fun edit(write: suspend SettingsRepository.() -> Unit) {
        scope.launch {
            repo.write()
            // Skipped when it is already "custom", so the common case stays one write.
            if (preset != CUSTOM_PRESET) repo.setAppearancePreset(CUSTOM_PRESET)
        }
    }
}

@Composable
internal fun rememberAppearanceEditor(settings: Settings): AppearanceEditor {
    val repo = LeanApp.instance.settings
    val scope = rememberCoroutineScope()
    return remember(repo, scope, settings.appearancePreset) {
        AppearanceEditor(repo, scope, settings.appearancePreset)
    }
}

/** Section label over a segmented row, the shape every closed-set knob in the tab takes. */
@Composable
internal fun KnobSegments(
    title: String,
    options: List<String>,
    index: Int,
    onSelect: (Int) -> Unit,
) {
    LeanSectionLabel(title)
    SegmentedControl(options, index.coerceIn(0, options.lastIndex), onSelect)
}

/** The quiet explanatory line a knob carries when its name cannot say enough. */
@Composable
internal fun KnobHint(text: String) {
    Text(
        text,
        color = LeanColors.TextTertiary,
        style = MaterialTheme.typography.labelMedium,
        modifier = Modifier.padding(start = 4.dp, top = 9.dp),
    )
}

/**
 * Launcher-icon variants (setting value → RU display name, also the tr() key).
 * Keep in sync with AppIcon.aliases / the manifest activity-aliases.
 *
 * The eight keys that are not "packNN" are older than the artwork behind them. They were
 * kept (rather than renamed to match), because a key is the enabled activity-alias for
 * everyone already using it: dropping one would leave that install with no enabled
 * launcher entry, i.e. the app gone from the home screen after an update. So they were
 * re-pointed at the pack instead, and their meaning is unchanged (frost is still the
 * light one, neon still the glowing one).
 */
internal val APP_ICON_OPTIONS: List<Pair<String, String>> = listOf(
    AppIcon.DEFAULT to "Ночь",
    "accent" to "Лаванда",
    "pack03" to "Фуксия",
    "pack04" to "Лазурь",
    "outline" to "Контур",
    "pack06" to "Аврора",
    "sunset" to "Пламя",
    "pack08" to "Космос",
    "pack09" to "Фарфор",
    "obsidian" to "Хром",
    "frost" to "Иней",
    "neon" to "Неон",
    "pack13" to "Золото",
    "pack14" to "Мираж",
    "pack15" to "Спектр",
    "pack16" to "Графит",
    "pack17" to "Перламутр",
    "pack18" to "Сапфир",
    "pack19" to "Коралл",
    "pack20" to "Серебро",
    "pack21" to "Небо",
    "pack23" to "Сирень",
    "black" to "Тьма",
)

/**
 * Variant key → the full-bleed artwork used for the picker tile.
 *
 * The finished square icon, not the adaptive foreground: the tile is a circle the size of
 * a launcher icon, so what it should show is what a launcher shows, the artwork
 * as drawn, not the 108dp canvas with its fabricated ring.
 */
internal fun appIconPreview(key: String): Int = when (key) {
    "accent" -> R.drawable.ic_pack_01
    "pack03" -> R.drawable.ic_pack_03
    "pack04" -> R.drawable.ic_pack_04
    "outline" -> R.drawable.ic_pack_05
    "pack06" -> R.drawable.ic_pack_06
    "sunset" -> R.drawable.ic_pack_07
    "pack08" -> R.drawable.ic_pack_08
    "pack09" -> R.drawable.ic_pack_09
    "obsidian" -> R.drawable.ic_pack_10
    "frost" -> R.drawable.ic_pack_11
    "neon" -> R.drawable.ic_pack_12
    "pack13" -> R.drawable.ic_pack_13
    "pack14" -> R.drawable.ic_pack_14
    "pack15" -> R.drawable.ic_pack_15
    "pack16" -> R.drawable.ic_pack_16
    "pack17" -> R.drawable.ic_pack_17
    "pack18" -> R.drawable.ic_pack_18
    "pack19" -> R.drawable.ic_pack_19
    "pack20" -> R.drawable.ic_pack_20
    "pack21" -> R.drawable.ic_pack_21
    "pack23" -> R.drawable.ic_pack_23
    "black" -> R.drawable.ic_pack_24
    else -> R.drawable.ic_pack_02
}

/**
 * Battery-optimisation exemption, a stability control, not a preference.
 *
 * Android's Doze and the vendor layers on top of it (HyperOS/MIUI especially) will freeze
 * or kill a background app's process even when it holds a foreground service, and for a
 * VPN that reads as the tunnel dropping by itself after a while. The exemption is the
 * documented way out, and only the user can grant it.
 *
 * The row states the current state rather than being a toggle, because the answer is
 * owned by the system: there is no API to revoke it, so it can only ever offer to open
 * the request. Re-read on every resume so it stops nagging the moment it is granted.
 */
@Composable
private fun BatteryOptimisationItem() {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var exempt by remember { mutableStateOf(isIgnoringBatteryOptimisations(context)) }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) exempt = isIgnoringBatteryOptimisations(context)
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    LeanNavItem(
        icon = LeanIcon.Power,
        tint = if (exempt) LeanColors.Accent else LeanColors.Ember,
        title = tr("Работа в фоне"),
        subtitle = if (exempt) {
            tr("Система не будет усыплять Lean")
        } else {
            tr("Система может усыпить Lean и оборвать туннель")
        },
        value = if (exempt) tr("Разрешено") else tr("Настроить"),
    ) {
        if (!exempt) requestIgnoreBatteryOptimisations(context)
    }
}

/**
 * The vendor guide, kept beside the exemption row because the exemption is only half of
 * it. Xiaomi, Huawei, Samsung and the rest each add their own killer with its own buried
 * switch, and no API can either read or request those, dontkillmyapp.com is the
 * maintained walkthrough. It stays visible after the exemption is granted (the home-screen
 * warning does not), since that is when the remaining vendor steps still bite.
 */
@Composable
private fun DontKillMyAppItem() {
    val context = LocalContext.current
    LeanNavItem(
        icon = LeanIcon.Shield,
        tint = LeanColors.TextSecondary,
        title = tr("Как не дать системе убить Lean"),
        subtitle = tr("Инструкция для вашей марки телефона"),
    ) {
        val site = Intent(Intent.ACTION_VIEW, Uri.parse(dontKillMyAppUrl()))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(site) }
    }
}

@Composable
fun ConnectionHub(onBack: () -> Unit, onNavigate: (String) -> Unit) {
    val repo = LeanApp.instance.settings
    val settings by repo.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var showMtuDialog by remember { mutableStateOf(false) }
    var showUtlsDialog by remember { mutableStateOf(false) }
    var showSniDialog by remember { mutableStateOf(false) }
    var showProxyPortDialog by remember { mutableStateOf(false) }

    HubScaffold(tr("Соединение"), onBack) {
        // First, because it decides what every setting under it even applies to: with no
        // TUN there is nothing to route, split-tunnel or kill-switch.
        LeanSectionLabel(tr("Режим работы"))
        LeanGroup {
            val modes = listOf(
                SettingsDefaults.SERVICE_MODE_VPN,
                SettingsDefaults.SERVICE_MODE_PROXY,
                SettingsDefaults.SERVICE_MODE_VPN_PROXY,
            )
            Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
                SegmentedControl(
                    options = listOf("TUN", tr("Прокси"), "TUN + " + tr("Прокси")),
                    selectedIndex = modes.indexOf(settings.serviceMode).coerceAtLeast(0),
                    // Named: `modifier` is the last parameter, so a trailing lambda binds
                    // to it instead of to onSelect.
                    onSelect = { index -> scope.launch { repo.setServiceMode(modes[index]) } },
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    when (settings.serviceMode) {
                        SettingsDefaults.SERVICE_MODE_PROXY ->
                            tr("Локальный прокси без системного туннеля: пойдут только приложения, которым вы его укажете.")
                        SettingsDefaults.SERVICE_MODE_VPN_PROXY ->
                            tr("Системный туннель плюс локальный прокси для приложений, умеющих его принимать.")
                        else ->
                            tr("Системный туннель: через VPN идёт весь трафик устройства.")
                    },
                    color = LeanColors.TextSecondary,
                    style = MaterialTheme.typography.labelMedium,
                )
            }
            // Only under the modes that actually open a listener, a port and a LAN switch
            // mean nothing in plain TUN.
            if (settings.serviceMode != SettingsDefaults.SERVICE_MODE_VPN) {
                LeanDivider()
                LeanNavItem(
                    LeanIcon.Cable,
                    LeanColors.Blue,
                    tr("Порт прокси"),
                    tr("SOCKS5 и HTTP на одном порту"),
                    value = settings.proxyPort.toString(),
                ) { showProxyPortDialog = true }
                LeanDivider()
                LeanToggleItem(
                    LeanIcon.Lan,
                    if (settings.proxyAllowLan) LeanColors.Ember else LeanColors.TextSecondary,
                    tr("Доступ из локальной сети"),
                    if (settings.proxyAllowLan) {
                        tr("Прокси открыт всем в этой сети Wi-Fi")
                    } else {
                        tr("Только с этого устройства (127.0.0.1)")
                    },
                    settings.proxyAllowLan,
                ) { on ->
                    scope.launch { repo.setProxyAllowLan(on) }
                }
            }
        }
        LeanSectionLabel(tr("Запуск"))
        LeanGroup {
            LeanToggleItem(LeanIcon.Power, LeanColors.Accent, tr("Автоподключение"), tr("Подключаться при запуске приложения"), settings.autoConnect) { on ->
                scope.launch { repo.setAutoConnect(on) }
            }
            LeanDivider()
            LeanToggleItem(
                LeanIcon.Refresh,
                LeanColors.Ember,
                tr("Автопереключение") + " · Beta",
                tr("Переподключаться при обрыве и переходить на рабочий сервер"),
                settings.autoFailover,
            ) { on ->
                scope.launch { repo.setAutoFailover(on) }
            }
            LeanDivider()
            BatteryOptimisationItem()
            LeanDivider()
            // Worded as what it does to the tunnel, not as a battery promise.
            // Pausing the core holds new connections until the device wakes, so while the
            // system is dozing nothing connects and nothing arrives. Off by default.
            LeanToggleItem(
                LeanIcon.Power,
                LeanColors.Violet,
                tr("Спать в глубоком сне"),
                tr("Меньше расход, но пока телефон спит туннель не пропускает трафик"),
                settings.dozePause,
            ) { on ->
                scope.launch { repo.setDozePause(on) }
            }
            LeanDivider()
            DontKillMyAppItem()
        }
        LeanSectionLabel(tr("Маршрутизация"))
        LeanGroup {
            LeanToggleItem(LeanIcon.Globe, LeanColors.Accent, tr("Глобальный режим"), tr("Весь трафик через прокси"), settings.routingMode == RoutingMode.GLOBAL) { on ->
                scope.launch { repo.setRoutingMode(if (on) RoutingMode.GLOBAL else RoutingMode.RULE) }
            }
            LeanDivider()
            LeanToggleItem(LeanIcon.Lan, LeanColors.Blue, tr("Обход локальной сети"), tr("Приватные адреса — напрямую"), settings.bypassLan) { on ->
                scope.launch { repo.setBypassLan(on) }
            }
            LeanDivider()
            LeanToggleItem(LeanIcon.Hub, LeanColors.Violet, "IPv6", tr("Включить IPv6 в туннеле"), settings.ipv6) { on ->
                scope.launch { repo.setIpv6(on) }
            }
            LeanDivider()
            LeanNavItem(LeanIcon.Globe, LeanColors.Blue, "DNS", tr("Серверы имён"), value = dnsLabel(settings.remoteDns)) { onNavigate(Routes.DNS) }
        }
        LeanSectionLabel(tr("Туннель"))
        LeanGroup {
            LeanToggleItem(LeanIcon.Shield, LeanColors.EmberRed, "Kill-switch", tr("Блокировать трафик без VPN"), settings.killSwitch) { on ->
                scope.launch { repo.setKillSwitch(on) }
            }
            LeanDivider()
            LeanToggleItem(LeanIcon.Split, LeanColors.Ember, tr("Фрагментация (DPI)"), tr("Дробление TLS-пакетов против DPI"), settings.fragment) { on ->
                scope.launch { repo.setFragment(on) }
            }
            LeanDivider()
            LeanToggleItem(LeanIcon.Layers, LeanColors.Blue, tr("Мультиплексирование"), tr("Mux для совместимых серверов"), settings.mux) { on ->
                scope.launch { repo.setMux(on) }
            }
            LeanDivider()
            LeanNavItem(LeanIcon.Hub, LeanColors.Accent, tr("Тип IP"), value = ipStrategyLabel(settings.ipStrategy)) { onNavigate(Routes.IP_TYPE) }
            LeanDivider()
            LeanNavItem(LeanIcon.Layers, LeanColors.Violet, tr("Сетевой стек"), tr("Как туннель обрабатывает пакеты"), value = tunStackLabel(settings.tunStack)) { onNavigate(Routes.TUN_STACK) }
            LeanDivider()
            LeanNavItem(LeanIcon.Apps, LeanColors.Blue, tr("Раздельный туннель"), tr("Приложения в обход/через VPN"), value = perAppLabel(settings)) { onNavigate(Routes.PER_APP) }
            LeanDivider()
            LeanToggleItem(LeanIcon.Globe, LeanColors.Accent, tr("Российские сайты — напрямую"), tr("GeoIP/GeoSite РФ мимо VPN (быстрее, меньше нагрузки)"), settings.ruDirect) { on ->
                scope.launch { repo.setRuDirect(on) }
            }
            LeanDivider()
            LeanNavItem(LeanIcon.Globe, LeanColors.Blue, tr("Свои rule-set"), tr("Кастомные geoip/geosite (.srs) — напрямую"), value = customRuleSetsLabel(settings)) { onNavigate(Routes.RULE_SETS) }
            LeanDivider()
            LeanNavItem(LeanIcon.Hub, LeanColors.Violet, "WireGuard MTU", tr("Меньше — надёжнее на мобильных (меньше фрагментации)"), value = settings.wgMtu.toString()) { showMtuDialog = true }
        }
        LeanSectionLabel(tr("Безопасность"))
        LeanGroup {
            LeanToggleItem(LeanIcon.Shield, LeanColors.Ember, tr("Небезопасный TLS"), tr("Для маскировки SNI"), settings.allowInsecure) { on ->
                scope.launch { repo.setAllowInsecure(on) }
            }
        }
        // Client-side traffic masking, works at the core level, no server changes needed.
        LeanSectionLabel(tr("Маскировка трафика"))
        LeanGroup {
            LeanNavItem(LeanIcon.Globe, LeanColors.Ember, tr("Подмена SNI"), tr("Камуфляж под разрешённый домен (для белых списков)"), value = settings.sniOverride.ifEmpty { tr("выкл") }) { showSniDialog = true }
            LeanDivider()
            LeanNavItem(LeanIcon.Shield, LeanColors.Blue, tr("Отпечаток TLS"), tr("Имитация ClientHello браузера (uTLS)"),
                value = if (settings.utlsFingerprint == SingBoxConfig.UTLS_OFF) tr("Выключено") else settings.utlsFingerprint,
            ) { showUtlsDialog = true }
            LeanDivider()
            LeanToggleItem(LeanIcon.Power, LeanColors.Violet, "TCP Fast Open", tr("Выключите, если конфиг не подключается (как в v2rayNG)"), settings.tcpFastOpen) { on ->
                scope.launch { repo.setTcpFastOpen(on) }
            }
        }
    }
    if (showMtuDialog) {
        AlertDialog(
            onDismissRequest = { showMtuDialog = false },
            title = { Text("WireGuard MTU") },
            text = {
                Column {
                    listOf(1280, 1380, 1408, 1420, 1500).forEach { mtu ->
                        RadioRow(mtu.toString(), settings.wgMtu == mtu) {
                            scope.launch { repo.setWgMtu(mtu) }
                            showMtuDialog = false
                        }
                    }
                    Text(
                        tr("MTU WireGuard-туннеля. 1280 не фрагментируется ни в одной сети (надёжно на мобильных); выше — быстрее на стабильной. Применяется при следующем подключении."),
                        color = LeanColors.TextSecondary,
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(top = 8.dp, start = 4.dp),
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showMtuDialog = false }) { Text(tr("Отмена"), color = LeanColors.TextSecondary) }
            },
        )
    }
    if (showProxyPortDialog) {
        // A short list rather than free text: these are the ports every client and
        // browser extension already defaults to, and a typo here is a proxy that silently
        // fails to bind.
        AlertDialog(
            onDismissRequest = { showProxyPortDialog = false },
            title = { Text(tr("Порт прокси")) },
            text = {
                Column {
                    listOf(1080, 2080, 7890, 10808, 20170).forEach { port ->
                        RadioRow(port.toString(), settings.proxyPort == port) {
                            scope.launch { repo.setProxyPort(port) }
                            showProxyPortDialog = false
                        }
                    }
                    Text(
                        tr("Один порт отвечает и по SOCKS5, и по HTTP. Применяется при следующем подключении."),
                        color = LeanColors.TextSecondary,
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(top = 8.dp, start = 4.dp),
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showProxyPortDialog = false }) {
                    Text(tr("Отмена"), color = LeanColors.TextSecondary)
                }
            },
        )
    }
    if (showUtlsDialog) {
        AlertDialog(
            onDismissRequest = { showUtlsDialog = false },
            title = { Text(tr("Отпечаток TLS")) },
            text = {
                Column {
                    listOf(SingBoxConfig.UTLS_OFF, "chrome", "firefox", "safari", "ios", "android", "edge", "random", "randomized").forEach { fp ->
                        // "off" is a mode, not a browser, label it so.
                        RadioRow(
                            if (fp == SingBoxConfig.UTLS_OFF) tr("Выключено") else fp,
                            settings.utlsFingerprint == fp,
                        ) {
                            scope.launch { repo.setUtlsFingerprint(fp) }
                            showUtlsDialog = false
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showUtlsDialog = false }) { Text(tr("Отмена"), color = LeanColors.TextSecondary) }
            },
        )
    }
    if (showSniDialog) {
        var sni by remember { mutableStateOf(settings.sniOverride) }
        AlertDialog(
            onDismissRequest = { showSniDialog = false },
            title = { Text(tr("Подмена SNI")) },
            text = {
                Column {
                    OutlinedTextField(
                        value = sni,
                        onValueChange = { sni = it },
                        singleLine = true,
                        placeholder = { Text("avito.st") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(Modifier.padding(top = 6.dp)) {
                        listOf("avito.st", "vk.com", "").forEach { preset ->
                            TextButton(onClick = { sni = preset }) {
                                Text(if (preset.isEmpty()) tr("выкл") else preset)
                            }
                        }
                    }
                    Text(
                        tr("Подменяет SNI у всех TLS-узлов (кроме Reality) на этот домен — камуфляж под разрешённый сайт для белых списков. Нужен «Небезопасный TLS». Пусто = выкл. Применяется при следующем подключении."),
                        color = LeanColors.TextSecondary,
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(top = 8.dp, start = 4.dp),
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { scope.launch { repo.setSniOverride(sni) }; showSniDialog = false }) { Text("OK") }
            },
            dismissButton = {
                TextButton(onClick = { showSniDialog = false }) { Text(tr("Отмена"), color = LeanColors.TextSecondary) }
            },
        )
    }
}

@Composable
fun ProviderHub(onBack: () -> Unit, onNavigate: (String) -> Unit) {
    val repo = LeanApp.instance.settings
    val settings by repo.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val clipboard = rememberLeanClipboard()
    val context = LocalContext.current
    var showBgDialog by remember { mutableStateOf(false) }
    var showUaDialog by remember { mutableStateOf(false) }
    HubScaffold(tr("Провайдер и пинг"), onBack) {
        Spacer(Modifier.height(8.dp))
        LeanGroup {
            LeanToggleItem(LeanIcon.Refresh, LeanColors.Accent, tr("Авто-обновление подписки"), tr("Только при открытии приложения, не в фоне"), settings.autoUpdate) { on ->
                scope.launch { repo.setAutoUpdate(on) }
            }
            LeanDivider()
            LeanNavItem(
                LeanIcon.Cloud, LeanColors.Violet, tr("Фоновое обновление"),
                tr("Обновлять подписки по расписанию"),
                value = bgRefreshLabel(settings.bgRefreshMinutes),
            ) { showBgDialog = true }
            LeanDivider()
            LeanToggleItem(LeanIcon.Shield, LeanColors.Blue, tr("Отправлять HWID"), tr("Заголовок x-hwid в запросах подписки"), settings.sendHwid) { on ->
                scope.launch { repo.setSendHwid(on) }
            }
            LeanDivider()
            LeanNavItem(LeanIcon.Speed, LeanColors.Accent, tr("Настройки пинга"), value = settings.pingProtocol) { onNavigate(Routes.PING) }
        }

        // User-Agent picker, near the bottom of the hub. Panels gate the returned
        // server list by UA, so let the user spoof it from a preset list. "User-Agent"
        // is a header name, kept literal, no tr().
        LeanSectionLabel("User-Agent")
        LeanGroup {
            LeanNavItem(
                LeanIcon.Globe, LeanColors.Ember, "User-Agent",
                subtitle = uaDisplay(settings.userAgent, context),
                value = uaPresetLabel(settings.userAgent),
            ) { showUaDialog = true }
            LeanDivider()
            // Tap to copy the active (resolved) UA string verbatim.
            LeanNavItem(LeanIcon.Hub, LeanColors.Blue, tr("Скопировать значение"), value = tr("копировать")) {
                clipboard.copy(uaDisplay(settings.userAgent, context))
                Toast.makeText(context, tr("Скопировано"), Toast.LENGTH_SHORT).show()
            }
        }
        Text(
            tr("Панели отдают разный список серверов в зависимости от User-Agent клиента. Если часть серверов не появилась (например Hysteria2), смените UA — например на v2rayNG. По умолчанию «Lean» — для панелей, которые его распознают."),
            color = LeanColors.TextTertiary,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(start = 4.dp, top = 9.dp),
        )
    }
    if (showUaDialog) {
        AlertDialog(
            onDismissRequest = { showUaDialog = false },
            title = { Text("User-Agent") },
            text = {
                Column {
                    UA_PRESETS.forEach { (label, ua) ->
                        RadioRow(label, settings.userAgent == ua) {
                            scope.launch { repo.setUserAgent(ua) }
                            showUaDialog = false
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showUaDialog = false }) { Text(tr("Отмена"), color = LeanColors.TextSecondary) }
            },
        )
    }
    if (showBgDialog) {
        AlertDialog(
            onDismissRequest = { showBgDialog = false },
            title = { Text(tr("Фоновое обновление")) },
            text = {
                Column {
                    BG_REFRESH_OPTIONS.forEach { min ->
                        RadioRow(bgRefreshLabel(min), settings.bgRefreshMinutes == min) {
                            scope.launch { repo.setBgRefreshMinutes(min) }
                            showBgDialog = false
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showBgDialog = false }) { Text(tr("Отмена"), color = LeanColors.TextSecondary) }
            },
        )
    }
}

/** Allowed WorkManager refresh intervals (minutes); 0 = Off (worker cancelled). */
private val BG_REFRESH_OPTIONS = listOf(0, 30, 60, 120, 360, 720, 1440)

/**
 * Subscription User-Agent spoof presets (display label → UA string).
 *
 * A panel gates both the format and the contents of the server list by UA: "v2rayNG"
 * unlocks the full Xray-JSON list including Hysteria2, and "Lean" is the default for
 * panels that recognise Lean.
 *
 * Every string is copied verbatim from a real client rather than composed from a version
 * number: gating matches what those clients actually send, down to the case and the
 * separators, so a plausible-looking invention unlocks nothing.
 *
 * Labels are product names, kept literal, no tr().
 */
private val UA_PRESETS: List<Pair<String, String>> = listOf(
    "Lean" to "",
    // Happ and Incy are what a panel's request log is mostly made of, so they come first.
    // The Happ token resolves (ClientSpoof) to Happ's real UA + a Happ-shaped hwid +
    // X-Bundle-ID/X-API-Version; each platform carries its own version and id length.
    "Happ" to "happ:Android",
    "Happ · iOS" to "happ:ios",
    "Happ · Windows" to "happ:Windows",
    // Verbatim from a real client. An invented string like "Incy/1.0.0" identifies the
    // preset as ours to any panel that has seen the genuine one.
    "Incy" to "INCY/3.4.8/android Dalvik/2.1.0",
    "v2rayNG" to "v2rayNG/1.9.5",
    "v2RayTun" to "v2raytun",
    "Throne" to "Throne/1.1.2",
    // NekoBox and Karing both announce a whole stack rather than a name, and panels match
    // on the substrings inside it, so these are pasted whole, semicolons and all.
    "NekoBox" to "v2ray;sing-box 1.13.0;NekoBox/Android/1.4.1 (Prefer ClashMeta Format)",
    "Karing" to "Karing/1.2.23.2606 platform/android;mihomo/1.19.28;clash-verge;FLClash;" +
        "ClashMeta;v2ray;sing-box 1.13.0;NekoBox/Android/1.4.1 (Prefer ClashMeta Format);" +
        "HiddifyNext",
    "Hiddify" to "Hiddify/2.0.0",
    "sing-box" to "sing-box/1.13.0",
)

/** Trailing-pill label for the active UA: the preset's short name, else "своё" (custom). */
internal fun uaPresetLabel(ua: String): String =
    UA_PRESETS.firstOrNull { it.second == ua }?.first ?: tr("своё")

/** Human-readable wire UA for a stored preset token (Happ/v2raytun tokens resolve to real UAs). */
internal fun uaDisplay(token: String, context: android.content.Context): String =
    ClientSpoof.resolveUa(token, "Lean/${BuildConfig.VERSION_NAME}", context)

internal fun bgRefreshLabel(minutes: Int): String = when (minutes) {
    0 -> tr("Выкл")
    30 -> tr("30 мин")
    60 -> tr("1 ч")
    120 -> tr("2 ч")
    360 -> tr("6 ч")
    720 -> tr("12 ч")
    1440 -> tr("24 ч")
    else -> tr("%d мин").format(minutes)
}

@Composable
fun AboutHub(onBack: () -> Unit, onNavigate: (String) -> Unit) {
    val repo = LeanApp.instance.settings
    val settings by repo.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var checkingUpdate by remember { mutableStateOf(false) }
    var updateInfo by remember { mutableStateOf<UpdateChecker.UpdateInfo?>(null) }
    HubScaffold(tr("О Lean"), onBack) {
        Spacer(Modifier.height(8.dp))
        LeanGroup {
            LeanNavItem(LeanIcon.Info, LeanColors.Accent, tr("О программе"), subtitle = tr(SETTINGS_RUNTIME_VERSION).format(BuildConfig.VERSION_NAME)) { onNavigate(Routes.ABOUT) }
            LeanDivider()
            LeanNavItem(LeanIcon.Cloud, LeanColors.Blue, tr("Резервная копия"), subtitle = tr("Экспорт и импорт данных")) { onNavigate(Routes.BACKUP) }
            LeanDivider()
            LeanNavItem(LeanIcon.Layers, LeanColors.TextSecondary, tr("Логи туннеля")) { onNavigate(Routes.LOGS) }
            LeanDivider()
            LeanToggleItem(
                LeanIcon.Shield, LeanColors.Violet,
                tr("Отчёты о сбоях"),
                tr(CrashReporter.CONSENT_DISCLOSURE),
                settings.crashReporting,
            ) { on -> scope.launch { repo.setCrashReporting(on) } }
            LeanDivider()
            LeanNavItem(
                LeanIcon.Info,
                LeanColors.Ember,
                tr(CrashReporter.PUBLIC_ISSUES_LABEL),
                subtitle = tr("Открыть список известных ошибок и сообщить о новой"),
            ) {
                context.openUrl(CrashReporter.PUBLIC_ISSUES_URL)
            }
        }
        // App-update check (GitHub Releases of the public repo), manual + on-launch.
        LeanSectionLabel(tr("Обновления"))
        LeanGroup {
            LeanNavItem(
                LeanIcon.Refresh, LeanColors.Accent,
                tr("Проверить обновления"),
                value = if (checkingUpdate) tr("Проверка…") else currentVersionPill(BuildConfig.VERSION_NAME),
            ) {
                if (!checkingUpdate) {
                    checkingUpdate = true
                    scope.launch {
                        val result = UpdateChecker.checkManually(BuildConfig.VERSION_NAME)
                        checkingUpdate = false
                        when (result) {
                            is UpdateChecker.CheckResult.Available -> updateInfo = result.info
                            UpdateChecker.CheckResult.UpToDate ->
                                Toast.makeText(context, tr("У вас последняя версия"), Toast.LENGTH_SHORT).show()
                            // Distinguished: GitHub is routinely unreachable from
                            // RU without the tunnel, and answering "you have the latest
                            // version" when nothing was actually checked is a lie the user
                            // would act on.
                            UpdateChecker.CheckResult.Failed ->
                                Toast.makeText(context, tr("Не удалось проверить обновления — GitHub недоступен"), Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }
            LeanDivider()
            LeanToggleItem(
                LeanIcon.Cloud, LeanColors.Blue,
                tr("Проверять при запуске"),
                tr("Уведомлять о новой версии приложения"),
                settings.checkAppUpdates,
            ) { on -> scope.launch { repo.setCheckAppUpdates(on) } }
        }
        // Support always points at the Lean developer's own channel, never the
        // imported subscription's provider URL (that linked users to whatever VPN
        // panel the .conf came from instead of to Lean's author).
        Spacer(Modifier.height(14.dp))
        SupportButton(LEAN_SUPPORT_URL)
    }
    updateInfo?.let { info ->
        AlertDialog(
            onDismissRequest = { updateInfo = null },
            title = { Text(tr("Доступно обновление %s").format(info.latestVersion)) },
            text = { Text(tr("Установлена %s. Скачать новую версию?").format(BuildConfig.VERSION_NAME)) },
            confirmButton = {
                TextButton(onClick = {
                    context.openUrl(info.apkUrl ?: info.releaseUrl)
                    updateInfo = null
                }) { Text(tr("Скачать")) }
            },
            dismissButton = { TextButton(onClick = { updateInfo = null }) { Text(tr("Позже")) } },
        )
    }
}

/** The installed version rendered as a trailing pill. */
private fun currentVersionPill(version: String): String = "v$version"

/** Lean's own support contact, the developer's Telegram, not any subscription's. */
private const val LEAN_SUPPORT_URL = "https://t.me/th3_nek1t"

@Composable
private fun SupportButton(url: String) {
    val context = LocalContext.current
    OutlinedButton(
        onClick = { context.openUrl(url) },
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = LeanColors.TextSecondary),
    ) {
        LeanIconImage(LeanIcon.Support, tint = LeanColors.TextSecondary, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(tr("Поддержка"))
    }
}

/**
 * One row of the «Протокол» picker.
 *
 * [label] is not translated, every one of these is a protocol name
 * (ICMP/TCP/GET/HEAD) or the reference client's own term for the test ("URL Test", the
 * name NekoBox uses for this exact measurement). [description] is the translatable half.
 *
 * [value] is what gets persisted and what [Pinger.measure]'s `when` dispatches on, kept
 * separate from [label] because "URL Test" carries a space that would never match
 * [Pinger.URL_TEST_PROTOCOL].
 *
 * [tint] is a lambda, not a Color: [LeanColors] resolves against the live theme, so a
 * value captured at list-construction time would freeze the accent from whenever this
 * file's class was first loaded and stop following «Оформление».
 */
private class PingProtocolOption(
    val value: String,
    val label: String,
    val description: String,
    val icon: LeanIcon,
    val tint: @Composable () -> Color,
)

/** Best first: URL Test is the default and the one to reach for unless it is too slow. */
private val PING_PROTOCOLS = listOf(
    PingProtocolOption(
        value = Pinger.URL_TEST_PROTOCOL,
        label = "URL Test",
        description = "Проверяет сервер его собственным протоколом — как настоящее подключение. Точнее всех, но дольше",
        icon = LeanIcon.Shield,
        tint = { LeanColors.Accent },
    ),
    PingProtocolOption(
        value = "TCP",
        label = "TCP",
        description = "Проверяет, отвечает ли порт сервера. Быстро, но не проверяет сам протокол",
        icon = LeanIcon.Route,
        tint = { LeanColors.Blue },
    ),
    PingProtocolOption(
        value = "ICMP",
        label = "ICMP",
        description = "Обычный ping до сервера. Часть сетей его блокирует",
        icon = LeanIcon.Pulse,
        tint = { LeanColors.Violet },
    ),
    PingProtocolOption(
        value = "GET",
        label = "GET",
        description = "Запрос по тестовому URL ниже ЧЕРЕЗ этот сервер. Если так проверить не вышло — запрос идёт напрямую, и число уже про интернет телефона",
        icon = LeanIcon.Globe,
        tint = { LeanColors.Ember },
    ),
    PingProtocolOption(
        // Same probe as GET, only the HTTP verb differs, so the same icon.
        value = "HEAD",
        label = "HEAD",
        description = "То же, что GET, но без загрузки ответа",
        icon = LeanIcon.Globe,
        tint = { LeanColors.Ember },
    ),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PingScreen(onBack: () -> Unit) {
    val repo = LeanApp.instance.settings
    val settings by repo.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val timeouts = listOf(1500, 3000, 5000, 8000)
    var urlEdit by remember { mutableStateOf<String?>(null) }
    val url = urlEdit ?: settings.pingUrl
    // Keep the local edit-shadow up to date on every keystroke (cursor stability),
    // but only persist a value that can actually serve as a ping URL, never a
    // blank or half-typed string, which would silently break GET/HEAD pings.
    fun setUrl(v: String) { urlEdit = v; if (v.startsWith("http")) scope.launch { repo.setPingUrl(v.trim()) } }

    HubScaffold(tr("Настройки пинга"), onBack) {
        LeanSectionLabel(tr("Протокол"))
        // A column of rows rather than a SegmentedControl: five options is already past
        // what a horizontal strip holds (it switches to a tighter type at >3 and then
        // ellipsises, "URL Test" was landing as "URL T…" on narrow screens), and these
        // options are not self-explanatory from a 4-letter label. A row per protocol
        // gives each one the sentence it needs, which one shared hint line cannot do for
        // five options at once.
        LeanGroup {
            PING_PROTOCOLS.forEachIndexed { i, p ->
                if (i > 0) LeanDivider()
                LeanChoiceItem(
                    icon = p.icon,
                    tint = p.tint(),
                    title = p.label,
                    subtitle = tr(p.description),
                    selected = settings.pingProtocol.equals(p.value, ignoreCase = true),
                    onSelect = { scope.launch { repo.setPingProtocol(p.value) } },
                )
            }
        }

        LeanSectionLabel(tr("Тестовый URL"))
        OutlinedTextField(
            value = url,
            onValueChange = { setUrl(it) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            leadingIcon = { LeanIconImage(LeanIcon.Globe, tint = LeanColors.TextSecondary, modifier = Modifier.size(19.dp)) },
            placeholder = { Text("https://…", color = LeanColors.TextTertiary) },
            shape = LeanCorner.Input,
        )
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            UrlPreset("gstatic") { setUrl("https://www.gstatic.com/generate_204") }
            UrlPreset("cloudflare") { setUrl("https://cloudflare.com/cdn-cgi/trace") }
            UrlPreset("apple") { setUrl("https://www.apple.com/library/test/success.html") }
        }

        LeanSectionLabel(tr("Параметры"))
        LeanGroup {
            LeanNavItem(LeanIcon.Speed, LeanColors.Accent, tr("Таймаут"), value = tr("%d мс").format(settings.pingTimeoutMs)) {
                val next = timeouts[(timeouts.indexOf(settings.pingTimeoutMs).coerceAtLeast(0) + 1) % timeouts.size]
                scope.launch { repo.setPingTimeout(next) }
            }
        }

        LeanSectionLabel(tr("Автопинг"))
        LeanGroup {
            LeanToggleItem(LeanIcon.Power, LeanColors.Accent, tr("Пинг при запуске"), tr("Проверять все серверы при старте приложения"), settings.pingOnLaunch) { on ->
                scope.launch { repo.setPingOnLaunch(on) }
            }
            LeanDivider()
            LeanToggleItem(LeanIcon.Refresh, LeanColors.Blue, tr("Пинг после обновления"), tr("Проверять серверы после обновления подписки"), settings.pingOnUpdate) { on ->
                scope.launch { repo.setPingOnUpdate(on) }
            }
        }
    }
}

@Composable
private fun UrlPreset(label: String, onClick: () -> Unit) {
    SuggestionChip(onClick = onClick, label = { Text(label) })
}

private fun dnsLabel(spec: String): String =
    spec.substringAfter("://").substringBefore('/').substringBefore(':').ifBlank { "—" }

private fun perAppLabel(s: Settings): String = when (s.perAppMode) {
    com.th3web.lean.data.PerAppMode.OFF -> tr("Выкл")
    com.th3web.lean.data.PerAppMode.INCLUDE -> tr("Только %d").format(s.perAppPackages.size)
    com.th3web.lean.data.PerAppMode.EXCLUDE -> tr("Кроме %d").format(s.perAppPackages.size)
}

private fun ipStrategyLabel(s: String): String = when (s) {
    "prefer_ipv4" -> tr("IPv4 приор.")
    "prefer_ipv6" -> tr("IPv6 приор.")
    "ipv4_only" -> tr("Только IPv4")
    "ipv6_only" -> tr("Только IPv6")
    else -> tr("Авто")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DnsScreen(onBack: () -> Unit) {
    val repo = LeanApp.instance.settings
    val settings by repo.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var remoteEdit by remember { mutableStateOf<String?>(null) }
    var directEdit by remember { mutableStateOf<String?>(null) }
    val remote = remoteEdit ?: settings.remoteDns
    val direct = directEdit ?: settings.directDns
    // Persist only a non-blank remote DNS: a blank/scheme-only spec would emit
    // {type:udp,server:""} and fail the whole config. The edit-shadow still updates
    // every keystroke (cursor stability). SingBoxConfig.dnsServer also guards this,
    // so this is just belt-and-suspenders to avoid persisting a broken value.
    fun setRemote(v: String) { remoteEdit = v; if (v.isNotBlank()) scope.launch { repo.setRemoteDns(v.trim()) } }
    fun setDirect(v: String) { directEdit = v; scope.launch { repo.setDirectDns(v) } }

    HubScaffold("DNS", onBack) {
        LeanSectionLabel(tr("Удалённый DNS (через прокси)"))
        DnsField(remote, LeanIcon.Globe) { setRemote(it) }
        Text(
            tr("Резолвит домены через туннель — без утечки DNS. Напр. https://1.1.1.1/dns-query, tls://8.8.8.8"),
            color = LeanColors.TextTertiary, style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(start = 4.dp, top = 9.dp),
        )

        LeanSectionLabel(tr("Прямой DNS (бутстрап)"))
        DnsField(direct, LeanIcon.Lan) { setDirect(it) }
        Text(
            tr("Резолвит адрес самого сервера до подъёма туннеля. Обычно 1.1.1.1 или DNS провайдера. Если UDP/53 режется — впишите DoH на IP: https://1.1.1.1/dns-query."),
            color = LeanColors.TextTertiary, style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(start = 4.dp, top = 9.dp),
        )
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            UrlPreset("Cloudflare") { setDirect("1.1.1.1") }
            UrlPreset("Google") { setDirect("8.8.8.8") }
            UrlPreset("DoH") { setDirect("https://1.1.1.1/dns-query") }
        }

        LeanSectionLabel(tr("Пресеты (удалённый)"))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            UrlPreset("Cloudflare") { setRemote("https://1.1.1.1/dns-query") }
            UrlPreset("Google") { setRemote("https://8.8.8.8/dns-query") }
            UrlPreset("Quad9") { setRemote("https://9.9.9.9/dns-query") }
        }

        // Only plain WireGuard is governed by this; AmneziaWG runs on its own native core
        // and never reaches this DNS module, and the other protocols never resolve
        // destinations locally at all. Said plainly in the hint so the switch does not look
        // broken to someone testing it on a Reality server.
        LeanSectionLabel(tr("WireGuard"))
        LeanGroup {
            LeanToggleItem(
                LeanIcon.Shield,
                LeanColors.Accent,
                tr("Резолвить домены через туннель"),
                tr("WireGuard резолвит адреса сам, поэтому без этого список сайтов уходит прямому DNS с вашего IP. Не влияет на AmneziaWG и остальные протоколы."),
                settings.wgDnsThroughTunnel,
            ) { on -> scope.launch { repo.setWgDnsThroughTunnel(on) } }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DnsField(value: String, icon: LeanIcon, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        leadingIcon = { LeanIconImage(icon, tint = LeanColors.TextSecondary, modifier = Modifier.size(19.dp)) },
        placeholder = { Text("local / 1.1.1.1 / https://… / tls://…", color = LeanColors.TextTertiary) },
        shape = LeanCorner.Input,
    )
}

private fun customRuleSetsLabel(s: Settings): String {
    val n = s.customRuleSets.count { it.trim().startsWith("http") }
    return if (n == 0) tr("выкл") else tr("%d шт.").format(n)
}

/**
 * User-supplied custom geoip/geosite rule-set URLs (one per line), "add your own
 * geoip" like Incy/Happ. Each real http(s) line is persisted to
 * [Settings.customRuleSets] and routed direct by [SingBoxConfig.route]. Mirrors
 * [DnsScreen]'s nullable edit-shadow so the cursor stays stable while typing.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RuleSetsScreen(onBack: () -> Unit) {
    val repo = LeanApp.instance.settings
    val settings by repo.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var edit by remember { mutableStateOf<String?>(null) }
    val text = edit ?: settings.customRuleSets.joinToString("\n")
    fun save(v: String) {
        edit = v
        val urls = v.lines().map { it.trim() }.filter { it.startsWith("http") }
        scope.launch { repo.setCustomRuleSets(urls) }
    }
    HubScaffold(tr("Свои rule-set"), onBack) {
        LeanSectionLabel(tr("URL наборов geoip / geosite (.srs)"))
        OutlinedTextField(
            value = text,
            onValueChange = { save(it) },
            modifier = Modifier.fillMaxWidth(),
            minLines = 4,
            leadingIcon = { LeanIconImage(LeanIcon.Globe, tint = LeanColors.TextSecondary, modifier = Modifier.size(19.dp)) },
            placeholder = { Text("https://…/geoip-xx.srs", color = LeanColors.TextTertiary) },
            shape = LeanCorner.Input,
        )
        Text(
            tr("Один URL на строку. Бинарные .srs-наборы идут НАПРЯМУЮ (как встроенные RU-наборы), скачиваются через прокси и кешируются. Работают вместе с «Российские сайты — напрямую» или сами по себе."),
            color = LeanColors.TextTertiary, style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(start = 4.dp, top = 9.dp),
        )
        LeanSectionLabel(tr("Где взять"))
        Text(
            tr("Свежие RU-наборы: github.com/runetfreedom/russia-v2ray-rules-dat (папка sing-box/rule-set-*). Формат — .srs (sing-box binary)."),
            color = LeanColors.TextTertiary, style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(start = 4.dp, top = 4.dp),
        )
    }
}

internal fun tunStackLabel(stack: String): String = when (stack.trim().lowercase()) {
    "system" -> tr("Системный")
    "mixed" -> tr("Смешанный")
    else -> "gVisor"
}

/**
 * Which TCP/IP implementation the tunnel runs on.
 *
 * `gvisor` implements the whole TCP/IP stack in userspace Go. `system` does NAT in
 * userspace and hands each TCP flow to a real local listener, letting the kernel
 * terminate it, less work per packet. `mixed` takes system's TCP and gVisor's UDP.
 *
 * The default stays gVisor as the most compatible; the others are worth reaching for on a
 * phone that is faster on them.
 */
@Composable
fun TunStackScreen(onBack: () -> Unit) {
    val repo = LeanApp.instance.settings
    val settings by repo.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val options = listOf(
        Triple("gvisor", "gVisor", tr("По умолчанию. Весь TCP/IP внутри приложения — самый совместимый вариант.")),
        Triple("system", tr("Системный"), tr("TCP отдаётся ядру системы: меньше работы на пакет, обычно меньше расход батареи.")),
        Triple("mixed", tr("Смешанный"), tr("TCP как в системном, UDP как в gVisor.")),
    )
    HubScaffold(tr("Сетевой стек"), onBack) {
        Spacer(Modifier.height(8.dp))
        Text(
            tr("Влияет на скорость и нагрузку на процессор. Если после смены появятся проблемы с какими-то приложениями — верните gVisor. Применяется при следующем подключении."),
            color = LeanColors.TextTertiary, style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(start = 4.dp, bottom = 10.dp),
        )
        LeanGroup {
            options.forEachIndexed { i, (key, label, hint) ->
                if (i > 0) LeanDivider()
                RadioRow(label, settings.tunStack.trim().lowercase() == key) {
                    scope.launch { repo.setTunStack(key) }
                }
                Text(
                    hint,
                    color = LeanColors.TextTertiary,
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
                )
            }
        }
    }
}

@Composable
fun IpTypeScreen(onBack: () -> Unit) {
    val repo = LeanApp.instance.settings
    val settings by repo.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val options = listOf(
        "auto" to tr("Авто (как в системе)"),
        "prefer_ipv4" to tr("Предпочитать IPv4"),
        "prefer_ipv6" to tr("Предпочитать IPv6"),
        "ipv4_only" to tr("Только IPv4"),
        "ipv6_only" to tr("Только IPv6"),
    )
    HubScaffold(tr("Тип IP"), onBack) {
        Spacer(Modifier.height(8.dp))
        Text(
            tr("Какую версию IP предпочитать при резолвинге доменов в туннеле."),
            color = LeanColors.TextTertiary, style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(start = 4.dp, bottom = 10.dp),
        )
        LeanGroup {
            options.forEachIndexed { i, option ->
                val key = option.first
                val label = option.second
                if (i > 0) LeanDivider()
                RadioRow(label, settings.ipStrategy == key) { scope.launch { repo.setIpStrategy(key) } }
            }
        }
    }
}

@Composable
internal fun RadioRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = LeanColors.TextPrimary, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        RadioButton(selected = selected, onClick = null)
    }
}

internal fun languageLabel(l: String): String = when (l) {
    "ru" -> "Русский"
    "en" -> "English"
    else -> tr("Как в системе")
}

@Composable
fun LanguageScreen(onBack: () -> Unit) {
    val repo = LeanApp.instance.settings
    val settings by repo.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val options = listOf(
        "system" to tr("Как в системе"),
        "ru" to "Русский",
        "en" to "English",
    )
    HubScaffold(tr("Язык"), onBack) {
        Spacer(Modifier.height(8.dp))
        LeanGroup {
            options.forEachIndexed { i, opt ->
                if (i > 0) LeanDivider()
                RadioRow(opt.second, settings.language == opt.first) { scope.launch { repo.setLanguage(opt.first) } }
            }
        }
    }
}

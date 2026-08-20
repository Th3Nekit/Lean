package com.th3web.lean.ui.screen.appearance

import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import com.th3web.lean.LeanApp
import com.th3web.lean.core.AppIcon
import com.th3web.lean.ui.Routes
import com.th3web.lean.ui.components.LeanDivider
import com.th3web.lean.ui.components.LeanGroup
import com.th3web.lean.ui.components.LeanNavItem
import com.th3web.lean.ui.components.LeanSectionLabel
import com.th3web.lean.ui.components.LeanToggleItem
import com.th3web.lean.ui.icons.LeanIcon
import com.th3web.lean.ui.screen.APP_ICON_OPTIONS
import com.th3web.lean.ui.screen.AppearanceHeader
import com.th3web.lean.ui.screen.HubScaffold
import com.th3web.lean.ui.screen.KnobHint
import com.th3web.lean.ui.screen.KnobSegments
import com.th3web.lean.ui.screen.appIconPreview
import com.th3web.lean.ui.screen.languageLabel
import com.th3web.lean.ui.screen.rememberAppearanceEditor
import com.th3web.lean.ui.screen.rememberLook
import com.th3web.lean.ui.theme.LeanColors
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.th3web.lean.data.AppearanceRanges
import com.th3web.lean.data.Settings
import com.th3web.lean.ui.components.LeanSlider
import com.th3web.lean.ui.screen.AppearanceEditor
import com.th3web.lean.ui.theme.BackgroundImage
import com.th3web.lean.ui.tr

/**
 * «Фон и система», the canvas behind everything, the two bars the OS draws, the launcher
 * icon, and the two long-plumbed settings that never had a control.
 */
@Composable
fun AppearanceSystemScreen(onBack: () -> Unit, onNavigate: (String) -> Unit) {
    val repo = LeanApp.instance.settings
    val settings by repo.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val editor = rememberAppearanceEditor(settings)
    val look = rememberLook(settings)

    HubScaffold(
        tr("Фон и система"),
        onBack,
        header = { AppearanceHeader(look, settings.appearancePreview) },
    ) {
        KnobSegments(
            tr("Фон приложения"),
            listOf(tr("Ровный"), tr("Виньетка"), tr("Градиент"), tr("Зерно"), tr("Картинка")),
            BG_STYLES.indexOf(settings.bgStyle).coerceAtLeast(0),
        ) { i -> editor.edit { setBgStyle(BG_STYLES[i]) } }
        KnobHint(tr("«Зерно» — едва заметный шум поверх фона: честное лекарство от полос на AMOLED-матрицах."))

        if (settings.bgStyle == "image") {
            BackgroundImagePicker(settings, editor)
        }

        KnobSegments(
            tr("Системные панели"),
            listOf(tr("Авто"), tr("Светлые значки"), tr("Тёмные значки")),
            SYSBAR_INKS.indexOf(settings.sysbarInk).coerceAtLeast(0),
        ) { i -> editor.edit { setSysbarInk(SYSBAR_INKS[i]) } }
        KnobHint(tr("Цвет значков статус-бара и панели навигации. «Авто» выводит его из темы."))

        Spacer(Modifier.height(14.dp))
        LeanGroup {
            LeanToggleItem(
                LeanIcon.Power, LeanColors.Accent, tr("Экран запуска под тему"),
                tr("Окно запуска красится в цвет вашей темы, а не в один фиксированный"),
                settings.splashTheme,
            ) { on -> editor.edit { setSplashTheme(on) } }
            LeanDivider()
            LeanToggleItem(
                LeanIcon.Speed, LeanColors.Blue, tr("Скорость в шторке"),
                tr("Показывать ↓/↑ в постоянном уведомлении"), settings.showSpeedInNotification,
            ) { on -> scope.launch { repo.setShowSpeedInNotification(on) } }
        }

        LeanSectionLabel(tr("Иконка приложения"))
        // Columns are computed from the width actually available, not fixed. A single Row
        // clipped everything past the screen edge as variants were added, and the fixed
        // four-per-row that replaced it wasted half the width on a phone that fits six.
        // Tiles then take an equal share of the row, so the grid is flush with the content
        // on both sides at any width or font scale.
        BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = 2.dp)) {
            val gap = 12.dp
            val columns = ((maxWidth + gap) / (MinTileSize + gap))
                .toInt()
                .coerceIn(3, APP_ICON_OPTIONS.size)
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                APP_ICON_OPTIONS.chunked(columns).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                        row.forEach { (key, nameRu) ->
                            Box(Modifier.weight(1f)) {
                                AppIconTile(key, tr(nameRu), settings.appIcon == key) {
                                    // Persist first, then re-point the single enabled
                                    // launcher alias (new one first, then the rest off),
                                    // the known-disruptive op: the icon may blink and some
                                    // launchers reset its spot. Run on IO: AppIcon.apply
                                    // makes synchronous PackageManager binder calls and is
                                    // not itself suspend, so it would otherwise run on Main
                                    // after setAppIcon resumes there. Not routed through the
                                    // appearance editor: the launcher icon is not part of a
                                    // saved look.
                                    scope.launch(Dispatchers.IO) {
                                        repo.setAppIcon(key)
                                        AppIcon.apply(context, key)
                                    }
                                }
                            }
                        }
                        // Keeps a short last row aligned with the ones above it instead of
                        // letting its tiles stretch to fill the width.
                        repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
        }
        KnobHint(tr("Лаунчер применит иконку не сразу: ярлык может мигнуть, а его место на рабочем столе — сброситься."))

        Spacer(Modifier.height(14.dp))
        LeanGroup {
            LeanNavItem(
                LeanIcon.Lang, LeanColors.Accent, tr("Язык"),
                value = languageLabel(settings.language),
            ) { onNavigate(Routes.LANGUAGE) }
        }
    }
}

/**
 * One launcher-icon variant as a round plate with its name underneath.
 *
 * The plates are not themed: a launcher icon never follows the in-app look, so
 * showing it in the current accent would promise something the home screen will not do.
 */
@Composable
private fun AppIconTile(key: String, label: String, selected: Boolean, onClick: () -> Unit) {
    val art = appIconPreview(key)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // The artwork fills the tile edge to edge, because that is the icon, the pack
        // ships finished squares, so a launcher shows exactly this under its own mask.
        // Nothing to centre and no plate to show through.
        Image(
            painter = painterResource(art),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(CircleShape)
                .border(
                    width = if (selected) 2.dp else 1.dp,
                    color = if (selected) LeanColors.Accent else LeanColors.Outline,
                    shape = CircleShape,
                ),
        )
        Spacer(Modifier.height(6.dp))
        Text(
            label,
            color = if (selected) LeanColors.Accent else LeanColors.TextSecondary,
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            // Pinned to the tile's width: a label free to grow at «Размер текста» 120
            // would push the row past the screen edge, and a tile is what identifies the
            // variant anyway.
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** Smallest a tile may get before the grid drops a column. */
private val MinTileSize = 52.dp

private val BG_STYLES = listOf("flat", "vignette", "gradient", "grain", "image")
private val SYSBAR_INKS = listOf("auto", "light", "dark")

/**
 * «Картинка», pick a background, and choose how strongly it is scrimmed.
 *
 * The picked image is copied into app-private storage by [BackgroundImage] rather than
 * referenced by its URI: a picker grant does not survive a reboot unless persisted, and
 * the source file can be deleted or moved, both of which would look like the background
 * silently vanishing.
 *
 * The dim slider is not decoration. Everything above the canvas, the tonal surface
 * ladder, the hairlines, the text, is designed against a flat colour; over a bright
 * photo it stops separating. The scrim is what keeps the interface readable, which is
 * why its range is clamped rather than free (see AppearanceRanges.BG_IMAGE_DIM_MIN).
 */
@Composable
private fun BackgroundImagePicker(settings: Settings, editor: AppearanceEditor) {
    val context = LocalContext.current
    var dimDraft by remember { mutableStateOf<Int?>(null) }
    var blurDraft by remember { mutableStateOf<Int?>(null) }
    var satDraft by remember { mutableStateOf<Int?>(null) }
    var zoomDraft by remember { mutableStateOf<Int?>(null) }
    var glassDraft by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(settings.bgImageDim) { dimDraft = null }
    LaunchedEffect(settings.bgImageBlur) { blurDraft = null }
    LaunchedEffect(settings.bgImageSaturation) { satDraft = null }
    LaunchedEffect(settings.bgImageZoom) { zoomDraft = null }
    LaunchedEffect(settings.glassTint) { glassDraft = null }
    var failed by remember { mutableStateOf(false) }
    // Re-read from disk when the screen opens: the process may have been restarted since
    // the image was picked, and the decoded copy lives only in memory.
    LaunchedEffect(Unit) { BackgroundImage.ensureLoaded(context) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) failed = !BackgroundImage.import(context, uri)
    }

    Spacer(Modifier.height(14.dp))
    LeanGroup {
        LeanNavItem(
            LeanIcon.Palette, LeanColors.Accent, tr("Выбрать картинку"),
            subtitle = if (BackgroundImage.exists(context)) {
                tr("Картинка выбрана")
            } else {
                tr("Изображение из галереи")
            },
        ) { picker.launch("image/*") }
        if (BackgroundImage.exists(context)) {
            LeanDivider()
            LeanNavItem(
                LeanIcon.Refresh, LeanColors.TextSecondary, tr("Убрать картинку"),
            ) { BackgroundImage.clear(context) }
        }
    }
    if (failed) {
        KnobHint(tr("Не удалось прочитать изображение — попробуйте другое."))
    }

    LeanSectionLabel(tr("Затемнение"))
    LeanSlider(
        value = dimDraft ?: settings.bgImageDim,
        onValueChange = { v -> dimDraft = v },
        onValueChangeFinished = { v ->
            dimDraft = v
            editor.edit { setBgImageDim(v) }
        },
        range = AppearanceRanges.BG_IMAGE_DIM_MIN..AppearanceRanges.BG_IMAGE_DIM_MAX,
        valueLabel = "${dimDraft ?: settings.bgImageDim}%",
    )
    KnobHint(tr("Насколько картинка приглушена, чтобы текст и границы оставались читаемыми."))

    LeanSectionLabel(tr("Размытие"))
    LeanSlider(
        value = blurDraft ?: settings.bgImageBlur,
        onValueChange = { v -> blurDraft = v },
        onValueChangeFinished = { v ->
            blurDraft = v
            editor.edit { setBgImageBlur(v) }
        },
        range = AppearanceRanges.BG_IMAGE_BLUR_MIN..AppearanceRanges.BG_IMAGE_BLUR_MAX,
        valueLabel = "${blurDraft ?: settings.bgImageBlur}%",
    )
    KnobHint(tr("Размытая картинка меньше спорит с интерфейсом за внимание."))

    LeanSectionLabel(tr("Насыщенность"))
    LeanSlider(
        value = satDraft ?: settings.bgImageSaturation,
        onValueChange = { v -> satDraft = v },
        onValueChangeFinished = { v ->
            satDraft = v
            editor.edit { setBgImageSaturation(v) }
        },
        range = AppearanceRanges.BG_IMAGE_SATURATION_MIN..AppearanceRanges.BG_IMAGE_SATURATION_MAX,
        valueLabel = "${satDraft ?: settings.bgImageSaturation}%",
    )
    KnobHint(tr("0% — чёрно-белая картинка: цвет остаётся только у акцента."))

    LeanSectionLabel(tr("Масштаб"))
    LeanSlider(
        value = zoomDraft ?: settings.bgImageZoom,
        onValueChange = { v -> zoomDraft = v },
        onValueChangeFinished = { v ->
            zoomDraft = v
            editor.edit { setBgImageZoom(v) }
        },
        range = AppearanceRanges.BG_IMAGE_ZOOM_MIN..AppearanceRanges.BG_IMAGE_ZOOM_MAX,
        step = 5,
        valueLabel = "${zoomDraft ?: settings.bgImageZoom}%",
    )
    KnobHint(tr("Приближение картинки. 100% — она ровно закрывает экран."))

    KnobSegments(
        tr("Положение"),
        listOf(tr("Верх"), tr("Центр"), tr("Низ")),
        BG_IMAGE_ALIGNS.indexOf(settings.bgImageAlign).coerceAtLeast(0),
    ) { i -> editor.edit { setBgImageAlign(BG_IMAGE_ALIGNS[i]) } }
    KnobHint(tr("Какая часть картинки останется видна, если она выше экрана."))

    Spacer(Modifier.height(14.dp))
    LeanGroup {
        LeanToggleItem(
            LeanIcon.Layers, LeanColors.Violet, tr("Стекло"),
            tr("Панели показывают размытый фон сквозь себя"), settings.glassPanels,
        ) { on -> editor.edit { setGlassPanels(on) } }
    }
    if (settings.glassPanels) {
        LeanSectionLabel(tr("Плотность стекла"))
        LeanSlider(
            value = glassDraft ?: settings.glassTint,
            onValueChange = { v -> glassDraft = v },
            onValueChangeFinished = { v ->
                glassDraft = v
                editor.edit { setGlassTint(v) }
            },
            range = AppearanceRanges.GLASS_TINT_MIN..AppearanceRanges.GLASS_TINT_MAX,
            valueLabel = "${glassDraft ?: settings.glassTint}%",
        )
        KnobHint(tr("Чем меньше, тем прозрачнее панели, вплоть до полного слияния с фоном на 0 %. Работает на любом фоне; на картинке читаемость дополнительно держит «Затемнение»."))
    }
}

private val BG_IMAGE_ALIGNS = listOf("top", "center", "bottom")

package com.th3web.lean.ui.theme

import android.app.WallpaperManager
import android.content.Context
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import kotlinx.coroutines.delay
import com.th3web.lean.data.AppearanceNorm
import com.th3web.lean.data.AppearanceRanges
import com.th3web.lean.data.Settings
import com.th3web.lean.data.SettingsDefaults
import java.util.Calendar

/**
 * The fully resolved look, one value that decides everything «Оформление» can change.
 *
 * "Resolved" is the load-bearing word. Nothing downstream re-reads [Settings], re-decides
 * what `"system"` meant, or re-clamps a range; [Settings.appearanceSpec] does all of it
 * once and hands over answers. That is what makes the two halves of the theme (the
 * `LeanColors` token mirror and the MD3 `ColorScheme`) structurally incapable of
 * disagreeing: they are computed from the same value by the same pure function.
 *
 * It is also the recomposition gate. `Settings` emits on things that have nothing to do
 * with looks (`selectedProfileId` moves on every tap in the server list); an
 * `AppearanceSpec` does not change then, so `remember(spec)` and [LeanAppearance]'s guard
 * both no-op and neither the scheme nor the typography is rebuilt.
 *
 * Field names mirror [Settings] one-for-one except where resolving changed the meaning:
 * [mode] (never `"system"`, night schedule already applied), [accent] (the seed already
 * resolved through [LeanAccents]), [surfaceTint] and [selectionWash] (fractions, not
 * percents), [connectedAccent] (the request and the safety veto), [corner] and [density]
 * (shorter names for `cornerStyle`/`uiDensity`, which read badly as `spec.cornerStyle`).
 */
@Immutable
data class AppearanceSpec(
    // ---- theme root ----
    /** dark | amoled | light, a real column, never "system". */
    val mode: String = "dark",
    val amoledDepth: String = "absolute",
    val contrastLevel: Int = 0,
    // ---- colour ----
    val accent: LeanAccent = LeanAccents.Steel,
    /** The ARGB the accent was resolved from, what the picker highlights. */
    val accentSeed: Long = SettingsDefaults.ACCENT_LEGACY_DEFAULT,
    /** Fraction, already zeroed for untinted AMOLED and capped for the light column. */
    val surfaceTint: Float = LeanNeutralTintAmount,
    /** True only if the user asked for it and the accent is not error-adjacent. */
    val connectedAccent: Boolean = false,
    /** The user asked for the accent and the safety guard refused, the tab warns on this. */
    val connectedVetoed: Boolean = false,
    val errorColor: String = "coral",
    val wordmarkAccent: Boolean = true,
    val roleOverrides: Map<String, Long> = emptyMap(),
    // ---- type ----
    val fontDisplay: String = "unbounded",
    val fontBody: String = "onest",
    val textScale: Int = 100,
    val fontWeightDelta: Int = 0,
    val tabularNums: Boolean = true,
    val sectionCaps: Boolean = true,
    // ---- shape and density ----
    val corner: String = "normal",
    val density: String = "normal",
    val outlineWeight: String = "thin",
    val showDividers: Boolean = true,
    val dividerIndent: String = "inset",
    val cardShadow: String = "soft",
    // ---- home screen ----
    val heroStyle: String = "ring",
    val heroSize: Int = 100,
    val heroGlyph: String = "power",
    val heroBreath: Boolean = true,
    val heroFloating: Boolean = false,
    val trafficRow: String = "large",
    val quickPeek: Int = SettingsDefaults.QUICK_PEEK,
    val homeBlocks: Int = SettingsDefaults.HOME_BLOCKS,
    val currentServerLabel: String = "name",
    // ---- server list ----
    val latencyPalette: String = "accent",
    val latT1: Int = SettingsDefaults.LAT_T1,
    val latT2: Int = SettingsDefaults.LAT_T2,
    val latT3: Int = SettingsDefaults.LAT_T3,
    val latencyMeter: String = "bars_ms",
    val showTags: Boolean = true,
    val serverTagKinds: String = SettingsDefaults.SERVER_TAG_KINDS,
    val serverRow: String = "normal",
    val selectionCue: String = "both",
    /** Fraction, already zeroed when [selectionCue] has no wash. */
    val selectionWash: Float = SettingsDefaults.SELECTION_WASH / 100f,
    // ---- motion ----
    val motionLevel: String = "normal",
    val respectSystemAnimations: Boolean = true,
    val bannerSheen: Boolean = true,
    val colorCrossfade: Boolean = true,
    val haptics: String = "normal",
    // ---- background and system chrome ----
    val bgStyle: String = "flat",
    val bgImageDim: Int = SettingsDefaults.BG_IMAGE_DIM,
    val bgImageBlur: Int = SettingsDefaults.BG_IMAGE_BLUR,
    val bgImageSaturation: Int = SettingsDefaults.BG_IMAGE_SATURATION,
    val bgImageZoom: Int = SettingsDefaults.BG_IMAGE_ZOOM,
    val bgImageAlign: String = SettingsDefaults.BG_IMAGE_ALIGN,
    val glassPanels: Boolean = false,
    val glassTint: Int = SettingsDefaults.GLASS_TINT,
    val sysbarInk: String = "auto",
    val splashTheme: Boolean = true,
) {
    val light: Boolean get() = mode == "light"
    val amoled: Boolean get() = mode == "amoled"

    /** False kills every frame driver in the app, the reason of «Анимации → выкл». */
    val motionEnabled: Boolean get() = motionLevel != "off"

    /** Multiplier on every tween duration. `off` collapses them to `snap()`. */
    val motionScale: Float
        get() = when (motionLevel) {
            "off" -> 0f
            "calm" -> 1.4f
            "lively" -> 0.7f
            else -> 1f
        }

    val heroScale: Float get() = heroSize / 100f

    companion object {
        /** Today's shipping look, resolved, what a fresh install renders on frame one. */
        val Default = AppearanceSpec()
    }
}

/** The four optional «Главный экран» blocks, as bits of `Settings.homeBlocks`. */
object HomeBlock {
    const val SUBSCRIPTION = 1
    const val QUICK_PICK = 2
    const val CONNECTION_TEST = 4
    const val BANNER = 8
}

/**
 * Resolve a settings snapshot into the look to render.
 *
 * The three parameters are the things the store cannot know: whether the system is in
 * dark mode ([systemDark]), whether the clock is inside the user's night window
 * ([nightNow], see [rememberNightWindow]), and what the wallpaper's dominant colour is
 * ([wallpaperSeed], null below API 27 or when the accent does not come from there).
 *
 * This is the only place `"system"` is turned into a column and the only place the night
 * schedule wins. Anything that resolved it a second time would eventually resolve it
 * differently: that is how `Theme.kt` grew two key lists that had to agree.
 */
fun Settings.appearanceSpec(
    systemDark: Boolean,
    nightNow: Boolean,
    wallpaperSeed: Long?,
): AppearanceSpec {
    val chosen = if (themeMode == "system") {
        if (systemDark) "dark" else "light"
    } else {
        themeMode
    }
    // The night window outranks the day choice, including "light", flipping a light
    // theme dark at 23:00 is the entire feature.
    val mode = if (nightNow) themeSchedMode else chosen
    val onLight = mode == "light"

    // Wallpaper is a seed source, not a second colour path: whatever it yields goes
    // through the same LeanAccents resolution, so the AMOLED ladder, the light column and
    // all 33 tokens keep working. A device that cannot report one falls back to the
    // stored accent rather than to grey.
    val seed = if (accentSource == "wallpaper") wallpaperSeed ?: accentColor else accentColor
    val accent = LeanAccents.resolve(seed, accentChroma)

    // The stored value first, through the same normaliser the read mapping uses, the
    // per-column cap below is an extra ceiling, not the range itself, and applying only the
    // extra one let a raw number through untouched.
    val tint = AppearanceNorm.surfaceTint(surfaceTint)
    val tintPercent = when {
        // The AMOLED canvas is #000000 either way (tintNeutral cannot lift a zero-luminance
        // base); this switch decides whether the raised cards take the hue. Two independent
        // booleans used to have to agree here, now there is one.
        mode == "amoled" && !amoledTint -> 0
        // Capped at resolve time, not at storage: the light column's ceiling is lower
        // (tintNeutral darkens rather than renormalises there), and clamping on write
        // would silently destroy the choice the moment the user previewed light mode.
        onLight -> tint.coerceAtMost(AppearanceRanges.SURFACE_TINT_MAX_LIGHT)
        else -> tint
    }

    // «Подключено» may follow the accent, but never into the error hue: an accent that
    // reads as red would turn the app's one reassuring moment into an alarm.
    // Judged on the seed's dark tone whatever the canvas is, the contract
    // accentClashesWithError documents on both sides of the comparison. Feeding it the
    // light T40 instead would let the verdict flip on a theme switch that changed no
    // colour, so the tab's warning would appear and vanish on its own.
    val wantsAccent = connectedMode == "accent"
    val vetoed = wantsAccent && accentClashesWithError(accent.primary, errorColor)

    val washed = selectionCue == "wash" || selectionCue == "both"

    return AppearanceSpec(
        mode = mode,
        amoledDepth = amoledDepth,
        // Clamped here, not trusted from the caller. This function's contract is that
        // everything downstream gets answers rather than raw settings, and contrast is the
        // one that punishes a stray value: contrastAdjust feeds it to lerp() as
        // step * 0.04, and lerp does not clamp its fraction, a value past the range
        // extrapolates past the target and lands outside the colour gamut. The read
        // mapping normalises too, so this is defence in depth, not the only guard.
        contrastLevel = AppearanceNorm.contrastLevel(contrastLevel),
        accent = accent,
        accentSeed = seed,
        surfaceTint = tintPercent / 100f,
        connectedAccent = wantsAccent && !vetoed,
        connectedVetoed = vetoed,
        errorColor = errorColor,
        wordmarkAccent = wordmarkAccent,
        roleOverrides = roleOverrides,
        fontDisplay = fontDisplay,
        fontBody = fontBody,
        textScale = AppearanceNorm.textScale(textScale),
        fontWeightDelta = AppearanceNorm.fontWeightDelta(fontWeightDelta),
        tabularNums = tabularNums,
        sectionCaps = sectionCaps,
        corner = cornerStyle,
        density = uiDensity,
        outlineWeight = outlineWeight,
        showDividers = showDividers,
        dividerIndent = dividerIndent,
        cardShadow = cardShadow,
        heroStyle = heroStyle,
        heroSize = AppearanceNorm.heroSize(heroSize),
        heroGlyph = heroGlyph,
        heroBreath = heroBreath,
        heroFloating = heroFloating,
        trafficRow = trafficRow,
        quickPeek = AppearanceNorm.quickPeek(quickPeek),
        homeBlocks = homeBlocks,
        currentServerLabel = currentServerLabel,
        latencyPalette = latencyPalette,
        latT1 = latT1,
        latT2 = latT2,
        latT3 = latT3,
        latencyMeter = latencyMeter,
        showTags = showTags,
        serverTagKinds = AppearanceNorm.serverTagKinds(serverTagKinds),
        serverRow = serverRow,
        selectionCue = selectionCue,
        selectionWash = if (washed) AppearanceNorm.selectionWash(selectionWash) / 100f else 0f,
        motionLevel = motionLevel,
        respectSystemAnimations = respectSystemAnimations,
        bannerSheen = bannerSheen,
        colorCrossfade = colorCrossfade == "on",
        haptics = haptics,
        bgStyle = bgStyle,
        bgImageDim = AppearanceNorm.bgImageDim(bgImageDim),
        bgImageBlur = AppearanceNorm.bgImageBlur(bgImageBlur),
        bgImageSaturation = AppearanceNorm.bgImageSaturation(bgImageSaturation),
        bgImageZoom = AppearanceNorm.bgImageZoom(bgImageZoom),
        bgImageAlign = AppearanceNorm.bgImageAlign(bgImageAlign),
        glassPanels = glassPanels,
        glassTint = AppearanceNorm.glassTint(glassTint),
        sysbarInk = sysbarInk,
        splashTheme = splashTheme,
    )
}

/**
 * The one writer of every theme global.
 *
 * Everything the app draws outside a `MaterialTheme` role, `LeanColors`, the corner
 * ladder, the type roles, [LeanMetrics], [LeanOptions], is published from here and
 * nowhere else. Two callers: `LeanTheme`'s `SideEffect` (so any host, including a second
 * activity or a `@Preview`, is correct on its first frame rather than its second) and
 * `MainActivity.onCreate` (so the pre-composition frame is correct too).
 *
 * The [applied] guard is what makes calling it from a `SideEffect` free: an unchanged
 * spec costs one data-class comparison and touches no snapshot state, so the ~40 writes
 * and the palette rebuild happen only on a real change.
 */
object LeanAppearance {

    /** null until the first apply, the tokens' declared defaults are not a resolved spec. */
    private var applied: AppearanceSpec? = null

    /** The look currently on screen, as snapshot state (the lab screen prints it). */
    var current by mutableStateOf(AppearanceSpec.Default)
        private set

    fun apply(spec: AppearanceSpec) {
        if (spec == applied) return
        applied = spec
        current = spec
        LeanColors.adopt(leanPalette(spec))
        LeanCorner.apply(spec.corner)
        applyTypography(spec)
        LeanMetrics.apply(spec)
        LeanOptions.apply(spec)
    }
}

/**
 * Geometry that is not a colour and not a shape: sizes, paddings, stroke widths, alphas.
 *
 * Not `LocalDensity`. A density multiplier would scale the 252dp connect
 * ring along with the list rows, and (worse) every `Dialog` builds its own
 * `AndroidComposeView` and re-declares `LocalDensity` from its context, so the app's two
 * dozen `AlertDialog`s would render unscaled. These are read directly inside the
 * composables that draw with them, which works everywhere.
 */
object LeanMetrics {

    /** LeanBadge / row leading icon container. */
    var badgeSize by mutableStateOf(38.dp)
    var rowPadV by mutableStateOf(12.dp)
    var groupGap by mutableStateOf(14.dp)
    var sectionPad by mutableStateOf(16.dp)

    /** «Контуры», 0 / 1 / 1.5dp. The colour's alpha is zeroed too when it is 0. */
    var outlineWidth by mutableStateOf(1.dp)

    /**
     * Leading inset of a `LeanDivider`. derived from [badgeSize] (+24dp of row padding),
     * not the old 62dp constant, at `comfortable` density a fixed inset drifts away from
     * the text column it is supposed to align with.
     */
    var dividerIndent by mutableStateOf(62.dp)

    /** «Тень карточек», 0 / 2 / 6dp for `Modifier.depthShadow`. */
    var shadowElevation by mutableStateOf(2.dp)

    /** 0 when «Выделение сервера» has no stripe. */
    var selectionStripeWidth by mutableStateOf(4.dp)
    var selectionStripeHeight by mutableStateOf(32.dp)

    /** Selected-row accent wash; 0 when the cue has no wash. */
    var selectionWash by mutableStateOf(SettingsDefaults.SELECTION_WASH / 100f)

    /**
     * The one named home for the 0.40f accent-stroke alpha that used to be copy-pasted
     * into Glass, SubscriptionCard and HomeScreen and had already started to drift.
     */
    var accentBorderAlpha by mutableStateOf(0.40f)

    /** «Размер кнопки», multiplies the connect hero's own dp literals (0.85 / 1 / 1.15). */
    var heroScale by mutableStateOf(1f)

    /** «Строки списка»: the server list is tuned apart from the settings rows. */
    var serverRowPadV by mutableStateOf(11.dp)
    var serverRowGap by mutableStateOf(8.dp)

    internal fun apply(spec: AppearanceSpec) {
        val badge = when (spec.density) {
            "compact" -> 34.dp
            "comfortable" -> 44.dp
            else -> 38.dp
        }
        badgeSize = badge
        rowPadV = when (spec.density) {
            "compact" -> 10.dp
            "comfortable" -> 16.dp
            else -> 12.dp
        }
        groupGap = when (spec.density) {
            "compact" -> 10.dp
            "comfortable" -> 18.dp
            else -> 14.dp
        }
        sectionPad = when (spec.density) {
            "compact" -> 12.dp
            "comfortable" -> 20.dp
            else -> 16.dp
        }
        outlineWidth = when (spec.outlineWeight) {
            "none" -> 0.dp
            "strong" -> 1.5.dp
            else -> 1.dp
        }
        dividerIndent = if (spec.dividerIndent == "full") 0.dp else badge + 24.dp
        shadowElevation = when (spec.cardShadow) {
            "none" -> 0.dp
            "deep" -> 6.dp
            else -> 2.dp
        }
        val striped = spec.selectionCue == "stripe" || spec.selectionCue == "both"
        selectionStripeWidth = if (striped) 4.dp else 0.dp
        selectionWash = spec.selectionWash
        heroScale = spec.heroScale
        serverRowPadV = when (spec.serverRow) {
            "compact" -> 8.dp
            "detailed" -> 16.dp
            // 11, not a rounder 12: this is the literal the row shipped with, and the
            // «Сталь·Ночь» preset promises today's look bit-for-bit. A default that is
            // one dp off is the kind of drift nobody reports and nobody can
            // explain later.
            else -> 11.dp
        }
        serverRowGap = when (spec.serverRow) {
            "compact" -> 6.dp
            "detailed" -> 10.dp
            else -> 8.dp
        }
    }
}

/**
 * The mode switches: which variant of a component to draw, and whether to draw it at all.
 *
 * Same publication contract as [LeanColors], snapshot state, written only by
 * [LeanAppearance], read inside composables. Reading one into a file-level `val` freezes
 * it at class-load on whatever look happened to be active first; that bug shipped once
 * already (ServerRow's hoisted `BarUnlitColor`), and is what the CI grep gate watches for.
 */
object LeanOptions {

    var showDividers by mutableStateOf(true)
    var sectionCaps by mutableStateOf(true)

    /** Percent. B2 reads it to release `maxLines` on the two rows that clip at 110+. */
    var textScale by mutableStateOf(100)

    // ---- home ----
    var heroStyle by mutableStateOf("ring")
    var heroGlyph by mutableStateOf("power")
    var heroBreath by mutableStateOf(true)
    var heroFloating by mutableStateOf(false)
    var trafficRow by mutableStateOf("large")
    var quickPeek by mutableStateOf(SettingsDefaults.QUICK_PEEK)
    var homeBlocks by mutableStateOf(SettingsDefaults.HOME_BLOCKS)
    var currentServerLabel by mutableStateOf("name")

    /** Named readings of [homeBlocks] so the bit order lives in exactly one place. */
    val showSubscriptionBlock: Boolean get() = (homeBlocks and HomeBlock.SUBSCRIPTION) != 0
    val showQuickPickBlock: Boolean get() = (homeBlocks and HomeBlock.QUICK_PICK) != 0
    val showConnectionTestBlock: Boolean get() = (homeBlocks and HomeBlock.CONNECTION_TEST) != 0
    val showBannerBlock: Boolean get() = (homeBlocks and HomeBlock.BANNER) != 0

    // ---- server list ----
    var latencyPalette by mutableStateOf("accent")
    var latT1 by mutableStateOf(SettingsDefaults.LAT_T1)
    var latT2 by mutableStateOf(SettingsDefaults.LAT_T2)
    var latT3 by mutableStateOf(SettingsDefaults.LAT_T3)
    var latencyMeter by mutableStateOf("bars_ms")
    var showTags by mutableStateOf(true)
    var serverTagKinds by mutableStateOf(SettingsDefaults.SERVER_TAG_KINDS)
    var serverRow by mutableStateOf("normal")
    var selectionCue by mutableStateOf("both")

    // ---- motion ----
    var motionLevel by mutableStateOf("normal")

    /** Multiplier on every tween duration; 0 means "use snap()". */
    var motionDurationScale by mutableStateOf(1f)

    /**
     * When true the system animator scale still vetoes in-app animation (today's
     * behaviour). When false it becomes a floor, so a battery-saver ROM no longer
     * decides for the user.
     */
    var respectSystemAnimations by mutableStateOf(true)
    var bannerSheen by mutableStateOf(true)
    var colorCrossfade by mutableStateOf(true)
    var haptics by mutableStateOf("normal")

    // ---- background and system chrome ----
    var bgStyle by mutableStateOf("flat")
    var bgImageDim by mutableStateOf(SettingsDefaults.BG_IMAGE_DIM)
    var bgImageBlur by mutableStateOf(SettingsDefaults.BG_IMAGE_BLUR)
    var bgImageSaturation by mutableStateOf(SettingsDefaults.BG_IMAGE_SATURATION)
    var bgImageZoom by mutableStateOf(SettingsDefaults.BG_IMAGE_ZOOM)
    var bgImageAlign by mutableStateOf(SettingsDefaults.BG_IMAGE_ALIGN)
    var glassPanels by mutableStateOf(false)
    var glassTint by mutableStateOf(SettingsDefaults.GLASS_TINT)

    /**
     * Read from composition so a live theme flip re-skins the system bars without adding
     * a second settings collector to `MainActivity`.
     */
    var sysbarInk by mutableStateOf("auto")

    internal fun apply(spec: AppearanceSpec) {
        showDividers = spec.showDividers
        sectionCaps = spec.sectionCaps
        textScale = spec.textScale

        heroStyle = spec.heroStyle
        heroGlyph = spec.heroGlyph
        heroBreath = spec.heroBreath
        heroFloating = spec.heroFloating
        trafficRow = spec.trafficRow
        quickPeek = spec.quickPeek
        homeBlocks = spec.homeBlocks
        currentServerLabel = spec.currentServerLabel

        latencyPalette = spec.latencyPalette
        latT1 = spec.latT1
        latT2 = spec.latT2
        latT3 = spec.latT3
        latencyMeter = spec.latencyMeter
        showTags = spec.showTags
        serverTagKinds = spec.serverTagKinds
        serverRow = spec.serverRow
        selectionCue = spec.selectionCue

        motionLevel = spec.motionLevel
        motionDurationScale = spec.motionScale
        respectSystemAnimations = spec.respectSystemAnimations
        bannerSheen = spec.bannerSheen
        colorCrossfade = spec.colorCrossfade
        haptics = spec.haptics

        bgStyle = spec.bgStyle
        bgImageDim = spec.bgImageDim
        bgImageBlur = spec.bgImageBlur
        bgImageSaturation = spec.bgImageSaturation
        bgImageZoom = spec.bgImageZoom
        bgImageAlign = spec.bgImageAlign
        glassPanels = spec.glassPanels
        glassTint = spec.glassTint
        sysbarInk = spec.sysbarInk
    }
}

// ── Night schedule ───────────────────────────────────────────────────────────

/**
 * Is [now] (minutes from midnight) inside the window [from]..[to]?
 *
 * The window normally wraps (23:00 → 07:00), so the reversed case is the common one, not
 * the edge case. An empty window (from == to) is treated as "off" rather than as "always"
 * a user who set both ends to the same time meant to disable it, and "always night"
 * would look like the theme setting had stopped working.
 */
internal fun nightWindowIncludes(now: Int, from: Int, to: Int): Boolean = when {
    from == to -> false
    from < to -> now in from until to
    else -> now >= from || now < to
}

/** Non-Compose twin of [rememberNightWindow], for `MainActivity`'s pre-composition seed. */
internal fun nightWindowNow(s: Settings): Boolean =
    s.themeSchedule && nightWindowIncludes(minuteOfDay(), s.themeSchedFrom, s.themeSchedTo)

/**
 * Whether the clock is currently inside the night window, re-evaluated on each minute
 * boundary, and only while the schedule is on, so the default costs one boolean and no
 * coroutine at all.
 *
 * No WorkManager,: outside the UI there is no theme to change. A backgrounded
 * app re-enters composition when it comes back and re-reads the clock then.
 */
@Composable
fun rememberNightWindow(s: Settings): Boolean {
    val enabled = s.themeSchedule
    val from = s.themeSchedFrom
    val to = s.themeSchedTo
    // Plain state + LaunchedEffect rather than produceState, which is what this is:
    // lint's ProduceStateDoesNotAssignValue does not see the assignment here in any
    // shape and fails the build, and suppressing a correctness check is worse than not
    // using the helper. The seed is read synchronously so the very first frame is
    // already right; the effect only exists to cross the next minute boundary.
    var inWindow by remember { mutableStateOf(nightWindowNow(s)) }
    LaunchedEffect(enabled, from, to) {
        inWindow = enabled && nightWindowIncludes(minuteOfDay(), from, to)
        while (enabled) {
            delay(millisToNextMinute())
            inWindow = nightWindowIncludes(minuteOfDay(), from, to)
        }
    }
    return inWindow
}

private fun minuteOfDay(): Int {
    // Calendar, not LocalTime: java.time is API 26 and minSdk is 24.
    val c = Calendar.getInstance()
    return c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE)
}

/** Sleep until the wall clock ticks over, with a floor so a clock jump can't spin us. */
private fun millisToNextMinute(): Long =
    (60_000L - System.currentTimeMillis() % 60_000L).coerceAtLeast(1_000L)

// ── Wallpaper accent source ──────────────────────────────────────────────────

/**
 * The wallpaper's dominant colour as an accent seed, or null when the source is something
 * else, the platform is below API 27, or the device declines to answer.
 *
 * `getWallpaperColors` is the permission-free path (API 27+); `getDrawable` would need
 * READ_EXTERNAL_STORAGE and is what we are avoiding. Below 27 the tab does not
 * offer the segment at all, so a null here is only ever a device that has no wallpaper
 * colours to report.
 */
internal fun wallpaperSeedOrNull(context: Context, s: Settings): Long? {
    if (s.accentSource != "wallpaper") return null
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O_MR1) return null
    return wallpaperPrimary(context)
}

@RequiresApi(Build.VERSION_CODES.O_MR1)
private fun wallpaperPrimary(context: Context): Long? = runCatching {
    WallpaperManager.getInstance(context)
        ?.getWallpaperColors(WallpaperManager.FLAG_SYSTEM)
        ?.primaryColor
        ?.toArgb()
        ?.toLong()
        ?.and(0xFFFFFFFFL)
}.getOrNull()

/**
 * Compose twin of [wallpaperSeedOrNull], refreshed on `ON_RESUME`.
 *
 * There is no permission-free broadcast for "the wallpaper changed", and it can change
 * while we are backgrounded (a shuffle, a live wallpaper, the user long-pressing the home
 * screen), so returning to the foreground is the read point.
 */
@Composable
fun rememberWallpaperSeed(s: Settings): Long? {
    // Returning before any composable call keeps the cost of the other two accent
    // sources at zero, no state slot, no lifecycle observer, no binder call.
    if (s.accentSource != "wallpaper") return null
    val context = LocalContext.current
    var seed by remember(context) { mutableStateOf(wallpaperSeedOrNull(context, s)) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        seed = wallpaperSeedOrNull(context, s)
    }
    return seed
}

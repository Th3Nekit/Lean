package com.th3web.lean.data

import android.util.Base64
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import java.util.zip.DataFormatException
import java.util.zip.Deflater
import java.util.zip.Inflater
import kotlin.math.abs

/**
 * Everything the «Оформление» tab can change, detached from [Settings].
 *
 * Two consumers justify the separate type: a named look (built-in or user-saved) has to
 * be storable and shareable without dragging along servers, DNS or the selected profile,
 * and applying one has to be a single DataStore transaction (see
 * SettingsRepository.applyAppearance), so the theme recomposes once instead of ~50 times.
 *
 * Field names mirror [Settings] one-for-one, [toAppearanceProfile] and
 * [mergeInto] are then reviewable by eye, and a missed field shows up as a name that
 * appears in one list but not the other.
 *
 * Every field has a Kotlin default, for the same reason [Settings] does: a share code is
 * decoded with `encodeDefaults = false`, so a code minted before a knob existed simply
 * resolves that knob to its default instead of failing to parse.
 */
@Serializable
data class AppearanceProfile(
    // ---- theme root ----
    val themeMode: String = "dark",
    val themeSchedule: Boolean = false,
    val themeSchedFrom: Int = SettingsDefaults.THEME_SCHED_FROM,
    val themeSchedTo: Int = SettingsDefaults.THEME_SCHED_TO,
    val themeSchedMode: String = "amoled",
    val contrastLevel: Int = 0,
    val amoledDepth: String = "absolute",
    val amoledTint: Boolean = false,
    // ---- colour ----
    val accentSource: String = "preset",
    val accentColor: Long = SettingsDefaults.ACCENT_LEGACY_DEFAULT,
    val accentChroma: Int = SettingsDefaults.ACCENT_CHROMA,
    val surfaceTint: Int = SettingsDefaults.SURFACE_TINT,
    val connectedMode: String = "sage",
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
    val cornerStyle: String = "normal",
    val uiDensity: String = "normal",
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
    val selectionWash: Int = SettingsDefaults.SELECTION_WASH,
    // ---- motion ----
    val motionLevel: String = "normal",
    val respectSystemAnimations: Boolean = true,
    val bannerSheen: Boolean = true,
    val colorCrossfade: String = "on",
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
    companion object {
        /** Today's shipping look, bit-for-bit, every field on its default. */
        val Default = AppearanceProfile()
    }
}

/** A look plus its label: built-in presets and user-saved ones render through one card. */
@Serializable
data class NamedAppearance(
    val name: String,
    val profile: AppearanceProfile = AppearanceProfile(),
)

fun Settings.toAppearanceProfile(): AppearanceProfile = AppearanceProfile(
    themeMode = themeMode,
    themeSchedule = themeSchedule,
    themeSchedFrom = themeSchedFrom,
    themeSchedTo = themeSchedTo,
    themeSchedMode = themeSchedMode,
    contrastLevel = contrastLevel,
    amoledDepth = amoledDepth,
    amoledTint = amoledTint,
    accentSource = accentSource,
    accentColor = accentColor,
    accentChroma = accentChroma,
    surfaceTint = surfaceTint,
    connectedMode = connectedMode,
    errorColor = errorColor,
    wordmarkAccent = wordmarkAccent,
    roleOverrides = roleOverrides,
    fontDisplay = fontDisplay,
    fontBody = fontBody,
    textScale = textScale,
    fontWeightDelta = fontWeightDelta,
    tabularNums = tabularNums,
    sectionCaps = sectionCaps,
    cornerStyle = cornerStyle,
    uiDensity = uiDensity,
    outlineWeight = outlineWeight,
    showDividers = showDividers,
    dividerIndent = dividerIndent,
    cardShadow = cardShadow,
    heroStyle = heroStyle,
    heroSize = heroSize,
    heroGlyph = heroGlyph,
    heroBreath = heroBreath,
    heroFloating = heroFloating,
    trafficRow = trafficRow,
    quickPeek = quickPeek,
    homeBlocks = homeBlocks,
    currentServerLabel = currentServerLabel,
    latencyPalette = latencyPalette,
    latT1 = latT1,
    latT2 = latT2,
    latT3 = latT3,
    latencyMeter = latencyMeter,
    showTags = showTags,
    serverTagKinds = serverTagKinds,
    serverRow = serverRow,
    selectionCue = selectionCue,
    selectionWash = selectionWash,
    motionLevel = motionLevel,
    respectSystemAnimations = respectSystemAnimations,
    bannerSheen = bannerSheen,
    colorCrossfade = colorCrossfade,
    haptics = haptics,
    bgStyle = bgStyle,
    bgImageDim = bgImageDim,
    bgImageBlur = bgImageBlur,
    bgImageSaturation = bgImageSaturation,
    bgImageZoom = bgImageZoom,
    bgImageAlign = bgImageAlign,
    glassPanels = glassPanels,
    glassTint = glassTint,
    sysbarInk = sysbarInk,
    splashTheme = splashTheme,
)

/**
 * Overlay a look onto a full settings snapshot. Used to render a preview of an incoming
 * share code (and of a preset thumbnail) against the current settings without writing
 * anything: the preview is shown before anything is committed.
 */
fun AppearanceProfile.mergeInto(s: Settings): Settings = s.copy(
    themeMode = themeMode,
    themeSchedule = themeSchedule,
    themeSchedFrom = themeSchedFrom,
    themeSchedTo = themeSchedTo,
    themeSchedMode = themeSchedMode,
    contrastLevel = contrastLevel,
    amoledDepth = amoledDepth,
    amoledTint = amoledTint,
    accentSource = accentSource,
    accentColor = accentColor,
    accentChroma = accentChroma,
    surfaceTint = surfaceTint,
    connectedMode = connectedMode,
    errorColor = errorColor,
    wordmarkAccent = wordmarkAccent,
    roleOverrides = roleOverrides,
    fontDisplay = fontDisplay,
    fontBody = fontBody,
    textScale = textScale,
    fontWeightDelta = fontWeightDelta,
    tabularNums = tabularNums,
    sectionCaps = sectionCaps,
    cornerStyle = cornerStyle,
    uiDensity = uiDensity,
    outlineWeight = outlineWeight,
    showDividers = showDividers,
    dividerIndent = dividerIndent,
    cardShadow = cardShadow,
    heroStyle = heroStyle,
    heroSize = heroSize,
    heroGlyph = heroGlyph,
    heroBreath = heroBreath,
    trafficRow = trafficRow,
    quickPeek = quickPeek,
    homeBlocks = homeBlocks,
    currentServerLabel = currentServerLabel,
    latencyPalette = latencyPalette,
    latT1 = latT1,
    latT2 = latT2,
    latT3 = latT3,
    latencyMeter = latencyMeter,
    showTags = showTags,
    serverTagKinds = serverTagKinds,
    serverRow = serverRow,
    selectionCue = selectionCue,
    selectionWash = selectionWash,
    motionLevel = motionLevel,
    respectSystemAnimations = respectSystemAnimations,
    bannerSheen = bannerSheen,
    colorCrossfade = colorCrossfade,
    haptics = haptics,
    bgStyle = bgStyle,
    bgImageDim = bgImageDim,
    bgImageBlur = bgImageBlur,
    bgImageSaturation = bgImageSaturation,
    bgImageZoom = bgImageZoom,
    bgImageAlign = bgImageAlign,
    sysbarInk = sysbarInk,
    splashTheme = splashTheme,
)

/**
 * Force every field back into its legal domain. Applied on decode of a pasted share
 * code, on decode of the saved-preset library, and again in
 * SettingsRepository.applyAppearance, so nothing a stranger typed can reach a knob that
 * feeds a Dp, an alpha or a font weight.
 */
fun AppearanceProfile.sanitized(): AppearanceProfile {
    val t1 = AppearanceNorm.latT1(latT1)
    val t2 = AppearanceNorm.latT2(latT2, t1)
    return copy(
        themeMode = AppearanceNorm.themeMode(themeMode),
        themeSchedFrom = AppearanceNorm.minuteOfDay(themeSchedFrom, SettingsDefaults.THEME_SCHED_FROM),
        themeSchedTo = AppearanceNorm.minuteOfDay(themeSchedTo, SettingsDefaults.THEME_SCHED_TO),
        themeSchedMode = AppearanceNorm.themeSchedMode(themeSchedMode),
        contrastLevel = AppearanceNorm.contrastLevel(contrastLevel),
        amoledDepth = AppearanceNorm.amoledDepth(amoledDepth),
        accentSource = AppearanceNorm.accentSource(accentSource),
        accentColor = AppearanceNorm.opaque(accentColor),
        accentChroma = AppearanceNorm.accentChroma(accentChroma),
        surfaceTint = AppearanceNorm.surfaceTint(surfaceTint),
        connectedMode = AppearanceNorm.connectedMode(connectedMode),
        errorColor = AppearanceNorm.errorColor(errorColor),
        roleOverrides = AppearanceNorm.roleOverrides(roleOverrides),
        fontDisplay = AppearanceNorm.fontFamily(fontDisplay, "unbounded"),
        fontBody = AppearanceNorm.fontFamily(fontBody, "onest"),
        textScale = AppearanceNorm.textScale(textScale),
        fontWeightDelta = AppearanceNorm.fontWeightDelta(fontWeightDelta),
        cornerStyle = AppearanceNorm.cornerStyle(cornerStyle),
        uiDensity = AppearanceNorm.uiDensity(uiDensity),
        outlineWeight = AppearanceNorm.outlineWeight(outlineWeight),
        dividerIndent = AppearanceNorm.dividerIndent(dividerIndent),
        cardShadow = AppearanceNorm.cardShadow(cardShadow),
        heroStyle = AppearanceNorm.heroStyle(heroStyle),
        heroSize = AppearanceNorm.heroSize(heroSize),
        heroGlyph = AppearanceNorm.heroGlyph(heroGlyph),
        trafficRow = AppearanceNorm.trafficRow(trafficRow),
        quickPeek = AppearanceNorm.quickPeek(quickPeek),
        homeBlocks = AppearanceNorm.homeBlocks(homeBlocks),
        currentServerLabel = AppearanceNorm.currentServerLabel(currentServerLabel),
        latencyPalette = AppearanceNorm.latencyPalette(latencyPalette),
        latT1 = t1,
        latT2 = t2,
        latT3 = AppearanceNorm.latT3(latT3, t2),
        latencyMeter = AppearanceNorm.latencyMeter(latencyMeter),
        serverRow = AppearanceNorm.serverRow(serverRow),
        selectionCue = AppearanceNorm.selectionCue(selectionCue),
        selectionWash = AppearanceNorm.selectionWash(selectionWash),
        motionLevel = AppearanceNorm.motionLevel(motionLevel),
        colorCrossfade = AppearanceNorm.colorCrossfade(colorCrossfade),
        haptics = AppearanceNorm.haptics(haptics),
        bgStyle = AppearanceNorm.bgStyle(bgStyle),
        bgImageDim = AppearanceNorm.bgImageDim(bgImageDim),
        sysbarInk = AppearanceNorm.sysbarInk(sysbarInk),
    )
}

/**
 * The built-in looks. [name] is the Russian label and doubles as its own I18n key, the
 * same arrangement [com.th3web.lean.ui.theme.LeanAccent] uses for `nameRu`, the tab
 * renders it through `tr()`, so the EN pairs live in ui/I18n.kt.
 *
 * Each preset states only the fields it moves; everything else inherits the default,
 * which is what makes «Сталь·Ночь» reproduce today's shipping look exactly.
 */
object AppearancePresets {

    /** The current look. Also what «Сбросить оформление» writes. */
    val Steel = NamedAppearance("Сталь·Ночь", AppearanceProfile.Default)

    /** Pure black, no hue, a notch more contrast and tighter corners. */
    val Midnight = NamedAppearance(
        "Полночь",
        AppearanceProfile(
            themeMode = "amoled",
            accentColor = 0xFFC6CAD3L,
            contrastLevel = 1,
            cornerStyle = "crisp",
        ),
    )

    /** Daylight reading: light column, generous corners, one step up in text size. */
    val Paper = NamedAppearance(
        "Бумага",
        AppearanceProfile(
            themeMode = "light",
            accentColor = 0xFFB1C4E6L,
            cornerStyle = "soft",
            textScale = 110,
        ),
    )

    /**
     * Console: untinted pure black, monospace everywhere, traffic-light latency instead
     * of accent-tinted, and motion off, the one preset that removes every frame driver.
     */
    val Terminal = NamedAppearance(
        "Терминал",
        AppearanceProfile(
            themeMode = "amoled",
            accentColor = 0xFF98D1A6L,
            surfaceTint = 0,
            cornerStyle = "sharp",
            fontDisplay = "mono",
            fontBody = "mono",
            latencyPalette = "traffic",
            motionLevel = "off",
        ),
    )

    val Soft = NamedAppearance(
        "Мягкий",
        AppearanceProfile(
            accentColor = 0xFFC9BFE8L,
            cornerStyle = "round",
            uiDensity = "comfortable",
        ),
    )

    /** Legibility first: maximum contrast, heavy outlines, largest text, no motion. */
    val Contrast = NamedAppearance(
        "Контраст",
        AppearanceProfile(
            contrastLevel = 2,
            outlineWeight = "strong",
            textScale = 120,
            motionLevel = "off",
        ),
    )

    val Warm = NamedAppearance(
        "Тепло",
        AppearanceProfile(
            accentColor = 0xFFDCC18CL,
            surfaceTint = 16,
            cornerStyle = "soft",
        ),
    )

    val all: List<NamedAppearance> = listOf(Steel, Midnight, Paper, Terminal, Soft, Contrast, Warm)
}

/** Share-code envelope. The version digit is what lets a future format be rejected cleanly. */
const val APPEARANCE_CODE_PREFIX = "LEAN1:"

/**
 * `LEAN1:` + Base64url(Deflate(Json)). Not the encrypted [Backup] format: a
 * theme is not a secret, and what is wanted is a string short enough to paste into a chat.
 */
fun AppearanceProfile.encode(): String {
    val json = appearanceJson.encodeToString(this).toByteArray(Charsets.UTF_8)
    val packed = deflate(json)
    return APPEARANCE_CODE_PREFIX + Base64.encodeToString(
        packed,
        Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING,
    )
}

/**
 * Inverse of [encode]; null for anything that is not a well-formed Lean appearance code.
 * The result is always [sanitized], so a hand-edited code cannot smuggle an out-of-range
 * number or an unknown mode string into the store.
 */
fun decodeAppearance(code: String): AppearanceProfile? {
    val trimmed = code.trim()
    if (!trimmed.startsWith(APPEARANCE_CODE_PREFIX)) return null
    // Chat clients wrap long lines; whitespace inside the payload is never significant.
    val body = trimmed.removePrefix(APPEARANCE_CODE_PREFIX).filterNot { it.isWhitespace() }
    if (body.isEmpty() || body.length > MAX_CODE_BODY) return null
    val packed = runCatching {
        Base64.decode(body, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }.getOrNull()?.takeIf { it.isNotEmpty() } ?: return null
    val json = inflate(packed) ?: return null
    return runCatching {
        appearanceJson.decodeFromString<AppearanceProfile>(json.toString(Charsets.UTF_8))
    }.getOrNull()?.sanitized()
}

/** Persisted form of «Мои образы»: a JSON array in one string preference. */
internal fun encodeCustomPresets(presets: List<NamedAppearance>): String =
    appearanceJson.encodeToString(presets.take(AppearanceRanges.CUSTOM_PRESET_MAX))

internal fun decodeCustomPresets(raw: String?): List<NamedAppearance> {
    if (raw.isNullOrBlank()) return emptyList()
    return runCatching { appearanceJson.decodeFromString<List<NamedAppearance>>(raw) }
        .getOrDefault(emptyList())
        .take(AppearanceRanges.CUSTOM_PRESET_MAX)
        .map { it.copy(name = it.name.take(MAX_PRESET_NAME), profile = it.profile.sanitized()) }
}

/** Persisted form of the role-override map: a JSON object in one string preference. */
internal fun encodeRoleOverrides(overrides: Map<String, Long>): String =
    appearanceJson.encodeToString(AppearanceNorm.roleOverrides(overrides))

internal fun decodeRoleOverrides(raw: String?): Map<String, Long> {
    if (raw.isNullOrBlank()) return emptyMap()
    return AppearanceNorm.roleOverrides(
        runCatching { appearanceJson.decodeFromString<Map<String, Long>>(raw) }
            .getOrDefault(emptyMap()),
    )
}

/** «Недавние» swatches: a CSV of ARGB hex, newest first. */
internal fun parseRecentAccents(raw: String?): List<Long> =
    raw.orEmpty()
        .split(',')
        .mapNotNull { it.trim().removePrefix("#").takeIf(String::isNotEmpty)?.toLongOrNull(16) }
        .map(AppearanceNorm::opaque)
        .distinct()
        .take(AppearanceRanges.RECENT_ACCENT_MAX)

internal fun formatRecentAccents(colors: List<Long>): String =
    colors.map(AppearanceNorm::opaque)
        .distinct()
        .take(AppearanceRanges.RECENT_ACCENT_MAX)
        .joinToString(",") { it.toString(16).padStart(8, '0') }

/**
 * Bounds for the numeric knobs. Shared by the normalisers below and by the sliders and
 * segmented controls, so the control can never offer a value the store would clamp away.
 */
object AppearanceRanges {
    const val CONTRAST_MIN = -2
    const val CONTRAST_MAX = 2
    /**
     * Scrim over «своя картинка». Neither end is a free choice: below ~20% the surface
     * ladder and hairlines stop separating from a bright photo and the interface becomes
     * unreadable, and at 100% the picture is gone entirely, which makes the style itself
     * pointless. The band keeps both the image and the UI present.
     */
    const val BG_IMAGE_DIM_MIN = 20
    const val BG_IMAGE_DIM_MAX = 92
    const val BG_IMAGE_BLUR_MIN = 0
    const val BG_IMAGE_BLUR_MAX = 100
    const val BG_IMAGE_SATURATION_MIN = 0
    const val BG_IMAGE_SATURATION_MAX = 100
    /** Below 100 the picture would no longer cover the screen and leave bare canvas. */
    const val BG_IMAGE_ZOOM_MIN = 100
    const val BG_IMAGE_ZOOM_MAX = 250
    /**
     * Down to fully transparent, which is a real setting rather than a broken one.
     *
     * A panel paints its tint over the background's own scrim («Затемнение фона»), so at 0
     * it adds nothing of its own and reads as the dimmed wallpaper. Contrast at that point
     * is what the dim knob is for, and the hint under the slider says so.
     */
    const val GLASS_TINT_MIN = 0
    const val GLASS_TINT_MAX = 95
    const val ACCENT_CHROMA_MIN = 0
    const val ACCENT_CHROMA_MAX = 100
    const val SURFACE_TINT_MIN = 0
    const val SURFACE_TINT_MAX = 20
    /**
     * The light column gets a lower ceiling: tintNeutral's `k > 1` branch does not
     * renormalise lightness there, it darkens, so the top of the dark-column range would
     * cost real contrast. The stored value keeps the full range: the cap belongs to the
     * resolver, so switching back to dark restores the user's choice.
     */
    const val SURFACE_TINT_MAX_LIGHT = 12
    const val QUICK_PEEK_MIN = 0
    const val QUICK_PEEK_MAX = 6
    const val SELECTION_WASH_MIN = 0
    const val SELECTION_WASH_MAX = 25
    const val HOME_BLOCKS_MIN = 0
    /** Four blocks, four bits, subscription | quick pick | connection test | banner. */
    const val HOME_BLOCKS_MAX = 15
    const val MINUTE_MIN = 0
    const val MINUTE_MAX = 1_439
    const val LAT_T1_MIN = 10
    const val LAT_T1_MAX = 1_500
    const val LAT_T2_MAX = 3_000
    const val LAT_T3_MAX = 5_000
    const val CUSTOM_PRESET_MAX = 10
    const val RECENT_ACCENT_MAX = 6

    /** Discrete steps, snapped to the nearest legal value rather than clamped. */
    val TEXT_SCALE_STEPS = intArrayOf(90, 95, 100, 110, 120)
    val FONT_WEIGHT_STEPS = intArrayOf(-100, 0, 100)
    // Two steps below the old floor: testers asked to shrink the connect button further
    // so more of the server list fits on screen. 55 is small enough to read as a control
    // rather than as the screen's centrepiece, which is what the asking is for.
    val HERO_SIZE_STEPS = intArrayOf(55, 70, 85, 100, 115)
}

/** The ten semantic colour slots «Цвета по ролям» exposes; anything else is dropped. */
object AppearanceRoles {
    const val BACKGROUND = "background"
    const val SURFACE = "surface"
    const val PANEL = "panel"
    const val OUTLINE = "outline"
    const val ACCENT = "accent"
    const val TEXT_PRIMARY = "text_primary"
    const val TEXT_SECONDARY = "text_secondary"
    const val CONNECTED = "connected"
    const val ERROR = "error"
    const val TAG = "tag"

    val all: List<String> = listOf(
        BACKGROUND, SURFACE, PANEL, OUTLINE, ACCENT,
        TEXT_PRIMARY, TEXT_SECONDARY, CONNECTED, ERROR, TAG,
    )
}

/**
 * One definition per closed value set, used by both the DataStore read mapping and
 * [sanitized].
 *
 * That sharing is the intent. The `?:`/`when` fallback in the read mapping is the real
 * runtime default, the data-class default only governs the startup mirror and backups,
 * so the two must agree forever. Written twice they drift the first time a knob gains a
 * value; written once they cannot.
 */
internal object AppearanceNorm {

    // ---- theme root ----

    /**
     * Segments write dark|amoled|light|system. "system" is a new value of the existing
     * key, not a new key, so no schema bump; it is resolved to a concrete column inside
     * the appearance spec, never here, which keeps LeanColors and the MD3 scheme from
     * disagreeing about what "system" meant this frame.
     */
    fun themeMode(v: String?): String = when (v) {
        "amoled" -> "amoled"
        "light" -> "light"
        "system" -> "system"
        else -> "dark"
    }

    fun themeSchedMode(v: String?): String = when (v) {
        "dark" -> "dark"
        else -> "amoled"
    }

    fun amoledDepth(v: String?): String = when (v) {
        "soft" -> "soft"
        else -> "absolute"
    }

    fun minuteOfDay(v: Int?, fallback: Int): Int =
        (v ?: fallback).coerceIn(AppearanceRanges.MINUTE_MIN, AppearanceRanges.MINUTE_MAX)

    fun contrastLevel(v: Int?): Int =
        (v ?: 0).coerceIn(AppearanceRanges.CONTRAST_MIN, AppearanceRanges.CONTRAST_MAX)

    // ---- colour ----

    fun accentSource(v: String?): String = when (v) {
        "custom" -> "custom"
        "wallpaper" -> "wallpaper"
        else -> "preset"
    }

    fun accentChroma(v: Int?): Int = (v ?: SettingsDefaults.ACCENT_CHROMA)
        .coerceIn(AppearanceRanges.ACCENT_CHROMA_MIN, AppearanceRanges.ACCENT_CHROMA_MAX)

    fun surfaceTint(v: Int?): Int = (v ?: SettingsDefaults.SURFACE_TINT)
        .coerceIn(AppearanceRanges.SURFACE_TINT_MIN, AppearanceRanges.SURFACE_TINT_MAX)

    fun connectedMode(v: String?): String = when (v) {
        "accent" -> "accent"
        else -> "sage"
    }

    fun errorColor(v: String?): String = when (v) {
        "crimson" -> "crimson"
        "amber" -> "amber"
        else -> "coral"
    }

    /** A translucent accent would silently wash out every surface it tints. */
    fun opaque(argb: Long): Long = (argb and 0x00FFFFFFL) or 0xFF000000L

    fun roleOverrides(map: Map<String, Long>): Map<String, Long> =
        map.filterKeys { it in AppearanceRoles.all }.mapValues { (_, v) -> opaque(v) }

    // ---- type ----

    /** Same four families for both roles; only the fallback differs. */
    fun fontFamily(v: String?, fallback: String): String = when (v) {
        "unbounded" -> "unbounded"
        "onest" -> "onest"
        "system" -> "system"
        "mono" -> "mono"
        else -> fallback
    }

    fun textScale(v: Int?): Int = snap(v ?: 100, AppearanceRanges.TEXT_SCALE_STEPS)

    fun fontWeightDelta(v: Int?): Int = snap(v ?: 0, AppearanceRanges.FONT_WEIGHT_STEPS)

    // ---- shape and density ----

    fun cornerStyle(v: String?): String = when (v) {
        "sharp" -> "sharp"
        "crisp" -> "crisp"
        "soft" -> "soft"
        "round" -> "round"
        else -> "normal"
    }

    fun uiDensity(v: String?): String = when (v) {
        "compact" -> "compact"
        "comfortable" -> "comfortable"
        else -> "normal"
    }

    fun outlineWeight(v: String?): String = when (v) {
        "none" -> "none"
        "strong" -> "strong"
        else -> "thin"
    }

    fun dividerIndent(v: String?): String = when (v) {
        "full" -> "full"
        else -> "inset"
    }

    fun cardShadow(v: String?): String = when (v) {
        "none" -> "none"
        "deep" -> "deep"
        else -> "soft"
    }

    // ---- home screen ----

    fun heroStyle(v: String?): String = when (v) {
        "disc" -> "disc"
        "pulse" -> "pulse"
        "minimal" -> "minimal"
        else -> "ring"
    }

    fun heroSize(v: Int?): Int = snap(v ?: 100, AppearanceRanges.HERO_SIZE_STEPS)

    fun heroGlyph(v: String?): String = when (v) {
        "shield" -> "shield"
        "globe" -> "globe"
        "pulse" -> "pulse"
        else -> "power"
    }

    fun trafficRow(v: String?): String = when (v) {
        "hidden" -> "hidden"
        "compact" -> "compact"
        else -> "large"
    }

    fun quickPeek(v: Int?): Int = (v ?: SettingsDefaults.QUICK_PEEK)
        .coerceIn(AppearanceRanges.QUICK_PEEK_MIN, AppearanceRanges.QUICK_PEEK_MAX)

    fun homeBlocks(v: Int?): Int = (v ?: SettingsDefaults.HOME_BLOCKS)
        .coerceIn(AppearanceRanges.HOME_BLOCKS_MIN, AppearanceRanges.HOME_BLOCKS_MAX)

    fun currentServerLabel(v: String?): String = when (v) {
        "name_proto" -> "name_proto"
        "hidden" -> "hidden"
        else -> "name"
    }

    // ---- server list ----

    fun latencyPalette(v: String?): String = when (v) {
        "traffic" -> "traffic"
        "mono" -> "mono"
        "gradient" -> "gradient"
        else -> "accent"
    }

    /**
     * The three thresholds are clamped in order, so a reordered pair from a foreign
     * backup cannot make a tier unreachable. Each upper bound is comfortably above the
     * next lower bound, so no coerceIn ever sees an empty range.
     */
    fun latT1(v: Int?): Int = (v ?: SettingsDefaults.LAT_T1)
        .coerceIn(AppearanceRanges.LAT_T1_MIN, AppearanceRanges.LAT_T1_MAX)

    fun latT2(v: Int?, t1: Int): Int = (v ?: SettingsDefaults.LAT_T2)
        .coerceIn(t1 + 1, AppearanceRanges.LAT_T2_MAX)

    fun latT3(v: Int?, t2: Int): Int = (v ?: SettingsDefaults.LAT_T3)
        .coerceIn(t2 + 1, AppearanceRanges.LAT_T3_MAX)

    /**
     * Which server-tag kinds a row states, as a flag string over p/s/t
     * (protocol / security / transport, see ui.components.TagKind).
     *
     * Order and case are normalised so the value is comparable and the preset share-code
     * is stable. An empty result would silently blank every row's tags while «Плашки»
     * still read as on, which is indistinguishable from a bug, so a value that keeps
     * nothing falls back to the default rather than to "".
     */
    fun serverTagKinds(v: String?): String {
        val kept = (v ?: "").lowercase().filter { it in "pst" }.toSet()
        val ordered = "pst".filter { it in kept }
        return ordered.ifEmpty { SettingsDefaults.SERVER_TAG_KINDS }
    }

    fun latencyMeter(v: String?): String = when (v) {
        "ms" -> "ms"
        "bars" -> "bars"
        else -> "bars_ms"
    }

    fun serverRow(v: String?): String = when (v) {
        "compact" -> "compact"
        "detailed" -> "detailed"
        else -> "normal"
    }

    fun selectionCue(v: String?): String = when (v) {
        "stripe" -> "stripe"
        "wash" -> "wash"
        "none" -> "none"
        else -> "both"
    }

    fun selectionWash(v: Int?): Int = (v ?: SettingsDefaults.SELECTION_WASH)
        .coerceIn(AppearanceRanges.SELECTION_WASH_MIN, AppearanceRanges.SELECTION_WASH_MAX)

    // ---- motion ----

    fun motionLevel(v: String?): String = when (v) {
        "off" -> "off"
        "calm" -> "calm"
        "lively" -> "lively"
        else -> "normal"
    }

    fun colorCrossfade(v: String?): String = when (v) {
        "off" -> "off"
        else -> "on"
    }

    fun haptics(v: String?): String = when (v) {
        "none" -> "none"
        "light" -> "light"
        else -> "normal"
    }

    // ---- background and system chrome ----

    /** Scrim over «своя картинка», percent. Clamped to a band that always leaves the
     * picture visible and the interface legible, 0 would bury the UI under a photo,
     * 100 would hide the photo entirely and make the style pointless. */
    fun bgImageDim(v: Int?): Int =
        (v ?: SettingsDefaults.BG_IMAGE_DIM).coerceIn(
            AppearanceRanges.BG_IMAGE_DIM_MIN,
            AppearanceRanges.BG_IMAGE_DIM_MAX,
        )

    fun bgImageBlur(v: Int?): Int = (v ?: SettingsDefaults.BG_IMAGE_BLUR)
        .coerceIn(AppearanceRanges.BG_IMAGE_BLUR_MIN, AppearanceRanges.BG_IMAGE_BLUR_MAX)

    fun bgImageSaturation(v: Int?): Int = (v ?: SettingsDefaults.BG_IMAGE_SATURATION)
        .coerceIn(AppearanceRanges.BG_IMAGE_SATURATION_MIN, AppearanceRanges.BG_IMAGE_SATURATION_MAX)

    fun bgImageZoom(v: Int?): Int = (v ?: SettingsDefaults.BG_IMAGE_ZOOM)
        .coerceIn(AppearanceRanges.BG_IMAGE_ZOOM_MIN, AppearanceRanges.BG_IMAGE_ZOOM_MAX)

    fun glassTint(v: Int?): Int = (v ?: SettingsDefaults.GLASS_TINT)
        .coerceIn(AppearanceRanges.GLASS_TINT_MIN, AppearanceRanges.GLASS_TINT_MAX)

    fun bgImageAlign(v: String?): String = when (v) {
        "top" -> "top"
        "bottom" -> "bottom"
        else -> "center"
    }

    fun bgStyle(v: String?): String = when (v) {
        "vignette" -> "vignette"
        "gradient" -> "gradient"
        "grain" -> "grain"
        "image" -> "image"
        else -> "flat"
    }

    fun sysbarInk(v: String?): String = when (v) {
        "light" -> "light"
        "dark" -> "dark"
        else -> "auto"
    }

    // ---- shared with the rest of Settings ----

    /**
     * Every launcher variant this build ships. Anything else, a key from a newer build
     * restored into an older one, or junk, normalizes to the default, same policy as
     * [themeMode], and AppIcon.apply treats an unknown value the same way.
     *
     * A list that falls behind the variants on offer does not look like a bug from the
     * outside: choosing an icon writes the new key and the launcher changes, and only the
     * read path turns it back into "default", so the picker draws its ring on the wrong
     * tile. AppIconWiringTest pins this against AppIcon's own map so the two cannot drift.
     */
    val APP_ICON_KEYS: Set<String> = setOf(
        "default",
        "accent",
        "pack03",
        "pack04",
        "outline",
        "pack06",
        "sunset",
        "pack08",
        "pack09",
        "obsidian",
        "frost",
        "neon",
        "pack13",
        "pack14",
        "pack15",
        "pack16",
        "pack17",
        "pack18",
        "pack19",
        "pack20",
        "pack21",
        "pack23",
        "black",
    )

    fun appIcon(v: String?): String = if (v in APP_ICON_KEYS) checkNotNull(v) else "default"

    /**
     * This one reaches the core, not just the UI: the value is written verbatim into the
     * sing-box config, where an unrecognised level fails the whole start. Accept every
     * level sing-box knows (a user who set "debug" in an older build keeps it), and send
     * anything else to the default.
     *
     * "info" needs its own branch: the default is quieter than it (see
     * SettingsDefaults.LOG_LEVEL), so without the line an explicit choice of info, the
     * one level picked precisely for the per-connection narration, would be downgraded.
     */
    fun logLevel(v: String?): String = when (v) {
        "trace" -> "trace"
        "debug" -> "debug"
        "info" -> "info"
        "warn" -> "warn"
        "error" -> "error"
        "fatal" -> "fatal"
        "panic" -> "panic"
        else -> SettingsDefaults.LOG_LEVEL
    }

    private fun snap(value: Int, steps: IntArray): Int {
        var best = steps[0]
        for (step in steps) {
            if (abs(step - value) < abs(best - value)) best = step
        }
        return best
    }
}

/**
 * Its own Json rather than [Serialization.json], for one reason: `encodeDefaults = false`
 * is what keeps a share code around a hundred characters instead of six hundred, only
 * the fields a look actually moves travel, and every AppearanceProfile field has a
 * default to restore the rest. `ignoreUnknownKeys` then lets a code minted by a newer
 * build still apply on an older one.
 */
private val appearanceJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = false
    isLenient = true
}

/** ~40x the largest legitimate payload, a ceiling, not a limit anyone can reach. */
private const val MAX_CODE_BODY = 8_192
private const val MAX_INFLATED = 64 * 1024
private const val MAX_PRESET_NAME = 40

private fun deflate(raw: ByteArray): ByteArray {
    // Zlib-wrapped (nowrap = false): raw deflate would save six bytes but
    // needs the notorious extra dummy input byte on the Inflater side, and the adler32
    // checksum the wrapper carries rejects a mistyped code before JSON parsing does.
    val deflater = Deflater(Deflater.BEST_COMPRESSION)
    try {
        deflater.setInput(raw)
        deflater.finish()
        val out = ByteArrayOutputStream(raw.size / 2 + 32)
        val buffer = ByteArray(1024)
        while (!deflater.finished()) {
            val n = deflater.deflate(buffer)
            if (n <= 0) break
            out.write(buffer, 0, n)
        }
        return out.toByteArray()
    } finally {
        deflater.end()
    }
}

private fun inflate(packed: ByteArray): ByteArray? {
    val inflater = Inflater()
    try {
        inflater.setInput(packed)
        val out = ByteArrayOutputStream(packed.size * 4)
        val buffer = ByteArray(1024)
        while (!inflater.finished()) {
            val n = inflater.inflate(buffer)
            if (n == 0) {
                // Truncated payload: the stream wants more input that will never come.
                // Without this the loop would spin forever on a half-copied code.
                if (inflater.needsInput() || inflater.needsDictionary()) return null
            } else {
                out.write(buffer, 0, n)
                if (out.size() > MAX_INFLATED) return null
            }
        }
        return out.toByteArray()
    } catch (e: DataFormatException) {
        return null
    } finally {
        inflater.end()
    }
}

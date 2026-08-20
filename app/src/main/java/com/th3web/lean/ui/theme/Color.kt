package com.th3web.lean.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import com.th3web.lean.data.AppearanceRoles
import kotlin.math.abs

/**
 * Default fraction of the accent's primary blended into neutral surface tokens
 * in the NORMAL (non-AMOLED) dark theme, small enough to read as a tinted gray,
 * never as a brighter surface.
 *
 * Since «Оформление → Оттенок поверхностей» this is only the DEFAULT of
 * [LeanAccent.applyTo]'s `tintAmount` parameter and the value the 6% setting
 * resolves to, so today's look is reproduced bit-for-bit at the default.
 */
internal const val LeanNeutralTintAmount = 0.06f

/** One contrast step = 4% of the way toward the far end of the lightness ladder. */
private const val ContrastStep = 0.04f

/**
 * Blend a small [amount] of [accent] into a neutral [base] surface to give it a
 * faint accent **hue**, without changing its darkness, MD3 "tonal surfaces".
 *
 * We [lerp] toward the accent, then renormalise the result back to the base's
 * original relative [luminance] by scaling its RGB channels. The hue/chroma of
 * the accent leaks in while the perceived brightness is held to the base, so a
 * dark gray becomes a dark *tinted* gray rather than a lighter one. Alpha is the
 * base's. Pass `amount == 0f` (or use this only in normal mode) to no-op.
 */
internal fun tintNeutral(base: Color, accent: Color, amount: Float): Color {
    if (amount <= 0f) return base
    val mixed = lerp(base, accent, amount)
    val baseLum = base.luminance()
    val mixedLum = mixed.luminance()
    // Rescale the tinted colour back down to the base's luminance so the tint
    // shifts hue only, never brightness. Guard the near-black case (lum ~ 0),
    // where scaling is undefined/explosive, there's nothing to brighten anyway.
    // This is also why the AMOLED canvas stays exactly #000000 even with the
    // «Оттенок на AMOLED» switch on: only the raised cards can take the hue.
    if (mixedLum <= 1e-4f || baseLum <= 1e-4f) return base
    val k = baseLum / mixedLum
    // k > 1 means the accent is darker than the base (the light-column case):
    // up-scaling the channels would saturate the already-near-white RGB to pure
    // white and discard the very hue we wanted. Instead keep the lerped result as
    // a faintly darker *tinted* surface, preserves the accent hue on light tokens.
    // Dark surfaces (accent brighter, k <= 1) are unaffected and still renormalise.
    if (k > 1f) return mixed.copy(alpha = base.alpha)
    return Color(
        red = (mixed.red * k).coerceIn(0f, 1f),
        green = (mixed.green * k).coerceIn(0f, 1f),
        blue = (mixed.blue * k).coerceIn(0f, 1f),
        alpha = base.alpha,
        colorSpace = base.colorSpace,
    )
}

/**
 * «Контрастность», push a token [step] × 4% toward (or away from) the far end of its
 * canvas's lightness ladder. Sibling of [tintNeutral]: that one moves hue and holds
 * lightness, this one moves lightness and holds hue.
 *
 * The direction is what the [onLight]/[ink] pair encodes. On a dark canvas more contrast
 * means surfaces sink toward black and inks climb toward white; on a light canvas both
 * reverse. A negative step collapses them toward each other instead, for people who find
 * the stock separation harsh. Alpha survives the [lerp] (which would otherwise pull a
 * translucent ink like `BarUnlit` toward the opaque endpoint's alpha).
 *
 * Applied per token rather than by regenerating the columns from a tonal
 * palette: the three hand-authored columns encode relationships a generator would lose,
 * `AmoledOutline` is intentionally one step dimmer than `DarkOutline`, and `LightHairline`
 * is intentionally lighter than `LightOutline` where both dark columns have the hairline
 * darker than the outline. That inversion is per-column, not derivable.
 */
internal fun contrastAdjust(base: Color, step: Int, onLight: Boolean, ink: Boolean = false): Color {
    if (step == 0) return base
    // Which endpoint does more contrast pull this token toward? Light surfaces and dark
    // inks go white; dark surfaces and light inks go black. That is exactly `onLight xor ink`.
    val towardWhite = onLight != ink
    val positive = step > 0
    val target = if (towardWhite == positive) Color.White else Color.Black
    return lerp(base, target, abs(step) * ContrastStep).copy(alpha = base.alpha)
}

// ── The three hand-authored neutral columns ──────────────────────────────────
//
// File-level (not members of LeanColors), so the pure [leanPalette] can read them: the
// preview has to build a complete draft palette for a look that is not the active one
// without touching a single global token.

private class NeutralColumn(
    val background: Color,
    val surface: Color,
    val surfaceVariant: Color,
    val surfaceElevated: Color,
    val outline: Color,
    val hairline: Color,
)

/** Dark ("Обычная") column. */
private val DarkColumn = NeutralColumn(
    background = Color(0xFF101319),       // surface / surfaceDim
    surface = Color(0xFF1C1F26),          // surfaceContainer — card base
    surfaceVariant = Color(0xFF262A31),   // surfaceContainerHigh — chips, dialogs
    surfaceElevated = Color(0xFF31343C),  // surfaceContainerHighest — pills, badges, tracks
    outline = Color(0xFF3F4550),          // outlineVariant
    hairline = Color(0xFF2E323A),         // divider ink, one step under outlineVariant
)

/** AMOLED column, «глубина чёрного → абсолютная» (true-black canvas). */
private val AmoledColumn = NeutralColumn(
    background = Color(0xFF000000),       // pure black
    surface = Color(0xFF12151B),
    surfaceVariant = Color(0xFF1A1D24),
    surfaceElevated = Color(0xFF24272F),
    outline = Color(0xFF353B45),          // one step dimmer — no hairline glow on #000
    hairline = Color(0xFF23262D),
)

/** AMOLED column, «мягкая», same black canvas, raised ladder lifted one step. */
private val AmoledSoftColumn = NeutralColumn(
    background = Color(0xFF000000),
    surface = Color(0xFF181B22),
    surfaceVariant = Color(0xFF20242C),
    surfaceElevated = Color(0xFF2C3038),
    outline = Color(0xFF3D4450),
    hairline = Color(0xFF2A2E36),
)

/**
 * light column (cool off-white canvas), same cool-neutral family as the dark column,
 * mirrored: the canvas is the brightest step and the container ladder descends into light
 * grays, so cards read as quiet tonal panes, never stark white-on-white.
 */
private val LightColumn = NeutralColumn(
    background = Color(0xFFFAF9FD),       // surface — airy cool off-white
    surface = Color(0xFFEFEDF4),          // surfaceContainer — card base
    surfaceVariant = Color(0xFFE8E7EF),   // surfaceContainerHigh — chips, dialogs
    surfaceElevated = Color(0xFFE2E1E9),  // surfaceContainerHighest — pills, badges, tracks
    outline = Color(0xFFC4C6D0),          // outlineVariant
    hairline = Color(0xFFD8DAE2),         // divider ink, one step QUIETER (lighter)
)

// ── The two ink columns ──────────────────────────────────────────────────────
//
// Seed-invariant foreground colours. The light column is the tone-40 mirror: the muted
// glyph tints, the reserved sage and the latency ladder all flip to their dark-on-light
// tones so they keep their MD3 duty (and their contrast) on the light canvas.

private class InkColumn(
    val textPrimary: Color,
    val textSecondary: Color,
    val textTertiary: Color,
    val sage: Color,
    val ember: Color,
    val emberRed: Color,
    val blue: Color,
    val violet: Color,
    val tagInk: Color,
    val tagGhost: Color,
    val latency2: Color,
    val latency3: Color,
    val latency4: Color,
)

private val DarkInk = InkColumn(
    textPrimary = Color(0xFFE0E2E9),      // onSurface
    textSecondary = Color(0xFFC2C6D1),    // onSurfaceVariant
    textTertiary = Color(0xFF8B919D),     // outline — captions, chevrons
    sage = Color(0xFF98D1A6),             // reserved security-positive green
    ember = Color(0xFFD5BD8E),            // muted sand
    emberRed = Color(0xFFCC8E89),         // muted clay
    blue = Color(0xFF9DB9DE),             // muted steel
    violet = Color(0xFFC5C1DD),           // tertiary
    tagInk = Color(0xCCE9EDF5),           // ghost-tag ink — cool-white @ 0.80
    tagGhost = Color(0x1AFFFFFF),         // ghost-tag fill — white 10%
    latency2 = Color(0xFF9FB3D1),         // ≤t2 — light steel
    latency3 = Color(0xFF8B919D),         // ≤t3 — outline gray
    latency4 = Color(0xFF646A76),         // >t3 — dim gray
)

private val LightInk = InkColumn(
    textPrimary = Color(0xFF1A1C22),
    textSecondary = Color(0xFF44464E),
    textTertiary = Color(0xFF74777F),
    sage = Color(0xFF2F6B40),             // sage T40 — "Подключено" on light
    ember = Color(0xFF6F5D2F),
    emberRed = Color(0xFF7A534D),
    blue = Color(0xFF44608C),
    violet = Color(0xFF605880),
    tagInk = Color(0xCC32363F),           // ghost-tag ink — cool-ink @ 0.80
    tagGhost = Color(0x141A1C22),         // ghost-tag fill — near-black 8%
    // The light twins are not a mechanical inversion: the dark tints read ~1.8–2.7:1 on
    // the light card, and the dark tier-4 is darker than tier 2's light tint, which would
    // invert the ramp. These keep every lit tint ≥3:1 on `surfaceContainer` (#EFEDF4).
    latency2 = Color(0xFF5B7297),         // ≈T45, ~4.2:1
    latency3 = Color(0xFF74777F),         // ≈T48, ~3.9:1
    latency4 = Color(0xFF848892),         // ≈T56, ~3.1:1 — the quietest lit tint
)

// ── Error inks ───────────────────────────────────────────────────────────────
//
// «Цвет ошибок» has to move both paths: LeanColors.Error (what the app's own components
// read), and the scheme's error/onError/*Container roles (what stock M3 dialogs and text
// fields read). LeanAccent never touches them, so they live here.

private class ErrorInk(
    val error: Color,
    val onError: Color,
    val container: Color,
    val onContainer: Color,
)

private val CoralError = ErrorInk(Color(0xFFFFB4AB), Color(0xFF690005), Color(0xFF93000A), Color(0xFFFFDAD6))
private val CoralErrorLight = ErrorInk(Color(0xFFBA1A1A), Color(0xFFFFFFFF), Color(0xFFFFDAD6), Color(0xFF410002))

/** Deeper, redder, for people who read the stock coral as "warning", not "error". */
private val CrimsonError = ErrorInk(Color(0xFFFF8A80), Color(0xFF5C0007), Color(0xFF7E0009), Color(0xFFFFDAD4))
private val CrimsonErrorLight = ErrorInk(Color(0xFF8E0012), Color(0xFFFFFFFF), Color(0xFFFFDAD4), Color(0xFF33000A))

/** The red-deficient option: failure reads as heat, not hue. */
private val AmberError = ErrorInk(Color(0xFFF2C078), Color(0xFF432C00), Color(0xFF614100), Color(0xFFFFDEA8))
private val AmberErrorLight = ErrorInk(Color(0xFF7A5900), Color(0xFFFFFFFF), Color(0xFFFFDEA8), Color(0xFF261A00))

private fun errorInk(key: String, onLight: Boolean): ErrorInk = when (key) {
    "crimson" -> if (onLight) CrimsonErrorLight else CrimsonError
    "amber" -> if (onLight) AmberErrorLight else AmberError
    else -> if (onLight) CoralErrorLight else CoralError
}

/** Hues closer than this to the error ink are indistinguishable from "something broke". */
private const val ErrorHueGuardDegrees = 22f

/**
 * Would [accent] be misread as the active error ink?
 *
 * The one guard behind «Цвет „Подключено" → акцент». The app has exactly one
 * reassuring moment, and a garnet or amber seed sitting inside the error hue would
 * turn "you are protected" into "something failed", a wrong signal, not a taste
 * question. A near-gray accent is exempt: with no chroma to speak of it reads as neutral
 * whatever its nominal hue is (that is `Графит`, whose hue is meaningless).
 *
 * Compared on the dark column: that is where both inks are at their most
 * saturated and the collision is worst, so the verdict does not flip with the canvas.
 */
internal fun accentClashesWithError(accent: Color, errorKey: String): Boolean {
    val a = hsvOf(accent)
    if (a[1] < 0.12f) return false
    return hueDistance(a[0], hsvOf(errorInk(errorKey, onLight = false).error)[0]) < ErrorHueGuardDegrees
}

// ── Canvas-agnostic inks ─────────────────────────────────────────────────────

/** Null / timeout latency tier (zero bars lit, readout "-"): a mid gray-blue, reads on every canvas. */
private val LatencyNoneInk = Color(0xFF474D58)

/**
 * Unlit latency bars / unfilled traffic track, outline-gray @ 18% alpha, a ghost on both
 * canvases (≈`#30333B` over the dark card, ≈`#DBDDE3` over the light one).
 */
private val BarUnlitInk = Color(0x2E8B919D)

/** Depth-shadow peak ink, black @ 0.25 (shadow ink is canvas-agnostic). */
private val DepthShadowPeak = Color(0x40000000)

/** The frozen wordmark brush stops, used when «Логотип по акценту» is off. */
private val WordmarkFixedDark = Color(0xFFE0E2E9) to Color(0xFFAEBDD6)
private val WordmarkFixedLight = Color(0xFF22262E) to Color(0xFF46608A)

private val InertGlassTop = Color(0x0DFFFFFF)     // legacy ink, inert
private val InertGlassBottom = Color(0x08FFFFFF)  // legacy ink, inert

/**
 * A complete resolved look, every colour the app can draw, for one [AppearanceSpec].
 *
 * Produced by the pure [leanPalette] and consumed two ways: [LeanColors.adopt] publishes
 * it to the global tokens for the live app, and the Оформление preview receives it as a
 * parameter. That second path is why this type exists at all, a draft palette (a preset
 * thumbnail, a slider mid-drag) must be renderable without writing anything global, or
 * every frame of a drag would repaint the entire app behind the sheet.
 *
 * [scheme] is the MD3 counterpart of the same resolution, so "the LeanColors token" and
 * "the MD3 role" can no longer disagree: they are two views of one computation.
 */
@Immutable
data class LeanPalette(
    val scheme: ColorScheme,
    val light: Boolean,
    val amoled: Boolean,
    val background: Color,
    val surface: Color,
    val surfaceVariant: Color,
    val surfaceElevated: Color,
    val outline: Color,
    val hairline: Color,
    val accent: Color,
    val accentDim: Color,
    val onAccent: Color,
    val connected: Color,
    val connecting: Color,
    val error: Color,
    val ember: Color,
    val emberRed: Color,
    val blue: Color,
    val violet: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textTertiary: Color,
    val tagInk: Color,
    val tagGhost: Color,
    val latencyTier1: Color,
    val latencyTier2: Color,
    val latencyTier3: Color,
    val latencyTier4: Color,
    val latencyNone: Color,
    val barUnlit: Color,
    val depthShadowInk: Color,
    val gradientInkStart: Color,
    val gradientInkEnd: Color,
)

/**
 * The single colour resolver. pure: it reads [spec] and the constants above, writes
 * nothing, and given equal specs returns equal palettes.
 *
 * Order matters and is deliberate: tint (hue) → contrast (lightness) → semantic
 * repointing (error ink, connected source, latency ramp) → role overrides. Overrides land
 * last because the user picked an exact colour and nothing downstream should second-guess
 * it; contrast lands before them because a hand-picked colour is already the contrast the
 * user wanted.
 */
internal fun leanPalette(spec: AppearanceSpec): LeanPalette {
    val onLight = spec.light
    val step = spec.contrastLevel
    val accent = spec.accent
    val column = when {
        spec.amoled -> if (spec.amoledDepth == "soft") AmoledSoftColumn else AmoledColumn
        onLight -> LightColumn
        else -> DarkColumn
    }
    val inks = if (onLight) LightInk else DarkInk
    // The tint always lerps toward the seed's T80 primary, on both columns: it is the
    // high-luminance tone, so tintNeutral's renormalisation distorts it least.
    val tintSource = accent.primary

    // A true-black AMOLED canvas must stay true black, black pixels being OFF pixels is
    // the whole contract. tintNeutral already leaves a zero-luminance base alone; a
    // negative contrast step would otherwise lift it to a dark gray, which is
    // what someone who chose AMOLED did not ask for.
    val pureBlack = spec.amoled

    fun neutral(c: Color): Color = when {
        pureBlack && c.luminance() <= 1e-4f -> c
        else -> contrastAdjust(tintNeutral(c, tintSource, spec.surfaceTint), step, onLight)
    }

    fun ink(c: Color): Color = contrastAdjust(c, step, onLight, ink = true)

    // «Контуры»: `strong` pushes the hairlines two steps the other way (toward visible),
    // `none` makes them fully transparent so a stale 1dp border literal still draws
    // nothing while B2's width tokens are landing.
    val outlineAlpha = if (spec.outlineWeight == "none") 0f else 1f
    fun rim(c: Color): Color {
        val base = neutral(c)
        val boosted = if (spec.outlineWeight == "strong") contrastAdjust(base, 2, onLight, ink = true) else base
        return if (outlineAlpha >= 1f) boosted else boosted.copy(alpha = boosted.alpha * outlineAlpha)
    }

    val background = neutral(column.background)
    val surface = neutral(column.surface)
    val surfaceVariant = neutral(column.surfaceVariant)
    val surfaceElevated = neutral(column.surfaceElevated)
    val outline = rim(column.outline)
    val hairline = rim(column.hairline)

    val textPrimary = ink(inks.textPrimary)
    val textSecondary = ink(inks.textSecondary)
    val textTertiary = ink(inks.textTertiary)

    val accentInk = if (onLight) accent.lightPrimary else accent.primary
    val accentDim = if (onLight) accent.lightDim else accent.dim
    val onAccent = if (onLight) accent.lightOnPrimary else accent.onPrimary
    val connecting = if (onLight) accent.lightConnecting else accent.connecting

    val sage = ink(inks.sage)
    val ember = ink(inks.ember)
    val emberRed = ink(inks.emberRed)
    val blue = ink(inks.blue)
    val violet = ink(inks.violet)
    val errors = errorInk(spec.errorColor, onLight)
    val error = ink(errors.error)
    // «Цвет „Подключено"»: sage by default and by design, the one security-positive
    // moment in the app does not move with the accent. The spec has already vetoed
    // `accent` if the seed sits too close to the error hue to be read as "good".
    val connected = if (spec.connectedAccent) accentInk else sage

    val latencyNone = ink(LatencyNoneInk)
    val (tier1, tier2, tier3, tier4) = latencyRamp(
        mode = spec.latencyPalette,
        accent = accentInk,
        accentDim = accentDim,
        sage = sage,
        ember = ember,
        emberRed = emberRed,
        error = error,
        textPrimary = textPrimary,
        textSecondary = textSecondary,
        textTertiary = textTertiary,
        latencyNone = latencyNone,
        steel2 = ink(inks.latency2),
        steel3 = ink(inks.latency3),
        steel4 = ink(inks.latency4),
    )

    // The wordmark is the app's only gradient, and it follows the accent rather than
    // ending on a fixed tone whatever the seed.
    val fixed = if (onLight) WordmarkFixedLight else WordmarkFixedDark
    val gradientStart = if (spec.wordmarkAccent) textPrimary else ink(fixed.first)
    val gradientEnd = when {
        !spec.wordmarkAccent -> ink(fixed.second)
        onLight -> accentInk
        else -> lerp(textPrimary, accentDim, 0.5f)
    }

    val overrides = spec.roleOverrides
    fun slot(role: String): Color? = overrides[role]?.let { Color(it.toInt()) }

    val ovBackground = slot(AppearanceRoles.BACKGROUND)
    val ovSurface = slot(AppearanceRoles.SURFACE)
    val ovPanel = slot(AppearanceRoles.PANEL)
    val ovOutline = slot(AppearanceRoles.OUTLINE)
    val ovAccent = slot(AppearanceRoles.ACCENT)
    val ovTextPrimary = slot(AppearanceRoles.TEXT_PRIMARY)
    val ovTextSecondary = slot(AppearanceRoles.TEXT_SECONDARY)
    val ovConnected = slot(AppearanceRoles.CONNECTED)
    val ovError = slot(AppearanceRoles.ERROR)
    val ovTag = slot(AppearanceRoles.TAG)

    val finalAccent = ovAccent ?: accentInk
    val finalAccentDim = ovAccent?.let { dimOf(it, onLight) } ?: accentDim
    val finalOnAccent = ovAccent?.let { inkOn(it) } ?: onAccent
    val finalBackground = ovBackground ?: background
    val finalSurface = ovSurface ?: surface
    val finalPanel = ovPanel ?: surfaceVariant
    val finalElevated = ovPanel?.let { lerp(it, inkOn(it), 0.08f) } ?: surfaceElevated
    val finalOutline = ovOutline ?: outline
    val finalHairline = ovOutline?.copy(alpha = ovOutline.alpha * 0.7f) ?: hairline
    val finalTextPrimary = ovTextPrimary ?: textPrimary
    val finalTextSecondary = ovTextSecondary ?: textSecondary
    val finalTextTertiary = ovTextSecondary?.copy(alpha = ovTextSecondary.alpha * 0.75f) ?: textTertiary
    val finalConnected = ovConnected ?: connected
    val finalError = ovError ?: error
    val finalTagInk = ovTag ?: ink(inks.tagInk)
    val finalTagGhost = ovTag?.copy(alpha = 0.12f) ?: ink(inks.tagGhost)

    val baseScheme = when {
        spec.amoled -> if (spec.amoledDepth == "soft") LeanAmoledSchemeSoft else LeanAmoledScheme
        onLight -> LeanLightScheme
        else -> LeanDarkScheme
    }
    val scheme = baseScheme
        .let { if (onLight) accent.applyToLight(it, spec.surfaceTint) else accent.applyTo(it, spec.surfaceTint) }
        .copy(
            error = errors.error,
            onError = errors.onError,
            errorContainer = errors.container,
            onErrorContainer = errors.onContainer,
        )
        .contrastPass(step, onLight, pureBlack)
        .overridePass(
            onLight = onLight,
            background = ovBackground,
            surface = ovSurface,
            panel = ovPanel,
            outline = ovOutline,
            accent = ovAccent,
            textPrimary = ovTextPrimary,
            textSecondary = ovTextSecondary,
            error = ovError,
        )

    return LeanPalette(
        scheme = scheme,
        light = onLight,
        amoled = spec.amoled,
        background = finalBackground,
        surface = finalSurface,
        surfaceVariant = finalPanel,
        surfaceElevated = finalElevated,
        outline = finalOutline,
        hairline = finalHairline,
        accent = finalAccent,
        accentDim = finalAccentDim,
        onAccent = finalOnAccent,
        connected = finalConnected,
        connecting = connecting,
        error = finalError,
        ember = ember,
        emberRed = emberRed,
        blue = blue,
        violet = violet,
        textPrimary = finalTextPrimary,
        textSecondary = finalTextSecondary,
        textTertiary = finalTextTertiary,
        tagInk = finalTagInk,
        tagGhost = finalTagGhost,
        latencyTier1 = tier1,
        latencyTier2 = tier2,
        latencyTier3 = tier3,
        latencyTier4 = tier4,
        latencyNone = latencyNone,
        barUnlit = ink(BarUnlitInk),
        depthShadowInk = DepthShadowPeak,
        gradientInkStart = gradientStart,
        gradientInkEnd = gradientEnd,
    )
}

/** Four lit latency tiers, brightest (fastest) first. */
private data class LatencyRamp(
    val tier1: Color,
    val tier2: Color,
    val tier3: Color,
    val tier4: Color,
)

/**
 * «Цвет пинга». Every mode is built from already-resolved tokens, so the light column,
 * the contrast step and the accent seed reach the ramp for free: there is no second
 * per-canvas table to keep in sync.
 *
 *  - `accent` today's look: the seed's primary, then the steel luminance ladder.
 *  - `traffic` the one traffic-light option, for people who read brightness as noise.
 *  - `mono` the ink ladder, quality is carried purely by bar count.
 *  - `gradient` the seed's own T80→T60 fade across all four tiers.
 */
private fun latencyRamp(
    mode: String,
    accent: Color,
    accentDim: Color,
    sage: Color,
    ember: Color,
    emberRed: Color,
    error: Color,
    textPrimary: Color,
    textSecondary: Color,
    textTertiary: Color,
    latencyNone: Color,
    steel2: Color,
    steel3: Color,
    steel4: Color,
): LatencyRamp = when (mode) {
    "traffic" -> LatencyRamp(sage, ember, emberRed, error)
    "mono" -> LatencyRamp(textPrimary, textSecondary, textTertiary, latencyNone)
    "gradient" -> LatencyRamp(
        accent,
        lerp(accent, accentDim, 1f / 3f),
        lerp(accent, accentDim, 2f / 3f),
        accentDim,
    )
    else -> LatencyRamp(accent, steel2, steel3, steel4)
}

/**
 * The contrast step over the MD3 roles, the fifteen neutral/ink ones only. Accent,
 * container and inverse roles are left alone: they are tuned pairs, and moving one half
 * of a pair is how a scheme ends up with unreadable text on its own container.
 *
 * `outline` counts as an INK here (MD3 uses it for quiet text as well as strokes) while
 * `outlineVariant` counts as a surface (it is the hairline), the same split
 * [leanPalette] makes between `TextTertiary` and `Outline`. `error` moves with the inks
 * (it too is drawn on a surface), so it cannot drift from `LeanColors.Error`; `onError`
 * and the containers stay put, being the other half of tuned pairs.
 *
 * [pureBlack] holds the AMOLED canvas at `#000000` against a negative step, exactly as
 * [leanPalette] does for the token mirror.
 */
private fun ColorScheme.contrastPass(step: Int, onLight: Boolean, pureBlack: Boolean): ColorScheme {
    if (step == 0) return this
    fun s(c: Color) = if (pureBlack && c.luminance() <= 1e-4f) c else contrastAdjust(c, step, onLight)
    fun i(c: Color) = contrastAdjust(c, step, onLight, ink = true)
    return copy(
        error = i(error),
        background = s(background),
        surface = s(surface),
        surfaceDim = s(surfaceDim),
        surfaceBright = s(surfaceBright),
        surfaceContainerLowest = s(surfaceContainerLowest),
        surfaceContainerLow = s(surfaceContainerLow),
        surfaceContainer = s(surfaceContainer),
        surfaceContainerHigh = s(surfaceContainerHigh),
        surfaceContainerHighest = s(surfaceContainerHighest),
        surfaceVariant = s(surfaceVariant),
        outlineVariant = s(outlineVariant),
        onBackground = i(onBackground),
        onSurface = i(onSurface),
        onSurfaceVariant = i(onSurfaceVariant),
        outline = i(outline),
    )
}

/**
 * The ten semantic slots of «Цвета по ролям», mapped onto MD3. One slot moves its
 * LeanColors token and its MD3 role together, so a stock M3 dialog cannot disagree with a
 * hand-drawn card about what "panel" means.
 */
private fun ColorScheme.overridePass(
    onLight: Boolean,
    background: Color?,
    surface: Color?,
    panel: Color?,
    outline: Color?,
    accent: Color?,
    textPrimary: Color?,
    textSecondary: Color?,
    error: Color?,
): ColorScheme {
    var s = this
    if (background != null) {
        s = s.copy(background = background, surface = background, surfaceDim = background)
    }
    if (surface != null) {
        s = s.copy(surfaceContainer = surface, surfaceContainerLow = surface)
    }
    if (panel != null) {
        s = s.copy(
            surfaceVariant = panel,
            surfaceContainerHigh = panel,
            surfaceContainerHighest = lerp(panel, inkOn(panel), 0.08f),
        )
    }
    if (outline != null) {
        s = s.copy(outlineVariant = outline)
    }
    if (accent != null) {
        // inversePrimary is the other scheme's primary: one tone darker under a dark
        // scheme, one tone lighter under a light one, which is what dimOf
        // encodes for the canvas it is told about.
        s = s.copy(
            primary = accent,
            onPrimary = inkOn(accent),
            inversePrimary = dimOf(accent, onLight),
        )
    }
    if (textPrimary != null) {
        s = s.copy(onSurface = textPrimary, onBackground = textPrimary)
    }
    if (textSecondary != null) {
        s = s.copy(onSurfaceVariant = textSecondary, outline = textSecondary)
    }
    if (error != null) {
        s = s.copy(error = error)
    }
    return s
}

/** Readable ink for an arbitrary user-picked fill, near-black on bright, near-white on dark. */
private fun inkOn(fill: Color): Color =
    if (fill.luminance() > 0.42f) Color(0xFF10131A) else Color(0xFFF5F7FB)

/**
 * The "one tone quieter" partner of an arbitrary accent. On dark the dim tone is darker
 * than the accent (T60 under T80); on light it is lighter (T60 over T40), the same
 * inversion [LeanAccent.lightDim] encodes for the hand-tuned seeds.
 */
private fun dimOf(accent: Color, onLight: Boolean): Color =
    if (onLight) lerp(accent, Color.White, 0.22f) else lerp(accent, Color.Black, 0.26f)

/**
 * Lean palette, **"Refined Cool"** Material Design 3, dark + AMOLED + light.
 *
 * One desaturated steel-indigo accent (`primary #B1C4E6`) on strict MD3
 * cool-neutral tonal surfaces; one reserved muted sage green for the single
 * security-positive moment ([Connected]); stock MD3 error red. Depth is
 * carried by the tonal surface-container ladder plus 1dp hairlines, no glow
 * halos, no gradients (the wordmark ink in Type.kt is the lone exception).
 *
 * Every token maps onto an MD3 role (noted per property), so this object stays
 * a thin mirror of the active `ColorScheme` for legacy call-sites. Tokens
 * remain mutable Compose state so every existing `LeanColors.X` call-site
 * keeps compiling and recomposing live when the theme flips.
 *
 * This object is now publication only: it holds no colour logic. [adopt] copies a
 * [LeanPalette] into the snapshot tokens and nothing else writes here; the resolution
 * itself lives in the pure [leanPalette], and [LeanAppearance] is the single writer.
 */
object LeanColors {

    var Background by mutableStateOf(DarkColumn.background)
    /** True when the AMOLED (pure-black) theme is active. */
    var amoled by mutableStateOf(false)
    /** True when the light theme is active (light canvas → dark inks). */
    var light by mutableStateOf(false)
    var Surface by mutableStateOf(DarkColumn.surface)
    var SurfaceVariant by mutableStateOf(DarkColumn.surfaceVariant)
    var SurfaceElevated by mutableStateOf(DarkColumn.surfaceElevated)
    var Outline by mutableStateOf(DarkColumn.outline)

    /** Active seed primary (steel-indigo `#B1C4E6` by default). */
    var Accent by mutableStateOf(LeanAccents.Steel.primary)
    var AccentDim by mutableStateOf(LeanAccents.Steel.dim)
    var OnAccent by mutableStateOf(LeanAccents.Steel.onPrimary)

    /** Muted sage `#98D1A6`, only for the "Подключено" moment + connected latency semantics. */
    var Connected by mutableStateOf(DarkInk.sage)
    var Connecting by mutableStateOf(LeanAccents.Steel.connecting)
    var Error by mutableStateOf(CoralError.error)

    // Legacy aliases, glyph-tint duty only; never use for surfaces or text.
    var Ember by mutableStateOf(DarkInk.ember)
    var EmberRed by mutableStateOf(DarkInk.emberRed)
    var Bad by mutableStateOf(DarkInk.emberRed)
    var Blue by mutableStateOf(DarkInk.blue)
    var Violet by mutableStateOf(DarkInk.violet)

    var GlassTop by mutableStateOf(InertGlassTop)
    var GlassBottom by mutableStateOf(InertGlassBottom)
    /** = outlineVariant; `Modifier.glass()` borders with it. */
    var GlassBorder by mutableStateOf(DarkColumn.outline)
    var Hairline by mutableStateOf(DarkColumn.hairline)

    var TextPrimary by mutableStateOf(DarkInk.textPrimary)
    var TextSecondary by mutableStateOf(DarkInk.textSecondary)
    var TextTertiary by mutableStateOf(DarkInk.textTertiary)

    /** Ghost-tag text ink, cool-white @ 0.80 on dark, cool-ink @ 0.80 on light. */
    var TagInk by mutableStateOf(DarkInk.tagInk)

    /** Ghost-tag fill, white 10% on dark, near-black 8% on light. */
    var TagGhost by mutableStateOf(DarkInk.tagGhost)

    // ── Latency ramp ─────────────────────────────────────────────────────────
    //
    // State, not constants, because «Цвет пинга» and «Контрастность» both reach them.
    // Every reader must go through these: a private file-level copy is captured at class
    // load and frozen on whatever look was active first, which compiles clean and
    // misbehaves only on a live settings change.

    /** Fastest tier (4 bars), the seed primary unless «Цвет пинга» says otherwise. */
    var LatencyTier1 by mutableStateOf(LeanAccents.Steel.primary)
    var LatencyTier2 by mutableStateOf(DarkInk.latency2)
    var LatencyTier3 by mutableStateOf(DarkInk.latency3)
    var LatencyTier4 by mutableStateOf(DarkInk.latency4)

    /** Null / timeout tier (zero bars lit, readout "-"). */
    var LatencyNone by mutableStateOf(LatencyNoneInk)

    /**
     * Unlit latency bars / unfilled traffic track, outline-gray @ 18% alpha, a ghost on
     * both canvases. State now that «Контрастность» moves it; every reader must read it
     * inside a composable (see the note on the tiers above).
     */
    var BarUnlit by mutableStateOf(BarUnlitInk)

    /** Depth-shadow peak ink, black @ 0.25 (shadow ink is canvas-agnostic). */
    var DepthShadowInk by mutableStateOf(DepthShadowPeak)

    /** The wordmark brush stops, the app's only gradient (see `LeanType.appTitle`). */
    var GradientInkStart by mutableStateOf(WordmarkFixedDark.first)
    var GradientInkEnd by mutableStateOf(WordmarkFixedDark.second)

    /**
     * Publish [p] to every token. Idempotent and cheap: snapshot state uses structural
     * equality, so re-adopting an equal palette writes nothing and invalidates no reader.
     * Called only from [LeanAppearance].
     */
    fun adopt(p: LeanPalette) {
        amoled = p.amoled
        light = p.light

        Background = p.background
        Surface = p.surface
        SurfaceVariant = p.surfaceVariant
        SurfaceElevated = p.surfaceElevated
        Outline = p.outline
        Hairline = p.hairline
        GlassBorder = p.outline
        GlassTop = InertGlassTop
        GlassBottom = InertGlassBottom

        Accent = p.accent
        AccentDim = p.accentDim
        OnAccent = p.onAccent

        Connected = p.connected
        Connecting = p.connecting
        Error = p.error

        Ember = p.ember
        EmberRed = p.emberRed
        Bad = p.emberRed
        Blue = p.blue
        Violet = p.violet

        TextPrimary = p.textPrimary
        TextSecondary = p.textSecondary
        TextTertiary = p.textTertiary

        TagInk = p.tagInk
        TagGhost = p.tagGhost

        LatencyTier1 = p.latencyTier1
        LatencyTier2 = p.latencyTier2
        LatencyTier3 = p.latencyTier3
        LatencyTier4 = p.latencyTier4
        LatencyNone = p.latencyNone
        BarUnlit = p.barUnlit
        DepthShadowInk = p.depthShadowInk

        GradientInkStart = p.gradientInkStart
        GradientInkEnd = p.gradientInkEnd
    }
}

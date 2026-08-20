package com.th3web.lean.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import com.th3web.lean.data.SettingsDefaults
import kotlin.math.abs
import kotlin.math.pow
import android.graphics.Color as AndroidColor

/**
 * Seed accents: each accent is a full hand-tuned
 * primary/secondary/tertiary tone-set on the same muted ladder as the original
 * steel (primary = T80, onPrimary = T20, container = T30, onContainer = T90,
 * inversePrimary = T40). Surfaces, outlines, error and the reserved sage
 * [LeanColors.Connected] are seed-invariant, a seed repoints only accent
 * roles on top of the dark/AMOLED base via [LeanAccent.applyTo], or on the
 * light base via [LeanAccent.applyToLight] (the standard MD3 light mapping,
 * derived from the same stored tone-set, see the `light*` properties).
 */
data class LeanAccent(
    val nameRu: String,      // Russian display name — also the tr() key
    val seed: Long,          // stored in Settings.accentColor; == primary ARGB
    val primary: Color, val onPrimary: Color, val primaryContainer: Color, val onPrimaryContainer: Color, val inversePrimary: Color,
    val secondary: Color, val onSecondary: Color, val secondaryContainer: Color, val onSecondaryContainer: Color,
    val tertiary: Color, val onTertiary: Color, val tertiaryContainer: Color, val onTertiaryContainer: Color,
    val dim: Color,          // tone-60 — connecting arc (LeanColors.AccentDim)
    val connecting: Color,   // "accent in motion" (LeanColors.Connecting)
) {
    // ── Light-mode roles, the standard MD3 light mapping over the same stored
    // tone-set, so every seed gets a light counterpart with zero extra tuning:
    // primary flips to the hand-tuned tone-40 ([inversePrimary]), containers
    // flip to the hand-tuned light pastels (the dark on*Container tones), and
    // the dark on* inks become the light containers' inks. Secondary/tertiary
    // tone-40s are interpolated between the stored T30 container and T80 tone
    // (T40 ≈ 20% of the way), close enough on these muted, low-chroma hues.

    /** Light-mode primary, the hand-tuned tone-40 (== [inversePrimary]). */
    val lightPrimary: Color get() = inversePrimary

    /** Light-mode onPrimary, white (T100) on the tone-40 primary. */
    val lightOnPrimary: Color get() = Color.White

    /** Light-mode [LeanColors.AccentDim]: the stored tone-60 reads as the *quieter* accent on light. */
    val lightDim: Color get() = dim

    /** Light-mode "accent in motion", ≈T47, between the strong T40 and the dim T60. */
    val lightConnecting: Color get() = lerp(inversePrimary, dim, 0.35f)

    private val lightSecondary: Color get() = lerp(secondaryContainer, secondary, 0.2f) // ≈T40
    private val lightTertiary: Color get() = lerp(tertiaryContainer, tertiary, 0.2f)    // ≈T40

    /**
     * Repoints the accent roles of [base]; outline/error/text roles are never
     * touched. [tintAmount] is the fraction of [primary] blended into the neutral
     * surface/background ladder via [tintNeutral], which preserves each token's
     * luminance so surfaces shift *hue*, not brightness (MD3 tonal surfaces).
     * The tint follows the seed because it lerps toward this accent's [primary].
     *
     * The amount is a parameter rather than the frozen [LeanNeutralTintAmount]
     * because «Оформление → Оттенок поверхностей» owns it now. Pass `0f` for an
     * untinted ladder: that is what AMOLED does unless the user opts in.
     */
    fun applyTo(base: ColorScheme, tintAmount: Float = LeanNeutralTintAmount): ColorScheme {
        val repointed = base.copy(
            primary = primary, onPrimary = onPrimary,
            primaryContainer = primaryContainer, onPrimaryContainer = onPrimaryContainer,
            inversePrimary = inversePrimary,
            secondary = secondary, onSecondary = onSecondary,
            secondaryContainer = secondaryContainer, onSecondaryContainer = onSecondaryContainer,
            tertiary = tertiary, onTertiary = onTertiary,
            tertiaryContainer = tertiaryContainer, onTertiaryContainer = onTertiaryContainer,
        )
        if (tintAmount <= 0f) return repointed
        fun t(c: Color): Color = tintNeutral(c, primary, tintAmount)
        // Tint the neutral surface/background ladder only. Text (onSurface*,
        // onBackground), outline, outlineVariant, inverse* and scrim stay as-is.
        return repointed.copy(
            background = t(repointed.background),
            surface = t(repointed.surface),
            surfaceDim = t(repointed.surfaceDim),
            surfaceBright = t(repointed.surfaceBright),
            surfaceContainerLowest = t(repointed.surfaceContainerLowest),
            surfaceContainerLow = t(repointed.surfaceContainerLow),
            surfaceContainer = t(repointed.surfaceContainer),
            surfaceContainerHigh = t(repointed.surfaceContainerHigh),
            surfaceContainerHighest = t(repointed.surfaceContainerHighest),
            surfaceVariant = t(repointed.surfaceVariant),
        )
    }

    /**
     * Light-mode counterpart of [applyTo], repoints the accent roles of a
     * light [base] using the MD3 light mapping over this seed's stored
     * tone-set (see the `light*` properties above); outline/error/text roles
     * are never touched. [tintAmount] gives the light neutral ladder the same
     * faint hue via [tintNeutral], toward the stored T80 [primary]
     * (high-luminance, so renormalisation barely distorts), which keeps
     * surfaces light *tinted* grays exactly like the dark path.
     *
     * Callers cap this lower than on the dark column: [tintNeutral]'s `k > 1`
     * branch does not renormalise here, it darkens, so the top of the dark
     * range would cost real contrast (see `AppearanceRanges.SURFACE_TINT_MAX_LIGHT`).
     */
    fun applyToLight(base: ColorScheme, tintAmount: Float = LeanNeutralTintAmount): ColorScheme {
        val repointed = base.copy(
            primary = lightPrimary, onPrimary = lightOnPrimary,
            primaryContainer = onPrimaryContainer, onPrimaryContainer = onPrimary,
            inversePrimary = primary,
            secondary = lightSecondary, onSecondary = Color.White,
            secondaryContainer = onSecondaryContainer, onSecondaryContainer = onSecondary,
            tertiary = lightTertiary, onTertiary = Color.White,
            tertiaryContainer = onTertiaryContainer, onTertiaryContainer = onTertiary,
        )
        if (tintAmount <= 0f) return repointed
        fun t(c: Color): Color = tintNeutral(c, primary, tintAmount)
        return repointed.copy(
            background = t(repointed.background),
            surface = t(repointed.surface),
            surfaceDim = t(repointed.surfaceDim),
            surfaceBright = t(repointed.surfaceBright),
            surfaceContainerLowest = t(repointed.surfaceContainerLowest),
            surfaceContainerLow = t(repointed.surfaceContainerLow),
            surfaceContainer = t(repointed.surfaceContainer),
            surfaceContainerHigh = t(repointed.surfaceContainerHigh),
            surfaceContainerHighest = t(repointed.surfaceContainerHighest),
            surfaceVariant = t(repointed.surfaceVariant),
        )
    }
}

object LeanAccents {

    /** Default, reproduces today's look bit-for-bit (exact current Theme.kt values). */
    val Steel = LeanAccent(
        nameRu = "Сталь", seed = 0xFFB1C4E6,
        primary = Color(0xFFB1C4E6), onPrimary = Color(0xFF1C314D), primaryContainer = Color(0xFF34486A), onPrimaryContainer = Color(0xFFD6E2F9), inversePrimary = Color(0xFF46608A),
        secondary = Color(0xFFBAC4D6), onSecondary = Color(0xFF252F3F), secondaryContainer = Color(0xFF3A4554), onSecondaryContainer = Color(0xFFD7E0EE),
        tertiary = Color(0xFFC5C1DD), onTertiary = Color(0xFF2E2C44), tertiaryContainer = Color(0xFF44425B), onTertiaryContainer = Color(0xFFE2DEF9),
        dim = Color(0xFF8298BC), connecting = Color(0xFF93ACD1),
    )

    val Teal = LeanAccent(
        nameRu = "Бирюза", seed = 0xFF9FD2CB,
        primary = Color(0xFF9FD2CB), onPrimary = Color(0xFF0F3833), primaryContainer = Color(0xFF27514B), onPrimaryContainer = Color(0xFFBBEEE6), inversePrimary = Color(0xFF3C6A63),
        secondary = Color(0xFFB3CCC7), onSecondary = Color(0xFF1E3531), secondaryContainer = Color(0xFF354C47), onSecondaryContainer = Color(0xFFCFE8E2),
        tertiary = Color(0xFFAFC6DC), onTertiary = Color(0xFF1F3344), tertiaryContainer = Color(0xFF364A5C), onTertiaryContainer = Color(0xFFCBE2F9),
        dim = Color(0xFF6FA39C), connecting = Color(0xFF86BBB3),
    )

    /** Primary == reserved sage Connected, intentional: with this seed "connected" and "accent" converge. */
    val Green = LeanAccent(
        nameRu = "Зелень", seed = 0xFF98D1A6,
        primary = Color(0xFF98D1A6), onPrimary = Color(0xFF0E3A1E), primaryContainer = Color(0xFF275334), onPrimaryContainer = Color(0xFFB3EDC0), inversePrimary = Color(0xFF3C6A4A),
        secondary = Color(0xFFB5CCB6), onSecondary = Color(0xFF213524), secondaryContainer = Color(0xFF374C39), onSecondaryContainer = Color(0xFFD1E8D1),
        tertiary = Color(0xFFA2CEC8), onTertiary = Color(0xFF173732), tertiaryContainer = Color(0xFF2F4E49), onTertiaryContainer = Color(0xFFBDEAE3),
        dim = Color(0xFF6BA37A), connecting = Color(0xFF82BA91),
    )

    val Violet = LeanAccent(
        nameRu = "Фиалка", seed = 0xFFC9BFE8,
        primary = Color(0xFFC9BFE8), onPrimary = Color(0xFF322B4F), primaryContainer = Color(0xFF484167), onPrimaryContainer = Color(0xFFE6DEFF), inversePrimary = Color(0xFF605880),
        secondary = Color(0xFFC7C2D8), onSecondary = Color(0xFF2F2C40), secondaryContainer = Color(0xFF454257), onSecondaryContainer = Color(0xFFE3DEF4),
        tertiary = Color(0xFFDDBED3), onTertiary = Color(0xFF412B3C), tertiaryContainer = Color(0xFF594153), onTertiaryContainer = Color(0xFFFADAEB),
        dim = Color(0xFF9A8FBC), connecting = Color(0xFFB1A6D1),
    )

    val Amber = LeanAccent(
        nameRu = "Янтарь", seed = 0xFFDCC18C,
        primary = Color(0xFFDCC18C), onPrimary = Color(0xFF3D2E04), primaryContainer = Color(0xFF564519), onPrimaryContainer = Color(0xFFFADDA4), inversePrimary = Color(0xFF6F5D2F),
        secondary = Color(0xFFD1C5AA), onSecondary = Color(0xFF362F22), secondaryContainer = Color(0xFF4E4637), onSecondaryContainer = Color(0xFFEEE1C5),
        tertiary = Color(0xFFAECCA2), onTertiary = Color(0xFF1F3517), tertiaryContainer = Color(0xFF354C2C), onTertiaryContainer = Color(0xFFC9E8BC),
        dim = Color(0xFFAC9260), connecting = Color(0xFFC4A975),
    )

    /** Muted clay, stays clearly distinct from error #FFB4AB. */
    val Garnet = LeanAccent(
        nameRu = "Гранат", seed = 0xFFE5B6AF,
        primary = Color(0xFFE5B6AF), onPrimary = Color(0xFF442723), primaryContainer = Color(0xFF5E3C38), onPrimaryContainer = Color(0xFFFFDAD4), inversePrimary = Color(0xFF7A534D),
        secondary = Color(0xFFD9C1BD), onSecondary = Color(0xFF3B2D2A), secondaryContainer = Color(0xFF534340), onSecondaryContainer = Color(0xFFF6DDD8),
        tertiary = Color(0xFFD9C38F), onTertiary = Color(0xFF3A2E0D), tertiaryContainer = Color(0xFF524522), onTertiaryContainer = Color(0xFFF6E0A8),
        dim = Color(0xFFB3837C), connecting = Color(0xFFCC9D95),
    )

    /** The neutral / "no-color" option, pure cool gray, no hue. */
    val Graphite = LeanAccent(
        nameRu = "Графит", seed = 0xFFC6CAD3,
        primary = Color(0xFFC6CAD3), onPrimary = Color(0xFF2F3338), primaryContainer = Color(0xFF45494F), onPrimaryContainer = Color(0xFFE2E6EE), inversePrimary = Color(0xFF5E626B),
        secondary = Color(0xFFC2C5CC), onSecondary = Color(0xFF2C2F35), secondaryContainer = Color(0xFF42454C), onSecondaryContainer = Color(0xFFDEE1E8),
        tertiary = Color(0xFFC8C4CF), onTertiary = Color(0xFF302E37), tertiaryContainer = Color(0xFF46444E), onTertiaryContainer = Color(0xFFE4E0EB),
        dim = Color(0xFF969AA4), connecting = Color(0xFFAEB2BC),
    )

    val all = listOf(Steel, Teal, Green, Violet, Amber, Garnet, Graphite)

    /**
     * Synthesise a full tone-set for an arbitrary [argb] by rotating [Steel]'s fifteen
     * stops onto the seed's hue, no `material-color-utilities`, no new dependency.
     *
     * Per stop: read its HSV, swap in the seed's hue, scale its saturation by how much
     * more saturated the seed is than Steel's primary (with [chromaClamp] as the ceiling),
     * then renormalise back to the stop'S own luminance. That last step is what makes the
     * result usable: a raw hue rotation would leave a yellow "T80" far brighter than a
     * blue one, and every contrast ratio the palette was tuned for would move. Holding the
     * ladder fixed means the user gets exactly one degree of freedom (hue), while the
     * muted chroma and the tonal steps stay inherited from the hand-tuned palette.
     *
     * Renormalisation runs in encoded space with the 1/2.2 exponent, so scaling the
     * channels by `k` moves relative luminance by `k^2.2`, the inverse of what sRGB's
     * transfer function does. ([tintNeutral] uses the un-gamma'd form because its
     * corrections are a couple of percent; a hue rotation is not.)
     *
     * [nameRu] is the `#RRGGBB` string itself: there is no translation for a colour the
     * user invented, and the picker prints it verbatim instead of through `tr()`.
     */
    fun fromSeed(argb: Long, chromaClamp: Int = SettingsDefaults.ACCENT_CHROMA): LeanAccent {
        val seedHsv = hsvOf(Color((argb or 0xFF000000L).toInt()))
        val templateHsv = hsvOf(Steel.primary)
        val hue = seedHsv[HSV_H]
        // How much more saturated the seed is than the template's primary. A near-gray
        // seed shrinks the whole ladder toward neutral (which is what "Графит" looks
        // like); a vivid one is pulled back by the ceiling below.
        val scale = if (templateHsv[HSV_S] <= 1e-3f) 0f else seedHsv[HSV_S] / templateHsv[HSV_S]
        val ceiling = (chromaClamp / 100f).coerceIn(0f, 1f)
        fun stop(template: Color): Color = rotate(template, hue, scale, ceiling)
        return LeanAccent(
            nameRu = "#%06X".format(argb and 0xFFFFFFL),
            seed = argb,
            primary = stop(Steel.primary),
            onPrimary = stop(Steel.onPrimary),
            primaryContainer = stop(Steel.primaryContainer),
            onPrimaryContainer = stop(Steel.onPrimaryContainer),
            inversePrimary = stop(Steel.inversePrimary),
            secondary = stop(Steel.secondary),
            onSecondary = stop(Steel.onSecondary),
            secondaryContainer = stop(Steel.secondaryContainer),
            onSecondaryContainer = stop(Steel.onSecondaryContainer),
            tertiary = stop(Steel.tertiary),
            onTertiary = stop(Steel.onTertiary),
            tertiaryContainer = stop(Steel.tertiaryContainer),
            onTertiaryContainer = stop(Steel.onTertiaryContainer),
            dim = stop(Steel.dim),
            connecting = stop(Steel.connecting),
        )
    }

    /**
     * A stored seed → its accent. A preset seed wins by exact match; anything else is
     * synthesised by [fromSeed] so the custom/wallpaper sources need no second colour path.
     *
     * The [SettingsDefaults.ACCENT_LEGACY_DEFAULT] special case is load-bearing, not
     * legacy cruft: that near-white `#F3F4F7` is what every install from the monochrome
     * era still carries, and it is the DEFAULT, so it is also what a fresh install writes.
     * Before this method could synthesise, it fell through to `?: Steel`. Removing the
     * fallback without this branch would repaint every one of those installs near-white.
     */
    fun resolve(argb: Long, chromaClamp: Int = SettingsDefaults.ACCENT_CHROMA): LeanAccent =
        all.firstOrNull { it.seed == argb }
            ?: if (argb == SettingsDefaults.ACCENT_LEGACY_DEFAULT) Steel else fromSeed(argb, chromaClamp)
}

private const val HSV_H = 0
private const val HSV_S = 1

/** HSV triple of [color]; `android.graphics.Color.colorToHSV` is API 1, so no guard. */
internal fun hsvOf(color: Color): FloatArray {
    val hsv = FloatArray(3)
    AndroidColor.colorToHSV(color.toArgb(), hsv)
    return hsv
}

/**
 * Shortest angular distance between two hues, in degrees (0..180): the wrap-around is
 * the reason: red at 358° and red at 4° are six degrees apart, not 354.
 */
internal fun hueDistance(a: Float, b: Float): Float {
    val d = abs(a - b) % 360f
    return if (d > 180f) 360f - d else d
}

/** One [LeanAccents.fromSeed] stop: hue swap + clamped saturation + luminance restore. */
private fun rotate(template: Color, hue: Float, satScale: Float, satCeiling: Float): Color {
    val hsv = hsvOf(template)
    hsv[HSV_H] = hue
    hsv[HSV_S] = (hsv[HSV_S] * satScale).coerceIn(0f, satCeiling)
    val shifted = Color(AndroidColor.HSVToColor(hsv))
    val want = template.luminance()
    val have = shifted.luminance()
    // Near-black stops carry no visible hue and scaling them is explosive, the same
    // guard tintNeutral makes, for the same reason.
    if (want <= 1e-4f || have <= 1e-4f) return template
    val k = (want / have).pow(1f / SRGB_GAMMA)
    return Color(
        red = (shifted.red * k).coerceIn(0f, 1f),
        green = (shifted.green * k).coerceIn(0f, 1f),
        blue = (shifted.blue * k).coerceIn(0f, 1f),
        alpha = template.alpha,
    )
}

private const val SRGB_GAMMA = 2.2f

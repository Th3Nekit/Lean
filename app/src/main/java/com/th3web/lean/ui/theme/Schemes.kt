package com.th3web.lean.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

/**
 * The four hand-authored MD3 base schemes, lifted out of `Theme.kt`.
 *
 * They are `internal`, not `private`, for one concrete reason: the Оформление preview
 * has to build a complete draft scheme for a look that is not the active one (a preset
 * thumbnail, a slider still under the finger) without writing a single global token.
 * [leanPalette] is the pure function that does it, and it needs these bases in scope.
 *
 * A scheme is only ever a base here: the seed repoints its accent roles
 * ([LeanAccent.applyTo] / [LeanAccent.applyToLight]), and [leanPalette] then applies the
 * surface tint, the contrast step, the error ink and the role overrides. Nothing writes
 * to these values.
 */

/**
 * "Refined Cool" MD3 dark scheme, steel-indigo primary on cool tonal
 * neutrals. Fully specified (including the surfaceContainer ladder), so stock
 * M3 components land on the exact tonal steps the design uses.
 */
internal val LeanDarkScheme = darkColorScheme(
    primary = Color(0xFFB1C4E6),
    onPrimary = Color(0xFF1C314D),
    primaryContainer = Color(0xFF34486A),
    onPrimaryContainer = Color(0xFFD6E2F9),
    inversePrimary = Color(0xFF46608A),
    secondary = Color(0xFFBAC4D6),
    onSecondary = Color(0xFF252F3F),
    secondaryContainer = Color(0xFF3A4554),
    onSecondaryContainer = Color(0xFFD7E0EE),
    tertiary = Color(0xFFC5C1DD),
    onTertiary = Color(0xFF2E2C44),
    tertiaryContainer = Color(0xFF44425B),
    onTertiaryContainer = Color(0xFFE2DEF9),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    background = Color(0xFF101319),
    onBackground = Color(0xFFE0E2E9),
    surface = Color(0xFF101319),
    onSurface = Color(0xFFE0E2E9),
    surfaceDim = Color(0xFF101319),
    surfaceBright = Color(0xFF363943),
    surfaceContainerLowest = Color(0xFF0B0E13),
    surfaceContainerLow = Color(0xFF181B21),
    surfaceContainer = Color(0xFF1C1F26),
    surfaceContainerHigh = Color(0xFF262A31),
    surfaceContainerHighest = Color(0xFF31343C),
    surfaceVariant = Color(0xFF414752),
    onSurfaceVariant = Color(0xFFC2C6D1),
    outline = Color(0xFF8B919D),
    outlineVariant = Color(0xFF3F4550),
    inverseSurface = Color(0xFFE0E2E9),
    inverseOnSurface = Color(0xFF2D3036),
    scrim = Color(0xFF000000),
)

/**
 * AMOLED variant, identical to [LeanDarkScheme] except the base canvas
 * collapses to pure black and the container ladder shifts one step darker
 * (while staying visibly raised so cards remain legible); outlineVariant is
 * one step dimmer so hairlines don't glow on true black.
 */
internal val LeanAmoledScheme = darkColorScheme(
    primary = Color(0xFFB1C4E6),
    onPrimary = Color(0xFF1C314D),
    primaryContainer = Color(0xFF34486A),
    onPrimaryContainer = Color(0xFFD6E2F9),
    inversePrimary = Color(0xFF46608A),
    secondary = Color(0xFFBAC4D6),
    onSecondary = Color(0xFF252F3F),
    secondaryContainer = Color(0xFF3A4554),
    onSecondaryContainer = Color(0xFFD7E0EE),
    tertiary = Color(0xFFC5C1DD),
    onTertiary = Color(0xFF2E2C44),
    tertiaryContainer = Color(0xFF44425B),
    onTertiaryContainer = Color(0xFFE2DEF9),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    background = Color(0xFF000000),
    onBackground = Color(0xFFE0E2E9),
    surface = Color(0xFF000000),
    onSurface = Color(0xFFE0E2E9),
    surfaceDim = Color(0xFF000000),
    surfaceBright = Color(0xFF2E313A),
    surfaceContainerLowest = Color(0xFF000000),
    surfaceContainerLow = Color(0xFF0C0F14),
    surfaceContainer = Color(0xFF12151B),
    surfaceContainerHigh = Color(0xFF1A1D24),
    surfaceContainerHighest = Color(0xFF24272F),
    surfaceVariant = Color(0xFF353B45),
    onSurfaceVariant = Color(0xFFC2C6D1),
    outline = Color(0xFF8B919D),
    outlineVariant = Color(0xFF353B45),
    inverseSurface = Color(0xFFE0E2E9),
    inverseOnSurface = Color(0xFF2D3036),
    scrim = Color(0xFF000000),
)

/**
 * «Глубина чёрного → мягкая», [LeanAmoledScheme] with the raised ladder lifted one
 * step. The canvas stays exactly `#000000` (that is what matters of AMOLED: black
 * pixels are off pixels); only the cards climb, from `#12151B/#1A1D24/#24272F` to
 * `#181B22/#20242C/#2C3038`.
 *
 * This exists because the absolute ladder is tuned for a good panel. On a cheap or aged
 * OLED the first two steps above true black are where near-black banding and grey-crush
 * live, so a card can read as a smear rather than a pane. One step up costs nothing on a
 * good screen and rescues the bad one.
 */
internal val LeanAmoledSchemeSoft = LeanAmoledScheme.copy(
    surfaceBright = Color(0xFF363943),
    surfaceContainerLow = Color(0xFF101319),
    surfaceContainer = Color(0xFF181B22),
    surfaceContainerHigh = Color(0xFF20242C),
    surfaceContainerHighest = Color(0xFF2C3038),
    surfaceVariant = Color(0xFF3D4450),
    outlineVariant = Color(0xFF3D4450),
)

/**
 * "Refined Cool" MD3 light scheme, the deliberate light counterpart of
 * [LeanDarkScheme] on the same cool-neutral family: an airy off-white canvas
 * (`#FAF9FD`), the standard MD3 light surface-container ladder descending
 * into light grays, dark-on-light inks and the steel accent flipped to its
 * tone-40 roles. Accent roles here are the Steel seed's light mapping; a
 * non-default seed repoints them via [LeanAccent.applyToLight].
 */
internal val LeanLightScheme = lightColorScheme(
    primary = Color(0xFF46608A),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFD6E2F9),
    onPrimaryContainer = Color(0xFF1C314D),
    inversePrimary = Color(0xFFB1C4E6),
    secondary = Color(0xFF545E6E),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFD7E0EE),
    onSecondaryContainer = Color(0xFF252F3F),
    tertiary = Color(0xFF5E5B75),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFE2DEF9),
    onTertiaryContainer = Color(0xFF2E2C44),
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    background = Color(0xFFFAF9FD),
    onBackground = Color(0xFF1A1C22),
    surface = Color(0xFFFAF9FD),
    onSurface = Color(0xFF1A1C22),
    surfaceDim = Color(0xFFDAD9E0),
    surfaceBright = Color(0xFFFAF9FD),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF4F3F9),
    surfaceContainer = Color(0xFFEFEDF4),
    surfaceContainerHigh = Color(0xFFE8E7EF),
    surfaceContainerHighest = Color(0xFFE2E1E9),
    surfaceVariant = Color(0xFFE0E2EC),
    onSurfaceVariant = Color(0xFF44464E),
    outline = Color(0xFF74777F),
    outlineVariant = Color(0xFFC4C6D0),
    inverseSurface = Color(0xFF2F3036),
    inverseOnSurface = Color(0xFFF1F0F7),
    scrim = Color(0xFF000000),
)

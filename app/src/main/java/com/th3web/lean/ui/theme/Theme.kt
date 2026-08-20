package com.th3web.lean.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.th3web.lean.LeanApp
import com.th3web.lean.ui.I18n
import java.util.Locale

/**
 * Root theme. Resolves the persisted settings into one [AppearanceSpec] and derives
 * everything from it: the MD3 scheme, the type scale and the shape slots inside the
 * composition, the `LeanColors`/`LeanCorner`/`LeanType`/[LeanMetrics]/[LeanOptions]
 * globals through the single writer [LeanAppearance].
 *
 * Three properties this arrangement buys, each of which used to be a bug or a near miss:
 *
 * 1. **The two halves cannot drift.** The scheme and the token mirror come from the same
 *    pure [leanPalette] over the same spec, instead of from two hand-kept key lists.
 * 2. **Frame one is correct in any host.** The publication is a `SideEffect`, which runs
 *    within the same frame as the composition that scheduled it, a `LaunchedEffect` ran
 *    a frame late, so a second activity, a `@Preview` or a tile drew its first frame with
 *    whatever look was left over. `MainActivity` still pre-seeds for the frames before
 *    composition exists.
 * 3. **Unrelated settings writes are free.** `Settings` emits on `selectedProfileId`,
 *    which moves on every tap in the server list. An `AppearanceSpec` does not change
 *    then, so `remember(spec)` rebuilds nothing and [LeanAppearance] compares once and
 *    returns.
 */
@Composable
fun LeanTheme(content: @Composable () -> Unit) {
    // Seed with the synchronously-read persisted settings (not a default Settings())
    // so the first frame already uses the saved look, no cold-start flash of the
    // default theme before the flow's first async emission arrives.
    val settings by LeanApp.instance.settings.flow
        .collectAsStateWithLifecycle(initialValue = LeanApp.instance.settings.initial)
    val systemDark = isSystemInDarkTheme()
    val nightNow = rememberNightWindow(settings)
    val wallpaperSeed = rememberWallpaperSeed(settings)
    val lang = when (settings.language) {
        "en" -> "en"
        "ru" -> "ru"
        else -> if (Locale.getDefault().language == "en") "en" else "ru" // "system"
    }
    val spec = remember(settings, systemDark, nightNow, wallpaperSeed) {
        settings.appearanceSpec(systemDark, nightNow, wallpaperSeed)
    }
    SideEffect { LeanAppearance.apply(spec) }
    LaunchedEffect(lang) {
        I18n.lang = lang
    }
    // Decode «своя картинка» here rather than from the screen that sets it, so a cold
    // start shows the chosen background instead of the flat fallback. Keyed on the style
    // so switching to image loads it on the spot; ensureLoaded is a no-op once decoded.
    LaunchedEffect(spec.bgStyle) {
        if (spec.bgStyle == "image") BackgroundImage.ensureLoaded(LeanApp.instance)
    }
    val palette = remember(spec) { leanPalette(spec) }
    val typography = remember(spec) { leanTypography(spec) }
    val shapes = remember(spec) { leanShapes(spec.corner) }
    MaterialTheme(
        colorScheme = palette.scheme,
        typography = typography,
        shapes = shapes,
        content = content,
    )
}

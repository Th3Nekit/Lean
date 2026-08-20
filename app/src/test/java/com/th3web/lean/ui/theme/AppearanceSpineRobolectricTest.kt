package com.th3web.lean.ui.theme

import android.app.Application
import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import com.th3web.lean.data.AppearancePresets
import com.th3web.lean.data.AppearanceRanges
import com.th3web.lean.data.Settings
import com.th3web.lean.data.mergeInto

/**
 * Actually RUNS the theme spine, in every combination the tab can produce.
 *
 * Everything else about «Оформление» is checked by compiling it or by inspecting emitted
 * JSON — neither of which executes `leanPalette`, `leanTypography`, `LeanCorner.apply` or
 * the accent synthesis even once. Those run on the FIRST FRAME of the app, so anything
 * that throws in them is not a wrong colour, it is a launch crash for whoever had that
 * setting stored. With no device in the loop, this is the only thing standing between a
 * bad combination and a user finding it.
 *
 * The typography pass matters most: `TextUnit` arithmetic THROWS on `Unspecified`, and
 * most roles leave `letterSpacing` unset, so the `isSp` guard in `scaled()` is the only
 * reason scaling any of them works at all. A regression there breaks every text style in
 * the app at once.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class AppearanceSpineRobolectricTest {

    private fun spec(s: Settings, systemDark: Boolean = true) =
        s.appearanceSpec(systemDark = systemDark, nightNow = false, wallpaperSeed = null)

    @Test
    fun `every built-in look resolves and applies without throwing`() {
        AppearancePresets.all.forEach { preset ->
            val settings = preset.profile.mergeInto(Settings())
            val resolved = spec(settings)
            val palette = leanPalette(resolved)

            // The mode must survive resolution intact, or the window theme picked in
            // MainActivity and the palette drawn by Compose disagree on the same launch.
            assertEquals(preset.name, resolved.light, palette.light)
            assertEquals(preset.name, resolved.amoled, palette.amoled)
            if (resolved.amoled && resolved.amoledDepth == "absolute") {
                // The point of absolute AMOLED is unlit pixels; anything above zero is a
                // grey the panel still has to light up.
                assertEquals("${preset.name}: absolute AMOLED must be pure black", Color.Black, palette.background)
            }

            // The whole chain a real launch performs.
            LeanAppearance.apply(resolved)
            leanShapes(resolved.corner)
            leanTypography(resolved)
        }
    }

    @Test
    fun `every theme mode and contrast step resolves`() {
        val modes = listOf("dark", "amoled", "light", "system")
        val depths = listOf("absolute", "soft")
        modes.forEach { mode ->
            depths.forEach { depth ->
                (AppearanceRanges.CONTRAST_MIN..AppearanceRanges.CONTRAST_MAX).forEach { contrast ->
                    listOf(true, false).forEach { systemDark ->
                        val resolved = spec(
                            Settings(themeMode = mode, amoledDepth = depth, contrastLevel = contrast),
                            systemDark = systemDark,
                        )
                        assertTrue(
                            "$mode/$depth/$contrast: \"system\" must already be resolved to a real canvas",
                            resolved.mode in setOf("dark", "amoled", "light"),
                        )
                        LeanAppearance.apply(resolved)
                    }
                }
            }
        }
    }

    /**
     * An arbitrary accent is synthesized by rotating a hand-built ladder through HSV, which
     * is real arithmetic on real colours — including the degenerate ones a colour picker can
     * hand it (pure black, pure white, fully saturated primaries).
     */
    @Test
    fun `arbitrary accent seeds synthesize a usable palette`() {
        val seeds = listOf(
            0xFF000000L, 0xFFFFFFFFL, 0xFFFF0000L, 0xFF00FF00L, 0xFF0000FFL,
            0xFF808080L, 0xFF1A1A1AL, 0xFFF3F4F7L, 0xFF7B2D8EL,
        )
        val chromas = listOf(
            AppearanceRanges.ACCENT_CHROMA_MIN,
            50,
            AppearanceRanges.ACCENT_CHROMA_MAX,
        )
        seeds.forEach { seed ->
            chromas.forEach { chroma ->
                listOf("dark", "amoled", "light").forEach { mode ->
                    val resolved = spec(
                        Settings(
                            themeMode = mode,
                            accentSource = "custom",
                            accentColor = seed,
                            accentChroma = chroma,
                        ),
                    )
                    val palette = leanPalette(resolved)
                    // Text on the accent has to stay legible whatever the seed — that is the
                    // entire reason the synthesis renormalises lightness instead of just
                    // rotating hue.
                    assertTrue(
                        "seed=${seed.toString(16)} chroma=$chroma mode=$mode: accent and its ink collapsed",
                        palette.scheme.primary != palette.scheme.onPrimary,
                    )
                    LeanAppearance.apply(resolved)
                }
            }
        }
    }

    /** Every font family, size step and weight delta, since one throw here kills all text. */
    @Test
    fun `every typography combination builds`() {
        val families = listOf("unbounded", "onest", "system", "mono")
        val scales = listOf(90, 95, 100, 110, 120)
        val weights = listOf(-100, 0, 100)
        families.forEach { display ->
            families.forEach { body ->
                scales.forEach { scale ->
                    weights.forEach { weight ->
                        listOf(true, false).forEach { tabular ->
                            val resolved = spec(
                                Settings(
                                    fontDisplay = display,
                                    fontBody = body,
                                    textScale = scale,
                                    fontWeightDelta = weight,
                                    tabularNums = tabular,
                                ),
                            )
                            applyTypography(resolved)
                            val typography = leanTypography(resolved)
                            assertTrue(
                                "$display/$body/$scale/$weight: body size collapsed",
                                typography.bodyMedium.fontSize.value > 0f,
                            )
                        }
                    }
                }
            }
        }
    }

    /**
     * Every corner ladder, and that they are genuinely five DIFFERENT ladders in the order
     * their names promise — a copy-paste that left two styles identical would look fine in
     * review and do nothing on screen.
     */
    @Test
    fun `the five corner ladders apply and increase in roundness`() {
        val radii = listOf("sharp", "crisp", "normal", "soft", "round").map { style ->
            LeanCorner.apply(style)
            leanShapes(style)
            style to LeanCorner.CardRadius.value
        }
        radii.zipWithNext { (leftName, left), (rightName, right) ->
            assertTrue("$leftName must be sharper than $rightName ($left vs $right)", left < right)
        }
        // Leave the tokens on the shipping ladder — they are global state.
        LeanCorner.apply("normal")
    }

    @Test
    fun `extreme numeric knobs stay inside their ranges after resolution`() {
        val wild = Settings(
            contrastLevel = 99,
            accentChroma = -50,
            surfaceTint = 999,
            selectionWash = -7,
            quickPeek = 42,
            textScale = 3,
            heroSize = 1_000,
        )
        val resolved = spec(wild)
        // Contrast is the one that punishes a stray value: it reaches lerp() as a fraction,
        // and lerp does not clamp — 99 would extrapolate past the target and out of gamut.
        assertTrue(resolved.contrastLevel in AppearanceRanges.CONTRAST_MIN..AppearanceRanges.CONTRAST_MAX)
        assertTrue(resolved.quickPeek in AppearanceRanges.QUICK_PEEK_MIN..AppearanceRanges.QUICK_PEEK_MAX)
        assertTrue("text scale snaps to a real step", resolved.textScale in AppearanceRanges.TEXT_SCALE_STEPS)
        assertTrue("hero size snaps to a real step", resolved.heroSize in AppearanceRanges.HERO_SIZE_STEPS)
        assertTrue("surface tint is a fraction after resolution", resolved.surfaceTint in 0f..0.20f)
        assertTrue("selection wash is a fraction after resolution", resolved.selectionWash in 0f..0.25f)
        LeanAppearance.apply(resolved)
        leanTypography(resolved)
    }
}

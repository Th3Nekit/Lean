package com.th3web.lean.ui.theme

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * «Плотность стекла» has to mean the number it shows.
 *
 * It did not. The fill's alpha followed the slider, but the FROSTING — the blur applied to
 * the fragment of wallpaper a panel shows through itself — was pinned at full strength for
 * every value above zero. At 1 % the fill was all but invisible and the panel was still an
 * unmistakable block, because it was a sharply bounded patch of blurred picture sitting on
 * a sharp one. Reported, exactly, as "1% выглядит как 25%".
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GlassDensityTest {

    private val backgroundBlur = 20

    @After
    fun reset() {
        LeanOptions.glassPanels = false
        LeanOptions.glassTint = 60
        LeanOptions.bgImageBlur = 0
    }

    private fun blurAt(density: Int): Int {
        LeanOptions.bgImageBlur = backgroundBlur
        LeanOptions.glassTint = density
        return glassBlur()
    }

    /**
     * At the bottom of the range the panel adds NOTHING to what the background already
     * carries, so its fragment is pixel-identical to the picture around it and the only
     * thing left is the 1 % of tint the user asked for.
     */
    @Test
    fun `a low density adds no frosting of its own`() {
        assertEquals(backgroundBlur, blurAt(1))
    }

    /** At the top it is the full frosted panel the setting has always produced. */
    @Test
    fun `full density is the full frosting`() {
        assertTrue("expected a real frost, got ${blurAt(100)}", blurAt(100) >= 45)
    }

    @Test
    fun `the frosting only ever grows with the density`() {
        val samples = (0..100 step 5).map(::blurAt)
        assertEquals(samples.sorted(), samples)
    }

    /**
     * A background blurred HARDER than the glass minimum keeps its own value: the panel is
     * never allowed to be sharper than what it sits on, at any density.
     */
    @Test
    fun `a heavily blurred background is never sharpened by the panel`() {
        LeanOptions.bgImageBlur = 80
        listOf(0, 1, 50, 100).forEach { density ->
            LeanOptions.glassTint = density
            assertEquals("at $density%", 80, glassBlur())
        }
    }

    @Test
    fun `zero density means the panel paints nothing at all`() {
        LeanOptions.glassPanels = true
        LeanOptions.glassTint = 0
        assertTrue(glassPanelIsInvisible())

        LeanOptions.glassTint = 1
        assertTrue("1% is a panel, however faint", !glassPanelIsInvisible())

        // Glass off is the opaque fallback, not an invisible panel — otherwise every card
        // in the app would vanish for anyone who never turned «Стекло» on.
        LeanOptions.glassPanels = false
        LeanOptions.glassTint = 0
        assertTrue(!glassPanelIsInvisible())
    }
}

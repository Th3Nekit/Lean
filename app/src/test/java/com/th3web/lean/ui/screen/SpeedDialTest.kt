package com.th3web.lean.ui.screen

import kotlin.math.exp
import kotlin.math.ln
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The dial's scale on «Скорость» — the other half of "полоска скачет сильно".
 *
 * The indicator was not the only thing lurching. The arc is drawn against a scale picked
 * from the largest rate seen so far, and that scale steps: 10, 25, 50, 100, 250… Every
 * step re-maps the arc, and a rate climbing out of the ramp walks through three or four of
 * them in as many seconds. Measured on the shape a 300-Мбит/с line actually produces, the
 * needle fell nineteen points of the arc as the rate passed 96 Мбит/с and another fifteen
 * as it passed 255 — backwards, while the number beside it was going up.
 *
 * Two changes, and these tests are about the first: the range a run opens at is inherited
 * from the run before it, so there is usually nothing to re-scale. (The second is that the
 * remaining steps are zoomed rather than cut, which lives in the animation.)
 */
class SpeedDialTest {

    /** The live rate of a 300-Мбит/с line coming up out of the ramp, as smoothed. */
    private val ramp = listOf(0.0, 4.2, 31.0, 96.0, 180.0, 255.0, 298.0, 305.0)

    private fun needle(rate: Double, peak: Double): Float = dialFraction(rate, dialMax(peak))

    @Test
    fun `a dial opened at the last run's range follows a rising rate upwards only`() {
        var peak = 305.0
        var previous = 0f
        ramp.forEach { rate ->
            peak = maxOf(peak, rate)
            val now = needle(rate, peak)
            assertTrue("на $rate Мбит/с стрелка ушла с $previous на $now", now >= previous)
            previous = now
        }
        assertTrue("стрелка дошла только до $previous", previous > 0.5f)
    }

    @Test
    fun `the range only ever grows with what the dial has to fit`() {
        val ranges = (0..4_000 step 25).map { dialMax(it.toDouble()) }
        assertEquals(ranges.sorted(), ranges)
    }

    @Test
    fun `every range the dial can settle on carries its number at the end of the arc`() {
        DIAL_STEPS.forEach { range ->
            val labelled = dialLabels(range).any { (fraction, text) ->
                fraction >= 0.999f && text.isNotEmpty()
            }
            assertTrue("$range Мбит/с: конец дуги без подписи", labelled)
        }
    }

    @Test
    fun `a range the dial zoomed to is still the same range`() {
        // The scale is animated in log space, so what it settles on is exp(ln(x)) in
        // single precision, which is not x. A label that dropped out at that point would
        // appear and disappear every time the dial changed range.
        DIAL_STEPS.forEach { range ->
            val settled = exp(ln(range.toFloat())).toDouble()
            val labelled = dialLabels(settled).any { (fraction, text) ->
                fraction >= 0.999f && text.isNotEmpty()
            }
            assertTrue("$range Мбит/с после зума ($settled): конец дуги без подписи", labelled)
        }
    }

    @Test
    fun `the needle never leaves the arc, whatever it is handed`() {
        listOf(-5.0, 0.0, 0.004, 99.9, 1_000_000.0).forEach { rate ->
            val fraction = dialFraction(rate, 100.0)
            assertTrue("$rate Мбит/с -> $fraction", fraction >= 0f && fraction <= 1f)
        }
        assertEquals(0f, dialFraction(50.0, 0.0), 0.0001f)
    }

    @Test
    fun `the bottom of a range gets a readable part of the arc`() {
        // What the power curve is for: on a 100-Мбит/с scale, 8 and 25 Мбит/с have to be
        // visibly different places on the dial and not two needles on top of each other.
        val low = dialFraction(8.0, 100.0)
        val higher = dialFraction(25.0, 100.0)
        assertTrue("8 -> $low, 25 -> $higher", higher - low > 0.15f)
    }
}

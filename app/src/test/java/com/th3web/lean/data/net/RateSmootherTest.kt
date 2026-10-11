package com.th3web.lean.data.net

import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The live readout's steadiness, which is a measurement problem rather than an animation
 * one: four streams starting and finishing bodies inside one 300 ms tick make the raw rate
 * swing by a factor of several while the link does not change at all. That is what made
 * the dial shake, and no spring can smooth a signal that noisy — at «Анимации: off» there
 * is not even a spring.
 */
class RateSmootherTest {

    private val tick = 300_000_000L
    private val mib = 1_048_576L

    /** 1 MiB per 300 ms = 27.96 Мбит/с, the figure both cases below are measuring. */
    private val trueRate = mib * 8.0 / 0.3 / 1_000_000.0

    @Test
    fun `a steady transfer reads as its actual rate`() {
        val smoother = RateSmoother()
        var total = 0L
        var last = 0.0
        repeat(30) { step ->
            total += mib
            last = smoother.observe((step + 1) * tick, total)
        }
        assertEquals(trueRate, last, trueRate * 0.05)
    }

    @Test
    fun `a bursty transfer reads as steady, where the raw rate would not`() {
        val smoother = RateSmoother()
        var total = 0L
        val smoothed = mutableListOf<Double>()
        val raw = mutableListOf<Double>()
        var previous = 0L
        repeat(40) { step ->
            // The same average, delivered in alternating bursts — a proxy handing over
            // data, or two of four streams finishing a body on the same tick.
            total += if (step % 2 == 0) 0L else 2 * mib
            val at = (step + 1) * tick
            smoothed += smoother.observe(at, total)
            raw += (total - previous) * 8.0 / 0.3 / 1_000_000.0
            previous = total
        }
        val tail = smoothed.takeLast(12)
        val spread = tail.max() - tail.min()
        val rawSpread = raw.takeLast(12).let { it.max() - it.min() }
        assertTrue("the raw series has to be the noisy one: $rawSpread", rawSpread > 40.0)
        assertTrue("smoothed series still swings by $spread Мбит/с", spread < 10.0)
        assertEquals(trueRate, tail.average(), trueRate * 0.2)
    }

    @Test
    fun `it answers zero before anything has moved`() {
        val smoother = RateSmoother()
        assertEquals(0.0, smoother.value, 0.0)
        assertEquals(0.0, smoother.observe(tick, 0L), 0.0)
        assertEquals(0.0, smoother.value, 0.0)
    }

    @Test
    fun `a stalled link falls towards zero instead of holding its last reading`() {
        val smoother = RateSmoother()
        var total = 0L
        repeat(10) { step ->
            total += mib
            smoother.observe((step + 1) * tick, total)
        }
        val before = smoother.value
        repeat(20) { step -> smoother.observe((11 + step) * tick, total) }
        assertTrue("stalled at $before, now ${smoother.value}", smoother.value < before * 0.1)
    }

    @Test
    fun `the window is the recent past, not the whole transfer`() {
        // Fast for a second, then half as fast: the reading has to follow the link rather
        // than average the run, or the needle would lag by the length of the transfer.
        val smoother = RateSmoother()
        var total = 0L
        repeat(10) { step ->
            total += 2 * mib
            smoother.observe((step + 1) * tick, total)
        }
        repeat(20) { step ->
            total += mib
            smoother.observe((11 + step) * tick, total)
        }
        assertTrue(abs(smoother.value - trueRate) < trueRate * 0.1)
    }
}

package com.th3web.lean.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The run indicator on «Скорость», reported twice.
 *
 * First as "дёргается вперёд в назад": it was handed the current phase's own fraction, and
 * a two-leg run has six phases, so it ran to the end and snapped back six times. Weighting
 * the phases fixed that and left the worse half: progress only existed when a sample
 * arrived, and samples arrive only while bytes are moving. Choosing a server, verifying it
 * answers directly, every handover in between — seconds each, not one sample between them.
 * The bar stood still through the slowest parts of the run and then leapt, which is what
 * "всё ещё полоска скачет сильно" was about.
 *
 * So these tests are about time, not about samples. They walk a clock through a run and
 * insist on two things: the bar never goes backwards, and it never stands still.
 */
class SpeedTestProgressTest {

    private val start = 7_000_000_000L

    private fun at(ms: Long) = start + ms * 1_000_000L

    private data class Entered(
        val step: RunProgress.Step,
        val viaTunnel: Boolean,
        val atMs: Long,
    )

    /**
     * A two-leg run as it actually unfolds: a server chosen in a few seconds, six latency
     * probes, two eight-second windows that overrun while their workers are joined, then
     * the same again on the link underneath the tunnel.
     */
    private val run = listOf(
        Entered(RunProgress.Step.Search, viaTunnel = true, atMs = 0),
        Entered(RunProgress.Step.Latency, viaTunnel = true, atMs = 3_200),
        Entered(RunProgress.Step.Download, viaTunnel = true, atMs = 4_050),
        Entered(RunProgress.Step.Upload, viaTunnel = true, atMs = 12_400),
        Entered(RunProgress.Step.Search, viaTunnel = false, atMs = 21_000),
        Entered(RunProgress.Step.Latency, viaTunnel = false, atMs = 23_600),
        Entered(RunProgress.Step.Download, viaTunnel = false, atMs = 24_300),
        Entered(RunProgress.Step.Upload, viaTunnel = false, atMs = 32_500),
    )

    @Test
    fun `the bar moves while a server is being chosen, with no samples at all`() {
        val progress = RunProgress(legs = 1, startedAt = start)
        val early = progress.at(at(500))
        val later = progress.at(at(1_500))
        val muchLater = progress.at(at(9_000))
        assertTrue("после 500 мс поиска: $early", early > 0f)
        assertTrue("$later после $early", later > early)
        // A directory blocked by the carrier can hold this step for ten seconds. The bar
        // has to be somewhere different at the end of that than at the start.
        assertTrue("$muchLater после $later", muchLater > later)
    }

    @Test
    fun `nothing in a two-leg run holds the bar still, and nothing pulls it back`() {
        val progress = RunProgress(legs = 2, startedAt = start)
        var previous = 0f
        var movedAt = 0L
        var longestStall = 0L
        for (ms in 0L..41_000L step STEP_MS) {
            run.filter { it.atMs == ms }.forEach { progress.enter(it.step, it.viaTunnel, at(ms)) }
            val now = progress.at(at(ms))
            assertTrue("$ms мс: $now после $previous", now >= previous)
            if (now > previous) {
                longestStall = maxOf(longestStall, ms - movedAt)
                movedAt = ms
            }
            previous = now
        }
        assertTrue("бар стоял $longestStall мс", longestStall <= 2 * STEP_MS)
        assertTrue("к концу прогона бар дошёл только до $previous", previous > 0.98f)
    }

    @Test
    fun `the tunnel leg owns the first half and the direct leg the second`() {
        val first = RunProgress(legs = 2, startedAt = start)
        first.enter(RunProgress.Step.Upload, viaTunnel = true, nowNanos = at(0))
        assertEquals(0.5f, first.at(at(60_000)), 0.005f)

        val second = RunProgress(legs = 2, startedAt = start)
        second.enter(RunProgress.Step.Search, viaTunnel = false, nowNanos = at(0))
        assertEquals(0.5f, second.at(at(0)), 0.001f)
    }

    @Test
    fun `a transfer window fills its share at an even pace`() {
        val progress = RunProgress(legs = 1, startedAt = start)
        progress.enter(RunProgress.Step.Download, viaTunnel = true, nowNanos = at(0))
        val opened = progress.at(at(0))
        val middle = progress.at(at(4_000))
        val closed = progress.at(at(8_000))
        // Half the window, half the share: a bar that accelerates or decelerates inside a
        // phase reads as jumping even though it only ever moves forward.
        assertEquals(middle - opened, closed - middle, 0.002f)
    }

    @Test
    fun `a window that overruns its deadline keeps the bar moving`() {
        val progress = RunProgress(legs = 1, startedAt = start)
        progress.enter(RunProgress.Step.Upload, viaTunnel = true, nowNanos = at(0))
        // The deadline cuts the last body, and the worker still has to close it and read
        // the response — a second or two in which the measurement reports nothing.
        val atDeadline = progress.at(at(8_000))
        val windingDown = progress.at(at(9_500))
        assertTrue("$windingDown после $atDeadline", windingDown > atDeadline)
        assertTrue("перебор дотянул бар до $windingDown", windingDown < 1f)
    }

    @Test
    fun `the two transfers own most of the bar, because they are most of the run`() {
        val progress = RunProgress(legs = 1, startedAt = start)
        progress.enter(RunProgress.Step.Download, viaTunnel = true, nowNanos = at(0))
        val beforeTransfers = progress.at(at(0))
        assertTrue(
            "поиску и задержке досталось $beforeTransfers прогона",
            1f - beforeTransfers > 0.7f,
        )
    }

    @Test
    fun `a straggling sample from the step just finished cannot pull the bar back`() {
        val progress = RunProgress(legs = 2, startedAt = start)
        progress.enter(RunProgress.Step.Upload, viaTunnel = true, nowNanos = at(0))
        val reached = progress.at(at(4_000))
        progress.enter(RunProgress.Step.Latency, viaTunnel = true, nowNanos = at(4_100))
        assertTrue("$reached упало до ${progress.at(at(4_100))}", progress.at(at(4_100)) >= reached)
    }

    @Test
    fun `the bar cannot pass the end however long a step runs`() {
        val progress = RunProgress(legs = 1, startedAt = start)
        progress.enter(RunProgress.Step.Upload, viaTunnel = true, nowNanos = at(0))
        assertTrue(progress.at(at(10_000_000)) <= 1f)
    }

    private companion object {
        /** The clock's resolution in these walks, close to the screen's own tick. */
        const val STEP_MS = 100L
    }
}

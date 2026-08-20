package com.th3web.lean.data.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A native Go fatal dump is the one crash class Kotlin cannot catch — it kills the
 * process outright, which users report as "приложение просто пропадает с экрана". The
 * dump names its cause on the FIRST line and then prints hundreds of lines of idle
 * goroutines parked in `gopark`.
 *
 * Keeping the last N lines therefore threw away the only informative part. A real 1.0.0
 * report arrived as fifty lines of signal-handler bookkeeping with the fault cut off the
 * top, which is what these tests exist to prevent recurring.
 */
class CrashLogFaultHeadTest {

    private fun goDump(appLines: Int = 20, tailGoroutines: Int = 400): List<String> = buildList {
        repeat(appLines) { add("INFO[0000] app line $it") }
        add("fatal error: unexpected signal during runtime execution")
        add("[signal SIGSEGV: segmentation violation code=0x1 addr=0x0]")
        add("goroutine 42 gp=0x74d4 m=7 [running]:")
        add("runtime.throw({0x1?, 0x2?})")
        repeat(tailGoroutines) { add("goroutine $it gp=0x0 m=nil [chan receive]:") }
    }

    @Test
    fun `the fault line survives a dump far longer than the cap`() {
        val kept = CrashRedactor.logLines(goDump())
        assertTrue(
            "the fatal line must be kept — it is the whole diagnosis:\n" + kept.joinToString("\n"),
            kept.any { it.contains("fatal error") },
        )
        assertTrue(
            "the signal line names the fault kind",
            kept.any { it.contains("SIGSEGV") },
        )
    }

    @Test
    fun `context before the fault is kept so the crash can be tied to what ran`() {
        val kept = CrashRedactor.logLines(goDump())
        assertTrue(
            "some app lines before the fault must survive, otherwise the dump says " +
                "nothing about which connection produced it",
            kept.any { it.contains("app line") },
        )
    }

    @Test
    fun `an ordinary log with no fault still keeps its tail`() {
        // Regression guard: the head-seeking behaviour must not hijack normal logs, where
        // the most recent lines are the interesting ones.
        val ordinary = (1..200).map { "INFO[0000] line $it" }
        val kept = CrashRedactor.logLines(ordinary)
        assertTrue("the newest line must be present", kept.any { it.contains("line 200") })
        assertEquals(CrashRedactor.MAX_LOG_LINES, kept.size)
    }

    @Test
    fun `the cap is still respected`() {
        val kept = CrashRedactor.logLines(goDump())
        assertTrue(kept.size <= CrashRedactor.MAX_LOG_LINES)
        assertTrue(kept.sumOf { it.length } <= CrashRedactor.MAX_LOG_TOTAL_CHARS)
    }
}

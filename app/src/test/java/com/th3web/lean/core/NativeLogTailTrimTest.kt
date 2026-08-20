package com.th3web.lean.core

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The core's log file is its redirected stderr: it only ever grows, and nothing else
 * shortens it. A device that had been running the app for weeks carried 18.8 MB of it in
 * cache, of which only the tail is ever read — for a diagnostics report.
 */
class NativeLogTailTrimTest {

    @get:Rule
    val folder = TemporaryFolder()

    private fun file(lines: Int): File =
        folder.newFile("neko.log").apply {
            writeText((1..lines).joinToString("\n", postfix = "\n") { "line $it" })
        }

    @Test
    fun `a file under the cap is left alone`() {
        val log = file(10)
        val before = log.readText()

        assertEquals(0, NativeLogTail(log).trimTo(maxBytes = 1_000_000, keepBytes = 4_096))
        assertEquals(before, log.readText())
    }

    @Test
    fun `an oversized file keeps its tail and loses the rest`() {
        val log = file(5_000)
        val sizeBefore = log.length()

        val freed = NativeLogTail(log).trimTo(maxBytes = 4_096, keepBytes = 1_024)

        assertTrue("nothing was reclaimed", freed > 0)
        assertTrue("not actually smaller", log.length() < sizeBefore)
        assertTrue("kept far more than asked", log.length() <= 1_024)
        val kept = log.readLines().filter { it.isNotEmpty() }
        // The TAIL is what matters: the newest lines are the ones a report needs.
        assertEquals("line 5000", kept.last())
        // And it must start on a line boundary, not mid-line where the cut landed.
        assertTrue("first kept line is a fragment: ${kept.first()}", kept.first().startsWith("line "))
    }

    /**
     * The tailer tracks how far it has read. Leaving that offset past the new, shorter end
     * would make the next read see a "truncated" file and start over, replaying the whole
     * tail into the log the user is looking at.
     */
    @Test
    fun `the read offset follows the file down`() {
        val log = file(5_000)
        val tail = NativeLogTail(log)
        tail.readNewLines()

        tail.trimTo(maxBytes = 4_096, keepBytes = 1_024)

        assertEquals("a trim must not look like new content", emptyList<String>(), tail.readNewLines())
    }
}

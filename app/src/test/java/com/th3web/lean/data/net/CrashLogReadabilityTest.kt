package com.th3web.lean.data.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a diagnostics report has to preserve to be worth sending at all.
 *
 * Each test here is a real report that arrived unreadable. Between them they cost a full
 * round of guessing about a live outage, so the guards are deliberately literal about the
 * shape of a sing-box log line.
 */
class CrashLogReadabilityTest {

    /** The line as the core actually writes it, reason last. */
    private fun coreLine(host: String, reason: String) =
        "ERROR[0015] [3928976230 14.29s] connection: open connection to $host:443 " +
            "using outbound/hysteria2[proxy]: dial udp 198.51.100.7:8444: $reason"

    /**
     * The bug that made four reports in a row useless: the cut kept the boilerplate head
     * and threw away the closing clause, so every failure read as "…dial udp …: ope" and
     * could have been either a cancelled dial or a blocked socket — a difference that
     * decides where to look.
     */
    @Test
    fun `the reason at the end of a core line survives the cut`() {
        val lines = (1..CrashRedactor.MAX_LOG_LINES).map {
            val host = "webcast$it-normal-" + "y".repeat(250) + ".tiktokv.com"
            coreLine(host, "operation was canceled")
        }

        val kept = CrashRedactor.logLines(lines)

        assertTrue("the lines must actually have been cut", kept.all { it.length < 400 })
        assertTrue(
            "the failure reason is the only part that diagnoses anything:\n" +
                kept.joinToString("\n"),
            kept.all { it.endsWith("operation was canceled") },
        )
    }

    /**
     * And a line of ordinary length is not cut at all. The budget was set so low that the
     * everyday case — one connection to one host, failing for one reason — did not fit.
     */
    @Test
    fun `an ordinary core line fits whole`() {
        val lines = (1..CrashRedactor.MAX_LOG_LINES).map {
            coreLine("webcast$it-normal-ycru.tiktokv.com", "no recent network activity")
        }

        val kept = CrashRedactor.logLines(lines)

        assertFalse(
            "a plain core line must arrive intact:\n" + kept.first(),
            kept.any { it.contains(CrashRedactor.TRUNCATED) },
        )
    }

    /** The head still identifies which connection it was. */
    @Test
    fun `the head of a cut line still names the connection`() {
        // Distinct hosts on purpose: identical lines would be collapsed into one, which
        // gets the whole budget to itself and is then never cut at all.
        val kept = CrashRedactor.logLines(
            List(CrashRedactor.MAX_LOG_LINES) { index ->
                coreLine("a".repeat(300) + "$index.example.com", "no recent network activity")
            },
        )

        assertTrue(kept.first().startsWith("ERROR[0015]"))
        assertTrue(kept.first().contains(CrashRedactor.TRUNCATED))
    }

    /**
     * A core stuck in a retry loop wrote the same malformed-DNS error every 50 ms. Nine of
     * the fifty lines kept were that one event, and the connection errors that explained
     * the outage had been pushed out of the buffer by it.
     */
    @Test
    fun `a retry loop is collapsed instead of flooding the report`() {
        val loop = (0 until 40).map {
            "ERROR[0044] [174647803 ${32 + it * 0.05}s] router: process DNS packet: " +
                "unpack request: bad question name: dns: buffer size too small"
        }
        val wanted = (1..20).map { coreLine("host$it.example.com", "connection refused") }

        val kept = CrashRedactor.logLines(loop + wanted)

        assertTrue(
            "the repeat must be reported once, with its count",
            kept.any { it.contains("bad question name") && it.contains("×40") },
        )
        assertEquals(
            "collapsing exists to make room — every distinct line must fit",
            20,
            kept.count { it.contains("host") },
        )
    }

    /** Distinct lines are never merged, whatever they have in common. */
    @Test
    fun `lines that only look alike are all kept`() {
        val lines = (1..30).map { coreLine("host$it.example.com", "i/o timeout") }

        val kept = CrashRedactor.logLines(lines)

        assertEquals(30, kept.size)
        assertFalse(kept.any { it.contains("×") })
    }

    /**
     * The size limit is counted in bytes and every budget above it in characters. Russian
     * app narration is two bytes a character, so a report inside its character budget can
     * be over the byte limit — and used to throw there, losing the whole thing.
     */
    @Test
    fun `a report too large in bytes is trimmed instead of lost`() {
        val russian = "⚠ туннель не отвечает — сбрасываю соединения и DNS, ".repeat(6)
        val payload = CrashPayload(
            appVersion = "1.1.0",
            exceptionType = "ManualDiagnostics",
            message = "У".repeat(CrashRedactor.MAX_MESSAGE_CHARS),
            stackTrace = "at com.th3web.lean.Ошибка".repeat(400),
            logTail = CrashRedactor.logLines(
                List(CrashRedactor.MAX_LOG_LINES) { "$it $russian" },
            ),
        )

        val encoded = CrashCodec.encodePayload(payload)

        assertTrue(encoded.size < CrashCodec.MAX_REQUEST_BYTES)
        val text = encoded.toString(Charsets.UTF_8)
        assertTrue("the newest lines must survive the trim", text.contains("49 ⚠"))
        assertTrue("the oldest lines must survive the trim", text.contains("0 ⚠"))
    }
}

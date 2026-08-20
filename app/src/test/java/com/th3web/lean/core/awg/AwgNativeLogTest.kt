package com.th3web.lean.core.awg

import java.io.ByteArrayInputStream
import java.io.InputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The AmneziaWG core logs the reason for every refusal through the Android log and
 * nowhere else, so this is the only path by which "не удалось запустить" can ever say
 * WHY. It runs on the failure path, which is exactly where a second failure would be
 * least welcome — hence the emphasis below on it never throwing.
 */
class AwgNativeLogTest {

    private fun log(
        output: String = "",
        exec: ((List<String>) -> Process)? = null,
    ) = AwgNativeLog(exec = exec ?: { FakeProcess(output) })

    @Test
    fun `it dumps the core's tags and exits instead of following the stream`() {
        var command: List<String> = emptyList()
        log(exec = { argv -> command = argv; FakeProcess("") }).tail()

        assertEquals("logcat", command.first())
        // -d, or the read never returns and the connect hangs on its own diagnostics.
        assertTrue("must dump and exit: $command", "-d" in command)
        assertTrue("must silence everything else: $command", "*:S" in command)
        assertTrue("must re-enable the core's tag: $command", "AmneziaWG/*:V" in command)
    }

    @Test
    fun `the core's lines come back trimmed and framing is dropped`() {
        val lines = log(
            """
            --------- beginning of main
            E/AmneziaWG/lean-awg-3( 1234): IpcSet: invalid UAPI device key: i1
            D/AmneziaWG/lean-awg-3( 1234): Device started

            """.trimIndent() + "\n",
        ).tail()

        assertEquals(
            listOf(
                "E/AmneziaWG/lean-awg-3( 1234): IpcSet: invalid UAPI device key: i1",
                "D/AmneziaWG/lean-awg-3( 1234): Device started",
            ),
            lines,
        )
    }

    /**
     * A ROM may refuse to run logcat at all. Then the right outcome is the tunnel's own
     * failure message on its own — not an exception replacing a real diagnosis with a
     * worse one.
     */
    @Test
    fun `a platform that refuses logcat yields nothing rather than throwing`() {
        assertEquals(emptyList<String>(), log(exec = { error("no logcat here") }).tail())
    }

    private class FakeProcess(output: String) : Process() {
        private val stream: InputStream = ByteArrayInputStream(output.toByteArray())
        override fun getInputStream(): InputStream = stream
        override fun getOutputStream() = java.io.ByteArrayOutputStream()
        override fun getErrorStream(): InputStream = ByteArrayInputStream(ByteArray(0))
        override fun waitFor(): Int = 0
        override fun exitValue(): Int = 0
        override fun destroy() = Unit
    }
}

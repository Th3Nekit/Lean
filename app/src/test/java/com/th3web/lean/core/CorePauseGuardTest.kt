package com.th3web.lean.core

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Where the core is allowed to be paused, and by what signal.
 *
 * This shipped once as an unconditional behaviour keyed on SCREEN_OFF, and it broke every
 * config. The comment justifying it claimed a paused core "carries traffic exactly as
 * before"; it does not — sing-box's pause manager holds NEW CONNECTIONS until wake(), so a
 * phone with its screen off had no working tunnel. Measured on a device, same network,
 * same minute: screen off, curl through the tunnel timed out at 12 s; screen on, the same
 * request returned 204 in 0.33 s.
 *
 * What replaced it is opt-in and keyed on Doze. The rules below are what keep it that way,
 * because both mistakes are invisible in review: a call added anywhere else pauses the core
 * for everyone, and a screen watcher looks like a reasonable proxy for "nobody is using the
 * phone" right up until someone plays music.
 *
 * Source is scanned as text: unit tests run with the module directory as the working
 * directory, the same way [AppIconWiringTest] reads the manifest.
 */
class CorePauseGuardTest {

    /** [DozePause] owns the decision; [NekoBox] and its adapter merely declare the call. */
    private val allowed = setOf("DozePause.kt", "NekoEngine.kt", "LeanNativePlatform.kt")

    private val sources: List<File> by lazy {
        val root = File("src/main/java")
        assertTrue("cannot find sources from ${File(".").absolutePath}", root.isDirectory)
        root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
    }

    @Test
    fun `only the Doze switch may put the core to sleep`() {
        val callers = sources.filter { file ->
            file.name !in allowed && file.readLines().any { line ->
                Regex("""\.(sleep|wake)\(\)""").containsMatchIn(line.substringBefore("//"))
            }
        }.map { it.name }.sorted()

        assertEquals(
            "these pause the core outside [DozePause]; see this test for why that is fatal",
            emptyList<String>(),
            callers,
        )
    }

    /**
     * A dark screen is not an idle phone — it is also music, a download, a call, a hotspot.
     * Doze is the system's own judgement that the device is unattended, and it is the only
     * signal whose meaning matches what pausing does.
     */
    @Test
    fun `nothing pauses on the screen turning off`() {
        val watchers = sources.filter { it.readText().contains("ACTION_SCREEN_OFF") }
            .map { it.name }
            .sorted()

        assertEquals(
            "a screen watcher is back; it is how the core got paused in a pocket",
            emptyList<String>(),
            watchers,
        )
    }

    /** Off unless the user asks for it: the cost is a tunnel that stops carrying traffic. */
    @Test
    fun `the switch ships off`() {
        assertEquals(false, com.th3web.lean.data.Settings().dozePause)
    }
}

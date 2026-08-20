package com.th3web.lean.core.plugin

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Stopping a helper that never got as far as running.
 *
 * The ordinary case, not an edge one: a device with no binary for its ABI, a helper that
 * failed to restart, a connect abandoned before the process was up. Cleanup used to
 * return early there — leaving the generated config on disk, which is a file with the
 * node's credentials in it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PluginProcessStopRobolectricTest {

    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun `stopping a helper that never started still removes its config`() {
        val work = folder.newFolder("work")
        val config = File(work, "naive.json").apply { writeText("""{"listen":"socks://127.0.0.1:1"}""") }
        val process = PluginProcess(
            context = RuntimeEnvironment.getApplication(),
            plugin = NativePlugin.Naive,
            configFile = config,
            arguments = listOf(config.absolutePath),
            environment = emptyMap(),
            workingDirectory = work,
        )

        // No binary is unpacked under Robolectric, so start() cannot have left a process
        // behind — exactly the state the early return used to walk out of.
        runCatching { process.start() }
        process.stop()

        assertFalse("a config holding credentials must not outlive the session", config.exists())
    }
}

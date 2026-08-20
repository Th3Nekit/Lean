package com.th3web.lean.core.engine

import android.net.Network
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertNull
import org.junit.Test

class LeanNativePlatformContractTest {
    @Test
    fun `wifi state always contains both ABI fields`() {
        assertEquals(",", WifiStateFormatter.format(null, null))
        assertEquals("LeanNet,", WifiStateFormatter.format("\"LeanNet\"", null))
        assertEquals(",00:11:22:33:44:55", WifiStateFormatter.format(null, "00:11:22:33:44:55"))
        assertEquals(
            "LeanNet,00:11:22:33:44:55",
            WifiStateFormatter.format("\"LeanNet\"", "00:11:22:33:44:55"),
        )
    }

    @Test
    fun `process delegate always routes callbacks to latest service`() {
        val first = RecordingService()
        val second = RecordingService()

        ActiveNativeService.install(first)
        ActiveNativeService.install(second)
        ActiveNativeService.selectorChanged("proxy", "node-b")
        ActiveNativeService.uninstall(first)

        assertEquals(emptyList<Pair<String, String>>(), first.selections)
        assertEquals(listOf("proxy" to "node-b"), second.selections)
        assertNull(ActiveNativeService.currentNetwork())

        ActiveNativeService.uninstall(second)
    }

    @Test
    fun `uninstalling stale service keeps current process delegate`() {
        val first = RecordingService()
        val second = RecordingService()

        ActiveNativeService.install(first)
        ActiveNativeService.install(second)
        ActiveNativeService.uninstall(first)

        assertSame(second, ActiveNativeService.currentOrNull())

        ActiveNativeService.uninstall(second)
    }

    private class RecordingService : NativeServiceBridge {
        val selections = mutableListOf<Pair<String, String>>()

        override fun openTun(tunJson: String, platformOptionsJson: String): Long = 1L
        override fun protectSocket(fd: Int) = Unit
        override fun currentNetwork(): Network? = null
        override fun selectorChanged(tag: String, selected: String) {
            selections += tag to selected
        }
    }
}

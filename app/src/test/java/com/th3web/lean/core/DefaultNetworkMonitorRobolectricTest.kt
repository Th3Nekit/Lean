package com.th3web.lean.core

import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26])
class DefaultNetworkMonitorRobolectricTest {
    @Test
    fun `startup without a physical network waits for a callback`() {
        val monitor = DefaultNetworkMonitor(RuntimeEnvironment.getApplication())

        try {
            monitor.start { _, _ -> }
        } finally {
            monitor.close()
        }
    }
}

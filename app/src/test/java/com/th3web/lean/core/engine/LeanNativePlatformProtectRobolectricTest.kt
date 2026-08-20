package com.th3web.lean.core.engine

import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Regression guard: sing-box calls autoDetectInterfaceControl for every outbound
 * socket it opens regardless of whether Lean's own VPN is running. The old
 * implementation forwarded straight to ActiveNativeService.require(), which
 * throws "VPN service is not active" when nothing is installed — fine for the
 * real tunnel (LeanVpnService always installs itself before starting the core),
 * fatal for UrlTestPinger's standalone off-VPN instance, whose very first
 * outbound dial would always throw and turn every "URL Test" ping into an
 * unconditional miss. Off-VPN there is no tunnel to protect against anyway, so
 * this must be a silent no-op, not a crash.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LeanNativePlatformProtectRobolectricTest {

    @Test
    fun `autoDetectInterfaceControl does not throw when no VPN service is installed`() {
        val platform = LeanNativePlatform(RuntimeEnvironment.getApplication())

        // No ActiveNativeService.install() anywhere in this test — simulates the
        // standalone off-VPN test-instance path. Must not throw.
        platform.autoDetectInterfaceControl(fd = 42)
    }

    @Test
    fun `autoDetectInterfaceControl still protects through the installed service`() {
        val platform = LeanNativePlatform(RuntimeEnvironment.getApplication())
        val service = RecordingProtectService()
        ActiveNativeService.install(service)
        try {
            platform.autoDetectInterfaceControl(fd = 7)
            org.junit.Assert.assertEquals(listOf(7), service.protectedFds)
        } finally {
            ActiveNativeService.uninstall(service)
        }
    }

    private class RecordingProtectService : NativeServiceBridge {
        val protectedFds = mutableListOf<Int>()
        override fun openTun(tunJson: String, platformOptionsJson: String): Long = 1L
        override fun protectSocket(fd: Int) { protectedFds += fd }
        override fun currentNetwork(): android.net.Network? = null
        override fun selectorChanged(tag: String, selected: String) = Unit
    }
}

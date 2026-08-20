package com.th3web.lean.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LeanVpnServiceStartPolicyTest {

    @Test
    fun onlyExplicitStartActionCanStartVpn() {
        assertTrue(isExplicitVpnStartAction(LeanVpnService.ACTION_START))
        assertFalse(isExplicitVpnStartAction(null))
        assertFalse(isExplicitVpnStartAction(LeanVpnService.ACTION_STOP))
        assertFalse(isExplicitVpnStartAction(LeanVpnService.ACTION_PAUSE))
        assertFalse(isExplicitVpnStartAction("unexpected"))
    }

    /**
     * The other half of the same decision, and the one with teeth: a start that names
     * none of the app's own actions came from Android, and must bring the tunnel UP
     * rather than shut it down.
     *
     * Getting this wrong is invisible and total. Always-on VPN starts the service exactly
     * this way, and while every such start was answered with a stop the feature did
     * nothing at all — the system brought us up, we shut ourselves down, and the user got
     * no tunnel, no error and no notification. The same path is how Android re-creates a
     * service it killed, which is what a dropped tunnel that never comes back looks like.
     */
    @Test
    fun aStartCarryingNoneOfOurActionsIsTheSystemsAndMustConnect() {
        // A null intent — a sticky re-creation, and always-on at boot.
        assertTrue(isSystemVpnStart(null))
        // The action the platform itself uses to address a VpnService.
        assertTrue(isSystemVpnStart("android.net.VpnService"))
        assertTrue(isSystemVpnStart("unexpected"))
    }

    @Test
    fun theAppsOwnActionsAreNeverMistakenForTheSystems() {
        assertFalse(isSystemVpnStart(LeanVpnService.ACTION_START))
        // Both of these mean STOP. Reading either as a system start would turn the
        // user's own "disconnect" into an immediate reconnect.
        assertFalse(isSystemVpnStart(LeanVpnService.ACTION_STOP))
        assertFalse(isSystemVpnStart(LeanVpnService.ACTION_PAUSE))
    }
}

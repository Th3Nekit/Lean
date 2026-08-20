package com.th3web.lean.ui.screen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import com.th3web.lean.core.VpnState
import com.th3web.lean.data.model.Outbound
import com.th3web.lean.data.model.Profile
import java.util.Locale

class HomePresentationTest {

    @Test
    fun telegramBannerUsesTheApprovedCopyAndHttpsTarget() {
        assertEquals("Lean VPN в Telegram", TELEGRAM_BOT_TITLE)
        assertEquals(
            "Оформите подписку и управляйте доступом в боте.",
            TELEGRAM_BOT_BODY,
        )
        assertEquals("Открыть бота", TELEGRAM_BOT_CTA)
        assertEquals("https://t.me/VPN_Lean_bot", TELEGRAM_BOT_URL)
    }

    @Test
    fun publicErrorNeverEchoesRuntimeDetails() {
        val privateDetails = listOf(
            "dial tcp vpn.example.net:443: timeout",
            "invalid private_key=top-secret profile=Work",
            "permission denied for endpoint 203.0.113.42",
            "token=abc123",
        )

        privateDetails.forEach { raw ->
            val label = publicConnectionError(raw).messageKey
            assertFalse(label.contains(raw, ignoreCase = true))
            raw.split(' ', '=', ':')
                .filter { it.length >= 6 }
                .forEach { fragment ->
                    assertFalse(label.contains(fragment, ignoreCase = true))
                }
        }
    }

    @Test
    fun publicErrorUsesStableCategories() {
        assertEquals(
            PublicConnectionError.VpnPermission,
            publicConnectionError("VpnService.prepare permission denied"),
        )
        assertEquals(
            PublicConnectionError.ServerSettings,
            publicConnectionError("invalid outbound config"),
        )
        assertEquals(
            PublicConnectionError.Network,
            publicConnectionError("DNS lookup timeout"),
        )
        assertEquals(
            PublicConnectionError.Connection,
            publicConnectionError("unexpected native failure"),
        )
    }

    @Test
    fun publicErrorClassificationDoesNotDependOnDeviceLocale() {
        val originalLocale = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"))
            assertEquals(
                PublicConnectionError.ServerSettings,
                publicConnectionError("INVALID CONFIG"),
            )
        } finally {
            Locale.setDefault(originalLocale)
        }
    }

    @Test
    fun heroMotionIsStaticForIdleErrorAndReducedMotion() {
        assertEquals(
            ConnectHeroMotion.Static,
            connectHeroMotion(VpnState.Disconnected, animationsEnabled = true),
        )
        assertEquals(
            ConnectHeroMotion.Static,
            connectHeroMotion(VpnState.Error("secret endpoint"), animationsEnabled = true),
        )
        assertEquals(
            ConnectHeroMotion.Static,
            connectHeroMotion(VpnState.Connecting, animationsEnabled = false),
        )
        assertEquals(
            ConnectHeroMotion.Static,
            connectHeroMotion(VpnState.Connected("profile"), animationsEnabled = false),
        )
    }

    @Test
    fun heroMotionOnlyRunsForActiveConnectionStates() {
        assertEquals(
            ConnectHeroMotion.Connecting,
            connectHeroMotion(VpnState.Connecting, animationsEnabled = true),
        )
        assertEquals(
            ConnectHeroMotion.Connecting,
            connectHeroMotion(VpnState.Stopping, animationsEnabled = true),
        )
        assertEquals(
            ConnectHeroMotion.Connected,
            connectHeroMotion(VpnState.Connected("profile"), animationsEnabled = true),
        )
    }

    @Test
    fun successfulSubscriptionRefreshQueuesOnlyItsProfilesForPing() {
        val profiles = listOf(
            profile(id = "first", subscriptionId = "sub-a"),
            profile(id = "second", subscriptionId = "sub-b"),
            profile(id = "third", subscriptionId = "sub-a"),
        )

        assertEquals(
            listOf("first", "third"),
            refreshedProfilesForPing(
                refreshSucceeded = true,
                pingOnUpdate = true,
                subscriptionId = "sub-a",
                profiles = profiles,
            ).map(Profile::id),
        )
    }

    @Test
    fun failedOrDisabledRefreshDoesNotQueueProfilesForPing() {
        val profiles = listOf(profile(id = "first", subscriptionId = "sub-a"))

        assertEquals(
            emptyList<Profile>(),
            refreshedProfilesForPing(
                refreshSucceeded = false,
                pingOnUpdate = true,
                subscriptionId = "sub-a",
                profiles = profiles,
            ),
        )
        assertEquals(
            emptyList<Profile>(),
            refreshedProfilesForPing(
                refreshSucceeded = true,
                pingOnUpdate = false,
                subscriptionId = "sub-a",
                profiles = profiles,
            ),
        )
    }

    private fun profile(id: String, subscriptionId: String) = Profile(
        id = id,
        name = id,
        subscriptionId = subscriptionId,
        outbound = Outbound.Vless(
            server = "$id.example",
            serverPort = 443,
            uuid = id,
        ),
    )
}

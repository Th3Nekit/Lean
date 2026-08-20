package com.th3web.lean.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import com.th3web.lean.data.model.AmneziaParams
import com.th3web.lean.data.model.Outbound
import com.th3web.lean.data.model.Profile

/**
 * Coverage of ProfileRepository.reconcile() — the data-integrity merge on every
 * subscription refresh. reconcile is a pure List<Profile> -> List<Profile> function;
 * we only need a ProfileRepository instance to call it. Its ctor touches
 * Context.filesDir + StoreCodec, so we build it with a real Robolectric application
 * Context (Robolectric is already wired and green; Mockito is NOT on the test
 * classpath, so a mocked Context would not compile).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ReconcileTest {

    private fun repo(): ProfileRepository =
        ProfileRepository(RuntimeEnvironment.getApplication())

    private fun wg(host: String, awg: AmneziaParams? = null) = Outbound.WireGuard(
        server = host, serverPort = 51820,
        privateKey = "priv", peerPublicKey = "pub", localAddresses = listOf("10.0.0.2/32"),
    ).copy(awg = awg)

    private fun vless(host: String) = Outbound.Vless(server = host, serverPort = 443, uuid = "u-$host")

    /** Local AWG state must survive a refresh that re-emits the node with awg=null. */
    @Test fun refreshWithAwgNull_preservesLocalAwgAndFavoriteAndLatency() {
        val r = repo()
        val params = AmneziaParams(jc = 4, jmin = 8, jmax = 80, s1 = 15, s2 = 16, h1 = "1111111111")
        val old = listOf(
            Profile(
                id = "keep-me", name = "DE-1", outbound = wg("1.2.3.4", awg = params),
                subscriptionId = "sub", latencyMs = 42, createdAt = 123L,
                favorite = true, excludedFromTest = true,
            ),
        )
        val fresh = listOf(Profile(name = "DE-1 renamed by provider", outbound = wg("1.2.3.4", awg = null)))

        val out = r.reconcile(old, fresh)

        assertEquals(1, out.size)
        val p = out.single()
        assertEquals("keep-me", p.id)
        assertEquals(42, p.latencyMs)
        assertEquals(123L, p.createdAt)
        assertTrue(p.favorite)
        assertTrue(p.excludedFromTest)
        val o = p.outbound as Outbound.WireGuard
        assertNotNull("local AWG params must survive a refresh that returned awg=null", o.awg)
        assertEquals(params, o.awg)
        assertEquals("AmneziaWG", o.protocol)
    }

    /** Regression guard: a normal refresh updates server fields but keeps id/favorite/latency. */
    @Test fun normalRefresh_updatesServerFields_keepsIdFavoriteLatency() {
        val r = repo()
        val old = listOf(
            Profile(id = "id-1", name = "old name", outbound = vless("5.6.7.8"),
                subscriptionId = "sub", latencyMs = 17, createdAt = 9L, favorite = true),
        )
        val fresh = listOf(Profile(name = "new provider name", outbound = vless("5.6.7.8")))

        val p = r.reconcile(old, fresh).single()
        assertEquals("id-1", p.id)
        assertEquals("new provider name", p.name)
        assertEquals(17, p.latencyMs)
        assertEquals(9L, p.createdAt)
        assertTrue(p.favorite)
    }

    @Test fun genuinelyNewServer_isAdded_freshDefaults() {
        val r = repo()
        val old = listOf(Profile(id = "id-1", name = "A", outbound = vless("1.1.1.1"), favorite = true))
        val fresh = listOf(Profile(name = "B", outbound = vless("9.9.9.9")))

        val p = r.reconcile(old, fresh).single()
        assertEquals("B", p.name)
        assertEquals("9.9.9.9", (p.outbound as Outbound.Vless).server)
        assertNull(p.latencyMs)
        assertFalse("a new server must not inherit favorite", p.favorite)
    }

    @Test fun removedServer_isDropped() {
        val r = repo()
        val old = listOf(
            Profile(id = "a", name = "A", outbound = vless("1.1.1.1")),
            Profile(id = "b", name = "B", outbound = vless("2.2.2.2")),
        )
        val fresh = listOf(Profile(name = "A", outbound = vless("1.1.1.1")))

        val out = r.reconcile(old, fresh)
        assertEquals(1, out.size)
        assertEquals("a", out.single().id)
    }

    @Test fun twoWgNodesSameKeysDifferentAwg_doNotCrossMatch() {
        val r = repo()
        val old = listOf(
            Profile(id = "de", name = "DE", outbound = wg("1.1.1.1", awg = AmneziaParams(jc = 4)), favorite = true),
            Profile(id = "nl", name = "NL", outbound = wg("2.2.2.2", awg = null)),
        )
        val fresh = listOf(Profile(name = "DE", outbound = wg("1.1.1.1", awg = null)))
        val p = r.reconcile(old, fresh).single()
        assertEquals("de", p.id)
        assertTrue(p.favorite)
        assertEquals(AmneziaParams(jc = 4), (p.outbound as Outbound.WireGuard).awg)
    }
}

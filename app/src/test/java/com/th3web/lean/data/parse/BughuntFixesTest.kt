package com.th3web.lean.data.parse

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import com.th3web.lean.data.model.Outbound

/** Regression tests for the 2026-07-03 bug-hunt parser fixes (M3 / L1 / L2). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BughuntFixesTest {

    // M3: a share-link AFTER a WG block must survive (previously swallowed into the block).
    @Test
    fun `share link after a WG conf block is not swallowed`() {
        val body = buildString {
            append("[Interface]\n")
            append("PrivateKey=aGVsbG8gd29ybGQgcHJpdmF0ZSBrZXkgcGFkZGluZz0=\n")
            append("Address=10.0.0.2/32\n")
            append("[Peer]\n")
            append("PublicKey=cHVibGljIGtleSBiYXNlNjQgcGFkZGluZyBoZXJlPT0=\n")
            append("Endpoint=vpn.example.com:51820\n")
            append("AllowedIPs=0.0.0.0/0\n")
            append("vless://11111111-1111-1111-1111-111111111111@vs.example.com:443?security=tls&type=tcp#DE\n")
        }
        val profiles = Subscriptions.parseBody(body)
        // Expect BOTH the WG profile and the VLESS profile.
        assertTrue("WG profile missing: $profiles", profiles.any { it.outbound is Outbound.WireGuard })
        assertTrue("VLESS profile lost: $profiles", profiles.any { it.outbound is Outbound.Vless })
    }

    // L1: an out-of-range hop base port must not leak into server_port (guard, not 0/70000).
    @Test
    fun `hysteria2 with an invalid hop port falls back to 443`() {
        val zero = ShareLinks.parse("hysteria2://pw@host.example.com?mport=0#N")
        assertEquals(443, zero?.outbound?.serverPort)
        val over = ShareLinks.parse("hysteria2://pw@host.example.com?ports=70000#N")
        assertEquals(443, over?.outbound?.serverPort)
        // A valid explicit port is still honoured.
        val ok = ShareLinks.parse("hysteria2://pw@host.example.com:8443?mport=0#N")
        assertEquals(8443, ok?.outbound?.serverPort)
    }

    // L2: a name with a literal % must not be double-decoded.
    @Test
    fun `profile name with a literal percent is preserved`() {
        val p = ShareLinks.parse("vless://11111111-1111-1111-1111-111111111111@h.example.com:443?type=tcp#100%25off")
        // getFragment() already decodes %25 -> %, and there must be no second decode.
        assertEquals("100%off", p?.name)
    }
}

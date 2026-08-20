package com.th3web.lean.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import com.th3web.lean.data.model.Outbound

/**
 * WireGuard/AmneziaWG has no "outbound" form in sing-box (only an "endpoint" one —
 * see SingBoxConfig.buildUrlTestJson's own require()), so UrlTestPinger.supports()
 * must reject it while accepting every real outbound protocol.
 */
class UrlTestPingerSupportsTest {

    @Test
    fun `every proxy outbound is supported`() {
        assertTrue(UrlTestPinger.supports(Outbound.Vless(server = "v.example.com", serverPort = 443, uuid = "u")))
        assertTrue(
            UrlTestPinger.supports(
                Outbound.Shadowsocks(server = "s.example.com", serverPort = 8388, method = "aes-256-gcm", password = "x"),
            ),
        )
        assertTrue(UrlTestPinger.supports(Outbound.Trojan(server = "t.example.com", serverPort = 443, password = "x")))
    }

    /**
     * An XHTTP node IS testable — just not by the core.
     *
     * This has to stay true, because it is the gate the dispatch consults before calling
     * the probe at all. Say false and the row silently falls back to a raw TCP connect,
     * which for a CDN-fronted node times the distance to the nearest edge PoP: five to
     * twenty milliseconds that have nothing to do with the tunnel, sorting the node to
     * the top of the list and poisoning the «Авто» prediction with it.
     */
    @Test
    fun `an xhttp node is testable, through the Xray probe rather than the core`() {
        val plain = Outbound.Vless(server = "v.example.com", serverPort = 443, uuid = "u")
        assertTrue(UrlTestPinger.supports(plain))
        assertTrue(
            UrlTestPinger.supports(
                plain.copy(transport = com.th3web.lean.data.model.TransportSettings(type = "xhttp", path = "/p")),
            ),
        )
        assertTrue(UrlTestPinger.supports(plain.copy(encryption = "mlkem768x25519plus.native.600s.abc")))
    }

    /**
     * The helper-backed protocols that have NO probe of their own still must not enter
     * the URL test: it would build a config whose only outbound is a socks pointer at a
     * local port with nothing behind it, and report a healthy server as dead.
     */
    @Test
    fun `helper protocols without a probe stay out of the URL test`() {
        assertFalse(
            UrlTestPinger.supports(
                Outbound.Mieru(
                    server = "m.example.com", serverPort = 9000,
                    transport = "TCP", username = "u", password = "p",
                ),
            ),
        )
        assertFalse(
            UrlTestPinger.supports(
                Outbound.Naive(server = "n.example.com", serverPort = 443),
            ),
        )
    }

    @Test
    fun `wireguard is not supported`() {
        val wg = Outbound.WireGuard(
            server = "wg.example.com", serverPort = 51820,
            privateKey = "priv", peerPublicKey = "pub", localAddresses = listOf("10.0.0.2/32"),
        )
        assertFalse(UrlTestPinger.supports(wg))
    }
}

package com.th3web.lean.data.net

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import com.th3web.lean.data.model.AmneziaParams
import com.th3web.lean.data.model.Outbound
import com.th3web.lean.data.model.TlsSettings

/**
 * Which outbounds must skip the TCP probe.
 *
 * This is worth pinning because the answer is invisible at the call site: every caller
 * already forwards `Pinger.isUdpService(outbound)`, so a protocol missing from this one
 * function silently reads as unreachable everywhere at once — which is exactly what
 * happened to WireGuard.
 */
class PingerUdpServiceTest {

    @Test
    fun `udp-only protocols never take the tcp probe`() {
        val udpOnly = listOf(
            Outbound.Hysteria2(server = "h2.example.com", serverPort = 443, password = "p"),
            Outbound.Hysteria(server = "h1.example.com", serverPort = 443, authStr = "a"),
            Outbound.Tuic(server = "tuic.example.com", serverPort = 443, uuid = "u", password = "p"),
            // A WireGuard endpoint has no TCP listener at all, so a TCP connect there is
            // not a weak signal — it is a guaranteed failure.
            Outbound.WireGuard(
                server = "wg.example.com",
                serverPort = 51820,
                privateKey = "k",
                peerPublicKey = "pk",
            ),
        )
        udpOnly.forEach { assertTrue(it.protocol, Pinger.isUdpService(it)) }
    }

    /** AmneziaWG is WireGuard with obfuscation — same transport, same verdict. */
    @Test
    fun `amneziawg counts as udp-only too`() {
        val awg = Outbound.WireGuard(
            server = "awg.example.com",
            serverPort = 51820,
            privateKey = "k",
            peerPublicKey = "pk",
            awg = AmneziaParams(jc = 4),
        )
        assertTrue(Pinger.isUdpService(awg))
    }

    @Test
    fun `tcp protocols keep the tcp probe`() {
        val tcp = listOf(
            Outbound.Vless(server = "a.example.com", serverPort = 443, uuid = "u"),
            Outbound.Trojan(
                server = "b.example.com",
                serverPort = 443,
                password = "p",
                tls = TlsSettings(enabled = true, serverName = "b.example.com"),
            ),
            Outbound.Shadowsocks(
                server = "c.example.com",
                serverPort = 8388,
                method = "aes-128-gcm",
                password = "p",
            ),
        )
        tcp.forEach { assertFalse(it.protocol, Pinger.isUdpService(it)) }
    }
}

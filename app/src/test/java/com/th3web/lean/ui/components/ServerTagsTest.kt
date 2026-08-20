package com.th3web.lean.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import com.th3web.lean.data.AppearanceNorm
import com.th3web.lean.data.SettingsDefaults
import com.th3web.lean.data.model.AmneziaParams
import com.th3web.lean.data.model.Outbound
import com.th3web.lean.data.model.RealitySettings
import com.th3web.lean.data.model.TlsSettings
import com.th3web.lean.data.model.TransportSettings

/**
 * The point of the tag rework: every server answers the SAME three questions in the same
 * order. The old scheme emitted one arbitrary follow-up per protocol — REALITY for a
 * Reality VLESS but WS for a WebSocket one, the cipher for Shadowsocks, nothing for a
 * plain VMess — which is what testers meant by "иногда написан тип транспорта, а иногда
 * уровень защиты". These tests pin the consistency rather than the exact words.
 *
 * Robolectric because [tagsFor] reads [com.th3web.lean.ui.theme.LeanColors] for the tag
 * inks, and those are Compose snapshot state.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ServerTagsTest {

    private fun vless(tls: TlsSettings? = null, transport: TransportSettings? = null) =
        Outbound.Vless(server = "v.example.com", serverPort = 443, uuid = "u", tls = tls, transport = transport)

    private val everyOutbound: List<Outbound> = listOf(
        vless(),
        vless(tls = TlsSettings(enabled = true)),
        vless(tls = TlsSettings(enabled = true, reality = RealitySettings(publicKey = "k", shortId = "s"))),
        vless(transport = TransportSettings(type = "ws")),
        Outbound.Vmess(server = "m.example.com", serverPort = 443, uuid = "u", security = "auto", alterId = 0),
        Outbound.Trojan(server = "t.example.com", serverPort = 443, password = "p"),
        Outbound.Shadowsocks(server = "s.example.com", serverPort = 8388, method = "aes-256-gcm", password = "p"),
        Outbound.Hysteria2(server = "h.example.com", serverPort = 443, password = "p"),
        Outbound.Tuic(server = "tu.example.com", serverPort = 443, uuid = "u", password = "p"),
        Outbound.WireGuard(
            server = "w.example.com", serverPort = 51820,
            privateKey = "priv", peerPublicKey = "pub", localAddresses = listOf("10.0.0.2/32"),
        ),
    )

    @Test
    fun `every outbound states all three kinds exactly once`() {
        for (o in everyOutbound) {
            val kinds = tagsFor(o).map { it.kind }
            assertEquals(
                "${o.protocol} must state protocol, security and transport in order",
                listOf(TagKind.Protocol, TagKind.Security, TagKind.Transport),
                kinds,
            )
        }
    }

    @Test
    fun `no tag carries a decorative emoji marker`() {
        // The 🛡/⚡ prefixes duplicated the protocol name and read as decoration at tag size.
        for (o in everyOutbound) {
            for (tag in tagsFor(o)) {
                assertTrue(
                    "tag «${tag.label}» still carries an emoji marker",
                    tag.label.none { it.code > 0x2000 && it != '·' },
                )
            }
        }
    }

    @Test
    fun `a reality node over websocket states BOTH facts`() {
        // The exact case the old code could not express: it emitted REALITY and dropped WS.
        val tags = tagsFor(
            vless(
                tls = TlsSettings(enabled = true, reality = RealitySettings(publicKey = "k", shortId = "s")),
                transport = TransportSettings(type = "ws"),
            ),
        )
        assertEquals("REALITY", tags.first { it.kind == TagKind.Security }.label)
        assertEquals("WS", tags.first { it.kind == TagKind.Transport }.label)
    }

    @Test
    fun `a plain node names its transport instead of leaving it blank`() {
        assertEquals("TCP", tagsFor(vless()).first { it.kind == TagKind.Transport }.label)
        assertEquals("NONE", tagsFor(vless()).first { it.kind == TagKind.Security }.label)
    }

    @Test
    fun `amnezia obfuscation is surfaced on the transport axis`() {
        val awg = Outbound.WireGuard(
            server = "w.example.com", serverPort = 51820,
            privateKey = "priv", peerPublicKey = "pub", localAddresses = listOf("10.0.0.2/32"),
            awg = AmneziaParams(jc = 4),
        )
        assertEquals("UDP · AWG", tagsFor(awg).first { it.kind == TagKind.Transport }.label)
    }

    // ---- the «Плашки» filter ----

    @Test
    fun `flags select exactly the kinds they name`() {
        assertEquals(setOf(TagKind.Protocol), keptTagKinds("p"))
        assertEquals(setOf(TagKind.Security, TagKind.Transport), keptTagKinds("st"))
        assertEquals(
            setOf(TagKind.Protocol, TagKind.Security, TagKind.Transport),
            keptTagKinds(SettingsDefaults.SERVER_TAG_KINDS),
        )
    }

    @Test
    fun `normalisation orders the flags and drops junk`() {
        assertEquals("pst", AppearanceNorm.serverTagKinds("tsp"))
        assertEquals("pt", AppearanceNorm.serverTagKinds("TPx"))
    }

    @Test
    fun `a value that would keep nothing falls back to the default`() {
        // Blanking every row while «Теги протоколов» still reads ON is indistinguishable
        // from a bug, so an empty selection is not a reachable state.
        assertEquals(SettingsDefaults.SERVER_TAG_KINDS, AppearanceNorm.serverTagKinds(""))
        assertEquals(SettingsDefaults.SERVER_TAG_KINDS, AppearanceNorm.serverTagKinds("xyz"))
        assertEquals(SettingsDefaults.SERVER_TAG_KINDS, AppearanceNorm.serverTagKinds(null))
    }
}

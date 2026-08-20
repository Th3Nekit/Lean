package com.th3web.lean.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import com.th3web.lean.data.Settings
import com.th3web.lean.data.model.Outbound
import com.th3web.lean.data.model.Profile
import com.th3web.lean.data.model.RealitySettings
import com.th3web.lean.data.model.TlsSettings

/**
 * Who decides the ClientHello.
 *
 * Forcing a Chrome fingerprint onto every TLS node is OUR behaviour, not the share
 * link's — other clients only apply `fp` when the link carries one. There was no way to
 * turn it off, so a server or middlebox that dislikes the mimicked hello failed in Lean
 * and worked elsewhere with nothing in the UI to try. «Выключено» is that escape hatch,
 * and these tests pin the precedence it lives inside.
 */
class UtlsFingerprintTest {

    private fun json(o: Outbound, s: Settings) =
        SingBoxConfig.buildJson(Profile(name = "n", outbound = o), s)

    private fun vless(tls: TlsSettings) =
        Outbound.Vless(server = "v.example.com", serverPort = 443, uuid = "u", tls = tls)

    private val plainTls = TlsSettings(enabled = true)
    private val realityTls = TlsSettings(
        enabled = true,
        reality = RealitySettings(publicKey = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8", shortId = "0123abcd"),
    )

    @Test
    fun `plain TLS gets a mimicked hello by default`() {
        assertTrue(json(vless(plainTls), Settings()).contains("\"utls\""))
    }

    @Test
    fun `off sends the core's own hello on plain TLS`() {
        val out = json(vless(plainTls), Settings(utlsFingerprint = SingBoxConfig.UTLS_OFF))
        assertFalse(
            "«Выключено» must actually drop the uTLS block — it is the only lever a user " +
                "has when a server rejects the mimicked ClientHello:\n$out",
            out.contains("\"utls\""),
        )
    }

    @Test
    fun `reality keeps a fingerprint even when the setting says off`() {
        // sing-box's Reality client is built ON uTLS and has no no-fingerprint mode, so
        // honouring "off" there would emit a config that cannot dial at all.
        val out = json(vless(realityTls), Settings(utlsFingerprint = SingBoxConfig.UTLS_OFF))
        assertTrue("Reality must still carry a fingerprint:\n$out", out.contains("\"utls\""))
        assertTrue(out.contains("chrome"))
    }

    @Test
    fun `a fingerprint named by the link wins over the setting`() {
        val out = json(
            vless(plainTls.copy(utlsFingerprint = "firefox")),
            Settings(utlsFingerprint = SingBoxConfig.UTLS_OFF),
        )
        assertTrue("the link's own choice is explicit intent:\n$out", out.contains("firefox"))
    }

    @Test
    fun `quic never carries utls regardless of the setting`() {
        // sing-box's QUIC dialer rejects a uTLS config outright ("unsupported usage for
        // uTLS"), so the outbound would build and then fail every dial.
        val hy2 = Outbound.Hysteria2(
            server = "h.example.com", serverPort = 443, password = "p",
            tls = TlsSettings(enabled = true),
        )
        assertFalse(json(hy2, Settings(utlsFingerprint = "chrome")).contains("\"utls\""))
    }
}

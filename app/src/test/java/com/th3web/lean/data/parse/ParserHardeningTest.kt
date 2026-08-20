package com.th3web.lean.data.parse

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import com.th3web.lean.data.model.Outbound

/**
 * Parser hardening regression tests. These exercise the parse paths that depend on
 * android.net.Uri / android.util.Base64, which throw "not mocked" under a plain JVM
 * unit test — Robolectric supplies the real Android impls so they run on
 * `gradle :app:testDebugUnitTest` in CI without a device.
 *
 * Every test is a PAIR: a VALID input still parses (no regression) AND the
 * previously-mishandled bad/edge input is now rejected or handled correctly.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ParserHardeningTest {

    // Encode a UTF-8 string to standard base64 using the JVM codec (independent of
    // the Android Base64 under test) so the vmess base64-JSON fixtures are stable.
    private fun b64(s: String): String =
        java.util.Base64.getEncoder().encodeToString(s.toByteArray(Charsets.UTF_8))

    // ---- Fix A: port upper-bound 1..65535 at every share-link parse site ----

    @Test
    fun vless_validPort_parses_outOfRangePort_rejected() {
        val ok = ShareLinks.parse(
            "vless://b831381d-6324-4d53-ad4f-8cda48b30811@example.com:443?type=tcp#OK",
        )
        assertNotNull("valid vless port must still parse", ok)
        assertEquals(443, ok!!.outbound.serverPort)

        // :99999 is > 65535 — previously accepted (only `> 0` was checked).
        assertNull(
            "vless with port 99999 must be rejected",
            ShareLinks.parse(
                "vless://b831381d-6324-4d53-ad4f-8cda48b30811@example.com:99999?type=tcp#BAD",
            ),
        )
    }

    @Test
    fun trojan_validPort_parses_outOfRangePort_rejected() {
        assertNotNull(ShareLinks.parse("trojan://pass@example.com:8443#OK"))
        assertNull(ShareLinks.parse("trojan://pass@example.com:70000#BAD"))
    }

    @Test
    fun tuic_validPort_parses_outOfRangePort_rejected() {
        assertNotNull(
            ShareLinks.parse("tuic://uuid:pass@example.com:443?congestion_control=bbr#OK"),
        )
        assertNull(ShareLinks.parse("tuic://uuid:pass@example.com:99999#BAD"))
    }

    @Test
    fun vmessUri_validPort_parses_outOfRangePort_rejected() {
        assertNotNull(
            ShareLinks.parse(
                "vmess://b831381d-6324-4d53-ad4f-8cda48b30811@example.com:443?type=tcp#OK",
            ),
        )
        assertNull(
            ShareLinks.parse(
                "vmess://b831381d-6324-4d53-ad4f-8cda48b30811@example.com:99999?type=tcp#BAD",
            ),
        )
    }

    @Test
    fun hysteria2_outOfRangePort_falls_back_not_accepted_verbatim() {
        // hy2 allows auth-less servers and falls back to 443 when the authority port
        // is invalid; the bad :99999 must NOT survive as a literal server_port.
        val p = ShareLinks.parse("hysteria2://auth@example.com:99999?sni=example.com#X")
        assertNotNull(p)
        assertTrue(
            "out-of-range hy2 port must not be emitted verbatim",
            p!!.outbound.serverPort in 1..65535,
        )
    }

    @Test
    fun shadowsocks_sip002_validPort_parses_outOfRangePort_rejected() {
        // SIP002: ss://base64(method:password)@host:port  — port via splitHostPort.
        val creds = b64("aes-256-gcm:secretpw")
        assertNotNull(ShareLinks.parse("ss://$creds@example.com:8388#OK"))
        assertNull(ShareLinks.parse("ss://$creds@example.com:99999#BAD"))
    }

    // ---- Fix B: vmess base64-JSON UNSUPPORTED_NETWORKS guard ----

    @Test
    fun vmess_base64Json_tcp_parses_kcp_rejected() {
        val okJson = """{"v":"2","ps":"OK","add":"example.com","port":"443",""" +
            """"id":"b831381d-6324-4d53-ad4f-8cda48b30811","aid":"0","net":"tcp","tls":""}"""
        val ok = ShareLinks.parse("vmess://${b64(okJson)}")
        assertNotNull("valid tcp vmess base64-JSON must parse", ok)
        assertTrue(ok!!.outbound is Outbound.Vmess)
        assertEquals("example.com", ok.outbound.server)

        // net=kcp has no sing-box transport here — previously imported as a
        // silently-broken transport-less outbound; must now be dropped.
        val kcpJson = """{"v":"2","ps":"BAD","add":"example.com","port":"443",""" +
            """"id":"b831381d-6324-4d53-ad4f-8cda48b30811","aid":"0","net":"kcp","tls":""}"""
        assertNull(
            "vmess base64-JSON with net=kcp must be rejected",
            ShareLinks.parse("vmess://${b64(kcpJson)}"),
        )
    }

    // ---- Fix C: XrayConfig SS EIH — exact 2+-segment predicate, not contains(':') ----

    @Test
    fun xray_ss_singlePsk_chacha20_with_colon_kept() {
        // A non-AES 2022 chacha20 password that merely contains an incidental ':' but
        // is NOT a real multi-segment EIH key (the segments don't decode to 16/32-byte
        // PSKs) must be KEPT — the old `password.contains(':')` dropped it.
        val json = """
            {"outbounds":[{"protocol":"shadowsocks","settings":{"servers":[
              {"address":"example.com","port":8388,
               "method":"2022-blake3-chacha20-poly1305",
               "password":"plain:passwordwithcolon"}
            ]}}]}
        """.trimIndent()
        val profiles = XrayConfig.parse(json)
        assertEquals("legitimate single-PSK chacha20 node must be kept", 1, profiles.size)
        assertTrue(profiles[0].outbound is Outbound.Shadowsocks)
    }

    @Test
    fun xray_ss_genuineEih_chacha20_dropped() {
        // A genuine multi-segment EIH key (each segment base64-decodes to a 32-byte
        // PSK) on a non-AES 2022 method is unrunnable on sing-box → still dropped.
        val psk32a = b64("0123456789abcdef0123456789abcdef") // 32 bytes
        val psk32b = b64("fedcba9876543210fedcba9876543210") // 32 bytes
        val json = """
            {"outbounds":[{"protocol":"shadowsocks","settings":{"servers":[
              {"address":"example.com","port":8388,
               "method":"2022-blake3-chacha20-poly1305",
               "password":"$psk32a:$psk32b"}
            ]}}]}
        """.trimIndent()
        assertTrue(
            "genuine non-AES 2022 EIH node must be dropped",
            XrayConfig.parse(json).isEmpty(),
        )
    }

    @Test
    fun xray_ss_aes2022_eih_kept_because_aes_supported() {
        // sing-box DOES implement EIH for AES 2022 ciphers, so an EIH-shaped AES key
        // must NOT be dropped (the guard only fires on non-AES). Regression anchor.
        val psk32a = b64("0123456789abcdef0123456789abcdef")
        val psk32b = b64("fedcba9876543210fedcba9876543210")
        val json = """
            {"outbounds":[{"protocol":"shadowsocks","settings":{"servers":[
              {"address":"example.com","port":8388,
               "method":"2022-blake3-aes-256-gcm",
               "password":"$psk32a:$psk32b"}
            ]}}]}
        """.trimIndent()
        assertEquals(1, XrayConfig.parse(json).size)
    }

    @Test
    fun xray_ss_outOfRangePort_dropped() {
        // Fix A mirrored into XrayConfig: port 99999 → node dropped, not built.
        val json = """
            {"outbounds":[{"protocol":"shadowsocks","settings":{"servers":[
              {"address":"example.com","port":99999,"method":"aes-256-gcm","password":"pw"}
            ]}}]}
        """.trimIndent()
        assertTrue(XrayConfig.parse(json).isEmpty())

        val ok = """
            {"outbounds":[{"protocol":"shadowsocks","settings":{"servers":[
              {"address":"example.com","port":8388,"method":"aes-256-gcm","password":"pw"}
            ]}}]}
        """.trimIndent()
        assertEquals(1, XrayConfig.parse(ok).size)
    }

    // ---- Fix D: base64-wrapped Xray-JSON (no `base64:` prefix) now parsed ----

    @Test
    fun subscription_base64Wrapped_xrayJson_parses() {
        val xrayJson = """
            [{"remarks":"WrappedNode","outbounds":[{"protocol":"vless",
              "settings":{"vnext":[{"address":"example.com","port":443,
                "users":[{"id":"b831381d-6324-4d53-ad4f-8cda48b30811","encryption":"none"}]}]},
              "streamSettings":{"network":"tcp","security":"none"}}]}]
        """.trimIndent()

        // Sanity: the raw JSON body parses on the existing JSON path.
        val raw = Subscriptions.parseBodyFull(xrayJson)
        assertEquals("raw Xray-JSON body must parse (no regression)", 1, raw.profiles.size)

        // The bug: the SAME JSON, base64-wrapped WITHOUT a `base64:` prefix, used to
        // fall through to the share-link tokenizer and yield nothing.
        val wrapped = Subscriptions.parseBodyFull(b64(xrayJson))
        assertEquals(
            "base64-wrapped Xray-JSON (no prefix) must now parse",
            1,
            wrapped.profiles.size,
        )
        assertEquals("WrappedNode", wrapped.profiles[0].name)
        assertEquals("example.com", wrapped.profiles[0].outbound.server)
    }

    @Test
    fun subscription_plainBase64ShareLinks_still_parse() {
        // Regression anchor: a base64 blob of newline-separated share links (the
        // common subscription shape) must still parse exactly as before — the new
        // per-candidate JSON sniff must not divert it.
        val links = "vless://b831381d-6324-4d53-ad4f-8cda48b30811@a.example.com:443?type=tcp#A\n" +
            "trojan://pw@b.example.com:8443#B"
        val parsed = Subscriptions.parseBodyFull(b64(links))
        assertEquals(2, parsed.profiles.size)
    }
}

package com.th3web.lean.core

import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.Assert.assertThrows
import com.th3web.lean.data.RoutingMode
import com.th3web.lean.data.Settings
import com.th3web.lean.data.model.AmneziaParams
import com.th3web.lean.data.model.Outbound
import com.th3web.lean.data.model.Profile
import com.th3web.lean.data.model.RealitySettings
import com.th3web.lean.data.model.TlsSettings
import com.th3web.lean.data.model.TransportSettings
import java.io.File

/**
 * Generates a sing-box config per protocol/routing variant and writes them to
 * `build/generated-configs/`. CI then runs `sing-box check` on each, validating
 * the schema against the real core (our only schema test without a device).
 *
 * The asserts are sanity checks; the real validation is `sing-box check` in CI.
 */
class SingBoxConfigTest {

    // CI runs on a fresh checkout; just ensure the dirs exist. (JUnit builds a
    // new instance per @Test, so clearing here would wipe siblings' output.)
    private val outDir = File("build/generated-configs").apply { mkdirs() }
    private val smokeDir = File("build/generated-configs/smoke").apply { mkdirs() }
    private fun emit(name: String, profile: Profile, settings: Settings) {
        val json = SingBoxConfig.buildJson(profile, settings)
        File(outDir, "$name.json").writeText(json)
        // Smoke variant (loopback inbound, no TUN) for CI `sing-box run` testing.
        File(smokeDir, "$name.json").writeText(SingBoxConfig.buildJson(profile, settings, smoke = true))
        assertTrue("$name: missing tun inbound", json.contains("\"type\": \"tun\""))
        assertTrue("$name: missing proxy outbound", json.contains("\"tag\": \"proxy\""))
        assertTrue("$name: missing final", json.contains("\"final\": \"proxy\""))
    }

    private fun profile(name: String, o: Outbound) = Profile(name = name, outbound = o)

    /**
     * A helper-backed profile emits a shape nothing else does — a socks outbound pointing
     * at a local port, plus a `direct` INBOUND that rewrites the destination to the real
     * server, plus a first-position route rule sending that inbound straight out.
     *
     * Written into the smoke dir so CI actually STARTS it with the pinned core. That is
     * the point: the direct inbound's options are a part of sing-box that our other
     * configs never touch (and whose outbound twin is deprecated upstream), so a schema
     * drift here would otherwise surface as a tunnel that fails to come up on a device.
     */
    private fun emitPlugin(name: String, profile: Profile) {
        val ports = mapOf(profile.id to SingBoxConfig.PluginPorts(socksPort = 18080, mappingPort = 18081))
        val json = SingBoxConfig.buildJson(listOf(profile), Settings(), smoke = true, plugins = ports)
        File(outDir, "plugin-$name.json").writeText(json)
        File(smokeDir, "plugin-$name.json").writeText(json)

        assertTrue("$name: proxy must be a socks outbound to the helper", json.contains("\"type\": \"socks\""))
        assertTrue("$name: helper socks port missing", json.contains("\"server_port\": 18080"))
        assertTrue("$name: mapping inbound missing", json.contains("\"listen_port\": 18081"))
        assertTrue("$name: mapping must rewrite to the real server", json.contains("\"override_address\""))
        // The rule that stops the helper proxying itself through the tunnel it provides.
        assertTrue("$name: mapping inbound must be routed direct", json.contains("\"plugin-map-${profile.id}\""))
    }

    @Test
    fun pluginNaive() = emitPlugin(
        "naive",
        profile(
            "naive",
            Outbound.Naive(
                server = "naive.example.com",
                serverPort = 443,
                username = "user",
                password = "pass",
                sni = "www.example.com",
            ),
        ),
    )

    @Test
    fun pluginMieru() = emitPlugin(
        "mieru",
        profile(
            "mieru",
            Outbound.Mieru(
                server = "mieru.example.com",
                serverPort = 9000,
                transport = "TCP",
                username = "user",
                password = "pass",
            ),
        ),
    )

    /**
     * The other mapping shape: a SOCKS listener instead of a fixed redirect, for the
     * helpers that name their own destinations (olcRTC, and Xray carrying an XHTTP node).
     *
     * Emitted into the smoke dir for the same reason [emitPlugin] is — CI STARTS this
     * with the pinned core, and a `mixed` inbound sitting on a loopback port beside a tun
     * is a combination none of the other configs exercise.
     */
    private fun emitSocksPlugin(name: String, profile: Profile) {
        val ports = mapOf(profile.id to SingBoxConfig.PluginPorts(socksPort = 18080, mappingPort = 18081))
        val json = SingBoxConfig.buildJson(listOf(profile), Settings(), smoke = true, plugins = ports)
        File(outDir, "plugin-$name.json").writeText(json)
        File(smokeDir, "plugin-$name.json").writeText(json)

        assertTrue("$name: proxy must be a socks outbound to the helper", json.contains("\"type\": \"socks\""))
        assertTrue("$name: mapping inbound must be a socks listener", json.contains("\"type\": \"mixed\""))
        assertTrue("$name: mapping port missing", json.contains("\"listen_port\": 18081"))
        // A fixed redirect here would send every destination the helper names to the one
        // server, which for olcRTC is a meeting service and for Xray a CDN edge.
        assertTrue("$name: a socks mapping must not rewrite the destination", !json.contains("\"override_address\""))
        assertTrue("$name: mapping inbound must be routed direct", json.contains("\"plugin-map-${profile.id}\""))
    }

    @Test
    fun pluginVlessXhttp() = emitSocksPlugin(
        "vless-xhttp",
        profile(
            "VLESS XHTTP",
            Outbound.Vless(
                server = "cdn.example.com",
                serverPort = 443,
                uuid = "b831381d-6324-4d53-ad4f-8cda48b30811",
                network = "xhttp",
                tls = TlsSettings(enabled = true, serverName = "front.example.com", utlsFingerprint = "chrome"),
                transport = TransportSettings(
                    type = "xhttp",
                    path = "/download",
                    host = "real.example.com",
                    mode = "packet-up",
                ),
            ),
        ),
    )

    /** Without allocated ports there is no helper to point at, and emitting anything at
     * all would be a tunnel that connects and carries nothing. It must fail loudly. */
    @Test
    fun pluginOutboundWithoutPortsIsRejected() {
        val p = profile("naive", Outbound.Naive(server = "naive.example.com", serverPort = 443))
        assertThrows(IllegalStateException::class.java) {
            SingBoxConfig.buildJson(listOf(p), Settings())
        }
    }

    @Test
    fun vlessReality() = emit(
        "vless-reality",
        profile(
            "VLESS Reality",
            Outbound.Vless(
                server = "reality.example.com",
                serverPort = 443,
                uuid = "b831381d-6324-4d53-ad4f-8cda48b30811",
                flow = "xtls-rprx-vision",
                network = "tcp",
                tls = TlsSettings(
                    enabled = true,
                    serverName = "www.microsoft.com",
                    utlsFingerprint = "chrome",
                    reality = RealitySettings(
                        // Valid 43-char base64url → exactly 32 bytes (x25519 key length).
                        publicKey = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8",
                        shortId = "0123abcd",
                    ),
                ),
            ),
        ),
        Settings(routingMode = RoutingMode.RULE),
    )

    @Test
    fun vlessWsTls() = emit(
        "vless-ws-tls",
        profile(
            "VLESS WS",
            Outbound.Vless(
                server = "1.2.3.4",
                serverPort = 443,
                uuid = "b831381d-6324-4d53-ad4f-8cda48b30811",
                network = "ws",
                tls = TlsSettings(enabled = true, serverName = "cdn.example.com"),
                transport = TransportSettings("ws", path = "/lean", host = "cdn.example.com"),
            ),
        ),
        Settings(routingMode = RoutingMode.GLOBAL, ipv6 = true),
    )

    @Test
    fun vmessWs() = emit(
        "vmess-ws",
        profile(
            "VMess WS",
            Outbound.Vmess(
                server = "1.2.3.4",
                serverPort = 8443,
                uuid = "b831381d-6324-4d53-ad4f-8cda48b30811",
                security = "auto",
                network = "ws",
                tls = TlsSettings(enabled = true, serverName = "cdn.example.com"),
                transport = TransportSettings("ws", path = "/vm", host = "cdn.example.com"),
            ),
        ),
        Settings(),
    )

    @Test
    fun trojanTls() = emit(
        "trojan-tls",
        profile(
            "Trojan",
            Outbound.Trojan(
                server = "trojan.example.com",
                serverPort = 443,
                password = "correct-horse-battery-staple",
                network = "tcp",
                tls = TlsSettings(enabled = true, serverName = "trojan.example.com"),
            ),
        ),
        Settings(),
    )

    @Test
    fun shadowsocks() = emit(
        "shadowsocks",
        profile(
            "Shadowsocks",
            Outbound.Shadowsocks(
                server = "ss.example.com",
                serverPort = 8388,
                // Classic AEAD method: key is derived from the password, so any
                // string is valid (2022 methods require an exact-length b64 key).
                method = "aes-256-gcm",
                password = "lean-shadowsocks-password",
            ),
        ),
        Settings(),
    )

    @Test
    fun hysteria2() = emit(
        "hysteria2",
        profile(
            "Hysteria2",
            Outbound.Hysteria2(
                server = "hy2.example.com",
                serverPort = 443,
                password = "hunter2",
                obfsType = "salamander",
                obfsPassword = "obfs-secret",
                tls = TlsSettings(enabled = true, serverName = "hy2.example.com"),
            ),
        ),
        Settings(),
    )

    @Test
    fun tuic() = emit(
        "tuic",
        profile(
            "TUIC",
            Outbound.Tuic(
                server = "tuic.example.com",
                serverPort = 443,
                uuid = "b831381d-6324-4d53-ad4f-8cda48b30811",
                password = "hunter2",
                congestionControl = "bbr",
                udpRelayMode = "native",
                tls = TlsSettings(enabled = true, serverName = "tuic.example.com"),
            ),
        ),
        Settings(),
    )

    /**
     * Single WireGuard server: emitted as a top-level "endpoints" entry whose tag
     * is TAG_PROXY (so route.final "proxy" and the DNS detour resolve to it). The
     * shared emit() asserts already cover "tag": "proxy" + "final": "proxy" — here
     * we additionally check the endpoint shape so CI `sing-box check`/`run` sees a
     * real wireguard endpoint, not an outbound.
     */
    @Test
    fun wireguardSingle() {
        val settings = Settings()
        val p = profile(
            "WireGuard",
            Outbound.WireGuard(
                server = "192.0.2.1", // IP literal: WG resolves the peer at start; CI has no DNS for a domain
                serverPort = 51820,
                // Valid 44-char base64 → 32 bytes (x25519 key length).
                privateKey = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAEA=",
                peerPublicKey = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAFA=",
                localAddresses = listOf("10.0.0.2/32", "fd00::2/128"),
                allowedIps = listOf("0.0.0.0/0", "::/0"),
                persistentKeepalive = 25,
                mtu = 1408,
            ),
        )
        val json = SingBoxConfig.buildJson(p, settings)
        File(outDir, "wireguard.json").writeText(json)
        File(smokeDir, "wireguard.json").writeText(SingBoxConfig.buildJson(p, settings, smoke = true))
        assertTrue("wireguard: missing endpoints array", json.contains("\"endpoints\""))
        assertTrue("wireguard: missing wireguard endpoint", json.contains("\"type\": \"wireguard\""))
        assertTrue("wireguard: endpoint must use proxy tag", json.contains("\"tag\": \"proxy\""))
        assertTrue("wireguard: missing final proxy", json.contains("\"final\": \"proxy\""))
        // The WG endpoint must NOT also appear as an outbound.
        assertTrue("wireguard: must not emit a wg outbound under outbounds",
            !json.substringBefore("\"endpoints\"").contains("\"type\": \"wireguard\""))
    }

    @Test
    fun wireguardWithoutLocalAddressesIsRejected() {
        val p = profile(
            "WireGuard without interface address",
            Outbound.WireGuard(
                server = "192.0.2.1",
                serverPort = 51820,
                privateKey = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAEA=",
                peerPublicKey = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAFA=",
                localAddresses = emptyList(),
            ),
        )

        val error = assertThrows(IllegalArgumentException::class.java) {
            SingBoxConfig.buildJson(p, Settings())
        }

        assertTrue(error.message.orEmpty().contains("localAddresses"))
    }

    /**
     * Mixed group (vless outbound + wireguard endpoint) in auto mode: vless goes to
     * "outbounds", wg to "endpoints", and BOTH "node-<id>" tags feed urltest +
     * selector. Validates that an endpoint tag is a legal urltest/selector target.
     */
    @Test
    fun wireguardMixedAuto() {
        val vless = profile(
            "VLESS",
            Outbound.Vless(
                server = "de.example.com", serverPort = 443,
                uuid = "b831381d-6324-4d53-ad4f-8cda48b30811",
                network = "tcp",
                tls = TlsSettings(enabled = true, serverName = "de.example.com"),
            ),
        )
        val wg = profile(
            "WireGuard",
            Outbound.WireGuard(
                server = "192.0.2.1", // IP literal: WG resolves the peer at start; CI has no DNS for a domain
                serverPort = 51820,
                privateKey = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAEA=",
                peerPublicKey = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAFA=",
                localAddresses = listOf("10.0.0.2/32"),
                persistentKeepalive = 25,
            ),
        )
        val settings = Settings()
        val json = SingBoxConfig.buildJson(listOf(vless, wg), settings)
        File(outDir, "wg-mixed-auto.json").writeText(json)
        File(smokeDir, "wg-mixed-auto.json").writeText(SingBoxConfig.buildJson(listOf(vless, wg), settings, smoke = true))
        assertTrue("wg-mixed: missing endpoints array", json.contains("\"endpoints\""))
        assertTrue("wg-mixed: missing wireguard endpoint", json.contains("\"type\": \"wireguard\""))
        assertTrue("wg-mixed: missing urltest", json.contains("\"type\": \"urltest\""))
        assertTrue("wg-mixed: missing selector", json.contains("\"type\": \"selector\""))
        // Both nodes' tags must be referenced (own definition + urltest + selector).
        assertTrue("wg-mixed: vless node tag missing", json.contains("\"node-${vless.id}\""))
        assertTrue("wg-mixed: wg node tag missing", json.contains("\"node-${wg.id}\""))
    }

    @Test
    fun amneziaWgIsRejectedByNekoConfig() {
        val p = profile(
            "AmneziaWG",
            Outbound.WireGuard(
                server = "192.0.2.1",
                serverPort = 51820,
                privateKey = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAEA=",
                peerPublicKey = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAFA=",
                preSharedKey = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAQA=",
                localAddresses = listOf("10.0.0.2/32", "fd00::2/128"),
                allowedIps = listOf("0.0.0.0/0", "::/0"),
                persistentKeepalive = 25,
                mtu = 1280,
                awg = AmneziaParams(
                    jc = 4, jmin = 40, jmax = 70,
                    s1 = 86, s2 = 574,
                    // H magic are uint32 (>Int.MAX) → carried & emitted as strings.
                    h1 = "1278178387", h2 = "2179286639",
                    h3 = "3344343040", h4 = "4286611653",
                ),
            ),
        )
        val error = assertThrows(IllegalArgumentException::class.java) {
            SingBoxConfig.buildJson(p, Settings())
        }
        assertTrue(
            "AWG must be routed to the separate Amnezia engine",
            error.message.orEmpty().contains("AmneziaWG"),
        )
    }

    /** mux + TLS fragment toggles must produce a core-valid config. */
    @Test
    fun muxAndFragment() = emit(
        "vless-mux-fragment",
        profile(
            "VLESS mux+frag",
            Outbound.Vless(
                server = "m.example.com", serverPort = 443,
                uuid = "b831381d-6324-4d53-ad4f-8cda48b30811",
                network = "tcp",
                tls = TlsSettings(enabled = true, serverName = "m.example.com"),
            ),
        ),
        Settings(mux = true, fragment = true),
    )

    /** "Авто · быстрейший": a multi-node group → urltest + selector. */
    @Test
    fun autoFastest() {
        val profiles = listOf(
            profile(
                "A",
                Outbound.Vless(
                    server = "a.example.com", serverPort = 443,
                    uuid = "b831381d-6324-4d53-ad4f-8cda48b30811",
                    network = "tcp",
                    tls = TlsSettings(enabled = true, serverName = "a.example.com"),
                ),
            ),
            profile(
                "B",
                Outbound.Trojan(
                    server = "b.example.com", serverPort = 443,
                    password = "correct-horse-battery-staple",
                    tls = TlsSettings(enabled = true, serverName = "b.example.com"),
                ),
            ),
            profile(
                "C",
                Outbound.Shadowsocks(
                    server = "c.example.com", serverPort = 8388,
                    method = "aes-256-gcm", password = "lean-ss-password",
                ),
            ),
        )
        val settings = Settings()
        val json = SingBoxConfig.buildJson(profiles, settings)
        File(outDir, "auto.json").writeText(json)
        File(smokeDir, "auto.json").writeText(SingBoxConfig.buildJson(profiles, settings, smoke = true))
        assertTrue("auto: missing urltest", json.contains("\"type\": \"urltest\""))
        assertTrue("auto: missing selector", json.contains("\"type\": \"selector\""))
        assertTrue("auto: missing auto tag", json.contains("\"tag\": \"auto\""))
        assertTrue("auto: missing final proxy", json.contains("\"final\": \"proxy\""))
    }

    /**
     * Exclude-from-speed-test: an excluded node keeps its outbound and its
     * selector entry (still manually selectable) but leaves the urltest group.
     * Tag occurrence counts: own outbound tag + selector + urltest = 3 for an
     * included node, 2 for an excluded one.
     */
    @Test
    fun autoWithExcludedFromTest() {
        val a = profile(
            "A",
            Outbound.Vless(
                server = "a.example.com", serverPort = 443,
                uuid = "b831381d-6324-4d53-ad4f-8cda48b30811",
                network = "tcp",
                tls = TlsSettings(enabled = true, serverName = "a.example.com"),
            ),
        )
        val b = profile(
            "B",
            Outbound.Trojan(
                server = "b.example.com", serverPort = 443,
                password = "correct-horse-battery-staple",
                tls = TlsSettings(enabled = true, serverName = "b.example.com"),
            ),
        ).copy(excludedFromTest = true)
        val settings = Settings()
        val json = SingBoxConfig.buildJson(listOf(a, b), settings)
        File(outDir, "auto-excluded.json").writeText(json)
        File(smokeDir, "auto-excluded.json").writeText(SingBoxConfig.buildJson(listOf(a, b), settings, smoke = true))
        fun occurrences(id: String) = Regex("\"node-$id\"").findAll(json).count()
        assertTrue("auto-excluded: A must be in outbounds+urltest+selector", occurrences(a.id) == 3)
        assertTrue("auto-excluded: B must be outbound+selector only", occurrences(b.id) == 2)
    }

    /**
     * RU-direct routing must reference geosite-CATEGORY-ru (the real SagerNet set),
     * NOT geosite-ru — "geosite-ru.srs" 404s, which fails rule-set init and stops the
     * whole tunnel (the reported bug). geoip-ru is correct. The bootstrap resolver
     * must be the system "local" server, not a blocked plain 1.1.1.1. Written to
     * outDir for `sing-box check` (schema); NOT to smokeDir — `sing-box run` would try
     * to download the set through the dead example.com proxy. URL correctness (a 404
     * isn't reachable from a JVM test) is asserted by string match here.
     */
    @Test
    fun ruDirectRuleSets() {
        val p = profile(
            "VLESS",
            Outbound.Vless(
                server = "de.example.com", serverPort = 443,
                uuid = "b831381d-6324-4d53-ad4f-8cda48b30811",
                network = "tcp",
                tls = TlsSettings(enabled = true, serverName = "de.example.com"),
            ),
        )
        val json = SingBoxConfig.buildJson(p, Settings(ruDirect = true, routingMode = RoutingMode.RULE))
        File(outDir, "ru-direct.json").writeText(json)
        assertTrue("ru-direct: geoip-ru url", json.contains("https://raw.githubusercontent.com/SagerNet/sing-geoip/rule-set/geoip-ru.srs"))
        assertTrue("ru-direct: geosite-category-ru url", json.contains("https://raw.githubusercontent.com/SagerNet/sing-geosite/rule-set/geosite-category-ru.srs"))
        // The 404-ing geosite-ru.srs must NEVER be emitted again.
        assertTrue("ru-direct: must NOT reference the 404 geosite-ru.srs", !json.contains("geosite-ru.srs"))
        // The direct resolver is the Android platform resolver by default; no "detour" is
        // ever emitted for it (dialed outside the routing engine, so it cannot loop
        // through a proxy that is not up yet).
        val dnsDirect = jsonAfterTag(json, "dns-direct")
        assertTrue("ru-direct: dns-direct is the platform resolver", dnsDirect.contains("\"type\": \"local\""))
        assertTrue("ru-direct: dns-direct has no detour", !dnsDirect.contains("\"detour\""))
    }

    /**
     * «Глобальный режим» / RoutingMode.GLOBAL promises «Весь трафик через прокси» — the UI
     * copy is that literal. ruDirect (and a user's own rule-sets) exist specifically to
     * send SOME destinations around the tunnel, which contradicts that promise; Global
     * must win. This is not cosmetic: on a restrictive/"whitelist" network, anything routed
     * direct is simply dropped, so a leftover ru-direct rule under Global read as "the VPN
     * doesn't work" for exactly the sites it matched, while the app reported Connected.
     */
    @Test
    fun globalModeOverridesRuDirectAndCustomRuleSets() {
        val p = profile(
            "VLESS",
            Outbound.Vless(
                server = "de.example.com", serverPort = 443,
                uuid = "b831381d-6324-4d53-ad4f-8cda48b30811",
                network = "tcp",
                tls = TlsSettings(enabled = true, serverName = "de.example.com"),
            ),
        )
        val global = SingBoxConfig.buildJson(
            p,
            Settings(
                ruDirect = true,
                routingMode = RoutingMode.GLOBAL,
                customRuleSets = listOf("https://example.com/my-geoip.srs"),
            ),
        )
        File(outDir, "global-overrides-ru-direct.json").writeText(global)
        assertTrue("global: no .ru/.su direct rule", !global.contains("\"domain_suffix\""))
        assertTrue("global: no geoip-ru rule_set reference", !global.contains("geoip-ru"))
        assertTrue("global: no custom rule_set reference", !global.contains("custom-0"))

        // Same settings under RULE mode DOES emit them — this is Global's effect
        // specifically, not ruDirect silently stopping working.
        val ruleMode = SingBoxConfig.buildJson(
            p,
            Settings(
                ruDirect = true,
                routingMode = RoutingMode.RULE,
                customRuleSets = listOf("https://example.com/my-geoip.srs"),
            ),
        )
        assertTrue("rule mode: .ru/.su direct rule present", ruleMode.contains("\"domain_suffix\""))
        assertTrue("rule mode: custom rule_set referenced", ruleMode.contains("custom-0"))
    }

    /**
     * Custom user rule-sets ("add your own geoip" like Incy/Happ): each http(s) URL
     * becomes a remote rule_set routed DIRECT, independent of ruDirect; blank /
     * non-http lines are dropped, and tags are stable ("custom-<i>").
     */
    @Test
    fun customRuleSets() {
        val p = profile(
            "VLESS",
            Outbound.Vless(
                server = "de.example.com", serverPort = 443,
                uuid = "b831381d-6324-4d53-ad4f-8cda48b30811",
                network = "tcp",
                tls = TlsSettings(enabled = true, serverName = "de.example.com"),
            ),
        )
        val json = SingBoxConfig.buildJson(
            p,
            Settings(customRuleSets = listOf("https://example.com/my-geoip.srs", "   ", "not-a-url")),
        )
        File(outDir, "custom-rulesets.json").writeText(json)
        assertTrue("custom: url emitted", json.contains("https://example.com/my-geoip.srs"))
        assertTrue("custom: stable tag emitted", json.contains("\"tag\": \"custom-0\""))
        // Only the single real URL survives → custom-0 exists, custom-1 does not.
        assertTrue("custom: junk lines dropped", !json.contains("custom-1"))
    }

    /**
     * «DNS для WireGuard» decides where a WG endpoint's DESTINATION lookups go — and only
     * a WG endpoint's, since the other protocols hand the proxy a hostname and resolve
     * nothing locally. On by default: without it every site a WG user visits is queried
     * from their own address through the direct resolver.
     */
    @Test
    fun wireguardDnsStaysInTheTunnelUnlessAskedOtherwise() {
        val p = profile(
            "VLESS",
            Outbound.Vless(
                server = "de.example.com", serverPort = 443,
                uuid = "b831381d-6324-4d53-ad4f-8cda48b30811",
                network = "tcp",
                tls = TlsSettings(enabled = true, serverName = "de.example.com"),
            ),
        )
        val tunnelled = SingBoxConfig.buildJson(p, Settings())
        // "any" is emitted nowhere else in the config, so its absence IS the assertion.
        assertTrue(
            "default: no blanket outbound rule sending lookups direct",
            !tunnelled.contains("\"any\""),
        )

        val direct = SingBoxConfig.buildJson(p, Settings(wgDnsThroughTunnel = false))
        File(outDir, "wg-dns-direct.json").writeText(direct)
        assertTrue("opt-out: the reference client's outbound rule is emitted", direct.contains("\"any\""))

        // Either way dns-remote must keep its own resolver — that is what stops the
        // loopback, not this knob.
        listOf(tunnelled, direct).forEach {
            assertTrue("dns-remote keeps its resolver", jsonAfterTag(it, "dns-remote", window = 300).contains("dns-direct"))
        }
    }

    @Test
    fun defaultDnsTunAndPinnedSchema() {
        val p = profile(
            "VLESS",
            Outbound.Vless(
                server = "de.example.com", serverPort = 443,
                uuid = "b831381d-6324-4d53-ad4f-8cda48b30811",
                network = "tcp",
                tls = TlsSettings(enabled = true, serverName = "de.example.com"),
            ),
        )
        val json = SingBoxConfig.buildJson(p, Settings())
        // dns-direct is the bootstrap behind route.default_domain_resolver — dialed over
        // the RAW physical network before any tunnel exists, which is exactly why it
        // defaults to the Android platform resolver ("local") rather than a fixed public
        // IP: a restrictive/"whitelist" mobile tariff blocks arbitrary destinations
        // outright, but the carrier's own DNS service is what "local" asks for. This
        // reverted a NekoBox-parity default (a DoH on AliDNS) after a real report showed
        // it broke bootstrap resolution — hence no profile could connect — on exactly
        // such a network. It emits no detour either way, so it is dialed outside the
        // routing engine and cannot loop through a proxy that is not up yet.
        val dnsDirect = jsonAfterTag(json, "dns-direct")
        assertTrue("default: dns-direct is the platform resolver", dnsDirect.contains("\"type\": \"local\""))
        assertTrue("default: dns-direct has no detour", !dnsDirect.contains("\"detour\""))
        val dnsLocal = jsonAfterTag(json, "dns-local")
        assertTrue("default: dns-local is the platform resolver", dnsLocal.contains("\"type\": \"local\""))
        assertTrue("default: Neko IPv4 address", json.contains("\"172.19.0.1/30\""))
        assertTrue("default: Neko MTU", json.contains("\"mtu\": 9000"))
        assertTrue("default: no clash API listener", !json.contains("\"clash_api\""))
        assertTrue("default: Neko endpoint-independent NAT", json.contains("\"endpoint_independent_nat\": true"))
        assertTrue("default: multicast is rejected", json.contains("\"224.0.0.0/3\""))
        assertTrue("default: native logger owns output", !json.contains("\"output\""))
        assertTrue("default: route detects the physical interface", json.contains("\"auto_detect_interface\": true"))
        assertTrue("default: cache file remains enabled", json.contains("\"cache_file\""))
    }

    /**
     * An explicit IP-literal bootstrap override (a user-typed value, or the legacy
     * on-disk "1.1.1.1" before SettingsRepository's local-vs-1.1.1.1 migration runs)
     * must still emit a plain udp server, not the local-resolver default.
     */
    @Test
    fun explicitDirectDnsIpLiteralStaysUdp() {
        val p = profile(
            "VLESS",
            Outbound.Vless(
                server = "de.example.com", serverPort = 443,
                uuid = "b831381d-6324-4d53-ad4f-8cda48b30811",
                network = "tcp",
                tls = TlsSettings(enabled = true, serverName = "de.example.com"),
            ),
        )
        val json = SingBoxConfig.buildJson(p, Settings(directDns = "1.1.1.1"))
        val dnsDirect = jsonAfterTag(json, "dns-direct")
        assertTrue("explicit 1.1.1.1: dns-direct is plain udp", dnsDirect.contains("\"type\": \"udp\""))
        assertTrue("explicit 1.1.1.1: server is 1.1.1.1", dnsDirect.contains("\"server\": \"1.1.1.1\""))
        assertTrue("explicit 1.1.1.1: no detour", !dnsDirect.contains("\"detour\""))
    }

    /**
     * A hostname-based direct resolver must be EMITTED, with the platform resolver
     * (dns-local) wired in to resolve its host — the arrangement the reference client
     * calls address_resolver. It used to be silently swapped for the system resolver,
     * because the bootstrap slot could not resolve its own host and the resulting cycle
     * would have stopped the whole config from starting: the user's chosen DoH was
     * quietly never used.
     */
    /**
     * dns-remote's own hostname must be resolvable, or NOTHING resolves.
     *
     * From a real diagnostics report: the tunnel was up on a WireGuard profile and every
     * single lookup died with
     *   `dns: lookup failed for dns.google: DNS query loopback in transport[dns-remote]`
     * followed by `lookup <site>: lookup dns.google: ...` for every site. dns-remote is a
     * DoH on a NAME, dialed through the proxy; resolving that name re-entered the DNS
     * module, matched no rule, fell through to `final` — which is dns-remote — and the core
     * aborted it as a loop. An explicit resolver on that server is what breaks the cycle.
     */
    @Test
    fun remoteDnsResolvesItsOwnHostnameThroughTheDirectResolver() {
        val p = profile(
            "VLESS",
            Outbound.Vless(
                server = "de.example.com", serverPort = 443,
                uuid = "b831381d-6324-4d53-ad4f-8cda48b30811",
                network = "tcp",
                tls = TlsSettings(enabled = true, serverName = "de.example.com"),
            ),
        )
        val json = SingBoxConfig.buildJson(p, Settings())
        val remote = jsonAfterTag(json, "dns-remote", window = 300)
        assertTrue("remote: still a DoH on a hostname", remote.contains("\"server\": \"dns.google\""))
        assertTrue("remote: routed through the proxy", remote.contains("\"detour\": \"proxy\""))
        assertTrue("remote: its own host resolves via dns-direct", remote.contains("\"server\": \"dns-direct\""))

        // An IP-literal remote needs no resolver, and naming one it never consults would
        // only be noise in the emitted config.
        val literal = SingBoxConfig.buildJson(p, Settings(remoteDns = "https://8.8.8.8/dns-query"))
        val literalRemote = jsonAfterTag(literal, "dns-remote", window = 300)
        assertTrue("literal remote: no resolver needed", !literalRemote.contains("\"domain_resolver\""))
    }

    @Test
    fun hostnameDirectDnsKeepsItsDohAndResolvesViaDnsLocal() {
        val p = profile(
            "VLESS",
            Outbound.Vless(
                server = "de.example.com", serverPort = 443,
                uuid = "b831381d-6324-4d53-ad4f-8cda48b30811",
                network = "tcp",
                tls = TlsSettings(enabled = true, serverName = "de.example.com"),
            ),
        )
        val json = SingBoxConfig.buildJson(p, Settings(directDns = "https://dns.google/dns-query"))
        File(outDir, "direct-dns-hostname.json").writeText(json)
        val dnsDirect = jsonAfterTag(json, "dns-direct", window = 260)
        assertTrue("hostname DoH: kept as https", dnsDirect.contains("\"type\": \"https\""))
        assertTrue("hostname DoH: host preserved", dnsDirect.contains("\"server\": \"dns.google\""))
        assertTrue("hostname DoH: resolved by dns-local", dnsDirect.contains("\"server\": \"dns-local\""))
        assertTrue("hostname DoH: still no detour", !dnsDirect.contains("\"detour\""))
    }

    /**
     * A blank or scheme-only remote DNS must NOT emit {"type":"udp","server":""} —
     * that makes sing-box reject the whole config and the tunnel never starts.
     * dnsServer falls back to the system "local" resolver. Emitted to BOTH dirs so
     * CI sing-box check + the smoke run prove the config still starts.
     */
    @Test
    fun blankRemoteDnsFallsBackToLocal() {
        val p = profile(
            "VLESS",
            Outbound.Vless(
                server = "de.example.com", serverPort = 443,
                uuid = "b831381d-6324-4d53-ad4f-8cda48b30811",
                network = "tcp",
                tls = TlsSettings(enabled = true, serverName = "de.example.com"),
            ),
        )
        listOf("blank" to "", "scheme-only" to "https://", "whitespace" to "   ").forEach { (name, spec) ->
            val json = SingBoxConfig.buildJson(p, Settings(remoteDns = spec))
            File(outDir, "remote-dns-$name.json").writeText(json)
            File(smokeDir, "remote-dns-$name.json").writeText(SingBoxConfig.buildJson(p, Settings(remoteDns = spec), smoke = true))
            assertTrue("$name remoteDns must not emit an empty server", !json.contains("\"server\": \"\""))
        }
    }

    private fun vlessP(name: String, host: String) = profile(
        name,
        Outbound.Vless(
            server = host, serverPort = 443,
            uuid = "b831381d-6324-4d53-ad4f-8cda48b30811",
            network = "tcp", tls = TlsSettings(enabled = true, serverName = host),
        ),
    )

    /** A group with a WireGuard member must lower the TUN MTU to the WG MTU. */
    @Test
    fun mixedAutoWithWgLowersTunMtu() {
        val wg = profile(
            "WG",
            Outbound.WireGuard(
                server = "192.0.2.1", serverPort = 51820,
                privateKey = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAEA=",
                peerPublicKey = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAFA=",
                localAddresses = listOf("10.0.0.2/32"), persistentKeepalive = 25,
            ),
        )
        val json = SingBoxConfig.buildJson(listOf(vlessP("A", "a.example.com"), wg), Settings())
        File(outDir, "mixed-wg-mtu.json").writeText(json)
        assertTrue("mixed-wg: TUN MTU must not keep the Neko default", !json.contains("\"mtu\": 9000"))
        assertTrue("mixed-wg: 1280 MTU present", json.contains("\"mtu\": 1280"))
    }

    /**
     * A small window of [json] right after `"tag": "<tag>"` — pretty-print
     * indentation varies, so this checks nearby content instead of an exact
     * hardcoded multi-line span. `localDnsServer`/`dnsServer` only ever emit a
     * couple of sibling keys per server object, so 120 chars safely covers one
     * object without spilling into the next.
     */
    private fun jsonAfterTag(json: String, tag: String, window: Int = 120): String {
        val i = json.indexOf("\"tag\": \"$tag\"")
        assertTrue("tag \"$tag\" not found in json", i >= 0)
        return json.substring(i, minOf(json.length, i + window))
    }

    /** Duplicate profile ids are deduped so node-<id> tags can't collide (sing-box rejects dup tags). */
    @Test
    fun duplicateProfileIdsDeduped() {
        val a = vlessP("A", "a.example.com")
        val b = profile("B", Outbound.Trojan(server = "b.example.com", serverPort = 443, password = "pw", tls = TlsSettings(enabled = true, serverName = "b.example.com")))
        val json = SingBoxConfig.buildJson(listOf(a, b, a.copy(name = "A-dup")), Settings())
        File(outDir, "dup-id.json").writeText(json)
        // node-A appears exactly 3× (own outbound + urltest + selector), not 6× from the dup.
        assertTrue("dup-id: node-A must appear 3×", Regex("\"node-${a.id}\"").findAll(json).count() == 3)
    }

    /** A trailing-slash DoH URL must not emit path:"/" (sing-box would use "/" instead of /dns-query). */
    @Test
    fun dohTrailingSlashPathDropped() {
        val json = SingBoxConfig.buildJson(vlessP("A", "a.example.com"), Settings(remoteDns = "https://dns.example/"))
        File(outDir, "doh-slash.json").writeText(json)
        assertTrue("doh: bare-slash path must not be emitted", !json.contains("\"path\": \"/\""))
    }

    /** Hysteria2 with an obfs type but no password must omit the obfs block (no-password obfs is rejected). */
    @Test
    fun hysteria2ObfsTypeWithoutPasswordOmitsObfs() {
        val p = profile(
            "HY2",
            Outbound.Hysteria2(
                server = "hy2.example.com", serverPort = 443, password = "hunter2",
                obfsType = "salamander", obfsPassword = "",
                tls = TlsSettings(enabled = true, serverName = "hy2.example.com"),
            ),
        )
        val json = SingBoxConfig.buildJson(p, Settings())
        File(outDir, "hy2-obfs-nopass.json").writeText(json)
        assertTrue("hy2: no obfs block without a password", !json.contains("\"obfs\""))
    }

    /** REALITY must not be emitted on a QUIC outbound (the core rejects reality-over-QUIC). */
    @Test
    fun realityNotEmittedOnQuic() {
        val p = profile(
            "HY2-reality",
            Outbound.Hysteria2(
                server = "h.example.com", serverPort = 443, password = "x",
                tls = TlsSettings(
                    enabled = true,
                    reality = RealitySettings(publicKey = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8", shortId = "0123abcd"),
                ),
            ),
        )
        val json = SingBoxConfig.buildJson(p, Settings())
        File(outDir, "reality-quic.json").writeText(json)
        assertTrue("reality: must not appear on a QUIC outbound", !json.contains("\"reality\""))
    }

    // ------------------------------------------------------- URL Test config ---

    /**
     * "URL Test" (see UrlTestPinger) needs a throwaway, headless config for exactly
     * one outbound — no TUN, no other inbound. It gets its own emit here (not the
     * shared [emit] helper, which asserts a "tun" inbound is present) but the SAME
     * `sing-box check` schema validation AND `sing-box run` startup smoke test
     * every other emitted config gets: written into both [outDir] (schema check)
     * and [smokeDir] (a real `sing-box run` for a few seconds, checked for a FATAL
     * error or panic) — see the CI workflow steps that walk both directories.
     */
    private fun emitUrlTest(name: String, o: Outbound, settings: Settings = Settings()) {
        val json = SingBoxConfig.buildUrlTestJson(o, settings)
        File(outDir, "urltest-$name.json").writeText(json)
        File(smokeDir, "urltest-$name.json").writeText(json)
        assertTrue("$name: must have no inbounds (headless)", !json.contains("\"inbounds\""))
        assertTrue("$name: missing proxy outbound", json.contains("\"tag\": \"proxy\""))
        assertTrue("$name: route.final must point at proxy", json.contains("\"final\": \"proxy\""))
        assertTrue("$name: must not reference dns-remote (bootstrap-only DNS)", !json.contains("dns-remote"))
        // The probe runs while the tunnel is up, and this flag is what makes sing-box
        // append the protect() Control to its sockets at all. Without it the probe dials
        // the server from inside the tunnel it is measuring; that loop killed all traffic
        // on every protocol once URL Test became the default ping.
        assertTrue(
            "$name: route.auto_detect_interface must be true, else the probe's sockets " +
                "are never protected and loop back through the active tunnel",
            json.contains("\"auto_detect_interface\": true"),
        )
    }

    @Test
    fun urlTestVlessReality() = emitUrlTest(
        "vless-reality",
        Outbound.Vless(
            server = "reality.example.com",
            serverPort = 443,
            uuid = "b831381d-6324-4d53-ad4f-8cda48b30811",
            flow = "xtls-rprx-vision",
            network = "tcp",
            tls = TlsSettings(
                enabled = true,
                serverName = "www.microsoft.com",
                utlsFingerprint = "chrome",
                reality = RealitySettings(
                    publicKey = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8",
                    shortId = "0123abcd",
                ),
            ),
        ),
    )

    @Test
    fun urlTestShadowsocks() = emitUrlTest(
        "shadowsocks",
        Outbound.Shadowsocks(server = "ss.example.com", serverPort = 8388, method = "aes-256-gcm", password = "x"),
    )

    @Test
    fun urlTestRejectsWireGuard() {
        val wg = Outbound.WireGuard(
            server = "wg.example.com", serverPort = 51820,
            privateKey = "priv", peerPublicKey = "pub", localAddresses = listOf("10.0.0.2/32"),
        )
        assertThrows(IllegalArgumentException::class.java) {
            SingBoxConfig.buildUrlTestJson(wg, Settings())
        }
    }

    @Test
    fun urlTestHonoursACustomDirectDns() {
        val json = SingBoxConfig.buildUrlTestJson(
            Outbound.Vless(server = "v.example.com", serverPort = 443, uuid = "b831381d-6324-4d53-ad4f-8cda48b30811"),
            Settings(directDns = "1.1.1.1"),
        )
        assertTrue("custom direct DNS must be honoured", json.contains("1.1.1.1"))
    }
}

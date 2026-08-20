package com.th3web.lean.data.parse

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import com.th3web.lean.data.model.Outbound

/**
 * The config-FILE importers, and the mieru share links.
 *
 * Robolectric because the parsers use android.net.Uri and android.util.Base64, which are
 * "not mocked" stubs under plain JUnit. SDK 34 for the same reason as the sibling test.
 *
 * The fixtures are upstream's OWN documented examples wherever one exists, so these
 * assert against the formats as published rather than against my reading of them.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ProxyConfigFilesRobolectricTest {

    // ------------------------------------------------------------ mieru links ----

    /**
     * The `mierus://` example from mieru's own docs/client-install.md.
     *
     * Note what this pins: the port is NOT in the authority, `port` and `protocol` repeat
     * and pair positionally, and one link therefore yields FOUR endpoints. An earlier
     * implementation here invented a `mieru://user:pass@host:port` shape that no tool
     * emits, which parsed this link into a single server pointing at the wrong place.
     */
    @Test
    fun `mierus link yields one profile per port binding`() {
        val link = "mierus://baozi:manlianpenfen@1.2.3.4?handshake-mode=HANDSHAKE_NO_WAIT" +
            "&mtu=1400&multiplexing=MULTIPLEXING_HIGH" +
            "&port=6666&port=9998-9999&port=6489&port=4896" +
            "&profile=default&protocol=TCP&protocol=TCP&protocol=UDP&protocol=UDP"

        val profiles = ShareLinks.parseAll(link)

        assertEquals(4, profiles.size)
        val outbounds = profiles.map { it.outbound as Outbound.Mieru }
        assertEquals(listOf(6666, 9998, 6489, 4896), outbounds.map { it.serverPort })
        assertEquals(listOf("TCP", "TCP", "UDP", "UDP"), outbounds.map { it.transport })
        outbounds.forEach {
            assertEquals("1.2.3.4", it.server)
            assertEquals("baozi", it.username)
            assertEquals("manlianpenfen", it.password)
            assertEquals(1400, it.mtu)
        }
    }

    /** A range's FIRST port is the one dialled, matching upstream's own Addr(). */
    @Test
    fun `mierus port range takes the first port`() {
        val profiles = ShareLinks.parseAll("mierus://u:p@example.com?profile=x&port=2012-2022&protocol=TCP")
        assertEquals(1, profiles.size)
        assertEquals(2012, (profiles[0].outbound as Outbound.Mieru).serverPort)
    }

    /**
     * The `mieru://` example from the same docs — base64 of a protobuf ClientConfig.
     * Values here are the ones the docs say that link carries.
     */
    @Test
    fun `mieru protobuf link decodes to its endpoints`() {
        val link = "mieru://CpsBCgdkZWZhdWx0ElgKBWJhb3ppEg1tYW5saWFucGVuZmVuGkA0MGFiYWM0MGY1" +
            "OWRhNTVkYWQ2YTk5ODMxYTUxMTY1MjJmYmM4MGUzODViYjFhYjE0ZGM1MmRiMzY4ZjczOGE0Gi8SCWxvY2Fs" +
            "aG9zdBoFCIo0EAIaDRACGgk5OTk5LTk5OTkaBQjZMhABGgUIoCYQASD4CioCCAQSB2RlZmF1bHQYnUYguAgw" +
            "BTgA"

        val profiles = ShareLinks.parseAll(link)

        assertEquals(4, profiles.size)
        val outbounds = profiles.map { it.outbound as Outbound.Mieru }
        outbounds.forEach {
            assertEquals("localhost", it.server)
            assertEquals("baozi", it.username)
            assertEquals("manlianpenfen", it.password)
        }
        assertEquals(listOf(6666, 9999, 6489, 4896), outbounds.map { it.serverPort })
        assertEquals(1400, outbounds[0].mtu)
        // Pins the enum, which is UDP=1, TCP=2 in upstream's base.proto — the reverse of
        // the conventional ordering. Assuming TCP=1 inverts the transport of every
        // endpoint and produces servers that connect to nothing, with no parse error to
        // show for it. With the mapping right, this protobuf link and the mierus:// one
        // above describe the SAME four endpoints, which is the cross-check.
        assertEquals(listOf("TCP", "TCP", "UDP", "UDP"), outbounds.map { it.transport })
    }

    /** Garbage after the scheme must yield nothing, not a half-built server. */
    @Test
    fun `malformed mieru protobuf link yields nothing`() {
        assertTrue(ShareLinks.parseAll("mieru://not-base64-at-all!!").isEmpty())
    }

    // ------------------------------------------------------------- mieru JSON ----

    @Test
    fun `mieru client json fans out profiles servers and port bindings`() {
        val text = """
            {
              "profiles": [{
                "profileName": "default",
                "user": { "name": "alice", "password": "secret" },
                "servers": [{
                  "ipAddress": "12.34.56.78",
                  "domainName": "",
                  "portBindings": [
                    { "portRange": "2012-2022", "protocol": "TCP" },
                    { "port": 2027, "protocol": "UDP" }
                  ]
                }],
                "mtu": 1380
              }],
              "activeProfile": "default",
              "socks5Port": 1080
            }
        """.trimIndent()

        val profiles = ProxyConfigFiles.parse(text)

        assertEquals(2, profiles.size)
        val outbounds = profiles.map { it.outbound as Outbound.Mieru }
        assertEquals(listOf(2012, 2027), outbounds.map { it.serverPort })
        assertEquals(listOf("TCP", "UDP"), outbounds.map { it.transport })
        assertEquals("alice", outbounds[0].username)
        assertEquals(1380, outbounds[0].mtu)
    }

    /** domainName wins over ipAddress — upstream ignores the IP entirely when it is set. */
    @Test
    fun `mieru json prefers the domain name over the ip`() {
        val text = """
            {"profiles":[{"profileName":"p","user":{"name":"u","password":"p"},
             "servers":[{"ipAddress":"1.2.3.4","domainName":"real.example.com",
             "portBindings":[{"port":443,"protocol":"TCP"}]}]}],"activeProfile":"p"}
        """.trimIndent()

        val out = ProxyConfigFiles.parse(text).single().outbound as Outbound.Mieru

        assertEquals("real.example.com", out.server)
    }

    // ------------------------------------------------------------- naive JSON ----

    /**
     * The pinned-IP shape: naive keeps the SNI hostname in `proxy` and maps it to the
     * real address with host-resolver-rules. Losing that split would dial the SNI name
     * directly and drop the pinning.
     */
    @Test
    fun `naive json splits the pinned ip from the sni host`() {
        val text = """
            {
              "listen": "socks://127.0.0.1:1080",
              "proxy": "https://user:pa%40ss@sni.example.com:443",
              "host-resolver-rules": "MAP sni.example.com 1.2.3.4",
              "extra-headers": "User-Agent: Mozilla/5.0",
              "insecure-concurrency": 2
            }
        """.trimIndent()

        val out = ProxyConfigFiles.parse(text).single().outbound as Outbound.Naive

        assertEquals("1.2.3.4", out.server)
        assertEquals("sni.example.com", out.sni)
        assertEquals(443, out.serverPort)
        assertEquals("https", out.proto)
        assertEquals("user", out.username)
        assertEquals("pa@ss", out.password)
        assertEquals(2, out.insecureConcurrency)
    }

    /** Without a resolver rule the proxy host IS the server, and no SNI override applies. */
    @Test
    fun `naive json without resolver rules uses the proxy host directly`() {
        val text = """{"listen":"socks://127.0.0.1:1080","proxy":"quic://u:p@example.com:8443"}"""

        val out = ProxyConfigFiles.parse(text).single().outbound as Outbound.Naive

        assertEquals("example.com", out.server)
        assertEquals("", out.sni)
        assertEquals("quic", out.proto)
        assertEquals(8443, out.serverPort)
    }

    // -------------------------------------------------------------- mihomo YAML ----

    /** The example from mieru's docs, which is also the mihomo documented shape. */
    @Test
    fun `mihomo yaml mieru proxies import`() {
        val text = """
            proxies:
              - name: server1
                type: mieru
                server: 12.34.56.78
                port-range: 2012-2022
                transport: TCP
                udp: true
                username: ducaiguozei
                password: xijinping
                multiplexing: MULTIPLEXING_HIGH
              - name: server2
                type: mieru
                server: 12.34.56.78
                port: 2027
                transport: UDP
                username: ducaiguozei
                password: xijinping
        """.trimIndent()

        val profiles = ProxyConfigFiles.parse(text)

        assertEquals(2, profiles.size)
        assertEquals(listOf("server1", "server2"), profiles.map { it.name })
        val outbounds = profiles.map { it.outbound as Outbound.Mieru }
        assertEquals(listOf(2012, 2027), outbounds.map { it.serverPort })
        assertEquals(listOf("TCP", "UDP"), outbounds.map { it.transport })
        assertEquals("ducaiguozei", outbounds[0].username)
    }

    /** Inline flow maps are as common in Clash configs as block entries. */
    @Test
    fun `mihomo yaml flow style entries import`() {
        val text = """
            proxies:
              - {name: flow, type: mieru, server: 1.2.3.4, port: 9000, transport: TCP, username: u, password: p}
            rules:
              - MATCH,DIRECT
        """.trimIndent()

        val profiles = ProxyConfigFiles.parse(text)

        assertEquals(1, profiles.size)
        val out = profiles.single().outbound as Outbound.Mieru
        assertEquals("1.2.3.4", out.server)
        assertEquals(9000, out.serverPort)
        assertEquals("flow", profiles.single().name)
    }

    /**
     * A mixed file yields every entry, each as its own protocol.
     *
     * This used to assert the opposite — non-mieru entries were dropped — because the
     * Clash branch only understood `type: mieru`. That is what made an ordinary panel
     * .yaml import nothing at all. What still matters, and is asserted here, is that a
     * neighbouring entry is never mangled INTO a mieru server.
     */
    @Test
    fun `mihomo yaml keeps every proxy type as itself`() {
        val text = """
            proxies:
              - name: ss
                type: ss
                server: 1.2.3.4
                port: 8388
                cipher: aes-256-gcm
                password: p
              - name: m
                type: mieru
                server: 5.6.7.8
                port: 9000
                transport: TCP
                username: u
                password: p
        """.trimIndent()

        val profiles = ProxyConfigFiles.parse(text)

        assertEquals(2, profiles.size)
        val ss = profiles[0].outbound as Outbound.Shadowsocks
        assertEquals("1.2.3.4", ss.server)
        assertEquals("aes-256-gcm", ss.method)
        val mieru = profiles[1].outbound as Outbound.Mieru
        assertEquals("5.6.7.8", mieru.server)
        assertEquals(9000, mieru.serverPort)
    }

    /**
     * The shape a panel actually hands out for these two protocols: a sing-box-style
     * config keyed on `type`. Verbatim structure from a real «stealth» config, which
     * imported NOTHING before — XrayConfig keys on `protocol`, the Xray spelling, so this
     * file matched no importer at all and failed silently.
     */
    @Test
    fun `sing-box style config imports its mieru and naive outbounds`() {
        val text = """
            {
              "inbounds": [
                { "type": "mixed", "tag": "mixed-in", "listen": "127.0.0.1", "listen_port": 2080 }
              ],
              "outbounds": [
                { "type": "selector", "tag": "LeanVPN", "outbounds": ["mieru-de", "naive-de"], "default": "mieru-de" },
                { "type": "mieru", "tag": "mieru-de", "server": "203.0.113.10", "server_port": 2027,
                  "username": "u1", "password": "p1", "transport": "TCP" },
                { "type": "naive", "tag": "naive-de", "server": "sub.example.com", "server_port": 8443,
                  "username": "u2", "password": "p2",
                  "tls": { "enabled": true, "server_name": "sub.example.com" } },
                { "type": "direct", "tag": "direct" }
              ],
              "route": { "final": "LeanVPN" }
            }
        """.trimIndent()

        val profiles = ProxyConfigFiles.parse(text)

        // The selector and the direct outbound are routing plumbing, not servers.
        assertEquals(2, profiles.size)
        assertEquals(listOf("mieru-de", "naive-de"), profiles.map { it.name })

        val mieru = profiles[0].outbound as Outbound.Mieru
        assertEquals("203.0.113.10", mieru.server)
        assertEquals(2027, mieru.serverPort)
        assertEquals("TCP", mieru.transport)
        assertEquals("u1", mieru.username)
        assertEquals("p1", mieru.password)

        val naive = profiles[1].outbound as Outbound.Naive
        assertEquals("sub.example.com", naive.server)
        assertEquals(8443, naive.serverPort)
        assertEquals("https", naive.proto)
        assertEquals("u2", naive.username)
        // server_name equals the address here, so there is nothing to override: an SNI
        // identical to the host would only add a pointless resolver rule.
        assertEquals("", naive.sni)
    }

    /** A server_name that DIFFERS from the address is a real SNI override and is kept. */
    @Test
    fun `sing-box naive keeps an sni that differs from the address`() {
        val text = """
            {"outbounds":[{"type":"naive","tag":"n","server":"1.2.3.4","server_port":443,
             "username":"u","password":"p","tls":{"server_name":"front.example.com"}}]}
        """.trimIndent()

        val out = ProxyConfigFiles.parse(text).single().outbound as Outbound.Naive

        assertEquals("1.2.3.4", out.server)
        assertEquals("front.example.com", out.sni)
    }

    /**
     * An ordinary panel .yaml — the case that used to import NOTHING.
     *
     * The Clash branch only ever looked for `type: mieru`, so every real-world export,
     * which is vless/vmess/trojan/ss, produced an empty list and the UI reported «Не
     * удалось разобрать файл». Reported on 4PDA as "a dozen .yaml tried, none accepted,
     * .conf works fine".
     */
    @Test
    fun `clash yaml imports the ordinary protocols`() {
        val text = """
            proxies:
              - name: "DE Reality"
                type: vless
                server: de.example.com
                port: 443
                uuid: b831381d-6324-4d53-ad4f-8cda48b30811
                flow: xtls-rprx-vision
                tls: true
                servername: www.microsoft.com
                client-fingerprint: chrome
                reality-opts:
                  public-key: xh4pOJ0DHCE8mVJhSUxCUXjBTLZ3TbQOxTFXSyGoLg
                  short-id: 6ba85179e30d4fc2
              - name: "WS node"
                type: trojan
                server: tr.example.com
                port: 8443
                password: sekret
                network: ws
                sni: cdn.example.com
                skip-cert-verify: true
                ws-opts:
                  path: /ws
                  headers:
                    Host: cdn.example.com
              - name: "SS node"
                type: ss
                server: ss.example.com
                port: 8388
                cipher: aes-256-gcm
                password: ssword
            rules:
              - MATCH,DIRECT
        """.trimIndent()

        val out = ProxyConfigFiles.parse(text)
        assertEquals(3, out.size)

        val vless = out[0].outbound as Outbound.Vless
        assertEquals("DE Reality", out[0].name)
        assertEquals("de.example.com", vless.server)
        assertEquals(443, vless.serverPort)
        assertEquals("xtls-rprx-vision", vless.flow)
        assertEquals("www.microsoft.com", vless.tls?.serverName)
        assertEquals("chrome", vless.tls?.utlsFingerprint)
        // reality-opts flattens into the entry, so its leaves are read by their own names.
        assertEquals("xh4pOJ0DHCE8mVJhSUxCUXjBTLZ3TbQOxTFXSyGoLg", vless.tls?.reality?.publicKey)
        assertEquals("6ba85179e30d4fc2", vless.tls?.reality?.shortId)

        val trojan = out[1].outbound as Outbound.Trojan
        assertEquals("sekret", trojan.password)
        assertEquals("ws", trojan.network)
        assertEquals("/ws", trojan.transport?.path)
        assertEquals("cdn.example.com", trojan.transport?.host)
        assertEquals(true, trojan.tls?.insecure)

        val ss = out[2].outbound as Outbound.Shadowsocks
        assertEquals("aes-256-gcm", ss.method)
        assertEquals("ssword", ss.password)
        // `rules:` ends the proxies block — it must not become a fourth server.
    }

    /** An unrelated JSON must not be mistaken for one of these formats. */
    @Test
    fun `unrelated json yields nothing`() {
        assertTrue(ProxyConfigFiles.parse("""{"outbounds":[{"type":"vless"}]}""").isEmpty())
        assertTrue(ProxyConfigFiles.parse("""{"hello":"world"}""").isEmpty())
    }
}

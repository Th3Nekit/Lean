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
 * Proof that android.net.Uri (and android.util.Base64) work in pure-JVM unit
 * tests under Robolectric, which unblocks testing the ShareLinks parsers.
 *
 * Plain JUnit can't run these: android.net.Uri/android.util.Base64 are the
 * "not mocked" android.jar stubs and throw. RobolectricTestRunner swaps in real
 * Android implementations. SDK 34 is pinned via @Config because it's the most
 * thoroughly supported Robolectric image (SDK 35 has a known font-load issue,
 * #9732, and lacks native graphics/sqlite on Windows) — the parser touches
 * neither, so 34 behaves identically and avoids surprises.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ShareLinksRobolectricTest {

    @Test
    fun `parses a vless share link via real android Uri`() {
        val link = "vless://b831381d-6324-4d53-ad4f-8cda48b30811@1.2.3.4:443?security=tls&type=tcp#Test"

        val profile = ShareLinks.parse(link)

        assertNotNull("parse returned null — android.net.Uri likely not active", profile)
        assertEquals("Test", profile!!.name)
        val out = profile.outbound
        assertTrue("expected a VLESS outbound, got ${out::class.simpleName}", out is Outbound.Vless)
        out as Outbound.Vless
        assertEquals("1.2.3.4", out.server)
        assertEquals(443, out.serverPort)
        assertEquals("b831381d-6324-4d53-ad4f-8cda48b30811", out.uuid)
        assertEquals("tcp", out.network)
        assertNotNull("security=tls should produce TlsSettings", out.tls)
    }

    @Test
    fun `parses a naive share link and keeps the transport from the scheme`() {
        val link = "naive+https://user:p%40ss@example.com:443?sni=www.example.com#Naive%20RU"

        val profile = ShareLinks.parse(link)

        assertNotNull(profile)
        val out = profile!!.outbound
        assertTrue("expected a Naive outbound, got ${out::class.simpleName}", out is Outbound.Naive)
        out as Outbound.Naive
        assertEquals("example.com", out.server)
        assertEquals(443, out.serverPort)
        assertEquals("https", out.proto)
        assertEquals("user", out.username)
        // The password is percent-encoded in the link and must come back decoded, or the
        // server rejects every request.
        assertEquals("p@ss", out.password)
        assertEquals("www.example.com", out.sni)
    }

    @Test
    fun `naive quic scheme survives a round trip`() {
        val profile = ShareLinks.parse("naive+quic://u:p@example.com:8443#Q")
        assertNotNull(profile)
        assertEquals("quic", (profile!!.outbound as Outbound.Naive).proto)

        val reparsed = ShareLinks.parse(ShareLinks.toShareLink(profile)!!)
        assertNotNull(reparsed)
        val out = reparsed!!.outbound as Outbound.Naive
        assertEquals("quic", out.proto)
        assertEquals("example.com", out.server)
        assertEquals(8443, out.serverPort)
        assertEquals("u", out.username)
        assertEquals("p", out.password)
    }

    /**
     * The SIMPLE form, `mierus://`. Its port lives in a query param, not the authority —
     * see ProxyConfigFilesRobolectricTest for the multi-endpoint and protobuf cases.
     */
    @Test
    fun `parses a mierus share link, defaulting the transport to TCP`() {
        val profile = ShareLinks.parse("mierus://alice:secret@10.0.0.1?profile=default&port=9000#Mieru")

        assertNotNull(profile)
        val out = profile!!.outbound
        assertTrue("expected a Mieru outbound, got ${out::class.simpleName}", out is Outbound.Mieru)
        out as Outbound.Mieru
        assertEquals("10.0.0.1", out.server)
        assertEquals(9000, out.serverPort)
        assertEquals("alice", out.username)
        assertEquals("secret", out.password)
        // Upstream looks the protocol up with no miss-check, so an absent or misspelled
        // one silently becomes UNKNOWN there. We land on TCP, which actually works.
        assertEquals("TCP", out.transport)
    }

    @Test
    fun `mieru udp transport survives a round trip`() {
        val profile = ShareLinks.parse("mierus://a:b@10.0.0.1?profile=default&port=9000&protocol=UDP&mtu=1280#M")
        assertNotNull(profile)

        val exported = ShareLinks.toShareLink(profile!!)!!
        // Exported as the readable form other clients can import; the protobuf form is
        // readable-only by design.
        assertTrue("expected a mierus:// link, got $exported", exported.startsWith("mierus://"))

        val reparsed = ShareLinks.parse(exported)
        assertNotNull(reparsed)
        val out = reparsed!!.outbound as Outbound.Mieru
        assertEquals("UDP", out.transport)
        assertEquals(1280, out.mtu)
        assertEquals(9000, out.serverPort)
    }

    /**
     * The community olcRTC notation, taken verbatim from upstream docs/uri.md.
     *
     * Positional separators, not a URI: the room is a whole `https://host/room` and the
     * comment is free text, so this is cut by hand. The example is the project's own.
     */
    @Test
    fun `olcrtc link parses provider transport room and key`() {
        val link = "olcrtc://wbstream?datachannel@room-01#" +
            "d823fa01cb3e0609b67322f7cf984c4ee2e4ce2e294936fc24ef38c9e59f4799" +
            "${'$'}RU / olc free sub / IPv6"

        val profile = ShareLinks.parse(link)
        assertNotNull(profile)
        val out = profile!!.outbound as Outbound.Olcrtc
        assertEquals("wbstream", out.provider)
        assertEquals("datachannel", out.transport)
        assertEquals("room-01", out.roomId)
        assertEquals(
            "d823fa01cb3e0609b67322f7cf984c4ee2e4ce2e294936fc24ef38c9e59f4799",
            out.key,
        )
        assertTrue(out.options.isEmpty())
        // The trailing comment is the label, which is what a bot puts the location in.
        assertEquals("RU / olc free sub / IPv6", profile.name)
    }

    /** The `<k=v&k=v>` payload rides right after the transport name. */
    @Test
    fun `olcrtc link carries transport tuning and round-trips`() {
        val link = "olcrtc://jitsi?vp8channel<vp8-fps=60&vp8-batch=64>@" +
            "https://meet.example.org/lean-room#" +
            "d823fa01cb3e0609b67322f7cf984c4ee2e4ce2e294936fc24ef38c9e59f4799" +
            "${'$'}Frankfurt"

        val profile = ShareLinks.parse(link)
        assertNotNull(profile)
        val out = profile!!.outbound as Outbound.Olcrtc
        assertEquals("vp8channel", out.transport)
        assertEquals(mapOf("vp8-fps" to "60", "vp8-batch" to "64"), out.options)
        assertEquals("https://meet.example.org/lean-room", out.roomId)
        // No server is dialled, so the row shows the room's own host.
        assertEquals("meet.example.org", out.server)

        val reparsed = ShareLinks.parse(ShareLinks.toShareLink(profile)!!)
        assertNotNull(reparsed)
        val again = reparsed!!.outbound as Outbound.Olcrtc
        assertEquals(out.roomId, again.roomId)
        assertEquals(out.key, again.key)
        assertEquals(out.options, again.options)
        assertEquals("Frankfurt", reparsed.name)
    }

    /** A link missing the key describes nothing runnable and must not become a profile. */
    @Test
    fun `olcrtc link without a key is rejected`() {
        assertNull(ShareLinks.parse("olcrtc://jitsi?datachannel@room-01"))
        assertNull(ShareLinks.parse("olcrtc://?datachannel@room-01#abc"))
    }

    /**
     * The exact shape a Remnawave panel emits for Hysteria2: a slash between the port and
     * the query, which no other protocol's link carries.
     *
     * Reported from the field as the source of "ghost" servers — entries with no sni, no
     * alpn and a name equal to the address, which is precisely what a parser that stops
     * at `:8454/` produces. This pins the whole line: the port, every query parameter and
     * the name, so the answer is a test result rather than an argument.
     */
    @Test
    fun `hysteria2 link survives the slash a panel puts between port and query`() {
        val link = "hysteria2://7449d1d9-1111-2222-3333-444455556666@zh.example.com:8454/" +
            "?sni=zh.example.com&alpn=h3&insecure=1" +
            "#LTE PRO Hysteria2"

        val profile = ShareLinks.parse(link)
        assertNotNull(profile)
        val out = profile!!.outbound as Outbound.Hysteria2
        assertEquals("zh.example.com", out.server)
        // The slash must neither swallow the port nor hide the query behind it.
        assertEquals(8454, out.serverPort)
        assertEquals("7449d1d9-1111-2222-3333-444455556666", out.password)
        assertEquals("zh.example.com", out.tls?.serverName)
        assertEquals(listOf("h3"), out.tls?.alpn)
        assertEquals(true, out.tls?.insecure)
        // A name equal to the address is the tell-tale of the broken parse.
        assertEquals("LTE PRO Hysteria2", profile.name)
    }

    /**
     * The line EXACTLY as the live panel serves it, byte for byte.
     *
     * Taken from a real subscription body fetched with this client's own User-Agent, so
     * it carries both quirks at once: the slash after the port and a percent-encoded
     * fragment holding an emoji plus a regional-indicator flag pair. The earlier test
     * used a plain fragment, which is a materially easier case — this is the one the
     * field report is actually about.
     */
    @Test
    fun `hysteria2 line from the live panel keeps its name and sni`() {
        val link = "hysteria2://7449d1d9-1111-2222-3333-444455556666@zh.example.com:8454/" +
            "?sni=zh.example.com" +
            "#%F0%9F%93%B1%20%F0%9F%87%B7%F0%9F%87%BA%20LTE%20PRO%20Hysteria2"

        val profile = ShareLinks.parse(link)
        assertNotNull(profile)
        val out = profile!!.outbound as Outbound.Hysteria2
        assertEquals("zh.example.com", out.server)
        assertEquals(8454, out.serverPort)
        assertEquals("7449d1d9-1111-2222-3333-444455556666", out.password)
        assertEquals("zh.example.com", out.tls?.serverName)
        // The whole point of the report: the name must NOT collapse to the address.
        assertTrue("name fell back to the host: '${profile.name}'", profile.name != out.server)
        assertTrue(profile.name.endsWith("LTE PRO Hysteria2"))
    }

    /**
     * Two lines that differ ONLY by port are two servers, not one.
     *
     * The live subscription carries exactly this: the same credentials and host on 8454
     * (RU) and 8455 (DE). Anything keying a server on host alone would silently drop one.
     */
    @Test
    fun `hysteria2 lines differing only by port stay two servers`() {
        val body = listOf(
            "hysteria2://7449d1d9-1111-2222-3333-444455556666@zh.example.com:8454/" +
                "?sni=zh.example.com#%F0%9F%87%B7%F0%9F%87%BA%20LTE%20PRO%20Hysteria2",
            "hysteria2://7449d1d9-1111-2222-3333-444455556666@zh.example.com:8455/" +
                "?sni=zh.example.com#%F0%9F%87%A9%F0%9F%87%AA%20LTE%20PRO%20Hysteria2",
        ).joinToString("\n")

        val profiles = ShareLinks.parseMany(body)
        assertEquals(2, profiles.size)
        assertEquals(listOf(8454, 8455), profiles.map { (it.outbound as Outbound.Hysteria2).serverPort })
        // Both keep their own name.
        assertTrue(profiles.none { it.name == it.outbound.server })
    }

    /** The same line with a trailing slash and NO query at all must still keep its port. */
    @Test
    fun `hysteria2 link keeps its port with a bare trailing slash`() {
        val profile = ShareLinks.parse("hysteria2://secret@example.org:8454/#Node")
        assertNotNull(profile)
        val out = profile!!.outbound as Outbound.Hysteria2
        assertEquals(8454, out.serverPort)
        assertEquals("Node", profile.name)
    }
}

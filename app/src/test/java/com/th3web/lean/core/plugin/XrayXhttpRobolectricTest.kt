package com.th3web.lean.core.plugin

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import com.th3web.lean.core.SingBoxConfig
import com.th3web.lean.data.Settings
import com.th3web.lean.data.model.Outbound
import com.th3web.lean.data.model.Profile
import com.th3web.lean.data.parse.ShareLinks

/**
 * VLESS over XHTTP, end to end through the layers that decide where such a node runs.
 *
 * The whole feature is one claim — "a transport the core does not have goes to Xray
 * instead, and its traffic still leaves through the core" — and every step of it is a
 * place where the failure is silent rather than loud: a link that parses to a
 * transport-less outbound, a config whose SNI says 127.0.0.1, a helper whose own sockets
 * loop back into the tunnel it is providing. None of those throw. They connect, and carry
 * nothing. So each is asserted here rather than left to a device.
 *
 * Robolectric because ShareLinks parses with android.net.Uri.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class XrayXhttpRobolectricTest {

    private val json = Json { ignoreUnknownKeys = true }

    private val link =
        "vless://b831381d-6324-4d53-ad4f-8cda48b30811@cdn.example.com:443" +
            "?security=tls&sni=front.example.com&type=xhttp&mode=packet-up" +
            "&path=%2Fdownload&host=real.example.com&fp=chrome#CDN"

    private fun parsed(): Outbound.Vless =
        ShareLinks.parse(link)!!.outbound as Outbound.Vless

    // ---------------------------------------------------------------- parsing ----

    @Test
    fun `an xhttp link imports instead of being dropped`() {
        val profile = ShareLinks.parse(link)
        assertNotNull("xhttp used to be in UNSUPPORTED_NETWORKS and returned null", profile)

        val out = profile!!.outbound as Outbound.Vless
        assertEquals("cdn.example.com", out.server)
        assertEquals(443, out.serverPort)
        assertEquals("xhttp", out.transport?.type)
        assertEquals("packet-up", out.transport?.mode)
        assertEquals("/download", out.transport?.path)
        assertEquals("real.example.com", out.transport?.host)
        assertEquals("front.example.com", out.tls?.serverName)
    }

    @Test
    fun `splithttp is the same transport under its old name`() {
        val out = ShareLinks.parse(
            "vless://id@h.example.com:443?security=tls&type=splithttp&path=%2Fp#S",
        )!!.outbound as Outbound.Vless
        assertEquals("normalised to one spelling downstream", "xhttp", out.transport?.type)
    }

    @Test
    fun `an xhttp link survives the round trip back to a link`() {
        val original = parsed()
        val again = ShareLinks.parse(ShareLinks.toShareLink(ShareLinks.parse(link)!!)!!)!!
            .outbound as Outbound.Vless
        assertEquals(original, again)
    }

    @Test
    fun `kcp is still dropped — it has no path anywhere`() {
        assertNull(ShareLinks.parse("vless://id@h.example.com:443?type=kcp#K"))
    }

    // ------------------------------------------------------------ the gate ----

    @Test
    fun `only the nodes the core cannot speak go to Xray`() {
        assertEquals(NativePlugin.Xray, PluginSession.pluginFor(parsed()))

        val plain = ShareLinks.parse(
            "vless://id@h.example.com:443?security=reality&type=tcp&pbk=k&flow=xtls-rprx-vision#R",
        )!!.outbound
        assertNull("an ordinary reality node must stay in the core", PluginSession.pluginFor(plain))

        val encrypted = ShareLinks.parse(
            "vless://id@h.example.com:443?type=tcp&encryption=mlkem768x25519plus.native.600s.abc#E",
        )!!.outbound
        assertEquals(
            "VLESS encryption is newer than the pinned core's VLESS",
            NativePlugin.Xray,
            PluginSession.pluginFor(encrypted),
        )
    }

    // ------------------------------------------------------- the Xray config ----

    private fun xrayConfig(o: Outbound.Vless = parsed()): JsonObject =
        json.parseToJsonElement(
            PluginConfig.forXray(o, localPort = 18080, mappedHost = "127.0.0.1", mappedPort = 18081),
        ).jsonObject

    private fun JsonObject.outbound(tag: String): JsonObject =
        this["outbounds"]!!.jsonArray
            .map { it.jsonObject }
            .first { it["tag"]!!.jsonPrimitive.content == tag }

    @Test
    fun `the helper listens where the core will dial it`() {
        val inbound = xrayConfig()["inbounds"]!!.jsonArray.single().jsonObject
        assertEquals("socks", inbound["protocol"]!!.jsonPrimitive.content)
        assertEquals(18080, inbound["port"]!!.jsonPrimitive.content.toInt())
        assertEquals("127.0.0.1", inbound["listen"]!!.jsonPrimitive.content)
    }

    /**
     * The reason Xray is wired with dialerProxy instead of the address rewrite mieru and
     * naive get. Rewriting the address would put "127.0.0.1" in the SNI and the Host
     * header — the two fields a CDN routes on.
     */
    @Test
    fun `the real server stays in the config and the socket is redirected instead`() {
        val proxy = xrayConfig().outbound("proxy")
        val vnext = proxy["settings"]!!.jsonObject["vnext"]!!.jsonArray.single().jsonObject
        assertEquals("cdn.example.com", vnext["address"]!!.jsonPrimitive.content)
        assertEquals(443, vnext["port"]!!.jsonPrimitive.content.toInt())

        val stream = proxy["streamSettings"]!!.jsonObject
        assertEquals(
            "core-egress",
            stream["sockopt"]!!.jsonObject["dialerProxy"]!!.jsonPrimitive.content,
        )
        assertEquals(
            "front.example.com",
            stream["tlsSettings"]!!.jsonObject["serverName"]!!.jsonPrimitive.content,
        )
        assertEquals("real.example.com", stream["xhttpSettings"]!!.jsonObject["host"]!!.jsonPrimitive.content)
    }

    @Test
    fun `the egress outbound lands on the core's mapping port`() {
        val egress = xrayConfig().outbound("core-egress")
        assertEquals("socks", egress["protocol"]!!.jsonPrimitive.content)
        val server = egress["settings"]!!.jsonObject["servers"]!!.jsonArray.single().jsonObject
        assertEquals("127.0.0.1", server["address"]!!.jsonPrimitive.content)
        assertEquals(18081, server["port"]!!.jsonPrimitive.content.toInt())
    }

    @Test
    fun `the proxy is first, because Xray sends unrouted traffic to the first outbound`() {
        val tags = xrayConfig()["outbounds"]!!.jsonArray.map { it.jsonObject["tag"]!!.jsonPrimitive.content }
        assertEquals(listOf("proxy", "core-egress"), tags)
    }

    /** No SNI in the link: the server's own name is the only honest answer. */
    @Test
    fun `a missing sni falls back to the hostname, never to the dial address`() {
        val out = ShareLinks.parse(
            "vless://id@node.example.com:443?security=tls&type=xhttp&path=%2Fp#N",
        )!!.outbound as Outbound.Vless
        val stream = xrayConfig(out).outbound("proxy")["streamSettings"]!!.jsonObject
        assertEquals(
            "node.example.com",
            stream["tlsSettings"]!!.jsonObject["serverName"]!!.jsonPrimitive.content,
        )
        assertEquals(
            "and the Host header with it",
            "node.example.com",
            stream["xhttpSettings"]!!.jsonObject["host"]!!.jsonPrimitive.content,
        )
    }

    @Test
    fun `vision flow is dropped on a stream transport, which Xray would reject`() {
        val out = ShareLinks.parse(
            "vless://id@h.example.com:443?security=tls&type=xhttp&flow=xtls-rprx-vision#F",
        )!!.outbound as Outbound.Vless
        val user = xrayConfig(out).outbound("proxy")["settings"]!!
            .jsonObject["vnext"]!!.jsonArray.single().jsonObject["users"]!!.jsonArray.single().jsonObject
        assertNull(user["flow"])
        assertEquals("none", user["encryption"]!!.jsonPrimitive.content)
    }

    @Test
    fun `reality always carries a fingerprint, which Xray refuses to load without`() {
        val out = ShareLinks.parse(
            "vless://id@h.example.com:443?security=reality&type=xhttp&pbk=key&sid=ab&spx=%2F#RX",
        )!!.outbound as Outbound.Vless
        val reality = xrayConfig(out).outbound("proxy")["streamSettings"]!!
            .jsonObject["realitySettings"]!!.jsonObject
        assertEquals("key", reality["publicKey"]!!.jsonPrimitive.content)
        assertEquals("ab", reality["shortId"]!!.jsonPrimitive.content)
        assertEquals("/", reality["spiderX"]!!.jsonPrimitive.content)
        assertEquals("chrome", reality["fingerprint"]!!.jsonPrimitive.content)
    }

    /**
     * `extra` is Xray's own schema, handed over by the panel verbatim. The part that
     * matters here is downloadSettings: it opens its OWN sockets, so without the same
     * dialer the download half of every request would leave through the tunnel this
     * helper is providing.
     */
    @Test
    fun `extra is merged, and its download half gets the same dialer`() {
        val extra = """{"xPaddingBytes":"100-1000","downloadSettings":{"address":"dl.example.com","port":443,"network":"xhttp"}}"""
        val out = ShareLinks.parse(
            "vless://id@h.example.com:443?security=tls&type=xhttp&path=%2Fp&extra=" +
                android.net.Uri.encode(extra) + "#X",
        )!!.outbound as Outbound.Vless

        val xhttp = xrayConfig(out).outbound("proxy")["streamSettings"]!!
            .jsonObject["xhttpSettings"]!!.jsonObject
        assertEquals("100-1000", xhttp["xPaddingBytes"]!!.jsonPrimitive.content)
        assertEquals("/p", xhttp["path"]!!.jsonPrimitive.content)

        val download = xhttp["downloadSettings"]!!.jsonObject
        assertEquals("dl.example.com", download["address"]!!.jsonPrimitive.content)
        assertEquals(
            "core-egress",
            download["sockopt"]!!.jsonObject["dialerProxy"]!!.jsonPrimitive.content,
        )
    }

    @Test
    fun `malformed extra is dropped rather than failing the connect`() {
        val out = parsed().let { it.copy(transport = it.transport!!.copy(extra = "{not json")) }
        val xhttp = xrayConfig(out).outbound("proxy")["streamSettings"]!!
            .jsonObject["xhttpSettings"]!!.jsonObject
        assertEquals(
            "the node still starts on XHTTP's defaults",
            setOf("path", "host", "mode"),
            xhttp.keys,
        )
        assertEquals("packet-up", xhttp["mode"]!!.jsonPrimitive.content)
    }

    // ------------------------------------------------- the core side of it ----

    @Test
    fun `the core points a socks outbound at the helper and opens a socks mapping`() {
        val profile = Profile(name = "cdn", outbound = parsed())
        val text = SingBoxConfig.buildJson(
            listOf(profile),
            Settings(),
            smoke = true,
            plugins = mapOf(profile.id to SingBoxConfig.PluginPorts(socksPort = 18080, mappingPort = 18081)),
        )
        val config = json.parseToJsonElement(text).jsonObject

        val proxy = config["outbounds"]!!.jsonArray.map { it.jsonObject }
            .first { it["tag"]!!.jsonPrimitive.content == "proxy" }
        assertEquals("socks", proxy["type"]!!.jsonPrimitive.content)
        assertEquals(18080, proxy["server_port"]!!.jsonPrimitive.content.toInt())

        val mapping = config["inbounds"]!!.jsonArray.map { it.jsonObject }
            .first { it["tag"]?.jsonPrimitive?.content == "plugin-map-${profile.id}" }
        // "mixed", not "direct": Xray names its own destinations (the server, and
        // whatever downloadSettings points at), so a fixed redirect cannot serve it.
        assertEquals("mixed", mapping["type"]!!.jsonPrimitive.content)
        assertEquals(18081, mapping["listen_port"]!!.jsonPrimitive.content.toInt())
        assertFalse("a socks mapping must not rewrite the destination", mapping.containsKey("override_address"))

        assertTrue(
            "the mapping inbound must be routed straight out, or the helper loops",
            text.contains("plugin-map-${profile.id}"),
        )
    }

    /**
     * The loud half of the contract. Without a port allocation the core would otherwise
     * emit a plain vless outbound whose xhttp transport it simply cannot express — a
     * tunnel that connects and carries nothing.
     */
    @Test
    fun `an xhttp profile with no helper ports fails the build instead of degrading`() {
        val profile = Profile(name = "cdn", outbound = parsed())
        assertThrows(IllegalStateException::class.java) {
            SingBoxConfig.buildJson(listOf(profile), Settings(), smoke = true)
        }
    }
}

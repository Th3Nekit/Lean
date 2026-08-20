package com.th3web.lean.core

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import com.th3web.lean.data.Settings
import com.th3web.lean.data.model.Outbound
import com.th3web.lean.data.model.Profile

class SingBoxRuntimeSettingsTest {

    @Test
    fun tunStackAndMtuAreAppliedAndNormalized() {
        val custom = config(Settings(tunStack = "system", tunMtu = 1_420))
        val customTun = custom.tunInbound()
        assertEquals("system", customTun.string("stack"))
        assertEquals(1_420, customTun.int("mtu"))

        val normalized = config(Settings(tunStack = "unsupported", tunMtu = 900))
        val normalizedTun = normalized.tunInbound()
        assertEquals("gvisor", normalizedTun.string("stack"))
        assertEquals(1_280, normalizedTun.int("mtu"))
    }

    @Test
    fun sniffSettingsAreAppliedToPinnedNekoTunContract() {
        val enabled = config(
            Settings(
                sniffEnabled = true,
                sniffOverrideDestination = true,
                sniffResolveDestination = true,
                ipStrategy = "ipv4_only",
            ),
        ).tunInbound()
        assertTrue(enabled.boolean("sniff"))
        assertTrue(enabled.boolean("sniff_override_destination"))
        assertEquals("ipv4_only", enabled.string("domain_strategy"))

        val disabled = config(
            Settings(
                sniffEnabled = false,
                sniffOverrideDestination = false,
                sniffResolveDestination = false,
            ),
        ).tunInbound()
        assertNull(disabled["sniff"])
        assertNull(disabled["sniff_override_destination"])
        assertNull(disabled["domain_strategy"])
    }

    @Test
    fun dnsRoutingControlsBothDnsHijackRules() {
        val enabledActions = config(Settings(dnsRouting = true)).routeRules().actions()
        assertEquals(2, enabledActions.count { it == "hijack-dns" })

        val disabledActions = config(Settings(dnsRouting = false)).routeRules().actions()
        assertFalse(disabledActions.contains("hijack-dns"))
    }

    @Test
    fun fakeDnsControlsTypedServerRuleRangeAndPersistence() {
        val enabled = config(Settings(fakeDns = true))
        val fakeServer = enabled.dnsServers().singleOrNull { it.string("tag") == "dns-fake" }
        assertNotNull(fakeServer)
        assertEquals("fakeip", fakeServer!!.string("type"))
        assertEquals("198.18.0.0/15", fakeServer.string("inet4_range"))
        // Both ranges are declared like the reference client does. The typed fakeip
        // server takes only these two fields, so what keeps the v6 half out of a
        // v4-only TUN is the DNS module's own strategy: A-only until IPv6 is on, so
        // no AAAA is answered and no fc00::/18 address is ever minted.
        assertEquals("fc00::/18", fakeServer.string("inet6_range"))
        assertEquals("ipv4_only", enabled.dnsStrategy())
        assertEquals("prefer_ipv4", config(Settings(fakeDns = true, ipv6 = true)).dnsStrategy())

        val fakeRule = enabled.dnsRules().singleOrNull { it.string("server") == "dns-fake" }
        assertNotNull(fakeRule)
        assertEquals(listOf("tun-in"), fakeRule!!.array("inbound").map { it.jsonPrimitive.content })
        assertTrue(fakeRule.boolean("disable_cache"))

        // The fake-IP table is never written to cache.db any more: it was the busiest
        // writer into that bbolt file, and a mid-write kill left it corrupt enough that
        // the core panicked on every later start ("misplaced bucket header:
        // fakeip_address -> fakeip_metadata") — no server worked again until app data
        // was wiped.
        assertNull(enabled["experimental"]!!.jsonObject["cache_file"]!!.jsonObject["store_fakeip"])

        val disabled = config(Settings(fakeDns = false))
        assertFalse(disabled.dnsServers().any { it.string("tag") == "dns-fake" })
        assertFalse(disabled.dnsRules().any { it.string("server") == "dns-fake" })
        assertNull(disabled["experimental"]!!.jsonObject["cache_file"]!!.jsonObject["store_fakeip"])
    }

    @Test
    fun fakeDnsAnswersHttpsRecordQueriesBeforeTheyReachFakeip() {
        val rules = config(Settings(fakeDns = true)).dnsRules()

        val guardIndex = rules.indexOfFirst { it.string("action") == "predefined" }
        val fakeIndex = rules.indexOfFirst { it.string("server") == "dns-fake" }
        assertTrue(guardIndex >= 0)
        assertTrue(fakeIndex >= 0)
        // Order is the whole point: the fakeip transport answers A/AAAA and errors on
        // everything else, so an HTTPS/SVCB query reaching it fails outright. Browsers
        // ask for the HTTPS record of nearly every host, which is why the field logs are
        // solid walls of "only IP queries are supported by fakeip" and pages crawled.
        assertTrue(guardIndex < fakeIndex)

        val guard = rules[guardIndex]
        assertEquals(listOf("tun-in"), guard.array("inbound").map { it.jsonPrimitive.content })
        assertEquals(
            listOf("HTTPS", "SVCB"),
            guard.array("query_type").map { it.jsonPrimitive.content },
        )
        // NOERROR with no answer says "this name has no HTTPS record", which sends the
        // client straight to A/AAAA. NXDOMAIN would deny the name itself and a reject
        // (SERVFAIL) would invite retries.
        assertEquals("NOERROR", guard.string("rcode"))

        // With fake DNS off the query resolves normally through dns-remote, so Encrypted
        // Client Hello keeps working for anyone who turned the toggle off.
        assertFalse(config(Settings(fakeDns = false)).dnsRules().any { it.string("action") == "predefined" })
    }

    private fun config(settings: Settings): JsonObject = SingBoxConfig.build(profile, settings)

    private fun JsonObject.tunInbound(): JsonObject =
        this["inbounds"]!!.jsonArray.single().jsonObject

    private fun JsonObject.routeRules(): List<JsonObject> =
        this["route"]!!.jsonObject["rules"]!!.jsonArray.map { it.jsonObject }

    private fun JsonObject.dnsServers(): List<JsonObject> =
        this["dns"]!!.jsonObject["servers"]!!.jsonArray.map { it.jsonObject }

    private fun JsonObject.dnsStrategy(): String? =
        this["dns"]!!.jsonObject["strategy"]?.jsonPrimitive?.content

    private fun JsonObject.dnsRules(): List<JsonObject> =
        this["dns"]!!.jsonObject["rules"]?.jsonArray?.map { it.jsonObject }.orEmpty()

    private fun List<JsonObject>.actions(): List<String> =
        mapNotNull { it["action"]?.jsonPrimitive?.content }

    private fun JsonObject.string(name: String): String? =
        this[name]?.jsonPrimitive?.content

    private fun JsonObject.int(name: String): Int =
        this[name]!!.jsonPrimitive.int

    private fun JsonObject.boolean(name: String): Boolean =
        this[name]!!.jsonPrimitive.boolean

    private fun JsonObject.array(name: String): JsonArray =
        this[name]!!.jsonArray

    private companion object {
        val profile = Profile(
            id = "runtime-settings",
            name = "Runtime settings",
            outbound = Outbound.Vless(
                server = "203.0.113.10",
                serverPort = 443,
                uuid = "b831381d-6324-4d53-ad4f-8cda48b30811",
            ),
        )
    }

    /**
     * «TUN | Прокси | TUN + Прокси».
     *
     * Proxy-only must emit NO tun inbound: the core asks the platform to open a TUN when
     * it starts that inbound, so its absence is the entire mechanism by which nothing is
     * captured system-wide and VpnService.establish never runs.
     */
    @Test
    fun serviceModeDecidesWhichInboundsExist() {
        fun inbounds(mode: String, allowLan: Boolean = false) =
            config(Settings(serviceMode = mode, proxyAllowLan = allowLan))["inbounds"]!!
                .jsonArray.map { it.jsonObject }

        val tunOnly = inbounds("vpn")
        assertTrue(tunOnly.any { it.string("type") == "tun" })
        assertFalse(tunOnly.any { it.string("tag") == "proxy-in" })

        val proxyOnly = inbounds("proxy")
        assertFalse(proxyOnly.any { it.string("type") == "tun" })
        val listener = proxyOnly.single { it.string("tag") == "proxy-in" }
        assertEquals("mixed", listener.string("type"))
        // Loopback unless the user opens it deliberately — on 0.0.0.0 this is an open
        // proxy for the whole Wi-Fi.
        assertEquals("127.0.0.1", listener.string("listen"))
        assertEquals(2080, listener["listen_port"]!!.jsonPrimitive.int)

        val both = inbounds("vpn_proxy")
        assertTrue(both.any { it.string("type") == "tun" })
        assertTrue(both.any { it.string("tag") == "proxy-in" })

        assertEquals(
            "0.0.0.0",
            inbounds("proxy", allowLan = true).single { it.string("tag") == "proxy-in" }.string("listen"),
        )
    }
}

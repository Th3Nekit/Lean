package com.th3web.lean.core.tun

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TunSpecParserTest {
    /**
     * The core serialises absent address lists as JSON null — `Inet6Address` whenever
     * IPv6 is off, which is the default. kotlinx.serialization's JsonNull is itself a
     * JsonPrimitive whose content is the string "null", so those fields used to parse as
     * a literal address "null" and throw `Invalid CIDR: null` out of the OpenTun
     * callback. The core then failed at "configure tun interface" and NOTHING could
     * connect, on any config. Nulls must read as absent.
     */
    @Test
    fun `json nulls are treated as absent, not as the string null`() {
        val spec = TunSpecParser.parse(
            """
            {
              "MTU": 1500,
              "Inet4Address": ["172.19.0.1/30"],
              "Inet6Address": null,
              "AutoRoute": true,
              "Inet4RouteAddress": null,
              "Inet6RouteAddress": [null],
              "Inet4RouteExcludeAddress": null,
              "Inet6RouteExcludeAddress": null,
              "StrictRoute": false
            }
            """.trimIndent(),
            """{"http_proxy":{"enabled":true,"server":null,"host":"127.0.0.1","server_port":7890}}""",
        )

        assertTrue(spec.ipv6Addresses.isEmpty())
        assertTrue(spec.ipv6Routes.isEmpty())
        assertTrue(spec.ipv4RouteExcludes.isEmpty())
        assertEquals(listOf(IpPrefix("0.0.0.0", 0)), spec.ipv4Routes)
        assertEquals("172.19.0.1", spec.ipv4Addresses.single().address)
        // A null "server" must fall through to "host" rather than becoming "null".
        assertEquals("127.0.0.1", spec.httpProxy?.host)
    }

    @Test
    fun `parses canonical Go JSON and derives ipv4 peer and dns`() {
        val spec = TunSpecParser.parse(
            """
            {
              "MTU": 9000,
              "Inet4Address": ["172.19.0.1/30"],
              "Inet6Address": ["fdfe:dcba:9876::1/126"],
              "AutoRoute": true,
              "Inet4RouteAddress": [],
              "Inet6RouteAddress": [],
              "Inet4RouteExcludeAddress": ["10.0.0.0/8"],
              "Inet6RouteExcludeAddress": ["fd00::/8"],
              "StrictRoute": true
            }
            """.trimIndent(),
            """{"http_proxy":{"enabled":true,"server":"127.0.0.1","server_port":7890,"bypass_domain":["localhost"]}}""",
        )

        assertEquals(9000, spec.mtu)
        assertEquals("172.19.0.1", spec.ipv4Addresses.single().address)
        assertEquals(30, spec.ipv4Addresses.single().prefixLength)
        assertEquals("172.19.0.2", spec.ipv4Peer)
        assertEquals("172.19.0.2", spec.ipv4Dns)
        assertEquals(listOf(IpPrefix("0.0.0.0", 0)), spec.ipv4Routes)
        assertEquals(listOf(IpPrefix("::", 0)), spec.ipv6Routes)
        assertEquals("127.0.0.1", spec.httpProxy?.host)
        assertEquals(7890, spec.httpProxy?.port)
        assertEquals(listOf("localhost"), spec.httpProxy?.bypassDomains)
        assertTrue(spec.strictRoute)
    }

    @Test
    fun `lower-case proxy wrapper is optional and disabled proxy is ignored`() {
        val canonical = """{"MTU":1500,"Inet4Address":["172.19.0.1/30"],"AutoRoute":false}"""

        assertNull(TunSpecParser.parse(canonical, null).httpProxy)
        assertNull(
            TunSpecParser.parse(
                canonical,
                """{"http_proxy":{"enabled":false,"server":"127.0.0.1","server_port":8080}}""",
            ).httpProxy,
        )
    }

    @Test
    fun `explicit routes are preserved when auto route is disabled`() {
        val spec = TunSpecParser.parse(
            """
            {
              "MTU": 1280,
              "Inet4Address": ["10.0.0.1/24"],
              "AutoRoute": false,
              "Inet4RouteAddress": ["1.1.1.0/24"],
              "StrictRoute": false
            }
            """.trimIndent(),
        )

        assertEquals(listOf(IpPrefix("1.1.1.0", 24)), spec.ipv4Routes)
        assertTrue(spec.ipv6Routes.isEmpty())
        assertFalse(spec.strictRoute)
    }

    @Test
    fun `rejects invalid cidr and ipv4 slash 32`() {
        assertFails("CIDR") {
            TunSpecParser.parse("""{"MTU":1500,"Inet4Address":["not-a-cidr"],"AutoRoute":true}""")
        }
        assertFails("/32") {
            TunSpecParser.parse("""{"MTU":1500,"Inet4Address":["172.19.0.1/32"],"AutoRoute":true}""")
        }
    }

    private fun assertFails(fragment: String, block: () -> Unit) {
        val error = runCatching(block).exceptionOrNull()
        checkNotNull(error)
        assertTrue(error.message.orEmpty().contains(fragment, ignoreCase = true))
    }
}

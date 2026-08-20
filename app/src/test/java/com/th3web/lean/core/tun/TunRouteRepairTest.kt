package com.th3web.lean.core.tun

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The bug this exists for: «Обход локальных сетей» is on by default, and it excludes
 * 10/8, 172.16/12 and 192.168/16 from the VPN. A WireGuard profile lives inside exactly
 * those ranges — its DNS server is the far end of the tunnel, typically 10.x.x.1 — so the
 * bypass routed every DNS query out through the physical network, where that address does
 * not exist. The tunnel connected, the handshake completed, and nothing resolved.
 *
 * It never touched the sing-box side because there the resolver is the tun's OWN address:
 * assigned to the interface, delivered on-link, reachable with no route at all.
 */
class TunRouteRepairTest {

    private val lanBypass = listOf(
        IpPrefix("10.0.0.0", 8),
        IpPrefix("172.16.0.0", 12),
        IpPrefix("192.168.0.0", 16),
    )
    private val everything = listOf(IpPrefix("0.0.0.0", 0))

    @Test
    fun `a tunnel dns inside the bypassed range is put back as a host route`() {
        assertEquals(
            listOf(IpPrefix("10.8.1.1", 32)),
            TunRouteRepair.restore(everything, lanBypass, listOf("10.8.1.1")),
        )
    }

    /**
     * Nothing to repair: the exclusion does not match, so the default route already
     * carries it and a host route would only be noise.
     */
    @Test
    fun `a public resolver is left alone`() {
        assertEquals(
            emptyList<IpPrefix>(),
            TunRouteRepair.restore(everything, lanBypass, listOf("1.1.1.1")),
        )
    }

    /**
     * The line this must not cross. A split-tunnel profile that routes only its own subnet
     * never claimed the LAN resolver, so pulling it in would be inventing a route the
     * profile did not ask for — the opposite mistake, made just as silently.
     */
    @Test
    fun `an address the tunnel never claimed stays out`() {
        assertEquals(
            emptyList<IpPrefix>(),
            TunRouteRepair.restore(
                routes = listOf(IpPrefix("10.8.1.0", 24)),
                excludes = lanBypass,
                needed = listOf("192.168.1.1"),
            ),
        )
    }

    /** A route MORE specific than the exclusion already wins; nothing to do. */
    @Test
    fun `a route that already outranks the exclusion needs no repair`() {
        assertEquals(
            emptyList<IpPrefix>(),
            TunRouteRepair.restore(
                routes = listOf(IpPrefix("0.0.0.0", 0), IpPrefix("10.8.1.0", 24)),
                excludes = lanBypass,
                needed = listOf("10.8.1.1"),
            ),
        )
    }

    /**
     * The canonical rendering, not the caller's string — VpnService.Builder.addRoute throws
     * on anything it cannot parse, and it throws while the tunnel is being established.
     */
    @Test
    fun `a padded address comes back in the form the platform will accept`() {
        assertEquals(
            listOf(IpPrefix("10.8.1.1", 32)),
            TunRouteRepair.restore(everything, lanBypass, listOf("  10.8.1.1  ")),
        )
    }

    @Test
    fun `ipv6 is handled on its own family and never mixed with v4`() {
        assertEquals(
            listOf(IpPrefix("fd00:0:0:0:0:0:0:1", 128)),
            TunRouteRepair.restore(
                routes = listOf(IpPrefix("::", 0), IpPrefix("0.0.0.0", 0)),
                excludes = listOf(IpPrefix("fd00::", 8), IpPrefix("10.0.0.0", 8)),
                needed = listOf("fd00::1"),
            ),
        )
    }

    /** A hostname would mean a blocking lookup while the tunnel is being established. */
    @Test
    fun `a non-literal address is skipped rather than resolved`() {
        assertEquals(
            emptyList<IpPrefix>(),
            TunRouteRepair.restore(everything, lanBypass, listOf("dns.example.com", "")),
        )
    }

    @Test
    fun `with nothing excluded there is nothing to repair`() {
        assertEquals(
            emptyList<IpPrefix>(),
            TunRouteRepair.restore(everything, emptyList(), listOf("10.8.1.1")),
        )
    }
}

package com.th3web.lean.core.tun

import java.net.InetAddress

/**
 * Puts back the routes «Обход локальных сетей» takes away from the tunnel itself.
 *
 * The bypass excludes 10/8, 172.16/12 and 192.168/16 from the VPN so traffic to the home
 * LAN keeps its own path. That is right for a proxy and quietly fatal for WireGuard: a
 * WireGuard config lives inside a private range, an address like 10.8.1.2/32 and, almost
 * always, a DNS server at the far end of the tunnel like 10.8.1.1. Excluding 10/8 routes
 * that DNS server out through the physical network, where it does not exist. The handshake
 * completes, the tunnel is up, the app says «подключено», and not one name resolves.
 *
 * It never showed on the sing-box side because there the DNS server is the tun's own
 * address: an address assigned to the interface is delivered on-link and needs no route at
 * all, so no exclusion can reach it. Only a remote address, which is what a
 * WireGuard peer's DNS is, depends on the routing table.
 *
 * The repair is a host route, because Android resolves this by prefix length:
 * "If multiple routes match the packet destination, route with the longest prefix takes
 * precedence" (VpnService.Builder#addRoute / #excludeRoute). A /32 therefore beats the /8
 * exclusion while leaving the rest of the LAN bypass exactly as the user asked for it.
 */
internal object TunRouteRepair {

    /**
     * The host routes to add so nothing in [needed] is stolen.
     *
     * An address is "stolen" only when the tunnel was supposed to carry it in the first
     * place (some route in [routes] matches it), and an exclusion matches it more
     * specifically. Anything the tunnel never claimed (a public resolver under a
     * split-tunnel config that routes only its own subnet) is left alone: pulling it in
     * would be inventing a route the profile did not ask for.
     */
    fun restore(
        routes: List<IpPrefix>,
        excludes: List<IpPrefix>,
        needed: List<String>,
    ): List<IpPrefix> {
        if (routes.isEmpty() || excludes.isEmpty() || needed.isEmpty()) return emptyList()
        return needed.distinct().mapNotNull { address ->
            val parsed = parse(address) ?: return@mapNotNull null
            val covered = longestMatch(routes, parsed.address) ?: return@mapNotNull null
            val stolen = longestMatch(excludes, parsed.address) ?: return@mapNotNull null
            if (stolen <= covered) return@mapNotNull null
            // The canonical text, never the caller's string. VpnService.Builder.addRoute
            // throws on anything it cannot parse, and that throw happens while the tunnel
            // is being established, so one DNS entry with a stray space around it would
            // take down every connect, on both engines, for a repair that is meant to be
            // invisible when it has nothing to do.
            IpPrefix(parsed.text, parsed.bits)
        }
    }

    /** Length of the most specific prefix in [prefixes] that contains [bytes], or null. */
    private fun longestMatch(prefixes: List<IpPrefix>, bytes: ByteArray): Int? =
        prefixes.mapNotNull { prefix ->
            val base = parse(prefix.address)?.address ?: return@mapNotNull null
            // A v4 address is never inside a v6 prefix, and vice versa.
            if (base.size != bytes.size) return@mapNotNull null
            if (prefix.prefixLength !in 0..(base.size * 8)) return@mapNotNull null
            if (sharesPrefix(base, bytes, prefix.prefixLength)) prefix.prefixLength else null
        }.maxOrNull()

    private fun sharesPrefix(left: ByteArray, right: ByteArray, bits: Int): Boolean {
        val wholeBytes = bits / 8
        for (i in 0 until wholeBytes) {
            if (left[i] != right[i]) return false
        }
        val remainder = bits % 8
        if (remainder == 0) return true
        val mask = (0xFF shl (8 - remainder)) and 0xFF
        return (left[wholeBytes].toInt() and mask) == (right[wholeBytes].toInt() and mask)
    }

    /**
     * Literal addresses only. A hostname here would mean a blocking lookup on the thread
     * that is establishing the tunnel, so anything that is not already an IP is skipped,
     * it cannot have been stolen by a prefix in the first place.
     */
    private fun parse(address: String): Literal? {
        val trimmed = address.trim().removeSurrounding("[", "]")
        if (trimmed.isEmpty()) return null
        if (trimmed.any { it !in "0123456789abcdefABCDEF.:%" }) return null
        val resolved = runCatching { InetAddress.getByName(trimmed) }.getOrNull() ?: return null
        val text = resolved.hostAddress ?: return null
        return Literal(resolved.address, text.substringBefore('%'))
    }

    /** A literal address as the platform itself renders it, plus its raw bytes. */
    private class Literal(val address: ByteArray, val text: String) {
        val bits: Int get() = address.size * 8
    }
}

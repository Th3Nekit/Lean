package com.th3web.lean.data.parse

import com.th3web.lean.data.model.AmneziaParams
import com.th3web.lean.data.model.Outbound
import com.th3web.lean.data.model.Profile

/**
 * Parses a WireGuard / AmneziaWG `.conf` (INI) file into a single [Profile]
 * carrying an [Outbound.WireGuard]. Unlike share links these are whole-interface
 * configs, not URIs, so they get their own parser.
 *
 * AmneziaWG: the obfuscation params (Jc/Jmin/Jmax/S1-S4/H1-H4/I1-I5) are captured
 * into [Outbound.WireGuard.awg]; when present the profile is routed to the separate
 * AmneziaWG-Go runtime. H1-H4 and I1-I5 are kept as raw
 * strings, H values are uint32 (overflow Int), and I values are opaque packet specs.
 *
 * Parsing is best-effort but strict on the load-bearing fields: a config missing
 * a PrivateKey, a local Address, a peer PublicKey, or a parseable Endpoint is
 * rejected (returns null) rather than producing a config that can never connect.
 */
object WgConfig {

    /** Cheap pre-check used by the import seam to route a paste/file to this parser. */
    fun looksLikeWgConf(text: String): Boolean =
        text.contains("[Interface]", ignoreCase = true)

    /**
     * Parse a `.conf` INI string into a WireGuard outbound. Returns null on any
     * reject condition (see class docs). [fallbackName] is used as the profile
     * name when the file carries no leading `# Name` comment.
     */
    fun parse(text: String, fallbackName: String): Outbound.WireGuard? {
        var section = "" // "interface" | "peer" | ""
        var seenInterface = false

        // [Interface] accumulators
        var privateKey = ""
        val addresses = mutableListOf<String>()
        val dnsServers = mutableListOf<String>()
        var mtu = 0
        // AmneziaWG accumulators. Junk/size knobs are small ints; H (uint32 magic)
        // and I (packet specs) are kept as raw strings, see class docs.
        var jc = 0; var jmin = 0; var jmax = 0
        var s1 = 0; var s2 = 0; var s3 = 0; var s4 = 0
        var h1 = ""; var h2 = ""; var h3 = ""; var h4 = ""
        var i1 = ""; var i2 = ""; var i3 = ""; var i4 = ""; var i5 = ""
        var sawAwgKey = false

        // [Peer] blocks, first peer is the proxy endpoint in our single-peer model.
        data class Peer(
            var publicKey: String = "",
            var preSharedKey: String = "",
            var endpoint: String = "",
            val allowedIps: MutableList<String> = mutableListOf(),
            var keepalive: Int = 0,
        )
        val peers = mutableListOf<Peer>()
        fun currentPeer(): Peer? = peers.lastOrNull()

        for (rawLine in text.lineSequence()) {
            // Capture a leading "# name" on the very first line as a name candidate
            // is handled below via the section/profile-name logic; here we just strip.
            val line = stripComment(rawLine).trim()
            if (line.isEmpty()) continue

            if (line.startsWith("[") && line.endsWith("]")) {
                when (line.substring(1, line.length - 1).trim().lowercase()) {
                    "interface" -> {
                        if (seenInterface) return null // duplicate [Interface] => malformed
                        seenInterface = true
                        section = "interface"
                    }
                    "peer" -> {
                        section = "peer"
                        peers.add(Peer())
                    }
                    else -> section = "" // unknown section: ignore its keys
                }
                continue
            }

            val eq = line.indexOf('=')
            if (eq < 0) return null // a key with no '=' (and not a header) => malformed
            val key = line.substring(0, eq).trim().lowercase()
            val value = line.substring(eq + 1).trim()

            when (section) {
                "interface" -> when (key) {
                    "privatekey" -> privateKey = value
                    "address" -> addresses += splitList(value).map { normalizeCidr(it) }
                    "mtu" -> mtu = value.toIntOrNull() ?: 0
                    "listenport" -> {} // optional; not modelled (core picks a random port)
                    "dns" -> dnsServers += splitList(value).filter(::isIpLiteral)
                    // --- AmneziaWG obfuscation params: captured into awg below ---
                    "jc" -> { jc = value.toIntOrNull() ?: 0; sawAwgKey = true }
                    "jmin" -> { jmin = value.toIntOrNull() ?: 0; sawAwgKey = true }
                    "jmax" -> { jmax = value.toIntOrNull() ?: 0; sawAwgKey = true }
                    "s1" -> { s1 = value.toIntOrNull() ?: 0; sawAwgKey = true }
                    "s2" -> { s2 = value.toIntOrNull() ?: 0; sawAwgKey = true }
                    "s3" -> { s3 = value.toIntOrNull() ?: 0; sawAwgKey = true }
                    "s4" -> { s4 = value.toIntOrNull() ?: 0; sawAwgKey = true }
                    "h1" -> { h1 = value; sawAwgKey = true }
                    "h2" -> { h2 = value; sawAwgKey = true }
                    "h3" -> { h3 = value; sawAwgKey = true }
                    "h4" -> { h4 = value; sawAwgKey = true }
                    "i1" -> { i1 = value; sawAwgKey = true }
                    "i2" -> { i2 = value; sawAwgKey = true }
                    "i3" -> { i3 = value; sawAwgKey = true }
                    "i4" -> { i4 = value; sawAwgKey = true }
                    "i5" -> { i5 = value; sawAwgKey = true }
                    else -> {} // unknown interface key: ignore
                }
                "peer" -> {
                    val peer = currentPeer() ?: return null
                    when (key) {
                        "publickey" -> peer.publicKey = value
                        "presharedkey" -> peer.preSharedKey = value
                        "endpoint" -> peer.endpoint = value
                        "allowedips" -> peer.allowedIps += splitList(value).map { normalizeCidr(it) }
                        "persistentkeepalive" -> peer.keepalive = value.toIntOrNull() ?: 0
                        else -> {} // unknown peer key: ignore
                    }
                }
                else -> return null // a key before any section header => malformed
            }
        }

        // ---- reject conditions ----
        if (!seenInterface || privateKey.isEmpty()) return null
        if (addresses.isEmpty()) return null
        val peer = peers.firstOrNull() ?: return null
        if (peer.publicKey.isEmpty()) return null
        val (peerHost, peerPort) = splitEndpoint(peer.endpoint) ?: return null

        // AmneziaWG fires when any junk/size param is non-zero, any signature packet
        // (I1-I5) is present, or any header magic differs from the canonical 1/2/3/4.
        // (Keys present at their default values == effectively plain WG.)
        fun hCustom(h: String, default: String) = h.isNotEmpty() && h != default
        val isAwg = sawAwgKey && (
            jc != 0 || jmin != 0 || jmax != 0 ||
                s1 != 0 || s2 != 0 || s3 != 0 || s4 != 0 ||
                i1.isNotEmpty() || i2.isNotEmpty() || i3.isNotEmpty() ||
                i4.isNotEmpty() || i5.isNotEmpty() ||
                hCustom(h1, "1") || hCustom(h2, "2") || hCustom(h3, "3") || hCustom(h4, "4")
            )
        val awgParams = if (isAwg) AmneziaParams(
            jc = jc, jmin = jmin, jmax = jmax,
            s1 = s1, s2 = s2, s3 = s3, s4 = s4,
            h1 = h1, h2 = h2, h3 = h3, h4 = h4,
            i1 = i1, i2 = i2, i3 = i3, i4 = i4, i5 = i5,
        ) else null

        return Outbound.WireGuard(
            server = peerHost,
            serverPort = peerPort,
            privateKey = privateKey,
            peerPublicKey = peer.publicKey,
            preSharedKey = peer.preSharedKey,
            localAddresses = addresses,
            dnsServers = dnsServers,
            allowedIps = peer.allowedIps.ifEmpty { listOf("0.0.0.0/0", "::/0") },
            persistentKeepalive = peer.keepalive,
            mtu = mtu,
            awg = awgParams,
        )
    }

    /**
     * Parse into a full [Profile]. Name precedence: a leading `# Name` comment in
     * the file, else [fallbackName].
     */
    fun parseProfile(text: String, fallbackName: String): Profile? {
        val outbound = parse(text, fallbackName) ?: return null
        val name = leadingNameComment(text) ?: fallbackName.ifBlank { outbound.server }
        return Profile(name = name, outbound = outbound)
    }

    // ---- helpers ----

    /** Strips an inline `#`/`;` comment to end of line. */
    private fun stripComment(line: String): String {
        val hash = line.indexOf('#')
        val semi = line.indexOf(';')
        val cut = when {
            hash < 0 -> semi
            semi < 0 -> hash
            else -> minOf(hash, semi)
        }
        return if (cut < 0) line else line.substring(0, cut)
    }

    /** A leading `# My Name` on the first non-blank line, used as the profile name. */
    private fun leadingNameComment(text: String): String? {
        for (raw in text.lineSequence()) {
            val t = raw.trim()
            if (t.isEmpty()) continue
            if (t.startsWith("#") || t.startsWith(";")) {
                val name = t.drop(1).trim()
                return name.ifEmpty { null }
            }
            return null // first content line isn't a comment
        }
        return null
    }

    /** Comma-separated list value → trimmed, non-empty elements. */
    private fun splitList(value: String): List<String> =
        value.split(',').map { it.trim() }.filter { it.isNotEmpty() }

    /** Bare IP (no prefix) → append /32 (v4) or /128 (v6); CIDRs pass through. */
    private fun normalizeCidr(addr: String): String {
        if ('/' in addr) return addr
        return if (':' in addr) "$addr/128" else "$addr/32"
    }

    private fun isIpLiteral(value: String): Boolean {
        val candidate = value.trim()
        if (candidate.isEmpty()) return false
        val looksV6 = ':' in candidate
        val looksV4 = candidate.count { it == '.' } == 3 &&
            candidate.all { it.isDigit() || it == '.' }
        if (!looksV4 && !looksV6) return false
        return runCatching { java.net.InetAddress.getByName(candidate) }
            .getOrNull()
            ?.let { (looksV6 && it is java.net.Inet6Address) || (looksV4 && it is java.net.Inet4Address) }
            ?: false
    }

    /**
     * Splits a WireGuard `Endpoint` (`host:port`) into host + port. Bracketed IPv6
     * (`[2606:4700::1111]:51820`) is honoured; an unbracketed multi-colon IPv6 is
     * ambiguous and rejected. Returns null when the port is missing or out of range.
     */
    private fun splitEndpoint(endpoint: String): Pair<String, Int>? {
        val ep = endpoint.trim()
        if (ep.isEmpty()) return null
        val host: String
        val portStr: String
        if (ep.startsWith("[")) {
            val close = ep.indexOf(']')
            if (close < 0) return null
            host = ep.substring(1, close)
            val after = ep.substring(close + 1)
            if (!after.startsWith(":")) return null
            portStr = after.substring(1)
        } else {
            val idx = ep.lastIndexOf(':')
            if (idx < 0) return null
            host = ep.substring(0, idx)
            portStr = ep.substring(idx + 1)
            // Unbracketed IPv6 (host itself still has colons) is ambiguous => reject.
            if (':' in host) return null
        }
        if (host.isEmpty()) return null
        val port = portStr.toIntOrNull() ?: return null
        if (port !in 1..65535) return null
        return host to port
    }
}

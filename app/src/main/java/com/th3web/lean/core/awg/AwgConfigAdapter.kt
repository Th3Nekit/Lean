package com.th3web.lean.core.awg

import android.util.Base64
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import com.th3web.lean.core.tun.AwgTunSpec
import com.th3web.lean.core.tun.IpPrefix
import com.th3web.lean.core.tun.TunRuntimePolicy
import com.th3web.lean.data.model.AmneziaParams
import com.th3web.lean.data.model.Outbound
import com.th3web.lean.data.model.Profile

interface AwgProfileAdapter {
    fun prepare(profile: Profile, policy: TunRuntimePolicy): PreparedAwgProfile
    fun userspaceConfig(prepared: PreparedAwgProfile, resolvedEndpoint: String): String
}

data class PreparedAwgProfile(
    val profileId: String,
    val server: String,
    val serverPort: Int,
    val privateKeyHex: String,
    val publicKeyHex: String,
    val preSharedKeyHex: String?,
    val allowedIps: List<String>,
    val persistentKeepalive: Int,
    val awg: AmneziaParams,
    val tunSpec: AwgTunSpec,
)

/**
 * What a profile that names no keepalive gets, and why it is not left at zero.
 *
 * The core only starts a handshake by itself when a peer has a keepalive: bringing the
 * device up runs `if peer.persistentKeepaliveInterval > 0 { peer.SendKeepalive() }` and
 * nothing else. With zero, the very first initiation waits for the first packet an app
 * happens to push into the fresh tun, so «подключение» sat there doing nothing until
 * some process on the phone decided to talk, which is not the same thing as connecting.
 *
 * 25 s is the value wg-quick and Amnezia's own generator write into every client config,
 * and it is what keeps a NAT binding open so the server can reach us between transfers.
 * A profile that asks for its own interval keeps it.
 */
private const val DEFAULT_KEEPALIVE_SECONDS = 25

/** The range the platform will accept for a tun interface. */
private const val MIN_TUNNEL_MTU = 1280
private const val MAX_TUNNEL_MTU = 1500

/**
 * What a tunnelled packet costs on the wire before any of its payload counts.
 *
 * An IPv6 outer header (40) + UDP (8) + WireGuard's transport header and tag (32). The
 * IPv6 figure rather than the IPv4 one because it is the larger of the two and the family
 * is not known until the endpoint resolves, being 20 bytes conservative costs nothing,
 * and being 20 bytes optimistic costs the packet.
 */
private const val OUTER_OVERHEAD = 80

/** Assumed path MTU. Ethernet everywhere that matters; mobile links are rarely larger. */
private const val PATH_MTU = 1500

/**
 * Below this a tunnel cannot carry IPv6 at all (RFC 8200 minimum link MTU), so it is the
 * floor whenever the profile routes any IPv6, and dropping under it there would break
 * more than it fixes.
 */
private const val IPV6_MIN_MTU = 1280

/** IPv4's own minimum reassembly buffer; the floor when no IPv6 is routed. */
private const val IPV4_MIN_MTU = 576

class AwgConfigAdapter : AwgProfileAdapter {
    override fun prepare(profile: Profile, policy: TunRuntimePolicy): PreparedAwgProfile {
        val outbound = profile.outbound as? Outbound.WireGuard
            ?: throw IllegalArgumentException("Профиль не является WireGuard")
        val awg = outbound.awg
            ?: throw IllegalArgumentException("Профиль не содержит параметры AmneziaWG")
        require(outbound.server.isNotBlank()) { "Хост AmneziaWG не указан" }
        require(outbound.serverPort in 1..65_535) { "Порт AmneziaWG вне диапазона" }
        require(outbound.localAddresses.isNotEmpty()) { "Локальный адрес AmneziaWG не указан" }
        require(outbound.allowedIps.isNotEmpty()) { "Список AllowedIPs пуст" }
        require(outbound.persistentKeepalive in 0..65_535) { "Некорректный keepalive AmneziaWG" }
        require(outbound.mtu == 0 || outbound.mtu in 576..65_535) { "Некорректный MTU AmneziaWG" }
        validateAwgNumbers(awg)

        val local = outbound.localAddresses.map(::parseCidr)
            .filter { policy.ipv6Enabled || ':' !in it.address }
        require(local.isNotEmpty()) { "Локальный адрес AmneziaWG отключён политикой IPv6" }
        val routes = outbound.allowedIps.map(::parseCidr)
            .filter { policy.ipv6Enabled || ':' !in it.address }
        require(routes.isNotEmpty()) { "Маршруты AmneziaWG отключены политикой IPv6" }
        val literalDns = outbound.dnsServers.mapNotNull(::parseLiteral)
            .filter { policy.ipv6Enabled || it is Inet4Address }
            .mapNotNull(InetAddress::getHostAddress)
        val dns = literalDns.ifEmpty {
            buildList {
                add("8.8.8.8")
                if (policy.ipv6Enabled && routes.any { ':' in it.address }) {
                    add("2001:4860:4860::8888")
                }
            }
        }

        return PreparedAwgProfile(
            profileId = profile.id,
            server = outbound.server.trim(),
            serverPort = outbound.serverPort,
            privateKeyHex = decodeKey(outbound.privateKey, "приватный"),
            publicKeyHex = decodeKey(outbound.peerPublicKey, "публичный"),
            preSharedKeyHex = outbound.preSharedKey
                .takeIf(String::isNotEmpty)
                ?.let { decodeKey(it, "предварительно согласованный") },
            allowedIps = routes.map { "${it.address}/${it.prefixLength}" },
            persistentKeepalive = outbound.persistentKeepalive.takeIf { it > 0 }
                ?: DEFAULT_KEEPALIVE_SECONDS,
            awg = awg,
            tunSpec = AwgTunSpec(
                mtu = tunnelMtu(policy.wgMtu, awg.s4, routes),
                addresses = local,
                routes = routes,
                dnsServers = dns,
            ),
        )
    }

    override fun userspaceConfig(
        prepared: PreparedAwgProfile,
        resolvedEndpoint: String,
    ): String =
        buildString {
            line("private_key", prepared.privateKeyHex)
            prepared.awg.apply {
                positive("jc", jc)
                positive("jmin", jmin)
                positive("jmax", jmax)
                positive("s1", s1)
                positive("s2", s2)
                positive("s3", s3)
                positive("s4", s4)
                opaque("h1", h1)
                opaque("h2", h2)
                opaque("h3", h3)
                opaque("h4", h4)
                opaque("i1", i1)
                opaque("i2", i2)
                opaque("i3", i3)
                opaque("i4", i4)
                opaque("i5", i5)
            }
            line("replace_peers", "true")
            line("public_key", prepared.publicKeyHex)
            prepared.allowedIps.forEach { line("allowed_ip", it) }
            line("endpoint", resolvedEndpoint)
            if (prepared.persistentKeepalive > 0) {
                line("persistent_keepalive_interval", prepared.persistentKeepalive.toString())
            }
            prepared.preSharedKeyHex?.let { line("preshared_key", it) }
        }

    /**
     * The frame size the tun may hand the core, once the wire has taken its share.
     *
     * «WireGuard MTU» wins over the profile, exactly as it does for the sing-box WireGuard
     * endpoint, which ignores the profile's value outright, one knob for one protocol.
     *
     * But the knob alone is not enough here, and that is what AmneziaWG adds over plain
     * WireGuard: [AmneziaParams.s4] pads every transport packet. Those bytes ride outside
     * the tun's MTU, so a tunnel set to a perfectly ordinary 1280 still puts
     * 1280 + 80 + s4 on the wire. Once that passes the path MTU the full-size packets are
     * gone while everything small still works, a tunnel that handshakes, moves a few
     * kilobytes and then goes quiet. So the junk is subtracted rather than hoped about.
     */
    private fun tunnelMtu(requested: Int, transportJunk: Int, routes: List<IpPrefix>): Int {
        val floor = if (routes.any { ':' in it.address }) IPV6_MIN_MTU else IPV4_MIN_MTU
        val budget = PATH_MTU - OUTER_OVERHEAD - transportJunk.coerceAtLeast(0)
        return requested
            .coerceIn(MIN_TUNNEL_MTU, MAX_TUNNEL_MTU)
            .coerceAtMost(budget)
            .coerceIn(floor, MAX_TUNNEL_MTU)
    }

    private fun StringBuilder.line(key: String, value: String) {
        append(key).append('=').append(value).append('\n')
    }

    private fun StringBuilder.positive(key: String, value: Int) {
        if (value > 0) line(key, value.toString())
    }

    private fun StringBuilder.opaque(key: String, value: String) {
        val trimmed = value.trim()
        if (trimmed.isEmpty()) return
        require('\n' !in trimmed && '\r' !in trimmed) { "Некорректный параметр AmneziaWG: $key" }
        line(key, trimmed)
    }

    private fun validateAwgNumbers(params: AmneziaParams) {
        val values = listOf(
            "jc" to params.jc,
            "jmin" to params.jmin,
            "jmax" to params.jmax,
            "s1" to params.s1,
            "s2" to params.s2,
            "s3" to params.s3,
            "s4" to params.s4,
        )
        require(values.all { it.second >= 0 }) {
            "Числовой параметр AmneziaWG не может быть отрицательным"
        }
    }

    private fun decodeKey(raw: String, label: String): String {
        val bytes = runCatching { Base64.decode(raw, Base64.NO_WRAP) }.getOrNull()
        require(
            bytes != null &&
                bytes.size == 32 &&
                Base64.encodeToString(bytes, Base64.NO_WRAP) == raw,
        ) {
            "Некорректный $label ключ AmneziaWG"
        }
        return bytes.joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    private fun parseCidr(raw: String): IpPrefix {
        val value = raw.trim()
        val slash = value.lastIndexOf('/')
        require(slash in 1 until value.lastIndex) { "Некорректный CIDR AmneziaWG" }
        val address = value.substring(0, slash)
        val prefix = value.substring(slash + 1).toIntOrNull()
            ?: throw IllegalArgumentException("Некорректный CIDR AmneziaWG")
        val parsed = parseLiteral(address)
            ?: throw IllegalArgumentException("Некорректный CIDR AmneziaWG")
        require(
            (parsed is Inet4Address && prefix in 0..32) ||
                (parsed is Inet6Address && prefix in 0..128),
        ) { "Некорректный CIDR AmneziaWG" }
        return IpPrefix(checkNotNull(parsed.hostAddress), prefix)
    }

    private fun parseLiteral(raw: String): InetAddress? {
        val value = raw.trim()
        val ipv4Shape = value.count { it == '.' } == 3 && value.all { it.isDigit() || it == '.' }
        val ipv6Shape = ':' in value
        if (!ipv4Shape && !ipv6Shape) return null
        return runCatching { InetAddress.getByName(value) }.getOrNull()?.takeIf {
            (ipv4Shape && it is Inet4Address) || (ipv6Shape && it is Inet6Address)
        }
    }
}

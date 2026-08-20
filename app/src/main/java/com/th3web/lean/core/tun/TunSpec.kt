package com.th3web.lean.core.tun

import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull

data class IpPrefix(
    val address: String,
    val prefixLength: Int,
)

data class TunHttpProxy(
    val host: String,
    val port: Int,
    val bypassDomains: List<String>,
)

data class TunSpec(
    val mtu: Int,
    val ipv4Addresses: List<IpPrefix>,
    val ipv6Addresses: List<IpPrefix>,
    val autoRoute: Boolean,
    val ipv4Routes: List<IpPrefix>,
    val ipv6Routes: List<IpPrefix>,
    val ipv4RouteExcludes: List<IpPrefix>,
    val ipv6RouteExcludes: List<IpPrefix>,
    val strictRoute: Boolean,
    val httpProxy: TunHttpProxy?,
) {
    val ipv4Peer: String = ipv4Addresses.first().nextIpv4()
    val ipv4Dns: String = ipv4Peer
}

object TunSpecParser {
    private val json = Json { ignoreUnknownKeys = true }

    fun parse(tunOptionsJson: String, platformOptionsJson: String? = null): TunSpec {
        val options = json.parseToJsonElement(tunOptionsJson).asObject("TUN options")
        val ipv4 = options.prefixes("Inet4Address", AddressFamily.IPV4)
            .ifEmpty { listOf(IpPrefix("172.19.0.1", 30)) }
        val ipv6 = options.prefixes("Inet6Address", AddressFamily.IPV6)
        require(ipv4.none { it.prefixLength == 32 }) {
            "IPv4 TUN address /32 has no peer address"
        }
        val autoRoute = options.boolean("AutoRoute", true)
        val explicitV4Routes = options.prefixes("Inet4RouteAddress", AddressFamily.IPV4)
        val explicitV6Routes = options.prefixes("Inet6RouteAddress", AddressFamily.IPV6)
        val routesV4 = if (autoRoute && explicitV4Routes.isEmpty()) {
            listOf(IpPrefix("0.0.0.0", 0))
        } else {
            explicitV4Routes
        }
        val routesV6 = if (autoRoute && ipv6.isNotEmpty() && explicitV6Routes.isEmpty()) {
            listOf(IpPrefix("::", 0))
        } else {
            explicitV6Routes
        }
        return TunSpec(
            mtu = options.int("MTU", 9000).also { require(it in 576..65_535) { "Invalid TUN MTU: $it" } },
            ipv4Addresses = ipv4,
            ipv6Addresses = ipv6,
            autoRoute = autoRoute,
            ipv4Routes = routesV4,
            ipv6Routes = routesV6,
            ipv4RouteExcludes = options.prefixes("Inet4RouteExcludeAddress", AddressFamily.IPV4),
            ipv6RouteExcludes = options.prefixes("Inet6RouteExcludeAddress", AddressFamily.IPV6),
            strictRoute = options.boolean("StrictRoute", false),
            httpProxy = parseHttpProxy(platformOptionsJson),
        )
    }

    private fun parseHttpProxy(raw: String?): TunHttpProxy? {
        if (raw.isNullOrBlank()) return null
        val root = json.parseToJsonElement(raw).asObject("platform options")
        val proxy = root["http_proxy"] as? JsonObject ?: return null
        if (!proxy.boolean("enabled", false)) return null
        val host = proxy.string("server").ifBlank { proxy.string("host") }
        val port = proxy.int("server_port", proxy.int("port", 0))
        require(host.isNotBlank()) { "HTTP proxy server is empty" }
        require(port in 1..65_535) { "Invalid HTTP proxy port: $port" }
        return TunHttpProxy(
            host = host,
            port = port,
            bypassDomains = proxy.strings("bypass_domain"),
        )
    }

    private fun JsonObject.prefixes(key: String, family: AddressFamily): List<IpPrefix> =
        strings(key).map { parsePrefix(it, family) }

    /**
     * Reads a string / array-of-strings field, dropping JSON nulls.
     *
     * The null handling is what matters: in kotlinx.serialization `JsonNull` is a
     * [JsonPrimitive], and its `content` is the four-character string "null". So a field
     * the core legitimately serialises as null, `Inet6Address` whenever IPv6 is off,
     * arrives as the literal address "null" and fails [parsePrefix] with
     * `Invalid CIDR: null`. That throws out of the OpenTun callback, so the core cannot
     * configure the interface and no config connects at all.
     */
    private fun JsonObject.strings(key: String): List<String> = when (val element = this[key]) {
        is JsonArray -> element.mapNotNull { it.stringOrNull() }
        is JsonPrimitive -> listOfNotNull(element.stringOrNull())
        else -> emptyList()
    }

    private fun JsonElement.stringOrNull(): String? =
        (this as? JsonPrimitive)
            ?.takeUnless { it is JsonNull }
            ?.content
            ?.takeIf { it.isNotBlank() }

    private fun JsonObject.boolean(key: String, fallback: Boolean): Boolean =
        (this[key] as? JsonPrimitive)?.booleanOrNull ?: fallback

    private fun JsonObject.int(key: String, fallback: Int): Int =
        (this[key] as? JsonPrimitive)?.intOrNull ?: fallback

    /** Same JsonNull guard as [strings]: a null host must read as absent, not as "null". */
    private fun JsonObject.string(key: String): String =
        this[key]?.stringOrNull().orEmpty()

    private fun JsonElement.asObject(label: String): JsonObject =
        this as? JsonObject ?: throw IllegalArgumentException("$label must be a JSON object")

    private fun parsePrefix(raw: String, family: AddressFamily): IpPrefix {
        val slash = raw.lastIndexOf('/')
        require(slash in 1 until raw.lastIndex) { "Invalid CIDR: $raw" }
        val address = raw.substring(0, slash)
        val prefix = raw.substring(slash + 1).toIntOrNull()
            ?: throw IllegalArgumentException("Invalid CIDR prefix: $raw")
        val parsed = runCatching { InetAddress.getByName(address) }
            .getOrElse { throw IllegalArgumentException("Invalid CIDR address: $raw", it) }
        when (family) {
            AddressFamily.IPV4 -> require(parsed is Inet4Address && prefix in 0..32) {
                "Invalid IPv4 CIDR: $raw"
            }
            AddressFamily.IPV6 -> require(parsed is Inet6Address && prefix in 0..128) {
                "Invalid IPv6 CIDR: $raw"
            }
        }
        return IpPrefix(address, prefix)
    }

    private enum class AddressFamily { IPV4, IPV6 }
}

private fun IpPrefix.nextIpv4(): String {
    require(prefixLength < 32) { "IPv4 TUN address /32 has no peer address" }
    val bytes = (InetAddress.getByName(address) as Inet4Address).address
    val value = bytes.fold(0L) { result, byte -> (result shl 8) or (byte.toInt() and 0xff).toLong() }
    require(value < 0xffff_ffffL) { "IPv4 TUN address has no peer address" }
    val next = value + 1
    return listOf(24, 16, 8, 0).joinToString(".") { shift -> ((next shr shift) and 0xff).toString() }
}

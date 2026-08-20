package com.th3web.lean.core.tun

data class AwgTunSpec(
    val mtu: Int,
    val addresses: List<IpPrefix>,
    val routes: List<IpPrefix>,
    val dnsServers: List<String>,
) {
    val ipv4Addresses: List<IpPrefix> = addresses.filter { ':' !in it.address }
    val ipv6Addresses: List<IpPrefix> = addresses.filter { ':' in it.address }
}

package com.th3web.lean.data.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import com.th3web.lean.core.engine.ActiveNativeService

/**
 * The network under the tunnel, for the one request that must not go through it.
 *
 * A diagnostics report is wanted at exactly the moment the tunnel is not carrying
 * traffic: that is what there is to report. Sent over the default network it goes into
 * the tunnel, waits out its ten-second timeout and fails, so the button did nothing on
 * the devices whose logs were worth having. «Отправить» has been dead in the
 * field for that reason and the failure looks like a broken server.
 *
 * Binding the connection to a non-VPN network is the documented way out
 * ([Network.openConnection]): the socket is created on that network's routes and never
 * enters the tun. Returns null when there is nothing better than the default, with no
 * tunnel up that is the normal case and the plain connection is already correct.
 *
 * ## Which non-VPN network
 *
 * There is usually more than one. A phone with Wi-Fi connected and mobile data enabled
 * has both, and this used to take **whichever the system listed first** — an order
 * `allNetworks` does not define. On «Скорость» that produced a comparison of two
 * different links: the tunnel measured over Wi-Fi at 250 Мбит/с while «напрямую»
 * measured the cellular radio at 10–15, identically on every target, and a tunnel cannot
 * be twenty times faster than the link it runs over. Reported exactly that way.
 *
 * So the answer now comes from the tunnel itself. [ActiveNativeService.currentNetwork] is
 * the network the running VpnService was handed as its underlying one — the system's own
 * best non-VPN network, tracked by `DefaultNetworkMonitor` — which makes "the link under
 * the tunnel" literally that object rather than a guess about it. Without a service the
 * scan remains, but ranked (see [rank]) instead of taking what comes first.
 */
internal object PhysicalNetwork {

    fun find(context: Context): Network? = runCatching {
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return null
        // What the tunnel is actually running over, when a tunnel is running. Checked
        // rather than trusted: the service hands out what it last saw, and a radio can go
        // away between the callback and this call.
        ActiveNativeService.currentNetwork()
            ?.takeIf { usable(manager.getNetworkCapabilities(it)) }
            ?.let { return@runCatching it }

        // allNetworks is deprecated with no replacement for what is needed here. Every
        // supported alternative answers "which network is the DEFAULT", and while a tunnel
        // is up the default is the tunnel, the one answer that cannot be used.
        @Suppress("DEPRECATION")
        manager.allNetworks
            .mapNotNull { network ->
                manager.getNetworkCapabilities(network)?.let { caps -> network to caps }
            }
            .filter { (_, caps) -> usable(caps) }
            .maxByOrNull { (_, caps) -> rank(linkOf(caps), validated(caps)) }
            ?.first
    }.getOrNull()

    /**
     * What link [network] is, for a screen that has to say which one it measured.
     *
     * A comparison between a tunnel and a link is only readable if the link is named: the
     * bug above was invisible for exactly as long as the direct figure had no label on it.
     */
    fun describe(context: Context, network: Network?): String {
        if (network == null) return NOT_FOUND
        val manager = context.getSystemService(ConnectivityManager::class.java)
            ?: return UNKNOWN
        val caps = manager.getNetworkCapabilities(network) ?: return UNKNOWN
        return when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "мобильная сеть"
            else -> UNKNOWN
        }
    }

    /** NOT_VPN is what makes it the link under the tunnel rather than the tunnel itself. */
    private fun usable(caps: NetworkCapabilities?): Boolean =
        caps != null &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)

    private fun validated(caps: NetworkCapabilities): Boolean =
        caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)

    private fun linkOf(caps: NetworkCapabilities): Link = when {
        caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> Link.Ethernet
        caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> Link.Wifi
        caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> Link.Cellular
        else -> Link.Other
    }

    /**
     * The kinds of link that can sit under a tunnel, in the order the platform itself
     * prefers them.
     */
    internal enum class Link { Ethernet, Wifi, Cellular, Other }

    /**
     * How good a stand-in for "the default network, if the tunnel were not there" a link
     * is.
     *
     * Its own function because it is the whole of the decision that went wrong: there used
     * to be no ranking at all, just whichever network `allNetworks` listed first, which on
     * a phone with Wi-Fi up and mobile data enabled is routinely the cellular one.
     *
     * Validation outranks every transport: a Wi-Fi behind a captive portal carries
     * nothing, and a working radio is a better answer than a link that is merely more
     * desirable.
     */
    internal fun rank(link: Link, validated: Boolean): Int {
        val transport = when (link) {
            Link.Ethernet -> 4
            Link.Wifi -> 3
            Link.Cellular -> 2
            Link.Other -> 1
        }
        return (if (validated) VALIDATED_WEIGHT else 0) + transport
    }

    /** Dominates the transport order: see [rank]. */
    private const val VALIDATED_WEIGHT = 16

    internal const val NOT_FOUND = "сеть под туннелем не найдена"
    internal const val UNKNOWN = "неизвестная сеть"
}

package com.th3web.lean.data.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities

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
 */
internal object PhysicalNetwork {

    // allNetworks is deprecated with no replacement for what is needed here. Every
    // supported alternative answers "which network is the DEFAULT", and while a tunnel is
    // up the default is the tunnel, the one answer that cannot be used. Enumerating and
    // filtering is the only way to name the link underneath it.
    @Suppress("DEPRECATION")
    fun find(context: Context): Network? = runCatching {
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return null
        manager.allNetworks.firstOrNull { network ->
            val caps = manager.getNetworkCapabilities(network) ?: return@firstOrNull false
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                // NOT_VPN is what makes it the network under the tunnel rather than the
                // tunnel itself; validated keeps a link that cannot reach anything from
                // being picked over one that can.
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN) &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        }
    }.getOrNull()
}

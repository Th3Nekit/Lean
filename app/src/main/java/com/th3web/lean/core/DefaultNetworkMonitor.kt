package com.th3web.lean.core

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import com.th3web.lean.core.network.DefaultNetworkCallbackGate
import com.th3web.lean.core.network.DefaultNetworkState
import com.th3web.lean.core.network.DefaultNetworkTransition

class DefaultNetworkMonitor(context: Context) : AutoCloseable {
    private val manager = context.getSystemService(ConnectivityManager::class.java)
        ?: error("ConnectivityManager is unavailable")
    private val thread = HandlerThread("lean-default-network").apply { start() }
    private val handler = Handler(thread.looper)
    private val state = DefaultNetworkState()
    private val callbackGate = DefaultNetworkCallbackGate()
    private val lock = Any()
    private var callback: ConnectivityManager.NetworkCallback? = null
    private var callbackRegistration = 0L
    private var listener: ((Network?, DefaultNetworkTransition?) -> Unit)? = null
    private var activeNetwork: Network? = null

    fun currentNetwork(): Network? = synchronized(lock) { activeNetwork }

    fun withCurrentNetwork(network: Network, block: () -> Unit): Boolean =
        synchronized(lock) {
            if (activeNetwork !== network) false else {
                block()
                true
            }
        }

    fun start(listener: (Network?, DefaultNetworkTransition?) -> Unit): Network? {
        lateinit var networkCallback: ConnectivityManager.NetworkCallback
        val registration = synchronized(lock) {
            check(callback == null) { "Default network monitor already started" }
            this.listener = listener
            callbackGate.open()
        }
        networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                publish(registration, network, manager.getLinkProperties(network))
            }

            override fun onLinkPropertiesChanged(network: Network, properties: LinkProperties) {
                publish(registration, network, properties)
            }

            override fun onLost(network: Network) {
                val notify = synchronized(lock) {
                    if (!callbackGate.accepts(registration) ||
                        !state.lost(network.handleCompat())
                    ) {
                        return
                    }
                    activeNetwork = null
                    this@DefaultNetworkMonitor.listener
                }
                notify?.invoke(null, null)
            }
        }
        synchronized(lock) {
            callback = networkCallback
            callbackRegistration = registration
        }
        try {
            register(networkCallback)
            // Bootstrap synchronously. The registered callback only fires a few ms later,
            // but the very first dial (and AmneziaWG's endpoint resolution) happens
            // inside the bring-up that follows this call, with no known physical network
            // it has nothing to bind or protect against, so connects stall waiting for a
            // network the monitor already could have reported.
            //
            // `manager.activeNetwork` alone is not enough: once our own VpnService is up
            // it is the active network, and it is correctly rejected here as non-physical,
            // leaving us with nothing. So fall back to scanning every network for the
            // first usable internet + NOT_VPN one, exactly as the pre-migration monitor did.
            val initial = manager.activeNetwork?.takeIf(::isUsablePhysicalNetwork)
                ?: runCatching {
                    @Suppress("DEPRECATION")
                    manager.allNetworks.firstOrNull(::isUsablePhysicalNetwork)
                }.getOrNull()
            if (initial != null) {
                publish(
                    registration = registration,
                    network = initial,
                    properties = manager.getLinkProperties(initial),
                    onlyIfEmpty = true,
                )
            }
            return synchronized(lock) {
                check(callbackGate.accepts(registration)) {
                    "Default network monitor stopped during startup"
                }
                activeNetwork
            }
        } catch (error: Throwable) {
            stop()
            throw error
        }
    }

    fun stop() {
        val old = synchronized(lock) {
            val result = callback ?: return
            callback = null
            val registration = callbackRegistration
            callbackRegistration = 0L
            callbackGate.close(registration)
            listener = null
            activeNetwork = null
            state.clear()
            result
        }
        runCatching { manager.unregisterNetworkCallback(old) }
    }

    override fun close() {
        stop()
        thread.quitSafely()
    }

    private fun publish(
        registration: Long,
        network: Network,
        properties: LinkProperties?,
        onlyIfEmpty: Boolean = false,
    ) {
        if (!isUsablePhysicalNetwork(network)) return
        val interfaceName = properties?.interfaceName?.takeIf(String::isNotBlank) ?: return
        // The addresses are the point of listening to link properties at all: this callback
        // fires on every address change, and an address the core's sockets were bound to
        // going away is indistinguishable (from anywhere else in the app) from a network
        // that is simply quiet.
        val addresses = properties?.linkAddresses.orEmpty()
            .mapNotNull { it?.address?.hostAddress?.takeIf(String::isNotBlank) }
            .toSet()
        val result = synchronized(lock) {
            if (!callbackGate.accepts(registration) || onlyIfEmpty && activeNetwork != null) return
            val transition = state.update(network.handleCompat(), interfaceName, addresses)
            if (transition == DefaultNetworkTransition.Duplicate) return
            activeNetwork = network
            listener to transition
        }
        result.first?.invoke(network, result.second)
    }

    private fun isUsablePhysicalNetwork(network: Network): Boolean {
        val capabilities = manager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
    }

    private fun register(networkCallback: ConnectivityManager.NetworkCallback) {
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .build()
        when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
                manager.registerBestMatchingNetworkCallback(request, networkCallback, handler)

            Build.VERSION.SDK_INT >= Build.VERSION_CODES.P ->
                manager.requestNetwork(request, networkCallback, handler)

            Build.VERSION.SDK_INT >= Build.VERSION_CODES.O ->
                manager.registerDefaultNetworkCallback(networkCallback, handler)

            else ->
                manager.registerDefaultNetworkCallback(networkCallback)
        }
    }
}

private fun Network.handleCompat(): Long = networkHandle

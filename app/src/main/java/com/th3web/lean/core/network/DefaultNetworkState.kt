package com.th3web.lean.core.network

/**
 * What makes one usable link different from another.
 *
 * [addresses] is part of the identity, and leaving it out was a real outage. A phone that
 * stays on the same mobile network for hours does not keep the same address: the carrier
 * re-assigns on re-attach, an IPv6 prefix rotates, a Wi-Fi lease is renewed onto a
 * different address. None of that changes the Network object or the interface name, so
 * `onLinkPropertiesChanged` arrived with a key identical to the last one and was dropped
 * as a duplicate, while every socket the core had open was bound to an address that no
 * longer existed. Nothing was sent, nothing came back, and the tunnel went on reporting
 * «подключено» until its owner reconnected by hand.
 */
data class DefaultNetworkKey(
    val handle: Long,
    val interfaceName: String,
    val addresses: Set<String> = emptySet(),
)

sealed interface DefaultNetworkTransition {
    data class Initial(val current: DefaultNetworkKey) : DefaultNetworkTransition

    data class Handover(
        val previous: DefaultNetworkKey,
        val current: DefaultNetworkKey,
    ) : DefaultNetworkTransition

    data object Duplicate : DefaultNetworkTransition
}

enum class DefaultNetworkRegistration {
    DEFAULT,
    DEFAULT_WITH_HANDLER,
    REQUEST,
    BEST_MATCHING,
}

fun defaultNetworkRegistration(sdk: Int): DefaultNetworkRegistration = when {
    sdk >= 31 -> DefaultNetworkRegistration.BEST_MATCHING
    sdk >= 28 -> DefaultNetworkRegistration.REQUEST
    sdk >= 26 -> DefaultNetworkRegistration.DEFAULT_WITH_HANDLER
    else -> DefaultNetworkRegistration.DEFAULT
}

class DefaultNetworkCallbackGate {
    private var sequence = 0L
    private var current = 0L

    @Synchronized
    fun open(): Long = (++sequence).also { current = it }

    @Synchronized
    fun accepts(registration: Long): Boolean =
        registration != 0L && registration == current

    @Synchronized
    fun close(registration: Long) {
        if (current == registration) current = 0L
    }
}

class DefaultNetworkState {
    var current: DefaultNetworkKey? = null
        private set

    /**
     * The network we were on before it was lost, kept until something replaces it.
     *
     * Without this, a break-before-make change reads as a first connection rather than as
     * a change: `lost()` cleared `current`, so the next `available()` saw no previous key
     * and returned [DefaultNetworkTransition.Initial]. Only Handover triggers
     * `resetAllConnections`, so on the ordinary case, Wi-Fi drops, LTE takes over a
     * moment later, nothing reset the core's sockets. They stayed bound to the dead
     * interface and every write failed with "network is unreachable" (372 of them in one
     * captured session), while the UI still read «подключено».
     *
     * Make-before-break (the new network arrives first) was never affected, which is why
     * this survived: switching networks by hand, with both up, worked fine.
     */
    private var lastLost: DefaultNetworkKey? = null

    fun available(
        handle: Long,
        interfaceName: String,
        addresses: Set<String> = emptySet(),
    ): Boolean {
        return update(handle, interfaceName, addresses) != DefaultNetworkTransition.Duplicate
    }

    fun update(
        handle: Long,
        interfaceName: String,
        addresses: Set<String> = emptySet(),
    ): DefaultNetworkTransition {
        val next = DefaultNetworkKey(handle, interfaceName, addresses)
        val live = current
        if (live != null && live.handle == handle && live.interfaceName == interfaceName) {
            // Same link, so the only question is whether it still has the addresses the
            // core's sockets are bound to. Gaining one breaks nothing and must stay a
            // duplicate, Android announces addresses in stages, and a reset per stage
            // would drop every connection during ordinary bring-up. losing one is the
            // handover: those sockets are dead and only a re-dial can replace them.
            //
            // An empty set is "not reported", never "all gone": link properties arrive
            // without addresses often enough that reading it as a loss would reset a
            // healthy tunnel repeatedly.
            val lostAddress = addresses.isNotEmpty() && live.addresses.any { it !in addresses }
            if (addresses.isNotEmpty()) current = next
            if (!lostAddress) return DefaultNetworkTransition.Duplicate
            lastLost = null
            return DefaultNetworkTransition.Handover(previous = live, current = next)
        }

        val previous = live ?: lastLost
        current = next
        lastLost = null
        return if (previous == null) {
            DefaultNetworkTransition.Initial(next)
        } else {
            // Note this covers "the same network came back after being lost" too, and
            // that is a handover: the interface went down, so every socket bound to it is
            // dead whether or not the key matches.
            DefaultNetworkTransition.Handover(previous, next)
        }
    }

    fun lost(handle: Long): Boolean {
        if (current?.handle != handle) return false
        lastLost = current
        current = null
        return true
    }

    fun clear() {
        current = null
        lastLost = null
    }
}

package com.th3web.lean.core.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DefaultNetworkStateTest {
    @Test
    fun `late loss of old network keeps replacement current`() {
        val state = DefaultNetworkState()

        assertTrue(state.available(handle = 10, interfaceName = "wlan0"))
        assertTrue(state.available(handle = 20, interfaceName = "rmnet0"))
        assertFalse(state.lost(handle = 10))

        assertEquals(DefaultNetworkKey(20, "rmnet0"), state.current)
    }

    @Test
    fun `duplicate callbacks do not report another handover`() {
        val state = DefaultNetworkState()

        assertTrue(state.available(handle = 10, interfaceName = "wlan0"))
        assertFalse(state.available(handle = 10, interfaceName = "wlan0"))
        assertFalse(state.available(handle = 10, interfaceName = "wlan0"))
    }

    /**
     * The outage this class was rewritten for. A phone that never leaves LTE still changes
     * address — re-attach, prefix rotation, a renewed lease — and the Network object and
     * interface name stay exactly the same. Read as a duplicate, nothing was reset, and
     * every socket the core held stayed bound to an address that no longer existed: no
     * traffic in either direction, state still «подключено», and no way out but a manual
     * reconnect.
     */
    @Test
    fun `losing the address on the same interface is a handover`() {
        val state = DefaultNetworkState()

        assertTrue(
            state.available(20, "rmnet_data0", setOf("10.2.3.4", "fe80::1")),
        )
        assertTrue(
            "the address the sockets were bound to is gone — they are dead",
            state.available(20, "rmnet_data0", setOf("10.9.9.9", "fe80::1")),
        )
        assertEquals(
            DefaultNetworkKey(20, "rmnet_data0", setOf("10.9.9.9", "fe80::1")),
            state.current,
        )
    }

    /**
     * Gaining one is not. Android announces addresses in stages — IPv4 first, IPv6 a
     * moment later — and resetting every connection at each stage would break bring-up for
     * no reason at all.
     */
    @Test
    fun `gaining an address is not a handover`() {
        val state = DefaultNetworkState()

        assertTrue(state.available(20, "rmnet_data0", setOf("10.2.3.4")))
        assertFalse(state.available(20, "rmnet_data0", setOf("10.2.3.4", "2a00::5")))
        assertEquals(
            "the newest set is still what we compare against next time",
            DefaultNetworkKey(20, "rmnet_data0", setOf("10.2.3.4", "2a00::5")),
            state.current,
        )
    }

    /** Link properties with no addresses at all means "not reported", never "all gone". */
    @Test
    fun `an empty address list is not read as a loss`() {
        val state = DefaultNetworkState()

        assertTrue(state.available(20, "rmnet_data0", setOf("10.2.3.4")))
        assertFalse(state.available(20, "rmnet_data0", emptySet()))
        assertEquals(
            "and it must not erase what we knew",
            DefaultNetworkKey(20, "rmnet_data0", setOf("10.2.3.4")),
            state.current,
        )
    }

    @Test
    fun `new handle on same interface is a real handover`() {
        val state = DefaultNetworkState()

        assertTrue(state.available(handle = 10, interfaceName = "wlan0"))
        assertTrue(state.available(handle = 11, interfaceName = "wlan0"))

        assertEquals(DefaultNetworkKey(11, "wlan0"), state.current)
    }

    @Test
    fun `loss of current network clears state once`() {
        val state = DefaultNetworkState()
        state.available(handle = 10, interfaceName = "wlan0")

        assertTrue(state.lost(handle = 10))
        assertNull(state.current)
        assertFalse(state.lost(handle = 10))
    }

    @Test
    fun `clear resets current state`() {
        val state = DefaultNetworkState()
        state.available(handle = 10, interfaceName = "wlan0")

        state.clear()

        assertNull(state.current)
    }

    @Test
    fun `registration follows supported API boundaries`() {
        assertEquals(DefaultNetworkRegistration.DEFAULT, defaultNetworkRegistration(24))
        assertEquals(DefaultNetworkRegistration.DEFAULT_WITH_HANDLER, defaultNetworkRegistration(26))
        assertEquals(DefaultNetworkRegistration.DEFAULT_WITH_HANDLER, defaultNetworkRegistration(27))
        assertEquals(DefaultNetworkRegistration.REQUEST, defaultNetworkRegistration(28))
        assertEquals(DefaultNetworkRegistration.REQUEST, defaultNetworkRegistration(30))
        assertEquals(DefaultNetworkRegistration.BEST_MATCHING, defaultNetworkRegistration(31))
    }

    @Test
    fun `first network is initial and replacement is handover`() {
        val state = DefaultNetworkState()

        assertEquals(
            DefaultNetworkTransition.Initial(DefaultNetworkKey(10, "wlan0")),
            state.update(handle = 10, interfaceName = "wlan0"),
        )
        assertEquals(
            DefaultNetworkTransition.Duplicate,
            state.update(handle = 10, interfaceName = "wlan0"),
        )
        assertEquals(
            DefaultNetworkTransition.Handover(
                previous = DefaultNetworkKey(10, "wlan0"),
                current = DefaultNetworkKey(20, "rmnet0"),
            ),
            state.update(handle = 20, interfaceName = "rmnet0"),
        )
    }

    /**
     * The break-before-make case, and the one that mattered in practice: Wi-Fi drops
     * first, LTE arrives a moment later.
     *
     * This used to report Initial, because `lost()` cleared the only record of where we
     * had been. Only Handover triggers resetAllConnections, so the core kept using
     * sockets bound to the dead interface and every write failed with "network is
     * unreachable" while the UI still said «подключено». Make-before-break (both up at
     * once) always reported Handover correctly, which is why switching networks by hand
     * looked fine.
     */
    @Test
    fun `network lost then replaced is a handover, not a first connection`() {
        val state = DefaultNetworkState()
        state.update(handle = 10, interfaceName = "wlan0")

        assertTrue(state.lost(handle = 10))

        assertEquals(
            DefaultNetworkTransition.Handover(
                previous = DefaultNetworkKey(10, "wlan0"),
                current = DefaultNetworkKey(20, "rmnet0"),
            ),
            state.update(handle = 20, interfaceName = "rmnet0"),
        )
    }

    /** Re-appearing as the SAME network after a loss is still a handover: the interface
     * went down, so anything bound to it is dead regardless of the key matching. */
    @Test
    fun `same network returning after a loss is still a handover`() {
        val state = DefaultNetworkState()
        state.update(handle = 10, interfaceName = "wlan0")
        state.lost(handle = 10)

        assertEquals(
            DefaultNetworkTransition.Handover(
                previous = DefaultNetworkKey(10, "wlan0"),
                current = DefaultNetworkKey(10, "wlan0"),
            ),
            state.update(handle = 10, interfaceName = "wlan0"),
        )
    }

    /** A genuinely first network after an explicit clear must NOT look like a handover. */
    @Test
    fun `clear forgets the lost network too`() {
        val state = DefaultNetworkState()
        state.update(handle = 10, interfaceName = "wlan0")
        state.lost(handle = 10)

        state.clear()

        assertEquals(
            DefaultNetworkTransition.Initial(DefaultNetworkKey(20, "rmnet0")),
            state.update(handle = 20, interfaceName = "rmnet0"),
        )
    }

    @Test
    fun `callbacks from a closed registration cannot enter a new session`() {
        val gate = DefaultNetworkCallbackGate()
        val first = gate.open()

        gate.close(first)
        val second = gate.open()

        assertFalse(gate.accepts(first))
        assertTrue(gate.accepts(second))
    }

    @Test
    fun `closing stale registration does not close current session`() {
        val gate = DefaultNetworkCallbackGate()
        val first = gate.open()
        val second = gate.open()

        gate.close(first)

        assertTrue(gate.accepts(second))
    }
}

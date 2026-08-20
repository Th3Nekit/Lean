package com.th3web.lean.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import com.th3web.lean.core.engine.NekoBox

/**
 * The three ways idle state, the switch and the core's lifetime can be out of step.
 *
 * Every one of them ends the same way if it is wrong: a core left paused, which is a
 * tunnel that connects and then carries nothing — the exact failure this feature caused
 * when it was unconditional and keyed on the screen.
 */
class DozePauseTest {

    /** Only the two calls matter here; the rest of the surface is inert. */
    private class FakeBox : NekoBox {
        val calls = mutableListOf<String>()
        override fun sleep() { calls += "sleep" }
        override fun wake() { calls += "wake" }
        override fun setAsMain() = Unit
        override fun setV2rayStats(tags: String) = Unit
        override fun start() = Unit
        override fun close() = Unit
        override fun queryStats(tag: String, direction: String): Long = 0
        override fun selectOutbound(tag: String): Boolean = true
    }

    private var enabled = true
    private var box: FakeBox? = FakeBox()
    private fun pause() = DozePause(box = { box }, enabled = { enabled })

    @Test
    fun `with the switch off Doze changes nothing`() {
        enabled = false
        val doze = pause()

        doze.idleChanged(deviceIdle = true)

        assertFalse(doze.paused)
        assertEquals(emptyList<String>(), box?.calls)
    }

    @Test
    fun `with the switch on it follows Doze in and out`() {
        val doze = pause()

        doze.idleChanged(deviceIdle = true)
        assertTrue(doze.paused)
        doze.idleChanged(deviceIdle = false)
        assertFalse(doze.paused)

        assertEquals(listOf("sleep", "wake"), box?.calls)
    }

    /**
     * Turning the switch off has to wake a core that is paused RIGHT NOW. Without this the
     * switch appears dead: nothing changes until the device next leaves Doze, which for a
     * phone lying on a desk can be hours — and the user is toggling it precisely because
     * the tunnel has stopped working.
     */
    @Test
    fun `turning the switch off wakes a core that is already asleep`() {
        val doze = pause()
        doze.idleChanged(deviceIdle = true)
        assertTrue(doze.paused)

        enabled = false
        doze.settingChanged()

        assertFalse(doze.paused)
        assertEquals(listOf("sleep", "wake"), box?.calls)
    }

    /** And turning it on mid-Doze applies at once rather than waiting for the next entry. */
    @Test
    fun `turning the switch on while already dozing takes effect`() {
        enabled = false
        val doze = pause()
        doze.idleChanged(deviceIdle = true)
        assertEquals(emptyList<String>(), box?.calls)

        enabled = true
        doze.settingChanged()

        assertTrue(doze.paused)
        assertEquals(listOf("sleep"), box?.calls)
    }

    /**
     * A tunnel can come up with the device already dozing — an always-on VPN restarting the
     * service, a scheduled reconnect — and no broadcast is coming in that case.
     */
    @Test
    fun `a core that starts during Doze is told about it`() {
        val doze = pause()

        doze.coreStarted(deviceIdle = true)

        assertTrue(doze.paused)
        assertEquals(listOf("sleep"), box?.calls)
    }

    /**
     * The state must not survive the core it described. A fresh core is awake, and a stale
     * `paused = true` would make the next wake() a no-op — leaving the new tunnel paused
     * with nothing left to un-pause it.
     */
    @Test
    fun `a new core does not inherit the old one's pause`() {
        val doze = pause()
        doze.coreStarted(deviceIdle = true)
        doze.coreStopped()
        assertFalse(doze.paused)

        box = FakeBox()
        doze.coreStarted(deviceIdle = false)

        assertFalse(doze.paused)
        assertEquals(emptyList<String>(), box?.calls)
    }

    /** No core yet, or one being closed on another thread: never throw from a broadcast. */
    @Test
    fun `it survives having no core to talk to`() {
        box = null
        val doze = pause()

        doze.idleChanged(deviceIdle = true)

        assertTrue("state still tracks, it just cannot deliver", doze.paused)
    }
}

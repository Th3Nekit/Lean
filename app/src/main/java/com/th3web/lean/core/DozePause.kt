package com.th3web.lean.core

import com.th3web.lean.core.engine.NekoBox

/**
 * Tells the core when the system has put the device into Doze, if the user asked for it.
 *
 * ## What this costs, stated plainly
 *
 * Pausing is not free. sing-box's pause manager holds new connections until the device
 * wakes, so a paused core carries nothing: nothing
 * connects, nothing resolves, a message does not arrive. What it buys is the radio staying
 * idle, no urltest sweep (32 proxied probes a minute on a large subscription), no
 * rule-set refresh, no reconnect back-off spin.
 *
 * That trade belongs to the user, so it is off by default and lives behind a switch. It
 * shipped once as an unconditional behaviour keyed on SCREEN_OFF, and the result was a
 * phone with no tunnel in a pocket, reported as "все конфиги сломаны".
 *
 * ## Why Doze and not the screen
 *
 * A dark screen is not an idle phone: it is also music playing, a download running, a
 * call, a hotspot. Doze is the system's own judgement that the device is genuinely
 * unattended: it takes minutes of stillness to enter and it breaks the moment the device
 * is picked up. It is the signal upstream's own clients use, and it is the only one whose
 * meaning matches what this does.
 *
 * Kept apart from [LeanVpnService] because the interesting part is not the broadcast, it
 * is the ways idle state, the setting and the core's lifetime can be out of step, and that
 * is worth testing without standing up a VpnService.
 */
internal class DozePause(
    private val box: () -> NekoBox?,
    private val enabled: () -> Boolean,
) {

    /** What the core has been told. False whenever the setting is off. */
    @Volatile
    var paused: Boolean = false
        private set

    private var idle = false

    fun idleChanged(deviceIdle: Boolean) {
        idle = deviceIdle
        apply()
    }

    /**
     * The setting was just toggled. Turning it OFF has to wake a core that is currently
     * paused, otherwise the switch would appear to do nothing until the device next left
     * Doze, which for a phone on a desk can be hours.
     */
    fun settingChanged() = apply()

    /**
     * A core has just started; [deviceIdle] is the system's idle state right now.
     *
     * Read fresh rather than trusted from [idle], because a tunnel can come up with the
     * device already dozing, an always-on VPN restarting the service, a scheduled
     * reconnect, and no broadcast is coming in that case.
     */
    fun coreStarted(deviceIdle: Boolean) {
        idle = deviceIdle
        paused = false
        apply()
    }

    /** The core is gone; nothing is paused any more. */
    fun coreStopped() {
        paused = false
    }

    private fun apply() {
        val wanted = idle && enabled()
        if (wanted == paused) return
        paused = wanted
        // Never throws. This runs on the main thread from a broadcast, while the box
        // underneath is a native instance another thread may be closing at that moment; a
        // failure to pause is not worth taking the service down for.
        val current = box() ?: return
        runCatching { if (wanted) current.sleep() else current.wake() }
    }
}

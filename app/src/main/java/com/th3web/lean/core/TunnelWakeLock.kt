package com.th3web.lean.core

import android.content.Context
import android.os.PowerManager

/**
 * Keeps the CPU from sleeping while a tunnel is up, when the user asks for it.
 *
 * ## Why a VPN needs this at all
 *
 * A foreground service survives being backgrounded; it does not survive the CPU going to
 * sleep. In Doze (and in the deeper vendor sleep states layered on top of it) timers are
 * coalesced into maintenance windows minutes apart, so the core's own clockwork — TCP
 * keepalives, the QUIC/Hysteria heartbeat, WireGuard's rekey, DNS cache expiry, the
 * urltest sweep — simply does not run. What the far end sees is a peer that stopped
 * answering, and it closes the session. What the user sees is a tunnel that was connected
 * when they put the phone down and is dead when they pick it up, with no event in between:
 * «рандомный дисконнект».
 *
 * A partial wake lock is the sanctioned way to say "this work must keep ticking". It is
 * what the reference clients in this ecosystem hold for the same reason.
 *
 * ## Why it is a setting and not unconditional
 *
 * It costs battery, and the cost is real: the CPU cannot enter its deep idle states for
 * as long as the tunnel is up. Some phones and some users would rather have the
 * occasional drop. So it is a switch, defaulting on, because a VPN that quietly stops
 * carrying traffic is a worse default than one that uses more battery, and because the
 * drop is the thing people report.
 *
 * ## Relationship to [DozePause]
 *
 * They are opposites, and both can be set. [DozePause] asks the core to stop working
 * while the device is idle (saving battery, carrying nothing); this asks the device to
 * keep the core working. With both on, the pause wins for as long as Doze lasts: the core
 * is paused, so there is nothing for the wake lock to keep alive. That combination is
 * contradictory but harmless, and the two screens say what each one does.
 *
 * Every call is best-effort and must never throw: this runs on the service's lifecycle
 * path, and a device that refuses a wake lock is not a reason to fail a connection.
 */
internal class TunnelWakeLock(
    context: Context,
    private val enabled: () -> Boolean,
) {
    private val power = runCatching { context.getSystemService(PowerManager::class.java) }.getOrNull()

    /**
     * Not `by lazy`: the lock is only ever touched from the service's own paths, and a
     * device whose PowerManager is unavailable leaves this null for the whole lifetime
     * rather than retrying on every call.
     */
    private val lock: PowerManager.WakeLock? = runCatching {
        power?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, TAG)?.apply { setReferenceCounted(false) }
    }.getOrNull()

    /** Whether a tunnel is currently supposed to be held awake. */
    private var wanted = false

    /** A session came up. Takes the lock if the setting allows it. */
    fun sessionStarted() {
        wanted = true
        apply()
    }

    /** The session is gone. Always releases, whatever the setting says. */
    fun sessionStopped() {
        wanted = false
        apply()
    }

    /**
     * The setting was toggled while a session may be running. Turning it off has to
     * release a lock that is held right now, or the switch would appear to do nothing
     * until the next disconnect.
     */
    fun settingChanged() = apply()

    /** Held right now, for the service's own log line and for tests. */
    val held: Boolean get() = runCatching { lock?.isHeld == true }.getOrDefault(false)

    private fun apply() {
        val current = lock ?: return
        val shouldHold = wanted && enabled()
        runCatching {
            if (shouldHold) {
                if (!current.isHeld) current.acquire()
            } else {
                if (current.isHeld) current.release()
            }
        }
    }

    private companion object {
        /**
         * Shows up in `dumpsys power` and in battery attribution, so it names the app and
         * the reason rather than a class.
         */
        const val TAG = "lean:tunnel"
    }
}

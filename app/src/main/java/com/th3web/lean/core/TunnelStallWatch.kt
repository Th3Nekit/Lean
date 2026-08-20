package com.th3web.lean.core

/**
 * Notices a tunnel that is still "connected" and has stopped carrying anything.
 *
 * The failure it exists for is the one people describe as «зависает через какое-то
 * время»: the state stays Connected, the notification stays up, and nothing loads. The
 * traffic counters say it plainly: apps keep writing into the tun, so the uplink total
 * climbs, while the downlink total has not moved for a long time. Nothing was watching
 * for that, so the only way out was for the user to notice and reconnect by hand.
 *
 * The condition is narrow, because the expensive mistake here is a false
 * positive on an idle tunnel:
 *
 *  * a phone in a pocket with nothing to say has both totals flat: that is idle, not
 *    stalled, and it must never trigger;
 *  * a download in progress moves the downlink, obviously fine;
 *  * only "we are sending and nothing at all is coming back, for a long time" is a wedge.
 *
 * There are two ways to establish that something was asked of the tunnel, and the second
 * is not optional. Byte counters are read per outbound, so they count only what a
 * connection carried after it was established. A wedge is upstream of that: the shared
 * session to the server is dead, every new connection piles onto a dial that never
 * completes, and not one byte is attributed to the outbound meanwhile. Both totals sit
 * still, by bytes alone, indistinguishable from a phone in a pocket. What separates them
 * is the core's own account: a run of connections failing with "no recent network
 * activity" or "operation was canceled" is demand the counters cannot show.
 *
 * One shot per episode: after firing it waits for the downlink to move again before it
 * will arm normally, so a working tunnel with one bad minute is never reset twice for the
 * same minute. It does not give up on a tunnel that stays wedged, though. If the downlink never
 * comes back it arms again after [REARM_AFTER_MS], since the alternative is a phone that
 * carries nothing until its owner notices. Worst case is one reset every few minutes.
 */
internal class TunnelStallWatch(
    private val stallAfterMs: Long = STALL_AFTER_MS,
    private val minUplinkBytes: Long = MIN_UPLINK_BYTES,
    private val minFailures: Long = MIN_FAILURES,
    private val rearmAfterMs: Long = REARM_AFTER_MS,
) {
    private var lastUplink = -1L
    private var lastDownlink = -1L
    private var lastFailures = 0L
    private var downlinkMovedAt = 0L
    private var uplinkSinceDownlink = 0L
    private var failuresSinceDownlink = 0L
    private var firedAt = 0L
    private var armed = true

    /**
     * Feeds one reading of the running totals. Returns true exactly once per stall, at the
     * moment the caller should try to un-wedge the tunnel.
     *
     * Totals that go backwards mean a fresh core reusing the counters, so the watch starts
     * over rather than reading the drop as a stall.
     */
    fun sample(
        uplinkTotal: Long,
        downlinkTotal: Long,
        failureTotal: Long,
        nowNanos: Long,
    ): Boolean {
        if (lastUplink < 0 ||
            uplinkTotal < lastUplink ||
            downlinkTotal < lastDownlink ||
            failureTotal < lastFailures
        ) {
            reset(uplinkTotal, downlinkTotal, failureTotal, nowNanos)
            return false
        }
        if (downlinkTotal > lastDownlink) {
            lastDownlink = downlinkTotal
            downlinkMovedAt = nowNanos
            uplinkSinceDownlink = 0
            failuresSinceDownlink = 0
            lastUplink = uplinkTotal
            lastFailures = failureTotal
            armed = true
            return false
        }
        uplinkSinceDownlink += uplinkTotal - lastUplink
        failuresSinceDownlink += failureTotal - lastFailures
        lastUplink = uplinkTotal
        lastFailures = failureTotal
        if (!armed) {
            if (nowNanos - firedAt < rearmAfterMs * 1_000_000L) return false
            // Still wedged, and long enough since the last attempt to try once more. The
            // window starts over from here so the next shot needs its own evidence.
            armed = true
            downlinkMovedAt = nowNanos
            uplinkSinceDownlink = 0
            failuresSinceDownlink = 0
            return false
        }
        // Enough sent (or enough refused) to rule out "nothing was asked of it". A
        // handful of bytes is a keepalive or a retransmit; a stalled tunnel accumulates
        // real requests that are never answered, and when the wedge is in the dial itself
        // those requests show up only as failures.
        if (uplinkSinceDownlink < minUplinkBytes && failuresSinceDownlink < minFailures) {
            return false
        }
        if (nowNanos - downlinkMovedAt < stallAfterMs * 1_000_000L) return false
        armed = false
        firedAt = nowNanos
        return true
    }

    /** A new core, or a new session: forget everything measured about the old one. */
    fun reset(
        uplinkTotal: Long = 0,
        downlinkTotal: Long = 0,
        failureTotal: Long = 0,
        nowNanos: Long = 0,
    ) {
        lastUplink = uplinkTotal
        lastDownlink = downlinkTotal
        lastFailures = failureTotal
        downlinkMovedAt = nowNanos
        uplinkSinceDownlink = 0
        failuresSinceDownlink = 0
        firedAt = nowNanos
        armed = true
    }

    companion object {
        /**
         * Long enough that a slow page, a dead server being retried, or a lull between
         * requests never reaches it; short enough that a user has not yet given up and
         * reconnected by hand.
         */
        const val STALL_AFTER_MS = 45_000L

        /** Sent without a single byte back before this is called a stall rather than a lull. */
        const val MIN_UPLINK_BYTES = 16 * 1024L

        /**
         * Connections the core gave up on, within one window, before their number counts
         * as demand in its own right. One is an unreachable host; a handful in a row with
         * nothing coming back is the tunnel.
         */
        const val MIN_FAILURES = 4L

        /**
         * How long a tunnel that did not recover from a reset is left alone before
         * trying again. Long, because a reset that did not help is evidence the problem is
         * elsewhere; not forever, because the phone may have changed networks under a
         * tunnel that was never told.
         */
        const val REARM_AFTER_MS = 4L * 60 * 1_000
    }
}

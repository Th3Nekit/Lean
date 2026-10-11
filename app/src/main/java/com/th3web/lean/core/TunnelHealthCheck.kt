package com.th3web.lean.core

/**
 * Decides what to do about a tunnel that has stopped answering, and escalates.
 *
 * ## What this covers that [TunnelStallWatch] does not
 *
 * [TunnelStallWatch] reads the byte counters: it fires when apps are visibly asking the
 * tunnel for something and nothing comes back. That is the right signal for a phone in
 * use, and it is blind in exactly the case people report most: a phone that was idle. On
 * an idle device nothing is being asked, so no counter moves, and a tunnel whose session
 * the far end (or a DPI box) tore down looks identical to a healthy one. The user finds
 * out by picking the phone up and discovering nothing loads.
 *
 * So this one asks instead of watching: when the tunnel has received nothing for a while,
 * a single generate_204 goes through the live core's own outbound. That answers the only
 * question that matters — does this tunnel still carry traffic — and it answers it before
 * the user does.
 *
 * ## Escalation, because the cheap fix often is enough
 *
 * One failed probe is not evidence: a lost packet, a server hiccup, a captive portal
 * blink. The ladder is therefore
 *
 *  * [Action.None] until [failuresBeforeReset] probes in a row have failed;
 *  * [Action.Reset] then — the same `resetNetwork()` a handover performs: sockets closed,
 *    outbounds re-bound, DNS cache and transports dropped. It keeps the tunnel and the
 *    user's session, and it is what fixes a wedge whose cause was the underlying link
 *    changing under a core that was never told;
 *  * [Action.Restart] once [failuresBeforeRestart] is reached — the reset did not help,
 *    so the core itself is rebuilt. This is visible to the user (a reconnect), which is
 *    why it is last.
 *
 * After a restart the counter starts over: the new session has to earn its own verdict.
 *
 * Pure logic, no Android and no core, so the escalation can be tested without a tunnel —
 * the same reason [FailoverPolicy] and [TunnelStallWatch] are shaped this way.
 */
internal class TunnelHealthCheck(
    private val failuresBeforeReset: Int = FAILURES_BEFORE_RESET,
    private val failuresBeforeRestart: Int = FAILURES_BEFORE_RESTART,
) {
    sealed interface Action {
        /** Nothing to do: either the probe passed, or not enough has failed yet. */
        data object None : Action
        /** Drop sockets, re-bind outbounds, clear DNS. Invisible to the user. */
        data object Reset : Action
        /** Rebuild the core. The user sees a reconnect. */
        data object Restart : Action
    }

    private var consecutiveFailures = 0

    /** How many probes in a row have failed. For the log line, and for tests. */
    val failures: Int get() = consecutiveFailures

    /**
     * Feeds one probe result and returns what the caller should do about it.
     *
     * A pass clears the history outright: a tunnel that answered is healthy, whatever it
     * did a minute ago, and carrying a stale failure forward would make the next isolated
     * blip trigger a reset the tunnel never needed.
     */
    fun onProbe(ok: Boolean): Action {
        if (ok) {
            consecutiveFailures = 0
            return Action.None
        }
        consecutiveFailures++
        return when {
            consecutiveFailures >= failuresBeforeRestart -> {
                // The next session is judged on its own evidence.
                consecutiveFailures = 0
                Action.Restart
            }
            consecutiveFailures == failuresBeforeReset -> Action.Reset
            else -> Action.None
        }
    }

    /** A new core, or a session the caller gave up on: forget the history. */
    fun reset() {
        consecutiveFailures = 0
    }

    companion object {
        /**
         * Two in a row before touching anything. One failure is routine on a mobile
         * link; two, minutes apart, is not.
         */
        const val FAILURES_BEFORE_RESET = 2

        /**
         * Four before rebuilding the core, i.e. two more probes after the reset was
         * given its chance.
         */
        const val FAILURES_BEFORE_RESTART = 4

        /**
         * How long the tunnel must have received nothing before a probe is worth
         * sending, while someone is looking at the app.
         *
         * Not a fixed interval: a tunnel that is carrying traffic has already answered
         * the question, so probing it would be pure cost. Only silence is interesting.
         */
        const val IDLE_BEFORE_PROBE_MS = 120_000L

        /** The same, with the screen away: the same check, a third as often. */
        const val IDLE_BEFORE_PROBE_IDLE_MS = 360_000L

        /** Deadline for the probe itself. Generous: a slow answer is still an answer. */
        const val PROBE_TIMEOUT_MS = 8_000
    }
}

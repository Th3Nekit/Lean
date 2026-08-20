package com.th3web.lean.core

import com.th3web.lean.data.model.Profile

/**
 * «Автопереключение (Beta)», what to do when a connection will not stay up.
 *
 * Pure decision logic, separated from the service so it can be reasoned
 * about and tested without a tunnel: given which profile just failed, how many times it
 * has failed, and what else is available, it answers with the next move and nothing else.
 *
 * The shape of the policy is "retry the thing the user chose, then move on":
 *  - the first [RETRIES_BEFORE_SWITCH] failures retry the same profile, because the
 *    common cause is a transient network blip and switching servers over one of those
 *    would be worse than waiting a second,
 *  - after that it moves to the next candidate, best-measured-latency first, skipping
 *    ones already tried this round and ones excluded from testing,
 *  - once every candidate has been tried it gives up rather than looping forever.
 */
internal object FailoverPolicy {

    /** Same-profile retries before the policy starts looking elsewhere. */
    const val RETRIES_BEFORE_SWITCH = 2

    /** Backoff before the next attempt; widens so a hard outage stops burning battery. */
    fun delayMs(attempt: Int): Long = when {
        attempt <= 1 -> 1_500L
        attempt == 2 -> 4_000L
        else -> 10_000L
    }

    sealed interface Move {
        /** Try the same profile again. */
        data class Retry(val profileId: String) : Move
        /** Give up on [from] and connect [to] instead. */
        data class Switch(val from: String, val to: String) : Move
        /** Nothing left worth trying. */
        data object Stop : Move
    }

    /**
     * [failed] is the profile that just dropped, [attempts] how many times it has failed
     * in this round (1 on the first failure), [tried] every profile already attempted in
     * this round, and [candidates] the servers available to switch to.
     */
    fun next(
        failed: String,
        attempts: Int,
        tried: Set<String>,
        candidates: List<Profile>,
    ): Move {
        if (attempts <= RETRIES_BEFORE_SWITCH) return Move.Retry(failed)
        val next = candidates
            .asSequence()
            .filterNot { it.id == failed || it.id in tried }
            .filterNot { it.excludedFromTest }
            // Best measured latency first; unmeasured (null), and unreachable (-1) last,
            // since a server we know nothing about is a worse bet than one we timed.
            .sortedBy { p -> p.latencyMs?.takeIf { it >= 0 } ?: Int.MAX_VALUE }
            .firstOrNull()
            ?: return Move.Stop
        return Move.Switch(from = failed, to = next.id)
    }
}

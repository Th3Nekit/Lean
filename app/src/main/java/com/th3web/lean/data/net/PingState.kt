package com.th3web.lean.data.net

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Which servers are being probed at this instant.
 *
 * Shared rather than owned by a screen because two places start sweeps, the home view
 * model and the launch burst in MainActivity, and both lists that show servers need to
 * render it. The Servers screen has no view model of its own.
 *
 * A sweep runs sixteen probes at a time across a list that can be sixty long. Without
 * this, "a ping is running" is all the UI can say: every row looks identical for the
 * minute it takes, with no way to tell what has been measured, what is in progress and
 * what has not been reached.
 */
object PingState {
    private val _inFlight = MutableStateFlow<Set<String>>(emptySet())

    /** Profile ids currently being measured. Empty when nothing is running. */
    val inFlight: StateFlow<Set<String>> = _inFlight.asStateFlow()

    /** Runs [block] with [id] marked as in flight, however it ends, including cancelled. */
    suspend fun <T> probing(id: String, block: suspend () -> T): T {
        _inFlight.update { it + id }
        return try {
            block()
        } finally {
            _inFlight.update { it - id }
        }
    }

    private val _substituted = MutableStateFlow(0)

    /**
     * How many servers in the current sweep were measured by something other than the
     * method the user picked.
     *
     * «URL Test» runs the node's own protocol and is the only probe that answers "does
     * this actually carry traffic". When it cannot run, a config the core refused, an
     * instance that would not start, the measurement falls back to a plain TCP connect,
     * which succeeds against almost any open port. The number that appears is then a
     * different question's answer, wearing the same clothes, and that is precisely what
     * was reported from the field: "он бодренько стал находить кучу якобы работающих
     * нод… выбираю верхнюю, не работает".
     *
     * Counted so the sweep can say so afterwards instead of leaving the list looking
     * green and authoritative.
     */
    val substituted: StateFlow<Int> = _substituted.asStateFlow()

    /** One server measured by a weaker method than the one asked for. */
    fun countSubstitution() {
        _substituted.update { it + 1 }
    }

    /**
     * Clears everything.
     *
     * Cancelling a sweep kills the coroutines mid-probe, and while each one's own
     * `finally` does run, a burst torn down under cancellation is not something to leave
     * to bookkeeping: a single id left behind would spin a row's meter forever.
     */
    fun clear() {
        _inFlight.value = emptySet()
    }

    /** Starts a fresh sweep's bookkeeping. */
    fun beginSweep() {
        _inFlight.value = emptySet()
        _substituted.value = 0
    }
}

package com.th3web.lean.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The bug: a tunnel that stays Connected and quietly stops carrying anything —
 * «зависает через какое-то время». The counters say it outright, and nothing was reading
 * them for it.
 *
 * Most of what follows is about NOT firing. A false positive resets a healthy tunnel's
 * sockets and DNS for no reason, and the commonest state of a phone is idle.
 */
class TunnelStallWatchTest {

    private val second = 1_000_000_000L
    private val watch = TunnelStallWatch(
        stallAfterMs = 45_000,
        minUplinkBytes = 16 * 1024,
        minFailures = 4,
        rearmAfterMs = 240_000,
    )

    /** Feeds one reading a second and reports the first second at which it fires. */
    private fun run(
        seconds: Int,
        up: (Int) -> Long,
        down: (Int) -> Long,
        failures: (Int) -> Long = { 0L },
    ): Int? {
        for (t in 0..seconds) {
            if (watch.sample(up(t), down(t), failures(t), t * second)) return t
        }
        return null
    }

    /** The failure itself: sending steadily, nothing coming back. */
    @Test
    fun `it fires once traffic goes out and none comes back`() {
        val at = run(120, up = { it * 4_096L }, down = { 100_000L })
        assertTrue("expected a stall, got none", at != null)
        assertTrue("fired too early at ${at}s", at!! >= 45)
    }

    /**
     * A phone in a pocket. NOTHING moves — not the downlink and not the uplink — and that
     * is the most common state there is. Resetting it would be pure harm.
     */
    @Test
    fun `an idle tunnel is never touched`() {
        assertTrue(run(600, up = { 50_000L }, down = { 100_000L }) == null)
    }

    /** Traffic in both directions, however slow, is a working tunnel. */
    @Test
    fun `a live tunnel is never touched`() {
        assertTrue(run(600, up = { it * 4_096L }, down = { it * 512L }) == null)
    }

    /**
     * A few bytes out with nothing back is a keepalive or a retransmit, not a stall. It
     * has to be real traffic going unanswered before anything is done about it.
     */
    @Test
    fun `a trickle out is not enough to call it stalled`() {
        assertTrue(run(600, up = { 50_000L + it * 8L }, down = { 100_000L }) == null)
    }

    /** One shot per episode: no second reset while the first one is still being given time. */
    @Test
    fun `it does not fire again inside the cooldown`() {
        val first = run(120, up = { it * 4_096L }, down = { 100_000L })
        assertTrue(first != null)
        var again = false
        for (t in 121..(first!! + 239)) {
            if (watch.sample(t * 4_096L, 100_000L, 0L, t * second)) again = true
        }
        assertFalse("fired again while the first reset was still settling", again)
    }

    /**
     * But it does not abandon the tunnel either. A reset that did not help leaves the
     * phone carrying nothing, and doing nothing about that until its owner notices is the
     * behaviour this whole class exists to end.
     */
    @Test
    fun `a tunnel still wedged after the cooldown is tried again`() {
        val first = run(120, up = { it * 4_096L }, down = { 100_000L })
        assertTrue(first != null)
        var again: Int? = null
        for (t in 121..900) {
            if (watch.sample(t * 4_096L, 100_000L, 0L, t * second)) {
                again = t
                break
            }
        }
        assertTrue("a tunnel wedged for a quarter of an hour was never retried", again != null)
        assertTrue("retried too soon at ${again}s", again!! - first!! >= 240)
    }

    /** And it arms again for the NEXT episode once the tunnel has proved it can carry. */
    @Test
    fun `it arms again after the downlink recovers`() {
        assertTrue(run(120, up = { it * 4_096L }, down = { 100_000L }) != null)
        // One byte back is proof of life; the clock starts over from there.
        watch.sample(121 * 4_096L, 100_001L, 0L, 121 * second)
        var fired = false
        for (t in 122..400) {
            if (watch.sample(t * 4_096L, 100_001L, 0L, t * second)) fired = true
        }
        assertTrue("a second episode must be caught too", fired)
    }

    /**
     * A fresh core starts its counters at zero. Read as a drop that would look like the
     * quietest possible tunnel, so it has to restart the measurement instead.
     */
    @Test
    fun `counters going backwards start the measurement over`() {
        watch.sample(9_000_000L, 5_000_000L, 7L, 10 * second)
        assertFalse(watch.sample(0, 0, 0L, 11 * second))
        assertTrue(run(120, up = { it * 4_096L }, down = { 0L }) != null)
    }

    /**
     * The wedge as it actually arrives from the field, and the reason the byte counters
     * alone were not enough: the session to the server is dead, so every connection dies
     * in the dial and NOTHING is ever attributed to the outbound. Both totals are frozen —
     * byte for byte the same picture as an idle phone — and the only difference is the
     * pile of connections the core reports giving up on.
     */
    @Test
    fun `a wedge that never carries a byte is still caught, by its failures`() {
        val at = run(
            120,
            up = { 50_000L },
            down = { 100_000L },
            failures = { it.toLong() / 4 },
        )
        assertTrue("a tunnel failing every connection must not pass as idle", at != null)
        assertTrue("fired too early at ${at}s", at!! >= 45)
    }

    /** One unreachable host is not a wedge, and must not reset a working tunnel. */
    @Test
    fun `a lone failure is not a stall`() {
        val at = run(
            600,
            up = { 50_000L },
            down = { 100_000L },
            failures = { if (it < 10) 0L else 1L },
        )
        assertTrue("one dead host must not count as a wedged tunnel", at == null)
    }
}

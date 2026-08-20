package com.th3web.lean.data.net

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Regression guard for the "TCP ping looks random" fix: a bare first connect to a
 * cold destination can eat a real SYN-retransmit RTO even against a healthy server
 * (some stateful firewalls drop the very first SYN), so [Pinger.tcpLatency] makes a
 * second immediate connect after a successful first and reports the smaller (warm)
 * number via [Pinger.warmResult].
 *
 * Tests the pure decision function directly rather than tcpLatency end-to-end: this
 * module's unit tests run on a plain JVM (see PingerDnsTimeoutTest's own comment),
 * where `android.system.Os` is not backed by a real socket implementation and every
 * raw-socket call throws — caught by tcpLatency's outer catch-all and turned into a
 * miss regardless of what's actually listening. A real warm-probe connect can only
 * be observed on-device; [warmResult] is the seam that stays meaningfully testable
 * here.
 */
class PingerTcpWarmProbeTest {

    @Test
    fun `second probe faster than first reports the warm number`() {
        assertEquals(200, Pinger.warmResult(first = 1400, second = 200))
    }

    @Test
    fun `second probe not faster keeps the first number`() {
        assertEquals(50, Pinger.warmResult(first = 50, second = 80))
    }

    @Test
    fun `second probe equal to first keeps the first number`() {
        assertEquals(120, Pinger.warmResult(first = 120, second = 120))
    }

    @Test
    fun `a missed second probe keeps the first (successful) number`() {
        // A first-hit-then-miss second is unlikely (momentary loss on an already
        // reachable path) but must never turn a working server into a reported miss.
        assertEquals(300, Pinger.warmResult(first = 300, second = -1))
    }
}

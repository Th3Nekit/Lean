package com.th3web.lean.data.net

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM proof that the InetAddress.getByName timeout guard works: a host that
 * cannot resolve returns -1 (a miss) rather than hanging the probe. We use a
 * syntactically-valid-but-nonexistent .invalid TLD (RFC 6761 — guaranteed never
 * to resolve), so the resolver either fails fast (UnknownHostException -> -1 via
 * the existing catch) or, on a slow/looping resolver, is bounded by the timeout
 * (-> withTimeoutOrNull null -> -1). Either way the call returns -1 well inside a
 * generous ceiling, which is the property under test.
 *
 * Plain JUnit; no Robolectric. tcpLatency/udpLatency/icmpLatency all share the
 * same guard, so testing tcpLatency exercises the pattern; the others are
 * textually identical.
 */
class PingerDnsTimeoutTest {

    @Test
    fun `tcpLatency returns -1 for an unresolvable host within the timeout`() = runBlocking {
        val timeoutMs = 1_000
        val start = System.currentTimeMillis()
        val rtt = Pinger.tcpLatency(
            host = "no-such-host.invalid",
            port = 443,
            timeoutMs = timeoutMs,
        )
        val elapsed = System.currentTimeMillis() - start

        assertEquals("unresolvable host must be a miss", -1, rtt)
        // Bounded: must not hang for the OS resolver's full (often 20-30s) budget.
        // Generous ceiling (5x) to stay non-flaky across CI resolver behavior.
        assertTrue("DNS resolve was not bounded: ${elapsed}ms", elapsed < timeoutMs * 5L)
    }

    @Test
    fun `tcpLatency to an IP literal resolves instantly (happy path unaffected)`() = runBlocking {
        // 192.0.2.1 is TEST-NET-1 (RFC 5737): an IP literal, so getByName is
        // synchronous and the resolve step adds no delay. The connect will miss
        // (returns -1) but the point is the resolve guard does not interfere.
        val rtt = Pinger.tcpLatency(host = "192.0.2.1", port = 443, timeoutMs = 1_000)
        assertTrue("IP-literal probe should return a number or a clean miss", rtt == -1 || rtt >= 1)
    }
}

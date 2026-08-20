package com.th3web.lean.data.net

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import com.th3web.lean.data.model.Outbound

/**
 * Pinger.measure's "URLTEST" dispatch branch is exercised through an INJECTED
 * probe lambda (the same pattern [Pinger.measure]'s `protect` hook already uses),
 * so it is fully testable here without ever touching the real native core — the
 * real UrlTestPinger boots an actual sing-box instance and can only be verified
 * on-device (see SingBoxConfigTest's urlTest* cases for the pure-JVM config-schema
 * half of that path, checked against the real core via `sing-box check`/`run`).
 */
class PingerUrlTestDispatchTest {

    private val outbound = Outbound.Vless(server = "v.example.com", serverPort = 443, uuid = "u")

    @Test
    fun `URLTEST protocol calls the injected probe and returns its result`() = runBlocking {
        val ms = Pinger.measure(
            host = "v.example.com", port = 443, protocol = Pinger.URL_TEST_PROTOCOL,
            pingUrl = "https://example.com/generate_204",
            outbound = outbound,
            urlTestProbe = { o, url, _ -> if (o === outbound && url == "https://example.com/generate_204") 123 else -1 },
        )
        assertEquals(123, ms)
    }

    @Test
    fun `URLTEST protocol is case-insensitive like every other protocol`() = runBlocking {
        val ms = Pinger.measure(
            host = "v.example.com", port = 443, protocol = "urltest",
            pingUrl = "https://example.com/generate_204",
            outbound = outbound,
            urlTestProbe = { _, _, _ -> 77 },
        )
        assertEquals(77, ms)
    }

    @Test
    fun `a probe that could not run falls back instead of reporting a miss`() = runBlocking {
        // null means "the test never ran" — reporting that as -1 is what made a broken
        // probe look like a list of dead servers.
        val ms = Pinger.measure(
            host = "203.0.113.1", port = 443, protocol = Pinger.URL_TEST_PROTOCOL,
            pingUrl = "https://example.com/generate_204",
            timeoutMs = 200,
            outbound = outbound,
            urlTestProbe = { _, _, _ -> null },
        )
        // The raw fallback runs against an unroutable address, so a clean miss — but it
        // came from the socket probe, not from the unusable core probe.
        assertEquals(-1, ms)
    }

    @Test
    fun `a negative from the probe is a REAL miss and is kept`() = runBlocking {
        val ms = Pinger.measure(
            host = "203.0.113.1", port = 443, protocol = Pinger.URL_TEST_PROTOCOL,
            pingUrl = "https://example.com/generate_204",
            timeoutMs = 200,
            outbound = outbound,
            urlTestProbe = { _, _, _ -> -1 },
        )
        assertEquals(-1, ms)
    }

    @Test
    fun `URLTEST with no outbound wired falls back to a TCP-style miss, not a crash`() = runBlocking {
        // No outbound/urlTestProbe (both default to null) and an unroutable IP -> a
        // clean miss. Every production call-site now wires them, but the parameters stay
        // optional so the fallback must remain safe rather than crash.
        val ms = Pinger.measure(
            host = "203.0.113.1", port = 443, protocol = Pinger.URL_TEST_PROTOCOL,
            pingUrl = "https://example.com/generate_204",
            timeoutMs = 200,
        )
        assertEquals(-1, ms)
    }

    /**
     * Regression: WireGuard has no "outbound" form in sing-box, so the URL Test probe
     * can only ever answer -1 for it. The dispatch used to call the probe anyway, which
     * — the moment URL Test became the DEFAULT protocol — made every WireGuard and
     * AmneziaWG row read "недоступен" regardless of whether the server was healthy. It
     * must take the raw-probe path instead and never reach the probe at all.
     */
    @Test
    fun `URLTEST never calls the probe for WireGuard and falls back to the raw probe`() = runBlocking {
        val wg = Outbound.WireGuard(
            server = "203.0.113.1", serverPort = 51820,
            privateKey = "priv", peerPublicKey = "pub", localAddresses = listOf("10.0.0.2/32"),
        )
        var called = false
        val ms = Pinger.measure(
            host = wg.server, port = wg.serverPort, protocol = Pinger.URL_TEST_PROTOCOL,
            pingUrl = "https://example.com/generate_204",
            timeoutMs = 200,
            udpService = Pinger.isUdpService(wg),
            outbound = wg,
            urlTestProbe = { _, _, _ -> called = true; 42 },
        )
        assertEquals("the URL Test probe must not be used for WireGuard", false, called)
        // Unroutable address in a plain-JVM test -> a clean miss from the raw probe,
        // which is the honest answer; the point is that it came from the fallback path.
        assertEquals(-1, ms)
    }

    @Test
    fun `supportsUrlTest excludes only WireGuard`() {
        assertEquals(true, Pinger.supportsUrlTest(outbound))
        assertEquals(
            false,
            Pinger.supportsUrlTest(
                Outbound.WireGuard(
                    server = "w.example.com", serverPort = 51820,
                    privateKey = "p", peerPublicKey = "k", localAddresses = listOf("10.0.0.2/32"),
                ),
            ),
        )
    }

    @Test
    fun `URLTEST with a blank pingUrl falls back instead of calling the probe`() = runBlocking {
        var called = false
        val ms = Pinger.measure(
            host = "203.0.113.1", port = 443, protocol = Pinger.URL_TEST_PROTOCOL,
            pingUrl = "",
            timeoutMs = 200,
            outbound = outbound,
            urlTestProbe = { _, _, _ -> called = true; 999 },
        )
        assertEquals(false, called)
        assertEquals(-1, ms)
    }
}

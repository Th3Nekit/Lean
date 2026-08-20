package com.th3web.lean.data.net

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Verifies the >16 KB survival probe ([ConnectionChecker.sustainedCheck]):
 * a full payload = Survived, a body cut short before the target = Torn (the
 * TSPU-after-16KB teardown signature), a non-2xx = Failed. Robolectric because
 * the probe shares [Http.userAgent]/android plumbing.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SustainedCheckTest {

    private lateinit var server: MockWebServer

    @Before fun setUp() { server = MockWebServer().apply { start() } }
    @After fun tearDown() { server.shutdown() }

    private fun url() = server.url("/__down").toString()

    @Test
    fun `full payload is Survived`() = runBlocking {
        val target = 8 * 1024
        server.enqueue(MockResponse().setBody("x".repeat(target)))
        val r = ConnectionChecker.sustainedCheck(url(), targetBytes = target, timeoutMs = 5_000)
        assertTrue("got $r", r is ConnectionChecker.SustainedResult.Survived)
        assertEquals(target, (r as ConnectionChecker.SustainedResult.Survived).bytes)
    }

    @Test
    fun `a body cut short before the target is Torn`() = runBlocking {
        val target = 64 * 1024
        server.enqueue(MockResponse().setBody("x".repeat(4 * 1024))) // stops at 4 KB
        val r = ConnectionChecker.sustainedCheck(url(), targetBytes = target, timeoutMs = 5_000)
        assertTrue("got $r", r is ConnectionChecker.SustainedResult.Torn)
        assertEquals(4 * 1024, (r as ConnectionChecker.SustainedResult.Torn).bytes)
    }

    @Test
    fun `a non-2xx response is Failed`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(502).setBody("bad gateway"))
        val r = ConnectionChecker.sustainedCheck(url(), targetBytes = 8 * 1024, timeoutMs = 5_000)
        assertEquals(ConnectionChecker.SustainedResult.Failed, r)
    }
}

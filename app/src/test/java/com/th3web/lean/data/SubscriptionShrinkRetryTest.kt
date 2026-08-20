package com.th3web.lean.data

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
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import com.th3web.lean.data.net.Http

/**
 * Regression coverage for "subscription refresh collapses to 1 server": a panel
 * can answer 200 OK with a genuine but tiny profile list on a transient hiccup
 * (mid-refresh backend inconsistency, a brief rate limit). That used to sail
 * straight past fetchSub's isEmpty() safety guard and wipe out every other
 * server for the subscription via reconcile(), which only ever emits as many
 * profiles as the fresh list it was given. A refresh that comes back suspiciously
 * smaller than the subscription's last known count now gets one same-UA retry,
 * keeping whichever attempt has more profiles — see ProfileRepository.fetchSub.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SubscriptionShrinkRetryTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
        Http.hwid = ""
        Http.sendHwid = false
        Http.userAgent = "Lean/test"
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun repo() = ProfileRepository(RuntimeEnvironment.getApplication())

    private fun links(count: Int) = (1..count).joinToString("\n") { i ->
        val uuid = "%08d-1111-1111-1111-111111111111".format(i)
        "vless://$uuid@host$i.example.com:443?type=tcp#Node$i"
    }

    @Test
    fun `refresh that collapses to 1 server retries and recovers the full list`() = runBlocking {
        val repo = repo()
        val url = server.url("/sub").toString()

        // Seed: subscription starts out with 20 servers.
        server.enqueue(MockResponse().setResponseCode(200).setBody(links(20)))
        val seeded = repo.addSubscription("Test", url)
        assertEquals(20, seeded.getOrThrow())
        val subId = repo.state.value.subscriptions.single().id

        // Refresh: first response is a truncated 1-server body (the bug trigger),
        // the retry response is the full 20 again.
        server.enqueue(MockResponse().setResponseCode(200).setBody(links(1)))
        server.enqueue(MockResponse().setResponseCode(200).setBody(links(20)))

        val updated = repo.updateSubscription(subId)

        assertTrue(updated.isSuccess)
        assertEquals(20, updated.getOrThrow())
        assertEquals(20, repo.state.value.profiles.count { it.subscriptionId == subId })
        // seed (1 request) + refresh (2: primary + shrink-triggered retry) = 3.
        assertEquals(3, server.requestCount)
    }

    @Test
    fun `a mild legitimate trim does not trigger a retry`() = runBlocking {
        val repo = repo()
        val url = server.url("/sub").toString()

        server.enqueue(MockResponse().setResponseCode(200).setBody(links(20)))
        repo.addSubscription("Test", url)
        val subId = repo.state.value.subscriptions.single().id

        // The provider legitimately dropped a handful of dead servers: 20 -> 15,
        // well above the "lost more than 2/3" retry threshold.
        server.enqueue(MockResponse().setResponseCode(200).setBody(links(15)))

        val updated = repo.updateSubscription(subId)

        assertTrue(updated.isSuccess)
        assertEquals(15, updated.getOrThrow())
        // seed (1) + refresh (1, no retry) = 2.
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `a brand new subscription with few servers is accepted without a spurious retry`() = runBlocking {
        val repo = repo()
        val url = server.url("/sub").toString()

        // No previous count exists yet for a fresh subscription, so the shrink
        // guard must never fire here even though the body is tiny.
        server.enqueue(MockResponse().setResponseCode(200).setBody(links(1)))
        val result = repo.addSubscription("Test", url)

        assertTrue(result.isSuccess)
        assertEquals(1, result.getOrThrow())
        assertEquals(1, server.requestCount)
    }
}

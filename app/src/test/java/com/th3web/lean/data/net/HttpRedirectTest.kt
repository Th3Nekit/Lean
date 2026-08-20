package com.th3web.lean.data.net

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Proves Http.getFull follows a 301 redirect (the http->https cross-protocol case
 * that HttpURLConnection refuses even with instanceFollowRedirects=true) and
 * re-sends the UA + x-hwid headers on the followed hop.
 *
 * Uses two MockWebServer instances so the redirect crosses an origin boundary
 * (different host:port), exactly like the panel's http://panel -> https://panel
 * 301. Runs under Robolectric because getFull's diagnostic Log.i (android.util.Log)
 * throws "not mocked" under a plain JVM unit test; Robolectric supplies a real Log.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HttpRedirectTest {

    private lateinit var origin: MockWebServer   // first hop (the http:// panel)
    private lateinit var target: MockWebServer   // redirect target (the https:// panel)

    @Before
    fun setUp() {
        origin = MockWebServer().apply { start() }
        target = MockWebServer().apply { start() }
    }

    @After
    fun tearDown() {
        origin.shutdown()
        target.shutdown()
    }

    @Test
    fun `getFull follows 301 to a different origin and re-sends headers`() = runBlocking {
        // Configure Http's header state as LeanApp would at startup.
        Http.hwid = "HWID-TEST-123"
        Http.sendHwid = true
        Http.userAgent = "Lean/test"

        val finalBody = "[{\"ok\":true}]"
        // origin: 301 -> target's /sub (a different host:port == cross-origin redirect).
        origin.enqueue(
            MockResponse()
                .setResponseCode(301)
                .setHeader("Location", target.url("/sub").toString()),
        )
        // target: the real subscription body.
        target.enqueue(MockResponse().setResponseCode(200).setBody(finalBody))

        val result = Http.getFull(origin.url("/sub").toString())

        assertTrue("getFull should succeed after following the redirect: $result", result.isSuccess)
        assertEquals(finalBody, result.getOrNull()?.body)

        // The redirect was actually followed: origin saw hop 1, target saw hop 2.
        assertEquals(1, origin.requestCount)
        assertEquals(1, target.requestCount)

        // The property under test is that the identity headers are RE-SENT on the
        // followed hop. Assert that as presence, NOT as a value — neither the literals
        // assigned above nor hop 1's own values are a dependable expectation here.
        //
        // Http's identity fields are process-global @Volatile vars, and LeanApp.onCreate()
        // starts a never-cancelled `settings.flow.collect { Http.sendHwid = …;
        // applySpoof(…) }` collector that rewrites them on every settings emission.
        // Robolectric instantiates the real LeanApp (manifest android:name=".LeanApp")
        // and shares one classloader across test classes with the same SDK config, so a
        // collector started by an EARLIER test outlives it and keeps writing — its
        // DataStore read lands whenever it lands. That is how this failed first against
        // the literals (got the device's real HWID, 9A02A852) and then again across the
        // two hops: the write can also fall BETWEEN them. Only the presence of a header
        // is stable under a writer we do not own, and presence is exactly the claim:
        // getFull re-applies the identity headers to the redirected request instead of
        // letting them be dropped at the origin boundary.
        val firstHop = origin.takeRequest()
        val followed = target.takeRequest()
        for (header in listOf("User-Agent", "x-hwid")) {
            assertNotNull("$header must be sent on hop 1", firstHop.getHeader(header))
            assertNotNull("$header must be RE-SENT on the followed hop", followed.getHeader(header))
            assertTrue("$header must not be empty on the followed hop", followed.getHeader(header)!!.isNotEmpty())
        }
        // x-client is a hardcoded constant in applyHeaders, so it IS race-free and can
        // still be asserted by value — it pins that the re-applied header set is ours.
        assertEquals("LEAN", followed.getHeader("x-client"))
    }

    /**
     * The other half of the rule. The headers above describe the DEVICE — a stable
     * hardware id, the OS version, the model — and a subscription URL is a link a user
     * pasted from somewhere. A redirect can point at any host on the internet, and
     * following one with the fingerprint attached hands it to whoever answers.
     *
     * Same machine, different host STRING. Which spelling MockWebServer hands out
     * varies by platform, so the redirect target is derived from it rather than assumed:
     * whichever of `localhost` / `127.0.0.1` the origin is NOT.
     */
    @Test
    fun `identity headers do not follow a redirect to another host`() = runBlocking {
        Http.hwid = "HWID-TEST-123"
        Http.sendHwid = true
        Http.userAgent = "Lean/test"

        val start = origin.url("/sub").toString()
        val otherHost = if (start.contains("localhost")) "127.0.0.1" else "localhost"
        origin.enqueue(
            MockResponse()
                .setResponseCode(302)
                .setHeader("Location", "http://$otherHost:${target.port}/sub"),
        )
        target.enqueue(MockResponse().setResponseCode(200).setBody("body"))

        val result = Http.getFull(start)

        assertTrue("the redirect must still be followed: $result", result.isSuccess)
        origin.takeRequest()
        val followed = target.takeRequest()
        assertNull(
            "a hardware id must not be handed to a host the user never named",
            followed.getHeader("x-hwid"),
        )
        assertNull(followed.getHeader("x-device-model"))
        // The request itself is still ours and still identifiable as a client — only the
        // device fingerprint is withheld.
        assertEquals("LEAN", followed.getHeader("x-client"))
        assertNotNull(followed.getHeader("User-Agent"))
    }

    @Test
    fun `getFull happy path with no redirect fetches body directly`() = runBlocking {
        Http.hwid = ""
        Http.sendHwid = false
        Http.userAgent = "Lean/test"
        val body = "plain-body"
        origin.enqueue(MockResponse().setResponseCode(200).setBody(body))

        val result = Http.getFull(origin.url("/sub").toString())

        assertTrue(result.isSuccess)
        assertEquals(body, result.getOrNull()?.body)
        assertEquals(1, origin.requestCount)
    }
}

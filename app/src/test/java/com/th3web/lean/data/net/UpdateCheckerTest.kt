package com.th3web.lean.data.net

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Version comparison + GitHub-Releases update detection ([UpdateChecker]). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class UpdateCheckerTest {

    private lateinit var server: MockWebServer

    @Before fun setUp() { server = MockWebServer().apply { start() } }
    @After fun tearDown() { server.shutdown() }

    @Test
    fun `normalizeVersion strips v prefix and beta suffix`() {
        assertEquals("0.9.4", UpdateChecker.normalizeVersion("v0.9.4-beta"))
        assertEquals("0.9.3.7", UpdateChecker.normalizeVersion("0.9.3.7"))
        assertEquals("1.0", UpdateChecker.normalizeVersion("V1.0-rc1"))
    }

    @Test
    fun `compareVersions orders mixed-length dotted versions`() {
        assertTrue(UpdateChecker.compareVersions("0.9.4", "0.9.3.7") > 0)
        assertTrue(UpdateChecker.compareVersions("0.9.3.7", "0.9.3") > 0)
        assertTrue(UpdateChecker.compareVersions("0.9.4", "0.10.0") < 0)
        assertEquals(0, UpdateChecker.compareVersions("0.9.4", "0.9.4"))
    }

    private fun releaseJson(tag: String) = """
        {"tag_name":"$tag","html_url":"https://github.com/Th3Nekit/Lean/releases/tag/$tag",
         "assets":[
           {"name":"lean-0.9.5-arm64-v8a.apk","browser_download_url":"https://x/arm64.apk"},
           {"name":"lean-0.9.5-universal.apk","browser_download_url":"https://x/universal.apk"}
         ]}
    """.trimIndent()

    @Test
    fun `newer release is reported with the universal apk`() = runBlocking {
        server.enqueue(MockResponse().setBody(releaseJson("v0.9.5-beta")))
        val info = UpdateChecker.check("0.9.4", server.url("/latest").toString())
        assertEquals("0.9.5", info?.latestVersion)
        assertEquals("https://x/universal.apk", info?.apkUrl)
    }

    @Test
    fun `same or older release yields no update`() = runBlocking {
        server.enqueue(MockResponse().setBody(releaseJson("v0.9.4-beta")))
        assertNull(UpdateChecker.check("0.9.4", server.url("/latest").toString()))
    }

    @Test
    fun `a 404 yields no update, not an error`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(404))
        assertNull(UpdateChecker.check("0.9.4", server.url("/latest").toString()))
    }

    /**
     * The manual check must keep apart the two things [check] collapses into null.
     * Reporting "you have the latest version" when the request never completed is a claim
     * the app cannot make — and from RU an unreachable GitHub is the common case, not a
     * rare one.
     */
    @Test
    fun `a manual check separates up-to-date from unreachable`() = runBlocking {
        server.enqueue(MockResponse().setBody(releaseJson("v0.9.4-beta")))
        assertEquals(
            UpdateChecker.CheckResult.UpToDate,
            UpdateChecker.checkManually("0.9.4", server.url("/latest").toString()),
        )

        server.enqueue(MockResponse().setResponseCode(403)) // rate-limited, the usual block
        assertEquals(
            UpdateChecker.CheckResult.Failed,
            UpdateChecker.checkManually("0.9.4", server.url("/latest").toString()),
        )

        server.enqueue(MockResponse().setBody(releaseJson("v0.9.5-beta")))
        val available = UpdateChecker.checkManually("0.9.4", server.url("/latest").toString())
        assertTrue(available is UpdateChecker.CheckResult.Available)
        assertEquals("0.9.5", (available as UpdateChecker.CheckResult.Available).info.latestVersion)
    }

    @Test
    fun `notification permission is required only on Android 13 and newer`() {
        assertTrue(UpdateChecker.mayPostNotification(sdkInt = 32, permissionGranted = false))
        assertFalse(UpdateChecker.mayPostNotification(sdkInt = 33, permissionGranted = false))
        assertTrue(UpdateChecker.mayPostNotification(sdkInt = 33, permissionGranted = true))
    }
}

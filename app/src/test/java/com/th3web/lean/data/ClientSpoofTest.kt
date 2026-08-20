package com.th3web.lean.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Verifies per-client UA/hwid spoofing: a stored preset token resolves to the
 * right wire User-Agent, a client-SHAPED hwid, and the right extra headers.
 * Needs Robolectric because [HwId] reads Settings.Secure.ANDROID_ID.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ClientSpoofTest {

    private val ctx get() = RuntimeEnvironment.getApplication()
    private val defaultUa = "Lean/9.9.9"

    @Test
    fun `empty token is the Lean default with a hex hwid`() {
        val r = ClientSpoof.resolve("", defaultUa, ctx)
        assertEquals(defaultUa, r.userAgent)
        assertEquals(HwId.get(ctx), r.hwid)
        assertTrue(r.extraHeaders.isEmpty())
    }

    @Test
    fun `happ token resolves to Happ UA, Happ-shaped hwid and bundle headers`() {
        val r = ClientSpoof.resolve("happ:Android", defaultUa, ctx)
        assertTrue("ua=${r.userAgent}", r.userAgent.startsWith("Happ/4.1.0/Android/"))
        // 20 digits on Android — the length real Happ ids have in a panel's request log
        // (Happ/4.1.0/Android/17860741775021899510). iOS and Windows use 13, see below.
        val uaId = r.userAgent.substringAfterLast('/')
        assertTrue("uaId=$uaId", uaId.length == 20 && uaId.all { it.isDigit() })
        // Header hwid is lowercase alphanumeric, 15 chars — Happ's shape, NOT the hex default.
        assertEquals(15, r.hwid.length)
        assertTrue("hwid=${r.hwid}", r.hwid.all { it in '0'..'9' || it in 'a'..'z' })
        assertFalse(r.hwid == HwId.get(ctx))
        assertEquals("su.happ.proxyutility", r.extraHeaders["X-Bundle-ID"])
        assertEquals("1.0", r.extraHeaders["X-API-Version"])
    }

    /**
     * Each platform carries its OWN version and id length.
     *
     * Happ does not ship one version everywhere — the same week showed Android on 4.1.0,
     * iOS on 5.2.0 and Windows on 3.3.6 — so a single constant described no real client.
     * Shapes below are copied from a panel's request log.
     */
    @Test
    fun `each happ platform carries its own version and id length`() {
        val ios = ClientSpoof.resolveUa("happ:ios", defaultUa, ctx)
        assertTrue("ua=$ios", ios.startsWith("Happ/5.2.0/ios/"))
        assertEquals(13, ios.substringAfterLast('/').length)

        val windows = ClientSpoof.resolveUa("happ:Windows", defaultUa, ctx)
        assertTrue("ua=$windows", windows.startsWith("Happ/3.3.6/Windows/"))
        assertEquals(13, windows.substringAfterLast('/').length)

        // An unknown platform is normalised rather than passed through: a segment no
        // panel has ever seen matches nothing, which defeats the point of spoofing.
        val unknown = ClientSpoof.resolveUa("happ:beos", defaultUa, ctx)
        assertTrue("ua=$unknown", unknown.startsWith("Happ/4.1.0/Android/"))
        assertEquals(20, unknown.substringAfterLast('/').length)
    }

    /**
     * Tokens stored by an earlier build still resolve to something a panel recognises.
     *
     * `chrome-android` / `chrome-win` / `chrome-linux` were offered once and are still in
     * settings on upgraded installs, but no panel log contains them — so they are mapped
     * to the nearest real platform instead of being sent as-is.
     */
    @Test
    fun `legacy chrome platforms map onto real ones`() {
        assertTrue(ClientSpoof.resolveUa("happ:chrome-android", defaultUa, ctx).startsWith("Happ/4.1.0/Android/"))
        assertTrue(ClientSpoof.resolveUa("happ:chrome-win", defaultUa, ctx).startsWith("Happ/3.3.6/Windows/"))
        assertTrue(ClientSpoof.resolveUa("happ:chrome-linux", defaultUa, ctx).startsWith("Happ/3.3.6/Windows/"))
        // And none of them leaks the old segment into the wire string.
        listOf("chrome-android", "chrome-win", "chrome-linux").forEach { legacy ->
            assertFalse(ClientSpoof.resolveUa("happ:$legacy", defaultUa, ctx).contains(legacy))
        }
    }

    @Test
    fun `v2raytun uses its UA and an upper-hex hwid`() {
        val r = ClientSpoof.resolve("v2raytun", defaultUa, ctx)
        // The platform, not a version — exactly as v2rayTun appears in a request log.
        assertEquals("v2raytun/android", r.userAgent)
        assertEquals(HwId.get(ctx), r.hwid) // upper hex, v2rayTun's shape
        assertTrue(r.extraHeaders.isEmpty())
    }

    @Test
    fun `a plain literal UA passes through with the default hwid`() {
        val r = ClientSpoof.resolve("v2rayNG/1.9.5", defaultUa, ctx)
        assertEquals("v2rayNG/1.9.5", r.userAgent)
        assertEquals(HwId.get(ctx), r.hwid)
    }

    @Test
    fun `happ hwid and ua id are stable across calls`() {
        assertEquals(HwId.happHwid(ctx), HwId.happHwid(ctx))
        assertEquals(HwId.happUaId(ctx, 20), HwId.happUaId(ctx, 20))
    }
}

package com.th3web.lean.data.parse

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The base64 subscription body, as the live panel actually serves it.
 *
 * This is the regression test for a real failure: of 32 servers the user saw 8, four of
 * them mangled, with fragments of one entry's name showing up inside another's. The same
 * list served as PLAIN TEXT imported perfectly, which is what proved the fault was in the
 * decode rather than in the links, the names or the emoji.
 *
 * The cause was the alphabet. The body is standard base64 and carries eight `/`
 * characters; the decoder tried the URL-safe alphabet first, where `/` does not exist.
 * Android's decoder skips a symbol it does not recognise instead of refusing, so those
 * eight were dropped, everything after each one shifted by six bits, and the decode
 * "succeeded" — so the standard-alphabet fallback never ran. Re-alignment by luck is what
 * left a handful of entries readable.
 *
 * The fixture is the untouched body (8768 characters, one line), so this test fails again
 * the moment anything upstream of the split starts losing bytes.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SubscriptionBase64RobolectricTest {

    private fun body(): String =
        checkNotNull(javaClass.classLoader?.getResourceAsStream(FIXTURE)) {
            "missing test fixture $FIXTURE"
        }.bufferedReader().use { it.readText() }.trim()

    @Test
    fun `the live base64 body yields every server intact`() {
        val raw = body()
        // Guard the fixture itself: without a `/` this test would pass for the wrong
        // reason, since the URL-safe alphabet only diverges on `+` and `/`.
        assertTrue("fixture must exercise the standard alphabet", raw.contains('/'))
        assertTrue(raw.none { it == '-' || it == '_' })

        val profiles = Subscriptions.parseBody(raw)

        assertEquals("every server must survive the decode", 32, profiles.size)
        // Four protocols, eight of each — the shape the panel serves.
        assertEquals(
            mapOf("Hysteria2" to 8, "VLESS" to 8, "Trojan" to 8, "Shadowsocks" to 8),
            profiles.groupingBy { it.outbound.protocol }.eachCount(),
        )
        profiles.forEach { p ->
            assertTrue("empty host in ${p.name}", p.outbound.server.isNotBlank())
            assertTrue("bad port in ${p.name}: ${p.outbound.serverPort}", p.outbound.serverPort in 1..65535)
            // A shifted stream produced hosts like "[zh.example.com:8455 <junk>]" — a colon
            // inside the host is the tell-tale, since a real one is a name or a bare IPv4.
            assertTrue("host looks corrupted: ${p.outbound.server}", ':' !in p.outbound.server)
            assertTrue("name looks corrupted: ${p.name}", p.name.isNotBlank())
            // Percent escapes must be decoded, not carried through verbatim.
            assertTrue("undecoded name: ${p.name}", "%20" !in p.name)
        }
    }

    /**
     * The decoder must not quietly drop what it cannot read.
     *
     * Decoding a standard-alphabet blob with the URL-safe alphabet is precisely the case
     * that used to succeed while losing bytes; the length check is what now stops it.
     */
    @Test
    fun `decoding the body returns every byte the input implies`() {
        val raw = body()
        val decoded = decodeBase64TolerantBytes(raw)
        assertNotNull(decoded)
        // 8768 characters, padded, minus the padding.
        val pad = raw.takeLast(2).count { it == '=' }
        assertEquals(raw.length / 4 * 3 - pad, decoded!!.size)
        assertEquals(32, decoded.toString(Charsets.UTF_8).lines().count { it.isNotBlank() })
    }

    /** URL-safe bodies must keep working — that alphabet is still the right one for them. */
    @Test
    fun `a url-safe body still decodes`() {
        // "sub?a/b+c" encoded with the URL-safe alphabet contains '-' and '_'.
        val text = "vless://x@h:1#a/b+c"
        val urlSafe = android.util.Base64.encodeToString(
            text.toByteArray(Charsets.UTF_8),
            android.util.Base64.NO_WRAP or android.util.Base64.URL_SAFE,
        )
        assertEquals(text, decodeBase64Tolerant(urlSafe))
    }

    private companion object {
        const val FIXTURE = "subscription-base64-32.txt"
    }
}

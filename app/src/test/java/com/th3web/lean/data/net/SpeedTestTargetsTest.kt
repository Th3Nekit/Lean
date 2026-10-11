package com.th3web.lean.data.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The target list and the request shapes it implies.
 *
 * These exist because of a shipped bug, not out of diligence: every download stream asked
 * Cloudflare for exactly 100 000 000 bytes, `__down` answers **403** from that number
 * upwards, and the result was «Приём: —» on the one target reachable from Russia while
 * «Отдача» kept working. A size that one endpoint refuses is not something a release
 * should be able to carry again, so it is pinned here.
 */
class SpeedTestTargetsTest {

    /** Measured against the live endpoint: 99 999 999 serves, 100 000 000 is a 403. */
    private val cloudflareHardCap = 100_000_000L

    @Test
    fun `cloudflare downloads stay under the size its endpoint refuses`() {
        val cloudflare = SpeedTest.cloudflareFallback()
        assertTrue(
            "Cloudflare __down answers 403 from $cloudflareHardCap bytes up",
            cloudflare.downloadChunkBytes < cloudflareHardCap,
        )
        assertTrue(cloudflare.downloadUrl(cloudflare.downloadChunkBytes).contains("bytes="))
    }

    @Test
    fun `every built-in target keeps its download request inside the cap it has`() {
        SpeedTest.BUILT_IN_TARGETS.forEach { server ->
            if (server.kind == SpeedTest.Server.Kind.Cloudflare) {
                assertTrue(server.host, server.downloadChunkBytes < cloudflareHardCap)
            }
            // Enough to outlast the transfer window on any consumer link, so the clock
            // ends the transfer rather than the body running out.
            assertTrue(server.host, server.downloadChunkBytes >= 50_000_000L)
        }
    }

    @Test
    fun `librespeed asks in megabytes, never for zero of them`() {
        val selectel = SpeedTest.BUILT_IN_TARGETS.first { it.kind == SpeedTest.Server.Kind.LibreSpeed }
        assertTrue(selectel.downloadUrl(100 * 1_048_576L).contains("ckSize=100"))
        // Below one MiB the endpoint would be asked for nothing at all and serve nothing.
        assertTrue(selectel.downloadUrl(1024L).contains("ckSize=1"))
        assertEquals("https://${selectel.host}/empty.php", selectel.latencyUrl)
        assertEquals("https://${selectel.host}/empty.php", selectel.uploadUrl)
    }

    @Test
    fun `every built-in target is labelled and addressable over https`() {
        assertTrue("the list is what Russia falls back to", SpeedTest.BUILT_IN_TARGETS.size >= 4)
        SpeedTest.BUILT_IN_TARGETS.forEach { server ->
            assertTrue("sponsor missing: $server", server.sponsor.isNotBlank())
            assertTrue("place missing: $server", server.place.isNotBlank())
            assertTrue("host missing: $server", server.host.isNotBlank())
            listOf(server.latencyUrl, server.uploadUrl, server.downloadUrl(1_000_000L)).forEach { url ->
                assertTrue("not https: $url", url.startsWith("https://"))
            }
        }
    }

    @Test
    fun `targets are distinct, so the chooser cannot show the same server twice`() {
        val hosts = SpeedTest.BUILT_IN_TARGETS.map { it.host }
        assertEquals(hosts.distinct(), hosts)
        val ids = SpeedTest.BUILT_IN_TARGETS.map { it.id }
        assertEquals(ids.distinct(), ids)
    }

    @Test
    fun `the directory fallback is reachable from a filtered network`() {
        // Cloudflare is last on purpose — it measures across a border — but it has to be
        // there: it is the only target that answers when everything else is filtered.
        assertEquals(
            SpeedTest.Server.Kind.Cloudflare,
            SpeedTest.BUILT_IN_TARGETS.last().kind,
        )
    }

    @Test
    fun `an upload body is sized to finish inside the time that is left`() {
        val second = 1_000_000_000L
        // A 1 MiB body that took a tenth of a second: ~84 Мбит/с.
        val size = 1_048_576L
        listOf(1L, 2L, 4L, 8L).forEach { secondsLeft ->
            val next = nextUploadSize(size, second / 10, secondsLeft * second)
            val bytesPerSecond = size * 10
            val willTakeSeconds = next.toDouble() / bytesPerSecond
            assertTrue(
                "a $next-byte body would run $willTakeSeconds s with $secondsLeft s left",
                willTakeSeconds <= secondsLeft.toDouble() || next == 256 * 1024L,
            )
        }
    }

    @Test
    fun `upload bodies stay inside their range and survive a missing estimate`() {
        val second = 1_000_000_000L
        assertEquals(4_000L, nextUploadSize(4_000L, 0L, second))
        assertEquals(4_000L, nextUploadSize(4_000L, second, 0L))
        // A ten-gigabit uplink with eight seconds left cannot be allowed to ask for a
        // body the deadline will certainly interrupt.
        val huge = nextUploadSize(32 * 1_048_576L, second / 100, 8 * second)
        assertTrue(huge <= 32 * 1_048_576L)
        // A dial-up estimate cannot shrink the body to nothing either.
        val tiny = nextUploadSize(1_048_576L, 30 * second, second / 10)
        assertTrue(tiny >= 256 * 1024L)
    }
}

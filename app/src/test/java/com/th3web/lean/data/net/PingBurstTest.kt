package com.th3web.lean.data.net

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A burst must show progress, not go silent until the last server answers.
 *
 * The old code awaitAll()'d and wrote once at the end. That was invisible when every
 * probe was a ~1s TCP connect, and became "пинг работает, но не обновляется" the moment
 * «URL Test» — which boots a native core per server, five at a time — became the default:
 * a 20-server list spent minutes with nothing changing on screen.
 *
 * Plain runBlocking rather than runTest: kotlinx-coroutines-test is not on this project's
 * test classpath, and pulling it in for one file is not worth it. The waits below are
 * therefore real, and kept short.
 */
class PingBurstTest {

    @Test
    fun `a quick result is published without waiting for a slow one`() = runBlocking {
        val publishes = mutableListOf<Map<String, Int>>()
        runPingBurst(
            ids = listOf("fast", "slow"),
            flushEveryMs = 30,
            measure = { id -> if (id == "slow") { delay(400); 2 } else 1 },
            publish = { publishes += it },
        )
        assertTrue(
            "the quick result must not wait for the slow one: $publishes",
            publishes.size >= 2,
        )
        assertEquals(mapOf("fast" to 1), publishes.first())
    }

    @Test
    fun `every result is published exactly once`() = runBlocking {
        val seen = mutableMapOf<String, Int>()
        var writes = 0
        val ids = (1..25).map { "s$it" }
        runPingBurst(
            ids = ids,
            measure = { it.removePrefix("s").toInt() },
            publish = { batch -> writes++; batch.forEach { (k, v) -> seen[k] = v } },
        )
        assertEquals(ids.toSet(), seen.keys)
        assertEquals(25, seen["s25"])
        assertTrue("results should still be coalesced, not one write per server", writes < ids.size)
    }

    @Test
    fun `an empty burst publishes nothing`() = runBlocking {
        var writes = 0
        runPingBurst(ids = emptyList(), measure = { 1 }, publish = { writes++ })
        assertEquals(0, writes)
    }
}

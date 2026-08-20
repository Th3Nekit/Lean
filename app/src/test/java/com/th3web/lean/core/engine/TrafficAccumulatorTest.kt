package com.th3web.lean.core.engine

import org.junit.Assert.assertEquals
import org.junit.Test

class TrafficAccumulatorTest {
    @Test
    fun `accumulates proxy deltas and calculates rates with monotonic time`() {
        val accumulator = TrafficAccumulator(startNanos = 1_000_000_000L)

        assertEquals(
            TrafficSample(100, 300, 100, 300),
            accumulator.add(uplinkDelta = 100, downlinkDelta = 300, nowNanos = 2_000_000_000L),
        )
        assertEquals(
            TrafficSample(100, 50, 300, 400),
            accumulator.add(uplinkDelta = 200, downlinkDelta = 100, nowNanos = 4_000_000_000L),
        )
    }

    @Test
    fun `negative native deltas and non advancing clocks cannot corrupt totals`() {
        val accumulator = TrafficAccumulator(startNanos = 10)
        assertEquals(
            TrafficSample(0, 0, 0, 0),
            accumulator.add(-1, -2, nowNanos = 10),
        )
    }
}

package com.th3web.lean.core.engine

data class TrafficSample(
    val uplink: Long,
    val downlink: Long,
    val uplinkTotal: Long,
    val downlinkTotal: Long,
)

class TrafficAccumulator(startNanos: Long) {
    private var lastNanos = startNanos
    private var uplinkTotal = 0L
    private var downlinkTotal = 0L

    @Synchronized
    fun add(uplinkDelta: Long, downlinkDelta: Long, nowNanos: Long): TrafficSample {
        val safeUp = uplinkDelta.coerceAtLeast(0)
        val safeDown = downlinkDelta.coerceAtLeast(0)
        uplinkTotal += safeUp
        downlinkTotal += safeDown
        val elapsed = (nowNanos - lastNanos).coerceAtLeast(0)
        val upRate = if (elapsed == 0L) 0 else safeUp * 1_000_000_000L / elapsed
        val downRate = if (elapsed == 0L) 0 else safeDown * 1_000_000_000L / elapsed
        if (nowNanos > lastNanos) lastNanos = nowNanos
        return TrafficSample(upRate, downRate, uplinkTotal, downlinkTotal)
    }
}

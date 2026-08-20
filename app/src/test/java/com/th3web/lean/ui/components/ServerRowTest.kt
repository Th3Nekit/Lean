package com.th3web.lean.ui.components

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class ServerRowTest {
    @Test
    fun fastestLatencyUsesTheSynchronousThemeAccent() {
        val accent = Color(0xFF12ABEF)

        assertEquals(
            accent,
            latencyTier(ms = 120, accent = accent).first,
        )
    }

    @Test
    fun slowerLatencyLeavesTheCallerAccentForThePublishedRamp() {
        val accent = Color(0xFF12ABEF)
        val fast = latencyTier(ms = 120, accent = accent)
        val slower = latencyTier(ms = 250, accent = accent)

        assertEquals(4, fast.second)
        assertEquals(3, slower.second)
        // Only tier 1 follows the caller's accent. The canvas-specific ladder moved into
        // the resolved palette (LeanColors.LatencyTier2..4), which already knows which
        // column it belongs to — so `onLight` no longer selects between two tables here,
        // and asserting that it does would be asserting the old architecture.
        assertNotEquals(accent, slower.first)
    }

    @Test
    fun thresholdsAndTheUntestedTierComeFromTheKnobs() {
        val accent = Color(0xFF12ABEF)

        // «Пороги пинга»: 200ms is the third tier at the shipping boundaries and the
        // first once the user moves t1 — 120ms as "excellent" is fantasy on a bad
        // mobile network, which is why the thresholds became a knob at all.
        assertEquals(3, latencyTier(ms = 200, accent = accent).second)
        assertEquals(4, latencyTier(ms = 200, accent = accent, t1 = 250).second)

        // null = never tested, negative = unreachable. Both light zero bars.
        assertEquals(0, latencyTier(ms = null, accent = accent).second)
        assertEquals(0, latencyTier(ms = -1, accent = accent).second)
    }
}

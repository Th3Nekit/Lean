package com.th3web.lean.ui.theme

import androidx.compose.runtime.staticCompositionLocalOf
import dev.chrisbanes.haze.HazeState

/**
 * Screen-level [HazeState] shared between the background ([leanBackground] +
 * hazeSource), and the glass panels ([glass] + hazeEffect), so the panels really
 * blur the backdrop behind them (true liquid glass). Null = no backdrop wired
 * (panels fall back to a flat translucent tint).
 */
val LocalHazeState = staticCompositionLocalOf<HazeState?> { null }

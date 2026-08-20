package com.th3web.lean.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import com.th3web.lean.LeanApp
import com.th3web.lean.ui.components.LeanSplash
import com.th3web.lean.ui.components.LeanSplashScrim
import com.th3web.lean.ui.theme.BackgroundImage
import com.th3web.lean.ui.theme.LeanOptions

/**
 * Holds the launch screen up until the app actually has something to show, then fades it.
 *
 * The gap it covers is real and specific: the window opens with the right theme (the
 * activity resolves that synchronously before it draws), but the chosen wallpaper still
 * has to be decoded off disk on an IO thread, and until it lands the home screen renders
 * on a flat canvas and then visibly changes under the user. Waiting for that one fact,
 * rather than for a fixed duration, is what makes this a launch screen and not a delay.
 *
 * Three rules keep it from becoming the problem it solves:
 *  - it waits for nothing when there is nothing to wait for (no wallpaper set),
 *  - it never outlasts [MAX_HOLD_MS], so a slow or broken decode cannot trap the user,
 *  - it holds for at least [MIN_HOLD_MS] once shown, because a splash that flickers past
 *    in 40 ms is worse than none at all.
 */
@Composable
fun LaunchScreen() {
    var done by remember { mutableStateOf(false) }
    var ready by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        // The minimum hold is counted in frames that were actually drawn, not in wall
        // clock. On a cold start the main thread is saturated: the process is still
        // wiring itself up, settings are read, the wallpaper is decoded, and Compose
        // produces no frames at all during that. A wall-clock minimum therefore elapsed
        // entirely while the splash sat frozen on its very first frame: the sparkle only
        // began to move once the work finished, and by then the hold was long spent and
        // the splash faded immediately. That is what it looked like, "the stars
        // only start turning at the very end, and then it closes".
        //
        // withFrameNanos suspends until the choreographer actually delivers a frame, so
        // the clock below cannot start before the animation is genuinely on screen.
        val firstFrame = withFrameNanos { it }
        // Only the picture keeps us waiting; every other startup read is synchronous.
        if (LeanOptions.bgStyle == "image" && BackgroundImage.exists(LeanApp.instance)) {
            withTimeoutOrNull(MAX_HOLD_MS) {
                while (BackgroundImage.bitmap == null) delay(POLL_MS)
            }
        }
        // Keep waiting on the frame clock until the mark has had a visible run.
        while (withFrameNanos { it } - firstFrame < MIN_HOLD_MS * 1_000_000L) Unit
        ready = true
    }

    val alpha by animateFloatAsState(
        targetValue = if (ready) 0f else 1f,
        animationSpec = tween(durationMillis = FADE_MS),
        label = "splash-alpha",
        finishedListener = { if (it == 0f) done = true },
    )

    if (done) return
    Box(
        // Swallows taps while the splash is up, so a stray touch cannot reach and act on
        // a UI the user cannot see yet.
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent() } },
    ) {
        LeanSplashScrim(alpha)
        LeanSplash(alpha = alpha)
    }
}

/**
 * Long enough for the entrance to play out and the mark to visibly turn. It is drawn
 * frames now, so this is the time the user really sees rather than time the splash spent
 * frozen behind a busy main thread.
 */
private const val MIN_HOLD_MS = 1_100L
private const val MAX_HOLD_MS = 2_500L
private const val FADE_MS = 260
private const val POLL_MS = 30L

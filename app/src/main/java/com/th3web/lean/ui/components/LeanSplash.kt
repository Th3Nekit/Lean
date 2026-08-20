package com.th3web.lean.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.dp
import com.th3web.lean.ui.theme.LeanColors

/**
 * The launch screen: the icon's twin sparkle, breathing, over the app canvas.
 *
 * It exists because a cold start has a real gap: the process comes up, then the
 * wallpaper has to be decoded off disk and the server list read, during which the app
 * was showing a correct but empty version of itself. An empty home screen that fills in
 * a second later reads as a bug; a deliberate launch screen reads as launching.
 *
 * The mark is redrawn here rather than loaded from the launcher vector because it has to
 * animate: same geometry (a four-point sparkle plus a smaller companion, offset up and
 * right), same two inks. Kept to plain shapes so it costs nothing on the very frames
 * where the app is busy doing the work this is covering.
 */
@Composable
fun LeanSplash(modifier: Modifier = Modifier, alpha: Float = 1f) {
    val transition = rememberInfiniteTransition(label = "splash")
    // One shared clock: the big sparkle breathes, the small one twinkles against it on
    // a different period so the pair never pulses in lockstep.
    val breathe by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1_600, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "breathe",
    )
    val spin by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 5_000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "spin",
    )

    // A one-shot entrance, so the first frame the user actually sees is already moving.
    // The infinite transitions above turn slowly by design; on their own the mark looked
    // static for the whole splash and only started to lean just as it faded out.
    var appeared by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { appeared = true }
    val entrance by animateFloatAsState(
        targetValue = if (appeared) 1f else 0f,
        animationSpec = tween(durationMillis = 620, easing = FastOutSlowInEasing),
        label = "splash-entrance",
    )

    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(132.dp)) {
            val unit = size.minDimension / 28f
            val centre = Offset(size.width / 2f, size.height / 2f)

            // Big sparkle: scales gently and turns very slowly. The entrance adds the
            // swing that makes the mark read as arriving rather than as a static logo.
            val grow = 0.72f + 0.28f * entrance
            val bigRadius = unit * 11f * (0.92f + 0.08f * breathe) * grow
            rotate(degrees = spin * 0.35f - 40f * (1f - entrance), pivot = centre) {
                drawPath(
                    path = sparkle(centre, bigRadius),
                    color = Color.White.copy(alpha = alpha * entrance),
                )
            }

            // Companion: up and to the right, as on the icon, twinkling out of phase.
            // Its offset and its 0.376 size ratio are the launcher artwork's own, the
            // small star there sits at centre (19.2, 5.82) with radius 4.62 against the
            // big one's (12, 13.9), and 12.3.
            // The companion arrives a touch later than the big star, which is what makes
            // the pair feel drawn rather than switched on.
            val follow = ((entrance - 0.25f) / 0.75f).coerceIn(0f, 1f)
            val smallCentre = Offset(centre.x + unit * 7.2f, centre.y - unit * 8.1f)
            val smallRadius = bigRadius * 0.376f * (1.1f - 0.2f * breathe) * follow
            rotate(degrees = -spin * 0.8f + 60f * (1f - follow), pivot = smallCentre) {
                drawPath(
                    path = sparkle(smallCentre, smallRadius),
                    color = CompanionInk.copy(
                        alpha = alpha * follow * (0.55f + 0.45f * breathe),
                    ),
                )
            }
        }
    }
}

/** The icon's light-blue second star. */
private val CompanionInk = Color(0xFFAEC2EC)

/**
 * The launcher mark's four-point sparkle, at [radius] around [centre].
 *
 * The proportions are lifted from the vector rather than invented, so the splash and the
 * icon are the same mark: each quadrant runs tip → waist → tip through two cubics, with
 * the waist on the diagonal at [waist] of the radius and the control points at [near] and
 * [FAR], mirrored across that diagonal. Reading them off the artwork is also what exposed
 * the icon's own defect, the small star's last curve did not land back on its first
 * point, so the closing line sheared its top flat.
 */
private fun sparkle(centre: Offset, radius: Float): Path = Path().apply {
    moveTo(centre.x, centre.y - radius)
    // One quadrant, repeated four times a quarter-turn apart. The mark is symmetric, so
    // stating it once is both shorter and impossible to get subtly out of true, which is
    // precisely how the vector's small star ended up lopsided.
    repeat(4) { quadrant ->
        var i = 0
        while (i < QUADRANT.size) {
            val c1 = rotate(QUADRANT[i], quadrant)
            val c2 = rotate(QUADRANT[i + 1], quadrant)
            val end = rotate(QUADRANT[i + 2], quadrant)
            cubicTo(
                centre.x + c1.first * radius, centre.y + c1.second * radius,
                centre.x + c2.first * radius, centre.y + c2.second * radius,
                centre.x + end.first * radius, centre.y + end.second * radius,
            )
            i += 3
        }
    }
    close()
}

/** A quarter turn clockwise, [times] times, in screen coordinates (y grows downward). */
private fun rotate(p: Pair<Float, Float>, times: Int): Pair<Float, Float> {
    var (x, y) = p
    repeat(times) {
        val nx = -y
        y = x
        x = nx
    }
    return x to y
}

/**
 * The top-to-right quadrant in units of the radius, read off the launcher vector's big
 * star (centre 12,13.9 · radius 12.3): control 0.45/4.1, control 1.6/6.9, waist 3.5/3.5,
 * then control 4.7/3.05, control 8.8/0.45, tip 12.3/0. Two cubics, tip to waist, waist
 * to tip, mirrored about the diagonal, which is what gives the arms their pinch.
 */
private val QUADRANT = listOf(
    0.0366f to -0.6667f, 0.1301f to -0.4390f, 0.2846f to -0.2846f,
    0.4390f to -0.1301f, 0.6667f to -0.0366f, 1.0000f to 0.0000f,
)

/** Fills the window with the app canvas so nothing of the real UI shows through yet. */
@Composable
fun LeanSplashScrim(alpha: Float) {
    Canvas(Modifier.fillMaxSize()) {
        drawRect(LeanColors.Background.copy(alpha = alpha))
    }
}

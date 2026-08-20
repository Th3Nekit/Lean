package com.th3web.lean.ui.theme

import android.animation.ValueAnimator
import android.content.Context
import android.os.Build
import android.provider.Settings
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.InfiniteRepeatableSpec
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring as coreSpring
import androidx.compose.animation.core.tween as coreTween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback

/**
 * The one place a duration is multiplied.
 *
 * «Анимации» is four levels, not a switch: `off` must reach the infinite
 * transitions that request a frame every frame they stay composed, and
 * `calm`/`lively` must stretch the same durations proportionally. A knob like
 * that cannot be honoured by asking every call site to remember it, a `tween(300)`
 * left behind is visibly out of step at 0.7× and keeps running at `off`, which is
 * the failure this object exists to make impossible.
 *
 * [LeanOptions.motionDurationScale] is read on every call rather than captured:
 * these are snapshot reads made from inside composition, so changing the level
 * invalidates exactly the composables that animate and nothing else. Reading it
 * into a file-level `val` would freeze the whole app's motion at class load.
 */
object LeanMotion {

    /** 0f / 1.4f / 1f / 0.7f, `off` / `calm` / `normal` / `lively`. */
    val scale: Float get() = LeanOptions.motionDurationScale

    val enabled: Boolean get() = scale > 0f

    /**
     * Floored at 1ms rather than 0: a zero-duration `tween` is a legal but
     * degenerate spec, and at `off` we hand back [snap] instead anyway.
     */
    fun durationMs(ms: Int): Int = (ms * scale).toInt().coerceAtLeast(1)

    /**
     * A finite tween, or [snap] when motion is off, so a call site never has to
     * carry its own `if (animate)` around an `animationSpec`.
     */
    fun <T> tween(ms: Int, easing: Easing = FastOutSlowInEasing): FiniteAnimationSpec<T> =
        if (enabled) coreTween<T>(durationMs(ms), easing = easing) else snap<T>()

    /**
     * An endlessly repeating tween. Unlike [tween] this has no "off" form on
     * purpose: an infinite transition costs a frame callback for as long as it is
     * composed, so callers must gate its existence (`if (animate) { … }`), not
     * just its speed. See `RefreshGlyph` for the house idiom.
     */
    fun <T> loop(
        ms: Int,
        easing: Easing = LinearEasing,
        repeatMode: RepeatMode = RepeatMode.Restart,
    ): InfiniteRepeatableSpec<T> =
        infiniteRepeatable(coreTween<T>(durationMs(ms), easing = easing), repeatMode)

    /**
     * Springs have no duration to scale, so the level can only decide whether one
     * runs at all, [scale] does not stiffen them, which would make
     * `calm` feel mushy rather than slow.
     */
    fun <T> spring(stiffness: Float = Spring.StiffnessMedium): FiniteAnimationSpec<T> =
        if (enabled) coreSpring<T>(stiffness = stiffness) else snap<T>()
}

/**
 * Whether anything on screen may animate right now.
 *
 * The system animator scale used to be a hard veto here. It is now a floor: it
 * still wins by default («Учитывать системные настройки анимаций»), but a user
 * whose ROM force-disables animators to save battery can overrule it and get the
 * app's motion back, before, that ROM decided on their behalf with no way to
 * disagree. Default behaviour is unchanged.
 *
 * Gate the existence of infinite transitions on this, not merely their output.
 */
@Composable
fun motionAllowed(): Boolean {
    if (!LeanMotion.enabled) return false
    // Read second: when the user has opted out of the system floor there is no
    // reason to touch the platform at all.
    if (!LeanOptions.respectSystemAnimations) return true
    return systemAnimatorsEnabled(LocalContext.current)
}

/**
 * The raw platform animator scale. Lives here, beside the knob that decides
 * whether it still gets a vote, so the policy and its input cannot drift apart.
 */
internal fun systemAnimatorsEnabled(context: Context): Boolean =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        ValueAnimator.areAnimatorsEnabled()
    } else {
        Settings.Global.getFloat(
            context.contentResolver,
            Settings.Global.ANIMATOR_DURATION_SCALE,
            1f,
        ) > 0f
    }

/**
 * The app's tap feedback, honouring «Вибро-отклик».
 *
 * The level is read inside the returned lambda rather than during composition:
 * haptics fire from input callbacks, so reading it there is free and stops a
 * settings change from recomposing every control that owns one.
 *
 * Only the two feedback constants Compose 1.7 ships are used, `TextHandleMove`
 * is the light tick, `LongPress` the firm one.
 */
@Composable
fun rememberTapHaptic(): () -> Unit {
    val haptics = LocalHapticFeedback.current
    return remember<() -> Unit>(haptics) {
        {
            when (LeanOptions.haptics) {
                "none" -> Unit
                "light" -> haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                else -> haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            }
        }
    }
}

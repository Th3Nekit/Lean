package com.th3web.lean.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.th3web.lean.ui.theme.LeanColors
import com.th3web.lean.ui.theme.LeanCorner
import com.th3web.lean.ui.theme.LeanType
import kotlin.math.roundToInt

/**
 * The «Оформление» slider, a stock MD3 [Slider] wearing the app's traffic-bar recipe.
 *
 * stock,. A hand-rolled `pointerInput` is the one control here that could
 * plausibly break: these sliders live inside a `verticalScroll` column, and a raw
 * horizontal drag detector fights the scroll for the gesture. The Material slider already
 * resolves that correctly, so all we replace are its `thumb` and `track` slots.
 *
 * Integer,. Every knob it serves is persisted as an `Int` percent or step, so
 * a `Float` API would only add a rounding step at each call-site, and two of them could
 * round differently.
 *
 * the commit rule. [onValueChange] fires on every frame of the drag and must only move
 * screen-local state (the preview's draft look). [onValueChangeFinished] fires once, on
 * release, with the final value, and is the only place a settings write belongs. One
 * settings write costs a DataStore file rewrite, a full JSON re-encode, a synchronous
 * `SharedPreferences` snapshot commit with an fsync, a new `ColorScheme` and a new
 * `Typography`; at 90 frames of drag that is not slow, it is a stall.
 *
 * The draft lives here rather than at the call-site so the rule is structural: the thumb
 * follows the finger whether or not the caller feeds anything back, and it is released the
 * moment [value] changes, the commit landing, a preset being applied, «Сбросить
 * оформление», so an external write still moves it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LeanSlider(
    value: Int,
    onValueChange: (Int) -> Unit,
    onValueChangeFinished: (Int) -> Unit,
    modifier: Modifier = Modifier,
    range: IntRange = 0..100,
    step: Int = 1,
    enabled: Boolean = true,
    label: String? = null,
    valueLabel: String? = null,
) {
    val span = (range.last - range.first).coerceAtLeast(1)
    val stride = step.coerceIn(1, span)
    // Material counts the ticks between the ends, so 0..20 in steps of 1 is 19, not 21.
    val steps = (span / stride - 1).coerceAtLeast(0)

    var draft by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(value) { draft = null }
    val shown = (draft ?: value).coerceIn(range.first, range.last)

    Column(modifier.fillMaxWidth()) {
        if (label != null || valueLabel != null) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                if (label != null) {
                    Text(
                        label,
                        color = LeanColors.TextPrimary,
                        style = LeanType.rowTitle,
                        modifier = Modifier.weight(1f),
                    )
                } else {
                    // Keeps the readout pinned to the trailing edge when the caller titles
                    // the slider itself (a section label above it, say) instead of here.
                    Spacer(Modifier.weight(1f))
                }
                if (valueLabel != null) {
                    // Accent, not TextSecondary: during a drag this readout is the only
                    // exact feedback there is, and it has to be findable at a glance.
                    Text(valueLabel, color = LeanColors.Accent, style = LeanType.valuePill)
                }
            }
        }
        Slider(
            value = shown.toFloat(),
            onValueChange = { raw ->
                val next = raw.roundToInt().coerceIn(range.first, range.last)
                // Material emits on every pointer move, including sub-step ones that
                // round back to where we already are.
                if (next != shown) {
                    draft = next
                    onValueChange(next)
                }
            },
            modifier = Modifier.fillMaxWidth(),
            enabled = enabled,
            // `draft` is snapshot state read at invocation time, not captured by value, so
            // this reports what the finger actually left behind even if the caller never
            // fed onValueChange back into `value`.
            onValueChangeFinished = { onValueChangeFinished(draft ?: value) },
            steps = steps,
            thumb = { LeanSliderThumb(enabled) },
            track = { state -> LeanSliderTrack(state, enabled) },
            valueRange = range.first.toFloat()..range.last.toFloat(),
        )
    }
}

/**
 * The traffic-bar recipe as a slider track: a [LeanCorner.BarFill]-capped 6dp rail in
 * [LeanColors.BarUnlit] with an accent fill up to the current fraction. Same drawing as the
 * subscription card's quota bar,, one bar idiom in the app, not two.
 *
 * The fraction is computed here because Material keeps its own `coercedValueAsFraction`
 * internal. Both reads are of `state`, so nothing is captured or stale.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LeanSliderTrack(state: SliderState, enabled: Boolean) {
    val span = state.valueRange.endInclusive - state.valueRange.start
    val fraction = if (span <= 0f) 0f else ((state.value - state.valueRange.start) / span).coerceIn(0f, 1f)
    Box(
        Modifier
            .fillMaxWidth()
            .height(TrackHeight)
            .clip(LeanCorner.BarFill)
            .background(LeanColors.BarUnlit),
    ) {
        Box(
            Modifier
                .fillMaxWidth(fraction)
                .height(TrackHeight)
                .clip(LeanCorner.BarFill)
                .background(if (enabled) LeanColors.Accent else LeanColors.TextTertiary),
        )
    }
}

/**
 * The thumb: an accent disc rimmed in [LeanColors.OnAccent]. The rim is what keeps it
 * legible where it sits on the accent-filled half of the track, `onPrimary` is by
 * construction the highest-contrast partner of `primary`, on both columns, so the rim
 * inverts correctly when the theme flips instead of needing a per-canvas value.
 */
@Composable
private fun LeanSliderThumb(enabled: Boolean) {
    val fill = if (enabled) LeanColors.Accent else LeanColors.TextTertiary
    Box(
        Modifier
            .size(ThumbSize)
            .clip(CircleShape)
            .background(fill)
            .border(ThumbRim, LeanColors.OnAccent, CircleShape),
    )
}

private val TrackHeight = 6.dp
private val ThumbSize = 20.dp
private val ThumbRim = 2.dp

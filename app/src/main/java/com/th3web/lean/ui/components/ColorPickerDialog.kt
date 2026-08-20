package com.th3web.lean.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.th3web.lean.data.SettingsDefaults
import com.th3web.lean.ui.theme.LeanAccents
import com.th3web.lean.ui.theme.LeanColors
import com.th3web.lean.ui.theme.LeanCorner
import com.th3web.lean.ui.theme.LeanType
import com.th3web.lean.ui.tr
import android.graphics.Color as AndroidColor

/**
 * «Свой цвет», pick an accent seed by hue and saturation, with a hex field and the six
 * most recent picks.
 *
 * Two bars and no colour wheel because the seed only has two meaningful degrees of freedom:
 * `LeanAccents.fromSeed` rotates the hand-tuned Steel ladder onto the seed's HUE and scales
 * its saturation, then renormalises every stop back to Steel's own luminance. The seed's
 * brightness is never read. A wheel would offer a third axis that does nothing.
 *
 * The preview shows the synthesised accent, not the raw pick. «Приглушённость» caps chroma,
 * so a vivid pick lands visibly muted, showing the raw colour would promise something the
 * app will not draw.
 *
 * This is the one place in «Оформление» with a hand-written horizontal drag, and it lives
 * inside an [AlertDialog], outside the settings screen's scrolling column, so the classic
 * "horizontal drag inside a vertical scroll" conflict cannot arise.
 *
 * Nothing is written until «Применить»: [onConfirm] carries the chosen ARGB, [onDismiss]
 * throws the draft away.
 */
@Composable
fun ColorPickerDialog(
    initial: Long,
    title: String,
    onDismiss: () -> Unit,
    onConfirm: (Long) -> Unit,
    recent: List<Long> = emptyList(),
    chromaClamp: Int = SettingsDefaults.ACCENT_CHROMA,
) {
    val start = remember(initial) { hsvOf(initial) }
    var hue by remember(initial) { mutableStateOf(start[0]) }
    var sat by remember(initial) { mutableStateOf(start[1]) }
    // Held rather than edited: the bars own hue and saturation, and a pasted hex owns all
    // three. Floored because a seed picked out of a near-black corner would make the bars
    // and the swatch read as one flat dark smear.
    var bright by remember(initial) { mutableStateOf(start[2].coerceAtLeast(MinBrightness)) }
    val argb = argbOf(hue, sat, bright)

    var hex by remember(initial) { mutableStateOf(hex6(initial)) }
    // Keyed on the resolved colour, not on the text: a half-typed "FF00" parses to nothing,
    // leaves h/s/v alone, leaves [argb] alone, and so never re-runs this and never yanks
    // the four characters back out from under the caret. Every path that really moves the
    // colour (either bar, a recent swatch, a complete hex) does change [argb] and does
    // rewrite the field, including when the brightness floor corrected the pick.
    LaunchedEffect(argb) { hex = hex6(argb) }

    val accent = remember(argb, chromaClamp) { LeanAccents.resolve(argb, chromaClamp) }
    val onLight = LeanColors.light

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                // What the app will actually paint: the resolved primary, its dim partner
                // and the "in motion" tone, side by side.
                Row(
                    Modifier
                        .fillMaxWidth()
                        .height(SwatchHeight)
                        .clip(LeanCorner.Button),
                ) {
                    Box(
                        Modifier
                            .weight(2f)
                            .fillMaxHeight()
                            .background(if (onLight) accent.lightPrimary else accent.primary),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            "#$hex",
                            color = if (onLight) accent.lightOnPrimary else accent.onPrimary,
                            style = LeanType.valuePill,
                        )
                    }
                    Box(
                        Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .background(if (onLight) accent.lightConnecting else accent.connecting),
                    )
                    Box(
                        Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .background(if (onLight) accent.lightDim else accent.dim),
                    )
                }
                Spacer(Modifier.height(14.dp))
                GradientBar(
                    brush = HueBrush,
                    fraction = hue / 360f,
                    onFraction = { f -> hue = (f * 360f).coerceIn(0f, 359.99f) },
                )
                Spacer(Modifier.height(10.dp))
                GradientBar(
                    brush = remember(hue, bright) { saturationBrush(hue, bright) },
                    fraction = sat,
                    onFraction = { f -> sat = f },
                )
                Spacer(Modifier.height(14.dp))
                OutlinedTextField(
                    value = hex,
                    onValueChange = { raw ->
                        val cleaned = raw.trim().removePrefix("#").filter { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' }
                        hex = cleaned.take(6).uppercase()
                        parseHex(hex)?.let { picked ->
                            val hsv = hsvOf(picked)
                            hue = hsv[0]
                            sat = hsv[1]
                            bright = hsv[2].coerceAtLeast(MinBrightness)
                        }
                    },
                    singleLine = true,
                    prefix = { Text("#", color = LeanColors.TextTertiary) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (recent.isNotEmpty()) {
                    Spacer(Modifier.height(14.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        recent.take(RecentMax).forEach { seed ->
                            LeanSwatch(
                                fill = Color(seed.toInt()),
                                selected = seed == argb,
                                onClick = {
                                    val hsv = hsvOf(seed)
                                    hue = hsv[0]
                                    sat = hsv[1]
                                    bright = hsv[2].coerceAtLeast(MinBrightness)
                                },
                                size = RecentSwatchSize,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(argb) }) { Text(tr("Применить")) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(tr("Отмена"), color = LeanColors.TextSecondary) }
        },
    )
}

/**
 * One draggable gradient rail.
 *
 * Both gesture detectors are needed and neither is redundant: [detectHorizontalDragGestures]
 * carries the drag, [detectTapGestures] makes a plain tap jump the marker. A drag consumes
 * its events after slop, which cancels the tap detector's own pending up, so a drag never
 * also fires a tap.
 *
 * The marker travels inset by half the rail's height at each end. The rail is clipped to a
 * stadium, so a marker centred at fraction 0 would be sliced in half; insetting both the
 * drawing and the hit mapping keeps the two in agreement.
 */
@Composable
private fun GradientBar(brush: Brush, fraction: Float, onFraction: (Float) -> Unit) {
    val clamped = fraction.coerceIn(0f, 1f)
    // `pointerInput(Unit)` never restarts, restarting it would cancel a drag
    // in progress on every recomposition, i.e. on every frame of that same drag. The price
    // is that its block captures whatever lambda was current when it started, so the
    // callback has to be routed through a state holder that stays put.
    val emit by rememberUpdatedState(onFraction)
    Box(
        Modifier
            .fillMaxWidth()
            .height(BarHeight)
            .clip(LeanCorner.Pill)
            .background(brush)
            .border(1.dp, LeanColors.Outline, LeanCorner.Pill)
            .drawWithContent {
                drawContent()
                val inset = size.height / 2f
                val x = inset + clamped * (size.width - 2f * inset)
                val center = Offset(x, size.height / 2f)
                val r = size.height * MarkerRadiusRatio
                // A white ring inside a black one reads on every hue the bar can show,
                // including the pale end of the saturation rail.
                drawCircle(Color.Black.copy(alpha = 0.55f), r + 1.dp.toPx(), center, style = Stroke(1.dp.toPx()))
                drawCircle(Color.White, r, center, style = Stroke(2.dp.toPx()))
            }
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragStart = { offset -> emit(fractionAt(offset.x, size.width.toFloat(), size.height / 2f)) },
                ) { change, _ ->
                    change.consume()
                    emit(fractionAt(change.position.x, size.width.toFloat(), size.height / 2f))
                }
            }
            .pointerInput(Unit) {
                detectTapGestures { offset ->
                    emit(fractionAt(offset.x, size.width.toFloat(), size.height / 2f))
                }
            },
    )
}

private fun fractionAt(x: Float, width: Float, inset: Float): Float {
    val travel = width - 2f * inset
    if (travel <= 0f) return 0f
    return ((x - inset) / travel).coerceIn(0f, 1f)
}

/** Thirteen stops at 30° each, enough that the interpolation between them is invisible. */
private val HueBrush: Brush = Brush.horizontalGradient(
    List(13) { i -> Color(AndroidColor.HSVToColor(floatArrayOf(i * 30f % 360f, BarSaturation, BarBrightness))) },
)

private fun saturationBrush(hue: Float, bright: Float): Brush = Brush.horizontalGradient(
    listOf(
        Color(AndroidColor.HSVToColor(floatArrayOf(hue, 0f, bright))),
        Color(AndroidColor.HSVToColor(floatArrayOf(hue, 1f, bright))),
    ),
)

/** `android.graphics.Color.colorToHSV` is API 1, so the whole picker needs no version gate. */
private fun hsvOf(argb: Long): FloatArray {
    val hsv = FloatArray(3)
    AndroidColor.colorToHSV((argb or 0xFF000000L).toInt(), hsv)
    return hsv
}

private fun argbOf(hue: Float, sat: Float, bright: Float): Long =
    AndroidColor.HSVToColor(floatArrayOf(hue, sat.coerceIn(0f, 1f), bright.coerceIn(0f, 1f)))
        .toLong() and 0xFFFFFFFFL or 0xFF000000L

private fun hex6(argb: Long): String = "%06X".format(argb and 0xFFFFFFL)

/** Null for anything that is not exactly six hex digits: a half-typed code must not jump the bars. */
private fun parseHex(text: String): Long? {
    if (text.length != 6) return null
    return text.toLongOrNull(16)?.or(0xFF000000L)
}

// The hue rail is drawn at a fixed, slightly toned saturation/brightness: tying it to the
// current pick would fade the whole rail to gray at sat 0, exactly when the user needs to
// see which hue they are on.
private const val BarSaturation = 0.85f
private const val BarBrightness = 0.95f
private const val MinBrightness = 0.5f
private const val MarkerRadiusRatio = 0.28f
private const val RecentMax = 6

private val BarHeight = 26.dp
private val SwatchHeight = 44.dp
private val RecentSwatchSize = 28.dp

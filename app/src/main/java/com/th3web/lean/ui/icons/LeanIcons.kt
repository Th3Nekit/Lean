package com.th3web.lean.ui.icons

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.unit.dp

/**
 * Custom "Lean" duotone icon set, ported from the web prototype (`_proto/lean.html`, `const SVG`).
 *
 * Two families:
 *  - STROKE-only nav/confirm glyphs (`ST` in the prototype: stroke-width 2.2, round caps/joins;
 *    `check` uses 2.6).
 *  - FILLED duotone "bulk" glyphs (`if`): a soft silhouette (often `opacity .45`/`.55`), a solid
 *    accent, and an occasional white "spark". White accents must stay white regardless of tint,
 *    so we render with [Image] + a vector painter rather than [androidx.compose.material3.Icon]
 *    (which would re-tint everything).
 *
 * All `<circle>` / `<rect>` layers from the prototype are converted to SVG path-data strings so the
 * whole icon can be fed to [PathParser].
 */
enum class LeanIcon {
    Chev, Back, Plus, Check, Sort, Power, Pulse, Palette, Lang, Apps, Cable, Layers,
    Route, Cloud, Speed, Info, Heart, Globe, Lan, Hub, Shield, Split, Gear, Servers,
    Refresh, Dots, Search, Support
}

/** How a single transcribed layer should be painted. */
private sealed interface LayerKind {
    /** Filled layer. [white] forces [Color.White] (spark accents); otherwise the tint is used. */
    data class Fill(val alpha: Float = 1f, val white: Boolean = false) : LayerKind
    /** Stroked layer (no fill); always drawn in the tint color. */
    data class Stroke(val widthPx: Float) : LayerKind
}

/** A single drawable layer: SVG path-data plus how to paint it. */
private class Layer(
    val pathData: String,
    val kind: LayerKind,
    val fillType: PathFillType = PathFillType.NonZero,
)

/* ---------------------------------------------------------------------------
 * Geometry helpers, convert <circle>/<rect> to SVG path-data strings.
 * ------------------------------------------------------------------------- */

/** `<circle cx cy r>` as a closed two-arc path. */
private fun circle(cx: Double, cy: Double, r: Double): String {
    val left = cx - r
    val d = r * 2
    return "M$left,${cy}a$r,$r 0 1,0 $d,0a$r,$r 0 1,0 ${-d},0z"
}

/** `<rect x y w h rx>` as a closed rounded-rect path (uniform corner radius). */
private fun rect(x: Double, y: Double, w: Double, h: Double, rx: Double): String {
    if (rx <= 0.0) {
        return "M$x,${y}h${w}v${h}h${-w}z"
    }
    val ix = w - 2 * rx // straight horizontal run
    val iy = h - 2 * rx // straight vertical run
    return buildString {
        append("M${x + rx},$y")
        append("h$ix")
        append("a$rx,$rx 0 0 1 $rx,$rx")
        append("v$iy")
        append("a$rx,$rx 0 0 1 ${-rx},$rx")
        append("h${-ix}")
        append("a$rx,$rx 0 0 1 ${-rx},${-rx}")
        append("v${-iy}")
        append("a$rx,$rx 0 0 1 $rx,${-rx}")
        append("z")
    }
}

/* ---------------------------------------------------------------------------
 * Layer tables, one per icon, in SVG paint order.
 * ------------------------------------------------------------------------- */

private fun layersFor(icon: LeanIcon): List<Layer> = when (icon) {

    // ---- STROKE-only nav / confirm (ST: stroke-width 2.2; check 2.6) ----
    LeanIcon.Chev -> listOf(
        Layer("M9.5 5.5 16 12l-6.5 6.5", LayerKind.Stroke(2.2f)),
    )
    LeanIcon.Back -> listOf(
        Layer("M19 12H6m5-6L5 12l6 6", LayerKind.Stroke(2.2f)),
    )
    LeanIcon.Plus -> listOf(
        Layer("M12 5.5v13M5.5 12h13", LayerKind.Stroke(2.2f)),
    )
    LeanIcon.Check -> listOf(
        Layer("M5 12.5l4.5 4.5L19 7", LayerKind.Stroke(2.6f)),
    )
    LeanIcon.Sort -> listOf(
        Layer("M7 4v13M7 4 4 7.5M7 4l3 3.5M17 20V7M17 20l3-3.5M17 20l-3-3.5", LayerKind.Stroke(2.2f)),
    )
    LeanIcon.Power -> listOf(
        Layer("M12 3v8.4", LayerKind.Stroke(2.2f)),
        Layer("M6.8 7a8 8 0 1 0 10.4 0", LayerKind.Stroke(2.2f)),
    )
    LeanIcon.Pulse -> listOf(
        Layer("M2.5 12h4l2.2-6.5 4 13L19 12h2.5", LayerKind.Stroke(2.2f)),
    )

    // ---- FILLED duotone (if) ----
    LeanIcon.Palette -> listOf(
        Layer(circle(12.0, 12.0, 9.0), LayerKind.Fill(alpha = 0.45f)),
        // The crescent's outer edge must trace the same r=9 circle as the base layer
        // above (its two endpoints are points on that circle, and its arc radius
        // matches): that is what guarantees it can never draw past the disc it is
        // meant to sit inside. The path this replaces used an outer arc whose two
        // endpoints were off that circle by a few hundredths (a transcription
        // rounding slip from the web prototype this set was ported from), which is
        // small enough to miss on a quick look but large enough that the crescent's
        // lower tip visibly poked out past the base circle at render size, "the
        // moon sticks out of the circle" in Lean's Appearance hub tile, verified
        // with a rendered before/after and a swept distance check across the whole
        // icon set (every other icon's layers already sit inside their own frame).
        // Inner arc radius (7) only controls sliver thickness and has no bearing on
        // the containment guarantee, which comes entirely from the outer arc.
        Layer("M11.216 3.034A9 9 0 1 0 20.966 11.216A7 7 0 1 1 11.216 3.034z", LayerKind.Fill()),
        Layer("M7.7 13.6 8.2 15l1.4.5-1.4.5-.5 1.4-.5-1.4L5.3 15.5l1.4-.5z", LayerKind.Fill()),
    )
    LeanIcon.Lang -> listOf(
        Layer(
            "M3.5 4h9.5A2.5 2.5 0 0 1 15.5 6.5v3A2.5 2.5 0 0 1 13 12H9l-3.4 2.8V12H3.5A2.5 2.5 0 0 1 1 9.5v-3A2.5 2.5 0 0 1 3.5 4z",
            LayerKind.Fill(alpha = 0.45f),
        ),
        Layer(
            "M11 10.5h9.5A2.5 2.5 0 0 1 23 13v3a2.5 2.5 0 0 1-2.5 2.5H20v2.4L16.6 18.5H11A2.5 2.5 0 0 1 8.5 16v-3A2.5 2.5 0 0 1 11 10.5z",
            LayerKind.Fill(),
        ),
    )
    LeanIcon.Apps -> listOf(
        // <g opacity=".45"> → apply .45 to each child
        Layer(rect(3.4, 3.4, 7.0, 7.0, 2.3), LayerKind.Fill(alpha = 0.45f)),
        Layer(rect(13.6, 3.4, 7.0, 7.0, 2.3), LayerKind.Fill(alpha = 0.45f)),
        Layer(rect(3.4, 13.6, 7.0, 7.0, 2.3), LayerKind.Fill(alpha = 0.45f)),
        Layer(rect(13.6, 13.6, 7.0, 7.0, 2.3), LayerKind.Fill()),
        Layer(
            "M17.1 14.8 17.7 16.5 19.4 17.1 17.7 17.7 17.1 19.4 16.5 17.7 14.8 17.1 16.5 16.5z",
            LayerKind.Fill(white = true),
        ),
    )
    LeanIcon.Cable -> listOf(
        Layer(rect(6.4, 7.4, 11.2, 6.6, 3.1), LayerKind.Fill(alpha = 0.45f)),
        Layer(rect(9.0, 2.8, 2.3, 5.2, 1.15), LayerKind.Fill()),
        Layer(rect(12.7, 2.8, 2.3, 5.2, 1.15), LayerKind.Fill()),
        Layer(rect(10.85, 13.0, 2.3, 8.2, 1.15), LayerKind.Fill()),
    )
    LeanIcon.Layers -> listOf(
        // <g opacity=".45">
        Layer("M12 10.6 21.2 14.6 12 18.6 2.8 14.6z", LayerKind.Fill(alpha = 0.45f)),
        Layer("M12 7 21.2 11 12 15 2.8 11z", LayerKind.Fill(alpha = 0.45f)),
        Layer("M12 3.4 21.2 7.4 12 11.4 2.8 7.4z", LayerKind.Fill()),
    )
    LeanIcon.Route -> listOf(
        // prototype draws this stroke at opacity .45, but the required Stroke model has no alpha,
        // so it is rendered at full tint: stroke colour follows the tint, width does not.
        Layer("M6 18.5h4a5 5 0 0 0 5-5V7.5", LayerKind.Stroke(2.4f)),
        Layer(circle(6.0, 18.5, 3.0), LayerKind.Fill()),
        Layer(circle(16.0, 6.0, 3.0), LayerKind.Fill()),
    )
    LeanIcon.Cloud -> listOf(
        Layer(
            "M7 18.6a4.6 4.6 0 0 1-.7-9.1A5.9 5.9 0 0 1 17.8 11 3.8 3.8 0 0 1 17.4 18.6z",
            LayerKind.Fill(alpha = 0.55f),
        ),
        Layer(
            "M12 8.6 12.85 11 15.2 11.85 12.85 12.7 12 15.1 11.15 12.7 8.8 11.85 11.15 11z",
            LayerKind.Fill(alpha = 0.92f, white = true),
        ),
    )
    LeanIcon.Speed -> listOf(
        // Soft filled half-circle dome (gauge dial): a semicircular arc over the top,
        // flat side down, opening downward. Center of the gauge is (12, 15).
        Layer("M3.5 15A8.5 8.5 0 0 1 20.5 15Z", LayerKind.Fill(alpha = 0.45f)),
        // Straight solid needle from the dial's center (12, 15) pointing up-and-to-the-right,
        // as a slim tapered sliver (speedometer needle).
        Layer("M12.81 15.74 17.5 9 11.19 14.26z", LayerKind.Fill()),
        // Solid pivot hub at the gauge center.
        Layer(circle(12.0, 15.0, 1.6), LayerKind.Fill()),
    )
    LeanIcon.Info -> listOf(
        Layer(circle(12.0, 12.0, 9.0), LayerKind.Fill(alpha = 0.45f)),
        Layer(rect(10.8, 10.8, 2.4, 6.6, 1.2), LayerKind.Fill()),
        Layer(circle(12.0, 7.5, 1.5), LayerKind.Fill()),
    )
    LeanIcon.Heart -> listOf(
        Layer(
            "M12 20.4S4.2 15.6 4.2 9.8A4.2 4.2 0 0 1 12 7a4.2 4.2 0 0 1 7.8 2.8c0 5.8-7.8 10.6-7.8 10.6z",
            LayerKind.Fill(),
        ),
    )
    LeanIcon.Globe -> listOf(
        Layer(circle(12.0, 12.0, 9.0), LayerKind.Fill(alpha = 0.45f)),
        Layer(rect(3.0, 10.8, 18.0, 2.4, 1.2), LayerKind.Fill()),
        Layer("M12 3a13 13 0 0 1 0 18M12 3a13 13 0 0 0 0 18", LayerKind.Stroke(2f)),
    )
    LeanIcon.Lan -> listOf(
        // <g opacity=".45">
        Layer(rect(9.0, 3.0, 6.0, 5.0, 1.6), LayerKind.Fill(alpha = 0.45f)),
        Layer(rect(3.0, 16.0, 6.0, 5.0, 1.6), LayerKind.Fill(alpha = 0.45f)),
        Layer(rect(15.0, 16.0, 6.0, 5.0, 1.6), LayerKind.Fill(alpha = 0.45f)),
        Layer("M12 8v3.4M6 13.6V16M18 13.6V16M6 13.6h12", LayerKind.Stroke(2f)),
    )
    LeanIcon.Hub -> listOf(
        // <g opacity=".45">
        Layer(circle(12.0, 4.0, 2.0), LayerKind.Fill(alpha = 0.45f)),
        Layer(circle(12.0, 20.0, 2.0), LayerKind.Fill(alpha = 0.45f)),
        Layer(circle(4.6, 8.0, 2.0), LayerKind.Fill(alpha = 0.45f)),
        Layer(circle(19.4, 8.0, 2.0), LayerKind.Fill(alpha = 0.45f)),
        Layer("M12 6v3.4M10.2 10.9 6.1 8.7M13.8 10.9 17.9 8.7", LayerKind.Stroke(2f)),
        Layer(circle(12.0, 12.0, 3.0), LayerKind.Fill()),
    )
    LeanIcon.Shield -> listOf(
        Layer(
            "M12 2.4 20 5.4v6c0 5-3.4 8.8-8 10.2-4.6-1.4-8-5.2-8-10.2v-6z",
            LayerKind.Fill(alpha = 0.45f),
        ),
        Layer("M8.4 11.8 11 14.4l4.6-4.7", LayerKind.Stroke(2.3f)),
    )
    LeanIcon.Split -> listOf(
        Layer("M5.6 5h6.6M5.6 19h6.6", LayerKind.Stroke(2.2f)),
        Layer(rect(3.0, 3.4, 2.4, 17.2, 1.2), LayerKind.Fill()),
        Layer("M11.6 2.4 16.4 5 11.6 7.6z", LayerKind.Fill()),
        Layer("M11.6 16.4 16.4 19 11.6 21.6z", LayerKind.Fill()),
    )
    LeanIcon.Gear -> listOf(
        Layer(
            "M19.43 12.98c.04-.32.07-.64.07-.98s-.03-.66-.07-.98l2.11-1.65a.5.5 0 0 0 .12-.64l-2-3.46a.5.5 0 0 0-.61-.22l-2.49 1c-.52-.4-1.08-.73-1.69-.98l-.38-2.65A.49.49 0 0 0 14 2h-4a.49.49 0 0 0-.49.42l-.38 2.65c-.61.25-1.17.59-1.69.98l-2.49-1a.5.5 0 0 0-.61.22l-2 3.46a.5.5 0 0 0 .12.64l2.11 1.65c-.04.32-.07.65-.07.98s.03.66.07.98l-2.11 1.65a.5.5 0 0 0-.12.64l2 3.46a.5.5 0 0 0 .61.22l2.49-1c.52.4 1.08.73 1.69.98l.38 2.65a.49.49 0 0 0 .49.42h4a.49.49 0 0 0 .49-.42l.38-2.65c.61-.25 1.17-.59 1.69-.98l2.49 1a.5.5 0 0 0 .61-.22l2-3.46a.5.5 0 0 0-.12-.64l-2.11-1.65zM12 15.5a3.5 3.5 0 1 1 0-7 3.5 3.5 0 0 1 0 7z",
            LayerKind.Fill(),
            fillType = PathFillType.EvenOdd,
        ),
    )
    LeanIcon.Servers -> listOf(
        // <g opacity=".45">
        Layer(rect(3.4, 4.0, 17.2, 6.6, 2.3), LayerKind.Fill(alpha = 0.45f)),
        Layer(rect(3.4, 13.4, 17.2, 6.6, 2.3), LayerKind.Fill(alpha = 0.45f)),
        Layer(circle(7.4, 7.3, 1.4), LayerKind.Fill()),
        Layer(circle(7.4, 16.7, 1.4), LayerKind.Fill()),
    )
    LeanIcon.Refresh -> listOf(
        Layer(
            "M12 5.4a6.6 6.6 0 1 1-6.3 8.6 1.3 1.3 0 0 1 2.5-.8A4 4 0 1 0 12 8z",
            LayerKind.Fill(alpha = 0.45f),
        ),
        Layer(
            "M12.4 2.5 8.2 5.4a.85.85 0 0 0 0 1.4l4.2 2.9A.85.85 0 0 0 13.8 9V3.2a.85.85 0 0 0-1.4-.7z",
            LayerKind.Fill(),
        ),
    )
    LeanIcon.Dots -> listOf(
        Layer(circle(5.0, 12.0, 1.9), LayerKind.Fill()),
        Layer(circle(12.0, 12.0, 1.9), LayerKind.Fill()),
        Layer(circle(19.0, 12.0, 1.9), LayerKind.Fill()),
    )
    LeanIcon.Search -> listOf(
        Layer(circle(11.0, 11.0, 6.8), LayerKind.Fill(alpha = 0.45f)),
        Layer(circle(11.0, 11.0, 6.8), LayerKind.Stroke(2f)),
        Layer("M20.6 20.6 16.3 16.3", LayerKind.Stroke(2.4f)),
    )
    LeanIcon.Support -> listOf(
        Layer(
            "M4 4.5h16A2.4 2.4 0 0 1 22.4 6.9v7.2A2.4 2.4 0 0 1 20 16.5h-8L6.2 20.6V16.5H4A2.4 2.4 0 0 1 1.6 14.1V6.9A2.4 2.4 0 0 1 4 4.5z",
            LayerKind.Fill(alpha = 0.45f),
        ),
        Layer(circle(8.4, 10.5, 1.5), LayerKind.Fill()),
        Layer(circle(12.0, 10.5, 1.5), LayerKind.Fill()),
        Layer(circle(15.6, 10.5, 1.5), LayerKind.Fill()),
    )
}

/* ---------------------------------------------------------------------------
 * Vector build.
 * ------------------------------------------------------------------------- */

private fun nodes(d: String) = PathParser().parsePathString(d).toNodes()

/** Build an [ImageVector] for this icon tinted with [tint]; white sparks stay white. */
fun LeanIcon.toImageVector(tint: Color): ImageVector {
    val builder = ImageVector.Builder(
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    )
    for (layer in layersFor(this)) {
        when (val kind = layer.kind) {
            is LayerKind.Fill -> builder.addPath(
                pathData = nodes(layer.pathData),
                pathFillType = layer.fillType,
                fill = SolidColor(if (kind.white) Color.White else tint),
                fillAlpha = kind.alpha,
            )
            is LayerKind.Stroke -> builder.addPath(
                pathData = nodes(layer.pathData),
                pathFillType = layer.fillType,
                stroke = SolidColor(tint),
                strokeLineWidth = kind.widthPx,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }
    }
    return builder.build()
}

/**
 * Render a [LeanIcon] preserving its duotone colors (tinted body + white spark).
 * Uses [Image] + [rememberVectorPainter] so no single content-tint flattens the icon.
 */
@Composable
fun LeanIconImage(
    icon: LeanIcon,
    tint: Color,
    modifier: Modifier = Modifier.size(20.dp),
) {
    val vector = remember(icon, tint) { icon.toImageVector(tint) }
    Image(
        painter = rememberVectorPainter(vector),
        contentDescription = null,
        modifier = modifier,
    )
}

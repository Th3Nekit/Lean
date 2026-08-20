package com.th3web.lean.ui.theme

import android.graphics.Bitmap
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

/**
 * The app canvas, «Фон приложения».
 *
 * `flat` is the shipping look and a single fill. The other three add texture without a
 * backdrop blur: this design is tonal surfaces plus visible hairlines, not glassmorphism,
 * and `RenderEffect` would need API 31 against a minSdk of 24 anyway.
 *
 * Written as [drawWithCache] rather than as a `@Composable` modifier for two reasons: the
 * brushes and the noise shader are rebuilt only when the size or the look actually
 * changes (state read inside the cache block is observed, so a theme flip re-runs it and
 * redraws without recomposing the screen under it), and it stays callable from the plain
 * `Modifier` chains the five screen roots already have.
 */
fun Modifier.leanBackground(): Modifier = drawWithCache {
    val base = LeanColors.Background
    when (LeanOptions.bgStyle) {
        "vignette" -> {
            // A true-black canvas has nothing left to darken, so there the vignette
            // inverts into a faint centre lift instead of a dead knob. Everywhere else it
            // is the classic thing: untouched centre, edges pulled toward black.
            val radius = max(size.width, size.height) * VignetteReach
            val centre = Offset(size.width / 2f, size.height / 2f)
            val brush = if (LeanColors.amoled) {
                Brush.radialGradient(
                    listOf(Color.White.copy(alpha = VignetteAlpha), Color.Transparent),
                    center = centre,
                    radius = radius,
                )
            } else {
                Brush.radialGradient(
                    listOf(Color.Transparent, Color.Black.copy(alpha = VignetteAlpha)),
                    center = centre,
                    radius = radius,
                )
            }
            onDrawBehind {
                drawRect(base)
                drawRect(brush)
            }
        }
        "gradient" -> {
            // Background → part-way to Surface, never the whole step: the ladder's own
            // gap is the smallest difference the design admits, and a full-height sweep
            // across it reads as a colour cast rather than as depth.
            val brush = Brush.verticalGradient(
                listOf(base, lerp(base, LeanColors.Surface, GradientReach)),
            )
            onDrawBehind { drawRect(brush) }
        }
        "grain" -> {
            // The honest cure for AMOLED banding: a repeating tile of per-pixel alpha
            // noise dithers the 1-2 code-value steps a large flat fill shows as rings on
            // an OLED panel. Tinted per canvas, white noise lifts a dark backdrop, black
            // noise is what a near-white one needs.
            val brush = ShaderBrush(ImageShader(GrainTile, TileMode.Repeated, TileMode.Repeated))
            val tint = ColorFilter.tint(if (LeanColors.light) Color.Black else Color.White)
            onDrawBehind {
                drawRect(base)
                drawRect(brush = brush, alpha = GrainAlpha, colorFilter = tint)
            }
        }
        "image" -> {
            // Battery saver drops the blur but keeps the picture: blurring is the part
            // that costs (a three-pass filter, and a second cached bitmap to hold the
            // result), while showing the wallpaper the user chose costs nothing extra.
            val image = BackgroundImage.forBlur(if (LeanPower.frugal) 0 else LeanOptions.bgImageBlur)
            if (image == null) {
                // Style selected but nothing decoded (never picked, or the file went
                // missing): fall back to the flat canvas rather than drawing nothing,
                // which would leave the window background showing through.
                onDrawBehind { drawRect(base) }
            } else {
                val plan = backgroundImagePlan(image, size, base)
                onDrawBehind { drawBackgroundImage(plan) }
            }
        }
        else -> onDrawBehind { drawRect(base) }
    }
}

/** How far out the vignette's transparent centre reaches, as a fraction of the long edge. */
private const val VignetteReach = 0.78f

/** Below the perceptual threshold on a single glance, it frames, not fades. */
private const val VignetteAlpha = 0.06f

/** Kept under one surface-ladder step, so the sweep never reads as a second colour. */
private const val GradientReach = 0.6f

private const val GrainAlpha = 0.03f

private const val GrainTileSide = 64

/**
 * 64×64 of white-with-random-alpha, generated once per process.
 *
 * Fixed seed: the tile is repeated across the whole screen, so a run-to-run
 * difference would be a visible change in a "static" backdrop, and a stable one can be
 * eyeballed against a screenshot. Full 0..255 alpha range, because it needs per-pixel
 * variance large enough to break a banding step, then scaled down globally by
 * [GrainAlpha].
 */
private val GrainTile: ImageBitmap by lazy {
    val pixels = IntArray(GrainTileSide * GrainTileSide)
    val random = Random(0x1EA4)
    for (i in pixels.indices) {
        pixels[i] = (random.nextInt(256) shl 24) or 0x00FFFFFF
    }
    Bitmap.createBitmap(pixels, GrainTileSide, GrainTileSide, Bitmap.Config.ARGB_8888)
        .asImageBitmap()
}

/**
 * Everything needed to paint «своя картинка» onto a canvas of a given size, resolved
 * once so the draw itself allocates nothing.
 *
 * Shared by three callers, the screen background, the «Оформление» showcase, and the
 * glass panels, because all three must show the same picture under the same crop, zoom
 * and tint. Recomputing that per caller is how a preview ends up lying about what the
 * home screen will look like.
 */
data class BackgroundImagePlan(
    val image: ImageBitmap,
    val dstOffset: IntOffset,
    val dstSize: IntSize,
    val filter: ColorFilter?,
    val base: Color,
    val scrim: Color,
)

fun backgroundImagePlan(image: ImageBitmap, size: Size, base: Color): BackgroundImagePlan {
    // Cover-crop: scale by the larger ratio so the picture covers the canvas and the
    // overflow is trimmed, instead of being squashed to the canvas aspect ratio.
    // «Масштаб» magnifies on top of that, which is why its floor is 100, below it the
    // picture would stop covering and leave bare canvas at the edges.
    val zoom = LeanOptions.bgImageZoom / 100f
    val scale = max(size.width / image.width, size.height / image.height) * zoom
    val w = image.width * scale
    val h = image.height * scale
    val left = (size.width - w) / 2f
    // «Положение» decides which part of a picture taller than the canvas survives the
    // crop, the difference between keeping a face and keeping the sky above it.
    val top = when (LeanOptions.bgImageAlign) {
        "top" -> 0f
        "bottom" -> size.height - h
        else -> (size.height - h) / 2f
    }
    // «Насыщенность» rides on the draw rather than on the stored bitmap, so it costs
    // nothing to change and never degrades the saved copy.
    val saturation = LeanOptions.bgImageSaturation / 100f
    return BackgroundImagePlan(
        image = image,
        dstOffset = IntOffset(left.toInt(), top.toInt()),
        dstSize = IntSize(w.toInt(), h.toInt()),
        filter = if (saturation >= 1f) null else {
            ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(saturation) })
        },
        base = base,
        // The scrim is not decoration. Text, hairlines and the whole tonal surface ladder
        // are built against a known flat canvas; drawn straight onto a photo they stop
        // being legible over its bright regions. Laying the canvas colour back over the
        // image at the user's chosen weight keeps the ladder working.
        scrim = base.copy(alpha = LeanOptions.bgImageDim / 100f),
    )
}

/**
 * Paints the plan across this whole DrawScope, the screen canvas.
 */
fun DrawScope.drawBackgroundImage(plan: BackgroundImagePlan) {
    // Clipped to the composable, because the picture is bigger than it: a
    // cover-crop, and «Приближение» can make it larger still. Compose does not clip a draw
    // to the layout bounds by itself, so the overflow was really being painted, outside
    // the rect that [plan.scrim] darkens and outside anything that could hide it. Standing
    // still nobody sees it, because it lies past the edge of the screen. Slide the screen
    // during a transition and that overflow rides into view: a band of undimmed wallpaper,
    // offset from the part beside it, belonging to no screen at all.
    clipRect {
        drawRect(plan.base)
        drawImage(
            image = plan.image,
            dstOffset = plan.dstOffset,
            dstSize = plan.dstSize,
            colorFilter = plan.filter,
        )
        drawRect(plan.scrim)
    }
}

/**
 * Paints only the part of the window-sized plan that falls inside this panel, which sits
 * at [origin] in window coordinates.
 *
 * This exists because the obvious implementation is a performance trap. Drawing the whole
 * window's plan inside a `translate(-origin)` and letting the panel's clip discard the
 * rest is correct and simple, and it costs a full-screen fill, a full-screen scaled image
 * and a second full-screen fill for every panel, on every frame. A server list where each
 * row is glass turns one screen into twenty screens' worth of drawing per frame, which is
 * exactly what made scrolling stutter.
 *
 * So instead of moving the canvas, we work out which rectangle of the source image lands
 * under this panel and draw just that. The visual result is identical, the same global
 * crop, so fragments still line up across panels and still slide correctly as the list
 * scrolls, but the cost is proportional to the panel rather than to the screen.
 */
fun DrawScope.drawBackgroundSlice(plan: BackgroundImagePlan, origin: Offset, overlay: Color) {
    // Where the image lives, in window coordinates.
    val imageLeft = plan.dstOffset.x.toFloat()
    val imageTop = plan.dstOffset.y.toFloat()
    val imageRight = imageLeft + plan.dstSize.width
    val imageBottom = imageTop + plan.dstSize.height

    // The panel, in the same coordinates, intersected with the image.
    val visibleLeft = max(imageLeft, origin.x)
    val visibleTop = max(imageTop, origin.y)
    val visibleRight = min(imageRight, origin.x + size.width)
    val visibleBottom = min(imageBottom, origin.y + size.height)

    val covered = visibleLeft <= origin.x && visibleTop <= origin.y &&
        visibleRight >= origin.x + size.width && visibleBottom >= origin.y + size.height
    // Only needed where the picture does not reach. With the usual cover-crop it reaches
    // everywhere, so this is normally skipped entirely, one full-panel fill saved per
    // panel per frame.
    if (!covered) drawRect(plan.base)

    if (visibleRight > visibleLeft && visibleBottom > visibleTop && plan.dstSize.width > 0 &&
        plan.dstSize.height > 0
    ) {
        // Window offsets -> source pixels, through the same scale the plan established.
        val scaleX = plan.image.width.toFloat() / plan.dstSize.width
        val scaleY = plan.image.height.toFloat() / plan.dstSize.height
        // Outward rounding on both rects. Truncating instead would leave a sub-pixel
        // sliver along a panel edge showing the flat canvas where the picture should be,
        // faint, but repeated on every panel it reads as a hairline border.
        val srcLeft = floor((visibleLeft - imageLeft) * scaleX).toInt().coerceIn(0, plan.image.width)
        val srcTop = floor((visibleTop - imageTop) * scaleY).toInt().coerceIn(0, plan.image.height)
        val srcRight = ceil((visibleRight - imageLeft) * scaleX).toInt().coerceIn(srcLeft, plan.image.width)
        val srcBottom = ceil((visibleBottom - imageTop) * scaleY).toInt().coerceIn(srcTop, plan.image.height)
        val dstLeft = floor(visibleLeft - origin.x).toInt()
        val dstTop = floor(visibleTop - origin.y).toInt()
        val dstRight = ceil(visibleRight - origin.x).toInt()
        val dstBottom = ceil(visibleBottom - origin.y).toInt()
        if (srcRight > srcLeft && srcBottom > srcTop && dstRight > dstLeft && dstBottom > dstTop) {
            drawImage(
                image = plan.image,
                srcOffset = IntOffset(srcLeft, srcTop),
                srcSize = IntSize(srcRight - srcLeft, srcBottom - srcTop),
                // Back into panel-local coordinates.
                dstOffset = IntOffset(dstLeft, dstTop),
                dstSize = IntSize(dstRight - dstLeft, dstBottom - dstTop),
                colorFilter = plan.filter,
            )
        }
    }

    // one fill, not two. The scrim and the panel's tint are both flat translucent
    // layers covering the whole panel, so drawing them separately paid twice for a
    // result a single pre-composited colour reproduces exactly.
    drawRect(overlay)
}

/**
 * [top] drawn over [bottom], resolved to the one colour that produces the same pixels.
 *
 * Standard source-over: the output alpha is what the two layers accumulate, and the
 * output colour is their alpha-weighted mix. Exact rather than an approximation, which
 * matters because this replaces two real draws with one: any drift would show up as a
 * changed tint rather than as an obvious bug.
 */
fun compositeOver(top: Color, bottom: Color): Color {
    val at = top.alpha
    val ab = bottom.alpha
    val outA = at + ab * (1f - at)
    if (outA <= 0f) return Color.Transparent
    val w = ab * (1f - at)
    return Color(
        red = (top.red * at + bottom.red * w) / outA,
        green = (top.green * at + bottom.green * w) / outA,
        blue = (top.blue * at + bottom.blue * w) / outA,
        alpha = outA,
    )
}

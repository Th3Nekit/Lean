package com.th3web.lean.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.node.CompositionLocalConsumerModifierNode
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.LayoutAwareModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.currentValueOf
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.platform.InspectorInfo

/**
 * True when a glass panel paints nothing at all: «Стекло» is on and its density is zero,
 * so the background shows through unchanged.
 *
 * Shared because everything a panel wears must disappear with it, see
 * [Modifier.depthShadow], which would otherwise trace a halo around a surface that is not
 * drawn.
 */
internal fun glassPanelIsInvisible(): Boolean =
    LeanOptions.glassPanels && LeanOptions.glassTint <= 0

/**
 * A panel that shows the blurred wallpaper through itself instead of an opaque fill.
 *
 * Sampled from the app's own background rather than captured from the composition behind
 * it: the wallpaper is already decoded and already blurred for «Размытие», and a
 * capture-based approach (RenderEffect, haze) would also blur siblings drawn beneath the
 * panel and needs API 31 against minSdk 24.
 *
 * Falls back to a plain fill whenever there is nothing to see through: the setting is
 * off, battery saver is on, the background is not a picture, or the screen never painted
 * one ([LocalGlassBackdrop]).
 */
fun Modifier.leanGlass(shape: Shape, fill: Color): Modifier {
    // Asked per composition because it decides the shape of the chain, while [GlassNode]
    // asks the same question per draw to decide what to paint. Loose: it may
    // say yes where the draw says no, costing one unused layer.
    val samplesBackdrop = LeanOptions.glassPanels &&
        LeanOptions.bgStyle == "image" &&
        LeanOptions.glassTint > 0
    return this.clip(shape)
        .then(GlassElement(fill))
        // The panel's layer is re-recorded on every frame of a scroll, since the fragment
        // of wallpaper it shows genuinely changes. This second layer keeps the content out
        // of that: text, badges and meters keep their display list while the glass
        // invalidation re-records one image draw.
        .then(if (samplesBackdrop) Modifier.graphicsLayer() else Modifier)
}

private data class GlassElement(val fill: Color) : ModifierNodeElement<GlassNode>() {
    override fun create(): GlassNode = GlassNode(fill)

    override fun update(node: GlassNode) {
        node.fill = fill
    }

    override fun InspectorInfo.inspectableProperties() {
        name = "leanGlass"
        properties["fill"] = fill
    }
}

/**
 * A [Modifier.Node] rather than `composed {}` for two reasons: a composition scope per
 * panel is wasteful on a list where every row is glass, and the panel's position arrives
 * at layout, so reading it during composition is a frame late, the first frame of a row
 * scrolling in would fall back to the opaque fill.
 */
private class GlassNode(var fill: Color) :
    Modifier.Node(),
    DrawModifierNode,
    LayoutAwareModifierNode,
    CompositionLocalConsumerModifierNode {

    // Plain fields, not snapshot state: written during layout and read by the draw that
    // follows in the same frame, so invalidation is stated explicitly below.
    private var origin = Offset.Zero
    private var root = Size.Zero

    /**
     * Whether the last draw sampled the wallpaper: that is, whether this panel's look
     * depends on where it sits.
     *
     * Gates the invalidation in [onPlaced]. A panel is clipped, so it owns a layer, and
     * invalidating its draw re-records that layer's whole display list. With «Стекло» off
     * the draw is one flat fill that is identical wherever the panel sits.
     */
    private var positionMatters = false

    override fun onPlaced(coordinates: LayoutCoordinates) {
        val nextOrigin = coordinates.positionInRoot()
        val size = coordinates.findRootCoordinates().size
        val nextRoot = Size(size.width.toFloat(), size.height.toFloat())
        if (nextOrigin != origin || nextRoot != root) {
            origin = nextOrigin
            root = nextRoot
            // Scrolling slides the panel over the backdrop, so the fragment it shows
            // changes even though the panel itself did not.
            if (positionMatters) invalidateDraw()
        }
    }

    override fun ContentDrawScope.draw() {
        // Read inside draw: a draw-phase read of snapshot state invalidates the
        // draw when the look changes, with no recomposition involved.
        val glassOn = LeanOptions.glassPanels
        val seeThrough = glassOn &&
            currentValueOf(LocalGlassBackdrop) &&
            LeanOptions.bgStyle == "image" &&
            !LeanPower.frugal
        // Zero density means no panel at all, not a transparent tint over a blurred copy
        // of the wallpaper, which still reads as a block.
        if (glassPanelIsInvisible()) {
            positionMatters = false
            drawContent()
            return
        }
        val image = if (seeThrough) BackgroundImage.forBlur(glassBlur()) else null
        positionMatters = image != null
        val plan = if (image != null) GlassPlan.of(image, root, LeanColors.Background) else null
        // Density applies wherever glass is on, not only where a picture makes it literal;
        // Over a flat colour it means what the label says, how solid the panel is.
        val tinted = if (glassOn) fill.copy(alpha = LeanOptions.glassTint / 100f) else fill
        when {
            image == null -> drawRect(tinted)
            // Enabled but not positioned yet (the first layout). Tint only: the wallpaper
            // is already on screen behind this panel, so this is a near match for the glass
            // that lands next frame, where a solid fill would be a dark block.
            plan == null -> drawRect(tinted)
            else -> drawBackgroundSlice(
                plan,
                origin,
                // The tint is what keeps it a panel: without it the card dissolves into the
                // wallpaper and its text loses the surface it was contrasted against.
                // Pre-composited with the plan's scrim so two flat layers cost one fill.
                compositeOver(top = tinted, bottom = plan.scrim),
            )
        }
        drawContent()
    }
}

/**
 * Whether the screen under this composable actually painted the wallpaper.
 *
 * Only a screen that calls [leanBackground] can host glass; elsewhere a glass panel would
 * show a slice of a picture that is not behind it. Defaults to false so a new screen opts
 * in.
 */
val LocalGlassBackdrop = staticCompositionLocalOf { false }

/**
 * Marks this subtree as sitting on the wallpaper, enabling glass inside it. Pair it with
 * [leanBackground] on the same screen.
 */
@Composable
fun GlassBackdrop(content: @Composable () -> Unit) {
    val painted = LeanOptions.bgStyle == "image"
    CompositionLocalProvider(LocalGlassBackdrop provides painted, content = content)
}

/**
 * The one background plan every glass panel on screen shares.
 *
 * A plan describes how the wallpaper is laid over the window, crop, zoom, alignment,
 * saturation, scrim, so it is identical for every panel, and building it allocates.
 * Computed once and handed out, at the cost of one identity check per draw.
 *
 * Only touched from the draw phase on the main thread, so plain fields are enough.
 */
private object GlassPlan {
    private var image: ImageBitmap? = null
    private var window: Size = Size.Zero
    private var base: Color = Color.Unspecified
    private var look: Int = Int.MIN_VALUE
    private var plan: BackgroundImagePlan? = null

    fun of(
        image: ImageBitmap,
        window: Size,
        base: Color,
    ): BackgroundImagePlan? {
        if (window.width <= 0f || window.height <= 0f) return null
        // Everything backgroundImagePlan reads off LeanOptions, folded into one value so a
        // look change rebuilds the plan and nothing else does.
        val look = LeanOptions.bgImageZoom * 31 + LeanOptions.bgImageAlign.hashCode() * 7 +
            LeanOptions.bgImageSaturation * 3 + LeanOptions.bgImageDim
        val cached = plan
        if (cached != null && this.image === image && this.window == window &&
            this.base == base && this.look == look
        ) {
            return cached
        }
        val fresh = backgroundImagePlan(image, window, base)
        this.image = image
        this.window = window
        this.base = base
        this.look = look
        plan = fresh
        return fresh
    }
}

/**
 * Blur for the glass fragment, the frosting, scaled by «Плотность стекла».
 *
 * A frosted panel needs a good amount of it even when «Размытие» is off: glass reads as
 * glass because what is behind it is out of focus. What scales with density is the
 * frosting above what the background already carries, so that at 1 % the fragment is
 * indistinguishable from the picture around it rather than a sharply bounded blurred
 * patch on a sharp one.
 */
internal fun glassBlur(): Int {
    val background = LeanOptions.bgImageBlur
    val frosted = maxOf(background, GlassMinBlur)
    val density = LeanOptions.glassTint.coerceIn(0, 100)
    return background + (frosted - background) * density / 100
}

private const val GlassMinBlur = 45

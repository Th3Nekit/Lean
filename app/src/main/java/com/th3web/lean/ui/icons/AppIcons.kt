package com.th3web.lean.ui.icons

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.th3web.lean.ui.theme.LeanColors
import com.th3web.lean.ui.theme.LeanCorner

/**
 * Launcher icons for the app lists, loaded one row at a time.
 *
 * Loading them with the list itself is the obvious approach and the wrong one: a phone
 * here holds several hundred packages with the internet permission, every icon is a
 * resource decoded out of another app's APK, and holding all of them at full size is tens
 * of megabytes of bitmaps for a list that shows a dozen rows at once.
 *
 * So an icon is fetched when its row appears, off the main thread, and kept in a cache
 * bounded by bytes rather than by entry count: the same icon costs wildly different
 * amounts on a 1x tablet and a 3x phone, and only the byte figure is comparable.
 */
internal object AppIcons {

    /** Roughly forty icons at phone density; a long scroll evicts the far end, as intended. */
    private const val CACHE_BYTES = 2 * 1024 * 1024

    private val cache = object : LruCache<String, ImageBitmap>(CACHE_BYTES) {
        override fun sizeOf(key: String, value: ImageBitmap): Int =
            value.width * value.height * 4
    }

    fun cached(pkg: String, sizePx: Int): ImageBitmap? = cache.get(key(pkg, sizePx))

    suspend fun load(context: Context, pkg: String, sizePx: Int): ImageBitmap? {
        val key = key(pkg, sizePx)
        cache.get(key)?.let { return it }
        return withContext(Dispatchers.IO) {
            // An app can be uninstalled between the list being built and its row being
            // drawn; a missing icon is a blank square, never a crash.
            val drawable = runCatching { context.packageManager.getApplicationIcon(pkg) }
                .getOrNull() ?: return@withContext null
            val rendered = runCatching { drawable.render(sizePx) }.getOrNull()
            rendered?.also { cache.put(key, it) }
        }
    }

    private fun key(pkg: String, sizePx: Int) = "$pkg@$sizePx"

    /**
     * Drawn rather than unwrapped. Most icons on a modern phone are adaptive, a
     * background layer plus a foreground layer with no single bitmap to take, and asking
     * the drawable to draw itself is the one path that renders every kind correctly.
     */
    private fun Drawable.render(sizePx: Int): ImageBitmap {
        val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        setBounds(0, 0, sizePx, sizePx)
        draw(Canvas(bitmap))
        return bitmap.asImageBitmap()
    }
}

/**
 * One app's icon, sized in dp and clipped to the app's own corner language.
 *
 * The cache is read synchronously for the first frame: scrolling back over rows that were
 * already loaded must not blink through a placeholder, which is what a
 * load-on-launch composable does.
 */
@Composable
internal fun AppIcon(pkg: String, modifier: Modifier = Modifier, size: Dp = 34.dp) {
    val context = LocalContext.current
    val sizePx = with(LocalDensity.current) { size.roundToPx() }
    // remember(keys) seeds the first frame straight from the cache, so a row scrolled back
    // into view never blinks through a placeholder; the effect then fills in only what the
    // cache did not have.
    var icon by remember(pkg, sizePx) { mutableStateOf(AppIcons.cached(pkg, sizePx)) }
    LaunchedEffect(pkg, sizePx) {
        if (icon == null) icon = AppIcons.load(context.applicationContext, pkg, sizePx)
    }
    val shape = LeanCorner.Badge
    val box = modifier.size(size).clip(shape)
    val bitmap = icon
    if (bitmap == null) {
        // A placeholder of the same size, so a row never changes height or shifts its
        // label sideways when the icon arrives.
        Box(box.background(LeanColors.SurfaceVariant))
    } else {
        Image(
            bitmap = bitmap,
            contentDescription = null,
            modifier = box,
            contentScale = ContentScale.Fit,
        )
    }
}

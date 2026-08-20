package com.th3web.lean.ui.theme

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * «Своя картинка», the user-supplied app background.
 *
 * The picked image is copied into app-private storage rather than referenced by its
 * content:// URI. A picker grant does not survive a reboot unless it is explicitly
 * persisted, the source file can be deleted or moved out from under us, and on a
 * scoped-storage device a URI that worked at pick time can be unreadable on the next
 * launch: all of which would show up as a background that silently vanishes. A copy is
 * ours, so the only way it disappears is the user clearing it.
 *
 * The decoded bitmap is held in one snapshot-state slot for the whole process: the
 * background modifier redraws constantly, and decoding a multi-megapixel photo per draw
 * (or even per screen) would be ruinous. [load] is the only thing that touches disk.
 */
object BackgroundImage {

    private const val FILE_NAME = "background.img"

    /**
     * Longest edge the stored copy is downsampled to. A background is drawn at screen
     * size and then scrimmed, so a 12-megapixel original buys nothing but memory, and a
     * full-resolution decode is what makes a photo background feel like a leak.
     */
    private const val MAX_EDGE_PX = 2048

    /** The decoded background, or null when none is set / it could not be read. */
    var bitmap by mutableStateOf<ImageBitmap?>(null)
        private set

    fun file(context: Context): File = File(context.filesDir, FILE_NAME)

    /** True when a stored background exists on disk (independent of whether it is loaded). */
    fun exists(context: Context): Boolean = file(context).isFile

    /**
     * Copies [source] into app-private storage, downsampling it, and publishes it.
     * Returns false when the picked image could not be read or decoded, leaving any
     * previous background untouched: a failed pick must not clear a working one.
     */
    fun import(context: Context, source: Uri): Boolean {
        val decoded = runCatching {
            // Two passes: bounds first, so inSampleSize is chosen before any pixels are
            // allocated. Decoding full-size and scaling afterwards is what OOMs on a
            // cheap device with a 108MP camera photo.
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(source)?.use {
                BitmapFactory.decodeStream(it, null, bounds)
            }
            val longest = maxOf(bounds.outWidth, bounds.outHeight)
            if (longest <= 0) return@runCatching null
            var sample = 1
            while (longest / sample > MAX_EDGE_PX) sample *= 2
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            context.contentResolver.openInputStream(source)?.use {
                BitmapFactory.decodeStream(it, null, opts)
            }
        }.getOrNull() ?: return false

        val written = runCatching {
            // Written to a temp file and renamed, so an interrupted copy cannot leave a
            // truncated image as the background.
            val tmp = File(context.filesDir, "$FILE_NAME.tmp")
            tmp.outputStream().use { out ->
                decoded.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, out)
            }
            tmp.renameTo(file(context))
        }.getOrDefault(false)
        if (!written) return false

        bitmap = decoded.asImageBitmap()
        cache.clear()
        return true
    }

    /**
     * Decodes the stored background if it is not in memory yet, OFF the main thread.
     *
     * This is what makes the picture appear on a cold start, and it is the
     * only way to get it off disk. The previous loader was called from the «Оформление»
     * screen alone, so the background was only ever decoded as a side effect of visiting
     * the very screen that configures it: launch the app fresh and the canvas fell back
     * to the flat fill until the user happened to open that screen, at which point it
     * appeared, looking as though the setting had been forgotten. One entry point,
     * called from the theme, cannot be forgotten by a new call site.
     *
     * Decoding is a multi-megapixel file read, so it belongs on IO rather than in the
     * first frame's path; [bitmap] is snapshot state, so publishing it from another
     * thread simply redraws whatever is already on screen once the picture is ready.
     */
    suspend fun ensureLoaded(context: Context) {
        if (bitmap != null) return
        val f = file(context)
        if (!f.isFile) return
        val decoded = withContext(Dispatchers.IO) {
            runCatching { BitmapFactory.decodeFile(f.path)?.asImageBitmap() }.getOrNull()
        } ?: return
        // Re-check: a pick (import) could have landed while we were decoding, and it
        // owns a newer picture than the file we started from.
        if (bitmap == null) {
            bitmap = decoded
            cache.clear()
        }
    }

    // ---- soft focus ----
    //
    // Downscale-then-upscale alone is not a blur: a bilinear upscale of a heavily shrunk
    // image reproduces the pixel grid as visible mush, and testers called the result
    // exactly what it was, bad. What actually looks like defocus is a Gaussian, and
    // three passes of a cheap box blur converge on one closely enough that the difference
    // is invisible (the standard box-approximates-Gaussian result).
    //
    // Doing that on the full picture would cost tens of milliseconds per frame, so the
    // work happens once, on a small copy: shrink to WORK_EDGE_PX, blur there, cache, and
    // let the draw scale it back up. Blurring after shrinking is also what makes the
    // radius cheap, a few pixels at working size is a wide blur at screen size.
    // RenderEffect would be the tidy answer but it needs API 31 against a minSdk of 24.
    // two slots, not one. The background draws at «Размытие» while a glass panel draws
    // at its own (stronger) radius, so a single slot would be evicted and recomputed by
    // whichever drew last, every frame, for both. Two is exactly the number of distinct
    // radii that can be on screen at once.
    private val cache = HashMap<Int, ImageBitmap>(2)

    /** Longest edge the blur is computed at, big enough to keep composition, small
     * enough that three passes are microseconds rather than milliseconds. */
    private const val WORK_EDGE_PX = 360

    /** The picture as it should be drawn for [blur] percent of soft focus. */
    fun forBlur(blur: Int): ImageBitmap? {
        val source = bitmap ?: return null
        if (blur <= 0) return source
        cache[blur]?.let { return it }
        val result = runCatching { blurred(source.asAndroidBitmap(), blur) }.getOrNull()
            ?: return source
        // Never grow past the two radii that can legitimately coexist; a stale third only
        // appears while the user drags the slider, and dropping the oldest is enough.
        if (cache.size >= 2) cache.clear()
        cache[blur] = result
        return result
    }

    private fun blurred(source: android.graphics.Bitmap, blur: Int): ImageBitmap {
        val longest = maxOf(source.width, source.height).coerceAtLeast(1)
        val k = (WORK_EDGE_PX.toFloat() / longest).coerceAtMost(1f)
        val w = (source.width * k).toInt().coerceAtLeast(8)
        val h = (source.height * k).toInt().coerceAtLeast(8)
        val small = android.graphics.Bitmap.createScaledBitmap(source, w, h, true)

        // Radius in working pixels. Kept modest because the result is stretched back up:
        // 18px at 360px wide is an unmistakable frosted-glass blur on a phone screen.
        val radius = (blur / 100f * 18f).toInt().coerceIn(1, 32)
        val pixels = IntArray(w * h)
        small.getPixels(pixels, 0, w, 0, 0, w, h)
        val scratch = IntArray(w * h)
        // Horizontal then vertical, three times: a separable box blur, and three passes
        // is where it stops being distinguishable from a Gaussian.
        repeat(3) {
            boxBlur(pixels, scratch, w, h, radius, vertical = false)
            boxBlur(scratch, pixels, w, h, radius, vertical = true)
        }
        val out = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
        out.setPixels(pixels, 0, w, 0, 0, w, h)
        return out.asImageBitmap()
    }

    /**
     * One box-blur pass. A moving sum keeps it O(pixels) rather than O(pixels × radius),
     * which is what makes three passes affordable at all.
     */
    private fun boxBlur(
        src: IntArray,
        dst: IntArray,
        w: Int,
        h: Int,
        radius: Int,
        vertical: Boolean,
    ) {
        val outer = if (vertical) w else h
        val inner = if (vertical) h else w
        val window = radius * 2 + 1
        for (o in 0 until outer) {
            var a = 0; var r = 0; var g = 0; var b = 0
            fun at(i: Int): Int {
                val idx = i.coerceIn(0, inner - 1)
                return if (vertical) src[idx * w + o] else src[o * w + idx]
            }
            // Prime the window with the clamped edge, so the border does not darken.
            for (i in -radius..radius) {
                val c = at(i)
                a += (c ushr 24) and 0xFF; r += (c ushr 16) and 0xFF
                g += (c ushr 8) and 0xFF; b += c and 0xFF
            }
            for (i in 0 until inner) {
                val c = ((a / window) shl 24) or ((r / window) shl 16) or
                    ((g / window) shl 8) or (b / window)
                if (vertical) dst[i * w + o] = c else dst[o * w + i] = c
                val out = at(i - radius)
                val inc = at(i + radius + 1)
                a += ((inc ushr 24) and 0xFF) - ((out ushr 24) and 0xFF)
                r += ((inc ushr 16) and 0xFF) - ((out ushr 16) and 0xFF)
                g += ((inc ushr 8) and 0xFF) - ((out ushr 8) and 0xFF)
                b += (inc and 0xFF) - (out and 0xFF)
            }
        }
    }

    /** Forgets and deletes the stored background. */
    fun clear(context: Context) {
        bitmap = null
        cache.clear()
        runCatching { file(context).delete() }
    }
}

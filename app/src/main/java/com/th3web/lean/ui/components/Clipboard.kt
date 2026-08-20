package com.th3web.lean.ui.components

import android.content.ClipData
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.Clipboard
import androidx.compose.ui.platform.LocalClipboard
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * The clipboard, as this app actually uses it: put one string in, or take one out.
 *
 * Every screen that copies a share link, a subscription URL, a HWID or a User-Agent used
 * to reach for `LocalClipboardManager` itself and wrap the string in an `AnnotatedString`
 * on the spot, eight copies of the same two lines, and eight deprecation warnings once
 * Compose replaced that API with a suspending one. The suspend calls live here instead,
 * so callers stay ordinary click handlers.
 */
internal class LeanClipboard(
    private val clipboard: Clipboard,
    private val scope: CoroutineScope,
) {
    /** Puts [text] on the clipboard. */
    fun copy(text: String) {
        scope.launch {
            clipboard.setClipEntry(ClipEntry(ClipData.newPlainText(LABEL, text)))
        }
    }

    /**
     * Hands the pasted text to [onText], never blank, and not at all when the clipboard
     * holds something with no text in it (an image, a bare URI).
     */
    fun paste(onText: (String) -> Unit) {
        scope.launch {
            val data = clipboard.getClipEntry()?.clipData ?: return@launch
            val text = (0 until data.itemCount)
                .asSequence()
                .mapNotNull { data.getItemAt(it)?.text?.toString() }
                .firstOrNull { it.isNotBlank() }
                ?: return@launch
            onText(text)
        }
    }

    private companion object {
        /** What the system clipboard UI shows this entry as. */
        const val LABEL = "Lean"
    }
}

@Composable
internal fun rememberLeanClipboard(): LeanClipboard {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    return remember(clipboard, scope) { LeanClipboard(clipboard, scope) }
}

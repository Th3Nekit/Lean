package com.th3web.lean.ui

import java.util.Locale

/**
 * Human-readable byte size, base-1024: "12.4 GB", "830 MB", "512 KB", "64 B".
 * One decimal at GB and above, integer below. Unit suffixes are intentionally
 * untranslated (they are universal). Negative input is clamped to 0.
 */
fun formatBytes(bytes: Long): String {
    val b = bytes.coerceAtLeast(0L)
    val kb = 1024.0
    val mb = kb * 1024
    val gb = mb * 1024
    val tb = gb * 1024
    return when {
        b >= tb -> String.format(Locale.US, "%.1f TB", b / tb)
        b >= gb -> String.format(Locale.US, "%.1f GB", b / gb)
        b >= mb -> "${(b / mb).toInt()} MB"
        b >= kb -> "${(b / kb).toInt()} KB"
        else -> "$b B"
    }
}

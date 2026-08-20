package com.th3web.lean.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast

/** Open an external URL in the browser, tolerating a missing scheme or no handler. */
fun Context.openUrl(url: String) {
    if (url.isBlank()) return
    val normalized = if (url.startsWith("http://") || url.startsWith("https://")) url else "https://$url"
    runCatching {
        startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(normalized)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }.onFailure {
        // No handler (e.g. ActivityNotFoundException, no browser installed),
        // surface it instead of silently doing nothing.
        Toast.makeText(this, tr("Не удалось открыть ссылку"), Toast.LENGTH_SHORT).show()
    }
}

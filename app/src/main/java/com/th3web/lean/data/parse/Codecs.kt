package com.th3web.lean.data.parse

import android.util.Base64

/**
 * Tolerant base64 decode used for share links and subscription bodies.
 *
 * The alphabet is chosen from the content, and the result is checked against the length
 * the input demands.
 *
 * Trying the URL-safe alphabet first is what makes both necessary. A standard-alphabet
 * body contains `/`, which URL-safe has no place for, and Android's decoder does not
 * reject it, it skips the character and carries on. Every subsequent 6-bit group shifts,
 * the decode "succeeds", and the standard-alphabet fallback never runs. The body
 * re-aligns by luck every so often, so the user gets a fraction of their servers, some of
 * them mangled, with fragments of one entry's name inside another's.
 *
 * Hence the length check: a decode that quietly dropped input produces fewer bytes than
 * the padded length allows, and is rejected in favour of the other alphabet. Silent data
 * loss is the one failure mode that must not stay silent here.
 */
fun decodeBase64Tolerant(input: String): String? =
    decodeBase64TolerantBytes(input)?.toString(Charsets.UTF_8)

/** True if [s] looks like a base64 blob (no scheme, mostly base64 alphabet). */
fun looksLikeBase64(s: String): Boolean {
    val t = s.trim()
    if (t.isEmpty() || t.contains("://")) return false
    return t.all { it.isLetterOrDigit() || it in "+/-_=\n\r " }
}

/**
 * Byte-level base64 decode for binary payloads (gzip output is not valid UTF-8 text, so
 * decoding it as a String would lose it). null on failure.
 */
fun decodeBase64TolerantBytes(input: String): ByteArray? {
    // Strip whitespace ourselves rather than leaving it to the decoder: the length check
    // below has to count only real symbols.
    val cleaned = input.filterNot(Char::isWhitespace)
    if (cleaned.isEmpty()) return null
    val padded = when (cleaned.length % 4) {
        1 -> return null // no amount of padding makes this a valid length
        2 -> "$cleaned=="
        3 -> "$cleaned="
        else -> cleaned
    }
    // Pick by what is actually in the text. `-`/`_` mean URL-safe; `+`/`/` mean standard.
    // A body carrying both is malformed either way, and standard is the likelier intent.
    val urlSafe = cleaned.any { it == '-' || it == '_' } && cleaned.none { it == '+' || it == '/' }
    val primary = if (urlSafe) Base64.URL_SAFE else 0
    val secondary = if (urlSafe) 0 else Base64.URL_SAFE
    return decodeWhole(padded, primary) ?: decodeWhole(padded, secondary)
}

/**
 * Decodes with one alphabet and refuses a short result.
 *
 * Android's decoder silently skips symbols outside the selected alphabet, so "it did not
 * throw" is not proof it read everything. Comparing against the length the padded input
 * implies is what turns that silence into a failure the caller can act on.
 */
private fun decodeWhole(padded: String, alphabet: Int): ByteArray? {
    val bytes = runCatching {
        Base64.decode(padded, Base64.NO_WRAP or alphabet)
    }.getOrNull() ?: return null
    return bytes.takeIf { it.size >= expectedDecodedSize(padded) }
}

/** Bytes a padded, whitespace-free base64 string must produce. */
private fun expectedDecodedSize(padded: String): Int {
    val pad = padded.takeLast(2).count { it == '=' }
    return padded.length / 4 * 3 - pad
}

/** Gunzip to UTF-8 text; null on any failure. */
fun gunzip(bytes: ByteArray): String? = runCatching {
    java.util.zip.GZIPInputStream(bytes.inputStream())
        .bufferedReader(Charsets.UTF_8)
        .use { it.readText() }
}.getOrNull()

package com.th3web.lean.data.parse

import com.th3web.lean.data.model.Profile

/**
 * Parses a subscription body into profiles. Subscription bodies are commonly a
 * base64 blob of newline-separated share links, but may also be plain text,
 * optionally wrapped in Incy-style `base64:` / `gzip:` body prefixes, and may
 * carry `#…:` directive comment lines (profile-title, update-interval, etc.).
 */
object Subscriptions {

    /** Full parse result: profiles plus any `#…:` directives found in the body. */
    data class ParsedSubscription(
        val profiles: List<Profile>,
        val directives: BodyDirectives,
    )

    /** Back-compat wrapper; existing call-sites (importFromText) keep working. */
    fun parseBody(body: String): List<Profile> = parseBodyFull(body).profiles

    fun parseBodyFull(body: String): ParsedSubscription {
        val text = decodeBody(body)
        if (text.isEmpty()) return ParsedSubscription(emptyList(), BodyDirectives())
        // Xray/V2Ray JSON subscription (what gating panels serve to v2rayNG-family
        // UAs, the only variant carrying their Hysteria2 servers). JSON bodies
        // have no '#…:' directive lines, so directives stay empty on this path.
        // JSON bodies have no '#:' directive lines, so directives stay empty on the
        // JSON path. BOM survives trim() (U+FEFF is not isWhitespace) -> removePrefix.
        // Two-candidate strategy: base64-decoded first, then raw.
        val candidates = buildList {
            if (looksLikeBase64(text)) decodeBase64Tolerant(text)?.let { add(it) }
            add(text)
        }
        // Sniff each candidate (raw and base64-decoded), not just `text`: some panels
        // base64-wrap the JSON body without a `base64:` prefix, so the JSON only
        // surfaces after the base64 decode above; checking only the raw text missed it
        // and fell through to the (then empty) share-link tokenizer.
        for (sniffSrc in candidates) {
            val sniff = sniffSrc.removePrefix("\uFEFF")
            if (sniff.startsWith("[") || sniff.startsWith("{")) {
                val jsonProfiles = XrayConfig.parse(sniff)
                if (jsonProfiles.isNotEmpty()) {
                    return ParsedSubscription(jsonProfiles, BodyDirectives())
                }
            }
        }
        for (candidate in candidates) {
            // A subscription body may also carry one or more WireGuard / AmneziaWG
            // `.conf` blocks (the «обход» plan ships servers + a WG conf in one key).
            // Split those out first so their multi-line, blank-line-significant
            // structure survives the per-line share-link tokenizer below; the
            // leftover text (share-links, base64 blobs, `#…:` directives) parses
            // exactly as before.
            val (wgProfiles, shareText) = extractWgProfiles(candidate)
            val lines = shareText.split('\n', '\r').map { it.trim() }.filter { it.isNotEmpty() }
            val directives = extractDirectives(lines)
            val shareProfiles = lines.filterNot { it.startsWith("#") }
                .mapNotNull { ShareLinks.parse(it) }
            val profiles = shareProfiles + wgProfiles
            if (profiles.isNotEmpty() || directives != BodyDirectives()) {
                return ParsedSubscription(profiles, directives)
            }
        }
        return ParsedSubscription(emptyList(), BodyDirectives())
    }

    /**
     * Pulls every WireGuard / AmneziaWG `.conf` block out of a candidate body and
     * parses each into a [Profile] (via [WgConfig]). Returns the parsed WG profiles
     * plus the body with those blocks removed, so the caller's share-link/base64/
     * directive parsing runs over only the non-WG remainder.
     *
     * A WG block begins at a `[Interface]` header line and runs until the next
     * `[Interface]` header (a second config) or end of text. This handles a body
     * that is only a WG conf, a mixed body (share-links then a trailing conf), and
     * several confs concatenated. When no `[Interface]` is present the body is
     * returned untouched and the cheap [WgConfig.looksLikeWgConf] check short-circuits.
     */
    private fun extractWgProfiles(candidate: String): Pair<List<Profile>, String> {
        if (!WgConfig.looksLikeWgConf(candidate)) return emptyList<Profile>() to candidate

        val lines = candidate.split('\n')
        val nonWgLines = mutableListOf<String>()
        val wgProfiles = mutableListOf<Profile>()
        val blockLines = mutableListOf<String>()

        fun isInterfaceHeader(line: String): Boolean =
            line.trim().equals("[Interface]", ignoreCase = true)

        // A `#…:`-shaped directive line (e.g. `#profile-title:`, `#support-url:`)
        // is not WG conf grammar: the token after `#` (up to the first whitespace)
        // carries a ':'. A plain `# My Server` name comment has no such ':' before
        // a space, so it is not mistaken for a directive.
        fun isDirective(line: String): Boolean {
            val t = line.trim()
            if (!t.startsWith("#")) return false
            return ':' in t.drop(1).substringBefore(' ').substringBefore('\t')
        }

        fun flushBlock() {
            if (blockLines.isEmpty()) return
            val block = blockLines.joinToString("\n")
            // fallbackName mirrors importFromText's naming; WgConfig prefers a
            // leading `# Name` comment in the block when present.
            WgConfig.parseProfile(block, fallbackName = "")?.let { wgProfiles += it }
            blockLines.clear()
        }

        // A share-link line (`vless://`, `ss://`, …) is not WG conf grammar, a scheme
        // separator never appears in a `key = value` conf line, so it must end the block
        // instead of being swallowed into it (and dropped, since a scheme line has no `=`).
        fun isShareLink(line: String): Boolean = "://" in line.trim()

        var inBlock = false
        for (line in lines) {
            if (isInterfaceHeader(line)) {
                // A new `[Interface]` closes any open block and starts the next.
                flushBlock()
                // Pull an immediately-preceding `# Name` comment into the new block
                // so WgConfig.leadingNameComment can pick it up as the profile name.
                val prev = nonWgLines.lastOrNull()?.trim()
                if (prev != null && (prev.startsWith("#") || prev.startsWith(";")) && !isDirective(prev)) {
                    blockLines += nonWgLines.removeAt(nonWgLines.lastIndex)
                }
                inBlock = true
            } else if (inBlock && (isDirective(line) || isShareLink(line))) {
                // A `#…:` directive or a share-link after a conf terminates the block so
                // it is routed to the directive/share-link scan instead of being swallowed.
                flushBlock()
                inBlock = false
            }
            if (inBlock) blockLines += line else nonWgLines += line
        }
        flushBlock()

        return wgProfiles to nonWgLines.joinToString("\n")
    }

    /** Incy body-prefix decode (case-insensitive). Falls back to the raw text on failure. */
    private fun decodeBody(raw: String): String {
        val t = raw.trim()
        val lower = t.lowercase()
        return when {
            lower.startsWith("base64:gzip:") || lower.startsWith("gzip:base64:") ->
                decodeBase64TolerantBytes(t.substring(12))?.let { gunzip(it) } ?: t
            lower.startsWith("base64:") -> decodeBase64Tolerant(t.substring(7)) ?: t
            // Raw gzip bytes survive Http's ISO-8859-1 fallback read losslessly.
            lower.startsWith("gzip:") -> gunzip(t.substring(5).toByteArray(Charsets.ISO_8859_1)) ?: t
            else -> t
        }.trim()
    }

    /** Substring offsets are Incy's exact ones; they equal prefix.length here. */
    private fun extractDirectives(lines: List<String>): BodyDirectives {
        var d = BodyDirectives()
        for (l in lines) when {
            // Incy rule: split on '|' → title+subtitle; multi-line (possible after
            // base64 decode) → first non-empty line = title, remaining = subtitle.
            l.startsWith("#profile-title:") -> {                      // offset 15
                val decoded = maybeBase64(l.substring(15))
                val titleLines = decoded.split('\n').map { it.trim() }.filter { it.isNotEmpty() }
                val first = titleLines.firstOrNull() ?: ""
                d = d.copy(
                    title = first.substringBefore('|').trim().ifEmpty { null },
                    description = first.substringAfter('|', "").trim().ifEmpty { null }
                        ?: titleLines.drop(1).joinToString("\n").ifEmpty { null },
                )
            }
            l.startsWith("#profile-update-interval:") -> d = d.copy(  // offset 25, HOURS
                updateIntervalMs = l.substring(25).trim().toLongOrNull()
                    ?.takeIf { it > 0 }?.times(3_600_000L))
            l.startsWith("#profile-web-page-url:") -> d = d.copy(     // offset 22
                webPageUrl = l.substring(22).trim().ifEmpty { null })
            l.startsWith("#support-url:") -> d = d.copy(              // offset 13
                supportUrl = l.substring(13).trim().ifEmpty { null })
            l.startsWith("#announce:") -> d = d.copy(                 // offset 10
                announce = maybeBase64(l.substring(10)).trim().ifEmpty { null })
            // Other '#' lines (announce-url, routing/autorouting deep links): skipped, deferred.
        }
        return d
    }
}

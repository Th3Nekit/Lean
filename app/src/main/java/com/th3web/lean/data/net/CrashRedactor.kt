package com.th3web.lean.data.net

internal object CrashRedactor {
    const val MAX_MESSAGE_CHARS = 1_000
    const val MAX_STACK_TRACE_CHARS = 12_000
    const val MAX_LOG_LINES = 50
    const val MAX_LOG_LINE_CHARS = 512

    /**
     * The whole log budget, split evenly across the lines kept. It has to stay under what
     * the receiving end will take, a 32 KB body, of which a stack trace may claim 12 KB
     * and the message 1 KB, while still leaving a line long enough to hold a core log
     * line, which around 160 characters is not.
     */
    const val MAX_LOG_TOTAL_CHARS = 14_000

    /**
     * Kept from the end of a log line that has to be cut.
     *
     * A core line reads `connection: open connection to <host>:443 using outbound/…: <why
     * it failed>`, and every part that identifies the fault is in that last clause,
     * "operation was canceled", "no recent network activity", "connection refused". Head
     * truncation threw away the answer and kept the boilerplate: a whole set of field
     * reports arrived saying only that some connection to some host had, at some length,
     * done something beginning with "ope".
     */
    private const val LOG_TAIL_CHARS = 80
    /** App lines kept before a native fault, for context on what was running. */
    const val FAULT_CONTEXT_LINES = 8
    const val REDACTED = "[REDACTED]"
    const val TRUNCATED = "…[TRUNCATED]"

    private const val MAX_SCAN_CHARS = 64 * 1024

    private val unsafeControls = Regex(
        "[\\u0000-\\u0008\\u000B\\u000C\\u000E-\\u001F\\u007F-\\u009F" +
            "\\u200B\\u200C\\u200D\\u2060\\u202A-\\u202E\\u2066-\\u2069\\uFEFF]",
    )
    private val pemPrivateKey = Regex(
        "-----BEGIN(?: [A-Z0-9]+)* PRIVATE KEY-----[\\s\\S]*?" +
            "(?:-----END(?: [A-Z0-9]+)* PRIVATE KEY-----|\\z)",
        RegexOption.IGNORE_CASE,
    )
    private val shareLine = Regex(
        "\\b(?:vless|vmess|trojan|ss|hysteria2?|hy2|tuic|wireguard|wg|awg)://" +
            "[^\\s\\r\\n]+",
        RegexOption.IGNORE_CASE,
    )
    private val uriUserInfo = Regex(
        "\\b(https?://)[^\\s/@]+@",
        RegexOption.IGNORE_CASE,
    )
    private val authorizationHeader = Regex(
        "\\b(authorization\\s*:\\s*)(?:bearer\\s+)?[^\\s,;]+",
        RegexOption.IGNORE_CASE,
    )
    private val bearerToken = Regex(
        "\\bbearer\\s+[A-Za-z0-9._~+/=-]+",
        RegexOption.IGNORE_CASE,
    )
    private val sensitiveAssignment = Regex(
        "\\b([\"']?)(" +
            "private[ _-]?key|" +
            "preshared[ _-]?key|" +
            "pass(?:word|wd)?|pwd|" +
            "auth(?:[ _-]?(?:str|entication|token))?|" +
            "authorization|cookie|" +
            "access[ _-]?token|refresh[ _-]?token|" +
            "api[ _-]?key|client[ _-]?secret|" +
            "subscription(?:[ _-]?(?:url|link))?|sub(?:[ _-]?(?:url|link))?|" +
            "token|secret" +
            ")\\b([\"']?)(\\s*[:=]\\s*)" +
            "(?:\"[^\"]*\"|'[^']*'|[^\\s,;}]+)",
        RegexOption.IGNORE_CASE,
    )
    private val uuid = Regex(
        "\\b[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\b",
        RegexOption.IGNORE_CASE,
    )

    fun message(value: String?): String = redact(value.orEmpty(), MAX_MESSAGE_CHARS)

    fun stackTrace(value: String?): String = redact(value.orEmpty(), MAX_STACK_TRACE_CHARS)

    fun logLine(value: String?): String {
        val oneLine = value.orEmpty().replace('\r', ' ').replace('\n', ' ')
        return redact(oneLine, MAX_LOG_LINE_CHARS, keepTail = true)
    }

    /**
     * A native Go crash dump starts with the one line that names the fault
     * ("fatal error: …", "panic: …", "signal SIGSEGV …") followed by the goroutine that
     * died; everything after that is hundreds of lines of idle goroutines parked in
     * `gopark`. Plain `takeLast` therefore keeps the useless end of the only
     * crash class Kotlin cannot catch: fifty lines of signal-handler bookkeeping with the
     * cause cut off the top.
     */
    private val goFatalMarker = Regex(
        """^\s*(fatal error:|panic:|\[signal |runtime: |unexpected signal)""",
        RegexOption.IGNORE_CASE,
    )

    /**
     * `ERROR[0044] [174647803 32.52s] `, level, seconds since start, connection id and
     * age. Everything before the message, and everything that makes two reports of the
     * same event look like two different lines.
     */
    private val coreLinePrefix = Regex("""^[A-Za-z]+\[\d+]\s+\[\d+\s+[\d.]+m?s]\s*""")

    /**
     * Collapses a run of lines that say the same thing into one, tagged with how many
     * times it was said.
     *
     * A core stuck in a retry loop writes the identical error every few milliseconds, a
     * real report came in where nine of the fifty lines kept were one malformed DNS packet
     * being re-parsed, evicting the connection errors that explained the outage. The count
     * is the diagnosis there ("×9, 50 ms apart" is the bug); the nine copies are not.
     */
    private fun collapseRepeats(values: List<String>): List<String> {
        val out = ArrayList<String>(values.size)
        var index = 0
        while (index < values.size) {
            val key = coreLinePrefix.replace(values[index], "")
            var end = index + 1
            while (end < values.size && coreLinePrefix.replace(values[end], "") == key) end++
            val repeats = end - index
            out += if (repeats > 1) "${values[index]}  ×$repeats" else values[index]
            index = end
        }
        return out
    }

    fun logLines(rawValues: List<String>): List<String> {
        if (rawValues.isEmpty()) return emptyList()
        val values = collapseRepeats(rawValues)
        val fault = values.indexOfFirst { goFatalMarker.containsMatchIn(it) }
        val selected = if (fault >= 0) {
            // Keep a little of what the app was doing before the fault (which connection,
            // which engine), and then the fault itself with as much of the crashing
            // goroutine as the budget allows.
            values.drop((fault - FAULT_CONTEXT_LINES).coerceAtLeast(0)).take(MAX_LOG_LINES)
        } else {
            values.takeLast(MAX_LOG_LINES)
        }
        val perLineLimit = minOf(
            MAX_LOG_LINE_CHARS,
            MAX_LOG_TOTAL_CHARS / selected.size,
        )
        val result = ArrayList<String>(selected.size)
        var retainedChars = 0
        for (raw in selected) {
            val remaining = MAX_LOG_TOTAL_CHARS - retainedChars
            val limit = minOf(perLineLimit, remaining)
            val line = bound(logLine(raw), limit, raw.length > limit, keepTail = true)
            result += line
            retainedChars += line.length
        }
        return result
    }

    private fun redact(value: String, maxChars: Int, keepTail: Boolean = false): String {
        if (maxChars <= 0 || value.isEmpty()) return ""
        val scanLimit = minOf(
            MAX_SCAN_CHARS,
            maxOf(8_192, maxChars * 4 + TRUNCATED.length),
        )
        val inputWasTruncated = value.length > scanLimit
        var safe = value.take(scanLimit)
        safe = unsafeControls.replace(safe, "")
        safe = pemPrivateKey.replace(safe, REDACTED)
        safe = shareLine.replace(safe, REDACTED)
        safe = uriUserInfo.replace(safe) { match -> "${match.groupValues[1]}$REDACTED@" }
        safe = authorizationHeader.replace(safe) { match ->
            "${match.groupValues[1]}$REDACTED"
        }
        safe = bearerToken.replace(safe, REDACTED)
        safe = sensitiveAssignment.replace(safe) { match ->
            "${match.groupValues[1]}${match.groupValues[2]}" +
                "${match.groupValues[3]}${match.groupValues[4]}$REDACTED"
        }
        safe = uuid.replace(safe, REDACTED)
        return bound(safe, maxChars, inputWasTruncated || safe.length > maxChars, keepTail)
    }

    /**
     * [keepTail] cuts the middle instead of the end, keeping [LOG_TAIL_CHARS] of the
     * closing text. Only log lines ask for it: a message or a stack trace is read from the
     * top, while a core line's whole meaning is its final clause.
     *
     * Safe with respect to redaction, because what is cut here has already
     * been scanned and rewritten, the tail that survives is redacted text, never raw
     * input that the scan never reached.
     */
    private fun bound(
        value: String,
        maxChars: Int,
        truncated: Boolean,
        keepTail: Boolean = false,
    ): String {
        if (maxChars <= 0) return ""
        if (!truncated && value.length <= maxChars) return value
        if (maxChars <= TRUNCATED.length) return TRUNCATED.take(maxChars)
        val budget = maxChars - TRUNCATED.length
        if (!keepTail || value.length <= budget) {
            return value.take(minOf(value.length, budget)).trimEnd() + TRUNCATED
        }
        val tailLength = minOf(budget / 2, LOG_TAIL_CHARS)
        val headLength = budget - tailLength
        return value.take(headLength).trimEnd() + TRUNCATED +
            value.takeLast(tailLength).trimStart()
    }
}

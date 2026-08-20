package com.th3web.lean.core.awg

import java.io.BufferedReader

/**
 * The AmneziaWG core's own log, which otherwise never leaves the device.
 *
 * Unlike sing-box (whose stderr this app redirects into a file it tails) the AmneziaWG
 * library logs through `__android_log_write` under the tag `AmneziaWG/<interface>`. So
 * every reason a tunnel refuses to start is printed in full and then thrown away:
 * `IpcSet: invalid UAPI device key`, `Unable to bring up device: address already in use`,
 * a handshake the peer never answers. What reaches the user is our own one-line
 * "Не удалось запустить AmneziaWG", which names none of them, and a diagnostics report
 * carries just as little.
 *
 * Reading back is narrow: `logcat -d` (dump and exit, never a follow), a
 * bounded tail, and only the AmneziaWG tags. An app without READ_LOGS sees only its own
 * process's entries, which is the scope wanted here: the core runs in this
 * process. On a ROM that blocks logcat outright this simply yields nothing, which is why
 * every caller treats an empty result as normal rather than as an error.
 */
class AwgNativeLog(
    private val maxLines: Int = MAX_LINES,
    private val exec: (List<String>) -> Process = { ProcessBuilder(it).redirectErrorStream(true).start() },
) {

    /**
     * The tail of the core's log, oldest first. Empty when there is nothing to show or
     * the platform will not hand it over, never an exception: this runs on the failure
     * path, where throwing would replace a real diagnosis with a worse one.
     */
    fun tail(): List<String> = runCatching {
        val process = exec(
            listOf(
                "logcat",
                "-d",
                "-v",
                "brief",
                "-t",
                maxLines.toString(),
                // Silence everything else, then re-enable only the core's tags. The
                // wildcard covers the per-generation interface names (lean-awg-1, -2, …)
                // that [AwgEngine] hands to turnOn.
                "*:S",
                "AmneziaWG:V",
                "AmneziaWG/*:V",
            ),
        )
        val lines = process.inputStream.bufferedReader().use(BufferedReader::readLines)
        runCatching { process.destroy() }
        lines.map(String::trim).filter { it.isNotEmpty() && it !in NOISE }
    }.getOrDefault(emptyList())

    private companion object {
        const val MAX_LINES = 60

        /** logcat's own framing, which carries no information about the tunnel. */
        val NOISE = setOf("--------- beginning of main", "--------- beginning of system")
    }
}

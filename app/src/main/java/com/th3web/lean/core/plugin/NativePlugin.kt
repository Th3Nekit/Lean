package com.th3web.lean.core.plugin

import android.content.Context
import java.io.File

/**
 * The upstream protocol helpers that ship inside the APK as executables.
 *
 * None of these exist in the core: sing-box has no mieru at all; its only naive support
 * is an INBOUND (a server), while the client half of naive is Chromium's network stack
 * in C++ and cannot be linked into a Go core; olcRTC is a WebRTC tunnel of its own; and
 * XHTTP is Xray's transport, absent from the pinned sing-box under any spelling. So each
 * runs the way the reference client runs them: as its own process, listening on a local
 * SOCKS port that a plain `socks` outbound points at.
 *
 * They are laid into `jniLibs/<abi>/lib*.so` by native/plugins/vendor.ps1, see that
 * script for why an executable has to be called lib*.so, and the app reads them back
 * out of [android.content.pm.ApplicationInfo.nativeLibraryDir], the one directory whose
 * contents the installer unpacks with the execute bit set.
 */
enum class NativePlugin(
    val soName: String,
    val displayName: String,
    /**
     * How long this helper gets to open its local port before the connect gives up on it.
     *
     * Not one number for all of them, because they do not do the same thing before they
     * are ready. NaiveProxy and Mieru bind their listener as their first act, if they
     * are going to work, they answer in well under a second. olcRTC has to join a video
     * call on a conference server before it can carry anything, which is a full WebRTC
     * negotiation over the network; six seconds is a perfectly ordinary time for that to
     * still be in progress, and killing it there turned a working tunnel into
     * «olcRTC не открыл локальный порт за 6 с» and a cancelled connect.
     */
    val readyTimeoutMs: Long = DEFAULT_READY_TIMEOUT_MS,
) {
    Naive("libnaive.so", "NaiveProxy"),
    Mieru("libmieru.so", "Mieru"),
    Olcrtc("libolcrtc.so", "olcRTC", readyTimeoutMs = 30_000L),

    /**
     * Xray-core, used for exactly one thing: VLESS over XHTTP.
     *
     * Unlike the others this helper is not a protocol of its own: it is a second core
     * standing in for the first on the one transport the first cannot speak. Every other
     * VLESS node keeps going through sing-box; only `type=xhttp` is handed over here.
     */
    Xray("libxray.so", "Xray"),
    ;

    /**
     * The extracted binary, or null when this build has no copy for the running ABI.
     *
     * Null is a real state, not a defensive shrug: upstream mieru publishes an Android
     * build for arm64 only, so on a 32-bit or x86 device there is genuinely no binary to
     * run and the UI has to say so rather than fail at connect time with something
     * cryptic. (CI asserts exactly which ABIs are expected to carry which binary, so this
     * turning null unexpectedly is a build error, not a runtime surprise.)
     */
    fun binary(context: Context): File? {
        val dir = context.applicationInfo.nativeLibraryDir ?: return null
        return File(dir, soName).takeIf { it.canExecute() }
    }

    fun isAvailable(context: Context): Boolean = binary(context) != null

    companion object {
        /** Enough for a cold start on a slow device; short enough not to strand a user. */
        const val DEFAULT_READY_TIMEOUT_MS = 6_000L

        /**
         * SIGKILLs any helper left over from a previous run.
         *
         * A stop we control (disconnect, service destroy) already kills them, but a stop
         * we do not control does not: if the app process is force-stopped or killed by
         * the system, the helper is reparented and keeps running, still holding its
         * SOCKS port, so the next connect finds it taken and that protocol simply never
         * works again until reboot. Sweeping at start-up is the only cure, and it is what
         * the reference client does for the same reason (Executable.killAll).
         *
         * Matching is on the executable name from /proc/<pid>/cmdline, and only our own
         * two names: this runs as our UID, so it cannot touch another app's processes
         * even if one happened to share a name.
         */
        fun killOrphans() {
            val names = entries.map { it.soName }.toSet()
            val procs = File("/proc").listFiles { _, name -> name.all(Char::isDigit) } ?: return
            for (proc in procs) {
                // /proc/<pid>/cmdline is NUL-separated, so argv[0] ends at the first NUL.
                // Char.MIN_VALUE is that NUL, and naming it avoids putting a raw NUL byte
                // (or a fragile escape) into the source file.
                val argv0 = runCatching {
                    File(proc, "cmdline").readText().substringBefore(Char.MIN_VALUE)
                }.getOrNull()?.takeIf { it.isNotBlank() } ?: continue
                if (File(argv0).name !in names) continue
                val pid = proc.name.toIntOrNull() ?: continue
                runCatching { android.system.Os.kill(pid, android.system.OsConstants.SIGKILL) }
            }
        }
    }
}

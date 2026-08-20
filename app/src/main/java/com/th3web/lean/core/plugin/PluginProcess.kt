package com.th3web.lean.core.plugin

import android.content.Context
import android.os.Build
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.th3web.lean.core.CoreManager
import com.th3web.lean.ui.tr

/**
 * Runs one protocol helper binary for the lifetime of a tunnel session.
 *
 * A supervisor rather than a bare `Process`, because a proxy helper that dies silently is
 * indistinguishable from a server that stopped answering: the SOCKS port simply refuses
 * connections and every request through that outbound fails with nothing in the log to
 * explain it. So a process that exits while it is still wanted is restarted, with a
 * backoff, and every transition is written to the app log.
 *
 * Not a general process pool: exactly one binary, exactly one config file,
 * torn down with the session that owns it. [PluginSession] owns the set of these.
 */
internal class PluginProcess(
    private val context: Context,
    private val plugin: NativePlugin,
    private val configFile: File,
    private val arguments: List<String>,
    private val environment: Map<String, String>,
    private val workingDirectory: File,
) {
    // Written by spawn()/stop() on the caller's thread, read by the supervisor coroutine
    // on another, volatile so the supervisor sees a stop instead of waiting on a process
    // that is already gone.
    @Volatile private var process: Process? = null
    private var supervisor: Job? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** True once [stop] has run, so an exit stops being treated as a crash. */
    @Volatile private var stopping = false

    /**
     * Guards the handover of a freshly started process between [spawn] and [stop].
     *
     * Checking the flag before spawning is not enough on its own: the supervisor tests it,
     * the session stops in the window that follows, and the process started a moment later
     * belongs to nobody, still holding the SOCKS port the next connect has to bind. Under
     * the lock exactly one side wins: either stop sees the process and kills it, or spawn
     * sees the stop and kills its own.
     */
    private val lifecycle = Any()

    fun start() {
        val binary = plugin.binary(context)
            ?: error("${plugin.displayName}: нет бинарного файла для этого устройства")
        spawn(binary)
        supervisor = scope.launch {
            var backoffMs = RESTART_BACKOFF_MS
            while (isActive) {
                val current = process ?: break
                val startedAt = System.nanoTime()
                val code = withContext(Dispatchers.IO) { runCatching { current.waitFor() }.getOrNull() }
                if (stopping || !isActive) break
                val aliveMs = (System.nanoTime() - startedAt) / 1_000_000
                CoreManager.appendLog(
                    tr("⚠ %s: процесс завершился (код %d), перезапуск")
                        .format(plugin.displayName, code),
                )
                // A helper that ran healthily for a long time and then died once is not
                // the same failure as one that cannot start: it starts over from the
                // short delay rather than inheriting a penalty earned hours ago.
                if (aliveMs >= HEALTHY_RUN_MS) backoffMs = RESTART_BACKOFF_MS
                delay(backoffMs)
                if (stopping || !isActive) break
                // Widening backoff: a helper that cannot start at all (bad credentials, a
                // port already taken) would otherwise respawn in a tight loop for the
                // whole session, burning battery and filling the log with the same line.
                backoffMs = (backoffMs * 2).coerceAtMost(MAX_RESTART_BACKOFF_MS)
                runCatching { spawn(binary) }
                    .onFailure {
                        CoreManager.appendLog(
                            tr("✖ %s: перезапуск не удался: %s")
                                .format(plugin.displayName, it.message),
                        )
                        return@launch
                    }
            }
        }
    }

    private fun spawn(binary: File) {
        val builder = ProcessBuilder(listOf(binary.absolutePath) + arguments)
            .directory(workingDirectory)
            // Merged into one stream and drained below. Not draining at all is a classic
            // way to wedge a child: once the pipe buffer fills, its next write blocks
            // forever and the helper stops passing traffic without ever exiting.
            .redirectErrorStream(true)
        builder.environment().putAll(environment)
        val started = builder.start()
        val adopted = synchronized(lifecycle) {
            if (stopping) false else { process = started; true }
        }
        if (!adopted) {
            runCatching { started.destroy() }
            return
        }
        scope.launch {
            runCatching {
                var logged = 0
                var suppressed = 0
                started.inputStream.bufferedReader().forEachLine { line ->
                    if (line.isBlank()) return@forEachLine
                    // Read always, log only up to a cap. Both halves matter: stopping the
                    // read fills the pipe and wedges the helper mid-traffic, while logging
                    // without a bound turns every line into a state update and a file
                    // write. Every helper is configured quiet, so the cap is a backstop
                    // against one that turns out not to be.
                    when {
                        logged < MAX_LOGGED_LINES -> {
                            logged++
                            CoreManager.appendLog("[${plugin.displayName}] $line")
                        }
                        suppressed == 0 -> {
                            suppressed++
                            CoreManager.appendLog(
                                tr("[%s] …дальнейший вывод скрыт (слишком многословный)")
                                    .format(plugin.displayName),
                            )
                        }
                        else -> suppressed++
                    }
                }
            }
        }
    }

    fun stop() {
        supervisor?.cancel()
        val current = synchronized(lifecycle) {
            stopping = true
            process.also { process = null }
        }
        if (current != null) {
            runCatching {
                current.destroy()
                // A helper that ignores SIGTERM would otherwise outlive the session and
                // hold its SOCKS port, so the next connect fails to bind and the user sees
                // a protocol that worked once and never again. Escalate rather than hope.
                if (!awaitExit(current, STOP_GRACE_MS)) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        current.destroyForcibly()
                        awaitExit(current, STOP_GRACE_MS)
                    }
                }
            }
        }
        // Unconditional, both of them. A stop with no live process is the ordinary case
        // after a helper that failed to restart, and returning early there leaves the
        // generated config (credentials and all) on disk, and the supervisor scope
        // uncancelled for the rest of the process's life.
        runCatching { configFile.delete() }
        scope.cancel()
    }

    /**
     * Waits up to [timeoutMs] for [process] to exit; true if it did.
     *
     * Polls `exitValue()` rather than calling `waitFor(timeout, unit)`, which is API 26
     * and this app ships to API 24. `exitValue()` throws while the process is alive,
     * which is the documented way to ask without blocking.
     */
    private fun awaitExit(process: Process, timeoutMs: Long): Boolean {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        while (System.nanoTime() < deadline) {
            val exited = runCatching { process.exitValue() }.isSuccess
            if (exited) return true
            Thread.sleep(EXIT_POLL_MS)
        }
        return runCatching { process.exitValue() }.isSuccess
    }

    private companion object {
        const val RESTART_BACKOFF_MS = 500L
        const val MAX_RESTART_BACKOFF_MS = 15_000L
        const val STOP_GRACE_MS = 1_500L
        const val EXIT_POLL_MS = 25L

        /** Per run of the helper. Enough to carry a startup failure, far short of a flood. */
        const val MAX_LOGGED_LINES = 60

        /** A run this long counts as healthy, so the restart delay starts over. */
        const val HEALTHY_RUN_MS = 60_000L
    }
}

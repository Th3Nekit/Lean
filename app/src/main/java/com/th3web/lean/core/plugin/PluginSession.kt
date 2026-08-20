package com.th3web.lean.core.plugin

import android.content.Context
import java.io.File
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import com.th3web.lean.core.CoreManager
import com.th3web.lean.data.model.Outbound
import com.th3web.lean.data.parse.ShareLinks
import com.th3web.lean.ui.tr

/** One helper-backed profile that cannot carry traffic this session, and why. */
internal data class PluginFailure(val profileId: String, val reason: String)

/**
 * One outbound that needs a helper process, resolved to concrete ports.
 *
 * [localPort] is where the helper listens for SOCKS; the core's outbound points there.
 * [mappingPort] is where the core listens; the helper dials that instead of the real
 * server, and the core forwards it on a protected socket.
 */
internal data class PluginBinding(
    val profileId: String,
    val plugin: NativePlugin,
    val localPort: Int,
    val mappingPort: Int,
    val serverHost: String,
    val serverPort: Int,
)

/**
 * The helper processes belonging to one tunnel session.
 *
 * ## Why the traffic takes such an odd path
 *
 * ```
 *   core socks outbound ─▶ 127.0.0.1:localPort   [helper process]
 *                                     │
 *                                     ▼
 *                          127.0.0.1:mappingPort  (core "direct" inbound,
 *                                     │            override_address = real server)
 *                                     ▼
 *                          core direct outbound ─▶ real server   (protected fd)
 * ```
 *
 * The helper never dials the internet itself. That is not indirection for its own sake:
 * a helper runs as this app's UID, so its sockets go into our own tun like any other app
 * traffic, and it would be proxying itself in a circle. The core is the only component
 * that can escape, because only it holds the VpnService protect() hook, so everything
 * leaves through it. The reference client states the same reason in one line:
 * "For external proxy software, their traffic must goes to v2ray-core to use protected
 * fd." (ConfigBuilder.kt)
 *
 * mieru could in principle protect its own sockets (it takes MIERU_PROTECT_PATH, and the
 * core already serves that unix socket), but naive has no protect support whatsoever, so
 * the mapping is the one mechanism that works for both, and one path is worth more here
 * than a marginally shorter one for half the protocols.
 */
internal class PluginSession(
    private val context: Context,
    /**
     * Overrides every helper's own budget. Injectable so the readiness contract can be
     * tested without waiting out a real one; null in production, where each plugin brings
     * the budget that suits what it has to do before it can answer.
     */
    private val readyTimeoutMs: Long? = null,
) {

    /** Keyed by profile id so a helper written off as dead can be stopped on its own. */
    private val processes = linkedMapOf<String, PluginProcess>()

    /** Allocates the ports each helper-backed profile will use. Starts nothing yet. */
    fun plan(profiles: List<Pair<String, Outbound>>): List<PluginBinding> {
        val wanted = profiles.filter { (_, outbound) -> isPluginOutbound(outbound) }
        if (wanted.isEmpty()) return emptyList()
        // Sweep once, before any port is picked. A helper we never got to stop, the app
        // was force-stopped, or the system killed the process, is still holding its
        // SOCKS port, and a port we then pick could be that one. This is the only thing
        // that keeps a hard kill from disabling the protocol until the phone reboots.
        NativePlugin.killOrphans()
        return wanted.map { (id, outbound) ->
            PluginBinding(
                profileId = id,
                plugin = requireNotNull(pluginFor(outbound)),
                localPort = freePort(),
                mappingPort = freePort(),
                serverHost = outbound.server,
                serverPort = outbound.serverPort,
            )
        }
    }

    /**
     * Spawns the helper for [binding] and returns without waiting for it to bind.
     *
     * Split from the wait: helpers are independent processes, so a profile
     * with several of them should pay one readiness budget rather than one per helper.
     * See [awaitAllReady].
     *
     * Returns the reason it could not even be started, or null on success. A missing
     * binary is a real, expected state, upstream mieru and olcRTC publish arm64 builds
     * only, and it is the caller that knows whether this node was the only way out.
     */
    fun spawn(binding: PluginBinding, outbound: Outbound): String? {
        if (!binding.plugin.isAvailable(context)) {
            return "${binding.plugin.displayName} недоступен на этом устройстве (нет сборки для этой архитектуры)"
        }
        val dir = File(context.cacheDir, PLUGIN_DIR).apply { mkdirs() }
        val config = when (outbound) {
            is Outbound.Mieru -> PluginConfig.forMieru(
                outbound, binding.localPort, PluginConfig.LOCALHOST, binding.mappingPort,
            )
            is Outbound.Naive -> PluginConfig.forNaive(
                outbound, binding.localPort, PluginConfig.LOCALHOST, binding.mappingPort,
            )
            is Outbound.Olcrtc -> PluginConfig.forOlcrtc(
                outbound, binding.localPort, PluginConfig.LOCALHOST, binding.mappingPort,
                dataDir = File(dir, OLCRTC_DATA_DIR).apply { mkdirs() }.absolutePath,
            )
            is Outbound.Vless -> PluginConfig.forXray(
                outbound, binding.localPort, PluginConfig.LOCALHOST, binding.mappingPort,
            )
            else -> error("${outbound.protocol} does not run as a plugin")
        }
        val extension = if (outbound is Outbound.Olcrtc) "yaml" else "json"
        val configFile = File(dir, "${binding.plugin.name.lowercase()}-${binding.profileId}.$extension")
        configFile.writeText(config)

        val environment = mutableMapOf<String, String>()
        val arguments = mutableListOf<String>()
        when (outbound) {
            is Outbound.Mieru -> {
                // mieru reads its config from a file named by env, and takes the
                // subcommand on the command line.
                environment["MIERU_CONFIG_JSON_FILE"] = configFile.absolutePath
                // Harmless alongside the mapping (mieru dials 127.0.0.1, which needs no
                // protection), and correct if it ever dials out directly.
                environment["MIERU_PROTECT_PATH"] = PROTECT_SOCKET
                arguments += "run"
            }
            is Outbound.Olcrtc -> {
                // The CLI takes exactly one argument: the path to the YAML.
                arguments += configFile.absolutePath
            }
            is Outbound.Vless -> {
                // `run -c <file>`. Xray also reads XRAY_LOCATION_ASSET for geoip/geosite,
                // which this build ships without, the config never names a
                // geo rule, so nothing looks for them.
                arguments += "run"
                arguments += "-c"
                arguments += configFile.absolutePath
            }
            is Outbound.Naive -> {
                if (outbound.certificates.isNotBlank()) {
                    val certFile = File(dir, "naive-${binding.profileId}.crt")
                    certFile.writeText(outbound.certificates)
                    environment["SSL_CERT_FILE"] = certFile.absolutePath
                }
                arguments += configFile.absolutePath
            }
            else -> Unit
        }

        val process = PluginProcess(
            context = context,
            plugin = binding.plugin,
            configFile = configFile,
            arguments = arguments,
            environment = environment,
            // The core chdir()s here at init and serves the protect socket by relative
            // name, so a helper only finds it from the same directory.
            workingDirectory = File(context.filesDir.parentFile, NO_BACKUP_DIR)
                .takeIf { it.isDirectory } ?: context.filesDir,
        )
        val failure = runCatching { process.start() }.exceptionOrNull()
        if (failure != null) {
            return failure.message ?: failure.javaClass.simpleName
        }
        processes[binding.profileId] = process
        CoreManager.appendLog(
            "▶ ${binding.plugin.displayName}: socks 127.0.0.1:${binding.localPort} → ${binding.serverHost}:${binding.serverPort}",
        )
        return null
    }

    /**
     * Blocks until every helper in [bindings] is listening, and reports the ones that
     * never were.
     *
     * Waiting matters at all because the alternative is the worst kind of failure: a
     * helper dies or never binds its port, the core comes up anyway pointing a socks
     * outbound at that port, and every connection through it fails with
     *   open connection to …: dial tcp 127.0.0.1:37995: connect: connection refused
     * while the UI says «подключено». A whole diagnostics report is fifty of those lines
     * and nothing else.
     *
     * Waiting for them together matters because they were spawned together. Doing it one
     * at a time multiplied a single readiness budget by the number of
     * helper-backed nodes in the profile, an «Авто» group with five of them could spend
     * half a minute before admitting the connect had failed, and even the happy path paid
     * each helper's start-up in series. One deadline, polled round-robin, costs the
     * slowest helper instead of the sum of all of them.
     */
    fun awaitAllReady(bindings: List<PluginBinding>): List<PluginFailure> {
        if (bindings.isEmpty()) return emptyList()
        // Polling the port is the only reliable signal, and the only one
        // consulted: PluginProcess restarts a helper that dies, so "the process is not
        // running right now" is a normal moment between attempts rather than a verdict.
        // A deadline per helper, not one for the set. They are waited on together, that
        // is what this function is for, but they are not owed the same time: a helper
        // that binds a port as its first act has answered or failed in well under a
        // second, while olcRTC has a video call to join first. One budget for both either
        // strands the fast ones behind the slow one or kills the slow one for being slow.
        val startedAt = System.nanoTime()
        fun budgetOf(binding: PluginBinding): Long =
            readyTimeoutMs ?: binding.plugin.readyTimeoutMs
        val pending = bindings.toMutableList()
        val lastError = mutableMapOf<String, String>()
        val expired = mutableListOf<PluginBinding>()
        while (true) {
            pending.removeAll { binding ->
                val outcome = runCatching { speaksSocks5(binding.localPort) }
                outcome.exceptionOrNull()?.message?.let { lastError[binding.profileId] = it }
                if (outcome.getOrDefault(false)) {
                    CoreManager.appendLog(
                        tr("✓ %s: локальный socks отвечает").format(binding.plugin.displayName),
                    )
                    true
                } else {
                    false
                }
            }
            val elapsed = System.nanoTime() - startedAt
            pending.removeAll { binding ->
                (elapsed >= budgetOf(binding) * 1_000_000L).also { if (it) expired += binding }
            }
            if (pending.isEmpty()) break
            Thread.sleep(READY_POLL_MS)
        }
        if (expired.isEmpty()) return emptyList()
        return expired.map { binding ->
            // Stop it rather than leave it respawning. PluginProcess restarts a helper
            // that dies, so a binary that cannot work, wrong credentials, a port taken by
            // another app, would otherwise spend the whole session in a back-off loop for
            // a node nothing is going to route through anyway.
            processes.remove(binding.profileId)?.let { runCatching { it.stop() } }
            PluginFailure(
                profileId = binding.profileId,
                reason = "${binding.plugin.displayName} не открыл локальный порт " +
                    "${PluginConfig.LOCALHOST}:${binding.localPort} за ${budgetOf(binding) / 1000} с" +
                    (lastError[binding.profileId]?.let { " ($it)" } ?: ""),
            )
        }
    }

    /** Stops every helper. Safe to call twice; never throws. */
    fun stopAll() {
        processes.values.forEach { runCatching { it.stop() } }
        processes.clear()
    }

    companion object {
        fun pluginFor(outbound: Outbound): NativePlugin? = when (outbound) {
            is Outbound.Naive -> NativePlugin.Naive
            is Outbound.Mieru -> NativePlugin.Mieru
            is Outbound.Olcrtc -> NativePlugin.Olcrtc
            // The only outbound whose protocol does not decide this. A VLESS node runs in
            // the core like any other unless it uses something the core has no
            // implementation of, then, and only then, Xray takes that one profile.
            is Outbound.Vless -> NativePlugin.Xray.takeIf { needsXray(outbound) }
            else -> null
        }

        /**
         * True when this VLESS node asks for something the pinned sing-box does not have.
         *
         * Two such things exist today. XHTTP is a transport that is absent from the core
         * under any spelling: its transport list is v2ray{grpc,http,httpupgrade,quic,
         * websocket} and nothing else. VLESS `encryption` is the protocol's post-quantum
         * layer, which the core's VLESS predates entirely; "none" (or nothing at all) is
         * every ordinary node and stays in the core.
         */
        fun needsXray(outbound: Outbound.Vless): Boolean =
            outbound.transport?.type.equals(ShareLinks.XHTTP, ignoreCase = true) ||
                outbound.encryption.isNotBlank() && outbound.encryption != "none"

        /** True when this outbound needs a helper process to work at all. */
        fun isPluginOutbound(outbound: Outbound): Boolean = pluginFor(outbound) != null

        /**
         * An ephemeral free port, obtained by binding one and closing it.
         *
         * Never a fixed port: two profiles of the same protocol would collide, and a
         * hardcoded port is also the kind of thing another app can be sitting on, which
         * shows up as one protocol that mysteriously never works on one person's phone.
         * The small race between closing and the helper binding is unavoidable with
         * ProcessBuilder and is what every client doing this accepts.
         */
        fun freePort(): Int = ServerSocket(0).use { it.localPort }

        /**
         * One SOCKS5 greeting: connect, offer "no authentication", expect version 5 back.
         *
         * A plain TCP connect is not enough: the port could be held by something else
         * entirely, and on a helper that is still initialising the listener can accept before
         * it can answer. The greeting is what makes this a statement about the helper.
         */
        internal fun speaksSocks5(port: Int): Boolean = Socket().use { socket ->
            socket.connect(InetSocketAddress(PluginConfig.LOCALHOST, port), READY_POLL_MS.toInt())
            socket.soTimeout = READY_POLL_MS.toInt()
            socket.getOutputStream().apply {
                write(byteArrayOf(0x05, 0x01, 0x00))
                flush()
            }
            val reply = ByteArray(2)
            socket.getInputStream().read(reply) == 2 && reply[0] == 0x05.toByte()
        }

        private const val READY_POLL_MS = 250L

        private const val PLUGIN_DIR = "plugins"

        /**
         * olcRTC's `data` directory. It expects to find name lists there to invent a
         * plausible display name for the participant it joins the meeting as; the
         * directory has to exist even when empty, or the config is rejected on load.
         */
        private const val OLCRTC_DATA_DIR = "olcrtc-data"
        private const val NO_BACKUP_DIR = "no_backup"
        private const val PROTECT_SOCKET = "protect_path"
    }
}

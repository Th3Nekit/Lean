package com.th3web.lean.core.awg

import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import com.th3web.lean.awg.AmneziaWgNative
import com.th3web.lean.core.connection.ConnectionCommand
import com.th3web.lean.core.connection.ConnectionRuntime
import com.th3web.lean.core.connection.ConnectionSession
import com.th3web.lean.core.connection.DesiredConnection
import com.th3web.lean.core.tun.AwgTunSpec
import com.th3web.lean.core.tun.TunRuntimePolicy
import com.th3web.lean.data.model.Outbound
import com.th3web.lean.data.model.Profile

interface AwgTunnelSession {
    fun prepareNetwork(generation: Long, policy: TunRuntimePolicy, networkToken: Any): Boolean
    fun establishAndDetach(generation: Long, spec: AwgTunSpec): Int
    fun closeDetachedFd(generation: Long, fd: Int)
    fun close(generation: Long)
}

fun interface AwgSessionObserver {
    suspend fun close()
}

fun interface AwgRuntime {
    suspend fun start(command: ConnectionCommand, profile: Profile): ConnectionSession
}

class AwgEngine(
    private val policyProvider: suspend () -> TunRuntimePolicy,
    private val adapter: AwgProfileAdapter,
    private val endpointResolver: AwgEndpointResolver,
    private val native: AmneziaWgNative,
    private val generationIsCurrent: (Long) -> Boolean,
    private val tunnel: AwgTunnelSession,
    private val protectSocket: (Int) -> Boolean,
    /** Receives the live device handle too, so the host can read UAPI counters. */
    private val onStarted: suspend (Long, Profile, Int) -> AwgSessionObserver,
    /** Overridable so the no-handshake path can be tested without the full wait. */
    private val handshakeTimeoutMs: Long = HANDSHAKE_TIMEOUT_MS,
    /**
     * Where progress goes. Injected rather than reaching for the log singleton: this
     * engine is unit-tested against fakes and has no business knowing about the UI.
     */
    private val onProgress: (String) -> Unit = {},
    /**
     * The core's own log ([AwgNativeLog]), read only when a start has already failed.
     *
     * Every reason the library refuses, a UAPI key it does not know, a port it cannot
     * bind, a tun it cannot wrap, is printed by the Go side and by nothing else, so
     * without this the app can report only that it failed. Injected, so tests keep the
     * failure paths free of process spawning.
     */
    private val nativeLog: () -> List<String> = { emptyList() },
) : AwgRuntime {
    override suspend fun start(command: ConnectionCommand, profile: Profile): ConnectionSession {
        val desired = command.desired as? DesiredConnection.Running
            ?: throw IllegalArgumentException("AmneziaWG нельзя запустить для команды остановки")
        require(profile.id == desired.profileId) { "Выбран устаревший профиль AmneziaWG" }

        var networkPrepared = false
        var rawFd = -1
        var rawOwned = false
        var handle = -1
        var observer: AwgSessionObserver? = null
        try {
            val policy = policyProvider()
            val prepared = adapter.prepare(profile, policy)
            var settings: String? = null
            var attempt = 0
            while (settings == null) {
                val endpoint = endpointResolver.resolve(prepared.server, prepared.serverPort)
                ensureCurrent(command.generation)
                if (tunnel.prepareNetwork(command.generation, policy, endpoint.networkToken)) {
                    networkPrepared = true
                    settings = adapter.userspaceConfig(prepared, endpoint.endpoint)
                    break
                }
                ensureCurrent(command.generation)
                attempt++
                if (attempt == MAX_NETWORK_ACQUIRE_ATTEMPTS) {
                    throw AwgEndpointException(
                        "Физическая сеть менялась во время запуска AmneziaWG",
                    )
                }
            }
            val nativeSettings = checkNotNull(settings)
            // The two things that decide whether a tunnel that handshakes also carries:
            // the frame size it was built with, and whether the obfuscation is padding
            // every packet on top of that. Stated up front so a log from the field answers
            // the question without a second round trip.
            onProgress(
                "AmneziaWG: MTU %d%s, обфускация %s".format(
                    prepared.tunSpec.mtu,
                    // Named when it is not the number the user chose, because a silently
                    // lowered MTU is indistinguishable from a setting that did not apply,
                    // and that confusion is what cost the last few rounds.
                    if (prepared.tunSpec.mtu < policy.wgMtu) {
                        " (снижен с %d: обфускация добавляет байты в каждый пакет)"
                            .format(policy.wgMtu)
                    } else {
                        ""
                    },
                    describeObfuscation(prepared),
                ),
            )
            ensureCurrent(command.generation)
            rawFd = tunnel.establishAndDetach(command.generation, prepared.tunSpec)
            rawOwned = true
            ensureCurrent(command.generation)
            handle = native.turnOn("lean-awg-${command.generation}", rawFd, nativeSettings)
            if (handle < 0) {
                // turnOn returns a bare -1 for everything: a rejected UAPI key, a port it
                // could not bind, a tun it could not wrap. The reason exists, the Go side
                // logged it, so put it where the user and a diagnostics report can see it
                // instead of leaving them with "не удалось" and nothing else.
                reportNativeLog()
                throw AwgRuntimeException("Не удалось запустить AmneziaWG")
            }
            onProgress("AmneziaWG: устройство поднято")
            rawOwned = false
            ensureCurrent(command.generation)
            val socketV4 = native.getSocketV4(handle)
            ensureCurrent(command.generation)
            protect(socketV4)
            ensureCurrent(command.generation)
            val socketV6 = native.getSocketV6(handle)
            ensureCurrent(command.generation)
            protect(socketV6)
            ensureCurrent(command.generation)
            awaitHandshake(command.generation, handle)
            observer = onStarted(command.generation, profile, handle)
            ensureCurrent(command.generation)
            return Session(profile.id, command.generation, handle, observer)
        } catch (failure: Throwable) {
            runCatching { observer?.close() }
            if (handle >= 0) {
                runCatching { native.turnOff(handle) }
            } else if (rawOwned) {
                runCatching { tunnel.closeDetachedFd(command.generation, rawFd) }
            }
            if (networkPrepared) runCatching { tunnel.close(command.generation) }
            if (failure is CancellationException) throw failure
            if (failure is IllegalArgumentException || failure is AwgEndpointException ||
                failure is AwgRuntimeException
            ) {
                throw failure
            }
            throw AwgRuntimeException("Не удалось запустить AmneziaWG", failure)
        }
    }

    /**
     * Blocks the connect until the peer has actually answered a handshake, for as long as
     * the PROTOCOL is still trying.
     *
     * Everything before this point can succeed on a tunnel that will never carry a byte:
     * `turnOn` returns a handle as soon as the device is configured, and WireGuard is
     * silent by design, a wrong key, a wrong Endpoint or obfuscation parameters that do
     * not match the server produce no error anywhere, just a peer that never replies.
     * Reporting «подключено» at that point produces the report that says "connects, no
     * errors, no speed", a log of connect/disconnect pairs with no error line between.
     *
     * `last_handshake_time_sec` is the one unambiguous proof the far side is really
     * there: it stays 0 until a handshake completes. The wait is generous because the
     * initiation is triggered by the first packet through the fresh TUN (Android's own
     * connectivity probe does that within a second), and may be retried a couple of times
     * on a slow link.
     */
    private suspend fun awaitHandshake(generation: Long, handle: Int) {
        val startedAt = System.nanoTime()
        val deadline = startedAt + handshakeTimeoutMs * 1_000_000L
        var nextNotice = startedAt + HANDSHAKE_NOTICE_MS * 1_000_000L
        while (true) {
            ensureCurrent(generation)
            if (hasHandshake(native.getConfig(handle))) {
                onProgress(
                    "AmneziaWG: рукопожатие за %d мс"
                        .format((System.nanoTime() - startedAt) / 1_000_000L),
                )
                return
            }
            // A wait this long has to say so, or it reads as a hang.
            if (System.nanoTime() >= nextNotice) {
                nextNotice += HANDSHAKE_NOTICE_MS * 1_000_000L
                onProgress(
                    "… рукопожатие AmneziaWG ещё идёт (%d с)"
                        .format((System.nanoTime() - startedAt) / 1_000_000_000L),
                )
            }
            if (System.nanoTime() >= deadline) {
                // The peer stayed silent for as long as the protocol keeps trying. The
                // core logged every initiation it sent and every packet it dropped as
                // unparseable, which is how a mismatched Jc/S/H set tells itself apart
                // from a server that is simply not there.
                reportNativeLog()
                throw AwgRuntimeException(
                    "Сервер AmneziaWG не ответил на рукопожатие за " +
                        "${handshakeTimeoutMs / 1000} с. Проверьте ключи, Endpoint и " +
                        "параметры обфускации (Jc/Jmin/Jmax, S1-S4, H1-H4, I1-I5) — " +
                        "они должны в точности совпадать с серверными.",
                )
            }
            delay(HANDSHAKE_POLL_MS)
        }
    }

    /**
     * The junk the AmneziaWG layer adds, in the terms that matter for size.
     *
     * s1/s2 pad the handshake, which is why a mismatched pair fails to connect at all.
     * s4 pads every transport packet, so it eats into what the MTU allows and is the one
     * that turns "connects, then stops" into an explanation rather than a mystery.
     */
    private fun describeObfuscation(prepared: PreparedAwgProfile): String = prepared.awg.run {
        val parts = buildList {
            if (jc > 0) add("jc=$jc")
            if (s1 > 0) add("s1=$s1")
            if (s2 > 0) add("s2=$s2")
            if (s3 > 0) add("s3=$s3")
            if (s4 > 0) add("s4=$s4 (в каждом пакете)")
            // The h-params are what stop a TRANSPORT packet from announcing itself as
            // WireGuard. Named individually because their absence is the finding: with
            // only jc/s1/s2 the handshake is disguised and everything after it is not,
            // which is a tunnel that connects and then gets shut down mid-stream.
            listOf("h1" to h1, "h2" to h2, "h3" to h3, "h4" to h4).forEach { (name, value) ->
                if (value.isNotBlank()) add("$name=$value")
            }
            if (i1.isNotBlank()) add("i1…i5")
        }
        val listed = if (parts.isEmpty()) "нет" else parts.joinToString(" ")
        if (h4.isBlank() && s4 <= 0) {
            "$listed — транспорт БЕЗ маскировки (только рукопожатие)"
        } else {
            listed
        }
    }

    /** UAPI reports one `last_handshake_time_sec` per peer; 0 means "never". */
    private fun hasHandshake(config: String?): Boolean =
        config?.lineSequence()?.any { line ->
            line.startsWith(HANDSHAKE_KEY) &&
                (line.substringAfter('=').trim().toLongOrNull() ?: 0L) > 0L
        } ?: false

    /**
     * Copies the core's log into the app's, prefixed so it is obvious whose lines these
     * are. Never throws and never reports its own absence: on a ROM that withholds logcat
     * the correct outcome is the failure message alone, not a second failure about it.
     */
    private fun reportNativeLog() {
        val lines = runCatching { nativeLog() }.getOrDefault(emptyList())
        if (lines.isEmpty()) {
            // Silence here is itself a finding and must not read as "nothing went
            // wrong". The library reports through the Android log, which an ordinary app
            // cannot read on several vendor ROMs, the devices where this
            // matters most.
            onProgress("AmneziaWG: лог ядра недоступен на этой прошивке")
            return
        }
        onProgress("— лог ядра AmneziaWG —")
        lines.forEach { onProgress(it) }
    }

    private fun protect(fd: Int) {
        if (fd >= 0 && !protectSocket(fd)) {
            throw AwgRuntimeException("Не удалось защитить сокет AmneziaWG")
        }
    }

    private fun ensureCurrent(generation: Long) {
        if (!generationIsCurrent(generation)) {
            throw AwgRuntimeException("Запуск AmneziaWG устарел")
        }
    }

    private inner class Session(
        override val profileId: String,
        private val generation: Long,
        private val handle: Int,
        private val observer: AwgSessionObserver,
    ) : ConnectionSession {
        private val closed = AtomicBoolean()

        override suspend fun close() {
            if (!closed.compareAndSet(false, true)) return
            try {
                observer.close()
            } finally {
                try {
                    native.turnOff(handle)
                } finally {
                    tunnel.close(generation)
                }
            }
        }
    }

    private companion object {
        const val MAX_NETWORK_ACQUIRE_ATTEMPTS = 3

        /**
         * WireGuard's own budget, and it must not be undercut.
         *
         * The protocol retries an initiation every 5 seconds and gives up at 90
         * (RekeyAttemptTime), so anything shorter calls a handshake dead while the tunnel
         * is still legitimately trying. Giving up early is worse than waiting: tearing the
         * session down opens a new socket on a new source port, so a server that is itself
         * initiating at a stale endpoint sees the address move again and the handshake can
         * never land.
         */
        const val HANDSHAKE_TIMEOUT_MS = 95_000L
        const val HANDSHAKE_POLL_MS = 250L

        /** How often the wait reports itself, so a long one is not mistaken for a hang. */
        const val HANDSHAKE_NOTICE_MS = 15_000L
        const val HANDSHAKE_KEY = "last_handshake_time_sec="
    }
}

class EngineSelector(
    private val profileProvider: suspend (ConnectionCommand) -> List<Profile>,
    private val neko: ConnectionRuntime,
    private val awg: AwgRuntime,
) : ConnectionRuntime {
    override suspend fun start(command: ConnectionCommand): ConnectionSession {
        val profiles = profileProvider(command)
        val awgCount = profiles.count {
            (it.outbound as? Outbound.WireGuard)?.awg != null
        }
        return if (awgCount > 0) {
            require(profiles.size == 1 && awgCount == 1) {
                "Для AmneziaWG нужно выбрать ровно один профиль"
            }
            awg.start(command, profiles.single())
        } else {
            neko.start(command)
        }
    }
}

class AwgRuntimeException(message: String, cause: Throwable? = null) :
    IllegalStateException(message, cause)

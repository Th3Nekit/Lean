package com.th3web.lean.core.awg

import java.util.Base64
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import com.th3web.lean.awg.AmneziaWgNative
import com.th3web.lean.core.connection.ConnectionCommand
import com.th3web.lean.core.connection.ConnectionRuntime
import com.th3web.lean.core.connection.ConnectionSession
import com.th3web.lean.core.connection.DesiredConnection
import com.th3web.lean.core.tun.AwgTunSpec
import com.th3web.lean.core.tun.TunRuntimePolicy
import com.th3web.lean.data.PerAppMode
import com.th3web.lean.data.model.AmneziaParams
import com.th3web.lean.data.model.Outbound
import com.th3web.lean.data.model.Profile

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AwgEngineTest {
    @Test
    fun `success follows ownership order and repeated close turns off once`() = runBlocking {
        val events = mutableListOf<String>()
        val native = FakeNative(events)
        val tunnel = FakeTunnel(events)
        val engine = engine(events, native, tunnel)

        val session = engine.start(command(7), profile())
        assertEquals(
            listOf(
                "validate", "resolve", "network.prepare:7", "tun.establish-detach:7",
                "native.on:lean-awg-7:73", "native.v4:9", "protect:9",
                "native.v6:10", "protect:10", "started:7",
            ),
            events,
        )

        session.close()
        session.close()
        assertEquals(
            listOf("observer.close", "native.off:17", "tun.close:7"),
            events.takeLast(3),
        )
        assertEquals(1, native.turnOffCalls)
        assertEquals(0, tunnel.rawCloseCalls)
    }

    @Test
    fun `negative turnOn closes detached fd and never turns off`() = runBlocking {
        val events = mutableListOf<String>()
        val native = FakeNative(events, turnOnResult = -1)
        val tunnel = FakeTunnel(events)

        val error = runCatching {
            engine(events, native, tunnel).start(command(8), profile())
        }.exceptionOrNull()

        checkNotNull(error)
        assertTrue(error.message.orEmpty().contains("запустить"))
        assertEquals(
            listOf("native.on:lean-awg-8:73", "raw.close:73", "tun.close:8"),
            events.takeLast(3),
        )
        assertEquals(0, native.turnOffCalls)
        assertEquals(1, tunnel.rawCloseCalls)
    }

    /**
     * A peer that never answers must fail the connect, not be reported as connected.
     *
     * Everything mechanical succeeds here — the device is configured and both sockets are
     * protected — yet the far side never handshakes, which is what a wrong key, a wrong
     * Endpoint or mismatched obfuscation parameters look like: no error anywhere, no
     * traffic ever. Testers reported precisely that ("подключается, ошибок нет, скорости
     * нет"), and the whole session teardown below is what used to be missing.
     */
    @Test
    fun `a peer that never handshakes fails instead of reporting connected`() = runBlocking {
        val events = mutableListOf<String>()
        val native = FakeNative(events).apply { handshakeSeconds = 0L }
        val tunnel = FakeTunnel(events)

        val error = runCatching {
            engine(events, native, tunnel, handshakeTimeoutMs = 40L).start(command(11), profile())
        }.exceptionOrNull()

        checkNotNull(error)
        assertTrue(error.message.orEmpty().contains("рукопожатие"))
        // Never reported as started, and the handle and TUN are both released.
        assertTrue(events.none { it.startsWith("started:") })
        assertEquals(listOf("native.off:17", "tun.close:11"), events.takeLast(2))
        assertEquals(1, native.turnOffCalls)
    }

    @Test
    fun `protect failure rolls live handle back before controller close`() = runBlocking {
        val events = mutableListOf<String>()
        val native = FakeNative(events)
        val tunnel = FakeTunnel(events)
        val engine = engine(events, native, tunnel, protect = { fd ->
            events += "protect:$fd"
            fd != 10
        })

        checkNotNull(runCatching { engine.start(command(9), profile()) }.exceptionOrNull())
        assertEquals(listOf("protect:10", "native.off:17", "tun.close:9"), events.takeLast(3))
        assertEquals(1, native.turnOffCalls)
        assertEquals(0, tunnel.rawCloseCalls)
    }

    @Test
    fun `stale generation before native closes raw fd and newer generation is untouched`() = runBlocking {
        val events = mutableListOf<String>()
        var checks = 0
        val native = FakeNative(events)
        val tunnel = FakeTunnel(events)
        val engine = engine(
            events,
            native,
            tunnel,
            generationCurrent = { ++checks < 3 },
        )

        checkNotNull(runCatching { engine.start(command(10), profile()) }.exceptionOrNull())
        assertFalse(events.any { it.startsWith("native.on") })
        assertEquals(listOf("raw.close:73", "tun.close:10"), events.takeLast(2))
        assertEquals(0, native.turnOffCalls)
    }

    @Test
    fun `selector routes awg without touching Neko runtime and rejects mixed selection`() = runBlocking {
        val awgProfile = profile()
        var nekoStarts = 0
        var awgStarts = 0
        val neko = runtime { nekoStarts++ }
        val awg = awgRuntime { awgStarts++ }
        val selector = EngineSelector(
            profileProvider = { listOf(awgProfile) },
            neko = neko,
            awg = awg,
        )

        selector.start(command(11))
        assertEquals(0, nekoStarts)
        assertEquals(1, awgStarts)

        val mixed = EngineSelector(
            profileProvider = { listOf(awgProfile, profile(awg = null)) },
            neko = neko,
            awg = awg,
        )
        val error = runCatching { mixed.start(command(12)) }.exceptionOrNull()
        checkNotNull(error)
        assertTrue(error.message.orEmpty().contains("один профиль"))
    }

    @Test
    fun `selector keeps non awg profiles on Neko runtime`() = runBlocking {
        var nekoStarts = 0
        var awgStarts = 0
        val selector = EngineSelector(
            profileProvider = { listOf(profile(awg = null)) },
            neko = runtime { nekoStarts++ },
            awg = awgRuntime { awgStarts++ },
        )

        selector.start(command(13))

        assertEquals(1, nekoStarts)
        assertEquals(0, awgStarts)
    }

    @Test
    fun `selector passes one immutable profile snapshot to awg runtime`() = runBlocking {
        val events = mutableListOf<String>()
        var providerReads = 0
        val provider: suspend (ConnectionCommand) -> List<Profile> = {
            providerReads++
            listOf(if (providerReads == 1) profile() else profile(awg = null))
        }
        val native = FakeNative(events)
        val tunnel = FakeTunnel(events)
        val awg = AwgEngine(
            policyProvider = {
                TunRuntimePolicy(true, true, true, PerAppMode.OFF, emptySet())
            },
            adapter = AwgConfigAdapter(),
            endpointResolver = AwgEndpointResolver { _, _ ->
                ResolvedAwgEndpoint("203.0.113.8:51820", "network")
            },
            native = native,
            generationIsCurrent = { true },
            tunnel = tunnel,
            protectSocket = { true },
            onStarted = { _, _, _ -> AwgSessionObserver {} },
        )
        val selector = EngineSelector(
            profileProvider = provider,
            neko = runtime { error("Neko must not start") },
            awg = awg,
        )

        val session = selector.start(command(22))

        assertEquals(1, providerReads)
        session.close()
    }

    @Test
    fun `network handover before tun acquire re-resolves endpoint on the new network`() = runBlocking {
        val events = mutableListOf<String>()
        var network = "A"
        var resolverCalls = 0
        val native = FakeNative(events)
        val tunnel = object : AwgTunnelSession {
            override fun prepareNetwork(
                generation: Long,
                policy: TunRuntimePolicy,
                networkToken: Any,
            ): Boolean {
                events += "network.prepare:$network"
                return networkToken == network
            }

            override fun establishAndDetach(generation: Long, spec: AwgTunSpec): Int {
                events += "tun.establish-detach:$network"
                return 73
            }

            override fun closeDetachedFd(generation: Long, fd: Int) {
                events += "raw.close:$fd"
            }

            override fun close(generation: Long) {
                events += "tun.close:$generation"
            }
        }
        val engine = AwgEngine(
            policyProvider = {
                TunRuntimePolicy(true, true, true, PerAppMode.OFF, emptySet())
            },
            adapter = AwgConfigAdapter(),
            endpointResolver = AwgEndpointResolver { _, _ ->
                resolverCalls++
                val resolvedNetwork = network
                events += "resolve:$resolvedNetwork"
                if (resolverCalls == 1) network = "B"
                ResolvedAwgEndpoint(
                    if (resolvedNetwork == "A") "203.0.113.8:51820" else "203.0.113.9:51820",
                    resolvedNetwork,
                )
            },
            native = native,
            generationIsCurrent = { true },
            tunnel = tunnel,
            protectSocket = { true },
            onStarted = { _, _, _ -> AwgSessionObserver {} },
        )

        val session = engine.start(command(23), profile())

        assertEquals(2, resolverCalls)
        assertEquals(
            listOf(
                "resolve:A",
                "network.prepare:B",
                "resolve:B",
                "network.prepare:B",
                "tun.establish-detach:B",
            ),
            events.take(5),
        )
        session.close()
    }

    @Test
    fun `every native and protect boundary rolls back owned resources`() = runBlocking {
        listOf("turnOn", "socketV4", "socketV6").forEach { failAt ->
            val events = mutableListOf<String>()
            val native = FakeNative(events, failAt = failAt)
            val tunnel = FakeTunnel(events)

            checkNotNull(
                runCatching {
                    engine(events, native, tunnel).start(command(14), profile())
                }.exceptionOrNull(),
            )

            assertTrue(events.last() == "tun.close:14")
            if (failAt == "turnOn") {
                assertTrue("raw.close:73" in events)
                assertEquals(0, native.turnOffCalls)
            } else {
                assertEquals(1, native.turnOffCalls)
                assertEquals(0, tunnel.rawCloseCalls)
            }
        }

        listOf(9, 10).forEach { rejectedFd ->
            val events = mutableListOf<String>()
            val native = FakeNative(events)
            val tunnel = FakeTunnel(events)
            checkNotNull(
                runCatching {
                    engine(events, native, tunnel, protect = { it != rejectedFd })
                        .start(command(15), profile())
                }.exceptionOrNull(),
            )
            assertEquals(listOf("native.off:17", "tun.close:15"), events.takeLast(2))
        }

        val events = mutableListOf<String>()
        val native = FakeNative(events)
        val tunnel = FakeTunnel(events)
        checkNotNull(
            runCatching {
                engine(events, native, tunnel, protect = { error("protect internals") })
                    .start(command(15), profile())
            }.exceptionOrNull(),
        )
        assertEquals(listOf("native.off:17", "tun.close:15"), events.takeLast(2))
    }

    @Test
    fun `stale generation at each ownership boundary never leaks resources`() = runBlocking {
        for (allowedChecks in 0..8) {
            val events = mutableListOf<String>()
            var checks = 0
            val native = FakeNative(events)
            val tunnel = FakeTunnel(events)

            checkNotNull(
                runCatching {
                    engine(
                        events,
                        native,
                        tunnel,
                        generationCurrent = { checks++ < allowedChecks },
                    ).start(command(16), profile())
                }.exceptionOrNull(),
            )

            when (allowedChecks) {
                0 -> {
                    assertFalse(events.any { it.startsWith("network.prepare") })
                    assertFalse(events.any { it.startsWith("tun.close") })
                    assertEquals(0, native.turnOffCalls)
                    assertEquals(0, tunnel.rawCloseCalls)
                }
                1 -> {
                    assertTrue(events.last() == "tun.close:16")
                    assertEquals(0, native.turnOffCalls)
                    assertEquals(0, tunnel.rawCloseCalls)
                }
                2 -> {
                    assertEquals(listOf("raw.close:73", "tun.close:16"), events.takeLast(2))
                    assertEquals(0, native.turnOffCalls)
                    assertEquals(1, tunnel.rawCloseCalls)
                }
                else -> {
                    assertTrue(events.last() == "tun.close:16")
                    assertEquals(1, native.turnOffCalls)
                    assertEquals(0, tunnel.rawCloseCalls)
                }
            }
        }
    }

    @Test
    fun `cancellation rolls back and remains cancellation`() = runBlocking {
        val events = mutableListOf<String>()
        val native = FakeNative(events)
        val tunnel = FakeTunnel(events)
        val engine = AwgEngine(
            policyProvider = {
                TunRuntimePolicy(true, true, true, PerAppMode.OFF, emptySet())
            },
            adapter = AwgConfigAdapter(),
            endpointResolver = AwgEndpointResolver { _, _ -> throw CancellationException("stop") },
            native = native,
            generationIsCurrent = { true },
            tunnel = tunnel,
            protectSocket = { true },
            onStarted = { _, _, _ -> AwgSessionObserver {} },
        )

        val error = runCatching { engine.start(command(17), profile()) }.exceptionOrNull()

        assertTrue(error is CancellationException)
        assertEquals(emptyList<String>(), events)
    }

    @Test
    fun `turnOff exception never prevents controller close`() = runBlocking {
        val rollbackEvents = mutableListOf<String>()
        val rollbackNative = FakeNative(rollbackEvents, turnOffThrows = true)
        val rollbackTunnel = FakeTunnel(rollbackEvents)
        checkNotNull(
            runCatching {
                engine(
                    rollbackEvents,
                    rollbackNative,
                    rollbackTunnel,
                    protect = { false },
                ).start(command(20), profile())
            }.exceptionOrNull(),
        )
        assertEquals("tun.close:20", rollbackEvents.last())

        val closeEvents = mutableListOf<String>()
        val closeNative = FakeNative(closeEvents, turnOffThrows = true)
        val closeTunnel = FakeTunnel(closeEvents)
        val session = engine(closeEvents, closeNative, closeTunnel).start(command(21), profile())
        checkNotNull(runCatching { session.close() }.exceptionOrNull())
        assertEquals("tun.close:21", closeEvents.last())
    }

    @Test
    fun `closing generation n cannot close generation n plus one`() = runBlocking {
        val events = mutableListOf<String>()
        val native = FakeNative(events, handles = ArrayDeque(listOf(21, 22)))
        val tunnel = FakeTunnel(events)
        val engine = engine(events, native, tunnel)
        val oldSession = engine.start(command(18), profile())
        val newSession = engine.start(command(19), profile())

        oldSession.close()

        assertTrue("native.off:21" in events)
        assertTrue("native.off:22" !in events)
        assertTrue("tun.close:18" in events)
        assertTrue("tun.close:19" !in events)
        newSession.close()
    }

    private fun engine(
        events: MutableList<String>,
        native: FakeNative,
        tunnel: FakeTunnel,
        protect: (Int) -> Boolean = { events += "protect:$it"; true },
        generationCurrent: (Long) -> Boolean = { true },
        handshakeTimeoutMs: Long = 12_000L,
    ) = AwgEngine(
        policyProvider = {
            TunRuntimePolicy(true, true, true, PerAppMode.OFF, emptySet())
        },
        adapter = object : AwgProfileAdapter {
            private val delegate = AwgConfigAdapter()
            override fun prepare(profile: Profile, policy: TunRuntimePolicy): PreparedAwgProfile {
                events += "validate"
                return delegate.prepare(profile, policy)
            }

            override fun userspaceConfig(
                prepared: PreparedAwgProfile,
                resolvedEndpoint: String,
            ): String = delegate.userspaceConfig(prepared, resolvedEndpoint)
        },
        endpointResolver = AwgEndpointResolver { _, _ ->
            events += "resolve"
            ResolvedAwgEndpoint("203.0.113.8:51820", "network")
        },
        native = native,
        generationIsCurrent = generationCurrent,
        tunnel = tunnel,
        protectSocket = protect,
        onStarted = { generation, _, _ ->
            events += "started:$generation"
            AwgSessionObserver { events += "observer.close" }
        },
        handshakeTimeoutMs = handshakeTimeoutMs,
    )

    private fun runtime(onStart: () -> Unit) = ConnectionRuntime { command ->
        onStart()
        object : ConnectionSession {
            override val profileId = (command.desired as DesiredConnection.Running).profileId
            override suspend fun close() = Unit
        }
    }

    private fun awgRuntime(onStart: () -> Unit) = AwgRuntime { command, profile ->
        onStart()
        object : ConnectionSession {
            override val profileId = profile.id
            override suspend fun close() = Unit
        }
    }

    private fun command(generation: Long) =
        ConnectionCommand(generation, DesiredConnection.Running("awg"))

    private fun profile(awg: AmneziaParams? = AmneziaParams(jc = 4)) = Profile(
        id = "awg",
        name = "AWG",
        outbound = Outbound.WireGuard(
            server = "vpn.example",
            serverPort = 51820,
            privateKey = key(1),
            peerPublicKey = key(2),
            localAddresses = listOf("10.0.0.2/32"),
            allowedIps = listOf("0.0.0.0/0"),
            awg = awg,
        ),
    )

    private fun key(fill: Int): String =
        Base64.getEncoder().encodeToString(ByteArray(32) { fill.toByte() })

    private class FakeTunnel(private val events: MutableList<String>) : AwgTunnelSession {
        var rawCloseCalls = 0

        override fun prepareNetwork(
            generation: Long,
            policy: TunRuntimePolicy,
            networkToken: Any,
        ): Boolean {
            events += "network.prepare:$generation"
            return true
        }

        override fun establishAndDetach(generation: Long, spec: AwgTunSpec): Int {
            events += "tun.establish-detach:$generation"
            return 73
        }

        override fun closeDetachedFd(generation: Long, fd: Int) {
            rawCloseCalls++
            events += "raw.close:$fd"
        }

        override fun close(generation: Long) {
            events += "tun.close:$generation"
        }

    }

    private class FakeNative(
        private val events: MutableList<String>,
        private val turnOnResult: Int = 17,
        private val failAt: String? = null,
        private val handles: ArrayDeque<Int> = ArrayDeque(),
        private val turnOffThrows: Boolean = false,
    ) : AmneziaWgNative {
        var turnOffCalls = 0

        override fun turnOn(interfaceName: String, tunFd: Int, settings: String): Int {
            events += "native.on:$interfaceName:$tunFd"
            if (failAt == "turnOn") error("native internals")
            return if (handles.isEmpty()) turnOnResult else handles.removeFirst()
        }

        override fun turnOff(handle: Int) {
            turnOffCalls++
            events += "native.off:$handle"
            if (turnOffThrows) error("native turnOff internals")
        }

        override fun getSocketV4(handle: Int): Int {
            events += "native.v4:9"
            if (failAt == "socketV4") error("native internals")
            return 9
        }

        override fun getSocketV6(handle: Int): Int {
            events += "native.v6:10"
            if (failAt == "socketV6") error("native internals")
            return 10
        }

        /**
         * A peer that HAS handshaken, which is the normal case every other test assumes.
         * `handshakeSeconds = 0` reproduces the silent dead tunnel: UAPI reports the peer
         * as never having answered.
         */
        var handshakeSeconds: Long = 1_700_000_000L

        override fun getConfig(handle: Int): String =
            "public_key=abcd\nlast_handshake_time_sec=$handshakeSeconds\n"
        override fun version(): String = "test"
    }
}

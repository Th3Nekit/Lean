package com.th3web.lean.core.engine

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import com.th3web.lean.core.connection.ConnectionCommand
import com.th3web.lean.core.connection.DesiredConnection
import com.th3web.lean.data.model.AmneziaParams
import com.th3web.lean.data.model.Outbound
import com.th3web.lean.data.model.Profile

class NekoEngineTest {
    @Test
    fun `standard startup follows native order and closes in native before tun order`() = runBlocking {
        val events = mutableListOf<String>()
        val box = FakeBox(events)
        val engine = NekoEngine(
            configProvider = { EngineConfig("p1", listOf(standardProfile()), "{}") },
            core = FakeCore(box, events),
            generationIsCurrent = { true },
            tunnel = FakeTunnel(events),
        )

        val session = engine.start(command(7))
        assertEquals(
            listOf("tun.begin:7", "core.new", "box.main", "box.stats:proxy", "box.start"),
            events,
        )

        session.close()
        session.close()
        assertEquals("box.close", events[5])
        assertEquals("tun.close:7", events[6])
        assertEquals(7, events.size)
    }

    @Test
    fun `session awaits observer shutdown before closing native box`() = runBlocking {
        val events = mutableListOf<String>()
        val observerClosing = CompletableDeferred<Unit>()
        val allowObserverClose = CompletableDeferred<Unit>()
        val engine = NekoEngine(
            configProvider = { EngineConfig("p1", listOf(standardProfile()), "{}") },
            core = FakeCore(FakeBox(events), events),
            generationIsCurrent = { true },
            tunnel = FakeTunnel(events),
            onStarted = { _, _, _ ->
                NekoSessionObserver {
                    events += "observer.close.start"
                    observerClosing.complete(Unit)
                    allowObserverClose.await()
                    events += "observer.close.end"
                }
            },
        )
        val session = engine.start(command(7))

        val closing = async { session.close() }
        observerClosing.await()

        assertTrue("native box closed while observer was active", "box.close" !in events)
        allowObserverClose.complete(Unit)
        closing.await()
        assertEquals(
            listOf("observer.close.start", "observer.close.end", "box.close", "tun.close:7"),
            events.takeLast(4),
        )
    }

    @Test
    fun `failure rolls back partial native instance and tunnel`() = runBlocking {
        val events = mutableListOf<String>()
        val box = FakeBox(events, failAt = "box.start")
        val engine = NekoEngine(
            configProvider = { EngineConfig("p1", listOf(standardProfile()), "{}") },
            core = FakeCore(box, events),
            generationIsCurrent = { true },
            tunnel = FakeTunnel(events),
        )

        checkNotNull(runCatching { engine.start(command(8)) }.exceptionOrNull())
        assertEquals(
            listOf(
                "tun.begin:8",
                "core.new",
                "box.main",
                "box.stats:proxy",
                "box.start",
                "box.close",
                "tun.close:8",
            ),
            events,
        )
    }

    @Test
    fun `stale generation closes immediately after native boundary`() = runBlocking {
        val events = mutableListOf<String>()
        var checks = 0
        val engine = NekoEngine(
            configProvider = { EngineConfig("p1", listOf(standardProfile()), "{}") },
            core = FakeCore(FakeBox(events), events),
            // The COUNT is what places the staleness, so it tracks the number of
            // generation checks on the way in — one more since the wait for the native
            // layer to go quiet gained its own check. The scenario is unchanged: stale
            // immediately after the box exists, so the box must be closed.
            generationIsCurrent = { ++checks < 5 },
            tunnel = FakeTunnel(events),
        )

        checkNotNull(runCatching { engine.start(command(9)) }.exceptionOrNull())
        assertEquals(listOf("tun.begin:9", "core.new", "box.close", "tun.close:9"), events)
    }

    @Test
    fun `awg is blocked before config generation or native creation`() = runBlocking {
        var configCalled = false
        var coreCalled = false
        val awg = standardProfile().copy(
            outbound = (standardProfile().outbound as Outbound.WireGuard).copy(awg = AmneziaParams(jc = 4)),
        )
        val engine = NekoEngine(
            configProvider = {
                configCalled = true
                EngineConfig("awg", listOf(awg), "{}")
            },
            core = object : NekoCore {
                override fun newInstance(config: String): NekoBox {
                    coreCalled = true
                    error("must not reach native")
                }
            },
            generationIsCurrent = { true },
            tunnel = FakeTunnel(mutableListOf()),
            profileProvider = { listOf(awg) },
        )

        val error = runCatching { engine.start(command(10, "awg")) }.exceptionOrNull()
        checkNotNull(error)
        assertTrue(error.message.orEmpty().contains("AmneziaWG"))
        assertTrue(error.message.orEmpty().contains("отдель"))
        assertTrue(!configCalled)
        assertTrue(!coreCalled)
    }

    private fun command(generation: Long, id: String = "p1") =
        ConnectionCommand(generation, DesiredConnection.Running(id))

    private fun standardProfile() = Profile(
        id = "p1",
        name = "WG",
        outbound = Outbound.WireGuard(
            server = "1.2.3.4",
            serverPort = 51820,
            privateKey = "private",
            peerPublicKey = "public",
            localAddresses = listOf("10.0.0.2/32"),
        ),
    )

    private class FakeCore(
        private val box: NekoBox,
        private val events: MutableList<String>,
    ) : NekoCore {
        override fun newInstance(config: String): NekoBox {
            events += "core.new"
            return box
        }
    }

    private class FakeBox(
        private val events: MutableList<String>,
        private val failAt: String? = null,
    ) : NekoBox {
        private fun event(value: String) {
            events += value
            if (failAt == value) error("failed at $value")
        }

        override fun setAsMain() = event("box.main")
        override fun setV2rayStats(tags: String) = event("box.stats:$tags")
        override fun start() = event("box.start")
        override fun close() = event("box.close")
        override fun queryStats(tag: String, direction: String): Long = 0
        override fun selectOutbound(tag: String): Boolean {
            event("box.select:$tag")
            return true
        }
    }

    private class FakeTunnel(private val events: MutableList<String>) : NekoTunnelSession {
        override fun begin(generation: Long, config: EngineConfig) {
            events += "tun.begin:$generation"
        }

        override fun close(generation: Long) {
            events += "tun.close:$generation"
        }
    }
}

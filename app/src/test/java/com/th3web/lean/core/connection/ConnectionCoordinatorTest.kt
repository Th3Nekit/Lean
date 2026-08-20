package com.th3web.lean.core.connection

import java.util.Collections
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionCoordinatorTest {

    @Test
    fun submittedRequestsReceiveStrictlyIncreasingGenerations() = runBlocking {
        val fixture = Fixture()

        val first = fixture.coordinator.submit(DesiredConnection.Stopped)
        val second = fixture.coordinator.submit(DesiredConnection.Running("profile-a"))
        val third = fixture.coordinator.submit(DesiredConnection.Stopped)

        assertEquals(listOf(1L, 2L, 3L), listOf(first.generation, second.generation, third.generation))
        fixture.coordinator.awaitIdle()
        fixture.coordinator.shutdown()
    }

    @Test
    fun startThenStopPublishesCurrentStateAndClosesSession() = runBlocking {
        val fixture = Fixture()

        val start = fixture.coordinator.submit(DesiredConnection.Running("profile-a"))
        fixture.coordinator.awaitIdle()
        val session = fixture.runtime.sessions.single()

        assertEquals(
            listOf(Publication(start.generation, ConnectionState.Connected("profile-a"))),
            fixture.publications,
        )
        assertFalse(session.closed.isCompleted)

        val stop = fixture.coordinator.submit(DesiredConnection.Stopped)
        fixture.coordinator.awaitIdle()

        assertTrue(session.closed.isCompleted)
        assertEquals(
            Publication(stop.generation, ConnectionState.Disconnected),
            fixture.publications.last(),
        )
        fixture.coordinator.shutdown()
    }

    @Test
    fun rapidStopThenStartDoesNotPublishStaleDisconnectedState() = runBlocking {
        val fixture = Fixture()
        fixture.coordinator.submit(DesiredConnection.Running("profile-a"))
        fixture.coordinator.awaitIdle()
        val firstSession = fixture.runtime.sessions.single()
        firstSession.closeGate = CompletableDeferred()

        val stop = fixture.coordinator.submit(DesiredConnection.Stopped)
        firstSession.closeStarted.await()
        val restart = fixture.coordinator.submit(DesiredConnection.Running("profile-b"))
        firstSession.closeGate?.complete(Unit)
        fixture.coordinator.awaitIdle()

        assertTrue(firstSession.closed.isCompleted)
        assertFalse(fixture.publications.any { it.generation == stop.generation })
        assertEquals(
            Publication(restart.generation, ConnectionState.Connected("profile-b")),
            fixture.publications.last(),
        )
        fixture.coordinator.shutdown()
    }

    @Test
    fun rapidProfileSwitchClosesOldSessionBeforeStartingNewOne() = runBlocking {
        val fixture = Fixture()
        fixture.coordinator.submit(DesiredConnection.Running("profile-a"))
        fixture.coordinator.awaitIdle()
        val firstSession = fixture.runtime.sessions.single()

        val switch = fixture.coordinator.submit(DesiredConnection.Running("profile-b"))
        fixture.coordinator.awaitIdle()

        assertTrue(firstSession.closed.isCompleted)
        assertEquals(listOf("start:profile-a", "close:profile-a", "start:profile-b"), fixture.runtime.operations)
        assertEquals(
            Publication(switch.generation, ConnectionState.Connected("profile-b")),
            fixture.publications.last(),
        )
        fixture.coordinator.shutdown()
    }

    @Test
    fun staleStartCompletionClosesItsResourcesAndCannotPublish() = runBlocking {
        val fixture = Fixture()
        val firstStartGate = CompletableDeferred<Unit>()
        fixture.runtime.startGates["profile-a"] = firstStartGate
        val firstStartSignal = fixture.runtime.signalFor("profile-a")

        val stale = fixture.coordinator.submit(DesiredConnection.Running("profile-a"))
        firstStartSignal.await()
        val current = fixture.coordinator.submit(DesiredConnection.Running("profile-b"))
        firstStartGate.complete(Unit)
        fixture.coordinator.awaitIdle()

        val staleSession = fixture.runtime.sessions.first { it.profileId == "profile-a" }
        assertTrue(staleSession.closed.isCompleted)
        assertFalse(fixture.publications.any { it.generation == stale.generation })
        assertEquals(
            Publication(current.generation, ConnectionState.Connected("profile-b")),
            fixture.publications.single(),
        )
        fixture.coordinator.shutdown()
    }

    @Test
    fun currentStartFailurePublishesErrorWithoutKillingCoordinator() = runBlocking {
        val fixture = Fixture()
        val failure = IllegalStateException("native start failed")
        fixture.runtime.startFailures["profile-a"] = failure

        val failed = fixture.coordinator.submit(DesiredConnection.Running("profile-a"))
        fixture.coordinator.awaitIdle()

        assertEquals(listOf(failure), fixture.failures)
        assertEquals(
            Publication(failed.generation, ConnectionState.Error("native start failed")),
            fixture.publications.single(),
        )

        val recovered = fixture.coordinator.submit(DesiredConnection.Running("profile-b"))
        fixture.coordinator.awaitIdle()

        assertEquals(
            Publication(recovered.generation, ConnectionState.Connected("profile-b")),
            fixture.publications.last(),
        )
        fixture.coordinator.shutdown()
    }

    @Test
    fun closeFailureDoesNotBlockTheNextDesiredConnection() = runBlocking {
        val fixture = Fixture()
        fixture.coordinator.submit(DesiredConnection.Running("profile-a"))
        fixture.coordinator.awaitIdle()
        fixture.runtime.sessions.single().closeFailure = IllegalStateException("native close failed")

        val switched = fixture.coordinator.submit(DesiredConnection.Running("profile-b"))
        fixture.coordinator.awaitIdle()

        assertEquals(
            Publication(switched.generation, ConnectionState.Connected("profile-b")),
            fixture.publications.last(),
        )
        fixture.coordinator.shutdown()
    }

    private class Fixture {
        val runtime = FakeRuntime()
        val publications = Collections.synchronizedList(mutableListOf<Publication>())
        val failures = Collections.synchronizedList(mutableListOf<Throwable>())
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val coordinator = ConnectionCoordinator(
            scope = scope,
            runtime = runtime,
            publisher = ConnectionStatePublisher { command, state ->
                publications += Publication(command.generation, state)
            },
            onFailure = failures::add,
        )
    }

    private data class Publication(
        val generation: Long,
        val state: ConnectionState,
    )

    private class FakeRuntime : ConnectionRuntime {
        val operations = Collections.synchronizedList(mutableListOf<String>())
        val sessions = Collections.synchronizedList(mutableListOf<FakeSession>())
        val startGates = Collections.synchronizedMap(mutableMapOf<String, CompletableDeferred<Unit>>())
        val startSignals = Collections.synchronizedMap(mutableMapOf<String, CompletableDeferred<Unit>>())
        val startFailures = Collections.synchronizedMap(mutableMapOf<String, Throwable>())

        fun signalFor(profileId: String): CompletableDeferred<Unit> =
            startSignals.getOrPut(profileId) { CompletableDeferred() }

        override suspend fun start(command: ConnectionCommand): ConnectionSession {
            val profileId = (command.desired as DesiredConnection.Running).profileId
            operations += "start:$profileId"
            signalFor(profileId).complete(Unit)
            startGates[profileId]?.await()
            startFailures[profileId]?.let { throw it }
            return FakeSession(profileId, operations).also(sessions::add)
        }
    }

    private class FakeSession(
        override val profileId: String,
        private val operations: MutableList<String>,
    ) : ConnectionSession {
        val closeStarted = CompletableDeferred<Unit>()
        val closed = CompletableDeferred<Unit>()
        var closeGate: CompletableDeferred<Unit>? = null
        var closeFailure: Throwable? = null

        override suspend fun close() {
            if (closed.isCompleted) return
            closeStarted.complete(Unit)
            closeGate?.await()
            if (closed.complete(Unit)) {
                operations += "close:$profileId"
                closeFailure?.let { throw it }
            }
        }
    }
}

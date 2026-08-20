package com.th3web.lean.core.connection

import org.junit.Assert.assertEquals
import org.junit.Test
import com.th3web.lean.core.VpnState

class ServiceStatePublisherTest {

    @Test
    fun staleGenerationCannotChangeStateNotificationOrForeground() {
        val target = FakeTarget()
        val publisher = ServiceStatePublisher(target)
        val stale = ConnectionCommand(1, DesiredConnection.Running("profile-a"))
        val current = ConnectionCommand(2, DesiredConnection.Stopped)

        publisher.onCommandSubmitted(stale)
        publisher.onCommandSubmitted(current)
        val operationsBeforeStaleCompletion = target.operations.toList()
        publisher.publish(stale, ConnectionState.Connected("profile-a"))

        assertEquals(VpnState.Stopping, target.currentState)
        assertEquals(operationsBeforeStaleCompletion, target.operations)
    }

    @Test
    fun currentConnectionPublishesConnectedStateAndNotification() {
        val target = FakeTarget()
        val publisher = ServiceStatePublisher(target)
        val command = ConnectionCommand(1, DesiredConnection.Running("profile-a"))

        publisher.onCommandSubmitted(command)
        publisher.publish(command, ConnectionState.Connected("profile-a"))

        assertEquals(VpnState.Connected("profile-a"), target.currentState)
        assertEquals(
            listOf("state:Connecting", "notification:connecting", "state:Connected(profileId=profile-a)", "notification:connected:profile-a"),
            target.operations,
        )
    }

    @Test
    fun currentDisconnectClearsRuntimeStateAndRemovesForeground() {
        val target = FakeTarget()
        val publisher = ServiceStatePublisher(target)
        val command = ConnectionCommand(1, DesiredConnection.Stopped)

        publisher.onCommandSubmitted(command)
        publisher.publish(command, ConnectionState.Disconnected)

        assertEquals(VpnState.Disconnected, target.currentState)
        assertEquals(
            listOf("state:Stopping", "traffic:clear", "groups:clear", "state:Disconnected", "foreground:remove"),
            target.operations,
        )
    }

    @Test
    fun currentFailureKeepsItsReasonAndPerformsTheSameTeardown() {
        val target = FakeTarget()
        val publisher = ServiceStatePublisher(target)
        val command = ConnectionCommand(1, DesiredConnection.Running("profile-a"))

        publisher.onCommandSubmitted(command)
        publisher.publish(command, ConnectionState.Error("native start failed"))

        assertEquals(VpnState.Error("native start failed"), target.currentState)
        assertEquals(
            listOf(
                "state:Connecting",
                "notification:connecting",
                "traffic:clear",
                "groups:clear",
                "state:Error(message=native start failed)",
                "foreground:remove",
            ),
            target.operations,
        )
    }

    @Test
    fun destroyDoesNotOverwritePublishedFailure() {
        val target = FakeTarget()
        val publisher = ServiceStatePublisher(target)
        val command = ConnectionCommand(1, DesiredConnection.Running("profile-a"))

        publisher.onCommandSubmitted(command)
        publisher.publish(command, ConnectionState.Error("native start failed"))
        publisher.onDestroyed()

        assertEquals(VpnState.Error("native start failed"), target.currentState)
        assertEquals(1, target.operations.count { it == "foreground:remove" })
        assertEquals(1, target.operations.count { it == "traffic:clear" })
        assertEquals(1, target.operations.count { it == "groups:clear" })
    }

    /**
     * A failed connect publishes Error and then tears the service down; the teardown's
     * own Stopped command must not repaint that Error away. The user's report was
     * literally "ошибка появляется на миллисекунду и пропадает", which left no trace of
     * what actually failed. A genuine new connect attempt does clear it.
     */
    @Test
    fun publishedFailureSurvivesTheTeardownItTriggers() {
        val target = FakeTarget()
        val publisher = ServiceStatePublisher(target)
        val failed = ConnectionCommand(1, DesiredConnection.Running("profile-a"))

        publisher.onCommandSubmitted(failed)
        publisher.publish(failed, ConnectionState.Error("native start failed"))

        val teardown = ConnectionCommand(2, DesiredConnection.Stopped)
        publisher.onCommandSubmitted(teardown)
        publisher.publish(teardown, ConnectionState.Disconnected)

        assertEquals(VpnState.Error("native start failed"), target.currentState)

        val retry = ConnectionCommand(3, DesiredConnection.Running("profile-a"))
        publisher.onCommandSubmitted(retry)
        assertEquals(VpnState.Connecting, target.currentState)
    }

    @Test
    fun destroyIsSynchronousTerminalAndIdempotent() {
        val target = FakeTarget()
        val publisher = ServiceStatePublisher(target)
        val command = ConnectionCommand(1, DesiredConnection.Running("profile-a"))
        publisher.onCommandSubmitted(command)
        publisher.publish(command, ConnectionState.Connected("profile-a"))

        publisher.onDestroyed()
        publisher.onDestroyed()
        publisher.publish(command, ConnectionState.Connected("profile-a"))

        assertEquals(VpnState.Disconnected, target.currentState)
        assertEquals(1, target.operations.count { it == "foreground:remove" })
        assertEquals(1, target.operations.count { it == "traffic:clear" })
        assertEquals(1, target.operations.count { it == "groups:clear" })
    }

    private class FakeTarget : ServiceStateTarget {
        var currentState: VpnState = VpnState.Disconnected
        val operations = mutableListOf<String>()

        override fun setState(state: VpnState) {
            currentState = state
            operations += "state:$state"
        }

        override fun clearTraffic() {
            operations += "traffic:clear"
        }

        override fun clearGroups() {
            operations += "groups:clear"
        }

        override fun showConnectingNotification() {
            operations += "notification:connecting"
        }

        override fun showConnectedNotification(profileId: String) {
            operations += "notification:connected:$profileId"
        }

        override fun removeForeground() {
            operations += "foreground:remove"
        }
    }
}

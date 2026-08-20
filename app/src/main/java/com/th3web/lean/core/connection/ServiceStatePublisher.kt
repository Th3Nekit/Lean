package com.th3web.lean.core.connection

import com.th3web.lean.core.VpnState

interface ServiceStateTarget {
    fun setState(state: VpnState)
    fun clearTraffic()
    fun clearGroups()
    fun showConnectingNotification()
    fun showConnectedNotification(profileId: String)
    fun removeForeground()
}

class ServiceStatePublisher(
    private val target: ServiceStateTarget,
) : ConnectionStatePublisher {
    private val lock = Any()
    private var currentGeneration = 0L
    private var destroyed = false
    private var terminalStatePublished = false

    /**
     * True while the last terminal state shown to the user is an [VpnState.Error].
     *
     * A failed connect publishes Error and then immediately tears the service down, and
     * that teardown publishes its own Stopping/Disconnected. Without this flag the error
     * was overwritten within milliseconds, exactly the reported "ошибка появляется на
     * миллисекунду и пропадает", which left both the user and the log with no clue what
     * actually failed. The error therefore survives until the user genuinely asks to
     * connect again.
     */
    private var lastTerminalWasError = false

    override fun onCommandSubmitted(command: ConnectionCommand) {
        synchronized(lock) {
            if (destroyed || command.generation <= currentGeneration) return
            currentGeneration = command.generation
            when (command.desired) {
                DesiredConnection.Stopped -> {
                    if (lastTerminalWasError) return
                    terminalStatePublished = false
                    target.setState(VpnState.Stopping)
                }
                is DesiredConnection.Running -> {
                    lastTerminalWasError = false
                    terminalStatePublished = false
                    target.setState(VpnState.Connecting)
                    target.showConnectingNotification()
                }
            }
        }
    }

    override fun publish(command: ConnectionCommand, state: ConnectionState) {
        synchronized(lock) {
            if (destroyed || command.generation != currentGeneration) return
            when (state) {
                is ConnectionState.Connected -> {
                    target.setState(VpnState.Connected(state.profileId))
                    target.showConnectedNotification(state.profileId)
                }

                ConnectionState.Disconnected -> finish(VpnState.Disconnected)
                is ConnectionState.Error -> {
                    lastTerminalWasError = true
                    finish(VpnState.Error(state.message))
                }
            }
        }
    }

    fun onDestroyed() {
        synchronized(lock) {
            if (destroyed) return
            destroyed = true
            if (!terminalStatePublished) finish(VpnState.Disconnected)
        }
    }

    private fun finish(state: VpnState) {
        terminalStatePublished = true
        target.clearTraffic()
        target.clearGroups()
        // Keep a surfaced Error on screen: the Disconnected that follows it is only the
        // teardown of that same failure, not new information (see [lastTerminalWasError]).
        if (!(lastTerminalWasError && state is VpnState.Disconnected)) {
            target.setState(state)
        }
        target.removeForeground()
    }
}

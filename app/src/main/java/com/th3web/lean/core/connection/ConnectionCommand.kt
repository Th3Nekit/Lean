package com.th3web.lean.core.connection

sealed interface DesiredConnection {
    data object Stopped : DesiredConnection

    data class Running(
        val profileId: String,
        /**
         * Rebuild the tunnel even when this profile is already the active one.
         *
         * Reconnecting to the profile you are on is normally a no-op, so that tapping the
         * server you are already using does not drop the tunnel for no reason. But when
         * the profile's own configuration just changed, editing AmneziaWG obfuscation on
         * the live server: the caller means "apply this now". Without the flag the
         * coordinator re-publishes Connected while the previous session keeps running, so
         * the UI claims settings are in effect that never reached the tunnel.
         */
        val restart: Boolean = false,
    ) : DesiredConnection
}

data class ConnectionCommand(
    val generation: Long,
    val desired: DesiredConnection,
)

sealed interface ConnectionState {
    data object Disconnected : ConnectionState

    data class Connected(
        val profileId: String,
    ) : ConnectionState

    data class Error(
        val message: String,
    ) : ConnectionState
}

interface ConnectionSession {
    val profileId: String

    suspend fun close()
}

fun interface ConnectionRuntime {
    suspend fun start(command: ConnectionCommand): ConnectionSession
}

fun interface ConnectionStatePublisher {
    fun onCommandSubmitted(command: ConnectionCommand) {}

    fun publish(command: ConnectionCommand, state: ConnectionState)
}

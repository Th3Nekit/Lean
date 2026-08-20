package com.th3web.lean.core.connection

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

class ConnectionCoordinator(
    scope: CoroutineScope,
    private val runtime: ConnectionRuntime,
    private val publisher: ConnectionStatePublisher,
    private val onFailure: (Throwable) -> Unit = {},
) {
    private val commitLock = Any()
    private val nextGeneration = AtomicLong()
    private val latest = AtomicReference(ConnectionCommand(0, DesiredConnection.Stopped))
    private val processedGeneration = AtomicLong()
    private val wakeups = Channel<Unit>(Channel.CONFLATED)
    private val idleWaiters = ConcurrentLinkedQueue<IdleWaiter>()
    private val shuttingDown = AtomicBoolean()
    private val actor: Job = scope.launch {
        for (ignored in wakeups) {
            // The actor is the only consumer of commands: if it ever dies, every later
            // command is accepted but never reconciled, so the UI hangs in Подключение /
            // Отключение forever with nothing in the log. Anything thrown by reconcile()
            // (including a native failure surfacing as an exception) must therefore be
            // reported and swallowed here, never allowed to terminate the loop.
            try {
                reconcile()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Throwable) {
                runCatching { onFailure(error) }
                val command = latest.get()
                publishIfCurrent(
                    command,
                    ConnectionState.Error(
                        error.message?.takeIf(String::isNotBlank)
                            ?: error.javaClass.simpleName,
                    ),
                )
                processedGeneration.set(command.generation)
                completeIdleWaiters()
            }
        }
    }

    fun submit(desired: DesiredConnection): ConnectionCommand {
        check(!shuttingDown.get()) { "ConnectionCoordinator is shutting down" }
        return submitInternal(desired)
    }

    suspend fun awaitIdle() {
        val target = latest.get().generation
        if (processedGeneration.get() >= target) return

        val completion = CompletableDeferred<Unit>()
        val waiter = IdleWaiter(target, completion)
        idleWaiters += waiter
        if (processedGeneration.get() >= target && idleWaiters.remove(waiter)) {
            completion.complete(Unit)
        } else {
            wakeups.trySend(Unit)
        }
        completion.await()
    }

    suspend fun shutdown() {
        if (!shuttingDown.compareAndSet(false, true)) {
            actor.join()
            return
        }

        submitInternal(DesiredConnection.Stopped)
        awaitIdle()
        wakeups.close()
        actor.join()
    }

    private fun submitInternal(desired: DesiredConnection): ConnectionCommand =
        synchronized(commitLock) {
            ConnectionCommand(nextGeneration.incrementAndGet(), desired).also {
                latest.set(it)
                publisher.onCommandSubmitted(it)
                check(wakeups.trySend(Unit).isSuccess) { "ConnectionCoordinator is closed" }
            }
        }

    private suspend fun reconcile() {
        var active = activeSession
        while (true) {
            val command = latest.get()
            if (command.generation <= processedGeneration.get()) break

            when (val desired = command.desired) {
                DesiredConnection.Stopped -> {
                    active?.closeQuietly()
                    active = null
                    publishIfCurrent(command, ConnectionState.Disconnected)
                }

                is DesiredConnection.Running -> {
                    if (active?.profileId == desired.profileId && !desired.restart) {
                        publishIfCurrent(command, ConnectionState.Connected(desired.profileId))
                    } else {
                        active?.closeQuietly()
                        active = null
                        val startResult = runCatching { runtime.start(command) }
                        val started = startResult.getOrNull()
                        if (started != null && isCurrent(command)) {
                            active = started
                            publishIfCurrent(command, ConnectionState.Connected(desired.profileId))
                        } else if (started != null) {
                            started.closeQuietly()
                        } else {
                            val failure = startResult.exceptionOrNull()
                            if (failure != null && isCurrent(command)) {
                                runCatching { onFailure(failure) }
                            }
                            publishIfCurrent(
                                command,
                                ConnectionState.Error(
                                    failure?.message?.takeIf(String::isNotBlank)
                                        ?: failure?.javaClass?.simpleName
                                        ?: "connection failed",
                                ),
                            )
                        }
                    }
                }
            }

            activeSession = active
            processedGeneration.set(command.generation)
            completeIdleWaiters()
        }
    }

    fun isCurrentGeneration(generation: Long): Boolean =
        latest.get().generation == generation

    fun <T> commitIfCurrentGeneration(generation: Long, block: () -> T): T? =
        synchronized(commitLock) {
            if (latest.get().generation == generation) block() else null
        }

    private fun isCurrent(command: ConnectionCommand): Boolean =
        isCurrentGeneration(command.generation)

    private fun publishIfCurrent(command: ConnectionCommand, state: ConnectionState) {
        synchronized(commitLock) {
            if (latest.get().generation == command.generation) {
                publisher.publish(command, state)
            }
        }
    }

    private suspend fun ConnectionSession.closeQuietly() {
        runCatching { close() }
            .onFailure { failure -> runCatching { onFailure(failure) } }
    }

    private fun completeIdleWaiters() {
        val processed = processedGeneration.get()
        val completed = mutableListOf<IdleWaiter>()
        idleWaiters.forEach {
            if (it.generation <= processed && idleWaiters.remove(it)) completed += it
        }
        completed.map { it.completion }.onEach { it.complete(Unit) }
    }

    private data class IdleWaiter(
        val generation: Long,
        val completion: CompletableDeferred<Unit>,
    )

    private var activeSession: ConnectionSession? = null
}

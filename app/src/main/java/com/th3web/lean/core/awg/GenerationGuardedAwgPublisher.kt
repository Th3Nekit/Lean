package com.th3web.lean.core.awg

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

data class AwgPublicationSnapshot<S>(
    val settings: S,
    val selectedProfileId: String?,
)

class GenerationGuardedAwgPublisher<S, O : Any>(
    private val generationIsCurrent: (Long) -> Boolean,
    private val loadSnapshot: suspend () -> AwgPublicationSnapshot<S>,
    private val writeSelectedProfileOwned: suspend (String?) -> O,
    private val restoreSelectedProfileIfOwned: suspend (O, String?) -> Unit,
    private val commitIfCurrent: (Long, () -> AwgSessionObserver) -> AwgSessionObserver?,
) {
    suspend fun publish(
        generation: Long,
        selectedProfileId: String,
        commit: (S) -> AwgSessionObserver,
    ): AwgSessionObserver {
        ensureCurrent(generation)
        val snapshot = loadSnapshot()
        ensureCurrent(generation)
        var selectedProfileWriteOwner: O? = null
        try {
            selectedProfileWriteOwner = writeSelectedProfileOwned(selectedProfileId)
            return commitIfCurrent(generation) { commit(snapshot.settings) }
                ?: throw stale()
        } catch (failure: Throwable) {
            selectedProfileWriteOwner?.let { owner ->
                runCatching {
                    withContext(NonCancellable) {
                        restoreSelectedProfileIfOwned(owner, snapshot.selectedProfileId)
                    }
                }
                    .exceptionOrNull()
                    ?.let(failure::addSuppressed)
            }
            throw failure
        }
    }

    private fun ensureCurrent(generation: Long) {
        if (!generationIsCurrent(generation)) throw stale()
    }

    private fun stale() = AwgRuntimeException("Запуск AmneziaWG устарел")
}

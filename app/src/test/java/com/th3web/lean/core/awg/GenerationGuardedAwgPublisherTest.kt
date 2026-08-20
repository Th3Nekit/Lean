package com.th3web.lean.core.awg

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GenerationGuardedAwgPublisherTest {
    @Test
    fun `stale rollback cannot overwrite direct selected profile write for next generation`() =
        runBlocking {
        var currentGeneration = 1L
        var selectedProfileId: String? = "previous"
        var selectedProfileRevision = 0L
        var committed = false
        val publisher = GenerationGuardedAwgPublisher(
            generationIsCurrent = { it == currentGeneration },
            loadSnapshot = {
                AwgPublicationSnapshot(settings = "settings", selectedProfileId = selectedProfileId)
            },
            writeSelectedProfileOwned = {
                selectedProfileId = it
                val owner = ++selectedProfileRevision
                if (it == "profile-n") currentGeneration = 2L
                owner
            },
            restoreSelectedProfileIfOwned = { owner, previous ->
                if (selectedProfileRevision == owner) {
                    selectedProfileId = previous
                    selectedProfileRevision++
                }
            },
            commitIfCurrent = { generation, block ->
                if (generation == 1L) {
                    selectedProfileId = "profile-n-plus-one"
                    selectedProfileRevision++
                    null
                } else if (generation == currentGeneration) {
                    block()
                } else {
                    null
                }
            },
        )

        val error = runCatching {
            publisher.publish(1L, "profile-n") {
                committed = true
                AwgSessionObserver {}
            }
        }.exceptionOrNull()

        assertTrue(error is AwgRuntimeException)
        assertEquals("profile-n-plus-one", selectedProfileId)
        assertFalse(committed)
        }
}

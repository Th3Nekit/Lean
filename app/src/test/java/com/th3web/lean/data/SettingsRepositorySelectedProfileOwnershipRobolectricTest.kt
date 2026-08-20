package com.th3web.lean.data

import android.app.Application
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.preferencesDataStoreFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class SettingsRepositorySelectedProfileOwnershipRobolectricTest {
    @Test
    fun `direct selected profile write invalidates stale owned rollback`() = runBlocking {
        withRepository("direct_write") { repository ->
            repository.setSelectedProfile("previous")
            val owner = repository.writeSelectedProfileOwned("profile-n")

            repository.setSelectedProfile("profile-n-plus-one")

            assertFalse(repository.restoreSelectedProfileIfOwned(owner, "previous"))
            assertEquals("profile-n-plus-one", repository.flow.first().selectedProfileId)
        }
    }

    @Test
    fun `same-value direct write also invalidates stale owned rollback`() = runBlocking {
        withRepository("aba_write") { repository ->
            repository.setSelectedProfile("previous")
            val owner = repository.writeSelectedProfileOwned("profile-n")

            repository.setSelectedProfile("profile-n")

            assertFalse(repository.restoreSelectedProfileIfOwned(owner, "previous"))
            assertEquals("profile-n", repository.flow.first().selectedProfileId)
        }
    }

    @Test
    fun `owned rollback restores snapshot when no newer writer exists`() = runBlocking {
        withRepository("owned_rollback") { repository ->
            repository.setSelectedProfile("previous")
            val owner = repository.writeSelectedProfileOwned("profile-n")

            assertTrue(repository.restoreSelectedProfileIfOwned(owner, "previous"))
            assertEquals("previous", repository.flow.first().selectedProfileId)
        }
    }

    private suspend fun withRepository(
        suffix: String,
        block: suspend (SettingsRepository) -> Unit,
    ) {
        val context = RuntimeEnvironment.getApplication()
        val file = context.preferencesDataStoreFile("selected_profile_ownership_$suffix")
        if (file.exists()) assertTrue(file.delete())
        val job = SupervisorJob()
        val store = PreferenceDataStoreFactory.create(
            scope = CoroutineScope(Dispatchers.IO + job),
            produceFile = { file },
        )
        try {
            block(SettingsRepository(context, store))
        } finally {
            job.cancelAndJoin()
            if (file.exists()) assertTrue(file.delete())
        }
    }
}

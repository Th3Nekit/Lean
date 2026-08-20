package com.th3web.lean.data

import android.content.ContextWrapper
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import com.th3web.lean.data.model.Folder
import com.th3web.lean.data.model.Outbound
import com.th3web.lean.data.model.Profile
import com.th3web.lean.data.model.Subscription

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MergeStoreTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private fun repo(): ProfileRepository {
        val files = temporaryFolder.newFolder("profile-repository")
        val context = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun getFilesDir() = files
        }
        return ProfileRepository(context)
    }

    private fun vless(host: String) = Outbound.Vless(
        server = host,
        serverPort = 443,
        uuid = "uuid-$host",
    )

    @Test
    fun subscriptionIdCollisionWithDifferentUrlGetsUniqueIdAndRemapsProfiles() = runBlocking {
        val repository = repo()
        repository.replaceStore(
            StoreData(
                subscriptions = listOf(
                    Subscription(id = "shared-id", name = "Existing", url = "https://old.example/sub"),
                ),
            ),
        )

        val result = repository.mergeStore(
            StoreData(
                subscriptions = listOf(
                    Subscription(id = "shared-id", name = "Incoming", url = "https://new.example/sub"),
                ),
                profiles = listOf(
                    Profile(
                        id = "incoming-profile",
                        name = "Incoming node",
                        outbound = vless("new.example"),
                        subscriptionId = "shared-id",
                    ),
                ),
            ),
        )

        val store = repository.state.value
        val incomingSubscription = store.subscriptions.single { it.url == "https://new.example/sub" }
        assertNotEquals("shared-id", incomingSubscription.id)
        assertEquals(incomingSubscription.id, store.profiles.single().subscriptionId)
        assertEquals(ProfileRepository.MergeResult(1, 1), result)
    }

    @Test
    fun equalOutboundInManualAndSubscriptionProfilesIsNotConflated() = runBlocking {
        val repository = repo()
        val outbound = vless("same.example")
        repository.replaceStore(
            StoreData(
                profiles = listOf(
                    Profile(id = "manual", name = "Manual", outbound = outbound),
                ),
            ),
        )

        val result = repository.mergeStore(
            StoreData(
                subscriptions = listOf(
                    Subscription(id = "subscription", name = "Subscription", url = "https://same.example/sub"),
                ),
                profiles = listOf(
                    Profile(
                        id = "sub-profile",
                        name = "Subscription node",
                        outbound = outbound,
                        subscriptionId = "subscription",
                    ),
                ),
            ),
        )

        val profiles = repository.state.value.profiles
        assertEquals(2, profiles.size)
        assertTrue(profiles.any { it.id == "manual" && it.subscriptionId == null })
        assertTrue(profiles.any { it.id == "sub-profile" && it.subscriptionId == "subscription" })
        assertEquals(ProfileRepository.MergeResult(1, 1), result)
    }

    @Test
    fun profileIdCollisionWithDifferentOutboundGetsUniqueId() = runBlocking {
        val repository = repo()
        repository.replaceStore(
            StoreData(
                profiles = listOf(
                    Profile(id = "shared-profile", name = "Existing", outbound = vless("old.example")),
                ),
            ),
        )

        val result = repository.mergeStore(
            StoreData(
                profiles = listOf(
                    Profile(id = "shared-profile", name = "Incoming", outbound = vless("new.example")),
                ),
            ),
        )

        val incoming = repository.state.value.profiles.single {
            (it.outbound as Outbound.Vless).server == "new.example"
        }
        assertNotEquals("shared-profile", incoming.id)
        assertNull(incoming.subscriptionId)
        assertEquals(ProfileRepository.MergeResult(1, 0), result)
    }

    @Test
    fun mergedSubscriptionJoinsTheExistingFolderOfTheSameName() = runBlocking {
        val repository = repo()
        repository.replaceStore(
            StoreData(
                subscriptions = listOf(Subscription(id = "here", name = "Here", url = "https://here.example")),
                folders = listOf(Folder(id = "local-work", name = "Работа")),
            ),
        )

        // Same folder name, different id — a backup taken on another install.
        repository.mergeStore(
            StoreData(
                subscriptions = listOf(
                    Subscription(id = "there", name = "There", url = "https://there.example", folderId = "remote-work"),
                ),
                folders = listOf(Folder(id = "remote-work", name = "Работа")),
            ),
        )

        val store = repository.state.value
        assertEquals(1, store.folders.size)
        val merged = store.subscriptions.single { it.url == "https://there.example" }
        assertEquals("local-work", merged.folderId)
    }

    @Test
    fun mergedFolderThatDoesNotExistHereIsCreatedAndKeepsItsSubscription() = runBlocking {
        val repository = repo()
        repository.replaceStore(StoreData())

        repository.mergeStore(
            StoreData(
                subscriptions = listOf(
                    Subscription(id = "s", name = "S", url = "https://s.example", folderId = "remote"),
                ),
                folders = listOf(Folder(id = "remote", name = "Личное")),
            ),
        )

        val store = repository.state.value
        val folder = store.folders.single()
        assertEquals("Личное", folder.name)
        assertEquals(folder.id, store.subscriptions.single().folderId)
    }

    @Test
    fun subscriptionPointingAtAFolderTheBackupNeverCarriedLandsAtTheTopLevel() = runBlocking {
        val repository = repo()
        repository.replaceStore(StoreData())

        // The folder list is empty, so "ghost" resolves to nothing. Keeping the id would
        // file the subscription behind a header that is never drawn, i.e. hide it.
        repository.mergeStore(
            StoreData(
                subscriptions = listOf(
                    Subscription(id = "s", name = "S", url = "https://s.example", folderId = "ghost"),
                ),
            ),
        )

        assertEquals("", repository.state.value.subscriptions.single().folderId)
    }

    @Test
    fun deletingAFolderKeepsItsSubscriptions() = runBlocking {
        val repository = repo()
        repository.replaceStore(
            StoreData(
                subscriptions = listOf(
                    Subscription(id = "s", name = "S", url = "https://s.example", folderId = "f"),
                ),
                folders = listOf(Folder(id = "f", name = "Папка")),
            ),
        )

        repository.deleteFolder("f")

        val store = repository.state.value
        assertTrue(store.folders.isEmpty())
        assertEquals("", store.subscriptions.single().folderId)
    }
}

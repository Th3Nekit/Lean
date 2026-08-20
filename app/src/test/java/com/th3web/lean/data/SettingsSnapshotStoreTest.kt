package com.th3web.lean.data

import kotlinx.serialization.decodeFromString
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SettingsSnapshotStoreTest {

    @Test
    fun `startup snapshot round-trips without DataStore`() {
        val context = RuntimeEnvironment.getApplication()
        val store = SettingsSnapshotStore(context, "settings-startup-${UUID.randomUUID()}")
        val expected = Settings(
            selectedProfileId = "profile-42",
            themeMode = "light",
            accentColor = 0xFF123456L,
            crashReporting = true,
            userAgent = "NekoBox/1.3.8",
        )

        store.save(expected)

        assertEquals(expected, store.load())
    }

    @Test
    fun `startup snapshot round-trips the appearance block`() {
        val context = RuntimeEnvironment.getApplication()
        val store = SettingsSnapshotStore(context, "settings-startup-${UUID.randomUUID()}")
        val expected = Settings(
            themeMode = "system",
            contrastLevel = -2,
            surfaceTint = 18,
            cornerStyle = "round",
            textScale = 120,
            motionLevel = "off",
            roleOverrides = mapOf(AppearanceRoles.ACCENT to 0xFF98D1A6L),
            customPresets = listOf(NamedAppearance("Мой образ", AppearanceProfile(themeMode = "light"))),
            accentRecent = listOf(0xFF9FD2CBL),
        )

        store.save(expected)

        assertEquals(expected, store.load())
    }

    /**
     * The mirror is the first-frame source of truth, and it is read through a
     * `runCatching{}.getOrDefault(Settings())`. An appearance field WITHOUT a Kotlin
     * default would make this decode throw, the catch would swallow it, and every
     * install would fall back to all-defaults forever — silently, on every launch.
     */
    @Test
    fun `a snapshot written before the appearance block still loads`() {
        val decoded = Serialization.json.decodeFromString<Settings>("""{"themeMode":"amoled"}""")

        assertEquals("amoled", decoded.themeMode)
        assertEquals(AppearanceProfile.Default.cornerStyle, decoded.cornerStyle)
        assertEquals(SettingsDefaults.SURFACE_TINT, decoded.surfaceTint)
        assertEquals(SettingsDefaults.LAT_T3, decoded.latT3)
        assertEquals(emptyMap<String, Long>(), decoded.roleOverrides)
    }

    @Test
    fun `corrupt startup snapshot fails closed to defaults`() {
        val context = RuntimeEnvironment.getApplication()
        val name = "settings-startup-${UUID.randomUUID()}"
        context.getSharedPreferences(name, 0)
            .edit()
            .putString(SettingsSnapshotStore.VALUE_KEY, "{broken")
            .commit()

        assertEquals(Settings(), SettingsSnapshotStore(context, name).load())
    }

    @Test
    fun `legacy restore changes only fields that were present in backup`() {
        val current = Settings(
            remoteDns = "https://current.example/dns-query",
            pingUrl = "https://current.example/generate_204",
            checkAppUpdates = false,
            appIcon = "accent",
            crashReporting = true,
        )
        val legacyDecoded = Settings(
            remoteDns = "https://legacy.example/dns-query",
        )

        val merged = SettingsRestore.merge(
            current = current,
            imported = legacyDecoded,
            importedFields = setOf("remoteDns"),
        )

        assertEquals("https://legacy.example/dns-query", merged.remoteDns)
        assertEquals(current.pingUrl, merged.pingUrl)
        assertEquals(current.checkAppUpdates, merged.checkAppUpdates)
        assertEquals(current.appIcon, merged.appIcon)
        assertEquals(current.crashReporting, merged.crashReporting)
    }

    @Test
    fun `current full backup still replaces every setting`() {
        val current = Settings(themeMode = "light", checkAppUpdates = false)
        val imported = Settings(themeMode = "amoled", checkAppUpdates = true)

        assertEquals(
            imported,
            SettingsRestore.merge(current, imported, importedFields = null),
        )
    }
}

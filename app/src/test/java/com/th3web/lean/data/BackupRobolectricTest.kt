package com.th3web.lean.data

import android.util.Base64
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Backup uses android.util.Base64 + javax.crypto, so these need Robolectric
 * (the bare android.jar Base64 is a throwing stub). SDK 34 is pinned per the
 * project convention (see ShareLinksRobolectricTest). Robolectric runs the real
 * JCE, so AES/GCM + PBKDF2 behave exactly as on-device.
 *
 * Coverage:
 *  - new 600k scheme round-trips (export -> import),
 *  - a hand-built OLD-format wrapper (leanBackup=1, NO iterations field,
 *    ciphertext derived at 100k) still imports -> backward-compat proof,
 *  - a payload whose schema version is NEWER than this app understands is
 *    rejected with Backup.TooNew -> forward-compat guard,
 *  - a wrong password still maps to WrongPassword.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BackupRobolectricTest {

    private val password = "correct horse battery staple"
    private val sampleStore = StoreData()
    private val sampleSettings = Settings()

    @Test
    fun `new scheme round-trips export then import`() {
        val wrapperJson = Backup.export(sampleStore, sampleSettings, password)
        val payload = Backup.import(wrapperJson, password)
        assertEquals(1, payload.version)
        assertEquals("android", payload.platform)
        assertEquals(sampleStore, payload.store)
        assertEquals(sampleSettings, payload.settings)
    }

    @Test
    fun `old 100k format with no iterations field still imports`() {
        // Build a wrapper exactly as the pre-change app would: magic=1, no
        // iterations key in the JSON, key derived with 100_000 iterations.
        val payloadJson = Serialization.json.encodeToString(
            BackupPayload(version = 1, platform = "android", store = sampleStore, settings = sampleSettings),
        ).toByteArray(Charsets.UTF_8)
        val rnd = SecureRandom()
        val salt = ByteArray(16).also(rnd::nextBytes)
        val iv = ByteArray(12).also(rnd::nextBytes)
        val ct = legacyEncrypt(payloadJson, password, salt, iv, iterations = 100_000)
        // Note: "iterations" key is intentionally ABSENT, like a real legacy file.
        val legacyWrapperJson = """{"leanBackup":1,"mode":"password","salt":"${b64(salt)}","iv":"${b64(iv)}","data":"${b64(ct)}"}"""

        val payload = Backup.import(legacyWrapperJson, password)

        assertEquals(sampleStore, payload.store)
        assertEquals(sampleSettings, payload.settings)
    }

    @Test
    fun `payload version newer than supported is rejected`() {
        // Valid V2 crypto wrapper, but the decrypted payload claims version 99.
        val futurePayloadJson = Serialization.json.encodeToString(
            BackupPayload(version = 99, platform = "android", store = sampleStore),
        ).toByteArray(Charsets.UTF_8)
        val rnd = SecureRandom()
        val salt = ByteArray(16).also(rnd::nextBytes)
        val iv = ByteArray(12).also(rnd::nextBytes)
        val ct = legacyEncrypt(futurePayloadJson, password, salt, iv, iterations = 600_000)
        val wrapperJson = """{"leanBackup":2,"mode":"password","iterations":600000,"salt":"${b64(salt)}","iv":"${b64(iv)}","data":"${b64(ct)}"}"""

        assertThrows(Backup.TooNew::class.java) { Backup.import(wrapperJson, password) }
    }

    @Test
    fun `wrong password maps to WrongPassword`() {
        val wrapperJson = Backup.export(sampleStore, sampleSettings, password)
        assertThrows(Backup.WrongPassword::class.java) { Backup.import(wrapperJson, "nope") }
    }

    @Test
    fun `legacy settings JSON without new defaults remains importable`() {
        val settings = Serialization.json.decodeFromString<Settings>(
            """{"remoteDns":"https://custom.example/dns-query"}""",
        )

        assertEquals("https://custom.example/dns-query", settings.remoteDns)
        assertEquals(SettingsDefaults.TEST_URL, settings.pingUrl)
    }

    @Test
    fun `import remembers exactly which legacy settings fields were present`() {
        val payloadJson = """
            {
              "version": 1,
              "platform": "android",
              "store": {},
              "settings": {"remoteDns":"https://legacy.example/dns-query"}
            }
        """.trimIndent().toByteArray(Charsets.UTF_8)
        val rnd = SecureRandom()
        val salt = ByteArray(16).also(rnd::nextBytes)
        val iv = ByteArray(12).also(rnd::nextBytes)
        val ct = legacyEncrypt(payloadJson, password, salt, iv, iterations = 600_000)
        val wrapperJson = """{"leanBackup":2,"mode":"password","iterations":600000,"salt":"${b64(salt)}","iv":"${b64(iv)}","data":"${b64(ct)}"}"""

        val payload = Backup.import(wrapperJson, password)

        assertEquals(setOf("remoteDns"), payload.settingsFields)
        assertEquals("https://legacy.example/dns-query", payload.settings?.remoteDns)
        assertEquals(
            "a default injected by deserialization must not be mistaken for a field present in the backup",
            SettingsDefaults.TEST_URL,
            payload.settings?.pingUrl,
        )
    }

    // --- local helpers (mirror Backup's crypto so fixtures are self-contained) ---

    private fun legacyEncrypt(pt: ByteArray, pw: String, salt: ByteArray, iv: ByteArray, iterations: Int): ByteArray {
        val spec = PBEKeySpec(pw.toCharArray(), salt, iterations, 256)
        val keyBytes = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        val key = SecretKeySpec(keyBytes, "AES")
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv))
        return cipher.doFinal(pt)
    }

    private fun b64(b: ByteArray): String = Base64.encodeToString(b, Base64.NO_WRAP)
}

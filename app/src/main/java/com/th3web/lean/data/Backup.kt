package com.th3web.lean.data

import android.util.Base64
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Transient
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import com.th3web.lean.BuildConfig
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Decrypted backup payload: the whole store (profiles incl. favorites +
 * subscriptions, verbatim), and an optional settings snapshot.
 */
@Serializable
data class BackupPayload(
    val version: Int = 1,
    val platform: String = "android",
    val appVersion: String = "",
    /** ISO-8601 UTC instant string. */
    val createdAt: String = "",
    val store: StoreData = StoreData(),
    val settings: Settings? = null,
    /**
     * Exact serialized field presence from an imported settings object. This is
     * not exported: current backups encode every default, while it
     * lets Replace preserve newer live settings absent from a legacy backup.
     */
    @Transient val settingsFields: Set<String>? = null,
)

/**
 * On-disk wrapper: `{"leanBackup":1,"mode":"password","salt":…,"iv":…,"data":…}`
 * with all binary values Base64.NO_WRAP-encoded.
 */
@Serializable
private data class EncryptedWrapper(
    val leanBackup: Int? = null,
    val mode: String = "password",
    /** PBKDF2 iteration count. Absent (null) == a pre-v2 backup derived at 100k. */
    val iterations: Int? = null,
    val salt: String = "",
    val iv: String = "",
    val data: String = "",
)

/**
 * Encrypted settings/servers backup: Incy's file layout, Lean's own magic.
 *
 * Crypto (javax.crypto only, password mode only, no hardcoded-key "key" mode):
 * salt = SecureRandom 16 B; key = PBKDF2WithHmacSHA256(password, salt,
 * 100 000 iterations, 256 bit); iv = SecureRandom 12 B; AES/GCM/NoPadding with
 * a 128-bit tag. The wrapper and payload both serialize via [Serialization.json].
 */
object Backup {

    /** GCM auth-tag mismatch: the password is wrong (or salt/iv tampered). */
    class WrongPassword : Exception()

    /** Missing/foreign magic, not a Lean backup (covers Incy `incyBackup` files). */
    class NotALeanBackup : Exception()

    /** Lean magic present but the file is damaged or the payload undecodable. */
    class Corrupt : Exception()

    /**
     * The backup's payload schema version is newer than this build understands
     * (a backup made by a future Lean release). Parsing it would silently
     * mis-interpret fields, so we refuse rather than guess.
     */
    class TooNew : Exception()

    // Wrapper magic: V1 = legacy (100k, no iterations field). V2 = iterations written
    // into the header. Both are accepted on import; export always writes V2.
    private const val MAGIC_V1 = 1
    private const val MAGIC_V2 = 2
    private const val MAGIC = MAGIC_V2
    // OWASP 2023 minimum for PBKDF2-HMAC-SHA256.
    private const val PBKDF2_ITERATIONS = 600_000
    // Iteration count used by all pre-v2 backups (the wrapper had no iterations field).
    private const val LEGACY_PBKDF2_ITERATIONS = 100_000
    // Highest BackupPayload.version this build can parse (forward-compat guard).
    private const val PAYLOAD_VERSION = 1
    private const val KEY_BITS = 256
    private const val GCM_TAG_BITS = 128
    private const val SALT_BYTES = 16
    private const val IV_BYTES = 12

    /** Encrypts store+settings; returns the wrapper JSON to write to the SAF file. */
    fun export(store: StoreData, settings: Settings, password: String): String {
        require(password.isNotEmpty()) { "backup password must not be empty" }
        val payload = BackupPayload(
            version = 1,
            platform = "android",
            appVersion = BuildConfig.VERSION_NAME,
            createdAt = isoNowUtc(),
            store = store,
            settings = settings,
        )
        val plaintext = Serialization.json.encodeToString(payload).toByteArray(Charsets.UTF_8)
        val random = SecureRandom()
        val salt = ByteArray(SALT_BYTES).also(random::nextBytes)
        val iv = ByteArray(IV_BYTES).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, deriveKey(password, salt, PBKDF2_ITERATIONS), GCMParameterSpec(GCM_TAG_BITS, iv))
        val encrypted = cipher.doFinal(plaintext)
        val wrapper = EncryptedWrapper(
            leanBackup = MAGIC,
            mode = "password",
            iterations = PBKDF2_ITERATIONS,
            salt = b64(salt),
            iv = b64(iv),
            data = b64(encrypted),
        )
        return Serialization.json.encodeToString(wrapper)
    }

    /**
     * Decrypts a wrapper JSON read from a SAF file.
     * @throws WrongPassword on GCM tag mismatch
     * @throws NotALeanBackup on missing/foreign magic (incl. Incy backups)
     * @throws Corrupt on damaged base64/ciphertext or an undecodable payload
     */
    fun import(wrapperJson: String, password: String): BackupPayload {
        require(password.isNotEmpty()) { "backup password must not be empty" }
        val wrapper = runCatching {
            Serialization.json.decodeFromString<EncryptedWrapper>(wrapperJson)
        }.getOrElse { throw NotALeanBackup() }
        if (wrapper.leanBackup != MAGIC_V1 && wrapper.leanBackup != MAGIC_V2) throw NotALeanBackup()
        if (wrapper.mode != "password") throw Corrupt()
        val salt = b64d(wrapper.salt) ?: throw Corrupt()
        val iv = b64d(wrapper.iv) ?: throw Corrupt()
        val data = b64d(wrapper.data) ?: throw Corrupt()
        val plaintext = try {
            // Absent iterations == a pre-v2 (100k) backup: the fallback is what keeps
            // every already-exported backup decryptable after the 600k bump.
            val iterations = wrapper.iterations ?: LEGACY_PBKDF2_ITERATIONS
            if (iterations < 1) throw Corrupt()
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, deriveKey(password, salt, iterations), GCMParameterSpec(GCM_TAG_BITS, iv))
            cipher.doFinal(data)
        } catch (e: AEADBadTagException) {
            throw WrongPassword()
        } catch (e: Exception) {
            throw Corrupt()
        }
        val payload = try {
            val root = Serialization.json.parseToJsonElement(
                String(plaintext, Charsets.UTF_8),
            ) as? JsonObject ?: throw SerializationException("payload root is not an object")
            val decoded = Serialization.json.decodeFromJsonElement<BackupPayload>(root)
            decoded.copy(settingsFields = (root["settings"] as? JsonObject)?.keys)
        } catch (e: SerializationException) {
            throw Corrupt()
        }
        // Forward-compat: a payload from a newer Lean would be silently mis-parsed
        // (ignoreUnknownKeys drops fields we don't know). Reject it explicitly. platform
        // is intentionally not rejected, a same-schema iOS backup stays importable.
        if (payload.version > PAYLOAD_VERSION) throw TooNew()
        return payload
    }

    /** "lean-backup-yyyy-MM-dd-HHmm.json" (UTC, Locale.US), Incy naming, Lean prefix. */
    fun suggestedFileName(): String =
        "lean-backup-${utcFormat("yyyy-MM-dd-HHmm").format(Date())}.json"

    // ---- helpers ----

    private fun deriveKey(password: String, salt: ByteArray, iterations: Int): SecretKeySpec {
        val spec = PBEKeySpec(password.toCharArray(), salt, iterations, KEY_BITS)
        val keyBytes = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            .generateSecret(spec).encoded
        spec.clearPassword()
        // SecretKeySpec clones the input, so zeroing the derived key bytes is safe and
        // keeps the raw 256-bit key from lingering on the heap until GC. (Do not call
        // SecretKeySpec.destroy(): it is unimplemented on Android and throws.)
        return SecretKeySpec(keyBytes, "AES").also { java.util.Arrays.fill(keyBytes, 0) }
    }

    private fun isoNowUtc(): String = utcFormat("yyyy-MM-dd'T'HH:mm:ss'Z'").format(Date())

    private fun utcFormat(pattern: String): SimpleDateFormat =
        SimpleDateFormat(pattern, Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }

    private fun b64(bytes: ByteArray): String = Base64.encodeToString(bytes, Base64.NO_WRAP)

    private fun b64d(s: String): ByteArray? =
        runCatching { Base64.decode(s, Base64.NO_WRAP) }.getOrNull()?.takeIf { it.isNotEmpty() }
}

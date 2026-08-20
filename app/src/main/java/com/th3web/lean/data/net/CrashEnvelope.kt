package com.th3web.lean.data.net

import android.util.AtomicFile
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
internal data class CrashPayload(
    @SerialName("schema_version")
    val schemaVersion: Int = 1,
    @SerialName("app_version")
    val appVersion: String,
    @SerialName("exception_type")
    val exceptionType: String,
    val message: String,
    @SerialName("stack_trace")
    val stackTrace: String,
    @SerialName("log_tail")
    val logTail: List<String>,
)

@Serializable
internal data class CrashEnvelope(
    @SerialName("local_schema_version")
    val localSchemaVersion: Int = 1,
    @SerialName("captured_at_epoch_ms")
    val capturedAtEpochMs: Long,
    @SerialName("attempt_count")
    val attemptCount: Int = 0,
    @SerialName("next_attempt_at_epoch_ms")
    val nextAttemptAtEpochMs: Long = 0,
    val payload: CrashPayload,
) {
    companion object {
        fun create(payload: CrashPayload, capturedAtEpochMs: Long): CrashEnvelope =
            CrashEnvelope(payload = payload, capturedAtEpochMs = capturedAtEpochMs)
    }
}

internal object CrashCodec {
    const val MAX_REQUEST_BYTES = 32 * 1024
    const val MAX_LOCAL_BYTES = 64 * 1024

    private const val MAX_APP_VERSION_CHARS = 32
    private const val MAX_EXCEPTION_TYPE_CHARS = 120

    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = false
        isLenient = false
        allowStructuredMapKeys = false
    }

    /**
     * A report that does not fit is not a report.
     *
     * The size limit is counted in bytes while every budget upstream of it is counted in
     * characters, and the two are not the same number: the app's own log lines are
     * Russian, so a line inside its character budget can be twice its length in the
     * request. Rather than let that throw, losing the entire report, which is the one
     * outcome worth avoiding, shed log lines until it fits.
     *
     * From the middle, because both ends are load-bearing: a native fault dump keeps its
     * head (the fault itself), an ordinary log keeps its tail (what happened last).
     */
    fun encodePayload(payload: CrashPayload): ByteArray {
        requireValid(payload)
        var current = payload
        var bytes = encode(current)
        while (bytes.size >= MAX_REQUEST_BYTES && current.logTail.isNotEmpty()) {
            val middle = current.logTail.size / 2
            current = current.copy(
                logTail = current.logTail.filterIndexed { index, _ -> index != middle },
            )
            bytes = encode(current)
        }
        return bytes.also { require(it.size < MAX_REQUEST_BYTES) }
    }

    private fun encode(payload: CrashPayload): ByteArray =
        json.encodeToString(CrashPayload.serializer(), payload).toByteArray(Charsets.UTF_8)

    fun encodeEnvelope(envelope: CrashEnvelope): ByteArray {
        requireValid(envelope)
        return json.encodeToString(CrashEnvelope.serializer(), envelope)
            .toByteArray(Charsets.UTF_8)
            .also { require(it.size <= MAX_LOCAL_BYTES) }
    }

    fun decodeEnvelope(bytes: ByteArray): CrashEnvelope {
        require(bytes.isNotEmpty() && bytes.size <= MAX_LOCAL_BYTES)
        val envelope = json.decodeFromString(
            CrashEnvelope.serializer(),
            bytes.toString(Charsets.UTF_8),
        )
        requireValid(envelope)
        return envelope
    }

    private fun requireValid(envelope: CrashEnvelope) {
        require(envelope.localSchemaVersion == 1)
        require(envelope.capturedAtEpochMs >= 0)
        require(envelope.attemptCount in 0..CrashDelivery.MAX_ATTEMPTS)
        require(envelope.nextAttemptAtEpochMs >= 0)
        requireValid(envelope.payload)
    }

    private fun requireValid(payload: CrashPayload) {
        require(payload.schemaVersion == 1)
        require(payload.appVersion.length in 1..MAX_APP_VERSION_CHARS)
        require(payload.exceptionType.length in 1..MAX_EXCEPTION_TYPE_CHARS)
        require(payload.message.length <= CrashRedactor.MAX_MESSAGE_CHARS)
        require(payload.stackTrace.length <= CrashRedactor.MAX_STACK_TRACE_CHARS)
        require(payload.logTail.size <= CrashRedactor.MAX_LOG_LINES)
        require(payload.logTail.all { it.length <= CrashRedactor.MAX_LOG_LINE_CHARS })
        require(payload.logTail.sumOf(String::length) <= CrashRedactor.MAX_LOG_TOTAL_CHARS)
    }
}

internal interface CrashStore {
    fun save(envelope: CrashEnvelope)
    fun load(): CrashEnvelope?
    fun delete()
    fun hasArtifacts(): Boolean
}

internal fun InputStream.readBounded(maxBytes: Int): ByteArray {
    require(maxBytes >= 0)
    val output = ByteArrayOutputStream(minOf(maxBytes, DEFAULT_BUFFER_SIZE))
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    var remaining = maxBytes
    while (remaining > 0) {
        val count = read(buffer, 0, minOf(buffer.size, remaining))
        if (count < 0) return output.toByteArray()
        if (count == 0) continue
        output.write(buffer, 0, count)
        remaining -= count
    }
    require(read() < 0) { "Input exceeds $maxBytes bytes" }
    return output.toByteArray()
}

internal class AtomicCrashStore(directory: File) : CrashStore {
    internal val baseFile = File(directory, FILE_NAME)
    private val atomicFile: AtomicFile

    init {
        require(directory.exists() || directory.mkdirs()) {
            "Cannot create crash diagnostics directory"
        }
        atomicFile = AtomicFile(baseFile)
    }

    override fun save(envelope: CrashEnvelope) {
        val bytes = CrashCodec.encodeEnvelope(envelope)
        val output = atomicFile.startWrite()
        try {
            output.write(bytes)
            atomicFile.finishWrite(output)
        } catch (error: Throwable) {
            atomicFile.failWrite(output)
            throw error
        }
    }

    override fun load(): CrashEnvelope? {
        return try {
            val bytes = atomicFile.openRead().use { input ->
                input.readBounded(CrashCodec.MAX_LOCAL_BYTES)
            }
            CrashCodec.decodeEnvelope(bytes)
        } catch (_: Throwable) {
            delete()
            null
        }
    }

    override fun delete() {
        atomicFile.delete()
        File(baseFile.path + ".bak").delete()
        File(baseFile.path + ".new").delete()
    }

    override fun hasArtifacts(): Boolean =
        baseFile.exists() ||
            File(baseFile.path + ".bak").exists() ||
            File(baseFile.path + ".new").exists()

    private companion object {
        const val FILE_NAME = "pending_crash.json"
    }
}

package com.th3web.lean.data.net

import java.io.IOException
import org.json.JSONObject

internal fun interface CrashClock {
    fun nowMillis(): Long
}

internal fun interface CrashTransport {
    fun post(body: ByteArray): CrashHttpResponse
}

internal data class CrashHttpResponse(
    val statusCode: Int,
    val body: ByteArray,
    val bodyTruncated: Boolean = false,
)

internal enum class CrashDeliveryResult {
    Delivered,
    Retry,
    Dropped,
    NothingToDo,
}

internal class CrashDelivery(
    private val store: CrashStore,
    private val transport: CrashTransport,
    private val clock: CrashClock,
    private val enabled: () -> Boolean,
) {
    fun deliver(): CrashDeliveryResult {
        if (!enabled()) {
            store.delete()
            return CrashDeliveryResult.Dropped
        }
        val envelope = store.load() ?: return CrashDeliveryResult.NothingToDo
        val now = clock.nowMillis()
        if (
            envelope.capturedAtEpochMs > now ||
            now - envelope.capturedAtEpochMs > MAX_AGE_MS ||
            envelope.attemptCount >= MAX_ATTEMPTS
        ) {
            store.delete()
            return CrashDeliveryResult.Dropped
        }
        if (envelope.nextAttemptAtEpochMs > now) return CrashDeliveryResult.Retry

        val attempted = envelope.copy(
            attemptCount = envelope.attemptCount + 1,
            nextAttemptAtEpochMs = boundedAdd(now, backoffMs(envelope.attemptCount + 1)),
        )
        try {
            store.save(attempted)
        } catch (_: Throwable) {
            return CrashDeliveryResult.Retry
        }

        val response = try {
            transport.post(CrashCodec.encodePayload(attempted.payload))
        } catch (_: IOException) {
            return CrashDeliveryResult.Retry
        } catch (_: Throwable) {
            store.delete()
            return CrashDeliveryResult.Dropped
        }

        if (isAcceptedCrashResponse(response)) {
            store.delete()
            return CrashDeliveryResult.Delivered
        }
        if (
            response.statusCode == 408 ||
            response.statusCode == 429 ||
            response.statusCode in 500..599
        ) {
            return CrashDeliveryResult.Retry
        }
        store.delete()
        return CrashDeliveryResult.Dropped
    }

    private fun backoffMs(attempt: Int): Long {
        val multiplier = 1L shl (attempt - 1).coerceIn(0, 10)
        return (INITIAL_BACKOFF_MS * multiplier).coerceAtMost(MAX_BACKOFF_MS)
    }

    private fun boundedAdd(left: Long, right: Long): Long =
        if (Long.MAX_VALUE - left < right) Long.MAX_VALUE else left + right

    companion object {
        const val MAX_AGE_MS = 7L * 24 * 60 * 60 * 1_000
        const val MAX_ATTEMPTS = 8
        private const val INITIAL_BACKOFF_MS = 30_000L
        private const val MAX_BACKOFF_MS = 6L * 60 * 60 * 1_000
    }
}

internal fun isAcceptedCrashResponse(response: CrashHttpResponse): Boolean {
    if (
        response.statusCode != 202 ||
        response.bodyTruncated ||
        response.body.size > CrashHttpTransport.MAX_RESPONSE_BYTES
    ) {
        return false
    }
    return runCatching {
        val json = JSONObject(response.body.toString(Charsets.UTF_8))
        if (json.keys().asSequence().toSet() != setOf("report_id", "status")) return false
        json.getString("status") == "accepted" &&
            Regex("[A-Za-z0-9_-]{10,24}").matches(json.getString("report_id"))
    }.getOrDefault(false)
}

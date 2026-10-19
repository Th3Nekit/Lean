package com.th3web.lean.data.net

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

class SubscriptionHwidRequired : IllegalStateException("Subscription requires device identification") {
    companion object {
        fun isRejectionName(value: String): Boolean {
            val name = value.trim { !it.isLetterOrDigit() }.lowercase()
                .replace(Regex("[\\s\\p{Z}]+"), " ")
            return name in setOf(
                "app not supported", "application not supported", "приложение не поддерживается",
                "hwid required", "hwid is required", "missing hwid", "hwid not found",
            )
        }

        fun isRejectionBody(value: String): Boolean {
            if (isRejectionName(value)) return true
            val text = value.trim().removePrefix("\uFEFF").trimStart()
            if (!text.startsWith("{")) return false
            val body = runCatching { Json.parseToJsonElement(text) as? JsonObject }.getOrNull() ?: return false
            return rejectionMessage(body)
        }

        private fun rejectionMessage(body: JsonObject): Boolean =
            body.any { (key, value) ->
                key.lowercase() in setOf("error", "message", "detail") && when (value) {
                    is JsonPrimitive -> value.isString && isRejectionName(value.content)
                    is JsonObject -> rejectionMessage(value)
                    else -> false
                }
            }
    }
}

package com.th3web.lean.data

import kotlinx.serialization.json.Json

/** Shared JSON for persistence and backups; added Settings fields remain optional on import. */
object Serialization {
    val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        isLenient = true
        // Sealed Outbound hierarchy serializes with the default "type" discriminator.
    }
}

package com.th3web.lean.data

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject

internal object SettingsRestore {
    fun merge(
        current: Settings,
        imported: Settings,
        importedFields: Set<String>?,
    ): Settings {
        if (importedFields == null) return imported

        val merged = Serialization.json.encodeToJsonElement(current).jsonObject.toMutableMap()
        val importedObject = Serialization.json.encodeToJsonElement(imported).jsonObject
        importedFields.forEach { field ->
            importedObject[field]?.let { value -> merged[field] = value }
        }
        return Serialization.json.decodeFromJsonElement(JsonObject(merged))
    }
}

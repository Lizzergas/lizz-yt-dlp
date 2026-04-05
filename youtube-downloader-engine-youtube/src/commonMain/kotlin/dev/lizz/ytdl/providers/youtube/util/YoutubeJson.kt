package dev.lizz.ytdl.providers.youtube

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

internal fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull()?.takeIf { it.isNotBlank() }
internal fun JsonObject.double(key: String): Double? = (this[key] as? JsonPrimitive)?.contentOrNull()?.toDoubleOrNull()
internal fun JsonObject.array(key: String): List<JsonElement> = (this[key] as? JsonArray)?.toList().orEmpty()

internal fun JsonObject.objectAt(vararg path: String): JsonObject? {
    var current: JsonElement = this
    for (key in path) {
        current = (current as? JsonObject)?.get(key) ?: return null
    }
    return current as? JsonObject
}

internal fun JsonObject.stringAt(vararg path: String): String? {
    var current: JsonElement = this
    for (key in path) {
        current = (current as? JsonObject)?.get(key) ?: return null
    }
    return (current as? JsonPrimitive)?.contentOrNull()?.takeIf { it.isNotBlank() }
}

internal fun JsonPrimitive.contentOrNull(): String? = runCatching { content }.getOrNull()

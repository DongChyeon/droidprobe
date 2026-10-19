package dev.droidprobe

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull

val EmptyJsonObject: JsonObject = JsonObject(emptyMap())

private fun JsonObject.primitive(key: String): JsonPrimitive? =
    (this[key] as? JsonPrimitive)?.takeUnless { it is JsonNull }

fun JsonObject.stringOrNull(key: String): String? = primitive(key)?.takeIf { it.isString }?.contentOrNull

fun JsonObject.longOrNull(key: String): Long? = primitive(key)?.takeUnless { it.isString }?.let {
    it.longOrNull ?: it.content.toDoubleOrNull()?.takeIf { d -> d % 1.0 == 0.0 }?.toLong()
}

fun JsonObject.intOrNull(key: String): Int? = longOrNull(key)?.toInt()

fun JsonObject.booleanOrNull(key: String): Boolean? = primitive(key)?.takeUnless { it.isString }?.booleanOrNull

fun JsonObject.objectOrNull(key: String): JsonObject? = this[key] as? JsonObject

internal fun JsonElement?.orEmptyObject(): JsonObject = this as? JsonObject ?: EmptyJsonObject

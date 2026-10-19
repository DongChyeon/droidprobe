package dev.droidprobe

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * A JSON Schema subset: type, properties, required, enum, items, additionalProperties (boolean)
 * and description. Anything else in [json] is ignored by [validate].
 */
class Schema(val json: JsonObject) {

    /** Returns null when [value] conforms, otherwise a message starting with the JSON path. */
    fun validate(value: JsonElement): String? = check(json, value, "$")

    companion object {
        fun parse(text: String): Schema = Schema(kotlinx.serialization.json.Json.parseToJsonElement(text).jsonObject)

        fun anything(description: String? = null): Schema = Schema(buildJsonObject { describe(description) })

        fun anyObject(description: String? = null): Schema = Schema(buildJsonObject {
            put("type", "object")
            describe(description)
        })

        fun string(description: String? = null, enum: List<String>? = null, nullable: Boolean = false): Schema =
            typed("string", description, nullable) {
                if (enum != null) put("enum", buildJsonArray { enum.forEach { add(JsonPrimitive(it)) } })
            }

        fun integer(description: String? = null, nullable: Boolean = false): Schema = typed("integer", description, nullable)

        fun number(description: String? = null, nullable: Boolean = false): Schema = typed("number", description, nullable)

        fun boolean(description: String? = null, nullable: Boolean = false): Schema = typed("boolean", description, nullable)

        fun array(items: Schema, description: String? = null): Schema = typed("array", description, false) {
            put("items", items.json)
        }

        fun obj(
            vararg properties: Pair<String, Schema>,
            required: List<String> = emptyList(),
            additionalProperties: Boolean = false,
            description: String? = null,
            nullable: Boolean = false,
        ): Schema = typed("object", description, nullable) {
            put("properties", JsonObject(properties.associate { (name, schema) -> name to schema.json }))
            if (required.isNotEmpty()) put("required", buildJsonArray { required.forEach { add(JsonPrimitive(it)) } })
            put("additionalProperties", additionalProperties)
        }

        private fun typed(
            type: String,
            description: String?,
            nullable: Boolean,
            extra: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit = {},
        ): Schema = Schema(buildJsonObject {
            if (nullable) put("type", buildJsonArray { add(JsonPrimitive(type)); add(JsonPrimitive("null")) }) else put("type", type)
            describe(description)
            extra()
        })

        private fun kotlinx.serialization.json.JsonObjectBuilder.describe(description: String?) {
            if (description != null) put("description", description)
        }

        private fun check(schema: JsonObject, value: JsonElement, path: String): String? {
            val types = when (val type = schema["type"]) {
                null -> null
                is JsonArray -> type.map { it.jsonPrimitive.content }
                else -> listOf(type.jsonPrimitive.content)
            }
            if (types != null && types.none { matches(it, value) }) {
                return "$path: expected ${types.joinToString(" or ")}"
            }
            (schema["enum"] as? JsonArray)?.let { allowed ->
                if (value !in allowed) return "$path: expected one of ${allowed.joinToString(", ")}"
            }
            if (value is JsonObject) {
                val properties = schema["properties"] as? JsonObject ?: EmptyJsonObject
                (schema["required"] as? JsonArray)?.forEach { name ->
                    val key = name.jsonPrimitive.content
                    if (key !in value) return "$path.$key: required"
                }
                val additional = (schema["additionalProperties"] as? JsonPrimitive)?.booleanOrNull ?: true
                for ((key, child) in value) {
                    val childSchema = properties[key]
                    if (childSchema != null) {
                        check(childSchema.jsonObject, child, "$path.$key")?.let { return it }
                    } else if (!additional) {
                        return "$path.$key: unexpected property"
                    }
                }
            }
            if (value is JsonArray) {
                val items = schema["items"] as? JsonObject
                if (items != null) {
                    value.forEachIndexed { index, item -> check(items, item, "$path[$index]")?.let { return it } }
                }
            }
            return null
        }

        private fun matches(type: String, value: JsonElement): Boolean = when (type) {
            "null" -> value is JsonNull
            "object" -> value is JsonObject
            "array" -> value is JsonArray
            "string" -> value is JsonPrimitive && value !is JsonNull && value.isString
            "boolean" -> value is JsonPrimitive && value !is JsonNull && !value.isString && value.booleanOrNull != null
            "number" -> value is JsonPrimitive && value !is JsonNull && !value.isString && value.doubleOrNull != null
            "integer" -> value is JsonPrimitive && value !is JsonNull && !value.isString &&
                value.doubleOrNull?.let { it % 1.0 == 0.0 } == true
            else -> false
        }
    }
}

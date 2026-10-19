package dev.droidprobe.okhttp

import dev.droidprobe.ProbeError
import dev.droidprobe.booleanOrNull
import dev.droidprobe.intOrNull
import dev.droidprobe.longOrNull
import dev.droidprobe.objectOrNull
import dev.droidprobe.stringOrNull
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** A mocked response; [status], [headers] and [body] are already resolved when a template is used. */
data class MockRule(
    val id: String? = null,
    val method: String? = null,
    val path: String,
    val status: Int? = null,
    val headers: Map<String, String> = emptyMap(),
    val body: String? = null,
    val template: String? = null,
    val vars: JsonObject? = null,
    val delayMs: Long = 0,
    val failure: String? = null,
    val times: Int? = null,
    val remaining: Int? = null,
    val persist: Boolean = false,
    val allowAuthSideEffects: Boolean = false,
) {
    fun matches(method: String, path: String): Boolean =
        (this.method == null || this.method.equals(method, ignoreCase = true)) && PathGlob.matches(this.path, path)

    fun toJson(): JsonObject = buildJsonObject {
        put("id", id)
        put("method", method)
        put("path", path)
        put("status", status)
        put("headers", JsonObject(headers.mapValues { JsonPrimitive(it.value) }))
        put("body", body)
        put("template", template)
        put("vars", vars ?: JsonNull)
        put("delayMs", delayMs)
        put("failure", failure)
        put("times", times)
        put("remaining", remaining)
        put("persist", persist)
        put("allowAuthSideEffects", allowAuthSideEffects)
    }

    companion object {
        const val FAILURE_TIMEOUT = "timeout"
        const val FAILURE_DISCONNECT = "disconnect"

        /** Reads a rule from wire or stored JSON; throws INVALID_INPUT on values the schema cannot express. */
        fun fromJson(json: JsonObject): MockRule {
            val failure = json.stringOrNull("failure")
            if (failure != null && failure != FAILURE_TIMEOUT && failure != FAILURE_DISCONNECT) {
                invalid("$.failure: expected \"timeout\" or \"disconnect\"")
            }
            val times = json.intOrNull("times")
            if (times != null && times < 1) invalid("$.times: must be positive")
            val delayMs = json.longOrNull("delayMs") ?: 0
            if (delayMs < 0) invalid("$.delayMs: must not be negative")
            val headers = json.objectOrNull("headers")?.mapValues { (name, value) ->
                (value as? JsonPrimitive)?.takeIf { it.isString }?.content ?: invalid("$.headers.$name: expected string")
            } ?: emptyMap()
            return MockRule(
                id = json.stringOrNull("id"),
                method = json.stringOrNull("method"),
                path = json.stringOrNull("path") ?: invalid("$.path: required"),
                status = json.intOrNull("status"),
                headers = headers,
                body = json.stringOrNull("body"),
                template = json.stringOrNull("template"),
                vars = json.objectOrNull("vars"),
                delayMs = delayMs,
                failure = failure,
                times = times,
                remaining = json.intOrNull("remaining") ?: times,
                persist = json.booleanOrNull("persist") ?: false,
                allowAuthSideEffects = json.booleanOrNull("allowAuthSideEffects") ?: false,
            )
        }

        private fun invalid(message: String): Nothing = throw ProbeError(ProbeError.INVALID_INPUT, message)
    }
}

data class MockedResponse(
    val status: Int,
    val headers: Map<String, String> = emptyMap(),
    val body: String? = null,
)

/** Checked when a rule is added. Return a reason to reject it, or null to accept. */
fun interface MockGuard {
    fun check(rule: MockRule): String?
}

/** An app-defined response shape; [render] may throw [ProbeError] for bad vars. */
class MockTemplate(
    val name: String,
    val description: String,
    val render: (vars: JsonObject) -> MockedResponse,
)

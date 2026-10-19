package dev.droidprobe

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Drives a [Dispatcher] through an in-memory [Transport]. */
class DispatcherHarness(core: ProbeCore, scope: CoroutineScope, callTimeoutMs: Long = 10_000) : Transport {
    private val incoming = Channel<String>(Channel.UNLIMITED)
    private val outgoing = Channel<String>(Channel.UNLIMITED)
    private val pending = mutableListOf<JsonObject>()
    val served: Job = scope.launch(Dispatchers.Default) {
        Dispatcher(core, callTimeoutMs, Dispatchers.Default).serve(this@DispatcherHarness)
    }

    override suspend fun readLine(): String? = incoming.receiveCatching().getOrNull()

    override suspend fun writeLine(line: String) {
        check('\n' !in line) { "raw newline in $line" }
        outgoing.send(line)
    }

    fun send(line: String) {
        incoming.trySend(line)
    }

    fun close() {
        incoming.close()
    }

    suspend fun hello(): JsonObject {
        send("""{"id":"h","op":"hello","protocol":"1.0"}""")
        return next("h")
    }

    /** Next response for [id], keeping responses for other ids for later calls. */
    suspend fun next(id: String?): JsonObject = withTimeout(5_000) {
        while (true) {
            val match = pending.firstOrNull { it.idOrNull() == id }
            if (match != null) {
                pending.remove(match)
                return@withTimeout match
            }
            pending += Json.parseToJsonElement(outgoing.receive()).jsonObject
        }
        @Suppress("UNREACHABLE_CODE")
        error("unreachable")
    }

    private fun JsonObject.idOrNull(): String? = (this["id"]?.jsonPrimitive)?.let { if (it.isString) it.content else null }
}

fun JsonObject.type(): String = getValue("type").jsonPrimitive.content
fun JsonObject.errorCode(): String = getValue("error").jsonObject.getValue("code").jsonPrimitive.content
fun JsonObject.errorMessage(): String = getValue("error").jsonObject.getValue("message").jsonPrimitive.content
fun JsonObject.data(): kotlinx.serialization.json.JsonElement = getValue("data")

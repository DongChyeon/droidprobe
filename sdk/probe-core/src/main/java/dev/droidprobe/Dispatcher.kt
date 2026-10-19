package dev.droidprobe

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.concurrent.ConcurrentHashMap

/** One line-oriented connection. [readLine] returns null at end of stream. */
interface Transport {
    suspend fun readLine(): String?
    suspend fun writeLine(line: String)
}

/** Serves the NDJSON protocol over a [Transport]; independent of sockets so it runs on the JVM. */
class Dispatcher(
    private val core: ProbeCore,
    private val callTimeoutMs: Long = DEFAULT_CALL_TIMEOUT_MS,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {

    /** Returns when the peer closes the connection; in-flight calls and watches are cancelled. */
    suspend fun serve(transport: Transport) = coroutineScope {
        val out = Channel<String>(Channel.UNLIMITED)
        launch { for (line in out) transport.writeLine(line) }
        val session = Session(this, out)
        while (true) {
            val line = transport.readLine() ?: break
            if (line.isNotBlank()) session.handle(line)
        }
        coroutineContext.cancelChildren()
    }

    private inner class Session(private val scope: CoroutineScope, private val out: Channel<String>) {
        private var greeted = false
        private val watches = ConcurrentHashMap<String, Job>()

        fun handle(line: String) {
            val request = try {
                Json.parseToJsonElement(line) as? JsonObject
            } catch (_: Exception) {
                null
            }
            if (request == null) return reply(error(null, ProbeError(ProbeError.BAD_REQUEST, "malformed request line")))
            val id = (request["id"] as? JsonPrimitive)?.takeUnless { it is JsonNull }?.content
                ?: return reply(error(null, ProbeError(ProbeError.BAD_REQUEST, "request has no id")))
            val op = request.stringOrNull("op")
            if (!greeted && op != "hello") {
                return reply(error(id, ProbeError(ProbeError.BAD_REQUEST, "the first request must be hello")))
            }
            when (op) {
                "hello" -> {
                    greeted = true
                    // Setup failure does not fail hello; every later request reports SETUP_FAILED.
                    respond(id) { core.ensureSetup(newConnection = true); core.hello() }
                }
                "list" -> respond(id) {
                    requireSetup()
                    buildJsonObject {
                        put("commands", JsonArray(core.registry.all().map { command ->
                            buildJsonObject {
                                put("name", command.name)
                                put("kind", kindOf(command))
                                put("description", command.description)
                            }
                        }))
                    }
                }
                "describe" -> respond(id) {
                    requireSetup()
                    val command = lookup(request.stringOrNull("command"))
                    buildJsonObject {
                        put("name", command.name)
                        put("kind", kindOf(command))
                        put("description", command.description)
                        put("input", command.input.json)
                        put("output", command.output?.json ?: JsonNull)
                    }
                }
                "call" -> respond(id) {
                    requireSetup()
                    val command = lookup(request.stringOrNull("command"))
                    val input = validInput(command, request["input"])
                    if (command !is CallCommand) {
                        throw ProbeError(ProbeError.BAD_REQUEST, "${command.name} is a stream command; use watch")
                    }
                    try {
                        withTimeout(callTimeoutMs) { command.call(input, core.context) }
                    } catch (timeout: TimeoutCancellationException) {
                        throw ProbeError(ProbeError.TIMEOUT, "${command.name} did not finish within ${callTimeoutMs}ms")
                    }
                }
                "watch" -> watch(id, request)
                "cancel" -> cancel(id, request.stringOrNull("target"))
                else -> reply(error(id, ProbeError(ProbeError.BAD_REQUEST, "unknown op '$op'")))
            }
        }

        private fun watch(id: String, request: JsonObject) {
            val job = scope.launch(io, start = CoroutineStart.LAZY) {
                try {
                    requireSetup()
                    val command = lookup(request.stringOrNull("command"))
                    val input = validInput(command, request["input"])
                    if (command !is StreamCommand) {
                        throw ProbeError(ProbeError.BAD_REQUEST, "${command.name} is a call command; use call")
                    }
                    command.stream(input, core.context).collect { data ->
                        out.send(message(id, "event") { put("data", data) })
                    }
                    if (watches.remove(id) != null) out.send(message(id, "end") {})
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Throwable) {
                    if (watches.remove(id) != null) out.send(error(id, ProbeError.from(failure)))
                }
            }
            watches[id] = job
            job.start()
        }

        private fun cancel(id: String, target: String?) {
            val job = target?.let { watches.remove(it) }
                ?: return reply(error(id, ProbeError(ProbeError.BAD_REQUEST, "no running watch '$target'")))
            scope.launch {
                job.cancelAndJoin()
                out.send(message(target, "end") {})
                out.send(result(id, EmptyJsonObject))
            }
        }

        private fun respond(id: String, block: suspend () -> JsonElement) {
            scope.launch(io) {
                val line = try {
                    result(id, block())
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Throwable) {
                    error(id, ProbeError.from(failure))
                }
                out.send(line)
            }
        }

        private fun reply(line: String) {
            out.trySend(line)
        }

        private suspend fun requireSetup() {
            core.ensureSetup()?.let { throw it }
        }

        private fun lookup(name: String?): DebugCommand {
            if (name == null) throw ProbeError(ProbeError.BAD_REQUEST, "request has no command")
            return core.registry[name] ?: throw ProbeError(
                ProbeError.UNKNOWN_COMMAND,
                "no command '$name'" + (core.registry.suggest(name)?.let { "; did you mean '$it'?" } ?: ""),
            )
        }

        private fun validInput(command: DebugCommand, raw: JsonElement?): JsonObject {
            val input = when (raw) {
                null, JsonNull -> EmptyJsonObject
                is JsonObject -> raw
                else -> throw ProbeError(ProbeError.INVALID_INPUT, "$: expected object")
            }
            command.input.validate(input)?.let { throw ProbeError(ProbeError.INVALID_INPUT, it) }
            return input
        }
    }

    private fun kindOf(command: DebugCommand): String = when (command) {
        is CallCommand -> "call"
        is StreamCommand -> "stream"
    }

    private fun result(id: String, data: JsonElement): String = message(id, "result") { put("data", data) }

    private fun error(id: String?, error: ProbeError): String = message(id, "error") {
        put("error", buildJsonObject {
            put("code", error.code)
            put("message", error.message ?: error.code)
        })
    }

    private inline fun message(
        id: String?,
        type: String,
        body: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit,
    ): String = buildJsonObject {
        put("id", id?.let(::JsonPrimitive) ?: JsonNull)
        put("type", type)
        body()
    }.toString()

    companion object {
        const val DEFAULT_CALL_TIMEOUT_MS = 10_000L
    }
}

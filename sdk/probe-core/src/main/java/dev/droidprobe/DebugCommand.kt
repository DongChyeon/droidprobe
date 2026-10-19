package dev.droidprobe

import android.app.Application
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/** The single extension unit. Implement [CallCommand] or [StreamCommand]. */
sealed interface DebugCommand {
    val name: String
    val description: String
    val input: Schema
    val output: Schema?
}

interface CallCommand : DebugCommand {
    suspend fun call(input: JsonObject, ctx: CommandContext): JsonElement
}

/** The flow is collected only while the host watches; cancelling the watch cancels collection. */
interface StreamCommand : DebugCommand {
    fun stream(input: JsonObject, ctx: CommandContext): Flow<JsonElement>
}

interface CommandContext {
    val app: Application
    val journal: Journal
    suspend fun <T> onMain(block: () -> T): T
}

fun callCommand(
    name: String,
    description: String,
    input: Schema = Schema.anyObject(),
    output: Schema? = null,
    block: suspend (input: JsonObject, ctx: CommandContext) -> JsonElement,
): CallCommand {
    val commandName = name
    val commandDescription = description
    val inputSchema = input
    val outputSchema = output
    return object : CallCommand {
        override val name = commandName
        override val description = commandDescription
        override val input = inputSchema
        override val output = outputSchema
        override suspend fun call(input: JsonObject, ctx: CommandContext): JsonElement = block(input, ctx)
    }
}

fun streamCommand(
    name: String,
    description: String,
    input: Schema = Schema.anyObject(),
    output: Schema? = null,
    block: (input: JsonObject, ctx: CommandContext) -> Flow<JsonElement>,
): StreamCommand {
    val commandName = name
    val commandDescription = description
    val inputSchema = input
    val outputSchema = output
    return object : StreamCommand {
        override val name = commandName
        override val description = commandDescription
        override val input = inputSchema
        override val output = outputSchema
        override fun stream(input: JsonObject, ctx: CommandContext): Flow<JsonElement> = block(input, ctx)
    }
}

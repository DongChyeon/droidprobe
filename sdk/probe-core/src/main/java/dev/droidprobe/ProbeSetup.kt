package dev.droidprobe

import android.app.Application
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * The app's probe configuration, named by `<meta-data android:name="dev.droidprobe.SETUP">`.
 * [config] is read at process start; [setup] runs once, on the first host connection.
 */
interface ProbeSetup {
    fun config(): ProbeConfig = ProbeConfig()
    fun setup(probe: ProbeRegistrar, app: Application)
}

data class ProbeConfig(
    val recordingEnabled: () -> Boolean = { true },
    val networkCapacity: Int = 500,
    val eventCapacity: Int = 2000,
    val bodyLimitBytes: Int = 256 * 1024,
    val redactHeaders: Set<String> = emptySet(),
    /** Whether probe-lifecycle appends each transition to the journal as a `lifecycle.*` event. */
    val lifecycleJournal: Boolean = true,
)

class ProbeRegistrar internal constructor(private val registry: CommandRegistry) {
    private val rollbacks = mutableListOf<() -> Unit>()

    fun register(command: DebugCommand) {
        registry.register(command)
        onRollback { registry.unregister(command.name) }
    }

    /** Undoes a registration made by an extension if this setup attempt fails, so a retry starts clean. */
    fun onRollback(action: () -> Unit) {
        rollbacks += action
    }

    internal fun rollback() {
        rollbacks.asReversed().forEach { runCatching(it) }
        rollbacks.clear()
    }

    fun call(
        name: String,
        description: String = "",
        input: Schema = Schema.anyObject(),
        output: Schema? = null,
        block: suspend (input: JsonObject, ctx: CommandContext) -> JsonElement,
    ) = register(callCommand(name, description, input, output, block))

    /** Registers `<name>.state` (current value) and `<name>.state.watch` (current value, then distinct changes). */
    fun state(name: String, flow: Flow<JsonElement>, description: String = "$name state") {
        register(callCommand("$name.state", description) { _, _ ->
            withTimeoutOrNull(STATE_TIMEOUT_MS) { flow.firstOrNull() }
                ?: throw ProbeError(ProbeError.TIMEOUT, "$name.state produced no value within ${STATE_TIMEOUT_MS}ms")
        })
        register(streamCommand("$name.state.watch", "$description (current value, then changes)") { _, _ ->
            flow.distinctUntilChanged()
        })
    }

    private companion object {
        const val STATE_TIMEOUT_MS = 2_000L
    }
}

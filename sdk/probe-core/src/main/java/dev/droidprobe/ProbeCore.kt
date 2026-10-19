package dev.droidprobe

import android.app.Application
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.Random

/**
 * Process-wide probe state: journal, registry, runtime settings and the one-time app setup.
 * [Probe.core] is the instance the app uses; tests build their own.
 */
class ProbeCore(val processStartId: String = newProcessStartId()) {

    val journal = Journal(processStartId)
    val registry = CommandRegistry()

    @Volatile
    var packageName: String = ""

    @Volatile
    var pid: Int = 0

    @Volatile
    var config: ProbeConfig = ProbeConfig()
        private set

    @Volatile
    var bodyLimitBytes: Int = config.bodyLimitBytes
        private set

    @Volatile
    private var recordingOverride: Boolean? = null

    @Volatile
    private var application: Application? = null

    private val setupLock = Mutex()
    private var setupRunner: ((ProbeRegistrar) -> Unit)? = null
    private var setupAttempts = 0
    private var setupSucceeded = false
    private var setupFatal = false

    @Volatile
    private var setupFailure: String? = null

    val context: CommandContext = object : CommandContext {
        override val app: Application
            get() = application ?: throw ProbeError(ProbeError.INTERNAL, "probe is not attached to an Application")
        override val journal: Journal get() = this@ProbeCore.journal
        override suspend fun <T> onMain(block: () -> T): T = withContext(Dispatchers.Main) { block() }
    }

    val recording: Boolean
        get() = recordingOverride ?: runCatching { config.recordingEnabled() }.getOrDefault(true)

    init {
        builtInCommands().forEach(registry::registerBuiltIn)
    }

    fun attach(app: Application) {
        application = app
    }

    fun applyConfig(config: ProbeConfig) {
        this.config = config
        bodyLimitBytes = config.bodyLimitBytes
        journal.setCapacity(Journal.Kind.NETWORK, config.networkCapacity)
        journal.setCapacity(Journal.Kind.EVENT, config.eventCapacity)
    }

    /**
     * [runner] runs on the first connection. If it throws, its registrations are rolled back, requests
     * fail with SETUP_FAILED, and the next connection retries, up to [MAX_SETUP_ATTEMPTS] attempts.
     */
    fun setSetup(runner: (ProbeRegistrar) -> Unit) {
        setupRunner = runner
    }

    /** A failure no retry can fix, such as a setup class that cannot be loaded. */
    fun failSetup(message: String) {
        setupFatal = true
        setupFailure = message
    }

    /** Runs the app setup unless it already succeeded; [newConnection] allows retrying an earlier failure. */
    suspend fun ensureSetup(newConnection: Boolean = false): ProbeError? {
        setupLock.withLock {
            val runner = setupRunner
            val due = setupAttempts == 0 || (newConnection && setupAttempts < MAX_SETUP_ATTEMPTS)
            if (runner != null && !setupSucceeded && !setupFatal && due) {
                setupAttempts++
                val registrar = ProbeRegistrar(registry)
                try {
                    runner(registrar)
                    setupSucceeded = true
                    setupFailure = null
                } catch (error: Throwable) {
                    registrar.rollback()
                    val retry = if (setupAttempts < MAX_SETUP_ATTEMPTS) "retried on the next connection" else "no more retries"
                    setupFailure = "${error.javaClass.name}: ${error.message} " +
                        "(attempt $setupAttempts of $MAX_SETUP_ATTEMPTS, $retry)"
                }
            }
        }
        return setupFailure?.let { ProbeError(ProbeError.SETUP_FAILED, "ProbeSetup failed: $it") }
    }

    /** Never throws and never blocks on I/O; dropped when recording is off. */
    fun recordEvent(name: String, params: JsonObject, source: String?) {
        try {
            if (!recording) return
            journal.append(Journal.Kind.EVENT, buildJsonObject {
                put("name", name)
                put("source", source?.let(::JsonPrimitive) ?: JsonNull)
                put("params", params)
            })
        } catch (_: Throwable) {
        }
    }

    fun hello(): JsonObject = buildJsonObject {
        put("protocol", PROTOCOL_VERSION)
        put("sdk", SDK_VERSION)
        put("package", packageName)
        put("pid", pid)
        put("processStartId", processStartId)
        put("headCursor", journal.headCursor)
    }

    private fun configJson(): JsonObject = buildJsonObject {
        put("networkCapacity", journal.capacity(Journal.Kind.NETWORK))
        put("eventCapacity", journal.capacity(Journal.Kind.EVENT))
        put("bodyLimitBytes", bodyLimitBytes)
    }

    private fun builtInCommands(): List<DebugCommand> = listOf(
        callCommand("probe.info", "Probe, process and journal configuration", Schema.obj()) { _, _ ->
            JsonObject(hello() + mapOf("recording" to JsonPrimitive(recording), "config" to configJson()))
        },
        callCommand(
            "probe.config",
            "Changes journal capacity, body limit or recording at runtime",
            Schema.obj(
                "journalCapacity" to Schema.integer("Capacity of every journal kind"),
                "bodyLimitBytes" to Schema.integer("Largest body stored per record"),
                "recording" to Schema.boolean("Whether network and events are recorded"),
            ),
        ) { input, _ ->
            input.intOrNull("journalCapacity")?.let { capacity ->
                if (capacity < 1) throw ProbeError(ProbeError.INVALID_INPUT, "$.journalCapacity: must be positive")
                journal.setCapacity(Journal.Kind.NETWORK, capacity)
                journal.setCapacity(Journal.Kind.EVENT, capacity)
            }
            input.intOrNull("bodyLimitBytes")?.let { limit ->
                if (limit < 0) throw ProbeError(ProbeError.INVALID_INPUT, "$.bodyLimitBytes: must not be negative")
                bodyLimitBytes = limit
            }
            input.booleanOrNull("recording")?.let { recordingOverride = it }
            JsonObject(configJson() + ("recording" to JsonPrimitive(recording)))
        },
        callCommand(
            "events.records",
            "Recorded app events after a cursor",
            Schema.obj(
                "since" to Schema.string("Cursor; only later entries are returned"),
                "limit" to Schema.integer("Maximum records, oldest first (default 200)"),
                "nameContains" to Schema.string("Substring of the event name"),
                "strict" to Schema.boolean("Fail with CURSOR_EVICTED instead of returning truncated results"),
            ),
        ) { input, _ ->
            val nameContains = input.stringOrNull("nameContains")
            journal.records(
                Journal.Kind.EVENT,
                since = input.stringOrNull("since"),
                limit = limitOf(input),
                strict = input.booleanOrNull("strict") ?: false,
                filter = { nameContains == null || it.stringOrNull("name")?.contains(nameContains) == true },
            )
        },
        streamCommand(
            "events.watch",
            "Live app events; with since, the retained backlog first",
            Schema.obj(
                "since" to Schema.string("Cursor; backlog after it is sent first"),
                "nameContains" to Schema.string("Substring of the event name"),
            ),
        ) { input, _ ->
            val nameContains = input.stringOrNull("nameContains")
            journal.watch(Journal.Kind.EVENT, input.stringOrNull("since")) {
                nameContains == null || it.stringOrNull("name")?.contains(nameContains) == true
            }
        },
    )

    companion object {
        const val PROTOCOL_VERSION = "1.0"
        const val SDK_VERSION = "0.1.0"
        const val DEFAULT_LIMIT = 200
        const val MAX_SETUP_ATTEMPTS = 5

        fun newProcessStartId(): String = "%08x".format(Random().nextInt())

        /** Reads `limit` (default 200) and rejects non-positive values. */
        fun limitOf(input: JsonObject): Int {
            val limit = input.intOrNull("limit") ?: DEFAULT_LIMIT
            if (limit < 1) throw ProbeError(ProbeError.INVALID_INPUT, "$.limit: must be positive")
            return limit
        }
    }
}

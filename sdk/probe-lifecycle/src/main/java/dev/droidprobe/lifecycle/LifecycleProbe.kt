package dev.droidprobe.lifecycle

import dev.droidprobe.DebugCommand
import dev.droidprobe.ProbeCore
import dev.droidprobe.ProbeError
import dev.droidprobe.Schema
import dev.droidprobe.callCommand
import dev.droidprobe.intOrNull
import dev.droidprobe.objectOrNull
import dev.droidprobe.streamCommand
import dev.droidprobe.stringOrNull
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.filter
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** What [LifecycleProbe] needs from Android; everything is called on the main thread. */
interface LifecycleHost {
    fun describe(entry: LifecycleTracker.Entry, activity: Any): ActivitySnapshot
    fun fragments(activity: Any, states: LifecycleTracker): List<FragmentNode>
    fun process(): ProcessSnapshot

    /** Throws INVALID_INPUT when [className] is not an Activity declared by this app. */
    fun startActivity(className: String, extras: Map<String, ExtraValue>, flags: Int, from: Any?)
}

/** Activity, fragment and process lifecycle bound to one [ProbeCore]; [ProbeLifecycle.probe] is the app's instance. */
class LifecycleProbe(
    val core: ProbeCore,
    private val host: LifecycleHost,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    val activities = LifecycleTracker("a")
    val fragments = LifecycleTracker("f")

    private val transitions = MutableSharedFlow<JsonObject>(extraBufferCapacity = 256, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private val lock = Any()
    private var startedCount = 0
    private var configChangePending = false

    @Volatile
    var processName: String = ""

    /** [changingConfigurations] is the Activity's flag at onStop, so a rotation is not reported as backgrounding. */
    fun onActivity(activity: Any, className: String, to: String, changingConfigurations: Boolean = false) {
        val stored = if (to == LifecycleState.DESTROYED) LifecycleState.DESTROYED_PENDING else to
        val (entry, from) = activities.move(activity, className, stored)
        emit(KIND_ACTIVITY, entry.id, className, from?.let(::reported), to)
        val processChange = synchronized(lock) {
            val before = startedCount > 0
            when (to) {
                LifecycleState.STARTED -> if (configChangePending) configChangePending = false else startedCount++
                LifecycleState.STOPPED -> if (changingConfigurations) configChangePending = true else startedCount--
            }
            startedCount = startedCount.coerceAtLeast(0)
            (startedCount > 0).takeIf { it != before }
        }
        if (processChange != null) {
            val (from, to) = if (processChange) LifecycleState.BACKGROUND to LifecycleState.FOREGROUND else
                LifecycleState.FOREGROUND to LifecycleState.BACKGROUND
            emit(KIND_PROCESS, null, processName, from, to)
        }
    }

    fun onFragment(fragment: Any, className: String, to: String) {
        if (to == LifecycleState.DESTROYED) {
            val entry = fragments.remove(fragment)
            emit(KIND_FRAGMENT, entry?.id, className, entry?.state, to)
        } else {
            val (entry, from) = fragments.move(fragment, className, to)
            emit(KIND_FRAGMENT, entry.id, className, from, to)
        }
    }

    /** The Activity in the resumed state, if any. */
    fun resumed(): Any? = activities.live().lastOrNull { it.state == LifecycleState.RESUMED }?.target

    /** Live Activities ordered by task, then creation; destroyed ones excluded. */
    fun liveActivities(): List<Any> = snapshots().filter { it.state != LifecycleState.DESTROYED_PENDING }
        .mapNotNull { activities.find(it.id)?.target }

    private fun snapshots(): List<ActivitySnapshot> = ActivitySnapshot.ordered(
        activities.live().mapNotNull { entry -> entry.target?.let { host.describe(entry, it) } },
    )

    private fun reported(state: String) = if (state == LifecycleState.DESTROYED_PENDING) LifecycleState.DESTROYED else state

    private fun emit(kind: String, id: String?, className: String, from: String?, to: String) {
        val transition = buildJsonObject {
            put("kind", kind)
            put("id", id)
            put("class", className)
            put("from", from)
            put("to", to)
            put("ts", clock())
        }
        transitions.tryEmit(transition)
        if (core.config.lifecycleJournal) {
            core.recordEvent(
                "lifecycle.$kind.$to",
                buildJsonObject {
                    put("class", className)
                    put("from", from)
                    put("to", to)
                    put("id", id)
                },
                SOURCE,
            )
        }
    }

    fun commands(): List<DebugCommand> = listOf(
        callCommand(
            "lifecycle.activities",
            "Live Activities ordered by task, then creation; the resumed one is marked",
            Schema.obj(),
            Schema.array(ACTIVITY_SCHEMA),
        ) { _, ctx ->
            ctx.onMain {
                val resumed = resumed()
                JsonArray(snapshots().map { it.toJson(resumed != null && activities.find(it.id)?.target === resumed) })
            }
        },
        callCommand(
            "lifecycle.fragments",
            "Fragment tree of one Activity (default: the resumed one)",
            Schema.obj("activityId" to Schema.string("id from lifecycle.activities")),
            Schema.obj(
                "activityId" to Schema.string(),
                "class" to Schema.string(),
                "fragments" to Schema.array(FRAGMENT_SCHEMA),
                required = listOf("activityId", "class", "fragments"),
            ),
        ) { input, ctx ->
            val id = input.stringOrNull("activityId")
            ctx.onMain {
                val entry = if (id != null) {
                    activities.find(id)?.takeIf { it.state != LifecycleState.DESTROYED_PENDING }
                        ?: invalid("$.activityId: no live Activity '$id'")
                } else {
                    val resumed = resumed() ?: invalid("$.activityId: no resumed Activity; pass an id from lifecycle.activities")
                    activities.entryOf(resumed) ?: invalid("$.activityId: the resumed Activity is not tracked")
                }
                val activity = entry.target ?: invalid("$.activityId: Activity '${entry.id}' was collected")
                buildJsonObject {
                    put("activityId", entry.id)
                    put("class", entry.className)
                    put("fragments", JsonArray(host.fragments(activity, fragments).map { it.toJson() }))
                }
            }
        },
        callCommand(
            "lifecycle.process",
            "Process importance, trim level, uptime and standby bucket",
            Schema.obj(),
            PROCESS_SCHEMA,
        ) { _, _ -> host.process().toJson() },
        streamCommand(
            "lifecycle.watch",
            "Live Activity, fragment and process lifecycle transitions",
            Schema.obj("kind" to Schema.string("Only this kind", enum = KINDS)),
            TRANSITION_SCHEMA,
        ) { input, _ ->
            val kind = input.stringOrNull("kind")
            transitions.filter { kind == null || it["kind"] == JsonPrimitive(kind) }
        },
        callCommand(
            "lifecycle.startActivity",
            "Starts an Activity of this app from inside the process, so non-exported ones work too",
            Schema.obj(
                "className" to Schema.string("Fully qualified Activity class declared by this app"),
                "extras" to Schema(buildJsonObject { put("type", "object"); put("description", "string, integer or boolean values") }),
                "flags" to Schema.integer("Intent flags added to the Intent"),
                required = listOf("className"),
            ),
            Schema.obj("started" to Schema.boolean(), required = listOf("started")),
        ) { input, ctx ->
            val className = input.stringOrNull("className")!!
            val extras = ExtraValue.parse(input.objectOrNull("extras"))
            val flags = input.intOrNull("flags") ?: 0
            ctx.onMain { host.startActivity(className, extras, flags, resumed()) }
            buildJsonObject { put("started", true) }
        },
    )

    companion object {
        const val SOURCE = "lifecycle"
        const val KIND_ACTIVITY = "activity"
        const val KIND_FRAGMENT = "fragment"
        const val KIND_PROCESS = "process"
        val KINDS = listOf(KIND_ACTIVITY, KIND_FRAGMENT, KIND_PROCESS)

        private val STRINGS = Schema.array(Schema.string())

        val INTENT_SCHEMA = Schema.obj(
            "action" to Schema.string(nullable = true),
            "component" to Schema.string(nullable = true),
            "flags" to Schema.integer(),
            "extraKeys" to STRINGS,
            "data" to Schema.string(nullable = true),
            required = listOf("action", "component", "flags", "extraKeys", "data"),
            nullable = true,
        )

        val ACTIVITY_SCHEMA = Schema.obj(
            "id" to Schema.string(),
            "class" to Schema.string(),
            "state" to Schema.string(
                enum = listOf(
                    LifecycleState.CREATED, LifecycleState.STARTED, LifecycleState.RESUMED,
                    LifecycleState.PAUSED, LifecycleState.STOPPED, LifecycleState.DESTROYED_PENDING,
                ),
            ),
            "resumed" to Schema.boolean(),
            "taskId" to Schema.integer(),
            "isFinishing" to Schema.boolean(),
            "intent" to INTENT_SCHEMA,
            required = listOf("id", "class", "state", "resumed", "taskId", "isFinishing", "intent"),
        )

        // Recursive: children use the same shape, which this schema subset cannot reference.
        val FRAGMENT_SCHEMA = Schema.obj(
            "class" to Schema.string(),
            "tag" to Schema.string(nullable = true),
            "id" to Schema.integer("Container view id, 0 when none"),
            "state" to Schema.string(),
            "isAdded" to Schema.boolean(),
            "isVisible" to Schema.boolean(),
            "isHidden" to Schema.boolean(),
            "isRemoving" to Schema.boolean(),
            "argumentsKeys" to STRINGS,
            "children" to Schema.array(Schema.anyObject("Fragment, same shape")),
            required = listOf("class", "tag", "id", "state", "isAdded", "isVisible", "isHidden", "isRemoving", "argumentsKeys", "children"),
        )

        val PROCESS_SCHEMA = Schema.obj(
            "pid" to Schema.integer(),
            "processName" to Schema.string(nullable = true),
            "importance" to Schema.integer("ActivityManager.RunningAppProcessInfo.importance"),
            "importanceName" to Schema.string(),
            "lastTrimLevel" to Schema.integer(),
            "uptimeMs" to Schema.integer(),
            "isForeground" to Schema.boolean(),
            "appStandbyBucket" to Schema.integer("API 28+, else null", nullable = true),
            required = listOf("pid", "processName", "importance", "importanceName", "lastTrimLevel", "uptimeMs", "isForeground", "appStandbyBucket"),
        )

        val TRANSITION_SCHEMA = Schema.obj(
            "kind" to Schema.string(enum = KINDS),
            "id" to Schema.string("Activity or fragment id; null for process", nullable = true),
            "class" to Schema.string("Class name, or the process name"),
            "from" to Schema.string("null when first seen", nullable = true),
            "to" to Schema.string(),
            "ts" to Schema.integer("Epoch milliseconds"),
            required = listOf("kind", "id", "class", "from", "to", "ts"),
        )

        fun invalid(message: String): Nothing = throw ProbeError(ProbeError.INVALID_INPUT, message)
    }
}

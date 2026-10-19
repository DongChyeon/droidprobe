package dev.droidprobe.lifecycle

import dev.droidprobe.ProbeError
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/** Only extra and argument keys are exposed: their values can hold tokens. */
data class IntentSnapshot(
    val action: String?,
    val component: String?,
    val flags: Int,
    val extraKeys: List<String>,
    val data: String?,
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("action", action)
        put("component", component)
        put("flags", flags)
        put("extraKeys", strings(extraKeys))
        put("data", data)
    }
}

data class ActivitySnapshot(
    val id: String,
    val order: Long,
    val className: String,
    val state: String,
    val taskId: Int,
    val isFinishing: Boolean,
    val intent: IntentSnapshot?,
) {
    fun toJson(resumed: Boolean): JsonObject = buildJsonObject {
        put("id", id)
        put("class", className)
        put("state", state)
        put("resumed", resumed)
        put("taskId", taskId)
        put("isFinishing", isFinishing)
        put("intent", intent?.toJson() ?: JsonNull)
    }

    companion object {
        /** Groups by task (tasks in the order their first Activity was created), then creation order. */
        fun ordered(activities: List<ActivitySnapshot>): List<ActivitySnapshot> {
            val taskOrder = activities.groupBy { it.taskId }.mapValues { (_, list) -> list.minOf { it.order } }
            return activities.sortedWith(compareBy({ taskOrder.getValue(it.taskId) }, { it.order }))
        }
    }
}

data class FragmentNode(
    val className: String,
    val tag: String?,
    val id: Int,
    val state: String,
    val isAdded: Boolean,
    val isVisible: Boolean,
    val isHidden: Boolean,
    val isRemoving: Boolean,
    val argumentsKeys: List<String>,
    val children: List<FragmentNode>,
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("class", className)
        put("tag", tag)
        put("id", id)
        put("state", state)
        put("isAdded", isAdded)
        put("isVisible", isVisible)
        put("isHidden", isHidden)
        put("isRemoving", isRemoving)
        put("argumentsKeys", strings(argumentsKeys))
        put("children", JsonArray(children.map { it.toJson() }))
    }
}

data class ProcessSnapshot(
    val pid: Int,
    val processName: String?,
    val importance: Int,
    val lastTrimLevel: Int,
    val uptimeMs: Long,
    val appStandbyBucket: Int?,
) {
    val isForeground: Boolean get() = importance == IMPORTANCE_FOREGROUND

    fun toJson(): JsonObject = buildJsonObject {
        put("pid", pid)
        put("processName", processName)
        put("importance", importance)
        put("importanceName", importanceName(importance))
        put("lastTrimLevel", lastTrimLevel)
        put("uptimeMs", uptimeMs)
        put("isForeground", isForeground)
        put("appStandbyBucket", appStandbyBucket)
    }

    companion object {
        // Values of ActivityManager.RunningAppProcessInfo.IMPORTANCE_*.
        const val IMPORTANCE_FOREGROUND = 100

        fun importanceName(importance: Int): String = when (importance) {
            100 -> "foreground"
            125 -> "foreground_service"
            150, 325 -> "top_sleeping"
            200 -> "visible"
            130, 230 -> "perceptible"
            300 -> "service"
            170, 350 -> "cant_save_state"
            400 -> "cached"
            1000 -> "gone"
            else -> "unknown"
        }
    }
}

/** A typed Intent extra parsed from `lifecycle.startActivity` input. */
sealed class ExtraValue {
    data class Text(val value: String) : ExtraValue()
    data class IntValue(val value: Int) : ExtraValue()
    data class LongValue(val value: Long) : ExtraValue()
    data class Bool(val value: Boolean) : ExtraValue()

    companion object {
        /** Strings, booleans and integers (Int when it fits, else Long); anything else is INVALID_INPUT. */
        fun parse(extras: JsonObject?): Map<String, ExtraValue> = extras.orEmpty().mapValues { (key, value) ->
            val primitive = value as? JsonPrimitive
            when {
                primitive == null || primitive is JsonNull -> null
                primitive.isString -> Text(primitive.content)
                primitive.booleanOrNull != null -> Bool(primitive.booleanOrNull!!)
                primitive.longOrNull != null -> primitive.longOrNull!!.let {
                    if (it in Int.MIN_VALUE..Int.MAX_VALUE) IntValue(it.toInt()) else LongValue(it)
                }
                else -> null
            } ?: throw ProbeError(ProbeError.INVALID_INPUT, "$.extras.$key: expected string, integer or boolean")
        }
    }
}

private fun strings(values: List<String>) = JsonArray(values.map(::JsonPrimitive))

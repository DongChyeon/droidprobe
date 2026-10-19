package dev.droidprobe.okhttp

import dev.droidprobe.DebugCommand
import dev.droidprobe.EmptyJsonObject
import dev.droidprobe.Journal
import dev.droidprobe.ProbeCore
import dev.droidprobe.ProbeError
import dev.droidprobe.Schema
import dev.droidprobe.booleanOrNull
import dev.droidprobe.callCommand
import dev.droidprobe.intOrNull
import dev.droidprobe.objectOrNull
import dev.droidprobe.streamCommand
import dev.droidprobe.stringOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.Interceptor
import java.util.concurrent.atomic.AtomicLong

/** Network recording and mocking bound to one [ProbeCore]; [ProbeOkHttp.network] is the app's instance. */
class NetworkProbe(val core: ProbeCore, persistence: MockPersistence? = null) {

    @Volatile
    var persistence: MockPersistence? = persistence

    val store = MockStore({ this.persistence })

    private val recordIds = AtomicLong()
    private val guards = mutableListOf<MockGuard>()
    private val templates = LinkedHashMap<String, MockTemplate>()

    fun interceptor(): Interceptor = ProbeInterceptor(this)

    internal fun nextRecordId(): String = "n-${recordIds.incrementAndGet()}"

    @Synchronized
    fun addGuard(guard: MockGuard) {
        guards += guard
    }

    @Synchronized
    internal fun removeGuard(guard: MockGuard) {
        guards -= guard
    }

    @Synchronized
    internal fun removeTemplate(name: String) {
        templates.remove(name)
    }

    @Synchronized
    fun addTemplate(template: MockTemplate) {
        require(template.name !in templates) { "Mock template ${template.name} is already registered" }
        templates[template.name] = template
    }

    /** Resolves the template, runs the guards and stores the rule. */
    fun addRule(input: JsonObject): MockRule {
        val requested = MockRule.fromJson(input).copy(id = null)
        val rule = resolve(requested)
        val guards = synchronized(this) { guards.toList() }
        for (guard in guards) {
            val reason = guard.check(rule) ?: continue
            throw ProbeError(ProbeError.MOCK_REJECTED, reason)
        }
        return store.add(rule)
    }

    private fun resolve(rule: MockRule): MockRule {
        if (rule.vars != null && rule.template == null) invalid("$.vars: requires template")
        if (rule.failure != null) return rule
        val name = rule.template ?: return if (rule.status == null) rule.copy(status = 200) else rule
        if (rule.status != null || rule.body != null) invalid("$.template: cannot be combined with status or body")
        val template = synchronized(this) { templates[name] } ?: invalid(
            "$.template: no template '$name' (available: ${synchronized(this) { templates.keys.joinToString() }})",
        )
        val response = try {
            template.render(rule.vars ?: EmptyJsonObject)
        } catch (error: ProbeError) {
            throw error
        } catch (error: Exception) {
            invalid("$.vars: template '$name' failed: ${error.message}")
        }
        return rule.copy(status = response.status, headers = response.headers + rule.headers, body = response.body)
    }

    fun commands(): List<DebugCommand> = listOf(
        callCommand(
            "network.records",
            "Recorded HTTP exchanges after a cursor; bodies over 4096 chars are omitted",
            Schema.obj(
                "since" to Schema.string("Cursor; only later entries are returned"),
                "limit" to Schema.integer("Maximum records, oldest first (default 200)"),
                "filter" to FILTER_SCHEMA,
                "strict" to Schema.boolean("Fail with CURSOR_EVICTED instead of returning truncated results"),
            ),
        ) { input, _ ->
            core.journal.records(
                Journal.Kind.NETWORK,
                since = input.stringOrNull("since"),
                limit = ProbeCore.limitOf(input),
                strict = input.booleanOrNull("strict") ?: false,
                filter = filterOf(input.objectOrNull("filter")),
                transform = ::omitLargeBodies,
            )
        },
        callCommand(
            "network.record",
            "One recorded HTTP exchange with full stored bodies",
            Schema.obj("id" to Schema.string("Record id, for example n-12"), required = listOf("id")),
        ) { input, _ ->
            val id = input.stringOrNull("id")
            core.journal.find(Journal.Kind.NETWORK) { it.stringOrNull("id") == id }
                ?: invalid("$.id: no retained network record '$id'")
        },
        streamCommand(
            "network.watch",
            "Live HTTP exchanges; with since, the retained backlog first",
            Schema.obj("since" to Schema.string("Cursor; backlog after it is sent first"), "filter" to FILTER_SCHEMA),
        ) { input, _ ->
            core.journal.watch(Journal.Kind.NETWORK, input.stringOrNull("since"), filterOf(input.objectOrNull("filter")))
        },
        callCommand("network.mock.add", "Adds a mock rule; the app's guard may reject it", RULE_SCHEMA) { input, _ ->
            buildJsonObject { put("id", addRule(input).id) }
        },
        callCommand("network.mock.list", "Mock rules in match order", Schema.obj()) { _, _ ->
            JsonArray(store.list().map { it.toJson() })
        },
        callCommand(
            "network.mock.remove",
            "Removes one mock rule",
            Schema.obj("id" to Schema.string(), required = listOf("id")),
        ) { input, _ ->
            buildJsonObject { put("removed", store.remove(input.stringOrNull("id")!!)) }
        },
        callCommand("network.mock.clear", "Removes every mock rule, persisted ones included", Schema.obj()) { _, _ ->
            buildJsonObject { put("removed", store.clear()) }
        },
        callCommand("network.mock.templates", "Response templates registered by the app", Schema.obj()) { _, _ ->
            val all = synchronized(this) { templates.values.toList() }
            JsonArray(all.map { buildJsonObject { put("name", it.name); put("description", it.description) } })
        },
    )

    private fun filterOf(filter: JsonObject?): (JsonObject) -> Boolean {
        if (filter == null) return { true }
        val method = filter.stringOrNull("method")
        val urlContains = filter.stringOrNull("urlContains")
        val status = filter.intOrNull("status")
        val mocked = filter.booleanOrNull("mocked")
        return { record ->
            (method == null || record.stringOrNull("method").equals(method, ignoreCase = true)) &&
                (urlContains == null || record.stringOrNull("url")?.contains(urlContains) == true) &&
                (status == null || record.intOrNull("status") == status) &&
                (mocked == null || record.booleanOrNull("mocked") == mocked)
        }
    }

    private fun omitLargeBodies(record: JsonObject): JsonObject {
        val large = BODY_FIELDS.filter { (record.stringOrNull(it)?.length ?: 0) > LIST_BODY_LIMIT }
        if (large.isEmpty()) return record
        return JsonObject(record + large.associateWith { JsonNull } + ("bodyOmitted" to JsonPrimitive(true)))
    }

    private companion object {
        const val LIST_BODY_LIMIT = 4096
        val BODY_FIELDS = listOf("requestBody", "responseBody")

        val FILTER_SCHEMA = Schema.obj(
            "method" to Schema.string(),
            "urlContains" to Schema.string(),
            "status" to Schema.integer(),
            "mocked" to Schema.boolean(),
        )

        val RULE_SCHEMA = Schema.obj(
            "method" to Schema.string("HTTP method; absent matches any", nullable = true),
            "path" to Schema.string("Glob over the encoded path: * is one segment, ** any number"),
            "status" to Schema.integer(nullable = true),
            "headers" to Schema(buildJsonObject { put("type", JsonArray(listOf(JsonPrimitive("object"), JsonPrimitive("null")))) }),
            "body" to Schema.string(nullable = true),
            "template" to Schema.string("Name from network.mock.templates", nullable = true),
            "vars" to Schema(buildJsonObject { put("type", JsonArray(listOf(JsonPrimitive("object"), JsonPrimitive("null")))) }),
            "delayMs" to Schema.integer(nullable = true),
            "failure" to Schema.string("timeout or disconnect", nullable = true),
            "times" to Schema.integer("Remove the rule after this many matches", nullable = true),
            "persist" to Schema.boolean("Keep the rule across process restarts"),
            "allowAuthSideEffects" to Schema.boolean("Let the app's guard allow responses with auth side effects"),
            required = listOf("path"),
        )

        fun invalid(message: String): Nothing = throw ProbeError(ProbeError.INVALID_INPUT, message)
    }
}

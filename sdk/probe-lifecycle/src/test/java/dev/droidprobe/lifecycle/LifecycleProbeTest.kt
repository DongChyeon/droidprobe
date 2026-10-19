package dev.droidprobe.lifecycle

import android.app.Application
import dev.droidprobe.CallCommand
import dev.droidprobe.CommandContext
import dev.droidprobe.CommandRegistry
import dev.droidprobe.EmptyJsonObject
import dev.droidprobe.Journal
import dev.droidprobe.ProbeConfig
import dev.droidprobe.ProbeCore
import dev.droidprobe.ProbeError
import dev.droidprobe.StreamCommand
import dev.droidprobe.callCommand
import dev.droidprobe.stringOrNull
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class LifecycleProbeTest {
    private class FakeActivity(val taskId: Int, val finishing: Boolean = false)
    private class FakeFragment

    private class FakeHost : LifecycleHost {
        var fragments: List<FragmentNode> = emptyList()
        var started: Triple<String, Map<String, ExtraValue>, Any?>? = null

        override fun describe(entry: LifecycleTracker.Entry, activity: Any): ActivitySnapshot {
            val fake = activity as FakeActivity
            return ActivitySnapshot(
                entry.id, entry.order, entry.className, entry.state, fake.taskId, fake.finishing,
                IntentSnapshot("android.intent.action.MAIN", "pkg/.Main", 0x10000000, listOf("token"), null),
            )
        }

        override fun fragments(activity: Any, states: LifecycleTracker) = fragments

        override fun process() = ProcessSnapshot(42, "pkg", 100, 0, 1234, 10)

        override fun startActivity(className: String, extras: Map<String, ExtraValue>, flags: Int, from: Any?) {
            if (!className.startsWith("pkg.")) LifecycleProbe.invalid("$.className: not ours")
            started = Triple(className, extras, from)
        }
    }

    private val core = ProbeCore("0a1b2c3d")
    private val host = FakeHost()
    private val probe = LifecycleProbe(core, host, clock = { 1000L }).apply { processName = "pkg" }
    private val ctx = object : CommandContext {
        override val app: Application get() = error("no app in JVM tests")
        override val journal: Journal get() = core.journal
        override suspend fun <T> onMain(block: () -> T): T = block()
    }

    private fun command(name: String) = probe.commands().first { it.name == name }
    private fun call(name: String, input: JsonObject = EmptyJsonObject) = runBlocking {
        val command = command(name)
        command.input.validate(input)?.let { throw ProbeError(ProbeError.INVALID_INPUT, it) }
        (command as CallCommand).call(input, ctx).also { result ->
            command.output?.validate(result)?.let { fail("output violates schema: $it in $result") }
        }
    }

    private fun events(): List<JsonObject> =
        core.journal.records(Journal.Kind.EVENT, null, 100)["records"]!!.jsonArray.map { it.jsonObject }

    private fun resume(activity: FakeActivity, name: String) {
        listOf(LifecycleState.CREATED, LifecycleState.STARTED, LifecycleState.RESUMED).forEach { probe.onActivity(activity, name, it) }
    }

    @Test
    fun `activities are ordered by task then creation and the resumed one is marked`() {
        val main = FakeActivity(taskId = 7)
        val other = FakeActivity(taskId = 9)
        val detail = FakeActivity(taskId = 7, finishing = true)
        resume(main, "pkg.Main")
        probe.onActivity(main, "pkg.Main", LifecycleState.PAUSED)
        resume(other, "pkg.Other")
        probe.onActivity(other, "pkg.Other", LifecycleState.PAUSED)
        resume(detail, "pkg.Detail")

        val list = call("lifecycle.activities").jsonArray.map { it.jsonObject }
        assertEquals(listOf("pkg.Main", "pkg.Detail", "pkg.Other"), list.map { it.stringOrNull("class") })
        assertEquals(listOf("a-1", "a-3", "a-2"), list.map { it.stringOrNull("id") })
        assertEquals(listOf("paused", "resumed", "paused"), list.map { it.stringOrNull("state") })
        assertEquals(listOf(false, true, false), list.map { it["resumed"]!!.jsonPrimitive.content.toBoolean() })
        assertEquals(JsonArray(listOf(JsonPrimitive("token"))), list[0]["intent"]!!.jsonObject["extraKeys"])
        assertSame(detail, probe.resumed())
    }

    @Test
    fun `a destroyed activity stays as destroyed-pending and is not resumed or live`() {
        val main = FakeActivity(1)
        resume(main, "pkg.Main")
        listOf(LifecycleState.PAUSED, LifecycleState.STOPPED, LifecycleState.DESTROYED).forEach { probe.onActivity(main, "pkg.Main", it) }
        val list = call("lifecycle.activities").jsonArray
        assertEquals("destroyed-pending", list.single().jsonObject.stringOrNull("state"))
        assertNull(probe.resumed())
        assertTrue(probe.liveActivities().isEmpty())
        assertEquals("lifecycle.activity.destroyed", events().last().stringOrNull("name"))
    }

    @Test
    fun `every transition is journaled with from and to`() {
        val main = FakeActivity(1)
        resume(main, "pkg.Main")
        val activityEvents = events().filter { it.stringOrNull("name")!!.startsWith("lifecycle.activity.") }
        assertEquals(
            listOf("lifecycle.activity.created", "lifecycle.activity.started", "lifecycle.activity.resumed"),
            activityEvents.map { it.stringOrNull("name") },
        )
        val resumed = activityEvents.last()
        assertEquals("lifecycle", resumed.stringOrNull("source"))
        val params = resumed["params"]!!.jsonObject
        assertEquals(listOf("pkg.Main", "started", "resumed"), listOf("class", "from", "to").map { params.stringOrNull(it) })
        assertNull(activityEvents.first()["params"]!!.jsonObject.stringOrNull("from"))
    }

    @Test
    fun `journaling can be disabled by config`() {
        core.applyConfig(ProbeConfig(lifecycleJournal = false))
        resume(FakeActivity(1), "pkg.Main")
        assertTrue(events().isEmpty())
    }

    @Test
    fun `process goes foreground and background but not across a configuration change`() {
        val first = FakeActivity(1)
        resume(first, "pkg.Main")
        probe.onActivity(first, "pkg.Main", LifecycleState.PAUSED)
        probe.onActivity(first, "pkg.Main", LifecycleState.STOPPED, changingConfigurations = true)
        probe.onActivity(first, "pkg.Main", LifecycleState.DESTROYED)
        val rotated = FakeActivity(1)
        resume(rotated, "pkg.Main")
        probe.onActivity(rotated, "pkg.Main", LifecycleState.PAUSED)
        probe.onActivity(rotated, "pkg.Main", LifecycleState.STOPPED)

        val process = events().filter { it.stringOrNull("name")!!.startsWith("lifecycle.process.") }
        assertEquals(listOf("lifecycle.process.foreground", "lifecycle.process.background"), process.map { it.stringOrNull("name") })
        assertEquals("pkg", process.first()["params"]!!.jsonObject.stringOrNull("class"))
    }

    @Test
    fun `fragment transitions carry the previous state and destroy forgets the fragment`() {
        val fragment = FakeFragment()
        probe.onFragment(fragment, "pkg.Detail", LifecycleState.CREATED)
        probe.onFragment(fragment, "pkg.Detail", LifecycleState.STARTED)
        assertEquals("started", probe.fragments.entryOf(fragment)?.state)
        probe.onFragment(fragment, "pkg.Detail", LifecycleState.DESTROYED)
        assertNull(probe.fragments.entryOf(fragment))
        val last = events().last()
        assertEquals("lifecycle.fragment.destroyed", last.stringOrNull("name"))
        assertEquals("started", last["params"]!!.jsonObject.stringOrNull("from"))
    }

    @Test
    fun `watch streams transitions filtered by kind`() = runBlocking {
        val stream = (command("lifecycle.watch") as StreamCommand)
            .stream(buildJsonObject { put("kind", "activity") }, ctx)
        val collected = async(start = CoroutineStart.UNDISPATCHED) { stream.take(2).toList() }
        probe.onFragment(FakeFragment(), "pkg.Frag", LifecycleState.CREATED)
        val main = FakeActivity(1)
        probe.onActivity(main, "pkg.Main", LifecycleState.CREATED)
        probe.onActivity(main, "pkg.Main", LifecycleState.STARTED)
        val transitions = collected.await().map { it.jsonObject }
        assertEquals(listOf("created", "started"), transitions.map { it.stringOrNull("to") })
        transitions.forEach { assertNull(command("lifecycle.watch").output!!.validate(it)) }
        assertEquals("a-1", transitions[1].stringOrNull("id"))
        assertEquals("created", transitions[1].stringOrNull("from"))
    }

    @Test
    fun `fragments default to the resumed activity`() {
        host.fragments = listOf(
            FragmentNode("pkg.Detail", "detail", 0x1020002, "resumed", true, true, false, false, listOf("orderId"), emptyList()),
        )
        val main = FakeActivity(1)
        resume(main, "pkg.Main")
        val result = call("lifecycle.fragments").jsonObject
        assertEquals("a-1", result.stringOrNull("activityId"))
        assertEquals("detail", result["fragments"]!!.jsonArray.single().jsonObject.stringOrNull("tag"))
        assertEquals("a-1", call("lifecycle.fragments", buildJsonObject { put("activityId", "a-1") }).jsonObject.stringOrNull("activityId"))
    }

    @Test
    fun `fragments without a resumed activity or with an unknown id are invalid input`() {
        assertInvalid { call("lifecycle.fragments") }
        assertInvalid { call("lifecycle.fragments", buildJsonObject { put("activityId", "a-99") }) }
    }

    @Test
    fun `startActivity passes typed extras and the resumed activity`() {
        val main = FakeActivity(1)
        resume(main, "pkg.Main")
        val result = call(
            "lifecycle.startActivity",
            buildJsonObject {
                put("className", "pkg.Detail")
                put("extras", buildJsonObject { put("orderId", "order-1"); put("n", 3); put("big", 5_000_000_000L); put("on", true) })
            },
        )
        assertEquals(buildJsonObject { put("started", true) }, result)
        val (className, extras, from) = host.started!!
        assertEquals("pkg.Detail", className)
        assertEquals(
            mapOf(
                "orderId" to ExtraValue.Text("order-1"),
                "n" to ExtraValue.IntValue(3),
                "big" to ExtraValue.LongValue(5_000_000_000L),
                "on" to ExtraValue.Bool(true),
            ),
            extras,
        )
        assertSame(main, from)
    }

    @Test
    fun `startActivity rejects foreign classes, unsupported extras and missing className`() {
        assertInvalid { call("lifecycle.startActivity", buildJsonObject { put("className", "other.Main") }) }
        assertInvalid {
            call("lifecycle.startActivity", buildJsonObject {
                put("className", "pkg.Detail")
                put("extras", buildJsonObject { put("ratio", 1.5) })
            })
        }
        assertInvalid { call("lifecycle.startActivity") }
        assertNull(host.started)
    }

    @Test
    fun `process output conforms to its schema`() {
        val result = call("lifecycle.process").jsonObject
        assertEquals("foreground", result.stringOrNull("importanceName"))
        assertEquals("true", result["isForeground"]!!.jsonPrimitive.content)
    }

    @Test
    fun `the lifecycle prefix is reserved for app commands`() {
        try {
            CommandRegistry().register(callCommand("lifecycle.mine", "") { _, _ -> EmptyJsonObject })
            fail("lifecycle.mine accepted")
        } catch (expected: IllegalArgumentException) {
        }
        probe.commands().forEach(CommandRegistry()::registerBuiltIn)
    }

    private fun assertInvalid(block: () -> Unit) {
        try {
            block()
            fail("expected INVALID_INPUT")
        } catch (error: ProbeError) {
            assertEquals(ProbeError.INVALID_INPUT, error.code)
        }
    }
}

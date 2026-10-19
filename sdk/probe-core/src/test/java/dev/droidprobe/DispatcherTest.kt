package dev.droidprobe

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DispatcherTest {
    private val core = ProbeCore("0a1b2c3d").apply { packageName = "com.example"; pid = 42 }

    private fun withSetup(block: ProbeRegistrar.() -> Unit) = core.setSetup { it.block() }

    private fun session(callTimeoutMs: Long = 10_000, test: suspend DispatcherHarness.() -> Unit) = runBlocking {
        val harness = DispatcherHarness(core, this, callTimeoutMs)
        try {
            harness.test()
        } finally {
            harness.close()
            withTimeout(5_000) { harness.served.join() }
        }
    }

    @Test
    fun `hello is required first`() = session {
        send("""{"id":"1","op":"list"}""")
        val response = next("1")
        assertEquals("error", response.type())
        assertEquals("BAD_REQUEST", response.errorCode())
    }

    @Test
    fun `hello reports process identity`() = session {
        core.recordEvent("e", EmptyJsonObject, null)
        val data = hello().data().jsonObject
        assertEquals("1.0", data.getValue("protocol").jsonPrimitive.content)
        assertEquals("com.example", data.getValue("package").jsonPrimitive.content)
        assertEquals(42, data.getValue("pid").jsonPrimitive.content.toInt())
        assertEquals("0a1b2c3d", data.getValue("processStartId").jsonPrimitive.content)
        assertEquals("0a1b2c3d:1", data.getValue("headCursor").jsonPrimitive.content)
    }

    @Test
    fun `malformed lines and unknown ops are bad requests`() = session {
        send("not json")
        assertEquals("BAD_REQUEST", next(null).errorCode())
        hello()
        send("""{"id":"2","op":"dance"}""")
        assertEquals("BAD_REQUEST", next("2").errorCode())
    }

    @Test
    fun `list and describe include built-ins and app commands`() {
        withSetup { call("echo", "Echo input", Schema.obj("x" to Schema.integer())) { input, _ -> input } }
        session {
            hello()
            send("""{"id":"2","op":"list"}""")
            val commands = next("2").data().jsonObject.getValue("commands").jsonArray.map { it.jsonObject }
            val names = commands.map { it.getValue("name").jsonPrimitive.content }
            assertEquals(names.sorted(), names)
            assertTrue(names.containsAll(listOf("echo", "events.records", "events.watch", "probe.config", "probe.info")))
            assertEquals("stream", commands.first { it.getValue("name").jsonPrimitive.content == "events.watch" }
                .getValue("kind").jsonPrimitive.content)

            send("""{"id":"3","op":"describe","command":"echo"}""")
            val described = next("3").data().jsonObject
            assertEquals("call", described.getValue("kind").jsonPrimitive.content)
            assertEquals("integer", described.getValue("input").jsonObject.getValue("properties").jsonObject
                .getValue("x").jsonObject.getValue("type").jsonPrimitive.content)
        }
    }

    @Test
    fun `call returns the result or maps failures to codes`() {
        withSetup {
            call("echo", "Echo", Schema.obj("x" to Schema.integer())) { input, _ -> input }
            call("slow") { _, _ -> delay(5_000); EmptyJsonObject }
            call("boom") { _, _ -> throw IllegalStateException("kaput") }
            call("refuse") { _, _ -> throw ProbeError(ProbeError.MOCK_REJECTED, "no") }
        }
        session(callTimeoutMs = 200) {
            hello()
            send("""{"id":"1","op":"call","command":"echo","input":{"x":1}}""")
            assertEquals(buildJsonObject { put("x", 1) }, next("1").data())

            send("""{"id":"2","op":"call","command":"ecoh"}""")
            val unknown = next("2")
            assertEquals("UNKNOWN_COMMAND", unknown.errorCode())
            assertTrue(unknown.errorMessage(), unknown.errorMessage().contains("'echo'"))

            send("""{"id":"3","op":"call","command":"echo","input":{"x":"1"}}""")
            val invalid = next("3")
            assertEquals("INVALID_INPUT", invalid.errorCode())
            assertEquals("$.x: expected integer", invalid.errorMessage())

            send("""{"id":"4","op":"call","command":"slow"}""")
            assertEquals("TIMEOUT", next("4").errorCode())

            send("""{"id":"5","op":"call","command":"boom"}""")
            val internal = next("5")
            assertEquals("INTERNAL", internal.errorCode())
            assertEquals("java.lang.IllegalStateException: kaput", internal.errorMessage())

            send("""{"id":"6","op":"call","command":"refuse"}""")
            assertEquals("MOCK_REJECTED", next("6").errorCode())

            send("""{"id":"7","op":"call","command":"events.watch"}""")
            assertEquals("BAD_REQUEST", next("7").errorCode())
        }
    }

    @Test
    fun `failed setup fails every request after hello`() {
        withSetup { throw IllegalStateException("no hilt") }
        session {
            assertEquals("result", hello().type())
            send("""{"id":"1","op":"list"}""")
            val failed = next("1")
            assertEquals("SETUP_FAILED", failed.errorCode())
            assertTrue(failed.errorMessage().contains("no hilt"))
            send("""{"id":"2","op":"call","command":"probe.info"}""")
            assertEquals("SETUP_FAILED", next("2").errorCode())
        }
    }

    @Test
    fun `reserved or duplicate names fail the setup`() {
        withSetup { call("network.mine") { _, _ -> EmptyJsonObject } }
        session {
            hello()
            send("""{"id":"1","op":"list"}""")
            assertEquals("SETUP_FAILED", next("1").errorCode())
        }
        val other = ProbeCore("0a1b2c3d")
        other.setSetup { it.call("a") { _, _ -> EmptyJsonObject }; it.call("a") { _, _ -> EmptyJsonObject } }
        assertEquals("SETUP_FAILED", runBlocking { other.ensureSetup() }?.code)
    }

    @Test
    fun `watch streams events until cancelled`() = session {
        hello()
        send("""{"id":"w","op":"watch","command":"events.watch","input":{"nameContains":"order"}}""")
        delay(100)
        core.recordEvent("order.result", buildJsonObject { put("outcome", "Confirmed") }, "test")
        core.recordEvent("other", EmptyJsonObject, null)
        core.recordEvent("order.result", buildJsonObject { put("outcome", "ShowError") }, "test")
        val first = next("w")
        assertEquals("event", first.type())
        assertEquals("Confirmed", first.data().jsonObject.getValue("params").jsonObject.getValue("outcome").jsonPrimitive.content)
        assertEquals("test", first.data().jsonObject.getValue("source").jsonPrimitive.content)
        assertEquals("ShowError", next("w").data().jsonObject.getValue("params").jsonObject.getValue("outcome").jsonPrimitive.content)

        send("""{"id":"c","op":"cancel","target":"w"}""")
        assertEquals("end", next("w").type())
        assertEquals(EmptyJsonObject, next("c").data())

        send("""{"id":"c2","op":"cancel","target":"w"}""")
        assertEquals("BAD_REQUEST", next("c2").errorCode())
    }

    @Test
    fun `watch with since replays the backlog`() = session {
        core.recordEvent("a", EmptyJsonObject, null)
        val cursor = core.journal.headCursor
        core.recordEvent("b", EmptyJsonObject, null)
        core.recordEvent("c", EmptyJsonObject, null)
        hello()
        send("""{"id":"w","op":"watch","command":"events.watch","input":{"since":"$cursor"}}""")
        assertEquals("b", next("w").data().jsonObject.getValue("name").jsonPrimitive.content)
        assertEquals("c", next("w").data().jsonObject.getValue("name").jsonPrimitive.content)
        send("""{"id":"x","op":"watch","command":"events.watch","input":{"since":"ffffffff:1"}}""")
        assertEquals("STALE_CURSOR", next("x").errorCode())
    }

    @Test
    fun `finite streams end and disconnect cancels running watches`() {
        val collecting = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        withSetup {
            register(streamCommand("two", "") { _, _ -> flow { emit(JsonPrimitive(1)); emit(JsonPrimitive(2)) } })
            register(streamCommand("forever", "") { _, _ ->
                flow {
                    try {
                        collecting.complete(Unit)
                        awaitCancellation()
                    } finally {
                        cancelled.complete(Unit)
                    }
                }
            })
        }
        session {
            hello()
            send("""{"id":"1","op":"watch","command":"two"}""")
            assertEquals(JsonPrimitive(1), next("1").data())
            assertEquals(JsonPrimitive(2), next("1").data())
            assertEquals("end", next("1").type())
            send("""{"id":"2","op":"watch","command":"forever"}""")
            withTimeout(5_000) { collecting.await() }
            close()
            withTimeout(5_000) { cancelled.await() }
        }
    }

    @Test
    fun `probe config changes capacity and recording`() = session {
        hello()
        send("""{"id":"1","op":"call","command":"probe.config","input":{"journalCapacity":4,"recording":false}}""")
        val config = next("1").data().jsonObject
        assertEquals(4, config.getValue("eventCapacity").jsonPrimitive.content.toInt())
        assertEquals("false", config.getValue("recording").jsonPrimitive.content)
        core.recordEvent("dropped", EmptyJsonObject, null)
        assertEquals("0a1b2c3d:0", core.journal.headCursor)

        send("""{"id":"2","op":"call","command":"probe.config","input":{"recording":true}}""")
        next("2")
        repeat(10) { core.recordEvent("e$it", EmptyJsonObject, null) }
        send("""{"id":"3","op":"call","command":"events.records","input":{"since":"0a1b2c3d:0"}}""")
        val page = next("3").data().jsonObject
        assertEquals(4, page.getValue("records").jsonArray.size)
        assertEquals("true", page.getValue("truncated").jsonPrimitive.content)
        assertEquals("0a1b2c3d:7", page.getValue("oldestCursor").jsonPrimitive.content)

        send("""{"id":"4","op":"call","command":"events.records","input":{"since":"0a1b2c3d:0","strict":true}}""")
        assertEquals("CURSOR_EVICTED", next("4").errorCode())

        send("""{"id":"5","op":"call","command":"probe.info"}""")
        val info = next("5").data().jsonObject
        assertEquals(4, info.getValue("config").jsonObject.getValue("networkCapacity").jsonPrimitive.content.toInt())
    }
}

package dev.droidprobe.okhttp

import dev.droidprobe.CallCommand
import dev.droidprobe.ProbeConfig
import dev.droidprobe.ProbeCore
import dev.droidprobe.ProbeError
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.net.SocketTimeoutException

class ProbeInterceptorTest {
    private val core = ProbeCore("0a1b2c3d")
    private val persistence = InMemoryPersistence()
    private val network = NetworkProbe(core, persistence)
    private val client = OkHttpClient.Builder().addInterceptor(network.interceptor()).build()
    private val server = MockWebServer()

    @Before
    fun start() = server.start()

    @After
    fun stop() = server.shutdown()

    private fun get(path: String) = client.newCall(Request.Builder().url(server.url(path)).build()).execute()

    private fun command(name: String, input: String = "{}"): JsonElement = runBlocking {
        (network.commands().first { it.name == name } as CallCommand)
            .call(Json.parseToJsonElement(input).jsonObject, core.context)
    }

    private fun records(filter: String = "{}"): List<JsonObject> =
        command("network.records", """{"filter":$filter}""").jsonObject.getValue("records").jsonArray.map { it.jsonObject }

    private fun JsonObject.str(key: String) = getValue(key).let { if (it is JsonNull) null else it.jsonPrimitive.content }

    private fun addRule(json: String): String = network.addRule(Json.parseToJsonElement(json).jsonObject).id!!

    @Test
    fun `records the exchange and masks credential headers`() {
        server.enqueue(
            MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json")
                .addHeader("Set-Cookie", "session=1").setBody("""{"ok":true}"""),
        )
        val request = Request.Builder().url(server.url("/orders/1/confirm?x=1"))
            .header("Authorization", "Bearer x")
            .header("Cookie", "a=b")
            .header("X-Refresh-Token", "refresh")
            .header("X-Trace", "visible")
            .post("""{"item":1}""".toRequestBody("application/json".toMediaType()))
            .build()
        client.newCall(request).execute().use { assertEquals("""{"ok":true}""", it.body!!.string()) }

        val record = records().single()
        assertEquals("POST", record.str("method"))
        assertEquals(server.url("/orders/1/confirm?x=1").toString(), record.str("url"))
        assertEquals("200", record.str("status"))
        assertEquals("false", record.str("mocked"))
        assertNull(record.str("mockRuleId"))
        assertNull(record.str("error"))
        val headers = record.getValue("requestHeaders").jsonObject
        assertEquals("***", headers.str("Authorization"))
        assertEquals("***", headers.str("Cookie"))
        assertEquals("***", headers.str("X-Refresh-Token"))
        assertEquals("visible", headers.str("X-Trace"))
        assertEquals("***", record.getValue("responseHeaders").jsonObject.str("Set-Cookie"))
        assertEquals("""{"item":1}""", record.str("requestBody"))
        assertEquals("""{"ok":true}""", record.str("responseBody"))
        assertEquals("11", record.str("responseBodySize"))
        assertTrue(record.str("id")!!.startsWith("n-"))
    }

    @Test
    fun `app configured headers are masked and bodies follow the limit and content type`() {
        core.applyConfig(ProbeConfig(bodyLimitBytes = 4, redactHeaders = setOf("X-Device")))
        server.enqueue(MockResponse().setHeader("Content-Type", "text/plain").setBody("abcdefgh"))
        server.enqueue(MockResponse().setHeader("Content-Type", "image/png").setBody(Buffer().write(ByteArray(10))))
        client.newCall(Request.Builder().url(server.url("/a")).header("x-device", "id").build()).execute().close()
        get("/b").close()

        val (text, binary) = records()
        assertEquals("***", text.getValue("requestHeaders").jsonObject.str("x-device"))
        assertNull(text.str("responseBody"))
        assertEquals("8", text.str("responseBodySize"))
        assertEquals("true", text.str("responseBodyTruncated"))
        assertNull(binary.str("responseBody"))
        assertEquals("10", binary.str("responseBodySize"))
    }

    @Test
    fun `mock rule answers without the network and records it as mocked`() {
        val id = addRule("""{"method":"get","path":"/orders/*/confirm","status":403,"body":"{\"code\":\"E1\"}","times":1}""")
        get("/orders/abc/confirm").use {
            assertEquals(403, it.code)
            assertEquals("""{"code":"E1"}""", it.body!!.string())
            assertTrue(it.header("Content-Type")!!.startsWith("application/json"))
        }
        assertEquals(0, server.requestCount)
        server.enqueue(MockResponse().setBody("real"))
        get("/orders/abc/confirm").use { assertEquals(200, it.code) }
        assertEquals(1, server.requestCount)

        val mocked = records("""{"mocked":true}""").single()
        assertEquals(id, mocked.str("mockRuleId"))
        assertEquals("403", mocked.str("status"))
        assertEquals("""{"code":"E1"}""", mocked.str("responseBody"))
        assertEquals(1, records("""{"mocked":false}""").size)
        assertEquals(JsonArray(emptyList()), command("network.mock.list"))
    }

    @Test
    fun `failure rules throw and are recorded with the error`() {
        addRule("""{"path":"/slow","failure":"timeout"}""")
        try {
            get("/slow")
            fail("expected timeout")
        } catch (_: SocketTimeoutException) {
        }
        val record = records().single()
        assertNull(record.str("status"))
        assertTrue(record.str("error")!!.startsWith("java.net.SocketTimeoutException"))
    }

    @Test
    fun `templates render the response and the guard runs on add`() {
        network.addTemplate(MockTemplate("apiError", "API error body") { vars ->
            MockedResponse(
                status = vars.getValue("status").jsonPrimitive.content.toInt(),
                headers = mapOf("Content-Type" to "application/json"),
                body = buildJsonObject {
                    put("code", vars.getValue("code").jsonPrimitive.content)
                    put("message", vars["message"]?.jsonPrimitive?.content ?: "")
                }.toString(),
            )
        })
        network.addGuard { rule ->
            val code = rule.body?.let { Json.parseToJsonElement(it).jsonObject["code"]?.jsonPrimitive?.content }
            if (code in setOf("AUTH_EXPIRED", "AUTH_REVOKED") && !rule.allowAuthSideEffects) "$code logs the user out" else null
        }

        addRule("""{"path":"/confirm","template":"apiError","vars":{"status":403,"code":"PAYMENT_DECLINED","message":"no"}}""")
        get("/confirm").use {
            assertEquals(403, it.code)
            assertEquals("""{"code":"PAYMENT_DECLINED","message":"no"}""", it.body!!.string())
        }

        try {
            addRule("""{"path":"/confirm","template":"apiError","vars":{"status":401,"code":"AUTH_EXPIRED"}}""")
            fail("expected MOCK_REJECTED")
        } catch (error: ProbeError) {
            assertEquals(ProbeError.MOCK_REJECTED, error.code)
            assertEquals("AUTH_EXPIRED logs the user out", error.message)
        }
        addRule("""{"path":"/confirm","template":"apiError","vars":{"status":401,"code":"AUTH_EXPIRED"},"allowAuthSideEffects":true}""")

        val templates = command("network.mock.templates").jsonArray
        assertEquals("apiError", templates.single().jsonObject.str("name"))
        try {
            addRule("""{"path":"/x","template":"missing"}""")
            fail("expected INVALID_INPUT")
        } catch (error: ProbeError) {
            assertEquals(ProbeError.INVALID_INPUT, error.code)
        }
    }

    @Test
    fun `persisted rules survive a restart and clear removes them`() {
        addRule("""{"path":"/kept","status":500,"persist":true}""")
        addRule("""{"path":"/dropped","status":500}""")

        val restarted = NetworkProbe(ProbeCore("11111111"), persistence)
        val rules = restarted.store.list()
        assertEquals(listOf("/kept"), rules.map { it.path })
        assertEquals("m-2", restarted.store.add(MockRule(path = "/new")).id)

        assertEquals(2, restarted.store.clear())
        assertEquals(emptyList<MockRule>(), NetworkProbe(ProbeCore("22222222"), persistence).store.list())
    }

    @Test
    fun `list omits large bodies and record returns them`() {
        val big = "x".repeat(5000)
        server.enqueue(MockResponse().setHeader("Content-Type", "text/plain").setBody(big))
        get("/big").close()
        val listed = records().single()
        assertNull(listed.str("responseBody"))
        assertEquals("true", listed.str("bodyOmitted"))
        val full = command("network.record", """{"id":"${listed.str("id")}"}""").jsonObject
        assertEquals(big, full.str("responseBody"))
    }

    @Test
    fun `recording off still applies mocks`() {
        runBlocking {
            core.registry["probe.config"].let { it as CallCommand }
                .call(Json.parseToJsonElement("""{"recording":false}""").jsonObject, core.context)
        }
        addRule("""{"path":"/m","status":204}""")
        get("/m").use { assertEquals(204, it.code) }
        assertEquals(emptyList<JsonObject>(), records())
    }

    @Test
    fun `request bodies over the limit are never copied in full`() {
        core.applyConfig(ProbeConfig(bodyLimitBytes = 16))
        server.enqueue(MockResponse())
        server.enqueue(MockResponse())
        server.enqueue(MockResponse())
        val known = "x".repeat(100).toRequestBody("text/plain".toMediaType())
        client.newCall(Request.Builder().url(server.url("/known")).post(known).build()).execute().close()
        val streamed = ChunkedBody(chunks = 1000, chunkSize = 1024)
        client.newCall(Request.Builder().url(server.url("/unknown")).post(streamed).build()).execute().close()
        val small = ChunkedBody(chunks = 2, chunkSize = 4)
        client.newCall(Request.Builder().url(server.url("/small")).post(small).build()).execute().close()

        val (knownRecord, unknownRecord, smallRecord) = records()
        assertNull(knownRecord.str("requestBody"))
        assertEquals("100", knownRecord.str("requestBodySize"))
        assertEquals("true", knownRecord.str("requestBodyTruncated"))
        assertNull(unknownRecord.str("requestBody"))
        assertEquals("true", unknownRecord.str("requestBodyTruncated"))
        // The first write is the real upload; the recording copy stops at the limit.
        assertEquals(listOf(1000L * 1024, 0L), streamed.writtenPerCall)
        assertEquals("aaaaaaaa", smallRecord.str("requestBody"))
        assertEquals("false", smallRecord.str("requestBodyTruncated"))
    }

    @Test
    fun `responses over the limit or of streaming types are size only`() {
        core.applyConfig(ProbeConfig(bodyLimitBytes = 16))
        server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setChunkedBody("y".repeat(100), 10))
        server.enqueue(MockResponse().setHeader("Content-Type", "application/x-ndjson").setBody("{}\n{}\n"))
        get("/chunked").use { assertEquals(100, it.body!!.string().length) }
        get("/ndjson").close()

        val (chunked, ndjson) = records()
        assertNull(chunked.str("responseBody"))
        assertNull(chunked.str("responseBodySize"))
        assertEquals("true", chunked.str("responseBodyTruncated"))
        assertNull(ndjson.str("responseBody"))
        assertEquals("false", ndjson.str("responseBodyTruncated"))
    }

    @Test
    fun `duration excludes the time spent peeking the body`() {
        server.enqueue(
            MockResponse().setHeader("Content-Type", "text/plain").setBody("abcdef")
                .throttleBody(2, 300, java.util.concurrent.TimeUnit.MILLISECONDS),
        )
        get("/slow-body").use { assertEquals("abcdef", it.body!!.string()) }
        val record = records().single()
        assertEquals("abcdef", record.str("responseBody"))
        assertTrue(record.str("durationMs")!!.toLong() < 300)
    }

    /** A body of unknown length that counts how many bytes each writeTo call managed to write. */
    private class ChunkedBody(private val chunks: Int, private val chunkSize: Int) : okhttp3.RequestBody() {
        val writtenPerCall = mutableListOf<Long>()
        override fun contentType() = "text/plain".toMediaType()
        override fun contentLength() = -1L
        override fun writeTo(sink: okio.BufferedSink) {
            writtenPerCall += 0L
            val chunk = ByteArray(chunkSize) { 'a'.code.toByte() }
            repeat(chunks) {
                sink.write(chunk)
                sink.emit()
                writtenPerCall[writtenPerCall.lastIndex] += chunkSize.toLong()
            }
        }
    }

    private class InMemoryPersistence : MockPersistence {
        private var stored = "[]"
        override fun load(): List<MockRule> = Json.parseToJsonElement(stored).jsonArray.map { MockRule.fromJson(it.jsonObject) }
        override fun save(rules: List<MockRule>) {
            stored = JsonArray(rules.map { it.toJson() }).toString()
        }
    }
}

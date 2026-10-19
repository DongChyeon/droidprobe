package dev.droidprobe

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SchemaTest {
    private val schema = Schema.obj(
        "limit" to Schema.integer(),
        "mode" to Schema.string(enum = listOf("a", "b")),
        "filter" to Schema.obj("status" to Schema.integer(nullable = true), additionalProperties = false),
        "tags" to Schema.array(Schema.string()),
        required = listOf("mode"),
    )

    private fun validate(json: String) = schema.validate(Json.parseToJsonElement(json))

    @Test
    fun `accepts conforming input`() {
        assertNull(validate("""{"mode":"a","limit":3,"filter":{"status":null},"tags":["x"]}"""))
        assertNull(validate("""{"mode":"b","limit":3.0}"""))
    }

    @Test
    fun `reports the json path`() {
        assertEquals("$.limit: expected integer", validate("""{"mode":"a","limit":"3"}"""))
        assertEquals("$.limit: expected integer", validate("""{"mode":"a","limit":1.5}"""))
        assertEquals("$.mode: required", validate("""{}"""))
        assertEquals("$.mode: expected one of \"a\", \"b\"", validate("""{"mode":"c"}"""))
        assertEquals("$.filter.status: expected integer or null", validate("""{"mode":"a","filter":{"status":"x"}}"""))
        assertEquals("$.filter.other: unexpected property", validate("""{"mode":"a","filter":{"other":1}}"""))
        assertEquals("$.tags[1]: expected string", validate("""{"mode":"a","tags":["x",2]}"""))
        assertEquals("$.extra: unexpected property", validate("""{"mode":"a","extra":true}"""))
        assertEquals("$: expected object", validate("""[]"""))
    }

    @Test
    fun `parses schemas from text`() {
        val parsed = Schema.parse("""{"type":["string","null"]}""")
        assertNull(parsed.validate(Json.parseToJsonElement("null")))
        assertEquals("$: expected string or null", parsed.validate(Json.parseToJsonElement("true")))
    }
}

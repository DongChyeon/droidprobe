package dev.droidprobe

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class JournalTest {
    private val id = "0a1b2c3d"
    private val journal = Journal(id, networkCapacity = 3, eventCapacity = 3)

    private fun event(name: String) = journal.append(Journal.Kind.EVENT, buildJsonObject { put("name", name) })
    private fun network(url: String) = journal.append(Journal.Kind.NETWORK, buildJsonObject { put("url", url) })

    private fun JsonObject.names() = getValue("records").jsonArray.map {
        it.jsonObject.getValue("name").jsonPrimitive.content
    }

    @Test
    fun `kinds share one sequence`() {
        assertEquals(1, event("a").seq)
        assertEquals(2, network("u").seq)
        assertEquals(3, event("b").seq)
        assertEquals("$id:3", journal.headCursor)
        val record = journal.records(Journal.Kind.EVENT, since = null, limit = 10)
        assertEquals(listOf("a", "b"), record.names())
        val first = record.getValue("records").jsonArray[0].jsonObject
        assertEquals("$id:1", first.getValue("cursor").jsonPrimitive.content)
        assertEquals("event", first.getValue("type").jsonPrimitive.content)
        assertTrue(first.getValue("ts").jsonPrimitive.content.endsWith("Z"))
    }

    @Test
    fun `since and limit page from the oldest`() {
        listOf("a", "b", "c").forEach(::event)
        val page = journal.records(Journal.Kind.EVENT, since = "$id:1", limit = 1)
        assertEquals(listOf("b"), page.names())
        assertEquals("$id:2", page.getValue("nextCursor").jsonPrimitive.content)
        assertFalse(page.getValue("truncated").jsonPrimitive.content.toBoolean())

        val empty = journal.records(Journal.Kind.EVENT, since = "$id:3", limit = 10)
        assertEquals(emptyList<String>(), empty.names())
        assertEquals("$id:3", empty.getValue("nextCursor").jsonPrimitive.content)
    }

    @Test
    fun `evicted range is reported as truncated or rejected in strict mode`() {
        listOf("a", "b", "c", "d", "e").forEach(::event)
        val page = journal.records(Journal.Kind.EVENT, since = "$id:1", limit = 10)
        assertEquals(listOf("c", "d", "e"), page.names())
        assertTrue(page.getValue("truncated").jsonPrimitive.content.toBoolean())
        assertEquals("$id:3", page.getValue("oldestCursor").jsonPrimitive.content)

        val intact = journal.records(Journal.Kind.EVENT, since = "$id:2", limit = 10)
        assertFalse(intact.getValue("truncated").jsonPrimitive.content.toBoolean())
        assertFalse("oldestCursor" in intact)

        expectError(ProbeError.CURSOR_EVICTED) {
            journal.records(Journal.Kind.EVENT, since = "$id:1", limit = 10, strict = true)
        }
    }

    @Test
    fun `shrinking capacity evicts`() {
        listOf("a", "b", "c").forEach(::event)
        journal.setCapacity(Journal.Kind.EVENT, 1)
        val page = journal.records(Journal.Kind.EVENT, since = "$id:0", limit = 10)
        assertEquals(listOf("c"), page.names())
        assertTrue(page.getValue("truncated").jsonPrimitive.content.toBoolean())
    }

    @Test
    fun `cursor from another process is stale and garbage is invalid`() {
        expectError(ProbeError.STALE_CURSOR) { journal.records(Journal.Kind.EVENT, "ffffffff:1", 10) }
        expectError(ProbeError.INVALID_INPUT) { journal.records(Journal.Kind.EVENT, "nope", 10) }
    }

    @Test
    fun `watch without since sends only live entries`() = runBlocking {
        event("old")
        val received = async(Dispatchers.Default) {
            journal.watch(Journal.Kind.EVENT, since = null).take(2).toList()
        }
        delay(100)
        event("new1")
        network("ignored")
        event("new2")
        val names = withTimeout(5_000) { received.await() }.map { it.getValue("name").jsonPrimitive.content }
        assertEquals(listOf("new1", "new2"), names)
    }

    @Test
    fun `watch with since sends backlog then live without gaps or duplicates`() = runBlocking {
        val big = Journal(id, eventCapacity = 10_000)
        repeat(50) { big.append(Journal.Kind.EVENT, buildJsonObject { put("n", it) }) }
        val total = 500
        val writer = async(Dispatchers.Default) {
            repeat(total - 50) { big.append(Journal.Kind.EVENT, buildJsonObject { put("n", it + 50) }) }
        }
        val seqs = withTimeout(10_000) {
            big.watch(Journal.Kind.EVENT, since = "$id:10").take(total - 10).toList()
        }.map { it.getValue("cursor").jsonPrimitive.content.substringAfter(':').toLong() }
        writer.await()
        assertEquals((11L..total).toList(), seqs)
    }

    private fun expectError(code: String, block: () -> Unit) {
        try {
            block()
            fail("expected $code")
        } catch (error: ProbeError) {
            assertEquals(code, error.code)
        }
    }
}

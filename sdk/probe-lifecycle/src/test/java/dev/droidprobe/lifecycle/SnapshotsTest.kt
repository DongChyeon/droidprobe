package dev.droidprobe.lifecycle

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class SnapshotsTest {

    private fun activity(id: String, order: Long, taskId: Int) =
        ActivitySnapshot(id, order, "pkg.A$id", LifecycleState.STOPPED, taskId, false, null)

    @Test
    fun `tasks keep the order of their first activity`() {
        val ordered = ActivitySnapshot.ordered(
            listOf(activity("1", 1, 5), activity("2", 2, 3), activity("3", 3, 5), activity("4", 4, 3), activity("5", 5, 8)),
        )
        assertEquals(listOf("1", "3", "2", "4", "5"), ordered.map { it.id })
    }

    @Test
    fun `activity json conforms to the schema with and without an intent`() {
        val withIntent = activity("1", 1, 1).copy(intent = IntentSnapshot(null, "pkg/.A", 0, listOf("a", "b"), "app://x"))
        assertNull(LifecycleProbe.ACTIVITY_SCHEMA.validate(withIntent.toJson(resumed = false)))
        assertNull(LifecycleProbe.ACTIVITY_SCHEMA.validate(activity("2", 2, 1).toJson(resumed = true)))
        val intent = withIntent.toJson(false)["intent"]!!.jsonObject
        assertEquals(JsonArray(listOf(JsonPrimitive("a"), JsonPrimitive("b"))), intent["extraKeys"])
    }

    @Test
    fun `fragment trees serialize recursively and every level conforms to the schema`() {
        val child = FragmentNode("pkg.Child", null, 0, "started", true, false, true, false, emptyList(), emptyList())
        val root = FragmentNode("pkg.Root", "root", 12, "resumed", true, true, false, false, listOf("orderId"), listOf(child))
        val json = root.toJson()
        assertNull(LifecycleProbe.FRAGMENT_SCHEMA.validate(json))
        val childJson = json["children"]!!.jsonArray.single().jsonObject
        assertNull(LifecycleProbe.FRAGMENT_SCHEMA.validate(childJson))
        assertEquals("pkg.Child", childJson["class"]!!.jsonPrimitive.content)
        assertEquals("true", childJson["isHidden"]!!.jsonPrimitive.content)
        assertEquals(0, childJson["children"]!!.jsonArray.size)
    }

    @Test
    fun `importance names cover current and legacy constants`() {
        assertEquals("foreground", ProcessSnapshot.importanceName(100))
        assertEquals("cached", ProcessSnapshot.importanceName(400))
        assertEquals("perceptible", ProcessSnapshot.importanceName(130))
        assertEquals("unknown", ProcessSnapshot.importanceName(7))
        assertNull(LifecycleProbe.PROCESS_SCHEMA.validate(ProcessSnapshot(1, null, 400, 20, 5, null).toJson()))
    }

    @Test
    fun `tracker ids are stable and identity based`() {
        val tracker = LifecycleTracker("a")
        val first = Any()
        val second = Any()
        val (entry, from) = tracker.move(first, "A", LifecycleState.CREATED)
        assertNull(from)
        assertEquals("a-1", entry.id)
        assertEquals("a-2", tracker.move(second, "A", LifecycleState.CREATED).first.id)
        assertEquals(LifecycleState.CREATED, tracker.move(first, "A", LifecycleState.STARTED).second)
        assertSame(first, tracker.find("a-1")?.target)
        tracker.remove(first)
        assertNull(tracker.find("a-1"))
        assertEquals("a-3", tracker.move(first, "A", LifecycleState.CREATED).first.id)
    }
}

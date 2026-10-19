package dev.droidprobe

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Per-kind ring buffers sharing one sequence counter, so a single cursor orders every kind.
 */
class Journal(
    val processStartId: String,
    networkCapacity: Int = 500,
    eventCapacity: Int = 2000,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    enum class Kind(val wire: String) { NETWORK("network"), EVENT("event") }

    class Entry(val seq: Long, val record: JsonObject)

    private class Ring(var capacity: Int) {
        val entries = ArrayDeque<Entry>()
        var evictedThrough = 0L
        val listeners = mutableListOf<(Entry) -> Unit>()

        fun evict() {
            while (entries.size > capacity) evictedThrough = entries.removeFirst().seq
        }
    }

    private val lock = Any()
    private var seq = 0L
    private val rings = mapOf(Kind.NETWORK to Ring(networkCapacity), Kind.EVENT to Ring(eventCapacity))
    private val timestamp = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    val head: Long get() = synchronized(lock) { seq }

    val headCursor: String get() = cursor(head)

    fun cursor(seq: Long): String = "$processStartId:$seq"

    fun capacity(kind: Kind): Int = synchronized(lock) { ring(kind).capacity }

    fun setCapacity(kind: Kind, capacity: Int) = synchronized(lock) {
        require(capacity > 0) { "capacity must be positive" }
        ring(kind).capacity = capacity
        ring(kind).evict()
    }

    /** Stores a record built from [fields] plus `cursor`, `type` and `ts`. */
    fun append(kind: Kind, fields: JsonObject): Entry = synchronized(lock) {
        val next = ++seq
        val record = buildJsonObject {
            put("cursor", cursor(next))
            put("type", kind.wire)
            put("ts", timestamp.format(Date(clock())))
            fields.forEach { (key, value) -> put(key, value) }
        }
        val entry = Entry(next, record)
        val ring = ring(kind)
        ring.entries.addLast(entry)
        ring.evict()
        ring.listeners.toList().forEach { it(entry) }
        entry
    }

    fun find(kind: Kind, predicate: (JsonObject) -> Boolean): JsonObject? = synchronized(lock) {
        ring(kind).entries.lastOrNull { predicate(it.record) }?.record
    }

    /**
     * Returns `{records, nextCursor, truncated, oldestCursor?}` holding the oldest [limit] matching
     * entries after [since].
     */
    fun records(
        kind: Kind,
        since: String?,
        limit: Int,
        strict: Boolean = false,
        filter: (JsonObject) -> Boolean = { true },
        transform: (JsonObject) -> JsonObject = { it },
    ): JsonObject {
        val sinceSeq = since?.let(::parseCursor)
        val (selected, truncated, oldest, headSeq) = synchronized(lock) {
            val ring = ring(kind)
            val truncated = sinceSeq != null && sinceSeq < ring.evictedThrough
            if (truncated && strict) {
                throw ProbeError(
                    ProbeError.CURSOR_EVICTED,
                    "${kind.wire} entries through ${cursor(ring.evictedThrough)} were evicted",
                )
            }
            val selected = ring.entries.asSequence()
                .filter { sinceSeq == null || it.seq > sinceSeq }
                .filter { filter(it.record) }
                .take(limit)
                .toList()
            Snapshot(selected, truncated, ring.entries.firstOrNull()?.seq, seq)
        }
        val next = selected.lastOrNull()?.seq ?: sinceSeq ?: headSeq
        return buildJsonObject {
            put("records", JsonArray(selected.map { transform(it.record) }))
            put("nextCursor", cursor(next))
            put("truncated", truncated)
            if (truncated && oldest != null) put("oldestCursor", cursor(oldest))
        }
    }

    /**
     * Live entries after subscribing; with [since], the retained backlog first. Registering the
     * listener and reading the backlog happen under one lock, and `seq > lastSent` drops overlap.
     */
    fun watch(kind: Kind, since: String?, filter: (JsonObject) -> Boolean = { true }): Flow<JsonObject> {
        val sinceSeq = since?.let(::parseCursor)
        return flow {
            val buffer = Channel<Entry>(Channel.UNLIMITED)
            val listener: (Entry) -> Unit = { buffer.trySend(it) }
            val ring = ring(kind)
            val (backlog, headAtSubscribe) = synchronized(lock) {
                ring.listeners += listener
                val backlog = if (sinceSeq == null) emptyList() else ring.entries.filter { it.seq > sinceSeq }
                backlog to seq
            }
            try {
                var lastSent = sinceSeq ?: headAtSubscribe
                for (entry in backlog) {
                    if (entry.seq <= lastSent) continue
                    lastSent = entry.seq
                    if (filter(entry.record)) emit(entry.record)
                }
                for (entry in buffer) {
                    if (entry.seq <= lastSent) continue
                    lastSent = entry.seq
                    if (filter(entry.record)) emit(entry.record)
                }
            } finally {
                synchronized(lock) { ring.listeners -= listener }
                buffer.close()
            }
        }
    }

    /** Parses `<processStartId>:<seq>`; throws INVALID_INPUT or STALE_CURSOR. */
    fun parseCursor(cursor: String): Long {
        val match = CURSOR.matchEntire(cursor)
            ?: throw ProbeError(ProbeError.INVALID_INPUT, "malformed cursor '$cursor', expected <processStartId>:<seq>")
        val (id, seqText) = match.destructured
        if (id != processStartId) {
            throw ProbeError(
                ProbeError.STALE_CURSOR,
                "cursor '$cursor' belongs to another process (current processStartId $processStartId)",
            )
        }
        return seqText.toLongOrNull()
            ?: throw ProbeError(ProbeError.INVALID_INPUT, "malformed cursor '$cursor'")
    }

    private fun ring(kind: Kind): Ring = rings.getValue(kind)

    private data class Snapshot(val selected: List<Entry>, val truncated: Boolean, val oldest: Long?, val head: Long)

    private companion object {
        val CURSOR = Regex("([0-9a-f]{8}):([0-9]+)")
    }
}

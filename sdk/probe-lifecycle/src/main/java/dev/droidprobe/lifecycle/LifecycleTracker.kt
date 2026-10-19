package dev.droidprobe.lifecycle

import java.lang.ref.WeakReference

/** Lifecycle states of objects keyed by identity and held only through weak references. Thread-safe. */
class LifecycleTracker(private val idPrefix: String) {

    class Entry internal constructor(val id: String, val order: Long, val className: String, target: Any, state: String) {
        private val ref = WeakReference(target)

        val target: Any? get() = ref.get()

        @Volatile
        var state: String = state
            internal set
    }

    private val entries = mutableListOf<Entry>()
    private var created = 0L

    /** Moves [target] to [to]; returns the entry and its previous state (null when it was not tracked yet). */
    @Synchronized
    fun move(target: Any, className: String, to: String): Pair<Entry, String?> {
        prune()
        val entry = entries.firstOrNull { it.target === target }
            ?: return Entry("$idPrefix-${++created}", created, className, target, to).also { entries += it } to null
        val from = entry.state
        entry.state = to
        return entry to from
    }

    /** Stops tracking [target]; returns its entry and last state, or null when it was not tracked. */
    @Synchronized
    fun remove(target: Any): Entry? {
        prune()
        val entry = entries.firstOrNull { it.target === target } ?: return null
        entries -= entry
        return entry
    }

    @Synchronized
    fun entryOf(target: Any): Entry? = entries.firstOrNull { it.target === target }

    /** Entries whose target is still reachable, in creation order. */
    @Synchronized
    fun live(): List<Entry> {
        prune()
        return entries.toList()
    }

    @Synchronized
    fun find(id: String): Entry? = live().firstOrNull { it.id == id }

    private fun prune() {
        entries.removeAll { it.target == null }
    }
}

object LifecycleState {
    const val CREATED = "created"
    const val STARTED = "started"
    const val RESUMED = "resumed"
    const val PAUSED = "paused"
    const val STOPPED = "stopped"
    const val DESTROYED = "destroyed"

    /** An Activity whose onDestroy ran but which is not garbage collected yet. */
    const val DESTROYED_PENDING = "destroyed-pending"

    const val FOREGROUND = "foreground"
    const val BACKGROUND = "background"
}

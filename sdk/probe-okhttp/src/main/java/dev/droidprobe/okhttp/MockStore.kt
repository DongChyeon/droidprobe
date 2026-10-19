package dev.droidprobe.okhttp

import android.content.Context
import android.util.Log
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

interface MockPersistence {
    fun load(): List<MockRule>
    fun save(rules: List<MockRule>)
}

/**
 * Rules in insertion order. Persisted rules are loaded lazily on first use, ahead of new ones.
 * If loading fails, the stored rules are left untouched and nothing is saved for this process.
 */
class MockStore(
    private val persistence: () -> MockPersistence?,
    private val onLoadFailure: (Throwable) -> Unit = { Log.w("droidprobe", "Cannot load persisted mock rules", it) },
) {
    private val rules = ArrayList<MockRule>()
    private var loaded = false
    private var loadFailed = false
    private var nextId = 1L

    @Synchronized
    fun add(rule: MockRule): MockRule {
        ensureLoaded()
        val stored = rule.copy(id = "m-${nextId++}", remaining = rule.times)
        rules += stored
        if (stored.persist) save()
        return stored
    }

    /** Returns the first matching rule and consumes one of its `times`. */
    @Synchronized
    fun match(method: String, path: String): MockRule? {
        ensureLoaded()
        val index = rules.indexOfFirst { it.matches(method, path) }
        if (index < 0) return null
        val rule = rules[index]
        val remaining = rule.remaining?.minus(1)
        if (remaining != null) {
            if (remaining <= 0) rules.removeAt(index) else rules[index] = rule.copy(remaining = remaining)
            if (rule.persist) save()
        }
        return rule
    }

    @Synchronized
    fun list(): List<MockRule> {
        ensureLoaded()
        return rules.toList()
    }

    @Synchronized
    fun remove(id: String): Boolean {
        ensureLoaded()
        val rule = rules.firstOrNull { it.id == id } ?: return false
        rules.remove(rule)
        if (rule.persist) save()
        return true
    }

    @Synchronized
    fun clear(): Int {
        ensureLoaded()
        val count = rules.size
        rules.clear()
        save()
        return count
    }

    private fun ensureLoaded() {
        if (loaded) return
        val store = persistence() ?: return
        loaded = true
        val stored = try {
            store.load()
        } catch (error: Exception) {
            loadFailed = true
            onLoadFailure(error)
            emptyList()
        }
        rules.addAll(0, stored)
        val highest = rules.mapNotNull { it.id?.removePrefix("m-")?.toLongOrNull() }.maxOrNull() ?: 0
        nextId = maxOf(nextId, highest + 1)
    }

    private fun save() {
        if (loadFailed) return
        val store = persistence() ?: return
        runCatching { store.save(rules.filter { it.persist }) }
    }
}

internal class SharedPreferencesMockPersistence(context: Context) : MockPersistence {
    private val prefs by lazy { context.getSharedPreferences("dev.droidprobe.mocks", Context.MODE_PRIVATE) }

    override fun load(): List<MockRule> {
        val text = prefs.getString(KEY, null) ?: return emptyList()
        return Json.parseToJsonElement(text).jsonArray.map { MockRule.fromJson(it.jsonObject) }
    }

    // commit, not apply: a test may kill the process right after adding a persisted rule.
    override fun save(rules: List<MockRule>) {
        prefs.edit().putString(KEY, JsonArray(rules.map { it.toJson() }).toString()).commit()
    }

    private companion object {
        const val KEY = "rules"
    }
}

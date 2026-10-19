package dev.droidprobe.okhttp

import org.junit.Assert.assertEquals
import org.junit.Test

class MockStoreTest {
    @Test
    fun `a failed load never overwrites the stored rules`() {
        val persistence = object : MockPersistence {
            var saves = 0
            override fun load(): List<MockRule> = throw IllegalStateException("corrupt")
            override fun save(rules: List<MockRule>) {
                saves++
            }
        }
        val failures = mutableListOf<Throwable>()
        val store = MockStore({ persistence }, failures::add)

        store.add(MockRule(path = "/a", persist = true))
        store.match("GET", "/a")
        store.clear()

        assertEquals(0, persistence.saves)
        assertEquals(listOf("corrupt"), failures.map { it.message })
    }
}

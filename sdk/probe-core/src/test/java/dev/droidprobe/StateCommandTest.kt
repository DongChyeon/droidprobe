package dev.droidprobe

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Test

class StateCommandTest {
    private val core = ProbeCore("0a1b2c3d")
    private val confirmed = MutableStateFlow(false)

    private fun checkoutJson(confirmed: Boolean) = buildJsonObject { put("confirmed", confirmed) }

    @Test
    fun `one state registration yields a call and a watch`() {
        core.setSetup { it.state("checkout", confirmed.map(::checkoutJson)) }
        runBlocking {
            core.ensureSetup()
            assertEquals(listOf("checkout.state", "checkout.state.watch"), core.registry.all().map { it.name }.filter { it.startsWith("checkout") })
            val harness = DispatcherHarness(core, this)
            harness.hello()
            harness.send("""{"id":"1","op":"call","command":"checkout.state"}""")
            assertEquals(checkoutJson(false), harness.next("1").data())

            harness.send("""{"id":"w","op":"watch","command":"checkout.state.watch","input":{"since":"ignored"}}""")
            assertEquals(checkoutJson(false), harness.next("w").data())
            delay(50)
            confirmed.value = false
            confirmed.value = true
            assertEquals(checkoutJson(true), harness.next("w").data())
            harness.send("""{"id":"c","op":"cancel","target":"w"}""")
            assertEquals("end", harness.next("w").type())
            harness.close()
            harness.served.join()
        }
    }

    @Test
    fun `state without a value times out`() {
        core.setSetup { it.state("empty", emptyFlow()) }
        runBlocking {
            val harness = DispatcherHarness(core, this)
            harness.hello()
            harness.send("""{"id":"1","op":"call","command":"empty.state"}""")
            assertEquals("TIMEOUT", harness.next("1").errorCode())
            harness.close()
            harness.served.join()
        }
    }
}

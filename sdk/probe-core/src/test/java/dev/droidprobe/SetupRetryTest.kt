package dev.droidprobe

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SetupRetryTest {
    private val core = ProbeCore("0a1b2c3d")

    private fun connect(block: suspend DispatcherHarness.() -> Unit) = runBlocking {
        val harness = DispatcherHarness(core, this)
        harness.hello()
        harness.block()
        harness.close()
        withTimeout(5_000) { harness.served.join() }
    }

    @Test
    fun `a failed setup is rolled back and retried on the next connection`() {
        var attempts = 0
        core.setSetup { probe ->
            attempts++
            probe.call("app.ping") { _, _ -> EmptyJsonObject }
            check(attempts > 1) { "not ready" }
        }
        connect {
            send("""{"id":"1","op":"list"}""")
            assertEquals("SETUP_FAILED", next("1").errorCode())
            send("""{"id":"2","op":"call","command":"probe.info"}""")
            assertEquals("SETUP_FAILED", next("2").errorCode())
        }
        assertEquals(1, attempts)
        connect {
            send("""{"id":"1","op":"call","command":"app.ping"}""")
            assertEquals("result", next("1").type())
        }
        assertEquals(2, attempts)
    }

    @Test
    fun `retries stop after the cap`() {
        var attempts = 0
        core.setSetup {
            attempts++
            error("broken")
        }
        repeat(ProbeCore.MAX_SETUP_ATTEMPTS + 2) {
            connect {
                send("""{"id":"1","op":"list"}""")
                assertEquals("SETUP_FAILED", next("1").errorCode())
            }
        }
        assertEquals(ProbeCore.MAX_SETUP_ATTEMPTS, attempts)
        val message = runBlocking { core.ensureSetup(newConnection = true) }!!.message!!
        assertTrue(message, message.contains("no more retries"))
    }

    @Test
    fun `an unloadable setup class is not retried`() {
        var attempts = 0
        core.setSetup { attempts++ }
        core.failSetup("cannot load")
        connect {}
        connect {}
        assertEquals(0, attempts)
    }
}

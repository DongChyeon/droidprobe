package dev.droidprobe.okhttp

import android.content.Context
import androidx.startup.Initializer
import dev.droidprobe.Probe
import dev.droidprobe.ProbeInitializer
import dev.droidprobe.ProbeRegistrar
import kotlinx.serialization.json.JsonObject
import okhttp3.Interceptor

object ProbeOkHttp {
    val network: NetworkProbe = NetworkProbe(Probe.core)

    private var installed = false

    /** Add with `addInterceptor`; every client may share it. */
    fun interceptor(): Interceptor = network.interceptor()

    @Synchronized
    internal fun install(context: Context) {
        if (installed) return
        installed = true
        network.persistence = SharedPreferencesMockPersistence(context.applicationContext)
        network.commands().forEach(Probe.core.registry::registerBuiltIn)
    }
}

/** Registers the `network.*` commands once the core probe has started. */
class ProbeOkHttpInitializer : Initializer<Unit> {
    override fun create(context: Context) = ProbeOkHttp.install(context)

    override fun dependencies(): List<Class<out Initializer<*>>> = listOf(ProbeInitializer::class.java)
}

/** `probe.network.guard { }` and `probe.network.template(...)` inside `ProbeSetup.setup`. */
val ProbeRegistrar.network: NetworkRegistrar
    get() = NetworkRegistrar(ProbeOkHttp.network, this)

class NetworkRegistrar internal constructor(private val network: NetworkProbe, private val probe: ProbeRegistrar) {
    fun guard(guard: MockGuard) {
        network.addGuard(guard)
        probe.onRollback { network.removeGuard(guard) }
    }

    fun template(name: String, description: String, render: (vars: JsonObject) -> MockedResponse) {
        network.addTemplate(MockTemplate(name, description, render))
        probe.onRollback { network.removeTemplate(name) }
    }
}

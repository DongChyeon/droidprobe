package dev.droidprobe

import android.app.Application
import android.content.Context
import androidx.startup.Initializer

/** Started by AndroidX Startup from the library manifest; probe modules depend on it. */
class ProbeInitializer : Initializer<ProbeCore> {
    override fun create(context: Context): ProbeCore {
        Probe.start(context.applicationContext as Application)
        return Probe.core
    }

    override fun dependencies(): List<Class<out Initializer<*>>> = emptyList()
}

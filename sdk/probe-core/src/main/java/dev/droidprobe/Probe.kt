package dev.droidprobe

import android.app.Application
import android.content.pm.PackageManager
import android.os.Build
import android.os.Process
import android.util.Log
import kotlinx.serialization.json.JsonObject
import java.io.File

/** Entry point for app code. Everything here is safe to call before or without a host connection. */
object Probe {
    internal const val TAG = "droidprobe"
    private const val SETUP_META_DATA = "dev.droidprobe.SETUP"

    val core: ProbeCore = ProbeCore()

    val events: ProbeEvents = ProbeEvents(core)

    @Volatile
    private var started = false

    /** Called by [ProbeInitializer]; opens the socket only in the default process. */
    @Synchronized
    internal fun start(app: Application) {
        if (started) return
        started = true
        if (processName(app) != app.packageName) return
        core.packageName = app.packageName
        core.pid = Process.myPid()
        core.attach(app)
        loadSetup(app)
        ProbeServer(app, core).start()
    }

    private fun loadSetup(app: Application) {
        val className = try {
            app.packageManager.getApplicationInfo(app.packageName, PackageManager.GET_META_DATA)
                .metaData?.getString(SETUP_META_DATA)
        } catch (error: Exception) {
            null
        } ?: return
        try {
            val type = Class.forName(className)
            val setup = (runCatching { type.getField("INSTANCE").get(null) }.getOrNull()
                ?: type.getDeclaredConstructor().newInstance()) as ProbeSetup
            core.applyConfig(setup.config())
            core.setSetup { registrar -> setup.setup(registrar, app) }
        } catch (error: Throwable) {
            Log.w(TAG, "Cannot load ProbeSetup $className", error)
            core.failSetup("cannot load $className: ${error.javaClass.name}: ${error.message}")
        }
    }

    private fun processName(app: Application): String? =
        if (Build.VERSION.SDK_INT >= 28) {
            Application.getProcessName()
        } else {
            runCatching { File("/proc/self/cmdline").readText().trimEnd('\u0000') }.getOrNull()
        }
}

class ProbeEvents internal constructor(private val core: ProbeCore) {
    /** Appends an event to the journal. Never throws. */
    fun record(name: String, params: JsonObject = EmptyJsonObject, source: String? = null) =
        core.recordEvent(name, params, source)
}

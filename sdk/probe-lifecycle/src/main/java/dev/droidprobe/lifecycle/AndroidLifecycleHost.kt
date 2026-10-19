package dev.droidprobe.lifecycle

import android.app.Activity
import android.app.ActivityManager
import android.app.Application
import android.app.usage.UsageStatsManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Process
import android.os.SystemClock
import dev.droidprobe.ProbeError
import java.io.File

internal class AndroidLifecycleHost(private val app: Application) : LifecycleHost {

    override fun describe(entry: LifecycleTracker.Entry, activity: Any): ActivitySnapshot {
        activity as Activity
        return ActivitySnapshot(
            id = entry.id,
            order = entry.order,
            className = entry.className,
            state = entry.state,
            taskId = activity.taskId,
            isFinishing = activity.isFinishing,
            intent = activity.intent?.let(::snapshot),
        )
    }

    override fun fragments(activity: Any, states: LifecycleTracker): List<FragmentNode> =
        if (ProbeLifecycle.FRAGMENTS_AVAILABLE) FragmentSupport.tree(activity as Activity, states) else emptyList()

    override fun process(): ProcessSnapshot {
        val info = ActivityManager.RunningAppProcessInfo().also(ActivityManager::getMyMemoryState)
        val bucket = if (Build.VERSION.SDK_INT >= 28) {
            runCatching { app.getSystemService(UsageStatsManager::class.java)?.appStandbyBucket }.getOrNull()
        } else {
            null
        }
        return ProcessSnapshot(
            pid = Process.myPid(),
            processName = processName(),
            importance = info.importance,
            lastTrimLevel = info.lastTrimLevel,
            uptimeMs = SystemClock.elapsedRealtime() - Process.getStartElapsedRealtime(),
            appStandbyBucket = bucket,
        )
    }

    override fun startActivity(className: String, extras: Map<String, ExtraValue>, flags: Int, from: Any?) {
        val info = try {
            app.packageManager.getActivityInfo(ComponentName(app.packageName, className), 0)
        } catch (error: PackageManager.NameNotFoundException) {
            LifecycleProbe.invalid("$.className: '$className' is not an Activity declared by ${app.packageName}")
        }
        val type = runCatching { Class.forName(info.targetActivity ?: info.name, false, app.classLoader) }.getOrNull()
        if (type == null || !Activity::class.java.isAssignableFrom(type)) {
            LifecycleProbe.invalid("$.className: '$className' is not an Activity class")
        }
        val intent = Intent().setClassName(app, className).addFlags(flags)
        extras.forEach { (key, value) ->
            when (value) {
                is ExtraValue.Text -> intent.putExtra(key, value.value)
                is ExtraValue.IntValue -> intent.putExtra(key, value.value)
                is ExtraValue.LongValue -> intent.putExtra(key, value.value)
                is ExtraValue.Bool -> intent.putExtra(key, value.value)
            }
        }
        val context = from as? Activity
        try {
            if (context != null) context.startActivity(intent) else app.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (error: RuntimeException) {
            throw ProbeError(ProbeError.INTERNAL, "startActivity failed: ${error.javaClass.name}: ${error.message}")
        }
    }

    private fun snapshot(intent: Intent) = IntentSnapshot(
        action = intent.action,
        component = intent.component?.flattenToShortString(),
        flags = intent.flags,
        // Unparcelling can fail on classes this loader lacks; keys are then omitted.
        extraKeys = runCatching { intent.extras?.keySet()?.sorted() }.getOrNull().orEmpty(),
        data = intent.dataString,
    )

    private fun processName(): String? =
        if (Build.VERSION.SDK_INT >= 28) {
            Application.getProcessName()
        } else {
            runCatching { File("/proc/self/cmdline").readText().trimEnd('\u0000') }.getOrNull()
        }
}

package dev.droidprobe.lifecycle

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import androidx.startup.Initializer
import dev.droidprobe.Probe
import dev.droidprobe.ProbeInitializer

/** Lifecycle tracking for app probe code. Lookups read UI state, so call them on the main thread (`ctx.onMain`). */
object ProbeLifecycle {
    private var installed = false

    @Volatile
    private var host: AndroidLifecycleHost? = null

    val probe: LifecycleProbe by lazy {
        LifecycleProbe(Probe.core, object : LifecycleHost {
            private fun android() = host ?: throw IllegalStateException("probe-lifecycle is not installed")
            override fun describe(entry: LifecycleTracker.Entry, activity: Any) = android().describe(entry, activity)
            override fun fragments(activity: Any, states: LifecycleTracker) = android().fragments(activity, states)
            override fun process() = android().process()
            override fun startActivity(className: String, extras: Map<String, ExtraValue>, flags: Int, from: Any?) =
                android().startActivity(className, extras, flags, from)
        })
    }

    /** The resumed Activity, or null when none is (for example while the app is in the background). */
    fun resumedActivity(): Activity? = probe.resumed() as? Activity

    /** Live Activities ordered by task, then creation. */
    fun activities(): List<Activity> = probe.liveActivities().filterIsInstance<Activity>()

    /** First fragment of type [T] matching [predicate], searching the resumed Activity first and child fragments recursively. */
    inline fun <reified T : Any> findFragment(noinline predicate: (T) -> Boolean = { true }): T? =
        findFragment(T::class.java, predicate)

    fun <T : Any> findFragment(type: Class<T>, predicate: (T) -> Boolean): T? {
        if (!FRAGMENTS_AVAILABLE) return null
        val resumed = resumedActivity()
        val ordered = listOfNotNull(resumed) + activities().filter { it !== resumed }
        return ordered.firstNotNullOfOrNull { FragmentSupport.find(it, type, predicate) }
    }

    /** First view of type [T] matching [predicate] in the resumed Activity's window. */
    inline fun <reified T : View> findView(noinline predicate: (T) -> Boolean = { true }): T? =
        findView(T::class.java, predicate)

    fun <T : View> findView(type: Class<T>, predicate: (T) -> Boolean): T? {
        val root = resumedActivity()?.window?.peekDecorView() ?: return null
        val pending = ArrayDeque<View>().apply { add(root) }
        while (pending.isNotEmpty()) {
            val view = pending.removeFirst()
            if (type.isInstance(view) && predicate(type.cast(view)!!)) return type.cast(view)
            if (view is ViewGroup) for (i in 0 until view.childCount) pending.add(view.getChildAt(i))
        }
        return null
    }

    @Synchronized
    internal fun install(app: Application) {
        // Probe.start leaves packageName empty outside the default process, where no socket is served.
        if (installed || Probe.core.packageName.isEmpty()) return
        installed = true
        host = AndroidLifecycleHost(app)
        probe.processName = Probe.core.packageName
        app.registerActivityLifecycleCallbacks(Callbacks(probe))
        probe.commands().forEach(Probe.core.registry::registerBuiltIn)
    }

    internal val FRAGMENTS_AVAILABLE: Boolean =
        runCatching { Class.forName("androidx.fragment.app.FragmentActivity") }.isSuccess

    private class Callbacks(private val probe: LifecycleProbe) : Application.ActivityLifecycleCallbacks {
        override fun onActivityPreCreated(activity: Activity, savedInstanceState: Bundle?) {
            // Before super.onCreate, so fragments restored from saved state are seen from creation.
            if (FRAGMENTS_AVAILABLE) FragmentSupport.register(activity, probe)
        }

        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
            if (Build.VERSION.SDK_INT < 29 && FRAGMENTS_AVAILABLE) FragmentSupport.register(activity, probe)
            move(activity, LifecycleState.CREATED)
        }

        override fun onActivityStarted(activity: Activity) = move(activity, LifecycleState.STARTED)
        override fun onActivityResumed(activity: Activity) = move(activity, LifecycleState.RESUMED)
        override fun onActivityPaused(activity: Activity) = move(activity, LifecycleState.PAUSED)
        override fun onActivityStopped(activity: Activity) =
            probe.onActivity(activity, activity.javaClass.name, LifecycleState.STOPPED, activity.isChangingConfigurations)

        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
        override fun onActivityDestroyed(activity: Activity) = move(activity, LifecycleState.DESTROYED)

        private fun move(activity: Activity, to: String) = probe.onActivity(activity, activity.javaClass.name, to)
    }
}

/** Registers the `lifecycle.*` commands and Activity callbacks before any Activity is created. */
class ProbeLifecycleInitializer : Initializer<Unit> {
    override fun create(context: Context) = ProbeLifecycle.install(context.applicationContext as Application)

    override fun dependencies(): List<Class<out Initializer<*>>> = listOf(ProbeInitializer::class.java)
}

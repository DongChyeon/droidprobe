package dev.droidprobe.lifecycle

import android.app.Activity
import android.os.Bundle
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.FragmentManager

/** Touches androidx.fragment; load only when [ProbeLifecycle.FRAGMENTS_AVAILABLE]. */
internal object FragmentSupport {

    fun register(activity: Activity, probe: LifecycleProbe) {
        if (activity !is FragmentActivity) return
        activity.supportFragmentManager.registerFragmentLifecycleCallbacks(Callbacks(probe), true)
    }

    fun tree(activity: Activity, states: LifecycleTracker): List<FragmentNode> =
        (activity as? FragmentActivity)?.supportFragmentManager?.let { nodes(it, states) }.orEmpty()

    fun <T : Any> find(activity: Activity, type: Class<T>, predicate: (T) -> Boolean): T? =
        (activity as? FragmentActivity)?.supportFragmentManager?.let { find(it, type, predicate) }

    private fun <T : Any> find(manager: FragmentManager, type: Class<T>, predicate: (T) -> Boolean): T? =
        manager.fragments.firstNotNullOfOrNull { fragment ->
            fragment.takeIf { type.isInstance(it) && predicate(type.cast(it)!!) }?.let(type::cast)
                ?: find(fragment.childFragmentManager, type, predicate)
        }

    private fun nodes(manager: FragmentManager, states: LifecycleTracker): List<FragmentNode> =
        manager.fragments.map { fragment ->
            FragmentNode(
                className = fragment.javaClass.name,
                tag = fragment.tag,
                id = fragment.id,
                state = states.entryOf(fragment)?.state ?: fragment.lifecycle.currentState.name.lowercase(),
                isAdded = fragment.isAdded,
                isVisible = fragment.isVisible,
                isHidden = fragment.isHidden,
                isRemoving = fragment.isRemoving,
                argumentsKeys = runCatching { fragment.arguments?.keySet()?.sorted() }.getOrNull().orEmpty(),
                children = if (fragment.host != null) nodes(fragment.childFragmentManager, states) else emptyList(),
            )
        }

    private class Callbacks(private val probe: LifecycleProbe) : FragmentManager.FragmentLifecycleCallbacks() {
        override fun onFragmentCreated(fm: FragmentManager, f: Fragment, savedInstanceState: Bundle?) = move(f, LifecycleState.CREATED)
        override fun onFragmentStarted(fm: FragmentManager, f: Fragment) = move(f, LifecycleState.STARTED)
        override fun onFragmentResumed(fm: FragmentManager, f: Fragment) = move(f, LifecycleState.RESUMED)
        override fun onFragmentPaused(fm: FragmentManager, f: Fragment) = move(f, LifecycleState.PAUSED)
        override fun onFragmentStopped(fm: FragmentManager, f: Fragment) = move(f, LifecycleState.STOPPED)
        override fun onFragmentDestroyed(fm: FragmentManager, f: Fragment) = move(f, LifecycleState.DESTROYED)


        private fun move(fragment: Fragment, to: String) = probe.onFragment(fragment, fragment.javaClass.name, to)
    }
}

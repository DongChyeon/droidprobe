package dev.droidprobe.hilt

import android.app.Application
import dagger.Module
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.Multibinds
import dev.droidprobe.DebugCommand
import dev.droidprobe.ProbeRegistrar
import dev.droidprobe.ProbeSetup

/** Contributes to the probe setup from any Hilt module with `@IntoSet`. */
fun interface ProbeSetupContributor {
    fun contribute(probe: ProbeRegistrar, app: Application)
}

/**
 * Registers every `@IntoSet DebugCommand` and runs every `@IntoSet ProbeSetupContributor` from the
 * Hilt singleton component, then [onSetup]. Setup runs on the first host connection, after Hilt is ready.
 */
abstract class HiltProbeSetup : ProbeSetup {

    final override fun setup(probe: ProbeRegistrar, app: Application) {
        val entryPoint = EntryPointAccessors.fromApplication(app, ProbeEntryPoint::class.java)
        entryPoint.probeCommands().forEach(probe::register)
        entryPoint.probeSetupContributors().forEach { it.contribute(probe, app) }
        onSetup(probe, app)
    }

    open fun onSetup(probe: ProbeRegistrar, app: Application) {}
}

@EntryPoint
@InstallIn(SingletonComponent::class)
interface ProbeEntryPoint {
    fun probeCommands(): Set<DebugCommand>
    fun probeSetupContributors(): Set<ProbeSetupContributor>
}

/** Declares the sets so an app with no contributions still builds. */
@Module
@InstallIn(SingletonComponent::class)
abstract class ProbeMultibindsModule {
    @Multibinds
    abstract fun probeCommands(): Set<DebugCommand>

    @Multibinds
    abstract fun probeSetupContributors(): Set<ProbeSetupContributor>
}

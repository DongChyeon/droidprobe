# probe-hilt

Lets Hilt apps contribute probe commands and setup from ordinary Hilt modules, and reach
singletons (repositories, caches, stores) from commands without writing an `EntryPoint`.

## Add it

```kotlin
// app/build.gradle.kts
dependencies {
    debugImplementation("dev.droidprobe:probe-core:0.1.0")
    debugImplementation("dev.droidprobe:probe-hilt:0.1.0")
}
```

Your app already runs Hilt's annotation processor (kapt or KSP); the module's `@EntryPoint` and
`@Multibinds` declarations are picked up by it. Nothing changes in `main`.

## Use it

One setup class, referenced from the debug manifest:

```kotlin
// src/debug/java/com/example/app/probe/AppProbeSetup.kt
class AppProbeSetup : HiltProbeSetup() {
    override fun config() = ProbeConfig(recordingEnabled = { !BuildConfig.PERF_RUN })
}
```

```xml
<!-- src/debug/AndroidManifest.xml -->
<meta-data android:name="dev.droidprobe.SETUP" android:value="com.example.app.probe.AppProbeSetup" />
```

Then contribute from any Hilt module in the debug source set. Each module receives its
dependencies by injection, so commands can use your singletons directly:

```kotlin
// src/debug/java/com/example/app/probe/CartProbeModule.kt
@Module @InstallIn(SingletonComponent::class)
object CartProbeModule {

    @Provides @IntoSet
    fun cartState(cart: CartRepository): ProbeSetupContributor = ProbeSetupContributor { probe, _ ->
        probe.state("cart", cart.state.map { buildJsonObject { put("itemCount", it.itemCount) } })
    }

    @Provides @IntoSet
    fun setQuantity(cart: CartRepository): DebugCommand = callCommand(
        "cart.setQuantity", "Sets the quantity of the first cart item",
        Schema.obj("quantity" to Schema.integer(), required = listOf("quantity")),
    ) { input, ctx ->
        ctx.onMain { cart.setFirstQuantity(input.intOrNull("quantity")!!) }
        buildJsonObject { put("ok", true) }
    }
}
```

- `@IntoSet DebugCommand`: registered as is.
- `@IntoSet ProbeSetupContributor`: called with the registrar, for anything else (`probe.state`,
  `probe.network.guard`, templates, several commands at once).
- `HiltProbeSetup.onSetup(probe, app)`: optional hook that runs after all contributions.

Setup runs on the first host connection, after Hilt's component exists, so injection is safe.
Contributions with conflicting command names make setup fail with `SETUP_FAILED` and the name.

## Hooking existing extension points

Hilt apps often already expose `@Multibinds` sets (analytics dispatch observers, interceptors,
logging trees). Contribute probe implementations to them from the debug source set; no `main`
change is needed:

```kotlin
@Module @InstallIn(SingletonComponent::class)
object ProbeHooksModule {
    @Provides @IntoSet
    fun analytics(): AnalyticsDispatchObserver = AnalyticsDispatchObserver { record ->
        val params = buildJsonObject { record.params.forEach { (key, value) -> put(key, value?.toString()) } }
        Probe.events.record(record.name, params, source = "analytics")
    }
}
```

## uitest and other debug-like build types

`src/debug` is not merged into other build types. To use the probe in, for example, a `uitest`
build type, add the same source directory and dependencies to it:

```kotlin
android { sourceSets["uitest"].java.srcDir("src/probe/java") }
dependencies { "uitestImplementation"("dev.droidprobe:probe-hilt:0.1.0") }
```

and put the `dev.droidprobe.SETUP` meta-data in that build type's manifest too.

## Remove it

Delete the dependency, the setup class, the meta-data and the debug Hilt modules.

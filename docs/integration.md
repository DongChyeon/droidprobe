# Integrating droidprobe into an app

The SDK must exist only in debug-type builds. Release builds contain zero `dev.droidprobe` classes,
so the app's main source set can never reference the SDK directly. There are two ways to keep it
that way; pick by whether the app uses dependency injection.

```kotlin
// build.gradle.kts of the app module
dependencies {
    debugImplementation("dev.droidprobe:probe-core:0.1.0")
    debugImplementation("dev.droidprobe:probe-okhttp:0.1.0")   // network recording and mocks
    debugImplementation("dev.droidprobe:probe-lifecycle:0.1.0") // Activity, fragment and process state
    debugImplementation("dev.droidprobe:probe-hilt:0.1.0")     // A1 with Hilt only
}
```

Both styles finish with a `ProbeSetup` named in the debug manifest:

```xml
<!-- src/debug/AndroidManifest.xml -->
<application>
    <meta-data android:name="dev.droidprobe.SETUP" android:value="com.example.debug.AppProbeSetup" />
</application>
```

The SDK starts itself through AndroidX Startup and listens on `@<applicationId>.probe` in the
default process. `ProbeSetup.config()` is read at process start; `ProbeSetup.setup()` runs once,
on the first host connection, so DI graphs are ready by then. If `setup()` throws or registers a
reserved (`probe.`, `network.`, `events.`, `lifecycle.`) or duplicate name, every request answers `SETUP_FAILED`.

## A1: DI hook points (Hilt)

Main code declares empty extension points; the debug source set fills them.

```kotlin
// main: declare the hook as an empty multibinding
@Qualifier annotation class DebugNetworkInterceptors

@Module @InstallIn(SingletonComponent::class)
abstract class NetworkHooks {
    @Multibinds @DebugNetworkInterceptors abstract fun interceptors(): Set<Interceptor>
}

// main: consume it where the client is built
OkHttpClient.Builder().apply { debugInterceptors.forEach(::addInterceptor) }

// main: an observer interface for app events, also an empty @Multibinds Set
interface OrderResultObserver { fun onOrderResult(outcome: String, reason: String?) }
```

```kotlin
// debug: provide the probe implementations
@Module @InstallIn(SingletonComponent::class)
object ProbeHooks {
    @Provides @IntoSet @DebugNetworkInterceptors
    fun probeInterceptor(): Interceptor = ProbeOkHttp.interceptor()

    @Provides @IntoSet
    fun orderObserver(): OrderResultObserver = OrderResultObserver { outcome, reason ->
        Probe.events.record("order.result", buildJsonObject {
            put("outcome", outcome)
            reason?.let { put("reason", it) }
        }, source = "checkout")
    }

    @Provides @IntoSet
    fun checkoutState(checkout: Checkout): ProbeSetupContributor = ProbeSetupContributor { probe, _ ->
        probe.state("checkout", checkout.confirmed.map { buildJsonObject { put("confirmed", it) } })
    }
}

class AppProbeSetup : HiltProbeSetup() {
    override fun onSetup(probe: ProbeRegistrar, app: Application) {
        probe.network.guard { rule -> /* return a reason to reject, or null */ null }
        probe.network.template("apiError", "status, code, message") { vars -> MockedResponse(400, body = "...") }
    }
}
```

`HiltProbeSetup` registers every `@IntoSet DebugCommand` and runs every `@IntoSet
ProbeSetupContributor` from the singleton component, then `onSetup`. probe-hilt declares both
sets with `@Multibinds`, so contributing nothing is fine. With empty sets in release, main
behaviour is unchanged; test that the interceptor list is identical when the set is empty.

## A2: same-named functions per source set (no DI)

Main code calls plain functions whose debug and release versions live in separate source sets.
`sdk/sample-app` is the reference.

```kotlin
// src/debug/java/.../ProbeHooks.kt
fun probeInterceptors(): List<Interceptor> = listOf(ProbeOkHttp.interceptor())
fun recordOrderResult(outcome: String, reason: String?) =
    Probe.events.record("order.result", buildJsonObject { put("outcome", outcome) }, source = "sample")

// src/release/java/.../ProbeHooks.kt
fun probeInterceptors(): List<Interceptor> = emptyList()
fun recordOrderResult(outcome: String, reason: String?) = Unit

// src/main: no dev.droidprobe import
OkHttpClient.Builder().apply { probeInterceptors().forEach(::addInterceptor) }.build()
```

```kotlin
// src/debug/java/.../SampleProbeSetup.kt
class SampleProbeSetup : ProbeSetup {
    override fun setup(probe: ProbeRegistrar, app: Application) {
        probe.state("checkout", Checkout.confirmed.map { buildJsonObject { put("confirmed", it) } })
        probe.network.guard { rule -> if (rule.body?.contains("AUTH_EXPIRED") == true && !rule.allowAuthSideEffects) "logs out" else null }
        probe.network.template("apiError", "vars: status, code, message") { vars -> MockedResponse(/* ... */) }
    }
}
```

Every build type that should carry the probe (for example a `uitest` type) needs the same three
pieces: the dependency, the hook source files and the manifest meta-data.

## B: no-op artifact

A `probe-*-noop` artifact with the same API for release builds is planned for v1.1 and is not
available yet. Until then use A1 or A2.

## Checks

- Release: no SDK classes in the dex. For example
  `dexdump app-release.apk | grep "Class descriptor" | grep "Ldev/droidprobe/"` is empty
  (exclude your own package if it lives under `dev.droidprobe`).
- Debug: after launching the app, `adb shell cat /proc/net/unix | grep '@<applicationId>.probe'`.
- Connections need `android.permission.DUMP` on the peer. `adb forward` connects as adbd, which has
  it (shell on user builds, root after `adb root`); other apps do not.

## What is recorded

- Network: every exchange through `ProbeOkHttp.interceptor()` (add it as an application
  interceptor). Headers `Authorization`, `Cookie`, `Set-Cookie`, any name containing `token`, and
  `ProbeConfig.redactHeaders` are stored as `***`. Bodies are stored only for textual content types,
  up to `bodyLimitBytes`. JSON body fields are not masked in v1, so tokens returned in response
  bodies (for example a refresh endpoint) are visible to anyone with adb access.
- Events: whatever the app passes to `Probe.events.record`. The call never throws and never blocks
  on I/O.
- `ProbeConfig.recordingEnabled` (and `probe.config {"recording": false}` at runtime) turns
  recording off; mocks still apply.

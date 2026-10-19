# droidprobe SDK

Android libraries that let the `probe` CLI read and steer a debug build over adb.
The wire contract is [docs/protocol.md](../docs/protocol.md); adoption styles are in
[docs/integration.md](../docs/integration.md).

| Module | Artifact | Contents | Usage guide | Change to `main` code |
|---|---|---|---|---|
| `probe-core` | `dev.droidprobe:probe-core` | Socket server, dispatcher, journal, schema, `Probe`, `ProbeSetup`, `probe.*` and `events.*` | [README](probe-core/README.md) | None |
| `probe-lifecycle` | `dev.droidprobe:probe-lifecycle` | Activity, fragment and process lifecycle, `ProbeLifecycle` lookups, `lifecycle.*` | [README](probe-lifecycle/README.md) | None |
| `probe-hilt` | `dev.droidprobe:probe-hilt` | `HiltProbeSetup`, `ProbeSetupContributor`, `@Multibinds` declarations | [README](probe-hilt/README.md) | None |
| `probe-okhttp` | `dev.droidprobe:probe-okhttp` | `ProbeOkHttp.interceptor()`, mock rules, guards, templates, `network.*` | [README](probe-okhttp/README.md) | One empty extension point where OkHttpClient is built |
| `sample-app` | — | Reference app without Hilt (A2 style); the e2e target | [README](sample-app/README.md) | — |

Suggested order for an existing app: probe-core + probe-lifecycle (dependency lines only), then
app commands in a debug source set (probe-hilt for Hilt apps), then probe-okhttp when you need
network records or mocks.

Requirements: minSdk 24, OkHttp 4.x (compiled against 4.6.0), kotlinx-serialization-json 1.6+,
kotlinx-coroutines 1.6+.

## Extending

One extension unit: a `DebugCommand`, either `CallCommand` (one result) or `StreamCommand` (a
`Flow` collected while the host watches). Register app commands in `ProbeSetup.setup`:

```kotlin
probe.call("cart.clear", "Empties the cart", Schema.obj()) { _, ctx -> ctx.onMain { cart.clear() }; EmptyJsonObject }
probe.state("checkout", checkout.state.map { buildJsonObject { put("confirmed", it.confirmed) } })
```

`probe.state(name, flow)` registers `<name>.state` (current value; `TIMEOUT` if none within 2 s)
and `<name>.state.watch` (current value, then distinct changes). Inputs are validated against
the declared `Schema` before the command runs; calls time out after 10 s.

## probe-lifecycle

Starts with the process (its own AndroidX Startup initializer, after `ProbeInitializer`), so every
Activity is tracked from creation even though `ProbeSetup.setup` runs only on the first connection.
Activities are held through weak references. Fragment callbacks (recursive) are registered on each
`FragmentActivity` when the app ships `androidx.fragment`; the module does not add that dependency.

- `lifecycle.activities`, `lifecycle.fragments`, `lifecycle.process`: current state.
- `lifecycle.watch`: live transitions. Each transition is also an event `lifecycle.<kind>.<to>`, so
  `events.records --since C` shows the navigation between two cursors. Set
  `ProbeConfig(lifecycleJournal = false)` to keep them out of the journal.
- `lifecycle.startActivity`: starts any Activity the app declares, exported or not.

App commands use the lookups instead of their own reflection. Call them on the main thread:

```kotlin
probe.call("checkout.view", "Checkout screen state") { _, ctx ->
    ctx.onMain {
        val view = ProbeLifecycle.findView<CheckoutView>() ?: throw ProbeError(ProbeError.INVALID_INPUT, "no checkout view")
        val payment = ProbeLifecycle.findFragment<PaymentFragment> { it.isResumed }
        buildJsonObject { put("confirmed", view.isConfirmed); put("paymentShown", payment != null) }
    }
}
```

`ProbeLifecycle.resumedActivity()` and `ProbeLifecycle.activities()` return the Activities
themselves. `findFragment` searches the resumed Activity first, then the others, children included;
`findView` searches the resumed Activity's window.

## Build

The Gradle wrapper needs JDK 17 or 21 (for example Android Studio's bundled JBR).

```sh
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew test                                # JVM unit tests
./gradlew publishToMavenLocal                 # dev.droidprobe:*:0.1.0
./gradlew :sample-app:installDebug            # add -PwithEcho=true for the echo command
```

## Sample app

Package `dev.droidprobe.sample`, socket `@dev.droidprobe.sample.probe`.

```sh
# Confirm an order: GET <baseUrl>/orders/<orderId>/confirm, then record order.result
adb shell am broadcast -a dev.droidprobe.sample.CONFIRM_ORDER -p dev.droidprobe.sample \
    [--es baseUrl https://host] [--es orderId order-1]
# Reset checkout to unconfirmed
adb shell am broadcast -a dev.droidprobe.sample.RESET -p dev.droidprobe.sample
# Record N events named sample.tick with params {i: 1..N}
adb shell am broadcast -a dev.droidprobe.sample.EMIT_EVENTS -p dev.droidprobe.sample --ei count N
```

The default base URL `https://example.invalid` never resolves, so every flow is driven by mocks.
A 2xx response records `order.result {outcome: "Confirmed"}` and sets `checkout.state.confirmed` to
true; anything else records `{outcome: "ShowError", reason}` where `reason` is the body's `code`,
`HTTP <status>`, or the exception class. Its setup registers `checkout.state`, a guard that rejects
bodies with `code` `AUTH_EXPIRED` or `AUTH_REVOKED` unless `allowAuthSideEffects`, and an `apiError` template
(`vars: status, code, message`). `DetailActivity` is not exported and hosts one `DetailFragment`
(tag `detail`, argument `orderId`); open it with
`probe call lifecycle.startActivity --input '{"className":"dev.droidprobe.sample.DetailActivity"}'`.

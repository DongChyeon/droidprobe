# probe-core

The in-app half of droidprobe: a socket server that the `probe` CLI reaches through adb, the
command dispatcher, the event journal, and the API your app uses to add commands. Every other
module depends on it.

## Add it

```kotlin
// app/build.gradle.kts
dependencies {
    debugImplementation("dev.droidprobe:probe-core:0.1.0")
}
```

That is the whole install. The AAR's manifest registers an AndroidX Startup initializer, so the
server starts with your process. You do not touch `Application`, your manifest, or any `main`
source. Release builds contain no droidprobe classes because the dependency is debug-only.

Requirements: minSdk 24, kotlinx-serialization-json 1.6+, kotlinx-coroutines 1.6+ (pulled in
transitively), AndroidX Startup (merged automatically unless your app removes
`InitializationProvider`; see Troubleshooting).

## What you get with no code

```sh
probe devices                       # device and package with an open probe socket
probe list -p com.example.app       # probe.info, probe.config, events.records, events.watch
probe call probe.info -p com.example.app
probe cursor -p com.example.app     # a bookmark for "everything after this point"
```

`events.*` stays empty until something records events (your code, or probe-lifecycle).

## Record events from your code

```kotlin
Probe.events.record(
    name = "checkout.completed",
    params = buildJsonObject { put("items", 3) },
    source = "checkout",
)
```

`record` never blocks and never throws, so it is safe on any thread and in hot paths. Read the
events from the host with a cursor:

```sh
C=$(probe cursor -p $PKG)
# ... do something in the app ...
probe call events.records -p $PKG --since $C
probe watch events.watch -p $PKG --since $C --until '.name=="checkout.completed"' --timeout 20s
```

Keep the `Probe.events.record` call out of `main` sources: put it behind an interface that your
debug source set implements (see [docs/integration.md](../../docs/integration.md)), or use an
extension point your app already has.

## Add commands: `ProbeSetup`

Commands are how the host reads app state or asks the app to do something. Register them in one
`ProbeSetup` class placed in a debug-only source set:

```kotlin
// src/debug/java/com/example/app/AppProbeSetup.kt
class AppProbeSetup : ProbeSetup {
    override fun config() = ProbeConfig(recordingEnabled = { !BuildConfig.PERF_RUN })

    override fun setup(probe: ProbeRegistrar, app: Application) {
        // A read-only command
        probe.call("cart.size", "Number of items in the cart") { _, ctx ->
            ctx.onMain { buildJsonObject { put("size", CartHolder.cart.size) } }
        }

        // A command with validated input
        probe.call(
            "cart.add",
            "Adds a product to the cart without opening the product screen",
            Schema.obj("productId" to Schema.string("Product id"), required = listOf("productId")),
        ) { input, ctx ->
            ctx.onMain { CartHolder.cart.add(input.stringOrNull("productId")!!) }
            buildJsonObject { put("added", true) }
        }

        // Current value + live changes from a Flow: registers cart.state and cart.state.watch
        probe.state("cart", CartHolder.state.map { buildJsonObject { put("itemCount", it.itemCount) } })
    }
}
```

```xml
<!-- src/debug/AndroidManifest.xml -->
<application>
    <meta-data android:name="dev.droidprobe.SETUP" android:value="com.example.app.AppProbeSetup" />
</application>
```

The CLI discovers these at run time; no CLI rebuild is needed:

```sh
probe list -p $PKG
probe describe cart.add -p $PKG
probe call cart.add -p $PKG --input '{"productId":"p-1"}'
probe watch cart.state.watch -p $PKG --until '.itemCount > 0' --timeout 30s
```

Rules the dispatcher enforces:

- Setup runs once, on the first host connection, so Hilt or other DI is ready by then. If it
  throws, `hello` still succeeds and every request returns `SETUP_FAILED` with the reason; the
  next connection retries (up to 5 times) after rolling back what the failed attempt registered.
- Names are lower-case and dotted. `probe.`, `network.`, `events.` and `lifecycle.` are reserved.
  A duplicate name fails setup instead of silently replacing a command.
- Input is checked against the declared `Schema` before your block runs; failures return
  `INVALID_INPUT` with the JSON path. Built-in schemas reject unknown fields.
- Each call runs on `Dispatchers.IO` with a 10 s timeout (`TIMEOUT`). Use `ctx.onMain { }` for
  views, fragments and other main-thread objects. Throw `ProbeError(code, message)` for an
  expected error; any other exception becomes `INTERNAL` and the app keeps running.
- `probe.state` returns `TIMEOUT` if the flow emits nothing within 2 s; the `.watch` variant
  always starts with the current value.

Write `description` carefully: it is what people and agents read in `probe list`.

For a full command type, implement `CallCommand` or `StreamCommand` (or use `callCommand` /
`streamCommand`) and pass it to `probe.register(...)`.

## Configuration (`ProbeConfig`)

| Field | Default | Meaning |
|---|---|---|
| `recordingEnabled` | `{ true }` | Checked on every record; return false to stop journaling (for example during performance runs) |
| `networkCapacity` | 500 | Ring buffer size for network records (probe-okhttp) |
| `eventCapacity` | 2000 | Ring buffer size for events |
| `bodyLimitBytes` | 256 KB | Largest body kept per network record |
| `redactHeaders` | empty | Extra header names to mask, on top of `Authorization`, `Cookie`, `Set-Cookie` and `*token*` |
| `lifecycleJournal` | true | Whether probe-lifecycle writes transitions as events |

`probe call probe.config --input '{"journalCapacity":4}'` changes some of these at run time.

## Security

- The socket is an abstract Unix socket named `<applicationId>.probe`, reachable from the host
  only through `adb forward`. No network port is opened.
- Every connection is checked with `getPeerCredentials()` + `checkPermission(DUMP)`. adbd (shell
  or root) passes; ordinary apps do not.
- Only the default process opens the socket.
- Debug-only dependency: nothing ships in release.

## Troubleshooting

| Symptom | Cause |
|---|---|
| `probe devices` shows nothing / exit code 3 | App not running, release build, or a non-default process. Launch the debug build |
| Server never starts | The app removes `androidx.startup.InitializationProvider` (`tools:node="remove"`). Merge it back for debug, or call your own initializer |
| Every request returns `SETUP_FAILED` | Your `ProbeSetup` threw or registered a duplicate/reserved name; the message says which |
| Commands from `ProbeSetup` missing | `dev.droidprobe.SETUP` meta-data missing from the debug manifest, or the class is not in the debug source set |

## Remove it

Delete the `debugImplementation` line, the `dev.droidprobe.SETUP` meta-data and your debug-only
setup class. Nothing in `main` refers to it.

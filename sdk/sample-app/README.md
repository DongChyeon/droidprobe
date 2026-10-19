# sample-app

A small app that adopts droidprobe **without Hilt**, and the target of `scripts/e2e.sh`. Read it
as a worked example of the no-DI style; it is not published.

## How it adopts the probe

| Where | What |
|---|---|
| `build.gradle.kts` | `debugImplementation` of probe-core, probe-okhttp, probe-lifecycle |
| `src/main` | No droidprobe import. Calls `probeInterceptors()`, `recordOrderResult()` and `recordTick()` from `ProbeHooks.kt` |
| `src/debug/.../ProbeHooks.kt` | `probeInterceptors()` returns `ProbeOkHttp.interceptor()`; the record functions call `Probe.events.record` |
| `src/release/.../ProbeHooks.kt` | Same functions as no-ops |
| `src/debug/.../SampleProbeSetup.kt` | `checkout.state`, an auth guard, an `apiError` template, optional `echo` |
| `src/debug/AndroidManifest.xml` | `dev.droidprobe.SETUP` meta-data and the debug broadcast receiver |

## Run it

```sh
cd sdk
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :sample-app:installDebug            # -PwithEcho=true adds the echo command
probe list -p dev.droidprobe.sample
```

Triggers (debug broadcasts; see [../README.md](../README.md) for details):

```sh
adb shell am broadcast -a dev.droidprobe.sample.CONFIRM_ORDER -p dev.droidprobe.sample
adb shell am broadcast -a dev.droidprobe.sample.RESET -p dev.droidprobe.sample
adb shell am broadcast -a dev.droidprobe.sample.EMIT_EVENTS --ei count 3 -p dev.droidprobe.sample
probe call lifecycle.startActivity -p dev.droidprobe.sample --input '{"className":"dev.droidprobe.sample.DetailActivity"}'
```

The default base URL never resolves, so network flows are driven by mocks.

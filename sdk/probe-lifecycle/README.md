# probe-lifecycle

Activity, Fragment and process state for droidprobe, with no app code. Also the lookups that app
commands use to find the current screen objects instead of writing their own reflection.

## Add it

```kotlin
// app/build.gradle.kts
dependencies {
    debugImplementation("dev.droidprobe:probe-core:0.1.0")
    debugImplementation("dev.droidprobe:probe-lifecycle:0.1.0")
}
```

No other change. The module has its own AndroidX Startup initializer (it runs after probe-core's),
so it tracks every Activity from creation, including ones created before the host first connects.

`androidx.fragment` is `compileOnly`: the module uses Fragment callbacks when your app already
ships `androidx.fragment`, and adds no dependency to your app. Without it, the Fragment commands
return empty trees.

## Commands you get

| Command | Kind | Returns |
|---|---|---|
| `lifecycle.activities` | call | Activities in task and creation order: `id`, `class`, `state` (`created`…`resumed`…`destroyed-pending`), `taskId`, `isFinishing`, `intent` (action, component, flags, **extra keys only**, data). The resumed one is marked |
| `lifecycle.fragments` | call | Fragment tree of the resumed Activity (or `{"activityId": …}`): `class`, `tag`, `id`, `state`, `isAdded`/`isVisible`/`isHidden`/`isRemoving`, **argument keys only**, `children` |
| `lifecycle.process` | call | `pid`, `processName`, `importance` + `importanceName`, `isForeground`, `lastTrimLevel`, `uptimeMs`, `appStandbyBucket` |
| `lifecycle.watch` | stream | Live transitions `{kind: activity\|fragment\|process, class, from, to, ts}`; optional `{"kind": …}` filter |
| `lifecycle.startActivity` | call | Starts one of your app's Activities from inside the process: `{className, extras?, flags?}` |

```sh
probe call lifecycle.activities -p $PKG | jq '.[] | select(.resumed) | .class'
probe call lifecycle.fragments -p $PKG
probe watch lifecycle.watch -p $PKG --until '.to=="resumed" and (.class|endswith("DetailActivity"))' --timeout 20s

# Open a non-exported Activity with extras. `adb shell am start` is refused for these on user builds.
probe call lifecycle.startActivity -p $PKG \
  --input '{"className":"com.example.app.OrderActivity","extras":{"orderId":"o-1","fromPush":true}}'
```

`lifecycle.startActivity` only accepts Activities declared by your app; other classes return
`INVALID_INPUT`. Extras may be string, int, long or boolean.

## Navigation history between two points

Every transition is also written to the journal as an event named `lifecycle.<kind>.<to>` with
source `lifecycle`, so you can see which screens were passed through:

```sh
C=$(probe cursor -p $PKG)
# ... tap, swipe, deep link ...
probe call events.records -p $PKG --since $C \
  | jq -r '.records[] | select(.source=="lifecycle") | "\(.name) \(.params.class)"'
```

`lifecycle.watch` is live only (it ignores `--since`); use `events.records --since` for history.
Turn the journaling off with `ProbeConfig(lifecycleJournal = false)` if it is too noisy.

## Lookups for your own commands

```kotlin
probe.call("checkout.state", "Checkout screen state") { _, ctx ->
    ctx.onMain {
        val fragment = ProbeLifecycle.findFragment<CheckoutFragment>()
            ?: throw ProbeError(ProbeError.INVALID_INPUT, "CheckoutFragment is not on screen")
        val button = ProbeLifecycle.findView<Button> { it.id == R.id.pay }
        buildJsonObject {
            put("step", fragment.viewModel.step.value.name)
            put("payEnabled", button?.isEnabled)
        }
    }
}
```

| Lookup | Searches |
|---|---|
| `ProbeLifecycle.resumedActivity()` | The resumed Activity, or null |
| `ProbeLifecycle.activities()` | All live Activities |
| `ProbeLifecycle.findFragment<T> { predicate }` | Resumed Activity first, then the others, child fragments included |
| `ProbeLifecycle.findView<T> { predicate }` | The resumed Activity's window |

Call them inside `ctx.onMain { }`. Activities are held through weak references; `destroyed-pending`
means `onDestroy` ran but the object is not collected yet.

## Notes

- Process foreground/background transitions are derived from the number of started Activities,
  so a rotation is not reported as going to the background.
- Values of intent extras and fragment arguments are never sent; they can hold tokens.
- On an emulator with `adb root`, `am start` can also open non-exported Activities. The bypass
  matters on user builds and real devices.

## Remove it

Delete the `debugImplementation` line. App commands that use `ProbeLifecycle` lookups live in
your debug source set and go with it.

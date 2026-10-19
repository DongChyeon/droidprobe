# droidprobe

[![CI](https://github.com/DongChyeon/droidprobe/actions/workflows/ci.yml/badge.svg)](https://github.com/DongChyeon/droidprobe/actions/workflows/ci.yml)

droidprobe lets an AI agent, or any script, see and steer the inside of an Android debug build:
- recorded network traffic and app events
- app-defined state it can read and subscribe to
- network mocks it can set up
- the Activity stack, fragment tree and process state, with navigation history in the journal

It has two parts, plus a dashboard built into the CLI:

- **An in-process probe** (`sdk/`): Android libraries for debug builds only. The app listens on an
  abstract Unix socket that only adb can reach. Release builds contain no SDK classes.
- **An agent-first CLI** (`cli/`): `probe`, a single Go binary with no daemon, for macOS and Linux.
  - stdout is JSON only.
  - Watches always end, through `--until`, `--limit` or `--timeout`.
  - Exit codes say what went wrong, and evicted history is reported instead of silently skipped.
- **A local dashboard** (`probe ui`): the same CLI serves a read-only web page on 127.0.0.1 with live
  network, events, app state, the Activity stack and a command runner. Mocks and other
  state-changing commands need `--allow-actions`. See [cli/README.md](cli/README.md#dashboard-probe-ui).

![probe ui showing a mocked request](docs/probe-ui.png)

UI is still driven with adb. Apps extend the probe with their own commands, and the CLI never
needs a rebuild to use them.

## Quick start

Build the CLI (Go 1.26):

```sh
cd cli && go build -o bin/probe ./cmd/probe
```

Add the SDK to debug builds only. [docs/integration.md](docs/integration.md) explains the two
adoption styles (DI hook points, or same-named functions per source set).

```kotlin
// build.gradle.kts
debugImplementation("dev.droidprobe:probe-core:0.1.0")
debugImplementation("dev.droidprobe:probe-okhttp:0.1.0")
debugImplementation("dev.droidprobe:probe-lifecycle:0.1.0")

// src/debug/java/com/example/AppProbeSetup.kt, named in src/debug/AndroidManifest.xml as
// <meta-data android:name="dev.droidprobe.SETUP" android:value="com.example.AppProbeSetup" />
class AppProbeSetup : ProbeSetup {
    override fun setup(probe: ProbeRegistrar, app: Application) {
        probe.state("checkout", checkout.confirmed.map { buildJsonObject { put("confirmed", it) } })
    }
}
```

Install the debug build, launch it, then:

```sh
probe devices                                  # attached devices
probe list                                     # commands the app exposes
probe call network.records --input '{"limit":20}'
C=$(probe cursor)                              # remember "now"
adb shell input tap 540 1200                   # drive the UI with adb
probe watch events.watch --since "$C" --until '.name == "order.result"' --timeout 15s
probe watch checkout.state.watch --until '.confirmed' --timeout 10s
probe ui --open                                # dashboard at http://127.0.0.1:7070
```

With several devices or apps, pass `-s SERIAL` (or set `ANDROID_SERIAL`) and `-p PACKAGE`.

## Exit codes

| code | meaning |
|---|---|
| 0 | success; a watch hit `--until`, `--limit`, or the app ended the stream |
| 1 | the app returned an error (`UNKNOWN_COMMAND`, `INVALID_INPUT`, `STALE_CURSOR`, `MOCK_REJECTED`, ...) |
| 2 | usage: bad flags or JSON input, jq compile error, ambiguous device or app |
| 3 | connection: no device or app, connection closed before hello, protocol major mismatch |
| 4 | timeout, app `TIMEOUT`, or `--until` unmet when the stream ended or `--limit` was reached |
| 130 | interrupted (SIGINT/SIGTERM) |

## Repository

| Path | Contents |
|---|---|
| [docs/protocol.md](docs/protocol.md) | Wire protocol: the only contract between the CLI and the SDK |
| [docs/integration.md](docs/integration.md) | Adding the SDK to an app |
| [sdk/README.md](sdk/README.md) | SDK modules, extending with commands, the sample app |
| [cli/README.md](cli/README.md) | CLI usage and behaviour details |
| [scripts/e2e.sh](scripts/e2e.sh) | End-to-end checks against the sample app (`ANDROID_SERIAL=emulator-5554 scripts/e2e.sh`) |
| [.github/workflows/ci.yml](.github/workflows/ci.yml) | Go tests, SDK tests, and e2e on an API 34 emulator |

## License

Apache License 2.0. See [LICENSE](LICENSE). Security reports: [SECURITY.md](SECURITY.md).

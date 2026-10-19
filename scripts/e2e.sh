#!/usr/bin/env bash
# End-to-end checks against sdk/sample-app on one device or emulator (ANDROID_SERIAL).
# Needs: a built CLI (cli/bin/probe or $PROBE), adb, jq, curl, and a JDK usable by Gradle.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PROBE="${PROBE:-$ROOT/cli/bin/probe}"
PKG=dev.droidprobe.sample
: "${ANDROID_SERIAL:?set ANDROID_SERIAL to the target device}"
export ANDROID_SERIAL

STEP=""
trap 'echo "e2e: FAILED at: $STEP" >&2' ERR
# Leave the default build (no echo) installed whatever happens.
restore() { build_and_install >/dev/null 2>&1 || echo "e2e: could not reinstall the default build" >&2; }
trap restore EXIT

step() { STEP="$*"; echo "--- $*" >&2; }
die() { echo "e2e: FAILED at: $STEP: $*" >&2; exit 1; }
probe() { "$PROBE" -p "$PKG" "$@"; }

# expect_exit CODE CMD...: runs CMD and requires that exit code; its stdout lands in $OUT.
expect_exit() {
  local want=$1 got=0
  shift
  OUT="$("$@")" || got=$?
  [[ $got == "$want" ]] || die "expected exit $want, got $got from: $*"
}

build_and_install() {
  local apk
  (cd "$ROOT/sdk" && ./gradlew -q "$@" :sample-app:assembleDebug)
  apk="$(ls "$ROOT"/sdk/sample-app/build/outputs/apk/debug/*.apk | head -n1)"
  adb install -r -t "$apk" >/dev/null
}

start_app() {
  adb shell am force-stop "$PKG"
  adb shell monkey -p "$PKG" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1
  for _ in $(seq 1 60); do
    probe cursor >/dev/null 2>&1 && return 0
    sleep 0.5
  done
  die "probe socket did not come up"
}

confirm_order() { adb shell am broadcast -a "$PKG.CONFIRM_ORDER" -p "$PKG" >/dev/null; }
emit() { adb shell am broadcast -a "$PKG.EMIT_EVENTS" -p "$PKG" --ei count "$1" >/dev/null; }

# Extensibility: extending the app needs no new CLI.
step "extensibility: build without echo"
build_and_install
start_app
probe list | jq -e '[.commands[].name] | index("echo") == null' >/dev/null || die "echo listed"

step "extensibility: same CLI binary finds the new echo command"
build_and_install -PwithEcho=true
start_app
[[ "$(probe call echo --input '{"x":1}')" == '{"x":1}' ]] || die "echo did not round-trip"

step "Probe.state registers two commands"
probe list | jq -e '[.commands[].name] | index("checkout.state") != null and index("checkout.state.watch") != null' >/dev/null \
  || die "checkout.state commands missing"

# Watch exit codes.
step "--since is dropped for commands without it"
probe call checkout.state --since "$(probe cursor)" | jq -e '.confirmed == false' >/dev/null || die "checkout.state with --since failed"

step "watch exit codes: until satisfied -> 0"
C="$(probe cursor)"
emit 3
expect_exit 0 probe watch events.watch --since "$C" --until '.params.i == 2' --timeout 15s
[[ "$(wc -l <<<"$OUT" | tr -d ' ')" == 2 ]] || die "expected 2 lines, got: $OUT"

step "watch exit codes: limit reached -> 0"
expect_exit 0 probe watch events.watch --since "$C" --limit 2 --timeout 15s

step "watch exit codes: timeout -> 4"
expect_exit 4 probe watch events.watch --timeout 2s

step "watch exit codes: until unmet at limit -> 4"
expect_exit 4 probe watch events.watch --since "$C" --limit 3 --until '.params.i == 99' --timeout 15s

step "watch exit codes: app stopped -> 3"
got=0
probe watch events.watch --timeout 30s >/dev/null &
pid=$!
sleep 2
adb shell am force-stop "$PKG"
wait "$pid" || got=$?
[[ $got == 3 ]] || die "watch on a stopped app exited $got"
expect_exit 3 probe cursor

# Cursors.
step "cursors: since returns exactly the new events"
start_app
C="$(probe cursor)"
emit 3
n="$(probe call events.records --since "$C" | jq '.records | length')"
[[ $n == 3 ]] || die "expected 3 records, got $n"

step "cursors: cursor from a previous process -> STALE_CURSOR"
start_app
err="$(probe call events.records --since "$C" 2>&1 >/dev/null)" && die "stale cursor accepted"
grep -q STALE_CURSOR <<<"$err" || die "unexpected error: $err"

step "cursors: eviction is reported as truncated"
probe call probe.config --input '{"journalCapacity":4}' >/dev/null
C="$(probe cursor)"
emit 10
probe call events.records --since "$C" | jq -e '.truncated == true and (.oldestCursor | type == "string")' >/dev/null \
  || die "not truncated"

# Order confirmation flow on the sample app (error path, then success path).
step "order flow: 403 mock via the apiError template"
start_app
probe call network.mock.clear >/dev/null
adb shell am broadcast -a "$PKG.RESET" -p "$PKG" >/dev/null
probe call network.mock.add --input '{"path":"/orders/*/confirm","template":"apiError","vars":{"status":403,"code":"OUT_OF_STOCK"}}' >/dev/null
C="$(probe cursor)"
confirm_order
expect_exit 0 probe watch network.watch --since "$C" --until '.mocked == true and .status == 403' --timeout 15s
expect_exit 0 probe watch events.watch --since "$C" --until '.name == "order.result" and .params.outcome == "ShowError"' --timeout 15s

step "order flow: checkout stays unconfirmed for 10s -> 4"
start_s=$SECONDS
expect_exit 4 probe watch checkout.state.watch --until '.confirmed == true' --timeout 10s
(( SECONDS - start_s <= 15 )) || die "watch took $((SECONDS - start_s))s"

step "order flow: clear, then the success path with a 200 mock"
probe call network.mock.clear | jq -e '.removed >= 1' >/dev/null || die "nothing cleared"
probe call network.mock.add --input '{"path":"/orders/*/confirm","status":200,"headers":{"Content-Type":"application/json"},"body":"{\"ok\":true}"}' >/dev/null
C="$(probe cursor)"
confirm_order
expect_exit 0 probe watch network.watch --since "$C" --until '.status == 200' --timeout 15s
expect_exit 0 probe watch events.watch --since "$C" --until '.name == "order.result" and .params.outcome == "Confirmed"' --timeout 15s
expect_exit 0 probe watch checkout.state.watch --until '.confirmed == true' --timeout 15s

step "mock persistence: persist=false rules vanish on restart, persist=true rules stay"
probe call network.mock.clear >/dev/null
probe call network.mock.add --input '{"path":"/kept","status":204,"persist":true}' >/dev/null
probe call network.mock.add --input '{"path":"/dropped","status":204}' >/dev/null
start_app
probe call network.mock.list | jq -e 'length == 1 and .[0].path == "/kept" and .[0].persist == true' >/dev/null \
  || die "unexpected rules after restart"
probe call network.mock.clear >/dev/null

step "the app's mock guard rejects AUTH_EXPIRED"
err="$(probe call network.mock.add --input '{"path":"/orders/*/confirm","template":"apiError","vars":{"status":401,"code":"AUTH_EXPIRED"}}' 2>&1 >/dev/null)" && die "AUTH_EXPIRED accepted"
grep -q MOCK_REJECTED <<<"$err" || die "unexpected error: $err"

# probe-lifecycle: Activity state, in-process start of a non-exported Activity, transitions, journal.
step "lifecycle.activities shows MainActivity resumed"
start_app
probe call lifecycle.activities \
  | jq -e 'map(select(.resumed)) | length == 1 and .[0].class == "dev.droidprobe.sample.MainActivity" and .[0].state == "resumed"' >/dev/null \
  || die "MainActivity is not the resumed Activity"

step "lifecycle.startActivity opens the non-exported DetailActivity; lifecycle.watch sees it resume"
C="$(probe cursor)"
WATCH_OUT="$(mktemp)"
got=0
probe watch lifecycle.watch --input '{"kind":"activity"}' \
  --until '.class == "dev.droidprobe.sample.DetailActivity" and .to == "resumed"' --timeout 20s >"$WATCH_OUT" &
pid=$!
sleep 2
probe call lifecycle.startActivity --input '{"className":"dev.droidprobe.sample.DetailActivity","extras":{"orderId":"order-7"}}' \
  | jq -e '.started == true' >/dev/null || die "startActivity did not report started"
wait "$pid" || got=$?
[[ $got == 0 ]] || die "lifecycle.watch exited $got: $(cat "$WATCH_OUT")"
rm -f "$WATCH_OUT"
probe call lifecycle.activities \
  | jq -e 'map(select(.resumed))[0] | .class == "dev.droidprobe.sample.DetailActivity" and (.intent.extraKeys | index("orderId") != null)' >/dev/null \
  || die "DetailActivity is not resumed"
probe call lifecycle.fragments | jq -e '.fragments[0].tag == "detail" and .fragments[0].argumentsKeys == ["orderId"]' >/dev/null \
  || die "DetailFragment missing from the fragment tree"

step "events.records --since holds the lifecycle history"
probe call events.records --since "$C" --input '{"nameContains":"lifecycle."}' \
  | jq -e '[.records[] | select(.name == "lifecycle.activity.resumed" and .source == "lifecycle")] | length >= 1' >/dev/null \
  || die "lifecycle.activity.resumed not journaled"

step "lifecycle.startActivity rejects a class that is not an Activity of the app"
err="$(probe call lifecycle.startActivity --input '{"className":"java.lang.String"}' 2>&1 >/dev/null)" && die "java.lang.String accepted"
grep -q INVALID_INPUT <<<"$err" || die "unexpected error: $err"

step "lifecycle.process returns a pid"
probe call lifecycle.process | jq -e '(.pid | type == "number") and .pid > 0' >/dev/null || die "no pid"

step "main sources do not import the SDK"
if grep -rEn '^import dev\.droidprobe\.' "$ROOT/sdk/sample-app/src/main" | grep -v 'import dev\.droidprobe\.sample\.'; then
  die "main sources import dev.droidprobe"
fi

step "probe ui: read-only API, SSE relay, clean exit"
UI_OUT="$(mktemp)"
SSE_OUT="$(mktemp)"
"$PROBE" -p "$PKG" ui --port 0 >"$UI_OUT" 2>/dev/null &
ui_pid=$!
URL=""
for _ in $(seq 1 20); do
  URL="$(jq -r '.url // empty' "$UI_OUT" 2>/dev/null || true)"
  [[ -n $URL ]] && curl -sf "$URL/api/hello" >/dev/null && break
  sleep 0.5
done
curl -sf "$URL/api/hello" >/dev/null || die "probe ui did not come up"
curl -sf "$URL/api/list" | jq -e '[.commands[] | select(.name == "network.mock.add")][0].readOnly == false' >/dev/null \
  || die "/api/list lacks readOnly flags"
code="$(curl -s -o /dev/null -w '%{http_code}' -H 'Content-Type: application/json' \
  -d '{"command":"network.mock.add","input":{"path":"/x","status":204}}' "$URL/api/call")"
[[ $code == 403 ]] || die "network.mock.add in read-only mode returned $code"
(curl -sN --max-time 8 "$URL/api/stream?cmd=events.watch" >"$SSE_OUT" || true) &
curl_pid=$!
sleep 2
emit 3
wait "$curl_pid"
grep -q '"name":"sample.tick"' "$SSE_OUT" || die "no sample.tick over /api/stream"
kill -INT "$ui_pid"
wait "$ui_pid" || die "probe ui exited non-zero on SIGINT"
rm -f "$UI_OUT" "$SSE_OUT"

step "no probe forwards left behind on this device"
# Other devices may run their own probe (for example a developer's probe ui), so only this serial counts.
if adb forward --list | grep -F "$ANDROID_SERIAL " | grep -F '.probe'; then
  die "stale forwards"
fi

echo "e2e: all checks passed" >&2

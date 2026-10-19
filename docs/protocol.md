# droidprobe wire protocol

Version **1.0**. This file is the only contract shared by the CLI (`cli/`) and the SDK (`sdk/`).
Neither side may depend on the other's types.

## Transport

- The app listens on an abstract-namespace Unix socket named `<packageName>.probe`
  (`LocalServerSocket`). Only the default process opens it.
- The host reaches it with `adb -s <serial> forward tcp:0 localabstract:<packageName>.probe`,
  then connects to `127.0.0.1:<port>`. The CLI removes the forward on every exit path.
- On accept, the app reads the peer pid/uid from `LocalSocket.getPeerCredentials()` and requires
  `checkPermission("android.permission.DUMP", pid, uid) == PERMISSION_GRANTED`.
  On failure it closes the socket without writing anything.
- Framing: NDJSON. One UTF-8 JSON object per line, `\n` terminated. No line may contain a raw newline.
- One connection may carry several concurrent requests, distinguished by `id`.
  The app serializes writes so lines never interleave.

## Requests (host → app)

```json
{"id": "1", "op": "hello", "protocol": "1.0"}
{"id": "2", "op": "list"}
{"id": "3", "op": "describe", "command": "network.records"}
{"id": "4", "op": "call", "command": "network.records", "input": {"since": "a1f3c09e:1041"}}
{"id": "5", "op": "watch", "command": "events.watch", "input": {"since": "a1f3c09e:1041"}}
{"id": "6", "op": "cancel", "target": "5"}
```

- `id`: string chosen by the host, unique within the connection.
- `input`: JSON object; omitted means `{}`.
- The first request on a connection must be `hello`. Any other op before it yields `BAD_REQUEST`.

## Responses (app → host)

```json
{"id": "4", "type": "result", "data": { }}
{"id": "5", "type": "event", "data": { }}
{"id": "5", "type": "end"}
{"id": "4", "type": "error", "error": {"code": "STALE_CURSOR", "message": "..."}}
```

- `call`, `list`, `describe`, `hello`, `cancel` produce exactly one `result` or `error`.
- `watch` produces zero or more `event`, then exactly one `end` or `error`.
- `cancel` stops the target watch: the target receives `end`, then the cancel request receives
  `result` with `{}` (or `error` `BAD_REQUEST` if the target is unknown or already finished).
- `call` on a stream command, or `watch` on a call command, is `BAD_REQUEST` (input validation
  runs first, so a bad input on the wrong op may surface as `INVALID_INPUT` instead).
- A malformed line or a request without `id` yields an `error` with `"id": null`.
- If the app's `ProbeSetup` fails, `hello` still succeeds and every later request yields `SETUP_FAILED`.

### hello result

```json
{"protocol": "1.0", "sdk": "0.1.0", "package": "com.example", "pid": 12345,
 "processStartId": "a1f3c09e", "headCursor": "a1f3c09e:1041"}
```

- `processStartId`: 8 lowercase hex chars generated once per process start.
- `headCursor`: cursor of the last written Journal entry (`<processStartId>:0` when empty).
- The CLI compares major versions; a mismatch makes the CLI exit with code 3 before any other request.

### list result

```json
{"commands": [{"name": "network.records", "kind": "call", "description": "..."}]}
```

`kind` is `call` or `stream`. Sorted by name.

### describe result

```json
{"name": "network.records", "kind": "call", "description": "...", "input": {schema}, "output": {schema} | null}
```

### Schema subset

JSON Schema keywords used: `type` (string or array of strings among
`object|array|string|integer|number|boolean|null`), `properties`, `required`, `enum`,
`items`, `additionalProperties` (boolean), `description`. Nothing else.
The app validates `input` before running a command; failures yield `INVALID_INPUT`
with a message naming the JSON path (for example `$.limit: expected integer`).

## Cursors

- Format: `"<processStartId>:<seq>"`. `seq` is a single monotonically increasing counter shared by
  every Journal entry kind (network and event).
- `since` selects entries with `seq > since.seq`.
- A cursor whose `processStartId` differs from the current process yields `STALE_CURSOR`.
- Each kind keeps a ring buffer and remembers `evictedThrough` (highest evicted seq of that kind).
  When `since.seq < evictedThrough`, the result carries `"truncated": true` and
  `"oldestCursor": "<id>:<oldest retained seq of that kind>"`. With `"strict": true` in the input
  it is an error `CURSOR_EVICTED` instead.
- Malformed cursor strings yield `INVALID_INPUT`.

### watch with since

1. Subscribe to live entries first and buffer them.
2. Send backlog entries with `since.seq < seq <= head` in seq order.
3. Then send buffered and later live entries whose `seq` is greater than the last sent seq.

Without `since`, only live entries are sent. `truncated` is computed only when `since` is given;
a `watch` with an evicted backlog does not report truncation (use `records` first when that matters).
Built-in command input schemas are closed (`additionalProperties: false`), so the host must only
send `since` to commands whose schema declares it. `<name>.state` / `<name>.state.watch`
accept any input and ignore it.

## Built-in commands

| name | kind | input | data |
|---|---|---|---|
| `probe.info` | call | — | `{protocol, sdk, package, pid, processStartId, headCursor, recording, config:{networkCapacity,eventCapacity,bodyLimitBytes}}` |
| `probe.config` | call | `{journalCapacity?: int, bodyLimitBytes?: int, recording?: bool}` | same as `probe.info.config` plus `recording`. `journalCapacity` sets both kinds' capacities |
| `events.records` | call | `{since?, limit?: int (default 200), nameContains?, strict?: bool}` | `{records:[Event], nextCursor, truncated, oldestCursor?}` |
| `events.watch` | stream | `{since?, nameContains?}` | each event is an `Event` |
| `network.records` | call | `{since?, limit?, filter?:{method?, urlContains?, status?: int, mocked?: bool}, strict?}` | `{records:[NetworkRecord], nextCursor, truncated, oldestCursor?}` |
| `network.record` | call | `{id: string}` | `NetworkRecord` with full stored bodies |
| `network.watch` | stream | `{since?, filter?}` | each event is a `NetworkRecord` |
| `network.mock.add` | call | `MockRule` (without `id`) | `{id}` |
| `network.mock.list` | call | — | `[MockRule]` (array, with `id` and `remaining`) |
| `network.mock.remove` | call | `{id}` | `{removed: bool}` |
| `network.mock.clear` | call | — | `{removed: int}` (includes persisted rules) |
| `network.mock.templates` | call | — | `[{name, description}]` |
| `lifecycle.activities` | call | — | `[{id, class, state, resumed: bool, taskId: int, isFinishing: bool, intent:{action, component, flags: int, extraKeys:[string], data}}]` ordered by task, then creation. `state` is `created`, `started`, `resumed`, `paused`, `stopped` or `destroyed-pending` (destroyed, not yet garbage collected). Extra values are never sent |
| `lifecycle.fragments` | call | `{activityId?}` (default: the resumed Activity) | `{activityId, class, fragments:[Fragment]}`, `Fragment` = `{class, tag, id: int, state, isAdded, isVisible, isHidden, isRemoving, argumentsKeys:[string], children:[Fragment]}` |
| `lifecycle.process` | call | — | `{pid, processName, importance: int, importanceName, lastTrimLevel: int, uptimeMs: int, isForeground: bool, appStandbyBucket: int or null (API 28+)}` |
| `lifecycle.watch` | stream | `{kind?: "activity" or "fragment" or "process"}` | each event is `{kind, id, class, from, to, ts}`; `from` is null when first seen, `ts` is epoch ms. Live only |
| `lifecycle.startActivity` | call | `{className, extras?:{string: string or integer or boolean}, flags?: int}` | `{started: true}`; `INVALID_INPUT` unless `className` is an Activity declared by the app |

`records` are in ascending seq order; `limit` (default 200) keeps the **oldest** `limit` entries
after `since` so callers can page with `nextCursor` (cursor of the last returned entry, or the
input `since` / head when empty). `network.record` with an unknown `id` is `INVALID_INPUT`.
`ts` is the append time, so for network records it is the end of the exchange. `source` is
always present on events (`null` when not given).
probe-lifecycle also appends every transition as an event named `lifecycle.<kind>.<to>` with
source `lifecycle` and params `{class, from, to, id}`, unless `ProbeConfig.lifecycleJournal` is false.

### Event

```json
{"cursor": "a1f3c09e:1043", "type": "event", "ts": "2026-10-16T10:12:03.120Z",
 "name": "checkout.order.result", "source": "checkout", "params": {"outcome": "ShowError"}}
```

### NetworkRecord

```json
{"cursor": "a1f3c09e:1042", "type": "network", "ts": "...", "id": "n-311",
 "method": "GET", "url": "https://host/path?q", "status": 403, "durationMs": 1,
 "mocked": true, "mockRuleId": "m-2", "error": null,
 "requestHeaders": {"Authorization": "***"}, "requestBody": null, "requestBodySize": 0, "requestBodyTruncated": false,
 "responseHeaders": {"Content-Type": "application/json"},
 "responseBody": "{...}", "responseBodySize": 41, "responseBodyTruncated": false}
```

- `error` is a string (exception class and message) when the call failed with an IOException; `status` is then `null`.
- Bodies are kept as text only when the content type is textual (`text/*`, `*json*`, `*xml*`,
  `application/x-www-form-urlencoded`); otherwise the body field is `null` and only the size is set.
  Streaming types (`text/event-stream`, `*ndjson*`, `jsonl`, `x-jsonlines`) are never read.
- A body larger than `bodyLimitBytes` is not stored: the body field is `null` and
  `requestBodyTruncated` / `responseBodyTruncated` is `true`. A body of unknown length is read
  only up to `bodyLimitBytes + 1` bytes, so when it is over the limit its size is `null` too.
- `durationMs` ends when the response headers arrive (or the call fails); reading the body for the
  record is not included.
- `network.records` omits `requestBody` and `responseBody` longer than 4096 chars (sets them to
  `null` and adds `"bodyOmitted": true`); `network.record` returns them in full (up to `bodyLimitBytes`).
- Header masking: `Authorization`, `Cookie`, `Set-Cookie`, and any header whose name contains
  `token` (case-insensitive), plus app-configured names, become `"***"`.

### MockRule

```json
{"id": "m-2", "method": "GET", "path": "/api/v1/orders/*/confirm",
 "status": 403, "headers": {"Content-Type": "application/json"}, "body": "{...}",
 "template": null, "vars": null, "delayMs": 0, "failure": null,
 "times": null, "remaining": null, "persist": false, "allowAuthSideEffects": false}
```

- `method`: optional; matches case-insensitively; absent matches any method.
- `path`: glob over the encoded URL path. `*` matches one segment (no `/`), `**` matches any
  number of segments.
- Response source: either `status`/`headers`/`body`, or `template` + `vars` (the app-registered
  template produces status/headers/body). `failure`: `"timeout"` throws `SocketTimeoutException`,
  `"disconnect"` throws `IOException`; then status/body are ignored.
- `times`: optional positive int; the rule is removed after that many matches.
- `persist: true` stores the rule so it survives process restarts.
- `add` rejects `id` and `remaining`, and a `template` combined with `status` or `body`.
  The rendered status/headers/body are stored on the rule and returned by `list` next to
  `template`/`vars`; the guard sees the rendered body.
- A mock body without `Content-Type` gets `application/json; charset=utf-8` when it starts with
  `{` or `[`, otherwise `text/plain; charset=utf-8`.
- On `add`, the app's guard (if any) runs; rejection yields `MOCK_REJECTED` with its reason.
- First matching rule in insertion order wins.

## App-defined state commands

`Probe.state("checkout", ...)` registers `checkout.state` (call, current value) and
`checkout.state.watch` (stream; first event is the current value, then distinct changes).

## Error codes

| code | meaning | CLI exit code |
|---|---|---|
| `BAD_REQUEST` | malformed line, unknown op, missing hello, bad cancel target | 1 |
| `UNKNOWN_COMMAND` | no such command (message may suggest a close name) | 1 |
| `INVALID_INPUT` | input fails the schema or a cursor is malformed | 1 |
| `STALE_CURSOR` | cursor from another process | 1 |
| `CURSOR_EVICTED` | strict mode and the range was evicted | 1 |
| `MOCK_REJECTED` | the app's mock guard refused the rule | 1 |
| `SETUP_FAILED` | the app's ProbeSetup threw or registered conflicting names | 1 |
| `INTERNAL` | command threw; message carries exception class and message | 1 |
| `TIMEOUT` | call exceeded the app-side timeout (default 10 s) | 4 |

Connection refused or closed before `hello` completes, and protocol major mismatch, are exit code 3.

## Compatibility

Adding fields or commands is a minor change. Removing fields or changing meaning is a major change.
Both sides ignore unknown fields.

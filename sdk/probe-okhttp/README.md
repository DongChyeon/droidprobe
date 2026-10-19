# probe-okhttp

Records OkHttp traffic and injects mock responses or failures, so the host can check what the app
sent and received, and drive error paths without a proxy or a test server.

This is the one module that needs a change in your `main` code: OkHttpClient is built by your
app, so a library cannot add itself to it. The change is an empty extension point; the probe
fills it from a debug-only source set.

## Add it

```kotlin
// app/build.gradle.kts
dependencies {
    debugImplementation("dev.droidprobe:probe-core:0.1.0")
    debugImplementation("dev.droidprobe:probe-okhttp:0.1.0")
}
```

Without the step below, `network.*` commands appear in `probe list` but record nothing.

## Attach the interceptor

Pick the style that matches your app. All keep droidprobe out of release builds.

### With Hilt/Dagger: an empty set in `main`, filled in debug

```kotlin
// main: declares the extension point (no droidprobe import)
@Qualifier annotation class DebugNetworkInterceptors

@Module @InstallIn(SingletonComponent::class)
abstract class NetworkHooksModule {
    @Multibinds @DebugNetworkInterceptors
    abstract fun debugNetworkInterceptors(): Set<@JvmSuppressWildcards Interceptor>
}

// main: where the client is built
fun createClient(@DebugNetworkInterceptors debug: Set<@JvmSuppressWildcards Interceptor>) =
    OkHttpClient.Builder()
        .addInterceptor(authInterceptor)
        .addInterceptor(loggingInterceptor)
        .apply { debug.forEach(::addInterceptor) }   // empty in release
        .build()
```

```kotlin
// src/debug: fills it
@Module @InstallIn(SingletonComponent::class)
object ProbeNetworkModule {
    @Provides @IntoSet @DebugNetworkInterceptors
    fun probeInterceptor(): Interceptor = ProbeOkHttp.interceptor()
}
```

Add a unit test that the interceptor list is unchanged when the set is empty.

### Without DI: same-named function per source set

```kotlin
// src/debug/java/.../ProbeHooks.kt
fun OkHttpClient.Builder.addProbe() = addInterceptor(ProbeOkHttp.interceptor())
// src/release/java/.../ProbeHooks.kt
fun OkHttpClient.Builder.addProbe() = this
// main
OkHttpClient.Builder().addProbe().build()
```

### Where in the chain

Add it as an application interceptor **after** interceptors that add auth headers or translate
error responses, and **before** interceptors that should not see mocked calls (for example Chucker):

- It records the request as your outer interceptors left it (auth header included, masked).
- It records the raw server response, before your error interceptor turns it into an exception.
- A mocked response travels back through the outer interceptors exactly like a server response,
  so your real error handling runs. Inner interceptors and the network never see it.
- Retries and token refreshes that call `chain.proceed` again are recorded as separate entries.

One shared instance can be added to every client.

## Read traffic

```sh
C=$(probe cursor -p $PKG)
# ... trigger a request ...
probe call network.records -p $PKG --since $C | jq '.records[] | {method, url, status, durationMs, mocked}'
probe call network.record -p $PKG --input '{"id":"n-12"}'          # full bodies
probe watch network.watch -p $PKG --since $C --until '.url|test("/orders")' --timeout 20s
```

`network.records` accepts `filter: {method, urlContains, status, mocked}` and `limit`.

What is stored:

- Headers `Authorization`, `Cookie`, `Set-Cookie` and any name containing `token` are `***`
  (add more with `ProbeConfig.redactHeaders`). **Bodies are not masked.**
- Text bodies (`text/*`, JSON, XML, form) up to `bodyLimitBytes` (256 KB). Larger bodies and
  binary types keep only the size; streaming types (event-stream, ndjson) are never read.
  Request bodies over the limit are never copied, so large uploads are not held in memory.
- `durationMs` excludes reading the body.

## Mock responses

```sh
probe call network.mock.add -p $PKG --input '{
  "method": "GET", "path": "/api/v1/orders/*",
  "status": 503, "body": "{\"code\":\"MAINTENANCE\"}",
  "times": 1 }'
probe call network.mock.list -p $PKG
probe call network.mock.clear -p $PKG
```

| Field | Meaning |
|---|---|
| `method` | Optional, case-insensitive |
| `path` | Glob over the URL path: `*` one segment, `**` any number |
| `status`, `headers`, `body` | The response; a JSON-looking body gets `application/json; charset=utf-8` if no Content-Type is given |
| `template` + `vars` | Instead of status/body, render an app-registered template |
| `delayMs` | Wait before answering |
| `failure` | `"timeout"` throws `SocketTimeoutException`, `"disconnect"` throws `IOException` |
| `times` | Remove after this many matches |
| `persist` | Keep across process restarts (stored in a probe-only SharedPreferences); applies from the first request after launch |
| `allowAuthSideEffects` | Opt-in flag your guard can check |

The first matching rule wins. Narrow globs: `/api/**` would also catch your token refresh call.

### Templates and guards (registered by your app)

The library does not know your server's error format or which responses are dangerous. Teach it
in your debug `ProbeSetup`:

```kotlin
override fun setup(probe: ProbeRegistrar, app: Application) {
    // Shorthand for your API's error envelope
    probe.network.template("apiError", "Error envelope; vars: status (default 400), code, message") { vars ->
        MockedResponse(
            status = vars.intOrNull("status") ?: 400,
            headers = mapOf("Content-Type" to "application/json"),
            body = buildJsonObject {
                put("code", vars.stringOrNull("code") ?: throw ProbeError(ProbeError.INVALID_INPUT, "$.vars.code: required"))
                put("message", vars.stringOrNull("message") ?: "mock")
            }.toString(),
        )
    }

    // Refuse mocks that would log the user out, unless explicitly allowed
    probe.network.guard { rule ->
        val code = rule.body?.let { runCatching { Json.parseToJsonElement(it).jsonObject.stringOrNull("code") }.getOrNull() }
        if (code in setOf("AUTH_EXPIRED", "AUTH_REVOKED") && !rule.allowAuthSideEffects) {
            "$code clears the session; set allowAuthSideEffects to mock it"
        } else null
    }
}
```

```sh
probe call network.mock.templates -p $PKG
probe call network.mock.add -p $PKG --input '{"path":"/api/v1/orders/*","template":"apiError","vars":{"status":403,"code":"FORBIDDEN"}}'
```

A guard runs when a rule is **added** and sees the rendered body; a rejection returns
`MOCK_REJECTED` (CLI exit code 1). Without a guard, every rule is accepted.

## Limits

- Only traffic through OkHttp clients you attached it to. Media players with their own stack,
  image loaders on another client, and WebView are not seen or mocked.
- Records live in memory (ring buffer); they are gone when the process dies.

## Remove it

Delete the dependency and the debug module that provides the interceptor. The empty set or the
release no-op function in `main` can stay or be removed in a normal PR.

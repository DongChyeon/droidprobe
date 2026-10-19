package dev.droidprobe.okhttp

import dev.droidprobe.Journal
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.Headers
import okhttp3.Interceptor
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.Sink
import okio.Timeout
import okio.buffer
import java.io.IOException
import java.io.InterruptedIOException
import java.net.SocketTimeoutException
import java.nio.charset.Charset

/** Add as an application interceptor. Applies the first matching mock rule, then records the exchange. */
class ProbeInterceptor internal constructor(private val network: NetworkProbe) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val startedNs = System.nanoTime()
        val rule = try {
            network.store.match(request.method, request.url.encodedPath)
        } catch (_: Exception) {
            null
        }
        val response = try {
            if (rule == null) chain.proceed(request) else mock(rule, request)
        } catch (error: IOException) {
            record(request, null, rule, elapsedMs(startedNs), error)
            throw error
        }
        // Measured before the body is peeked, so recording does not inflate it.
        record(request, response, rule, elapsedMs(startedNs), null)
        return response
    }

    private fun mock(rule: MockRule, request: Request): Response {
        if (rule.delayMs > 0) {
            try {
                Thread.sleep(rule.delayMs)
            } catch (_: InterruptedException) {
                throw InterruptedIOException("droidprobe mock delay interrupted")
            }
        }
        when (rule.failure) {
            MockRule.FAILURE_TIMEOUT -> throw SocketTimeoutException("droidprobe mock ${rule.id}: timeout")
            MockRule.FAILURE_DISCONNECT -> throw IOException("droidprobe mock ${rule.id}: disconnect")
        }
        val status = rule.status ?: 200
        val headers = Headers.Builder().apply { rule.headers.forEach { (name, value) -> add(name, value) } }
        val body = rule.body ?: ""
        if (rule.body != null && headers[CONTENT_TYPE] == null) {
            val trimmed = body.trimStart()
            val type = if (trimmed.startsWith("{") || trimmed.startsWith("[")) "application/json" else "text/plain"
            headers.add(CONTENT_TYPE, "$type; charset=utf-8")
        }
        val built = headers.build()
        val now = System.currentTimeMillis()
        return Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(status)
            .message("droidprobe mock")
            .headers(built)
            .body(body.toResponseBody(built[CONTENT_TYPE]?.toMediaTypeOrNull()))
            .sentRequestAtMillis(now)
            .receivedResponseAtMillis(now)
            .build()
    }

    private fun elapsedMs(startedNs: Long): Long = (System.nanoTime() - startedNs) / 1_000_000

    private fun record(request: Request, response: Response?, rule: MockRule?, durationMs: Long, error: IOException?) {
        try {
            val core = network.core
            if (!core.recording) return
            val limit = core.bodyLimitBytes
            val extraRedacted = core.config.redactHeaders
            val requestBody = requestBodyOf(request, limit)
            val responseBody = response?.let { responseBodyOf(it, limit) }
            core.journal.append(Journal.Kind.NETWORK, buildJsonObject {
                put("id", network.nextRecordId())
                put("method", request.method)
                put("url", request.url.toString())
                put("status", response?.code)
                put("durationMs", durationMs)
                put("mocked", rule != null)
                put("mockRuleId", rule?.id)
                put("error", error?.let { "${it.javaClass.name}: ${it.message}" })
                put("requestHeaders", maskHeaders(request.headers, extraRedacted))
                put("requestBody", requestBody.text)
                put("requestBodySize", requestBody.size)
                put("requestBodyTruncated", requestBody.truncated)
                put("responseHeaders", response?.let { maskHeaders(it.headers, extraRedacted) } ?: JsonNull)
                put("responseBody", responseBody?.text)
                put("responseBodySize", responseBody?.size)
                put("responseBodyTruncated", responseBody?.truncated ?: false)
            })
        } catch (_: Throwable) {
            // Recording must never break the app's request.
        }
    }

    private class Body(val text: String?, val size: Long?, val truncated: Boolean)

    private fun requestBodyOf(request: Request, limit: Int): Body {
        val body = request.body ?: return Body(null, 0, false)
        val size = body.contentLength().takeIf { it >= 0 }
        if (body.isOneShot() || body.isDuplex() || !isTextual(body.contentType())) return Body(null, size, false)
        if (size != null && size > limit) return Body(null, size, true)
        // Holds at most limit + 1 bytes even when the declared length is unknown or wrong.
        val sink = LimitedSink(limit.toLong() + 1)
        val buffered = sink.buffer()
        try {
            body.writeTo(buffered)
            buffered.flush()
        } catch (_: IOException) {
            if (!sink.reachedLimit) return Body(null, size, false)
        }
        if (sink.reachedLimit || sink.buffer.size > limit) return Body(null, size, true)
        val total = sink.buffer.size
        return Body(sink.buffer.readString(charsetOf(body.contentType())), size ?: total, false)
    }

    private fun responseBodyOf(response: Response, limit: Int): Body {
        val body = response.body ?: return Body(null, 0, false)
        val type = body.contentType()
        val declared = body.contentLength().takeIf { it >= 0 }
        // Streams may never end, so peeking them would block the caller.
        if (!isTextual(type) || isStreaming(type)) return Body(null, declared, false)
        if (declared != null && declared > limit) return Body(null, declared, true)
        val bytes = response.peekBody(limit.toLong() + 1).bytes()
        if (bytes.size > limit) return Body(null, declared, true)
        return Body(String(bytes, charsetOf(type)), bytes.size.toLong(), false)
    }

    private fun charsetOf(type: MediaType?): Charset = type?.charset(Charsets.UTF_8) ?: Charsets.UTF_8

    private companion object {
        const val CONTENT_TYPE = "Content-Type"
    }
}

/** Collects up to [limit] bytes, then fails the write so a large body is never held in memory. */
private class LimitedSink(private val limit: Long) : Sink {
    val buffer = Buffer()
    var reachedLimit = false
        private set

    override fun write(source: Buffer, byteCount: Long) {
        val room = limit - buffer.size
        if (byteCount > room) {
            buffer.write(source, room)
            source.skip(byteCount - room)
            reachedLimit = true
            throw IOException("droidprobe body limit reached")
        }
        buffer.write(source, byteCount)
    }

    override fun flush() = Unit
    override fun timeout(): Timeout = Timeout.NONE
    override fun close() = Unit
}

private fun isStreaming(type: MediaType?): Boolean =
    type?.subtype?.let { it == "event-stream" || it.contains("ndjson") || it == "jsonl" || it == "x-jsonlines" } == true

private val DEFAULT_REDACTED = setOf("authorization", "cookie", "set-cookie")

internal fun isTextual(type: MediaType?): Boolean = type != null && (
    type.type == "text" || type.subtype.contains("json") || type.subtype.contains("xml") ||
        type.subtype == "x-www-form-urlencoded"
    )

/** Masks credentials: the defaults, any name containing "token", and [extra], all case-insensitive. */
internal fun maskHeaders(headers: Headers, extra: Set<String>): JsonObject {
    val redacted = DEFAULT_REDACTED + extra.map { it.lowercase() }
    val values = LinkedHashMap<String, String>()
    for (index in 0 until headers.size) {
        val name = headers.name(index)
        val lower = name.lowercase()
        val value = if (lower in redacted || "token" in lower) "***" else headers.value(index)
        val key = values.keys.firstOrNull { it.equals(name, ignoreCase = true) } ?: name
        values[key] = values[key]?.let { "$it, $value" } ?: value
    }
    return JsonObject(values.mapValues { JsonPrimitive(it.value) })
}

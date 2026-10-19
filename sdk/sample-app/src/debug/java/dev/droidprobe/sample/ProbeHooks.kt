package dev.droidprobe.sample

import dev.droidprobe.Probe
import dev.droidprobe.okhttp.ProbeOkHttp
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.Interceptor

fun probeInterceptors(): List<Interceptor> = listOf(ProbeOkHttp.interceptor())

fun recordOrderResult(outcome: String, reason: String?) {
    Probe.events.record(
        "order.result",
        buildJsonObject {
            put("outcome", outcome)
            if (reason != null) put("reason", reason)
        },
        source = "sample",
    )
}

fun recordTick(i: Int) {
    Probe.events.record("sample.tick", buildJsonObject { put("i", i) }, source = "sample")
}

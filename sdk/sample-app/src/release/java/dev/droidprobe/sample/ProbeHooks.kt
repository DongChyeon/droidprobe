package dev.droidprobe.sample

import okhttp3.Interceptor

fun probeInterceptors(): List<Interceptor> = emptyList()

@Suppress("UNUSED_PARAMETER")
fun recordOrderResult(outcome: String, reason: String?) = Unit

@Suppress("UNUSED_PARAMETER")
fun recordTick(i: Int) = Unit

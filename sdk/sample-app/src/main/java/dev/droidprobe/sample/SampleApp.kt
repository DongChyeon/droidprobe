package dev.droidprobe.sample

import android.app.Application
import kotlinx.coroutines.flow.MutableStateFlow
import okhttp3.OkHttpClient

class SampleApp : Application() {
    val http: OkHttpClient by lazy {
        OkHttpClient.Builder().apply { probeInterceptors().forEach(::addInterceptor) }.build()
    }
}

object Checkout {
    val confirmed = MutableStateFlow(false)
}

package dev.droidprobe.sample

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlin.concurrent.thread

/** Declared only in the debug manifest. */
class OrderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_RESET -> Checkout.confirmed.value = false
            ACTION_EMIT_EVENTS -> (1..intent.getIntExtra("count", 1)).forEach(::recordTick)
            ACTION_CONFIRM_ORDER -> {
                val app = context.applicationContext as SampleApp
                val baseUrl = intent.getStringExtra("baseUrl") ?: DEFAULT_BASE_URL
                val orderId = intent.getStringExtra("orderId") ?: "order-1"
                val pending = goAsync()
                thread(name = "sample-order") {
                    try {
                        OrderFlow(app.http).confirm(baseUrl, orderId)
                    } finally {
                        pending.finish()
                    }
                }
            }
        }
    }

    companion object {
        const val ACTION_CONFIRM_ORDER = "dev.droidprobe.sample.CONFIRM_ORDER"
        const val ACTION_RESET = "dev.droidprobe.sample.RESET"
        const val ACTION_EMIT_EVENTS = "dev.droidprobe.sample.EMIT_EVENTS"

        // Unresolvable on purpose: every flow is driven by mocks unless a baseUrl extra is given.
        const val DEFAULT_BASE_URL = "https://example.invalid"
    }
}

package dev.droidprobe.sample

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException

/** What confirming an order does: call the confirm endpoint, then either mark checkout confirmed or show an error. */
class OrderFlow(private val http: OkHttpClient) {

    fun confirm(baseUrl: String, orderId: String) {
        val request = Request.Builder().url("${baseUrl.trimEnd('/')}/orders/$orderId/confirm").build()
        try {
            http.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    recordOrderResult("Confirmed", null)
                    Checkout.confirmed.value = true
                } else {
                    recordOrderResult("ShowError", errorCode(response.body?.string()) ?: "HTTP ${response.code}")
                }
            }
        } catch (error: IOException) {
            recordOrderResult("ShowError", error.javaClass.simpleName)
        }
    }

    private fun errorCode(body: String?): String? =
        runCatching { JSONObject(body ?: return null).optString("code").takeIf { it.isNotEmpty() } }.getOrNull()
}

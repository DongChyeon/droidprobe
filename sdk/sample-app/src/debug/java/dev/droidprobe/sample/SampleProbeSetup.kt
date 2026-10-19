package dev.droidprobe.sample

import android.app.Application
import dev.droidprobe.ProbeError
import dev.droidprobe.ProbeRegistrar
import dev.droidprobe.ProbeSetup
import dev.droidprobe.Schema
import dev.droidprobe.intOrNull
import dev.droidprobe.okhttp.MockedResponse
import dev.droidprobe.okhttp.network
import dev.droidprobe.stringOrNull
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class SampleProbeSetup : ProbeSetup {

    override fun setup(probe: ProbeRegistrar, app: Application) {
        probe.state("checkout", Checkout.confirmed.map { buildJsonObject { put("confirmed", it) } }, "Sample checkout")

        // These codes make the real app drop its session, so a mock must opt in explicitly.
        probe.network.guard { rule ->
            val code = errorCode(rule.body)
            if (code in AUTH_CODES && !rule.allowAuthSideEffects) {
                "$code triggers logout; set allowAuthSideEffects to mock it"
            } else {
                null
            }
        }

        probe.network.template("apiError", "API error body; vars: status (int, default 400), code, message") { vars ->
            val code = vars.stringOrNull("code")
                ?: throw ProbeError(ProbeError.INVALID_INPUT, "$.vars.code: required")
            MockedResponse(
                status = vars.intOrNull("status") ?: 400,
                headers = mapOf("Content-Type" to "application/json"),
                body = buildJsonObject {
                    put("code", code)
                    put("message", vars.stringOrNull("message") ?: "")
                    put("data", JsonNull)
                }.toString(),
            )
        }

        if (BuildConfig.WITH_ECHO) {
            probe.call("echo", "Returns its input", Schema.anyObject()) { input, _ -> input }
        }
    }

    private fun errorCode(body: String?): String? =
        runCatching { (Json.parseToJsonElement(body ?: return null) as? JsonObject)?.stringOrNull("code") }.getOrNull()

    private companion object {
        val AUTH_CODES = setOf("AUTH_EXPIRED", "AUTH_REVOKED")
    }
}

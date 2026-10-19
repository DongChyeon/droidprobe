package dev.droidprobe

/** An error with a wire error code; commands throw it to answer with that code. */
class ProbeError(val code: String, message: String) : Exception(message) {
    companion object {
        const val BAD_REQUEST = "BAD_REQUEST"
        const val UNKNOWN_COMMAND = "UNKNOWN_COMMAND"
        const val INVALID_INPUT = "INVALID_INPUT"
        const val STALE_CURSOR = "STALE_CURSOR"
        const val CURSOR_EVICTED = "CURSOR_EVICTED"
        const val MOCK_REJECTED = "MOCK_REJECTED"
        const val SETUP_FAILED = "SETUP_FAILED"
        const val INTERNAL = "INTERNAL"
        const val TIMEOUT = "TIMEOUT"

        internal fun from(error: Throwable): ProbeError =
            error as? ProbeError ?: ProbeError(INTERNAL, "${error.javaClass.name}: ${error.message}")
    }
}

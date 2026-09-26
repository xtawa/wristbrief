package ink.underflo.wristbrief.mobile.artifacts

/**
 * Classification of the error codes this client can receive from the transcript and
 * speech endpoints.
 *
 * RETRYABLE means retrying can plausibly help (transport failure, 5xx, 429, timeout).
 * TERMINAL means retrying cannot help (bad request, unauthorized, gone, quota used up),
 * so the UI must not offer a retry that would only fail again.
 */
object TranscriptGatewayErrors {
    const val NETWORK_ERROR = "NETWORK_ERROR"
    const val INVALID_GATEWAY_URL = "INVALID_GATEWAY_URL"
    const val JOB_FAILED = "JOB_FAILED"
    const val JOB_GONE = "JOB_GONE"
    const val UNKNOWN_STATUS = "UNKNOWN_STATUS"
    const val UNKNOWN_RESPONSE = "UNKNOWN_RESPONSE"
    const val RESPONSE_TOO_LARGE = "response_too_large"
    const val UNAUTHORIZED = "UNAUTHORIZED"
    const val QUOTA_UNAVAILABLE = "managed_ai_quota_unavailable"
    const val NOT_FOUND = "not_found"

    fun isRetryable(errorCode: String?): Boolean = classify(errorCode).retryable

    /** The retryable / terminal / unauthorized discriminator the UI acts on. */
    fun classify(errorCode: String?): TranscriptFailureKind {
        val code = errorCode?.trim().orEmpty()
        if (code.isEmpty()) return TranscriptFailureKind.RETRYABLE
        httpStatus(code)?.let { http ->
            return when {
                http == 401 || http == 403 -> TranscriptFailureKind.UNAUTHORIZED
                http == 408 || http == 429 -> TranscriptFailureKind.RETRYABLE
                http >= 500 -> TranscriptFailureKind.RETRYABLE
                // 400/404/405/410/413/422 and anything else 4xx: asking again cannot help.
                else -> TranscriptFailureKind.TERMINAL
            }
        }
        return when (code) {
            INVALID_GATEWAY_URL, RESPONSE_TOO_LARGE -> TranscriptFailureKind.TERMINAL
            UNAUTHORIZED -> TranscriptFailureKind.UNAUTHORIZED
            QUOTA_UNAVAILABLE, NOT_FOUND, JOB_GONE -> TranscriptFailureKind.TERMINAL
            else -> when {
                code.equals("unauthorized", ignoreCase = true) ||
                    code.equals("unauthenticated", ignoreCase = true) ||
                    code.equals("forbidden", ignoreCase = true) -> TranscriptFailureKind.UNAUTHORIZED
                code.equals("not_found", ignoreCase = true) ||
                    code.equals("not_yours", ignoreCase = true) ||
                    code.equals("job_not_found", ignoreCase = true) ||
                    code.equals("job_gone", ignoreCase = true) -> TranscriptFailureKind.TERMINAL
                code.equals("invalid_request", ignoreCase = true) ||
                    code.equals("invalid_audio_url", ignoreCase = true) ||
                    code.equals("request_too_large", ignoreCase = true) -> TranscriptFailureKind.TERMINAL
                else -> TranscriptFailureKind.RETRYABLE
            }
        }
    }

    /**
     * Maps a gateway failure onto a canonical code so no raw body string can leak into
     * state selection or UI wording.
     *
     * This is deliberately TOTAL for the known server vocabulary: the frozen contract
     * answers a 404 on a job with `{"error":"job_not_found"}`, but a client that matched on
     * the raw string would never reach its own "gone" state, so `job_not_found`,
     * `not_found`, `not_yours`, `HTTP_404` and `HTTP_410` must ALL become [NOT_FOUND].
     */
    fun normalize(errorCode: String?, message: String? = null): String {
        val code = errorCode?.trim().orEmpty()
        if (code.isEmpty()) return UNKNOWN_STATUS
        val http = httpStatus(code)
        if (http != null) {
            return when (http) {
                401, 403 -> UNAUTHORIZED
                404, 410 -> NOT_FOUND
                413 -> "request_too_large"
                429 -> QUOTA_UNAVAILABLE
                else -> code
            }
        }
        return when {
            code.equals(INVALID_GATEWAY_URL, ignoreCase = true) -> INVALID_GATEWAY_URL
            code.equals(RESPONSE_TOO_LARGE, ignoreCase = true) -> RESPONSE_TOO_LARGE
            code.equals(NETWORK_ERROR, ignoreCase = true) -> NETWORK_ERROR
            code.equals(UNAUTHORIZED, ignoreCase = true) ||
                code.equals("unauthorized", ignoreCase = true) ||
                code.equals("unauthenticated", ignoreCase = true) ||
                code.equals("forbidden", ignoreCase = true) -> UNAUTHORIZED
            code.equals(NOT_FOUND, ignoreCase = true) ||
                code.equals("not_found", ignoreCase = true) ||
                code.equals("not_yours", ignoreCase = true) ||
                code.equals("job_not_found", ignoreCase = true) ||
                code.equals("job_gone", ignoreCase = true) -> NOT_FOUND
            code.equals(QUOTA_UNAVAILABLE, ignoreCase = true) ||
                code.equals("managed_ai_quota_unavailable", ignoreCase = true) -> QUOTA_UNAVAILABLE
            code.equals(UNKNOWN_RESPONSE, ignoreCase = true) && message.isNullOrBlank() -> UNKNOWN_STATUS
            else -> code
        }
    }

    /**
     * Failure codes that arrive through [TranscriptFetchResult.Failure] never coincide
     * with the retryable constants, so classification starts from the raw code.
     */
    fun normalizeFromFailure(errorCode: String?, message: String? = null): String {
        val raw = errorCode?.trim().orEmpty()
        if (raw.isBlank()) return UNKNOWN_STATUS
        if (raw.equals(NETWORK_ERROR, ignoreCase = true)) return NETWORK_ERROR
        return normalize(raw, message)
    }

    private fun httpStatus(code: String): Int? {
        val prefix = "HTTP_"
        if (!code.startsWith(prefix)) return null
        return code.removePrefix(prefix).toIntOrNull()
    }
}

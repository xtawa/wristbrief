package ink.underflo.wristbrief.mobile.audio

/** Server-side and client-side input bound for `POST /v1/audio/speech`. */
const val SPEECH_PREVIEW_MAX_CHARACTERS = 2000

/**
 * A short text sample for the speech preview.
 *
 * The bound is validated on the trimmed text because the gateway trims before it
 * measures. Oversized input is rejected with a visible message; it is never silently
 * truncated.
 */
data class SpeechPreviewRequest(val text: String) {

    val trimmedText: String get() = text.trim()

    val characterCount: Int get() = trimmedText.length

    val remainingCharacters: Int get() = SPEECH_PREVIEW_MAX_CHARACTERS - characterCount

    val isWithinLimit: Boolean get() = characterCount <= SPEECH_PREVIEW_MAX_CHARACTERS

    val isSendable: Boolean get() = trimmedText.isNotEmpty() && isWithinLimit

    companion object {
        const val MAX_CHARACTERS = SPEECH_PREVIEW_MAX_CHARACTERS
    }
}

sealed interface SpeechPreviewResult {
    /** A generated MP3 that has been received in full. */
    data class Success(val audio: ByteArray, val mimeType: String) : SpeechPreviewResult {
        override fun equals(other: Any?): Boolean =
            other is Success && audio.contentEquals(other.audio) && mimeType == other.mimeType

        override fun hashCode(): Int = 31 * audio.contentHashCode() + mimeType.hashCode()
    }

    data class Failure(
        val code: String,
        val httpStatus: Int?,
        val message: String?,
    ) : SpeechPreviewResult
}

/**
 * Error contract of `POST /v1/audio/speech`:
 * 401 unauthorized, 413 request_too_large, 400 invalid_json / invalid_text,
 * 429 managed_ai_quota_unavailable, 503 tts_provider_unavailable,
 * 502 tts_generation_failed, 405 method_not_allowed.
 */
object SpeechPreviewErrors {
    const val UNAUTHORIZED = "unauthorized"
    const val REQUEST_TOO_LARGE = "request_too_large"
    const val INVALID_JSON = "invalid_json"
    const val INVALID_TEXT = "invalid_text"
    const val QUOTA_UNAVAILABLE = "managed_ai_quota_unavailable"
    const val PROVIDER_UNAVAILABLE = "tts_provider_unavailable"
    const val GENERATION_FAILED = "tts_generation_failed"
    const val METHOD_NOT_ALLOWED = "method_not_allowed"
    const val NETWORK_ERROR = "network_error"
    const val INVALID_REQUEST = "invalid_request"

    /** Normalizes the gateway's own `error` body value plus the HTTP status. */
    fun normalize(rawCode: String?, httpStatus: Int? = null): String {
        val code = rawCode?.trim()?.lowercase().orEmpty()
        if (code.isNotEmpty()) {
            return when (code) {
                UNAUTHORIZED, "unauthenticated", "forbidden" -> UNAUTHORIZED
                REQUEST_TOO_LARGE, "payload_too_large", "text_too_long" -> REQUEST_TOO_LARGE
                INVALID_JSON -> INVALID_JSON
                INVALID_TEXT, "invalid_request", "bad_request" -> INVALID_TEXT
                QUOTA_UNAVAILABLE, "quota_exhausted" -> QUOTA_UNAVAILABLE
                PROVIDER_UNAVAILABLE, "tts_unavailable" -> PROVIDER_UNAVAILABLE
                GENERATION_FAILED -> GENERATION_FAILED
                METHOD_NOT_ALLOWED -> METHOD_NOT_ALLOWED
                else -> code
            }
        }
        return when (httpStatus) {
            401, 403 -> UNAUTHORIZED
            405 -> METHOD_NOT_ALLOWED
            413 -> REQUEST_TOO_LARGE
            429 -> QUOTA_UNAVAILABLE
            502 -> GENERATION_FAILED
            503 -> PROVIDER_UNAVAILABLE
            null -> NETWORK_ERROR
            else -> "http_$httpStatus"
        }
    }

    /** Uses the response body's `error` value when present, otherwise the HTTP status. */
    fun normalizeUploadFailure(httpStatus: Int, rawCode: String?): String = normalize(rawCode, httpStatus)

    /**
     * Retrying only helps when the failure was transient. A missing provider, an
     * unusable sample, an expired session or exhausted quota will fail again.
     */
    fun isRetryable(code: String?): Boolean = when (normalize(code, null)) {
        NETWORK_ERROR,
        GENERATION_FAILED,
        "http_500",
        "http_502",
        -> true
        else -> false
    }
}

/** Why a preview attempt did not produce audio. */
enum class SpeechPreviewIssueReason {
    NETWORK,
    UNAUTHORIZED,
    QUOTA_UNAVAILABLE,
    TEXT_TOO_LONG,
    TEXT_INVALID,
    PROVIDER_UNAVAILABLE,
    GENERATION_FAILED,
    SERVER_GONE,
}

data class SpeechPreviewIssue(
    val reason: SpeechPreviewIssueReason,
    val errorCode: String,
    val canRetry: Boolean,
) {
    companion object {
        fun from(result: SpeechPreviewResult.Failure, requestWasSendable: Boolean): SpeechPreviewIssue {
            val code = SpeechPreviewErrors.normalize(result.code, result.httpStatus)
            val reason = when (code) {
                SpeechPreviewErrors.UNAUTHORIZED -> SpeechPreviewIssueReason.UNAUTHORIZED
                SpeechPreviewErrors.QUOTA_UNAVAILABLE -> SpeechPreviewIssueReason.QUOTA_UNAVAILABLE
                SpeechPreviewErrors.REQUEST_TOO_LARGE -> SpeechPreviewIssueReason.TEXT_TOO_LONG
                SpeechPreviewErrors.INVALID_JSON,
                SpeechPreviewErrors.INVALID_TEXT,
                -> SpeechPreviewIssueReason.TEXT_INVALID
                SpeechPreviewErrors.PROVIDER_UNAVAILABLE -> SpeechPreviewIssueReason.PROVIDER_UNAVAILABLE
                SpeechPreviewErrors.GENERATION_FAILED -> SpeechPreviewIssueReason.GENERATION_FAILED
                SpeechPreviewErrors.METHOD_NOT_ALLOWED -> SpeechPreviewIssueReason.SERVER_GONE
                SpeechPreviewErrors.NETWORK_ERROR -> SpeechPreviewIssueReason.NETWORK
                else -> if (!requestWasSendable) {
                    SpeechPreviewIssueReason.TEXT_INVALID
                } else {
                    SpeechPreviewIssueReason.NETWORK
                }
            }
            return SpeechPreviewIssue(
                reason = reason,
                errorCode = code,
                canRetry = SpeechPreviewErrors.isRetryable(code),
            )
        }
    }
}

package ink.underflo.wristbrief.mobile.artifacts

import ink.underflo.wristbrief.mobile.R

/**
 * The user-facing transcript state.
 *
 * The states are distinct because they mean different things to the user: waiting for a
 * worker (`Queued`), conversion or recognition under way (`Processing`), a real failure
 * with an optional retry (`Failed`), a status that has not been observed from the server
 * yet (`Checking`), and a finished transcript (`Completed`). None of them is derived
 * from elapsed time or from a percentage.
 */
sealed interface TranscriptUiState {
    /** A persisted job exists but this process has not seen a server status for it yet. */
    data class Checking(val title: String?) : TranscriptUiState

    data class Queued(val title: String?) : TranscriptUiState

    data class Processing(val title: String?) : TranscriptUiState

    data class Failed(
        val title: String?,
        val messageRes: Int,
        val retryable: Boolean,
    ) : TranscriptUiState

    data class Completed(
        val title: String?,
        val contentCode: String,
        /** Gateway-reported provenance (`generated`, `shared_cache`, `existing_access`). */
        val source: String,
        val durationMs: Long,
        val payload: TranscriptPayload?,
    ) : TranscriptUiState {
        val sourceLabelRes: Int get() = transcriptSourceLabelRes(source)
    }
}

/**
 * Localized, truthful label for where a transcript came from. `source` is an opaque
 * diagnostic value with an open set, so an unrecognized value is labelled unknown
 * instead of being guessed, and nothing branches on it.
 */
internal fun transcriptSourceLabelRes(source: String): Int = when (source.trim().lowercase()) {
    "shared_cache" -> R.string.transcript_source_shared_cache
    "existing_access" -> R.string.transcript_source_existing_access
    "generated" -> R.string.transcript_source_generated
    else -> R.string.transcript_source_unknown
}

/**
 * Maps the persisted job plus cached payload onto a localized presentation state.
 *
 * The five states the product requires are all reachable here and all come from real
 * data: `Checking` for a job whose server status has not been observed yet, `Queued`
 * and `Processing` for the two non-terminal server statuses, `Failed` for a real error
 * record, and `Completed` for a finished artifact.
 */
object TranscriptJobPresentation {

    fun from(job: TranscriptJob?, payload: TranscriptPayload?, cachedPayload: TranscriptPayload?): TranscriptUiState {
        val resolved = payload ?: cachedPayload
        if (job == null) {
            return TranscriptUiState.Checking(title = null)
        }
        val title = job.title
        if (job.status == TranscriptStatus.COMPLETED) {
            return TranscriptUiState.Completed(
                title = title,
                contentCode = job.contentCode.orEmpty(),
                source = job.source.orEmpty(),
                durationMs = resolved?.durationMs ?: job.durationMs ?: 0L,
                payload = resolved,
            )
        }
        if (resolved != null) {
            // The artifact is already cached even though the job record was not updated;
            // show the transcript rather than a spinner over data we already have.
            return TranscriptUiState.Completed(
                title = title,
                contentCode = job.contentCode ?: resolved.contentCode,
                source = job.source.orEmpty(),
                durationMs = resolved.durationMs,
                payload = resolved,
            )
        }
        val errorCode = job.errorCode
        if (errorCode != null) {
            return TranscriptUiState.Failed(
                title = title,
                messageRes = transcriptFailureMessageRes(errorCode),
                retryable = job.retryable && TranscriptGatewayErrors.isRetryable(errorCode),
            )
        }
        return when (job.status) {
            TranscriptStatus.QUEUED -> TranscriptUiState.Queued(title)
            TranscriptStatus.PROCESSING -> TranscriptUiState.Processing(title)
            TranscriptStatus.FAILED -> TranscriptUiState.Failed(
                title = title,
                messageRes = R.string.transcript_error_generic,
                retryable = true,
            )
            // A job id with no observed status (for example straight after a restart) is
            // honestly "checking", never a guessed queued/processing.
            TranscriptStatus.UNKNOWN, TranscriptStatus.COMPLETED ->
                if (job.jobId.isBlank()) TranscriptUiState.Queued(title) else TranscriptUiState.Checking(title)
        }
    }
}

/** Maps a raw gateway error code to the one message that is true for the user. */
internal fun transcriptFailureMessageRes(errorCode: String): Int =
    when (TranscriptGatewayErrors.normalizeFromFailure(errorCode, null)) {
        TranscriptGatewayErrors.NETWORK_ERROR -> R.string.transcript_error_network
        TranscriptGatewayErrors.INVALID_GATEWAY_URL -> R.string.transcript_error_unavailable
        TranscriptGatewayErrors.NOT_FOUND, TranscriptGatewayErrors.JOB_GONE -> R.string.transcript_error_job_gone
        TranscriptGatewayErrors.QUOTA_UNAVAILABLE -> R.string.transcript_error_quota
        TranscriptGatewayErrors.UNAUTHORIZED -> R.string.transcript_error_sign_in
        TranscriptGatewayErrors.RESPONSE_TOO_LARGE -> R.string.transcript_error_too_large
        else -> R.string.transcript_error_generic
    }

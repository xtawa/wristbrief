package ink.underflo.wristbrief.mobile.artifacts

/**
 * Job lifecycle as the gateway actually reports it.
 *
 * `UNKNOWN` exists only for a job that was persisted locally before this process
 * observed a server status (for example straight after an app restart). It is never
 * inferred from elapsed time and is never presented as progress: the UI shows
 * "checking" for it until a real response arrives.
 */
enum class TranscriptStatus(val wireValue: String) {
    UNKNOWN("unknown"),
    QUEUED("queued"),
    PROCESSING("processing"),
    FAILED("failed"),
    COMPLETED("completed");

    companion object {
        fun fromWire(raw: String?): TranscriptStatus = when (raw?.trim()?.lowercase()) {
            "queued", "pending" -> QUEUED
            "running", "processing", "in_progress" -> PROCESSING
            "failed", "error" -> FAILED
            "completed", "ready", "succeeded" -> COMPLETED
            else -> UNKNOWN
        }
    }
}

/**
 * A transcript request the phone is still waiting on. Persisted durably so the job
 * survives leaving the transcript page and a process restart.
 */
data class TranscriptJob(
    val audioUrl: String,
    val jobId: String,
    val contentCode: String?,
    val status: TranscriptStatus,
    val title: String? = null,
    val feedUrl: String? = null,
    val guid: String? = null,
    val durationMs: Long? = null,
    /**
     * Gateway-reported provenance of the artifact: `generated`, `shared_cache` or
     * `existing_access`. Rendered verbatim as a localized label; never guessed.
     */
    val source: String? = null,
    val artifactId: String? = null,
    val errorCode: String? = null,
    val retryable: Boolean = true,
    val pollAfterMs: Long? = null,
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long,
)

/**
 * Result of a single status probe. Distinct from [TranscriptFetchResult] because a
 * poll can also discover that the job vanished server-side.
 */
sealed interface TranscriptJobSnapshot {
    data class Ready(
        val contentCode: String,
        val artifactId: String,
        val source: String,
        val payload: TranscriptPayload?,
    ) : TranscriptJobSnapshot

    data class Active(
        val status: TranscriptStatus,
        val contentCode: String?,
        val jobId: String,
        /**
         * Cadence the server asked for on this probe (`pollAfterMs` body field or
         * `Retry-After` header). Null means the server did not ask; the caller then keeps
         * its own steady cadence.
         */
        val pollAfterMs: Long? = null,
        /**
         * Retryable transport failure observed on this probe (`NETWORK_ERROR`, `HTTP_500`…).
         * Only informational: the job keeps polling, and the UI keeps showing the last
         * real server status rather than inventing one.
         */
        val message: String? = null,
    ) : TranscriptJobSnapshot

    data class Failed(
        val errorCode: String,
        val message: String,
        val retryable: Boolean,
    ) : TranscriptJobSnapshot

    /** The gateway no longer has this job for this account (404 / gone / expired). */
    data class JobGone(val errorCode: String, val message: String) : TranscriptJobSnapshot
}

/**
 * Durable store for in-flight transcript requests. Implementations must survive
 * process death; nothing here may live only in memory.
 */
interface TranscriptJobStore {
    fun find(audioUrl: String): TranscriptJob?
    fun listInFlight(limit: Int = 50): List<TranscriptJob>
    fun upsert(job: TranscriptJob)

    /** Links a completed job to its cached artifact so it can be reopened offline. */
    fun markCompleted(
        audioUrl: String,
        contentCode: String,
        source: String,
        artifactId: String,
        nowEpochMs: Long,
    )

    fun markFailed(
        audioUrl: String,
        errorCode: String,
        message: String,
        retryable: Boolean,
        nowEpochMs: Long,
    )

    fun clear(audioUrl: String)
    fun pruneOlderThan(thresholdEpochMs: Long)
}

internal fun TranscriptFetchResult.toSnapshot(fallbackContentCode: String = ""): TranscriptJobSnapshot = when (this) {
    is TranscriptFetchResult.Ready -> TranscriptJobSnapshot.Ready(
        contentCode = contentCode.ifBlank { fallbackContentCode },
        artifactId = artifactId,
        source = source,
        payload = payload,
    )

    is TranscriptFetchResult.Processing -> TranscriptJobSnapshot.Active(
        status = TranscriptStatus.fromWire(status),
        contentCode = contentCode.ifBlank { fallbackContentCode }.takeIf { it.isNotEmpty() },
        jobId = jobId,
        pollAfterMs = pollAfterMs,
    )

    is TranscriptFetchResult.Failure -> when (TranscriptGatewayErrors.classify(errorCode)) {
        // The server no longer has this job for this account (404 / gone): terminal, not
        // transient, and it must never be retried.
        TranscriptFailureKind.TERMINAL -> {
            val normalized = TranscriptGatewayErrors.normalizeFromFailure(errorCode, message)
            if (normalized == TranscriptGatewayErrors.NOT_FOUND) {
                TranscriptJobSnapshot.JobGone(errorCode = normalized, message = message)
            } else {
                TranscriptJobSnapshot.Failed(
                    errorCode = normalized,
                    message = message,
                    retryable = false,
                )
            }
        }
        // A transport/5xx/429 failure says nothing about the job, so the job stays in
        // flight: report it as still active and let the poll loop keep trying.
        TranscriptFailureKind.RETRYABLE -> TranscriptJobSnapshot.Active(
            status = TranscriptStatus.UNKNOWN,
            contentCode = fallbackContentCode.takeIf { it.isNotEmpty() },
            jobId = "",
            message = errorCode,
        )
        TranscriptFailureKind.UNAUTHORIZED -> TranscriptJobSnapshot.Failed(
            errorCode = TranscriptGatewayErrors.UNAUTHORIZED,
            message = message,
            retryable = false,
        )
    }
}

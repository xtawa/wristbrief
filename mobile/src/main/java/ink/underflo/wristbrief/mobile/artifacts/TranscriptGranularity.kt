package ink.underflo.wristbrief.mobile.artifacts

/**
 * What a transcript timestamp can and cannot do, as a single source of truth.
 *
 * The server splits audio into 4-minute clips and stores one timed segment per clip, so a
 * timestamp localizes a passage. There are no word timestamps and no speaker diarization
 * (`docs/AUDIO_PIPELINE.md`). Anything user-visible that describes positioning must be
 * consistent with this object, and the viewer shows [explanationRes] to say so explicitly.
 */
object TranscriptGranularity {
    /** Clip length the server currently produces. */
    const val CLIP_DURATION_MS: Long = 4L * 60L * 1_000L

    /** Whether a per-word affordance can be offered. It cannot, today. */
    const val SUPPORTS_WORD_LEVEL: Boolean = false

    /** Whether the provider reports speaker labels. It does not, today. */
    const val SUPPORTS_SPEAKER_DIARIZATION: Boolean = false

    /**
     * True when a segment's timing is clip-shaped rather than word-shaped. Used by tests to
     * prove the UI is only asked to position by the granularity that actually exists.
     */
    fun isClipLevel(segment: TranscriptSegment): Boolean =
        segment.endMs - segment.startMs >= CLIP_DURATION_MS

    val explanationRes: Int
        get() = ink.underflo.wristbrief.mobile.R.string.transcript_positioning_explainer
}

export interface TranscriptSegment {
  id: number;
  startMs: number;
  endMs: number;
  text: string;
  speaker?: string;
}

export interface TranscriptPayload {
  schemaVersion: "1";
  contentCode: string;
  language: string;
  durationMs: number;
  fullText: string;
  segments: TranscriptSegment[];
}

export interface TranscriptRequestInput {
  episode: {
    feedId?: string;
    feedUrl?: string;
    guid?: string;
    audioUrl: string;
    title?: string;
    publishedAt?: string | number;
    durationMs?: number;
    audioSha256?: string;
  };
  language?: string;
  sharePolicy?: "PUBLIC_REUSE" | "PRIVATE_ACCOUNT";
}

export interface QuotaChargedInfo {
  normalUnits: number;
  multiplier: number;
  chargedUnits: number;
}

export interface TranscriptReadyResponse {
  status: "ready";
  contentCode: string;
  artifactId: string;
  source: "existing_access" | "shared_cache" | "generated";
  quota: QuotaChargedInfo;
  transcript?: TranscriptPayload;
}

export interface TranscriptProcessingResponse {
  status: "processing";
  contentCode: string;
  jobId: string;
}

export type TranscriptRequestResponse = TranscriptReadyResponse | TranscriptProcessingResponse;

export type TranscriptJobStatusValue = "queued" | "running" | "completed" | "failed";

/**
 * Per-user projection of a transcript job. `jobId` is the id the requester was given by
 * POST /v1/transcripts/request, which may be the shared generation job (creator) or that
 * user's own follower request (joiner). `artifactId` and `quota` are only present when the
 * work is complete AND the requester is authorized to read the artifact.
 */
export interface TranscriptJobStatusResponse {
  jobId: string;
  contentId: string;
  contentCode: string | undefined;
  status: TranscriptJobStatusValue;
  artifactId?: string;
  attemptCount: number;
  errorCode: string | null;
  updatedAt: number;
  quota?: QuotaChargedInfo;
}

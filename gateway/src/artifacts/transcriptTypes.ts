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

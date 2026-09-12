import { ContentResolver, validateAudioUrl } from "../content/contentResolver";
import { D1ContentStore } from "../content/contentStore";
import { D1ArtifactStore } from "./artifactStore";
import { D1QuotaLedgerStore } from "./quotaLedger";
import { TranscriptService, type TranscriptServiceEnv } from "./transcriptService";
import { createTranscriptStorage, type TranscriptStorage } from "./transcriptStorage";
import { type TranscriptPayload, type TranscriptRequestInput } from "./transcriptTypes";

export interface TranscriptRouteEnv extends TranscriptServiceEnv {
  ACCOUNT_DB?: D1Database;
  TRANSCRIPTS_BUCKET?: R2Bucket;
  TRANSCRIPT_STORAGE?: TranscriptStorage;
  PUBLIC_REUSE_FEED_HOSTS?: string;
}

export async function handleTranscriptRequest(
  request: Request,
  env: TranscriptRouteEnv,
  userId: string,
  preParsedBody?: TranscriptRequestInput
): Promise<{ status: number; body: unknown }> {
  if (!env.ACCOUNT_DB) {
    return { status: 503, body: { error: "database_unavailable" } };
  }
  try {
    createTranscriptStorage(env);
  } catch {
    return { status: 503, body: { error: "transcript_storage_unavailable" } };
  }

  let body: TranscriptRequestInput;
  if (preParsedBody) {
    body = preParsedBody;
  } else {
    try {
      body = (await request.json()) as TranscriptRequestInput;
    } catch {
      return { status: 400, body: { error: "invalid_json" } };
    }
  }

  if (!body.episode || !body.episode.audioUrl) {
    return { status: 400, body: { error: "invalid_request", message: "episode.audioUrl is required" } };
  }

  const urlValidation = validateAudioUrl(body.episode.audioUrl);
  if (!urlValidation.ok) {
    return { status: 400, body: { error: "invalid_audio_url", message: urlValidation.error } };
  }

  const contentStore = new D1ContentStore(env.ACCOUNT_DB);
  const contentResolver = new ContentResolver(contentStore, {
    publicReuseHosts: (env.PUBLIC_REUSE_FEED_HOSTS ?? "").split(",").map((value) => value.trim()).filter(Boolean)
  });
  const artifactStore = new D1ArtifactStore(env.ACCOUNT_DB);
  const quotaStore = new D1QuotaLedgerStore(env.ACCOUNT_DB);

  const service = new TranscriptService(contentResolver, artifactStore, quotaStore, env);
  try {
    const result = await service.requestTranscript(userId, body);
    return {
      status: result.status === "ready" ? 200 : 202,
      body: result
    };
  } catch {
    return {
      status: 400,
      body: { error: "transcript_request_failed", message: "Transcript request could not be processed" }
    };
  }
}

export async function handleTranscriptGet(
  contentCode: string,
  env: TranscriptRouteEnv,
  userId: string
): Promise<{ status: number; body: unknown }> {
  if (!env.ACCOUNT_DB) {
    return { status: 503, body: { error: "database_unavailable" } };
  }

  const contentStore = new D1ContentStore(env.ACCOUNT_DB);
  const content = await contentStore.findByContentCode(contentCode);
  if (!content || content.status === "tombstone") {
    return { status: 404, body: { error: "transcript_not_found" } };
  }

  const artifactStore = new D1ArtifactStore(env.ACCOUNT_DB);
  const artifact = await artifactStore.findPreferredArtifactForContent(content.id);
  if (!artifact || artifact.status !== "ready") {
    return { status: 404, body: { error: "transcript_not_found" } };
  }

  // SEC-01 Object-level authorization
  let isAuthorized = false;
  if (artifact.sharePolicy === "PUBLIC_REUSE") {
    isAuthorized = true;
  } else if (artifact.createdByUserId === userId) {
    isAuthorized = true;
  } else {
    const hasAccess = await artifactStore.hasUserAccess(userId, artifact.id);
    if (hasAccess) {
      isAuthorized = true;
    }
  }

  if (!isAuthorized) {
    // Return 404 to avoid leaking existence of private artifacts
    return { status: 404, body: { error: "transcript_not_found" } };
  }

  // Load payload from storage if available
  const storage = createTranscriptStorage(env);
  let payload: TranscriptPayload | null = null;
  if (artifact.objectKeyJson) {
    const jsonStr = await storage.get(artifact.objectKeyJson);
    if (jsonStr) {
      try {
        payload = JSON.parse(jsonStr) as TranscriptPayload;
      } catch {}
    }
  }

  return {
    status: 200,
    body: {
      ...artifact,
      transcript: payload ?? undefined
    }
  };
}

export async function handleTranscriptStatus(
  jobId: string,
  env: TranscriptRouteEnv,
  userId: string
): Promise<{ status: number; body: unknown }> {
  if (!env.ACCOUNT_DB) {
    return { status: 503, body: { error: "database_unavailable" } };
  }

  const artifactStore = new D1ArtifactStore(env.ACCOUNT_DB);
  const job = await artifactStore.getJobById(jobId);
  // SEC-01: job status is strictly restricted to the user who created it
  if (!job || job.userId !== userId) {
    return { status: 404, body: { error: "job_not_found" } };
  }

  const contentStore = new D1ContentStore(env.ACCOUNT_DB);
  const content = await contentStore.findById(job.contentId);
  const artifact = job.status === "completed"
    ? await artifactStore.findPreferredArtifactForContent(job.contentId, job.language)
    : null;

  return {
    status: 200,
    body: {
      jobId: job.id,
      contentId: job.contentId,
      contentCode: content?.contentCode,
      status: job.status,
      artifactId: artifact?.id,
      attemptCount: job.attemptCount,
      errorCode: job.errorCode,
      updatedAt: job.updatedAt
    }
  };
}

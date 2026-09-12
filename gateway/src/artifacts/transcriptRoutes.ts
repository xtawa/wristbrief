import { ContentResolver } from "../content/contentResolver";
import { D1ContentStore } from "../content/contentStore";
import { D1ArtifactStore } from "./artifactStore";
import { D1QuotaLedgerStore } from "./quotaLedger";
import { TranscriptService, type TranscriptServiceEnv } from "./transcriptService";
import { type TranscriptRequestInput } from "./transcriptTypes";

export interface TranscriptRouteEnv extends TranscriptServiceEnv {
  ACCOUNT_DB?: D1Database;
}

export async function handleTranscriptRequest(
  request: Request,
  env: TranscriptRouteEnv,
  userId: string
): Promise<{ status: number; body: unknown }> {
  if (!env.ACCOUNT_DB) {
    return { status: 503, body: { error: "database_unavailable" } };
  }

  let body: TranscriptRequestInput;
  try {
    body = (await request.json()) as TranscriptRequestInput;
  } catch {
    return { status: 400, body: { error: "invalid_json" } };
  }

  if (!body.episode || !body.episode.audioUrl) {
    return { status: 400, body: { error: "invalid_request", message: "episode.audioUrl is required" } };
  }

  const contentStore = new D1ContentStore(env.ACCOUNT_DB);
  const contentResolver = new ContentResolver(contentStore);
  const artifactStore = new D1ArtifactStore(env.ACCOUNT_DB);
  const quotaStore = new D1QuotaLedgerStore(env.ACCOUNT_DB);

  const service = new TranscriptService(contentResolver, artifactStore, quotaStore, env);
  const result = await service.requestTranscript(userId, body);

  return {
    status: result.status === "ready" ? 200 : 202,
    body: result
  };
}

export async function handleTranscriptGet(
  contentCode: string,
  env: TranscriptRouteEnv
): Promise<{ status: number; body: unknown }> {
  if (!env.ACCOUNT_DB) {
    return { status: 503, body: { error: "database_unavailable" } };
  }

  const contentStore = new D1ContentStore(env.ACCOUNT_DB);
  const content = await contentStore.findByContentCode(contentCode);
  if (!content) {
    return { status: 404, body: { error: "content_not_found" } };
  }

  const artifactStore = new D1ArtifactStore(env.ACCOUNT_DB);
  const artifact = await artifactStore.findPreferredArtifactForContent(content.id);
  if (!artifact) {
    return { status: 404, body: { error: "transcript_not_found" } };
  }

  return {
    status: 200,
    body: artifact
  };
}

export async function handleTranscriptStatus(
  jobId: string,
  env: TranscriptRouteEnv
): Promise<{ status: number; body: unknown }> {
  if (!env.ACCOUNT_DB) {
    return { status: 503, body: { error: "database_unavailable" } };
  }

  const artifactStore = new D1ArtifactStore(env.ACCOUNT_DB);
  const job = await artifactStore.getJobById(jobId);
  if (!job) {
    return { status: 404, body: { error: "job_not_found" } };
  }

  return {
    status: 200,
    body: {
      jobId: job.id,
      contentId: job.contentId,
      status: job.status,
      attemptCount: job.attemptCount,
      errorCode: job.errorCode,
      updatedAt: job.updatedAt
    }
  };
}

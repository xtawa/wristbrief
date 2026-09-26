import { ContentResolver, validateAudioUrl } from "../content/contentResolver";
import { D1ContentStore } from "../content/contentStore";
import {
  type ArtifactJobRecord,
  type ArtifactStore,
  D1ArtifactStore,
  type JobFollowerRecord,
  type TranscriptArtifactRecord
} from "./artifactStore";
import { type CreditTransaction, D1QuotaLedgerStore } from "./quotaLedger";
import { TranscriptService, type TranscriptServiceEnv } from "./transcriptService";
import { createTranscriptStorage, type TranscriptStorage } from "./transcriptStorage";
import {
  type QuotaChargedInfo,
  type TranscriptJobStatusResponse,
  type TranscriptJobStatusValue,
  type TranscriptPayload,
  type TranscriptRequestInput
} from "./transcriptTypes";

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
  // Share policy is decided server-side, never by the caller. PUBLIC_REUSE_FEED_HOSTS is
  // empty by default, and an empty allowlist means every content resolves as
  // PRIVATE_ACCOUNT: no cross-account reuse, so no follower/0.2x shared-cache tier. That
  // tier only becomes reachable for feed hosts explicitly listed here (and requested with
  // sharePolicy PUBLIC_REUSE). See docs/PRODUCTION_DEPLOYMENT_GUIDE.md.
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
  // SEC-01: resolve only an artifact this user may read, so another account's
  // PRIVATE_ACCOUNT artifact stays invisible (404) instead of leaking its existence.
  const artifact = await artifactStore.findReadableArtifactForContent(userId, content.id);
  if (!artifact || artifact.status !== "ready") {
    return { status: 404, body: { error: "transcript_not_found" } };
  }

  // SEC-01 Object-level authorization
  const isAuthorized = await isArtifactReadable(artifactStore, userId, artifact);
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
  // SEC-01: the shared generation job is restricted to the user who created it.
  if (job && job.userId === userId) {
    return { status: 200, body: await buildGenerationJobStatus(job, env, userId) };
  }

  // A joiner polls their own follower request id, which belongs to them alone.
  const follower = await artifactStore.getJobFollowerById(jobId);
  if (!follower || follower.userId !== userId) {
    return { status: 404, body: { error: "job_not_found" } };
  }
  return { status: 200, body: await buildFollowerJobStatus(follower, env, userId) };
}

async function buildGenerationJobStatus(
  job: ArtifactJobRecord,
  env: TranscriptRouteEnv,
  userId: string
): Promise<TranscriptJobStatusResponse> {
  const db = env.ACCOUNT_DB as D1Database;
  const content = await new D1ContentStore(db).findById(job.contentId);

  let artifactId: string | undefined;
  let quota: QuotaChargedInfo | undefined;
  if (job.status === "completed") {
    const artifactStore = new D1ArtifactStore(db);
    const artifact = await artifactStore.findReadableArtifactForContent(
      userId,
      job.contentId,
      job.language
    );
    if (artifact && artifact.status === "ready" && (await isArtifactReadable(artifactStore, userId, artifact))) {
      artifactId = artifact.id;
      const tx = await new D1QuotaLedgerStore(db).findByReference(
        userId,
        "transcript_generation",
        job.id
      );
      if (tx) quota = quotaFromTransaction(tx);
    }
  }

  return {
    jobId: job.id,
    contentId: job.contentId,
    contentCode: content?.contentCode,
    status: job.status,
    ...(artifactId ? { artifactId } : {}),
    attemptCount: job.attemptCount,
    errorCode: job.errorCode,
    updatedAt: job.updatedAt,
    ...(quota ? { quota } : {})
  };
}

async function buildFollowerJobStatus(
  follower: JobFollowerRecord,
  env: TranscriptRouteEnv,
  userId: string
): Promise<TranscriptJobStatusResponse> {
  const db = env.ACCOUNT_DB as D1Database;
  const artifactStore = new D1ArtifactStore(db);
  const leader = await artifactStore.getJobById(follower.jobId);
  const content = await new D1ContentStore(db).findById(follower.contentId);

  // Until this follower's own settlement row reaches a terminal state, the derived status
  // follows the underlying work item. When the leader fails, failJob marks the follower row
  // failed as well, so a follower never reads "completed" before being authorized.
  const settled = follower.status === "completed" || follower.status === "failed";
  const status: TranscriptJobStatusValue = settled
    ? follower.status
    : leader?.status ?? follower.status;

  let artifactId: string | undefined;
  let quota: QuotaChargedInfo | undefined;
  if (status === "completed") {
    const artifact = follower.artifactId
      ? await artifactStore.findArtifactById(follower.artifactId)
      : await artifactStore.findReadableArtifactForContent(userId, follower.contentId, follower.language);
    // Only report the artifact/billing when this user can actually read the transcript.
    if (artifact && artifact.status === "ready" && (await isArtifactReadable(artifactStore, userId, artifact))) {
      artifactId = artifact.id;
      quota = {
        normalUnits: follower.normalUnits,
        multiplier: follower.quotaMultiplier,
        chargedUnits: follower.quotaUnits
      };
    }
  }

  return {
    jobId: follower.id,
    contentId: follower.contentId,
    contentCode: content?.contentCode,
    status,
    ...(artifactId ? { artifactId } : {}),
    attemptCount: leader?.attemptCount ?? follower.attemptCount,
    errorCode: status === "failed" ? follower.errorCode ?? leader?.errorCode ?? null : null,
    updatedAt: Math.max(follower.updatedAt, leader?.updatedAt ?? 0),
    ...(quota ? { quota } : {})
  };
}

async function isArtifactReadable(
  artifactStore: ArtifactStore,
  userId: string,
  artifact: TranscriptArtifactRecord
): Promise<boolean> {
  if (artifact.sharePolicy === "PUBLIC_REUSE") return true;
  if (artifact.createdByUserId === userId) return true;
  return artifactStore.hasUserAccess(userId, artifact.id);
}

function quotaFromTransaction(tx: CreditTransaction): QuotaChargedInfo {
  const normalUnits = tx.multiplier > 0 ? Math.round((tx.units / tx.multiplier) * 100) / 100 : tx.units;
  return {
    normalUnits,
    multiplier: tx.multiplier,
    chargedUnits: tx.units
  };
}

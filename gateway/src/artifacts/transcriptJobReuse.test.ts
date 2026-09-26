import { describe, expect, it } from "vitest";
import { createMigratedTestDb } from "../testDbHelper";
import { ContentResolver } from "../content/contentResolver";
import { D1ContentStore } from "../content/contentStore";
import { D1ArtifactStore } from "./artifactStore";
import { D1QuotaLedgerStore } from "./quotaLedger";
import { TranscriptService } from "./transcriptService";
import { InMemoryTranscriptStorage } from "./transcriptStorage";
import {
  handleTranscriptGet,
  handleTranscriptRequest,
  handleTranscriptStatus,
  type TranscriptRouteEnv
} from "./transcriptRoutes";
import { type TranscriptPayload, type TranscriptRequestInput } from "./transcriptTypes";

const USER_A = "usr_creator";
const USER_B = "usr_joiner";
const USER_C = "usr_outsider";

interface Env {
  d1: D1Database;
  env: TranscriptRouteEnv;
  storage: InMemoryTranscriptStorage;
  enqueues: string[];
}

function createEnv(): Env {
  const { d1 } = createMigratedTestDb();
  const storage = new InMemoryTranscriptStorage();
  const enqueues: string[] = [];
  const queue = {
    send: async (message: { jobId: string }) => {
      enqueues.push(message.jobId);
    }
  } as unknown as Queue;

  const env: TranscriptRouteEnv = {
    ACCOUNT_DB: d1,
    TRANSCRIPT_STORAGE: storage,
    TRANSCRIPT_QUEUE: queue,
    PUBLIC_REUSE_FEED_HOSTS: "example.com"
  };

  return { d1, env, storage, enqueues };
}

function createService(d1: D1Database, env: TranscriptRouteEnv) {
  const contentStore = new D1ContentStore(d1);
  const contentResolver = new ContentResolver(contentStore, { publicReuseHosts: ["example.com"] });
  const artifactStore = new D1ArtifactStore(d1);
  const quotaStore = new D1QuotaLedgerStore(d1);
  const service = new TranscriptService(contentResolver, artifactStore, quotaStore, env);
  return { service, artifactStore, quotaStore, contentStore };
}

function publicEpisode(): TranscriptRequestInput {
  return {
    sharePolicy: "PUBLIC_REUSE",
    episode: {
      feedUrl: "https://example.com/public-show.xml",
      guid: "ep-public-1",
      audioUrl: "https://cdn.example.com/public-show-1.mp3",
      title: "Public Show 1",
      durationMs: 3_600_000
    }
  };
}

function privateEpisode(): TranscriptRequestInput {
  return {
    sharePolicy: "PRIVATE_ACCOUNT",
    episode: {
      feedUrl: "https://private.example.com/private-show.xml",
      guid: "ep-private-1",
      audioUrl: "https://cdn.private.example.com/private-show-1.mp3",
      title: "Private Show 1",
      durationMs: 3_600_000
    }
  };
}

async function postTranscript(
  env: TranscriptRouteEnv,
  userId: string,
  input: TranscriptRequestInput
): Promise<{ status: number; body: Record<string, unknown> }> {
  const response = await handleTranscriptRequest(
    new Request("https://gateway.internal/v1/transcripts/request", {
      method: "POST",
      body: JSON.stringify(input)
    }),
    env,
    userId
  );
  return { status: response.status, body: response.body as Record<string, unknown> };
}

function payloadFor(contentCode: string, fullText: string): TranscriptPayload {
  return {
    schemaVersion: "1",
    contentCode,
    language: "en",
    durationMs: 3_600_000,
    fullText,
    segments: [{ id: 1, startMs: 0, endMs: 5_000, text: fullText }]
  };
}

async function countRows(d1: D1Database, sql: string): Promise<number> {
  const row = await d1.prepare(sql).first<{ count: number }>();
  return Number(row?.count ?? 0);
}

describe("Transcript job reuse: per-user polling, shared-cache billing and retry", () => {
  it("gives two users on PUBLIC_REUSE content distinct pollable ids for one deduplicated generation", async () => {
    const { d1, env, storage, enqueues } = createEnv();
    const { service, artifactStore, quotaStore, contentStore } = createService(d1, env);
    const input = publicEpisode();

    const resA = await postTranscript(env, USER_A, input);
    expect(resA.status).toBe(202);
    expect(resA.body.status).toBe("processing");
    const jobA = resA.body.jobId as string;
    const contentCode = resA.body.contentCode as string;
    expect(jobA).toMatch(/^job_/);

    const resB = await postTranscript(env, USER_B, input);
    expect(resB.status).toBe(202);
    expect(resB.body.status).toBe("processing");
    const jobB = resB.body.jobId as string;
    expect(jobB).toMatch(/^jobu_/);
    expect(jobB).not.toBe(jobA);
    expect(resB.body.contentCode).toBe(contentCode);

    // Underlying work is deduplicated: one job row, one queue enqueue.
    expect(enqueues).toEqual([jobA]);
    expect(await countRows(d1, "SELECT COUNT(*) AS count FROM artifact_jobs")).toBe(1);

    // The joiner can poll before completion - 200, own status, no artifact, no payload.
    const pollB = await handleTranscriptStatus(jobB, env, USER_B);
    expect(pollB.status).toBe(200);
    const pollBBody = pollB.body as Record<string, unknown>;
    expect(["queued", "running"]).toContain(pollBBody.status);
    expect(pollBBody.jobId).toBe(jobB);
    expect(pollBBody.artifactId).toBeUndefined();
    expect(pollBBody.quota).toBeUndefined();
    expect(pollBBody.transcript).toBeUndefined();

    // Neither id is readable by anyone else, and no content is readable yet.
    expect((await handleTranscriptStatus(jobB, env, USER_C)).status).toBe(404);
    expect((await handleTranscriptStatus(jobA, env, USER_B)).status).toBe(404);
    expect((await handleTranscriptStatus("job_missing", env, USER_B)).status).toBe(404);
    const earlyGet = await handleTranscriptGet(contentCode, env, USER_B);
    expect(earlyGet.status).toBe(404);
    expect((earlyGet.body as Record<string, unknown>).transcript).toBeUndefined();

    await service.completeJobWithArtifact(jobA, payloadFor(contentCode, "shared public transcript"), storage);

    const doneA = (await handleTranscriptStatus(jobA, env, USER_A)).body as Record<string, unknown>;
    expect(doneA.status).toBe("completed");
    const artifactId = doneA.artifactId as string;
    expect(artifactId).toMatch(/^art_/);
    expect(doneA.quota).toEqual({ normalUnits: 60, multiplier: 1, chargedUnits: 60 });

    const doneB = (await handleTranscriptStatus(jobB, env, USER_B)).body as Record<string, unknown>;
    expect(doneB.status).toBe("completed");
    expect(doneB.artifactId).toBe(artifactId);
    expect(doneB.quota).toEqual({ normalUnits: 60, multiplier: 0.2, chargedUnits: 12 });

    // Creator pays 1.0x, joiner pays the shared-cache rate, both committed.
    const txA = await quotaStore.findByReference(USER_A, "transcript_generation", jobA);
    expect(txA?.status).toBe("COMMITTED");
    expect(txA?.units).toBe(60);
    expect(txA?.multiplier).toBe(1);
    const txB = await quotaStore.findByReference(USER_B, "transcript_shared", jobB);
    expect(txB?.status).toBe("COMMITTED");
    expect(txB?.units).toBe(12);
    expect(txB?.multiplier).toBe(0.2);

    // The joiner's grant only exists because the artifact became readable.
    const content = await contentStore.findByContentCode(contentCode);
    const accessB = await artifactStore.getUserAccessForContent(USER_B, content!.id);
    expect(accessB?.accessSource).toBe("shared_cache");
    expect(accessB?.quotaUnitsCharged).toBe(12);
    expect(await artifactStore.hasUserAccess(USER_B, artifactId)).toBe(true);

    const getB = await handleTranscriptGet(contentCode, env, USER_B);
    expect(getB.status).toBe(200);
    expect((getB.body as { transcript: TranscriptPayload }).transcript.fullText).toBe(
      "shared public transcript"
    );

    // Repeat request by an already-authorized user stays 0x.
    const repeatB = await postTranscript(env, USER_B, input);
    expect(repeatB.status).toBe(200);
    expect(repeatB.body.status).toBe("ready");
    expect(repeatB.body.source).toBe("existing_access");
    expect(repeatB.body.quota).toEqual({ normalUnits: 60, multiplier: 0, chargedUnits: 0 });

    expect(await countRows(d1, "SELECT COUNT(*) AS count FROM transcript_artifacts")).toBe(1);
    expect(await countRows(d1, "SELECT COUNT(*) AS count FROM credit_transactions WHERE status = 'RESERVED'")).toBe(0);
  });

  it("keeps PRIVATE_ACCOUNT content per account: separate jobs, separate artifacts, no shared grant", async () => {
    const { d1, env, storage, enqueues } = createEnv();
    const { service, artifactStore, contentStore } = createService(d1, env);
    const input = privateEpisode();

    const resA = await postTranscript(env, USER_A, input);
    expect(resA.status).toBe(202);
    const jobA = resA.body.jobId as string;
    const contentCode = resA.body.contentCode as string;
    expect(jobA).toMatch(/^job_/);

    const resB = await postTranscript(env, USER_B, input);
    expect(resB.status).toBe(202);
    const jobB = resB.body.jobId as string;
    expect(jobB).toMatch(/^job_/);
    expect(jobB).not.toBe(jobA);
    expect(resB.body.jobId).not.toMatch(/^jobu_/);

    const content = await contentStore.findByContentCode(contentCode);
    expect(content?.sharePolicy).toBe("PRIVATE_ACCOUNT");

    // The second user still gets a job status they are entitled to poll.
    const pollB = await handleTranscriptStatus(jobB, env, USER_B);
    expect(pollB.status).toBe(200);
    expect((pollB.body as Record<string, unknown>).status).toBe("queued");
    expect((await handleTranscriptStatus(jobA, env, USER_B)).status).toBe(404);
    expect((await handleTranscriptStatus(jobB, env, USER_A)).status).toBe(404);
    expect((await handleTranscriptStatus(jobA, env, USER_C)).status).toBe(404);

    // Nothing is readable before the account's own generation completes.
    const earlyB = await handleTranscriptGet(contentCode, env, USER_B);
    expect(earlyB.status).toBe(404);
    expect((earlyB.body as Record<string, unknown>).transcript).toBeUndefined();
    const earlyC = await handleTranscriptGet(contentCode, env, USER_C);
    expect(earlyC.status).toBe(404);

    // Each account runs its own generation (two work items, one per account).
    expect(enqueues).toEqual([jobA, jobB]);

    await service.completeJobWithArtifact(jobA, payloadFor(contentCode, "A private transcript"), storage);
    await service.completeJobWithArtifact(jobB, payloadFor(contentCode, "B private transcript"), storage);

    const doneA = (await handleTranscriptStatus(jobA, env, USER_A)).body as Record<string, unknown>;
    const doneB = (await handleTranscriptStatus(jobB, env, USER_B)).body as Record<string, unknown>;
    expect(doneA.status).toBe("completed");
    expect(doneB.status).toBe("completed");
    const artifactAId = doneA.artifactId as string;
    const artifactBId = doneB.artifactId as string;
    expect(artifactAId).not.toBe(artifactBId);
    expect(doneA.quota).toEqual({ normalUnits: 60, multiplier: 1, chargedUnits: 60 });
    expect(doneB.quota).toEqual({ normalUnits: 60, multiplier: 1, chargedUnits: 60 });

    // B never joins A's artifact, never gets the shared rate, never reads A's content.
    expect(await artifactStore.hasUserAccess(USER_B, artifactAId)).toBe(false);
    expect(await artifactStore.hasUserAccess(USER_C, artifactAId)).toBe(false);
    expect(await countRows(d1, "SELECT COUNT(*) AS count FROM credit_transactions WHERE operation_type = 'transcript_shared'")).toBe(0);

    const artifactA = await artifactStore.findArtifactById(artifactAId);
    const artifactB = await artifactStore.findArtifactById(artifactBId);
    expect(artifactA?.artifactVersion).toBe(1);
    expect(artifactB?.artifactVersion).toBe(2);
    expect(artifactA?.objectKeyJson).not.toBe(artifactB?.objectKeyJson);
    expect(artifactA?.createdByUserId).toBe(USER_A);
    expect(artifactB?.createdByUserId).toBe(USER_B);

    const getB = await handleTranscriptGet(contentCode, env, USER_B);
    expect(getB.status).toBe(200);
    expect((getB.body as { transcript: TranscriptPayload }).transcript.fullText).toBe(
      "B private transcript"
    );

    // A third account that never requested anything gets 404 with no payload at all.
    const getC = await handleTranscriptGet(contentCode, env, USER_C);
    expect(getC.status).toBe(404);
    expect(getC.body).toEqual({ error: "transcript_not_found" });

    // The outsider can create a job of their own, but that never exposes A's artifact.
    const resC = await postTranscript(env, USER_C, input);
    expect(resC.status).toBe(202);
    expect(await artifactStore.hasUserAccess(USER_C, artifactAId)).toBe(false);
    const doneCQuota = await d1
      .prepare("SELECT COUNT(*) AS count FROM credit_transactions WHERE operation_type = 'transcript_shared'")
      .first<{ count: number }>();
    expect(Number(doneCQuota?.count)).toBe(0);
  });

  it("releases every follower reservation on leader failure and lets followers retry into one pollable job", async () => {
    const { d1, env, storage, enqueues } = createEnv();
    const { service, artifactStore, quotaStore } = createService(d1, env);
    const input = publicEpisode();

    const resA = await postTranscript(env, USER_A, input);
    const jobA = resA.body.jobId as string;
    const contentCode = resA.body.contentCode as string;
    const resB = await postTranscript(env, USER_B, input);
    const jobB = resB.body.jobId as string;
    const resC = await postTranscript(env, USER_C, input);
    const jobC = resC.body.jobId as string;
    expect(jobB).toMatch(/^jobu_/);
    expect(jobC).toMatch(/^jobu_/);

    await service.failJob(jobA, "provider_timeout");

    // Every participant sees the failure with the leader's error code.
    for (const [userId, jobId] of [
      [USER_A, jobA],
      [USER_B, jobB],
      [USER_C, jobC]
    ] as const) {
      const polled = await handleTranscriptStatus(jobId, env, userId);
      expect(polled.status).toBe(200);
      const body = polled.body as Record<string, unknown>;
      expect(body.status).toBe("failed");
      expect(body.errorCode).toBe("provider_timeout");
      expect(body.artifactId).toBeUndefined();
      expect(body.quota).toBeUndefined();
    }

    // No orphaned reservations: three reservations existed, all released.
    expect(await countRows(d1, "SELECT COUNT(*) AS count FROM credit_transactions WHERE status = 'RESERVED'")).toBe(0);
    expect(await countRows(d1, "SELECT COUNT(*) AS count FROM credit_transactions WHERE status = 'RELEASED'")).toBe(3);

    // The creator retries in place: same job id, next attempt, still one work item.
    const retryA = await postTranscript(env, USER_A, input);
    expect(retryA.status).toBe(202);
    expect(retryA.body.jobId).toBe(jobA);
    const polledA = (await handleTranscriptStatus(jobA, env, USER_A)).body as Record<string, unknown>;
    expect(polledA.status).toBe("queued");
    expect(polledA.attemptCount).toBe(2);

    // Followers retry onto the same re-armed job: their own ids stay pollable, and the
    // re-armed retry does not enqueue a second copy of the work.
    const retryB = await postTranscript(env, USER_B, input);
    expect(retryB.status).toBe(202);
    expect(retryB.body.jobId).toBe(jobB);
    const retryC = await postTranscript(env, USER_C, input);
    expect(retryC.status).toBe(202);
    expect(retryC.body.jobId).toBe(jobC);
    expect(enqueues).toEqual([jobA, jobA]);

    const polledRetryB = await handleTranscriptStatus(jobB, env, USER_B);
    expect(polledRetryB.status).toBe(200);
    expect(["queued", "running"]).toContain((polledRetryB.body as Record<string, unknown>).status);

    // The retried generation completes for everyone, at their own rate.
    await service.completeJobWithArtifact(jobA, payloadFor(contentCode, "retried transcript"), storage);
    const finalA = (await handleTranscriptStatus(jobA, env, USER_A)).body as Record<string, unknown>;
    const finalB = (await handleTranscriptStatus(jobB, env, USER_B)).body as Record<string, unknown>;
    const finalC = (await handleTranscriptStatus(jobC, env, USER_C)).body as Record<string, unknown>;
    expect(finalA.status).toBe("completed");
    expect(finalB.status).toBe("completed");
    expect(finalC.status).toBe("completed");
    expect(finalA.quota).toEqual({ normalUnits: 60, multiplier: 1, chargedUnits: 60 });
    expect(finalB.quota).toEqual({ normalUnits: 60, multiplier: 0.2, chargedUnits: 12 });
    expect(finalC.quota).toEqual({ normalUnits: 60, multiplier: 0.2, chargedUnits: 12 });
    expect(finalB.artifactId).toBe(finalA.artifactId);

    const txA = await quotaStore.findByReference(USER_A, "transcript_generation", jobA);
    expect(txA?.status).toBe("COMMITTED");
    expect(await countRows(d1, "SELECT COUNT(*) AS count FROM credit_transactions WHERE status = 'RESERVED'")).toBe(0);
  });

  it("lets a joiner start a fresh deduplicated generation when the leader stays failed", async () => {
    const { d1, env, storage, enqueues } = createEnv();
    const { service, artifactStore } = createService(d1, env);
    const input = publicEpisode();

    const resA = await postTranscript(env, USER_A, input);
    const jobA = resA.body.jobId as string;
    const contentCode = resA.body.contentCode as string;
    const resB = await postTranscript(env, USER_B, input);
    const jobB = resB.body.jobId as string;
    expect(jobB).toMatch(/^jobu_/);

    await service.failJob(jobA, "stt_unavailable");

    const retryB = await postTranscript(env, USER_B, input);
    expect(retryB.status).toBe(202);
    const retryJobB = retryB.body.jobId as string;
    expect(retryJobB).toMatch(/^job_/);
    expect(retryJobB).not.toBe(jobB);
    expect(retryJobB).not.toBe(jobA);
    expect(enqueues).toEqual([jobA, retryJobB]);

    const retryJob = await artifactStore.getJobById(retryJobB);
    const baseJob = await artifactStore.getJobById(jobA);
    expect(retryJob?.dedupeKey).toBe(`${baseJob!.contentId}:auto:1#retry`);
    expect(retryJob?.userId).toBe(USER_B);
    expect(retryJob?.status).toBe("queued");

    const polledRetryB = await handleTranscriptStatus(retryJobB, env, USER_B);
    expect(polledRetryB.status).toBe(200);
    expect((polledRetryB.body as Record<string, unknown>).status).toBe("queued");

    // A third user retrying the same failed content joins that new generation instead of
    // starting a second one.
    const retryC = await postTranscript(env, USER_C, input);
    expect(retryC.status).toBe(202);
    const retryJobC = retryC.body.jobId as string;
    expect(retryJobC).toMatch(/^jobu_/);
    expect(enqueues).toEqual([jobA, retryJobB]);
    const followerC = await artifactStore.getJobFollowerById(retryJobC);
    expect(followerC?.jobId).toBe(retryJobB);

    await service.completeJobWithArtifact(retryJobB, payloadFor(contentCode, "regenerated transcript"), storage);
    const finalB = (await handleTranscriptStatus(retryJobB, env, USER_B)).body as Record<string, unknown>;
    const finalC = (await handleTranscriptStatus(retryJobC, env, USER_C)).body as Record<string, unknown>;
    expect(finalB.status).toBe("completed");
    expect(finalC.status).toBe("completed");
    expect(finalC.quota).toEqual({ normalUnits: 60, multiplier: 0.2, chargedUnits: 12 });
  });

  it("fails closed for the default deployment: a PUBLIC_REUSE request on a non-allowlisted host stays per-account", async () => {
    const { d1, env, enqueues } = createEnv();
    const { artifactStore, contentStore } = createService(d1, env);
    // The client asks for shareable content, but the feed host is not in the server-side
    // PUBLIC_REUSE_FEED_HOSTS allowlist, so the server resolves PRIVATE_ACCOUNT (the
    // default when the allowlist is empty). Sharing must not be granted by request alone.
    const input: TranscriptRequestInput = {
      sharePolicy: "PUBLIC_REUSE",
      episode: {
        feedUrl: "https://not-allowlisted.example.org/show.xml",
        guid: "ep-default-1",
        audioUrl: "https://cdn.not-allowlisted.example.org/ep-default-1.mp3",
        durationMs: 3_600_000
      }
    };

    const resA = await postTranscript(env, USER_A, input);
    expect(resA.status).toBe(202);
    const jobA = resA.body.jobId as string;
    expect(jobA).toMatch(/^job_/);

    const resB = await postTranscript(env, USER_B, input);
    expect(resB.status).toBe(202);
    const jobB = resB.body.jobId as string;
    expect(jobB).toMatch(/^job_/);
    expect(jobB).not.toBe(jobA);
    expect(resB.body.jobId).not.toMatch(/^jobu_/);

    const content = await contentStore.findByContentCode(resA.body.contentCode as string);
    expect(content?.sharePolicy).toBe("PRIVATE_ACCOUNT");

    // Each account got its own work item; no follower/0.2x row exists.
    expect(enqueues).toEqual([jobA, jobB]);
    expect(await countRows(d1, "SELECT COUNT(*) AS count FROM transcript_job_followers")).toBe(0);
    expect(await countRows(d1, "SELECT COUNT(*) AS count FROM credit_transactions WHERE operation_type = 'transcript_shared'")).toBe(0);

    // Both users still receive a job status they are entitled to poll.
    expect((await handleTranscriptStatus(jobA, env, USER_A)).status).toBe(200);
    expect((await handleTranscriptStatus(jobB, env, USER_B)).status).toBe(200);
    expect((await handleTranscriptStatus(jobA, env, USER_B)).status).toBe(404);
    expect(await artifactStore.getJobFollowerForUser(jobA, USER_B)).toBeNull();
  });
});

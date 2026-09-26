import { afterEach, expect, it, vi } from "vitest";
import { spawnSync } from "node:child_process";
import { mkdtemp, rm } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import type { DatabaseSync } from "node:sqlite";
import { recognize, synthesize, transcodeAudio, AudioJobRunner, type AudioProvider } from "./audio";
import { openServerDatabase } from "./database";
import { localObjectStore } from "./storage";
import { D1ArtifactStore } from "../src/artifacts/artifactStore";
import { D1QuotaLedgerStore } from "../src/artifacts/quotaLedger";
import { setSafeFetchResolverOverride, setSafeFetchTransportOverride } from "../src/net/safeRemoteFetcher";

const mimoStt: AudioProvider = { id: "mimo-stt", capability: "stt", adapter: "mimo", model: "mimo-v2.5-asr", voice: null, secret_ref: "AI_PROVIDER_SECRET_1", enabled: 1, priority: 1 };
const deepgramStt: AudioProvider = { ...mimoStt, id: "deepgram-stt", adapter: "deepgram", model: "nova-3" };
const ffmpegAvailable = spawnSync("ffmpeg", ["-version"]).status === 0;
type RunnerRuntime = ConstructorParameters<typeof AudioJobRunner>[0];

afterEach(() => {
  vi.unstubAllGlobals();
  setSafeFetchTransportOverride(null);
  setSafeFetchResolverOverride(null);
});

/* ------------------------------------------------------------------ */
/* Provider adapters (a separate boundary: the provider HTTP APIs)     */
/* ------------------------------------------------------------------ */

it("calls MiMo ASR with an MP3 data URL and parses text", async () => {
  const remote = vi.fn(async (_url: string, init: RequestInit) => {
    expect(init.headers).toMatchObject({ "api-key": "private-key" });
    const body = JSON.parse(init.body as string);
    expect(body.model).toBe("mimo-v2.5-asr");
    expect(body.messages[0].content[0].input_audio.data).toBe("data:audio/mpeg;base64,AQID");
    expect(body.asr_options.language).toBe("zh");
    return Response.json({ choices: [{ message: { content: "一段转写" } }] });
  });
  vi.stubGlobal("fetch", remote);
  expect(await recognize(mimoStt, "private-key", new Uint8Array([1, 2, 3]), "zh")).toBe("一段转写");
});

it("calls Deepgram STT and MiMo TTS using task-specific adapters", async () => {
  const remote = vi.fn(async (url: string, init: RequestInit) => {
    if (url.includes("/listen")) {
      expect(init.headers).toMatchObject({ Authorization: "Token private-key", "Content-Type": "audio/mpeg" });
      return Response.json({ results: { channels: [{ alternatives: [{ transcript: "A podcast" }] }] } });
    }
    const body = JSON.parse(init.body as string);
    expect(body.messages[0]).toEqual({ role: "assistant", content: "Hello" });
    expect(body.audio).toEqual({ format: "mp3", voice: "mimo_default" });
    return Response.json({ choices: [{ message: { audio: { data: Buffer.from([0xff, 0xfb, 1]).toString("base64") } } }] });
  });
  vi.stubGlobal("fetch", remote);
  expect(await recognize(deepgramStt, "private-key", new Uint8Array([1]), "auto")).toBe("A podcast");
  expect(await synthesize({ ...mimoStt, capability: "tts", model: "mimo-v2.5-tts" }, "private-key", "Hello"))
    .toEqual(Buffer.from([0xff, 0xfb, 1]));
});

it("uses OpenAI multipart STT and MP3 TTS endpoints", async () => {
  const remote = vi.fn(async (url: string, init: RequestInit) => {
    expect(init.headers).toMatchObject({ Authorization: "Bearer private-key" });
    if (url.endsWith("/transcriptions")) {
      expect(init.body).toBeInstanceOf(FormData);
      expect((init.body as FormData).get("model")).toBe("gpt-4o-transcribe");
      return Response.json({ text: "recognized text" });
    }
    expect(JSON.parse(init.body as string)).toMatchObject({ model: "gpt-4o-mini-tts", voice: "alloy", input: "Hello", response_format: "mp3" });
    return new Response(Buffer.from([0xff, 0xfb, 1]), { headers: { "Content-Type": "audio/mpeg" } });
  });
  vi.stubGlobal("fetch", remote);
  const openai: AudioProvider = { ...mimoStt, adapter: "openai", model: "gpt-4o-transcribe" };
  expect(await recognize(openai, "private-key", new Uint8Array([1, 2]), "en")).toBe("recognized text");
  expect(await synthesize({ ...openai, capability: "tts", model: "gpt-4o-mini-tts", voice: "alloy" }, "private-key", "Hello"))
    .toEqual(new Uint8Array([0xff, 0xfb, 1]));
});

it("refuses to follow a provider redirect instead of re-sending the request elsewhere", async () => {
  // Provider hosts are compile-time constants, so the fetch carries
  // `redirect: "error"`: the runtime must never follow a 3xx to a host the
  // gateway did not choose. (A real fetch rejects; this asserts the property
  // that forces that behaviour plus the surfaced failure.)
  const remote = vi.fn(async (_url: string, init: RequestInit) => {
    expect(init.redirect).toBe("error");
    expect(init.method).toBe("POST");
    return new Response(null, { status: 302, headers: { Location: "http://169.254.169.254/latest/meta-data" } });
  });
  vi.stubGlobal("fetch", remote);
  await expect(recognize(deepgramStt, "private-key", new Uint8Array([1]), "en")).rejects.toThrow("stt_upstream_302");
  await expect(synthesize({ ...mimoStt, capability: "tts", model: "mimo-v2.5-tts" }, "private-key", "Hello")).rejects.toThrow("tts_upstream_302");
  expect(remote).toHaveBeenCalledTimes(2);
});

/* ------------------------------------------------------------------ */
/* Harness: real SQLite + real FFmpeg, mocked network                  */
/* ------------------------------------------------------------------ */

/**
 * Audio downloads go through the SSRF-safe Node transport (pinned IP), not
 * global fetch, so tests serve them from the transport seam. `fetch` stays
 * stubbed only for the provider APIs, which are a separate boundary.
 */
function serveAudio(bytes: Buffer) {
  const calls: string[] = [];
  setSafeFetchResolverOverride(async () => ["93.184.216.34"]);
  setSafeFetchTransportOverride({
    async request({ url }) {
      calls.push(url.toString());
      return {
        status: 200,
        headers: { get: (name: string) => name.toLowerCase() === "content-type" ? "audio/wav" : null },
        body: { async *[Symbol.asyncIterator]() { yield new Uint8Array(bytes); } },
        destroy: () => {}
      };
    }
  });
  return calls;
}

function serveAudioFailure(error: string) {
  setSafeFetchResolverOverride(async () => ["93.184.216.34"]);
  setSafeFetchTransportOverride({ async request() { throw new Error(error); } });
}

function sineWav(seconds: number): Buffer {
  const generated = spawnSync("ffmpeg", ["-hide_banner", "-loglevel", "error", "-f", "lavfi", "-i", `sine=frequency=440:duration=${seconds}`, "-ar", "16000", "-ac", "1", "-f", "wav", "pipe:1"], { maxBuffer: 32_000_000 });
  expect(generated.status).toBe(0);
  return generated.stdout;
}

function stubDeepgram(text = "Episode transcript") {
  const calls: string[] = [];
  vi.stubGlobal("fetch", vi.fn(async (url: string) => {
    calls.push(String(url));
    return Response.json({ results: { channels: [{ alternatives: [{ transcript: text }] }] } });
  }));
  return calls;
}

type Harness = {
  directory: string;
  file: string;
  sqlite: DatabaseSync;
  db: D1Database;
  runtime: RunnerRuntime;
  close(): Promise<void>;
  reopen(): Harness;
};

async function openHarness(): Promise<Harness> {
  const directory = await mkdtemp(join(tmpdir(), "wristbrief-audio-job-"));
  const file = join(directory, "database.sqlite");
  const opened = openServerDatabase(file);
  const harness: Harness = {
    directory,
    file,
    sqlite: opened.sqlite,
    db: opened.db,
    runtime: { ACCOUNT_DB: opened.db, TRANSCRIPTS_BUCKET: localObjectStore(join(directory, "objects")), AI_PROVIDER_SECRET_2: "test-only-key" },
    async close() {
      try { harness.sqlite.close(); } catch { /* already closed */ }
      await rm(directory, { recursive: true, force: true });
    },
    /** A new process opening the same durable database. */
    reopen() {
      try { harness.sqlite.close(); } catch { /* already closed */ }
      const next = openServerDatabase(file);
      harness.sqlite = next.sqlite;
      harness.db = next.db;
      harness.runtime = { ...harness.runtime, ACCOUNT_DB: next.db };
      return harness;
    }
  };
  harness.sqlite.prepare("INSERT INTO podcast_contents (id, content_code, media_type, share_policy, status, created_at, updated_at) VALUES ('podcast-1', 'code-1', 'podcast', 'PRIVATE_ACCOUNT', 'active', ?, ?)").run(Date.now(), Date.now());
  harness.sqlite.prepare("UPDATE audio_provider_configs SET enabled = 1 WHERE id = 'deepgram-stt'").run();
  return harness;
}

function seedJob(
  sqlite: DatabaseSync,
  { id = "job-1", status = "queued", attemptCount = 1, leaseExpiresAt = null, audioUrl = "https://media.example.org/episode.wav" }: { id?: string; status?: string; attemptCount?: number; leaseExpiresAt?: number | null; audioUrl?: string } = {}
) {
  const now = Date.now();
  sqlite.prepare("INSERT INTO artifact_jobs (id, dedupe_key, user_id, content_id, artifact_type, language, requested_version, status, lease_owner, lease_expires_at, attempt_count, created_at, updated_at) VALUES (?, ?, 'user-1', 'podcast-1', 'transcript', 'en', 1, ?, NULL, ?, ?, ?, ?)")
    .run(id, `dedupe-${id}`, status, leaseExpiresAt, attemptCount, now, now);
  sqlite.prepare("INSERT INTO artifact_job_inputs (job_id, audio_url, duration_ms) VALUES (?, ?, ?)").run(id, audioUrl, 60_000);
}

function jobRow(sqlite: DatabaseSync, jobId = "job-1") {
  return sqlite.prepare("SELECT status, error_code, attempt_count, lease_expires_at FROM artifact_jobs WHERE id = ?").get(jobId) as { status: string; error_code: string | null; attempt_count: number; lease_expires_at: number | null };
}

function transactions(sqlite: DatabaseSync, jobId = "job-1") {
  return sqlite.prepare("SELECT units, multiplier, status FROM credit_transactions WHERE reference_id = ? AND operation_type = 'transcript_generation'").all(jobId) as Array<{ units: number; multiplier: number; status: string }>;
}

function artifactCount(sqlite: DatabaseSync) {
  return (sqlite.prepare("SELECT COUNT(*) AS count FROM transcript_artifacts").get() as { count: number }).count;
}

async function waitFor(predicate: () => boolean, timeoutMs = 10_000) {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    if (predicate()) return;
    await new Promise((resolve) => setTimeout(resolve, 10));
  }
  throw new Error("condition was not reached in time");
}

/* ------------------------------------------------------------------ */
/* FFmpeg conversion (behaviour unchanged)                             */
/* ------------------------------------------------------------------ */

it.skipIf(!ffmpegAvailable)("converts remote audio into bounded MP3 segments", async () => {
  const fetched = serveAudio(sineWav(1));
  const parts = await transcodeAudio("https://audio.example.org/episode.wav");
  expect(fetched).toEqual(["https://audio.example.org/episode.wav"]);
  expect(parts).toHaveLength(1);
  expect(parts[0].bytes.subarray(0, 3).toString()).toBe("ID3");
});

it.skipIf(!ffmpegAvailable)("refuses audio longer than the configured duration cap", async () => {
  serveAudio(sineWav(3));
  await expect(transcodeAudio("https://audio.example.org/long.wav", { maxDurationMs: 1000 })).rejects.toThrow("audio_duration_unsupported");
});

/* ------------------------------------------------------------------ */
/* Duration settlement                                                 */
/* ------------------------------------------------------------------ */

it.skipIf(!ffmpegAvailable)("settles by the measured duration, not the declared duration", async () => {
  const harness = await openHarness();
  try {
    seedJob(harness.sqlite);
    // The client declared one minute; the real audio is 61 seconds (2 billable minutes).
    await new D1QuotaLedgerStore(harness.db).reserve("user-1", "transcript_generation", "job-1", 1, 1.0);
    serveAudio(sineWav(61));
    stubDeepgram();

    await new AudioJobRunner(harness.runtime).tick();

    expect(transactions(harness.sqlite)).toEqual([{ units: 2, multiplier: 1, status: "COMMITTED" }]);
    expect(jobRow(harness.sqlite).status).toBe("completed");

    const objectKey = (harness.sqlite.prepare("SELECT object_key_json FROM transcript_artifacts WHERE content_id = 'podcast-1'").get() as { object_key_json: string }).object_key_json;
    const stored = await harness.runtime.TRANSCRIPTS_BUCKET.get(objectKey);
    const payload = JSON.parse(await stored!.text());
    expect(payload.durationMs).toBeGreaterThanOrEqual(61_000);
    expect(payload.segments).toHaveLength(1);
  } finally { await harness.close(); }
});

it.skipIf(!ffmpegAvailable)("refuses a job beyond the executable billing ceiling and releases the reservation", async () => {
  const harness = await openHarness();
  try {
    seedJob(harness.sqlite);
    await new D1QuotaLedgerStore(harness.db).reserve("user-1", "transcript_generation", "job-1", 1, 1.0);
    serveAudio(sineWav(61));
    const providerCalls = stubDeepgram();
    harness.runtime.TRANSCRIPT_MAX_BILLABLE_MINUTES = 1;

    await new AudioJobRunner(harness.runtime).tick();

    expect(jobRow(harness.sqlite)).toMatchObject({ status: "failed", error_code: "audio_billing_cap_exceeded" });
    // No partial charge: the hold is released, its amount untouched.
    expect(transactions(harness.sqlite)).toEqual([{ units: 1, multiplier: 1, status: "RELEASED" }]);
    expect(artifactCount(harness.sqlite)).toBe(0);
    // The ceiling is enforced before any provider work.
    expect(providerCalls).toHaveLength(0);
  } finally { await harness.close(); }
});

it.skipIf(!ffmpegAvailable)("releases the reservation when the provider fails mid-transcription", async () => {
  const harness = await openHarness();
  try {
    seedJob(harness.sqlite);
    await new D1QuotaLedgerStore(harness.db).reserve("user-1", "transcript_generation", "job-1", 1, 1.0);
    serveAudio(sineWav(1));
    vi.stubGlobal("fetch", vi.fn(async () => new Response("upstream down", { status: 503 })));

    await new AudioJobRunner(harness.runtime).tick();

    expect(jobRow(harness.sqlite)).toMatchObject({ status: "failed", error_code: "stt_upstream_503" });
    expect(transactions(harness.sqlite)).toEqual([{ units: 1, multiplier: 1, status: "RELEASED" }]);
    expect(artifactCount(harness.sqlite)).toBe(0);
    expect(jobRow(harness.sqlite).lease_expires_at).toBeLessThanOrEqual(Date.now());
  } finally { await harness.close(); }
});

it.skipIf(!ffmpegAvailable)("releases the reservation when the audio download fails", async () => {
  const harness = await openHarness();
  try {
    seedJob(harness.sqlite);
    await new D1QuotaLedgerStore(harness.db).reserve("user-1", "transcript_generation", "job-1", 1, 1.0);
    serveAudioFailure("ECONNREFUSED");
    stubDeepgram();

    await new AudioJobRunner(harness.runtime).tick();

    expect(jobRow(harness.sqlite)).toMatchObject({ status: "failed", error_code: "audio_fetch_network_error" });
    expect(transactions(harness.sqlite)).toEqual([{ units: 1, multiplier: 1, status: "RELEASED" }]);
  } finally { await harness.close(); }
});

it.skipIf(!ffmpegAvailable)("releases the reservations of users who joined a job that then failed", async () => {
  const harness = await openHarness();
  try {
    seedJob(harness.sqlite);
    await new D1QuotaLedgerStore(harness.db).reserve("user-1", "transcript_generation", "job-1", 1, 1.0);
    // A second account joined the in-flight job and is holding its own 0.2x share.
    const now = Date.now();
    harness.sqlite.prepare("INSERT INTO transcript_job_followers (id, job_id, user_id, content_id, language, status, attempt_count, error_code, normal_units, quota_multiplier, quota_units, artifact_id, created_at, updated_at) VALUES ('jobu-1', 'job-1', 'user-2', 'podcast-1', 'en', 'running', 1, NULL, 1, 0.2, 0.2, NULL, ?, ?)").run(now, now);
    await new D1QuotaLedgerStore(harness.db).reserve("user-2", "transcript_shared", "jobu-1", 1, 0.2);
    serveAudioFailure("ECONNREFUSED");
    stubDeepgram();

    await new AudioJobRunner(harness.runtime).tick();

    expect(harness.sqlite.prepare("SELECT status, error_code FROM transcript_job_followers WHERE id = 'jobu-1'").get())
      .toEqual({ status: "failed", error_code: "audio_fetch_network_error" });
    expect(harness.sqlite.prepare("SELECT units, status FROM credit_transactions WHERE reference_id = 'jobu-1'").all())
      .toEqual([{ units: 0.2, status: "RELEASED" }]);
    expect(transactions(harness.sqlite)).toEqual([{ units: 1, multiplier: 1, status: "RELEASED" }]);
  } finally { await harness.close(); }
});

/* ------------------------------------------------------------------ */
/* Crash / abnormal-interruption recovery                              */
/* ------------------------------------------------------------------ */

it.skipIf(!ffmpegAvailable)("reclaims a job whose lease expired after a kill and settles it exactly once", async () => {
  const harness = await openHarness();
  try {
    // The previous process was SIGKILLed mid-job: the row is still `running`
    // with a lease in the past and the reservation is still held.
    seedJob(harness.sqlite, { status: "running", attemptCount: 1, leaseExpiresAt: Date.now() - 60_000 });
    await new D1QuotaLedgerStore(harness.db).reserve("user-1", "transcript_generation", "job-1", 1, 1.0);
    const restart = harness.reopen();
    serveAudio(sineWav(1));
    stubDeepgram();

    const recovered = new AudioJobRunner(restart.runtime);
    await recovered.tick();

    expect(jobRow(restart.sqlite)).toMatchObject({ status: "completed", error_code: null, attempt_count: 2 });
    expect(transactions(restart.sqlite)).toEqual([{ units: 1, multiplier: 1, status: "COMMITTED" }]);
    expect(artifactCount(restart.sqlite)).toBe(1);

    // Later polls and further restarts must not charge again, publish a second
    // transcript, or move an already committed transaction.
    await recovered.tick();
    await new AudioJobRunner(restart.runtime).tick();
    expect(transactions(restart.sqlite)).toEqual([{ units: 1, multiplier: 1, status: "COMMITTED" }]);
    expect(artifactCount(restart.sqlite)).toBe(1);
    expect(jobRow(restart.sqlite).attempt_count).toBe(2);
  } finally { await harness.close(); }
});

it.skipIf(!ffmpegAvailable)("settles a reclaimed job whose artifact was already published without re-charging", async () => {
  const harness = await openHarness();
  try {
    // Exactly what a kill between `createArtifact` and the status update leaves
    // behind: a published transcript, a held reservation, an expired lease.
    seedJob(harness.sqlite, { status: "running", attemptCount: 1, leaseExpiresAt: Date.now() - 60_000 });
    await new D1QuotaLedgerStore(harness.db).reserve("user-1", "transcript_generation", "job-1", 3, 1.0);
    await new D1ArtifactStore(harness.db).createArtifact({
      id: "art-published", contentId: "podcast-1", language: "en", artifactVersion: 1, provider: "deepgram", model: "nova-3",
      status: "ready", objectKeyJson: "transcripts/podcast-1/en/1/transcript.json", objectKeyText: "transcripts/podcast-1/en/1/transcript.txt",
      objectKeySegments: "transcripts/podcast-1/en/1/segments.json", transcriptHash: null, wordCount: 3, segmentCount: 1,
      qualityScore: 1.0, sharePolicy: "PRIVATE_ACCOUNT", createdByUserId: "user-1"
    });
    const restart = harness.reopen();
    serveAudio(sineWav(1));
    const providerCalls = stubDeepgram();

    await new AudioJobRunner(restart.runtime).tick();

    expect(jobRow(restart.sqlite).status).toBe("completed");
    // No second transcript, no second provider call, no second charge, and the
    // reservation that was already held becomes the single charge.
    expect(artifactCount(restart.sqlite)).toBe(1);
    expect(providerCalls).toHaveLength(0);
    expect(transactions(restart.sqlite)).toEqual([{ units: 3, multiplier: 1, status: "COMMITTED" }]);
    // The user keeps access to what was already paid for.
    expect(restart.sqlite.prepare("SELECT COUNT(*) AS count FROM user_artifact_access WHERE user_id = 'user-1' AND artifact_id = 'art-published'").get()).toEqual({ count: 1 });
  } finally { await harness.close(); }
});

it.skipIf(!ffmpegAvailable)("fails a job cleanly once its bounded attempts are exhausted", async () => {
  const harness = await openHarness();
  try {
    seedJob(harness.sqlite, { status: "running", attemptCount: 3, leaseExpiresAt: Date.now() - 60_000 });
    await new D1QuotaLedgerStore(harness.db).reserve("user-1", "transcript_generation", "job-1", 1, 1.0);
    serveAudio(sineWav(1));
    stubDeepgram();

    await new AudioJobRunner(harness.runtime).tick();

    expect(jobRow(harness.sqlite)).toMatchObject({ status: "failed", error_code: "audio_attempts_exhausted" });
    expect(transactions(harness.sqlite)).toEqual([{ units: 1, multiplier: 1, status: "RELEASED" }]);
    expect(artifactCount(harness.sqlite)).toBe(0);
  } finally { await harness.close(); }
});

it.skipIf(!ffmpegAvailable)("still processes an explicit user retry that has already used up its crash attempts", async () => {
  const harness = await openHarness();
  try {
    // After several failures the user retries through TranscriptService, which
    // re-queues the job and increments attempt_count. The crash-reclaim cap must
    // not swallow that explicit retry.
    seedJob(harness.sqlite, { status: "queued", attemptCount: 5 });
    await new D1QuotaLedgerStore(harness.db).reserve("user-1", "transcript_generation", "job-1", 1, 1.0);
    serveAudio(sineWav(1));
    stubDeepgram();

    await new AudioJobRunner(harness.runtime).tick();

    expect(jobRow(harness.sqlite)).toMatchObject({ status: "completed", error_code: null, attempt_count: 6 });
    expect(transactions(harness.sqlite)).toEqual([{ units: 1, multiplier: 1, status: "COMMITTED" }]);
    expect(artifactCount(harness.sqlite)).toBe(1);
  } finally { await harness.close(); }
});

it.skipIf(!ffmpegAvailable)("hands an in-flight job back to the queue on graceful shutdown and the next process resumes it", async () => {
  const harness = await openHarness();
  try {
    seedJob(harness.sqlite);
    await new D1QuotaLedgerStore(harness.db).reserve("user-1", "transcript_generation", "job-1", 1, 1.0);
    serveAudio(sineWav(1));

    let releaseProvider: () => void = () => {};
    const providerGate = new Promise<void>((resolve) => { releaseProvider = () => resolve(); });
    vi.stubGlobal("fetch", vi.fn(async () => {
      await providerGate;
      return Response.json({ results: { channels: [{ alternatives: [{ transcript: "Episode transcript" }] }] } });
    }));

    const runner = new AudioJobRunner(harness.runtime);
    const inFlight = runner.tick();
    await waitFor(() => jobRow(harness.sqlite).status === "running");

    // server.close() -> audio.stop() while the provider call is still running.
    runner.stop();
    releaseProvider();
    await inFlight;

    const stopped = jobRow(harness.sqlite);
    expect(stopped.status).toBe("queued");
    expect(stopped.lease_expires_at).toBeLessThanOrEqual(Date.now());
    // The hold survives a graceful shutdown: it is a reservation, not a charge.
    expect(transactions(harness.sqlite)).toEqual([{ units: 1, multiplier: 1, status: "RESERVED" }]);
    expect(artifactCount(harness.sqlite)).toBe(0);

    // The next process reclaims it immediately (the lease is already expired).
    stubDeepgram();
    await new AudioJobRunner(harness.runtime).tick();
    expect(jobRow(harness.sqlite).status).toBe("completed");
    expect(transactions(harness.sqlite)).toEqual([{ units: 1, multiplier: 1, status: "COMMITTED" }]);
    expect(artifactCount(harness.sqlite)).toBe(1);
  } finally { await harness.close(); }
});

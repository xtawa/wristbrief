import { D1ArtifactStore } from "../src/artifacts/artifactStore";
import { D1ContentStore } from "../src/content/contentStore";
import { ContentResolver } from "../src/content/contentResolver";
import { D1QuotaLedgerStore } from "../src/artifacts/quotaLedger";
import { TranscriptService } from "../src/artifacts/transcriptService";
import { createTranscriptStorage } from "../src/artifacts/transcriptStorage";
import { safeFetch } from "../src/net/safeRemoteFetcher";
import { mkdtemp, readFile, readdir, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { execFile, spawn } from "node:child_process";
import { promisify } from "node:util";

export type AudioProvider = { id: string; capability: "stt" | "tts"; adapter: "mimo" | "deepgram" | "openai"; model: string; voice: string | null; secret_ref: string; enabled: number; priority: number };
export type AudioRuntime = { ACCOUNT_DB: D1Database; TRANSCRIPTS_BUCKET: R2Bucket; [key: string]: unknown };

/** Download / conversion limits. FFmpeg and FFprobe come from the server image. */
const AUDIO_MAX_BYTES = 96 * 1024 * 1024;
/**
 * The audio download gets a 5 minute deadline that now covers connect, headers
 * AND the whole body read (see safeRemoteFetcher). A connection that stops
 * making progress is cut after 30 seconds, so a slow-but-moving download fits.
 */
const AUDIO_FETCH_TIMEOUT_MS = 300_000;
const AUDIO_STALL_TIMEOUT_MS = 30_000;
const AUDIO_SEGMENT_MS = 240_000;
const AUDIO_MAX_SEGMENT_BYTES = 7_000_000;
const DEFAULT_MAX_DURATION_MS = 3 * 60 * 60 * 1000;
/** Hard per-job billing ceiling: a single job can never consume unbounded quota. */
const DEFAULT_MAX_BILLABLE_MINUTES = 180;
const DEFAULT_MAX_ATTEMPTS = 3;
const DEFAULT_LEASE_MS = 240_000;

export async function providers(db: D1Database): Promise<AudioProvider[]> {
  return (await db.prepare("SELECT id, capability, adapter, model, voice, secret_ref, enabled, priority FROM audio_provider_configs ORDER BY capability, priority, id").all<AudioProvider>()).results;
}

export async function selectedProvider(runtime: AudioRuntime, capability: "stt" | "tts"): Promise<{ config: AudioProvider; key: string } | null> {
  for (const config of await providers(runtime.ACCOUNT_DB)) {
    if (config.capability !== capability || !config.enabled) continue;
    const key = runtime[config.secret_ref];
    if (typeof key === "string" && key) return { config, key };
  }
  return null;
}

/** Positive-number runtime configuration: runtime value, then process.env, then default. */
function runtimeNumber(runtime: AudioRuntime, key: string, fallback: number): number {
  const raw = runtime[key] ?? (typeof process !== "undefined" ? process.env?.[key] : undefined);
  const parsed = Number(raw);
  return Number.isFinite(parsed) && parsed > 0 ? parsed : fallback;
}

function timeoutSignal(ms: number): AbortSignal { return AbortSignal.timeout(ms); }

export type TranscodeOptions = {
  /** Reject audio longer than this (default 3 hours). */
  maxDurationMs?: number;
  maxBytes?: number;
  timeoutMs?: number;
};

export async function transcodeAudio(audioUrl: string, options: TranscodeOptions = {}): Promise<Array<{ bytes: Buffer; startMs: number; endMs: number }>> {
  const maxDurationMs = options.maxDurationMs ?? DEFAULT_MAX_DURATION_MS;
  const fetched = await safeFetch(audioUrl, {
    maxBytes: options.maxBytes ?? AUDIO_MAX_BYTES,
    timeoutMs: options.timeoutMs ?? AUDIO_FETCH_TIMEOUT_MS,
    idleTimeoutMs: AUDIO_STALL_TIMEOUT_MS,
    allowedContentTypes: [/^audio\//i, /^application\/octet-stream/i]
  });
  if (!fetched.ok) throw new Error(`audio_fetch_${fetched.error}`);
  const directory = await mkdtemp(join(tmpdir(), "wristbrief-audio-"));
  try {
    const source = join(directory, "source");
    await writeFile(source, fetched.bytes, { mode: 0o600 });
    const probe = await promisify(execFile)("ffprobe", ["-v", "error", "-show_entries", "format=duration", "-of", "default=noprint_wrappers=1:nokey=1", source], { timeout: 15000 });
    const durationMs = Math.ceil(Number(probe.stdout.trim()) * 1000);
    if (!Number.isFinite(durationMs) || durationMs <= 0 || durationMs > maxDurationMs) throw new Error("audio_duration_unsupported");
    await new Promise<void>((resolve, reject) => {
      const process = spawn("ffmpeg", ["-nostdin", "-hide_banner", "-loglevel", "error", "-i", source,
        "-vn", "-ac", "1", "-ar", "16000", "-c:a", "libmp3lame", "-b:a", "32k",
        "-f", "segment", "-segment_time", "240", join(directory, "part-%03d.mp3")], { stdio: "ignore" });
      const timer = setTimeout(() => process.kill("SIGKILL"), 180000);
      process.on("error", (error) => { clearTimeout(timer); reject(error); });
      process.on("close", (code) => { clearTimeout(timer); code === 0 ? resolve() : reject(new Error("audio_transcode_failed")); });
    });
    const files = (await readdir(directory)).filter((name) => /^part-\d{3}\.mp3$/.test(name)).sort();
    if (!files.length || files.length > Math.ceil(maxDurationMs / AUDIO_SEGMENT_MS)) throw new Error("audio_duration_unsupported");
    const parts = [];
    for (const [index, name] of files.entries()) {
      const bytes = await readFile(join(directory, name));
      if (!bytes.length || bytes.length > AUDIO_MAX_SEGMENT_BYTES) throw new Error("audio_segment_too_large");
      parts.push({ bytes, startMs: index * AUDIO_SEGMENT_MS, endMs: Math.min(durationMs, (index + 1) * AUDIO_SEGMENT_MS) });
    }
    return parts;
  } finally { await rm(directory, { recursive: true, force: true }); }
}

/**
 * Provider calls POST to fixed, operator-configured API hosts. They do not go
 * through `safeFetch` (which is the GET-only downloader for user-supplied URLs),
 * so they carry `redirect: "error"`: the runtime must never follow a provider
 * redirect to a host the gateway did not choose, and a 3xx surfaces as a
 * provider failure instead of silently re-sending credentials and audio
 * elsewhere. The destination host itself is a compile-time constant.
 */
const PROVIDER_FETCH = { redirect: "error" } as const;

export async function recognize(config: AudioProvider, key: string, audio: Uint8Array, language: string): Promise<string> {
  let response: Response;
  if (config.adapter === "mimo") {
    if (audio.byteLength > 7_000_000) throw new Error("mimo_audio_too_large");
    response = await fetch("https://api.xiaomimimo.com/v1/chat/completions", {
      ...PROVIDER_FETCH,
      method: "POST", signal: timeoutSignal(120000),
      headers: { "api-key": key, "Content-Type": "application/json" },
      body: JSON.stringify({ model: config.model, messages: [{ role: "user", content: [{ type: "input_audio",
        input_audio: { data: `data:audio/mpeg;base64,${Buffer.from(audio).toString("base64")}` } }] }],
        asr_options: { language: ["zh", "en"].includes(language) ? language : "auto" } })
    });
  } else if (config.adapter === "deepgram") {
    const params = new URLSearchParams({ model: config.model, smart_format: "true" });
    if (["zh", "en"].includes(language)) params.set("language", language);
    response = await fetch(`https://api.deepgram.com/v1/listen?${params}`, {
      ...PROVIDER_FETCH,
      method: "POST", signal: timeoutSignal(120000),
      headers: { Authorization: `Token ${key}`, "Content-Type": "audio/mpeg" },
      body: Buffer.from(audio)
    });
  } else {
    const form = new FormData();
    form.set("file", new File([new Uint8Array(audio)], "segment.mp3", { type: "audio/mpeg" }));
    form.set("model", config.model);
    if (["zh", "en"].includes(language)) form.set("language", language);
    response = await fetch("https://api.openai.com/v1/audio/transcriptions", {
      ...PROVIDER_FETCH,
      method: "POST", signal: timeoutSignal(120000), headers: { Authorization: `Bearer ${key}` }, body: form
    });
  }
  if (!response.ok) throw new Error(`stt_upstream_${response.status}`);
  const result = await response.json() as any;
  const text = config.adapter === "mimo" ? result?.choices?.[0]?.message?.content :
    config.adapter === "deepgram" ? result?.results?.channels?.[0]?.alternatives?.[0]?.transcript : result?.text;
  if (typeof text !== "string" || !text.trim()) throw new Error("stt_empty_result");
  return text.trim();
}

export async function synthesize(config: AudioProvider, key: string, text: string): Promise<Uint8Array> {
  let response: Response;
  if (config.adapter === "mimo") {
    response = await fetch("https://api.xiaomimimo.com/v1/chat/completions", {
      ...PROVIDER_FETCH,
      method: "POST", signal: timeoutSignal(90000),
      headers: { "api-key": key, "Content-Type": "application/json" },
      body: JSON.stringify({ model: config.model, messages: [{ role: "assistant", content: text }],
        audio: { format: "mp3", voice: config.voice || "mimo_default" } })
    });
    if (!response.ok) throw new Error(`tts_upstream_${response.status}`);
    const data = (await response.json() as any)?.choices?.[0]?.message?.audio?.data;
    if (typeof data !== "string" || data.length > 12_000_000) throw new Error("tts_invalid_audio");
    return Buffer.from(data, "base64");
  }
  response = config.adapter === "deepgram" ? await fetch(`https://api.deepgram.com/v1/speak?${new URLSearchParams({ model: config.model })}`, {
    ...PROVIDER_FETCH,
    method: "POST", signal: timeoutSignal(90000),
    headers: { Authorization: `Token ${key}`, "Content-Type": "application/json" }, body: JSON.stringify({ text })
  }) : await fetch("https://api.openai.com/v1/audio/speech", {
    ...PROVIDER_FETCH,
    method: "POST", signal: timeoutSignal(90000),
    headers: { Authorization: `Bearer ${key}`, "Content-Type": "application/json" },
    body: JSON.stringify({ model: config.model, voice: config.voice || "alloy", input: text, response_format: "mp3" })
  });
  if (!response.ok) throw new Error(`tts_upstream_${response.status}`);
  if (Number(response.headers.get("Content-Length")) > 8_000_000) throw new Error("tts_audio_too_large");
  const bytes = new Uint8Array(await response.arrayBuffer());
  if (!bytes.length || bytes.length > 8_000_000) throw new Error("tts_invalid_audio");
  return bytes;
}

type ClaimedJob = { id: string; user_id: string; content_id: string; language: string; status: string; attempt_count: number; audio_url: string };

/**
 * Jobs are durable SQLite records, polled after restart; one processor per server.
 *
 * Settlement and recovery rules:
 *  - transcription is charged by the FFprobe-measured duration of the audio that
 *    was actually processed, never by the client-declared duration, and the
 *    units are rewritten only while the credit transaction is still RESERVED, so
 *    a committed charge is never restated;
 *  - a job whose measured duration exceeds TRANSCRIPT_MAX_BILLABLE_MINUTES is
 *    refused and its whole reservation is released — no partial charge;
 *  - every error path ends with the reservation RELEASED, never orphaned RESERVED;
 *  - a reclaimed job (lease expired after SIGKILL) reuses the same job row, the
 *    same credit transaction and the same object keys, so it settles exactly once
 *    and cannot double-charge or duplicate the transcript;
 *  - `stop()` hands an in-flight job back to the queue with an expired lease so
 *    the next process resumes it immediately.
 */
export class AudioJobRunner {
  private busy = false;
  private stopping = false;
  private timer: NodeJS.Timeout | undefined;
  private active: { jobId: string; cancelled: boolean } | null = null;
  constructor(private readonly runtime: AudioRuntime) {}

  queue = { send: async (message: { jobId: string; audioUrl: string; durationMs?: number }) => {
    await this.runtime.ACCOUNT_DB.prepare("INSERT OR REPLACE INTO artifact_job_inputs (job_id, audio_url, duration_ms) VALUES (?, ?, ?)")
      .bind(message.jobId, message.audioUrl, message.durationMs ?? 0).run();
    void this.tick().catch((error) => console.error("Audio worker poll failed", error instanceof Error ? error.name : "unknown"));
  } };

  start(): void {
    if (this.stopping) return;
    const poll = () => { void this.tick().catch((error) => console.error("Audio worker poll failed", error instanceof Error ? error.name : "unknown")); };
    if (!this.timer) this.timer = setInterval(poll, 2000);
    poll();
  }

  /**
   * Graceful shutdown (`server.close` -> `audio.stop()`): no new job is claimed,
   * and an in-flight job is handed back to the queue with an expired lease so the
   * next process reclaims it immediately instead of waiting out the old lease.
   */
  stop(): void {
    if (this.timer) { clearInterval(this.timer); this.timer = undefined; }
    this.stopping = true;
    if (this.active) this.active.cancelled = true;
  }

  async tick(): Promise<void> {
    if (this.busy || this.stopping) return;
    this.busy = true;
    try {
      const selected = await selectedProvider(this.runtime, "stt");
      if (!selected) return;
      const db = this.runtime.ACCOUNT_DB;
      const now = Date.now();
      const row = await db.prepare("SELECT j.id, j.user_id, j.content_id, j.language, j.status, j.attempt_count, i.audio_url FROM artifact_jobs j JOIN artifact_job_inputs i ON i.job_id = j.id WHERE j.status = 'queued' OR (j.status = 'running' AND j.lease_expires_at < ?) ORDER BY j.created_at LIMIT 1")
        .bind(now).first<ClaimedJob>();
      if (!row) return;

      const artifacts = new D1ArtifactStore(db);
      const quota = new D1QuotaLedgerStore(db);
      const service = new TranscriptService(new ContentResolver(new D1ContentStore(db)), artifacts, quota);

      // Bounded retries. A `queued` job is a fresh request, an explicit user retry
      // (TranscriptService.resetJobForRetry) or a graceful-shutdown hand-back, and
      // is always processed. Only a CRASH RECLAIM — `running` with an expired
      // lease — counts against the cap, so a job that keeps killing its processor
      // fails cleanly instead of being reclaimed forever, while a user's explicit
      // retry is never silently swallowed.
      if (row.status === "running" && row.attempt_count >= this.maxAttempts()) {
        console.error("Audio job exhausted its attempts", row.id);
        await this.settleFailure(service, row.id, "audio_attempts_exhausted");
        return;
      }

      const leaseMs = runtimeNumber(this.runtime, "TRANSCRIPT_LEASE_MS", DEFAULT_LEASE_MS);
      const lease = await db.prepare("UPDATE artifact_jobs SET status = 'running', lease_owner = ?, lease_expires_at = ?, attempt_count = attempt_count + 1, error_code = NULL, updated_at = ? WHERE id = ? AND (status = 'queued' OR (status = 'running' AND lease_expires_at < ?))")
        .bind("server", now + leaseMs, now, row.id, now).run();
      if (lease.meta.changes !== 1) return;

      const heartbeat = setInterval(() => {
        void db.prepare("UPDATE artifact_jobs SET lease_expires_at = ? WHERE id = ? AND status = 'running'")
          .bind(Date.now() + leaseMs, row.id).run().catch(() => {});
      }, Math.max(1000, Math.floor(leaseMs / 4)));
      this.active = { jobId: row.id, cancelled: false };
      try {
        await this.processClaimedJob(service, artifacts, quota, row, selected);
      } catch (error) {
        if (this.active?.cancelled) {
          // Graceful shutdown, not a failure: resume on the next process.
          await this.requeueJob(row.id);
        } else {
          const code = error instanceof Error ? error.message.replace(/[^a-z0-9_]/gi, "_").slice(0, 80) : "audio_processing_failed";
          console.error("Audio job failed", row.id, code);
          await this.settleFailure(service, row.id, code);
        }
      } finally {
        clearInterval(heartbeat);
        this.active = null;
      }
    } finally { this.busy = false; }
  }

  private maxDurationMs(): number { return runtimeNumber(this.runtime, "TRANSCRIPT_MAX_DURATION_MS", DEFAULT_MAX_DURATION_MS); }
  private maxBillableMinutes(): number { return runtimeNumber(this.runtime, "TRANSCRIPT_MAX_BILLABLE_MINUTES", DEFAULT_MAX_BILLABLE_MINUTES); }
  private maxAttempts(): number { return runtimeNumber(this.runtime, "TRANSCRIPT_MAX_ATTEMPTS", DEFAULT_MAX_ATTEMPTS); }

  private async processClaimedJob(
    service: TranscriptService,
    artifacts: D1ArtifactStore,
    quota: D1QuotaLedgerStore,
    row: ClaimedJob,
    selected: { config: AudioProvider; key: string }
  ): Promise<void> {
    const db = this.runtime.ACCOUNT_DB;

    // Crash recovery, exactly once. A previous attempt can be SIGKILLed after it
    // published the artifact but before it updated the job status; reclaiming
    // that job must settle it, not transcribe (and charge for) a second copy.
    const published = await artifacts.findPreferredArtifactForContent(row.content_id, row.language);
    if (published && published.status === "ready" && published.createdByUserId === row.user_id) {
      await artifacts.grantUserAccess(row.user_id, published.id, row.content_id, "creator", 1.0);
      const reservation = await quota.findByReference(row.user_id, "transcript_generation", row.id);
      if (reservation && reservation.status === "RESERVED") await quota.commit(reservation.id);
      await artifacts.updateJobStatus(row.id, "completed");
      return;
    }

    const parts = await transcodeAudio(row.audio_url, { maxDurationMs: this.maxDurationMs() });
    const processedMs = parts.at(-1)!.endMs;
    const billableMinutes = Math.max(1, Math.ceil(processedMs / 60000));
    // Executable ceiling: refuse (and release) rather than consume unbounded quota.
    if (billableMinutes > this.maxBillableMinutes()) throw new Error("audio_billing_cap_exceeded");

    const segments = [];
    for (const [index, part] of parts.entries()) {
      if (this.active?.cancelled) throw new Error("audio_job_cancelled");
      const text = await recognize(selected.config, selected.key, part.bytes, row.language || "auto");
      segments.push({ id: index, startMs: part.startMs, endMs: part.endMs, text });
    }
    // Do not start charging once a graceful shutdown is in progress: the job is
    // handed back to the queue and the next process finishes it.
    if (this.active?.cancelled) throw new Error("audio_job_cancelled");
    const text = segments.map((segment) => segment.text).join("\n");
    const content = await new D1ContentStore(db).findById(row.content_id);

    // Settle by the measured duration actually processed, and only while the
    // transaction is still a hold; the commit below turns it into a charge once.
    await db.prepare("UPDATE credit_transactions SET units = ROUND(? * multiplier, 2) WHERE user_id = ? AND operation_type = 'transcript_generation' AND reference_id = ? AND status = 'RESERVED'")
      .bind(billableMinutes, row.user_id, row.id).run();

    await service.completeJobWithArtifact(row.id, { schemaVersion: "1", contentCode: content?.contentCode ?? "", language: row.language,
      durationMs: processedMs, fullText: text, segments },
      createTranscriptStorage(this.runtime), { provider: selected.config.adapter, model: selected.config.model });
  }

  /** Fails a job and guarantees its reservation ends RELEASED. */
  private async settleFailure(service: TranscriptService, jobId: string, code: string): Promise<void> {
    const db = this.runtime.ACCOUNT_DB;
    const job = await db.prepare("SELECT user_id FROM artifact_jobs WHERE id = ?").bind(jobId).first<{ user_id: string }>();
    try {
      await service.failJob(jobId, code);
    } catch (error) {
      console.error("Audio job failure settlement failed", jobId, error instanceof Error ? error.name : "unknown");
    }
    // Guarantee, independent of the service: a held reservation never survives a
    // failure. This also covers a failure after the measured-duration UPDATE ran.
    if (job) {
      await db.prepare("UPDATE credit_transactions SET status = 'RELEASED' WHERE user_id = ? AND operation_type = 'transcript_generation' AND reference_id = ? AND status = 'RESERVED'")
        .bind(job.user_id, jobId).run();
    }
    // Users who joined this job must not keep a hold for work that produced
    // nothing. TranscriptService.failJob already settles them; this is the
    // unconditional guarantee if that call fails part-way.
    await db.prepare("UPDATE credit_transactions SET status = 'RELEASED' WHERE operation_type = 'transcript_shared' AND status = 'RESERVED' AND reference_id IN (SELECT id FROM transcript_job_followers WHERE job_id = ?)")
      .bind(jobId).run();
    await db.prepare("UPDATE transcript_job_followers SET status = 'failed', error_code = ?, updated_at = ? WHERE job_id = ? AND status <> 'completed'")
      .bind(code, Date.now(), jobId).run();
    await db.prepare("UPDATE artifact_jobs SET status = 'failed', error_code = ?, lease_owner = NULL, lease_expires_at = ?, updated_at = ? WHERE id = ? AND status <> 'completed'")
      .bind(code, Date.now(), Date.now(), jobId).run();
  }

  /** Graceful shutdown hand-back: resumable immediately by the next process. */
  private async requeueJob(jobId: string): Promise<void> {
    const now = Date.now();
    await this.runtime.ACCOUNT_DB.prepare("UPDATE artifact_jobs SET status = 'queued', lease_owner = NULL, lease_expires_at = ?, updated_at = ? WHERE id = ? AND status = 'running'")
      .bind(now, now, jobId).run();
  }
}

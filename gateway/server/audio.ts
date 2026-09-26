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

function timeoutSignal(ms: number): AbortSignal { return AbortSignal.timeout(ms); }

export async function transcodeAudio(audioUrl: string): Promise<Array<{ bytes: Buffer; startMs: number; endMs: number }>> {
  const fetched = await safeFetch(audioUrl, { maxBytes: 96 * 1024 * 1024, timeoutMs: 120000,
    allowedContentTypes: [/^audio\//i, /^application\/octet-stream/i] });
  if (!fetched.ok) throw new Error(`audio_fetch_${fetched.error}`);
  const directory = await mkdtemp(join(tmpdir(), "wristbrief-audio-"));
  try {
    const source = join(directory, "source");
    await writeFile(source, fetched.bytes, { mode: 0o600 });
    const probe = await promisify(execFile)("ffprobe", ["-v", "error", "-show_entries", "format=duration", "-of", "default=noprint_wrappers=1:nokey=1", source], { timeout: 15000 });
    const durationMs = Math.ceil(Number(probe.stdout.trim()) * 1000);
    if (!Number.isFinite(durationMs) || durationMs <= 0 || durationMs > 3 * 60 * 60 * 1000) throw new Error("audio_duration_unsupported");
    await new Promise<void>((resolve, reject) => {
      const process = spawn("ffmpeg", ["-nostdin", "-hide_banner", "-loglevel", "error", "-i", source,
        "-vn", "-ac", "1", "-ar", "16000", "-c:a", "libmp3lame", "-b:a", "32k",
        "-f", "segment", "-segment_time", "240", join(directory, "part-%03d.mp3")], { stdio: "ignore" });
      const timer = setTimeout(() => process.kill("SIGKILL"), 180000);
      process.on("error", (error) => { clearTimeout(timer); reject(error); });
      process.on("close", (code) => { clearTimeout(timer); code === 0 ? resolve() : reject(new Error("audio_transcode_failed")); });
    });
    const files = (await readdir(directory)).filter((name) => /^part-\d{3}\.mp3$/.test(name)).sort();
    if (!files.length || files.length > 45) throw new Error("audio_duration_unsupported");
    const parts = [];
    for (const [index, name] of files.entries()) {
      const bytes = await readFile(join(directory, name));
      if (!bytes.length || bytes.length > 7_000_000) throw new Error("audio_segment_too_large");
      parts.push({ bytes, startMs: index * 240000, endMs: Math.min(durationMs, (index + 1) * 240000) });
    }
    return parts;
  } finally { await rm(directory, { recursive: true, force: true }); }
}

export async function recognize(config: AudioProvider, key: string, audio: Uint8Array, language: string): Promise<string> {
  let response: Response;
  if (config.adapter === "mimo") {
    if (audio.byteLength > 7_000_000) throw new Error("mimo_audio_too_large");
    response = await fetch("https://api.xiaomimimo.com/v1/chat/completions", {
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
    method: "POST", signal: timeoutSignal(90000),
    headers: { Authorization: `Token ${key}`, "Content-Type": "application/json" }, body: JSON.stringify({ text })
  }) : await fetch("https://api.openai.com/v1/audio/speech", {
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

/** Jobs are durable SQLite records, polled after restart; one processor per server. */
export class AudioJobRunner {
  private busy = false;
  private timer: NodeJS.Timeout | undefined;
  constructor(private readonly runtime: AudioRuntime) {}

  queue = { send: async (message: { jobId: string; audioUrl: string; durationMs?: number }) => {
    await this.runtime.ACCOUNT_DB.prepare("INSERT OR REPLACE INTO artifact_job_inputs (job_id, audio_url, duration_ms) VALUES (?, ?, ?)")
      .bind(message.jobId, message.audioUrl, message.durationMs ?? 0).run();
    void this.tick().catch((error) => console.error("Audio worker poll failed", error instanceof Error ? error.name : "unknown"));
  } };

  start(): void {
    const poll = () => { void this.tick().catch((error) => console.error("Audio worker poll failed", error instanceof Error ? error.name : "unknown")); };
    this.timer = setInterval(poll, 2000);
    poll();
  }
  stop(): void { if (this.timer) clearInterval(this.timer); }

  async tick(): Promise<void> {
    if (this.busy) return;
    this.busy = true;
    try {
      const selected = await selectedProvider(this.runtime, "stt");
      if (!selected) return;
      const db = this.runtime.ACCOUNT_DB;
      const now = Date.now();
      const row = await db.prepare("SELECT j.id, i.audio_url, i.duration_ms FROM artifact_jobs j JOIN artifact_job_inputs i ON i.job_id = j.id WHERE j.status = 'queued' OR (j.status = 'running' AND j.lease_expires_at < ?) ORDER BY j.created_at LIMIT 1")
        .bind(now).first<{ id: string; audio_url: string; duration_ms: number }>();
      if (!row) return;
      const lease = await db.prepare("UPDATE artifact_jobs SET status = 'running', lease_owner = ?, lease_expires_at = ?, attempt_count = attempt_count + 1, updated_at = ? WHERE id = ? AND (status = 'queued' OR (status = 'running' AND lease_expires_at < ?))")
        .bind("server", now + 240000, now, row.id, now).run();
      if (lease.meta.changes !== 1) return;
      const heartbeat = setInterval(() => {
        void db.prepare("UPDATE artifact_jobs SET lease_expires_at = ? WHERE id = ? AND status = 'running'")
          .bind(Date.now() + 240000, row.id).run().catch(() => {});
      }, 60000);
      const artifacts = new D1ArtifactStore(db);
      const service = new TranscriptService(new ContentResolver(new D1ContentStore(db)), artifacts, new D1QuotaLedgerStore(db));
      try {
        const parts = await transcodeAudio(row.audio_url);
        const measuredMinutes = Math.max(1, Math.ceil(parts.at(-1)!.endMs / 60000));
        await db.prepare("UPDATE credit_transactions SET units = ROUND(? * multiplier, 2) WHERE reference_id = ? AND operation_type = 'transcript_generation' AND status = 'RESERVED'")
          .bind(measuredMinutes, row.id).run();
        const segments = [];
        for (const [index, part] of parts.entries()) {
          const text = await recognize(selected.config, selected.key, part.bytes, (await artifacts.getJobById(row.id))?.language ?? "auto");
          segments.push({ id: index, startMs: part.startMs, endMs: part.endMs, text });
        }
        const text = segments.map((segment) => segment.text).join("\n");
        const content = await new D1ContentStore(db).findById((await artifacts.getJobById(row.id))!.contentId);
        const job = (await artifacts.getJobById(row.id))!;
        await service.completeJobWithArtifact(row.id, { schemaVersion: "1", contentCode: content?.contentCode ?? "", language: job.language,
          durationMs: parts.at(-1)!.endMs, fullText: text, segments },
          createTranscriptStorage(this.runtime), { provider: selected.config.adapter, model: selected.config.model });
      } catch (error) {
        const code = error instanceof Error ? error.message.replace(/[^a-z0-9_]/gi, "_").slice(0, 80) : "audio_processing_failed";
        console.error("Audio job failed", row.id, code);
        await service.failJob(row.id, code);
      } finally { clearInterval(heartbeat); }
    } finally { this.busy = false; }
  }
}

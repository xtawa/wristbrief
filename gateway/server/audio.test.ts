import { afterEach, expect, it, vi } from "vitest";
import { spawnSync } from "node:child_process";
import { recognize, synthesize, transcodeAudio, type AudioProvider } from "./audio";

const mimoStt: AudioProvider = { id: "mimo-stt", capability: "stt", adapter: "mimo", model: "mimo-v2.5-asr", voice: null, secret_ref: "AI_PROVIDER_SECRET_1", enabled: 1, priority: 1 };
const deepgramStt: AudioProvider = { ...mimoStt, id: "deepgram-stt", adapter: "deepgram", model: "nova-3" };
afterEach(() => vi.unstubAllGlobals());

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

it.skipIf(spawnSync("ffmpeg", ["-version"]).status !== 0)("converts remote audio into bounded MP3 segments", async () => {
  const generated = spawnSync("ffmpeg", ["-hide_banner", "-loglevel", "error", "-f", "lavfi", "-i", "sine=frequency=440:duration=1", "-f", "wav", "pipe:1"], { maxBuffer: 2_000_000 });
  expect(generated.status).toBe(0);
  vi.stubGlobal("fetch", vi.fn(async () => new Response(generated.stdout, { headers: { "Content-Type": "audio/wav" } })));
  const parts = await transcodeAudio("https://audio.example.org/episode.wav");
  expect(parts).toHaveLength(1);
  expect(parts[0].bytes.subarray(0, 3).toString()).toBe("ID3");
});

it.skipIf(spawnSync("ffmpeg", ["-version"]).status !== 0)("completes a durable queued transcript through FFmpeg and Deepgram", async () => {
  const { mkdtemp, rm } = await import("node:fs/promises");
  const { tmpdir } = await import("node:os");
  const { join } = await import("node:path");
  const { openServerDatabase } = await import("./database");
  const { localObjectStore } = await import("./storage");
  const { AudioJobRunner } = await import("./audio");
  const directory = await mkdtemp(join(tmpdir(), "wristbrief-audio-job-"));
  const { sqlite, db } = openServerDatabase(join(directory, "database.sqlite"));
  try {
    const now = Date.now();
    sqlite.prepare("INSERT INTO podcast_contents (id, content_code, media_type, share_policy, status, created_at, updated_at) VALUES ('podcast-1', 'code-1', 'podcast', 'PRIVATE_ACCOUNT', 'active', ?, ?)").run(now, now);
    sqlite.prepare("INSERT INTO artifact_jobs (id, dedupe_key, user_id, content_id, artifact_type, language, requested_version, status, attempt_count, created_at, updated_at) VALUES ('job-1', 'dedupe-1', 'user-1', 'podcast-1', 'transcript', 'en', 1, 'queued', 0, ?, ?)").run(now, now);
    sqlite.prepare("UPDATE audio_provider_configs SET enabled = 1 WHERE id = 'deepgram-stt'").run();
    const generated = spawnSync("ffmpeg", ["-hide_banner", "-loglevel", "error", "-f", "lavfi", "-i", "sine=frequency=440:duration=1", "-f", "wav", "pipe:1"], { maxBuffer: 2_000_000 });
    vi.stubGlobal("fetch", vi.fn(async (url: string) => url.includes("deepgram.com")
      ? Response.json({ results: { channels: [{ alternatives: [{ transcript: "Episode transcript" }] }] } })
      : new Response(generated.stdout, { headers: { "Content-Type": "audio/wav" } })));
    const runtime = { ACCOUNT_DB: db, TRANSCRIPTS_BUCKET: localObjectStore(join(directory, "objects")), AI_PROVIDER_SECRET_2: "test-only-key" };
    const runner = new AudioJobRunner(runtime);
    await runner.queue.send({ jobId: "job-1", audioUrl: "https://media.example.org/episode.wav", durationMs: 1000 });
    for (let attempt = 0; attempt < 70; attempt++) {
      const row = sqlite.prepare("SELECT status, error_code FROM artifact_jobs WHERE id = 'job-1'").get() as {status:string;error_code:string|null};
      if (row.status === "completed" || row.status === "failed") break;
      await new Promise((resolve) => setTimeout(resolve, 50));
    }
    expect((sqlite.prepare("SELECT status FROM artifact_jobs WHERE id = 'job-1'").get() as {status:string}).status).toBe("completed");
    const record = sqlite.prepare("SELECT provider, model, object_key_json FROM transcript_artifacts WHERE content_id = 'podcast-1'").get() as {provider:string;model:string;object_key_json:string};
    expect(record).toMatchObject({ provider: "deepgram", model: "nova-3" });
    const obj = await runtime.TRANSCRIPTS_BUCKET.get(record.object_key_json);
    expect(JSON.parse(await obj!.text()).fullText).toBe("Episode transcript");
  } finally { sqlite.close(); await rm(directory, { recursive: true, force: true }); }
});

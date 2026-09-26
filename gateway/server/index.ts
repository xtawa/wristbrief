import { createServer, type IncomingMessage, type ServerResponse } from "node:http";
import { Readable } from "node:stream";
import { resolve } from "node:path";
import worker from "../src/index";
import { openServerDatabase } from "./database";
import { localObjectStore } from "./storage";
import { ensureServerAdmin } from "./bootstrap";
import { ServerSettings } from "./settings";
import { AudioJobRunner, selectedProvider, synthesize } from "./audio";
import { configureFetchConcurrency, fetchConcurrencyState } from "../src/net/safeRemoteFetcher";
import { authenticateRequestUser } from "../src/requestAuth";
import { createMembershipService } from "../src/membership";

export async function startServer(config: NodeJS.ProcessEnv = process.env) {
  const dataDir = resolve(config.WRISTBRIEF_DATA_DIR || "./data");
  const publicOrigin = (config.WRISTBRIEF_PUBLIC_ORIGIN || "http://localhost:8787").replace(/\/$/, "");
  const parsed = new URL(publicOrigin);
  if (!(["http:", "https:"].includes(parsed.protocol)) || (config.NODE_ENV === "production" && parsed.protocol !== "https:")) {
    throw new Error("WRISTBRIEF_PUBLIC_ORIGIN must be an HTTPS origin in production");
  }
  const { sqlite, db } = openServerDatabase(resolve(dataDir, "wristbrief.sqlite"));
  const runtime: Record<string, unknown> = {};
  const settings = new ServerSettings(db, runtime, config.WRISTBRIEF_MASTER_KEY || "");
  await ensureServerAdmin(db, config.WRISTBRIEF_ADMIN_INITIAL_PASSWORD);
  const env = {
    ACCOUNT_DB: db,
    TRANSCRIPTS_BUCKET: localObjectStore(resolve(dataDir, "objects")),
    SINGLE_ADMIN_MODE: true,
    GATEWAY_PUBLIC_BASE_URL: publicOrigin,
    AI_API_KEY: config.AI_API_KEY || "",
    AI_MODEL: config.AI_MODEL || "",
    AI_BASE_URL: config.AI_BASE_URL || "",
    AI_ALLOWED_HOSTS: config.AI_ALLOWED_HOSTS || "",
    FREE_AI_MONTHLY_LIMIT: config.FREE_AI_MONTHLY_LIMIT || "10",
    PRO_AI_MONTHLY_LIMIT: config.PRO_AI_MONTHLY_LIMIT || "100",
    GOOGLE_OAUTH_CLIENT_ID: config.GOOGLE_OAUTH_CLIENT_ID,
    GOOGLE_PLAY_SERVICE_ACCOUNT_EMAIL: config.GOOGLE_PLAY_SERVICE_ACCOUNT_EMAIL,
    GOOGLE_PLAY_SERVICE_ACCOUNT_PRIVATE_KEY: config.GOOGLE_PLAY_SERVICE_ACCOUNT_PRIVATE_KEY,
    PLAY_PACKAGE_NAME: config.PLAY_PACKAGE_NAME,
    PLAY_SUBSCRIPTION_PRODUCT_IDS: config.PLAY_SUBSCRIPTION_PRODUCT_IDS,
    PUBSUB_PUSH_SERVICE_ACCOUNT_EMAIL: config.PUBSUB_PUSH_SERVICE_ACCOUNT_EMAIL,
    PUBSUB_PUSH_AUDIENCE: config.PUBSUB_PUSH_AUDIENCE,
    RESEND_API_KEY: config.RESEND_API_KEY,
    RESEND_FROM_EMAIL: config.RESEND_FROM_EMAIL,
    // Audio job settlement and recovery limits (see docs/AUDIO_PIPELINE.md).
    TRANSCRIPT_MAX_DURATION_MS: config.TRANSCRIPT_MAX_DURATION_MS,
    TRANSCRIPT_MAX_BILLABLE_MINUTES: config.TRANSCRIPT_MAX_BILLABLE_MINUTES,
    TRANSCRIPT_MAX_ATTEMPTS: config.TRANSCRIPT_MAX_ATTEMPTS,
    TRANSCRIPT_LEASE_MS: config.TRANSCRIPT_LEASE_MS,
    ADMIN_RECOVERY_SECRET: undefined,
    ADMIN_SETTINGS_SERVICE: settings,
    EMAIL_SENDER: settings,
    ...Object.fromEntries(Array.from({ length: 10 }, (_, i) => {
      const key = `AI_PROVIDER_SECRET_${i + 1}`;
      return [key, config[key]];
    }))
  };
  Object.assign(runtime, env);
  await settings.load();
  // Bound simultaneous outbound fetches (the SSRF-safe transport applies it to
  // every hop, including redirects).
  const fetchConcurrency = Number(config.WRISTBRIEF_FETCH_CONCURRENCY);
  if (Number.isFinite(fetchConcurrency) && fetchConcurrency >= 1) configureFetchConcurrency(fetchConcurrency);
  const audio = new AudioJobRunner(runtime as unknown as ConstructorParameters<typeof AudioJobRunner>[0]);
  runtime.TRANSCRIPT_QUEUE = audio.queue;

  const server = createServer(async (req, res) => {
    try {
      const headers = new Headers();
      for (const [key, value] of Object.entries(req.headers)) {
        if (value != null) headers.set(key, Array.isArray(value) ? value.join(", ") : value);
      }
      // The public origin is fixed by deployment config, never derived from Host or X-Forwarded-Host.
      const target = new URL(req.url || "/", publicOrigin);
      if (target.origin !== publicOrigin) throw new Error("Invalid request target");
      const body = req.method === "GET" || req.method === "HEAD" ? undefined : Readable.toWeb(req) as unknown as ReadableStream;
      const init = { method: req.method, headers, body, duplex: body ? "half" : undefined } as unknown as RequestInit;
      const request = new Request(target, init);
      if (target.pathname === "/v1/transcripts/request" && req.method === "POST" &&
          !(await selectedProvider(runtime as Parameters<typeof selectedProvider>[0], "stt"))) {
        send(res, Response.json({ error: "stt_provider_unavailable" }, { status: 503 }));
        return;
      }
      const response = target.pathname === "/v1/audio/speech" ?
        await speechRequest(request, runtime) :
        await worker.fetch(request, runtime as unknown as Parameters<typeof worker.fetch>[1]);
      send(res, response);
    } catch (error) {
      console.error("Gateway request failed", error instanceof Error ? error.name : "unknown");
      res.writeHead(500, { "Content-Type": "application/json", "Cache-Control": "no-store" });
      res.end('{"error":"server_error"}');
    }
  });
  const port = Number(config.PORT || 8787);
  const host = config.HOST || "127.0.0.1";
  await new Promise<void>((resolveReady) => server.listen(port, host, resolveReady));
  audio.start();
  server.on("close", () => audio.stop());
  console.log(`WristBrief server listening on ${host}:${port} (outbound fetch limit ${fetchConcurrencyState().limit})`);
  return { server, sqlite, db, env, audio };
}

async function speechRequest(request: Request, runtime: Record<string, unknown>): Promise<Response> {
  if (request.method !== "POST") return Response.json({ error: "method_not_allowed" }, { status: 405 });
  const user = await authenticateRequestUser(request, runtime as Parameters<typeof authenticateRequestUser>[1]);
  if (!user) return Response.json({ error: "unauthorized" }, { status: 401 });
  if (Number(request.headers.get("content-length")) > 8192) return Response.json({ error: "request_too_large" }, { status: 413 });
  const reader = request.body?.getReader();
  let total = 0;
  const chunks: Uint8Array[] = [];
  if (reader) while (true) {
    const { value, done } = await reader.read();
    if (done) break;
    total += value.byteLength;
    if (total > 8192) { await reader.cancel(); return Response.json({ error: "request_too_large" }, { status: 413 }); }
    chunks.push(value);
  }
  let body: { text?: unknown };
  try { body = JSON.parse(Buffer.concat(chunks).toString("utf8")); }
  catch { return Response.json({ error: "invalid_json" }, { status: 400 }); }
  const text = typeof body?.text === "string" ? body.text.trim() : "";
  if (!text || text.length > 2000) return Response.json({ error: "invalid_text" }, { status: 400 });
  const selected = await selectedProvider(runtime as Parameters<typeof selectedProvider>[0], "tts");
  if (!selected) return Response.json({ error: "tts_provider_unavailable" }, { status: 503 });
  const membership = createMembershipService(runtime as Parameters<typeof createMembershipService>[0]);
  const reserved = await membership.reserveAiQuota(user.id);
  if (!reserved.allowed) return Response.json({ error: "managed_ai_quota_unavailable" }, { status: 429 });
  try {
    const data = await synthesize(selected.config, selected.key, text);
    return new Response(Buffer.from(data), { headers: { "Content-Type": "audio/mpeg", "Cache-Control": "no-store" } });
  } catch {
    await membership.releaseAiQuota(user.id);
    return Response.json({ error: "tts_generation_failed" }, { status: 502 });
  }
}

function send(res: ServerResponse, response: Response): void {
  res.statusCode = response.status;
  response.headers.forEach((value, key) => { if (key !== "set-cookie") res.setHeader(key, value); });
  const cookies = response.headers.getSetCookie();
  if (cookies.length) res.setHeader("Set-Cookie", cookies);
  if (!response.body) { res.end(); return; }
  Readable.fromWeb(response.body as unknown as Parameters<typeof Readable.fromWeb>[0]).pipe(res);
}

if (process.argv[1] && import.meta.url === new URL(`file://${resolve(process.argv[1])}`).href) {
  startServer().catch((error) => { console.error(error); process.exitCode = 1; });
}

import { afterEach, describe, expect, it, vi } from "vitest";
import worker from "./index";
import { BRIEF_PROMPT_VERSION, BRIEF_SCHEMA_VERSION, type StructuredBrief } from "./structuredBrief";
import { InMemoryMembershipStore } from "./membership";
import { InMemorySummaryCache, buildSummaryCacheKey } from "./summaryCache";
import { setSafeFetchResolverOverride, setSafeFetchTransportOverride, type SafeHttpTransport } from "./net/safeRemoteFetcher";

/**
 * The AI provider POST runs through the SSRF-safe pinned transport (resolve,
 * screen every answer, pin the connect), not global fetch. This double gives the
 * tests the same `fetch(url, init)` view so their assertions stay unchanged.
 */
type TransportCall = { url: string; init: RequestInit };

function usePinnedTransport(handler: (url: string, init?: RequestInit) => Response | Promise<Response>): TransportCall[] {
  const calls: TransportCall[] = [];
  setSafeFetchResolverOverride(async () => ["93.184.216.34"]);
  const transport: SafeHttpTransport = {
    async request(request) {
      const init: RequestInit = {
        method: request.method ?? "GET",
        headers: request.headers,
        body: typeof request.body === "string" ? request.body : undefined,
        redirect: "error",
        signal: request.signal
      };
      calls.push({ url: request.url.toString(), init });
      const response = await handler(request.url.toString(), init);
      const bytes = new Uint8Array(await response.arrayBuffer());
      return {
        status: response.status,
        headers: { get: (name: string) => response.headers.get(name) },
        body: { async *[Symbol.asyncIterator]() { if (bytes.byteLength) yield bytes; } },
        destroy: () => {}
      };
    }
  };
  setSafeFetchTransportOverride(transport);
  return calls;
}

const env = {
  AI_API_KEY: "provider-secret",
  GATEWAY_TOKEN: "gateway-secret",
  AI_BASE_URL: "https://provider.example/v1",
  AI_MODEL: "test-model"
};

const structured: StructuredBrief = {
  tiny: "Tiny summary.",
  brief: "Three concise points.",
  long: "A longer phone-friendly explanation that preserves the useful context omitted from the watch brief.",
  bullets: ["One", "Two"],
  topics: ["Tech"],
  sourceLanguage: "en",
  outputLanguage: "en",
  schemaVersion: BRIEF_SCHEMA_VERSION,
  promptVersion: BRIEF_PROMPT_VERSION
};

function summaryRequest(content: string, token = env.GATEWAY_TOKEN): Request {
  return new Request("https://gateway.example/v1/summary", {
    method: "POST",
    headers: { "Content-Type": "application/json", "Authorization": `Bearer ${token}` },
    body: JSON.stringify({ title: "Test title", content })
  });
}

function openAiBody(value: unknown): Response {
  return new Response(JSON.stringify({
    choices: [{ message: { content: typeof value === "string" ? value : JSON.stringify(value) } }]
  }), { status: 200, headers: { "Content-Type": "application/json" } });
}

afterEach(() => {
  vi.unstubAllGlobals();
  setSafeFetchTransportOverride(null);
  setSafeFetchResolverOverride(null);
});

describe("WristBrief gateway", () => {
  it("returns health with a request id and without provider access", async () => {
    const response = await worker.fetch(new Request("https://gateway.example/health"), env);
    expect(response.status).toBe(200);
    expect(response.headers.get("X-Request-ID")).toMatch(/^[0-9a-f-]{36}$/i);
    await expect(response.json()).resolves.toEqual({ ok: true });
  });

  it("rejects unauthorized summary requests without leaking the presented token", async () => {
    const response = await worker.fetch(summaryRequest("hello", "wrong-token"), env);
    expect(response.status).toBe(401);
    expect(await response.text()).toBe(JSON.stringify({ error: "unauthorized" }));
    expect(response.headers.get("X-Request-ID")).toBeTruthy();
  });

  it("rejects invalid JSON", async () => {
    const request = new Request("https://gateway.example/v1/summary", {
      method: "POST",
      headers: { "Content-Type": "application/json", "Authorization": `Bearer ${env.GATEWAY_TOKEN}` },
      body: "{"
    });
    const response = await worker.fetch(request, env);
    expect(response.status).toBe(400);
    await expect(response.json()).resolves.toEqual({ error: "invalid_json" });
  });

  it("enforces the raw request byte limit before provider work", async () => {
    const calls = usePinnedTransport(() => openAiBody(structured));
    const request = new Request("https://gateway.example/v1/summary", {
      method: "POST",
      headers: { "Content-Type": "application/json", "Authorization": `Bearer ${env.GATEWAY_TOKEN}` },
      body: JSON.stringify({ content: "x".repeat(70_000) })
    });
    const response = await worker.fetch(request, env);
    expect(response.status).toBe(413);
    await expect(response.json()).resolves.toEqual({ error: "request_too_large" });
    expect(calls).toHaveLength(0);
  });

  it("enforces the summary content character limit", async () => {
    const response = await worker.fetch(summaryRequest("a".repeat(50_001)), env);
    expect(response.status).toBe(413);
    await expect(response.json()).resolves.toEqual({ error: "content_too_large" });
  });

  it("rejects non-HTTPS or non-allowlisted provider configuration", async () => {
    const httpResponse = await worker.fetch(summaryRequest("hello"), {
      ...env,
      AI_BASE_URL: "http://provider.example/v1"
    });
    expect(httpResponse.status).toBe(500);
    await expect(httpResponse.json()).resolves.toEqual({ error: "invalid_provider_url" });

    const hostResponse = await worker.fetch(summaryRequest("hello"), {
      ...env,
      AI_ALLOWED_HOSTS: "trusted.example"
    });
    expect(hostResponse.status).toBe(500);
    await expect(hostResponse.json()).resolves.toEqual({ error: "invalid_provider_url" });
  });

  it("returns compatibility summary plus validated structured brief including the phone summary", async () => {
    const calls = usePinnedTransport(async (url, init) => {
      expect(url).toBe("https://provider.example/v1/chat/completions");
      expect(init?.headers).toMatchObject({
        "Content-Type": "application/json",
        "Authorization": "Bearer provider-secret"
      });
      return openAiBody(structured);
    });

    const response = await worker.fetch(summaryRequest("Long feed content"), env);
    expect(response.status).toBe(200);
    await expect(response.json()).resolves.toEqual({
      summary: structured.brief,
      model: "test-model",
      structured
    });
    expect(calls).toHaveLength(1);
  });

  it("falls back only after a retryable provider failure", async () => {
    const calls = usePinnedTransport(async (url) => {
      if (url.startsWith("https://provider.example/")) {
        return new Response("private primary body", { status: 503 });
      }
      expect(url).toBe("https://openrouter.ai/api/v1/chat/completions");
      return openAiBody(structured);
    });

    const response = await worker.fetch(summaryRequest("hello"), {
      ...env,
      AI_PROVIDER_MAX_RETRIES: "0",
      AI_FALLBACK_PROVIDER: "openrouter",
      OPENROUTER_API_KEY: "openrouter-secret",
      OPENROUTER_MODEL: "openrouter/fallback"
    });
    expect(response.status).toBe(200);
    await expect(response.json()).resolves.toMatchObject({
      summary: structured.brief,
      model: "openrouter/fallback",
      structured: { long: structured.long }
    });
    expect(calls).toHaveLength(2);
  });

  it.each([400, 401, 403])("never hides upstream auth/client status %s with fallback", async (status) => {
    const calls = usePinnedTransport(() => new Response("secret upstream body", { status }));

    const response = await worker.fetch(summaryRequest("hello"), {
      ...env,
      AI_FALLBACK_PROVIDER: "openrouter",
      OPENROUTER_API_KEY: "openrouter-secret",
      OPENROUTER_MODEL: "openrouter/fallback"
    });
    expect(response.status).toBe(502);
    const text = await response.text();
    expect(JSON.parse(text)).toEqual({ error: "provider_error", status });
    expect(text).not.toContain("secret upstream body");
    expect(text).not.toContain("provider-secret");
    expect(calls).toHaveLength(1);
  });

  it("maps exhausted timeout to a safe 504 without leaking fetch errors", async () => {
    const calls = usePinnedTransport((_url, init) =>
      new Promise<Response>((_resolve, reject) => {
        init?.signal?.addEventListener("abort", () => reject(new Error("sensitive network detail")), { once: true });
      })
    );

    const response = await worker.fetch(summaryRequest("hello"), {
      ...env,
      AI_PROVIDER_TIMEOUT_MS: "1",
      AI_PROVIDER_MAX_RETRIES: "0"
    });
    expect(response.status).toBe(504);
    const text = await response.text();
    expect(JSON.parse(text)).toEqual({ error: "provider_timeout" });
    expect(text).not.toContain("sensitive network detail");
    expect(calls).toHaveLength(1);
  });

  it("maps exhausted 429/5xx failures to a safe gateway error", async () => {
    usePinnedTransport(() => new Response("secret upstream body", { status: 429 }));
    const response = await worker.fetch(summaryRequest("hello"), {
      ...env,
      AI_PROVIDER_MAX_RETRIES: "0"
    });
    expect(response.status).toBe(502);
    const text = await response.text();
    expect(JSON.parse(text)).toEqual({ error: "provider_error", status: 429 });
    expect(text).not.toContain("secret upstream body");
  });

  it("rejects malformed upstream JSON without retry or leaked body", async () => {
    const calls = usePinnedTransport(() => new Response("{secret", { status: 200 }));
    const response = await worker.fetch(summaryRequest("hello"), env);
    expect(response.status).toBe(502);
    const text = await response.text();
    expect(JSON.parse(text)).toEqual({ error: "invalid_provider_response" });
    expect(text).not.toContain("secret");
    expect(calls).toHaveLength(1);
  });

  it("rejects malformed model output after one repair attempt", async () => {
    const calls = usePinnedTransport(() => openAiBody("bad"));
    const response = await worker.fetch(summaryRequest("hello"), env);
    expect(response.status).toBe(502);
    await expect(response.json()).resolves.toEqual({ error: "invalid_provider_response" });
    expect(calls).toHaveLength(2);
  });

  it("serves cache hits without reserving or deducting user quota", async () => {
    const store = new InMemoryMembershipStore([
      { userId: "legacy-user", plan: "PRO", managedAiLimit: 10, managedAiUsed: 2 }
    ]);
    const cache = new InMemorySummaryCache();
    const cacheKey = await buildSummaryCacheKey({
      provider: "openai-compatible",
      model: "test-model",
      title: "Test title",
      content: "Cached content",
      language: "auto"
    });
    const cachedBrief = { summary: structured.brief, model: "test-model", structured };
    await cache.put(cacheKey, cachedBrief, 60);

    const upstreamCalls = usePinnedTransport(() => openAiBody(structured));

    const response = await worker.fetch(summaryRequest("Cached content"), {
      ...env,
      MEMBERSHIP_STORE: store,
      SUMMARY_CACHE: cache
    });

    expect(response.status).toBe(200);
    await expect(response.json()).resolves.toEqual(cachedBrief);
    expect(upstreamCalls).toHaveLength(0);
    // Quota remains untouched
    expect((await store.getManagedAiQuota("legacy-user")).used).toBe(2);
  });

  it("refunds reserved quota when upstream provider fails", async () => {
    const store = new InMemoryMembershipStore([
      { userId: "legacy-user", plan: "PRO", managedAiLimit: 10, managedAiUsed: 3 }
    ]);
    usePinnedTransport(() => new Response("error", { status: 500 }));

    const response = await worker.fetch(summaryRequest("hello"), {
      ...env,
      AI_PROVIDER_MAX_RETRIES: "0",
      MEMBERSHIP_STORE: store
    });

    expect(response.status).toBe(502);
    // Quota was reserved (+1 to 4) then refunded back to 3
    expect((await store.getManagedAiQuota("legacy-user")).used).toBe(3);
  });

  it("prevents stampede on concurrent identical requests and charges quota only once", async () => {
    const store = new InMemoryMembershipStore([
      { userId: "legacy-user", plan: "PRO", managedAiLimit: 10, managedAiUsed: 0 }
    ]);
    const cache = new InMemorySummaryCache();

    let calls = 0;
    let finishProvider: () => void;
    const gate = new Promise<void>((resolve) => { finishProvider = resolve; });

    const upstreamFetch = vi.fn(async () => {
      calls += 1;
      await gate;
      return openAiBody(structured);
    });
    usePinnedTransport(upstreamFetch);

    const config = {
      ...env,
      MEMBERSHIP_STORE: store,
      SUMMARY_CACHE: cache
    };

    const req1 = worker.fetch(summaryRequest("Concurrent article"), config);
    const req2 = worker.fetch(summaryRequest("Concurrent article"), config);

    finishProvider!();

    const [res1, res2] = await Promise.all([req1, req2]);
    expect(res1.status).toBe(200);
    expect(res2.status).toBe(200);
    expect(calls).toBe(1);

    // Only 1 quota used total because second request was coalesced and refunded
    expect((await store.getManagedAiQuota("legacy-user")).used).toBe(1);
  });
});

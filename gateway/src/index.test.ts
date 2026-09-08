import { afterEach, describe, expect, it, vi } from "vitest";
import worker from "./index";
import { BRIEF_PROMPT_VERSION, BRIEF_SCHEMA_VERSION } from "./structuredBrief";

const env = {
  AI_API_KEY: "provider-secret",
  GATEWAY_TOKEN: "gateway-secret",
  AI_BASE_URL: "https://provider.example/v1",
  AI_MODEL: "test-model"
};

const structured = {
  tiny: "Tiny summary.",
  brief: "Three concise points.",
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

afterEach(() => vi.unstubAllGlobals());

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
    const upstreamFetch = vi.fn();
    vi.stubGlobal("fetch", upstreamFetch);
    const request = new Request("https://gateway.example/v1/summary", {
      method: "POST",
      headers: { "Content-Type": "application/json", "Authorization": `Bearer ${env.GATEWAY_TOKEN}` },
      body: JSON.stringify({ content: "x".repeat(70_000) })
    });
    const response = await worker.fetch(request, env);
    expect(response.status).toBe(413);
    await expect(response.json()).resolves.toEqual({ error: "request_too_large" });
    expect(upstreamFetch).not.toHaveBeenCalled();
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

  it("returns compatibility summary plus validated structured brief", async () => {
    const upstreamFetch = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      expect(String(input)).toBe("https://provider.example/v1/chat/completions");
      expect(init?.headers).toMatchObject({
        "Content-Type": "application/json",
        "Authorization": "Bearer provider-secret"
      });
      return openAiBody(structured);
    });
    vi.stubGlobal("fetch", upstreamFetch);

    const response = await worker.fetch(summaryRequest("Long feed content"), env);
    expect(response.status).toBe(200);
    await expect(response.json()).resolves.toEqual({
      summary: structured.brief,
      model: "test-model",
      structured
    });
    expect(upstreamFetch).toHaveBeenCalledTimes(1);
  });

  it("falls back only after a retryable provider failure", async () => {
    const upstreamFetch = vi.fn(async (input: RequestInfo | URL) => {
      if (String(input).startsWith("https://provider.example/")) {
        return new Response("private primary body", { status: 503 });
      }
      expect(String(input)).toBe("https://openrouter.ai/api/v1/chat/completions");
      return openAiBody(structured);
    });
    vi.stubGlobal("fetch", upstreamFetch);

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
      model: "openrouter/fallback"
    });
    expect(upstreamFetch).toHaveBeenCalledTimes(2);
  });

  it.each([400, 401, 403])("never hides upstream auth/client status %s with fallback", async (status) => {
    const upstreamFetch = vi.fn(async () => new Response("secret upstream body", { status }));
    vi.stubGlobal("fetch", upstreamFetch);

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
    expect(upstreamFetch).toHaveBeenCalledTimes(1);
  });

  it("maps exhausted timeout to a safe 504 without leaking fetch errors", async () => {
    const upstreamFetch = vi.fn((_input: RequestInfo | URL, init?: RequestInit) =>
      new Promise<Response>((_resolve, reject) => {
        init?.signal?.addEventListener("abort", () => reject(new Error("sensitive network detail")), { once: true });
      })
    );
    vi.stubGlobal("fetch", upstreamFetch);

    const response = await worker.fetch(summaryRequest("hello"), {
      ...env,
      AI_PROVIDER_TIMEOUT_MS: "1",
      AI_PROVIDER_MAX_RETRIES: "0"
    });
    expect(response.status).toBe(504);
    const text = await response.text();
    expect(JSON.parse(text)).toEqual({ error: "provider_timeout" });
    expect(text).not.toContain("sensitive network detail");
    expect(upstreamFetch).toHaveBeenCalledTimes(1);
  });

  it("maps exhausted 429/5xx failures to a safe gateway error", async () => {
    vi.stubGlobal("fetch", vi.fn(async () => new Response("secret upstream body", { status: 429 })));
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
    const upstreamFetch = vi.fn(async () => new Response("{secret", { status: 200 }));
    vi.stubGlobal("fetch", upstreamFetch);
    const response = await worker.fetch(summaryRequest("hello"), env);
    expect(response.status).toBe(502);
    const text = await response.text();
    expect(JSON.parse(text)).toEqual({ error: "invalid_provider_response" });
    expect(text).not.toContain("secret");
    expect(upstreamFetch).toHaveBeenCalledTimes(1);
  });

  it("rejects malformed model output after one repair attempt", async () => {
    const upstreamFetch = vi.fn(async () => openAiBody("bad"));
    vi.stubGlobal("fetch", upstreamFetch);
    const response = await worker.fetch(summaryRequest("hello"), env);
    expect(response.status).toBe(502);
    await expect(response.json()).resolves.toEqual({ error: "invalid_provider_response" });
    expect(upstreamFetch).toHaveBeenCalledTimes(2);
  });
});

import { afterEach, describe, expect, it, vi } from "vitest";
import worker from "./index";
import { InMemoryMembershipStore } from "./membership";
import { BRIEF_PROMPT_VERSION, BRIEF_SCHEMA_VERSION } from "./structuredBrief";

const baseEnv = {
  AI_API_KEY: "managed-provider-secret",
  GATEWAY_TOKEN: "gateway-secret",
  GATEWAY_USER_ID: "byok-user",
  AI_BASE_URL: "https://provider.example/v1",
  AI_MODEL: "managed-model"
};

const structured = {
  tiny: "Tiny summary.",
  brief: "BYOK brief.",
  long: "Longer BYOK summary.",
  bullets: ["One", "Two"],
  topics: ["Tech"],
  sourceLanguage: "en",
  outputLanguage: "en",
  schemaVersion: BRIEF_SCHEMA_VERSION,
  promptVersion: BRIEF_PROMPT_VERSION
};

function byokRequest(
  provider: string,
  model: string,
  apiKey?: string
): Request {
  const headers: Record<string, string> = {
    "Content-Type": "application/json",
    "Authorization": `Bearer ${baseEnv.GATEWAY_TOKEN}`
  };
  if (apiKey !== undefined) headers["X-WristBrief-BYOK-Key"] = apiKey;
  return new Request("https://gateway.example/v1/byok/summary", {
    method: "POST",
    headers,
    body: JSON.stringify({ provider, model, title: "Test title", content: "Source content" })
  });
}

function openAiBody(): Response {
  return new Response(JSON.stringify({
    choices: [{ message: { content: JSON.stringify(structured) } }]
  }), { status: 200, headers: { "Content-Type": "application/json" } });
}

afterEach(() => vi.unstubAllGlobals());

describe("BYOK summary route", () => {
  it("uses the caller OpenRouter key on the fixed provider endpoint without consuming managed quota", async () => {
    const store = new InMemoryMembershipStore([{
      userId: "byok-user",
      plan: "FREE",
      managedAiLimit: 0,
      managedAiUsed: 0
    }]);
    const upstreamFetch = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      expect(String(input)).toBe("https://openrouter.ai/api/v1/chat/completions");
      expect(init?.headers).toMatchObject({
        "Content-Type": "application/json",
        "Authorization": "Bearer caller-openrouter-key"
      });
      const payload = JSON.parse(String(init?.body));
      expect(payload.model).toBe("openrouter/test-model");
      expect(String(init?.body)).not.toContain(baseEnv.AI_API_KEY);
      return openAiBody();
    });
    vi.stubGlobal("fetch", upstreamFetch);

    const response = await worker.fetch(
      byokRequest("openrouter", "openrouter/test-model", "caller-openrouter-key"),
      { ...baseEnv, MEMBERSHIP_STORE: store }
    );

    expect(response.status).toBe(200);
    const text = await response.text();
    expect(JSON.parse(text)).toMatchObject({ summary: structured.brief, model: "openrouter/test-model" });
    expect(text).not.toContain("caller-openrouter-key");
    expect(upstreamFetch).toHaveBeenCalledTimes(1);
    await expect(store.getManagedAiQuota("byok-user")).resolves.toEqual({ limit: 0, used: 0 });
  });

  it("rejects unsupported BYOK providers before any upstream request", async () => {
    const upstreamFetch = vi.fn();
    vi.stubGlobal("fetch", upstreamFetch);

    const response = await worker.fetch(
      byokRequest("https://attacker.example", "anything", "secret-key"),
      baseEnv
    );

    expect(response.status).toBe(400);
    await expect(response.json()).resolves.toEqual({ error: "byok_provider_unsupported" });
    expect(upstreamFetch).not.toHaveBeenCalled();
  });

  it("requires a per-request key and never falls back to the managed provider secret", async () => {
    const upstreamFetch = vi.fn();
    vi.stubGlobal("fetch", upstreamFetch);

    const response = await worker.fetch(
      byokRequest("gemini", "gemini-2.5-flash"),
      baseEnv
    );

    expect(response.status).toBe(400);
    await expect(response.json()).resolves.toEqual({ error: "byok_api_key_required" });
    expect(upstreamFetch).not.toHaveBeenCalled();
  });

  it("rejects control characters in user-selected model names", async () => {
    const response = await worker.fetch(
      byokRequest("openrouter", "model\nInjected: value", "caller-key"),
      baseEnv
    );

    expect(response.status).toBe(400);
    await expect(response.json()).resolves.toEqual({ error: "byok_model_invalid" });
  });

  it("advertises only the fixed BYOK provider set", async () => {
    const response = await worker.fetch(new Request("https://gateway.example/v1/providers", {
      headers: { "Authorization": `Bearer ${baseEnv.GATEWAY_TOKEN}` }
    }), baseEnv);

    expect(response.status).toBe(200);
    await expect(response.json()).resolves.toMatchObject({
      byokProviders: ["openrouter", "gemini"]
    });
  });
});

import { afterEach, describe, expect, it, vi } from "vitest";
import worker from "./index";

const env = {
  AI_API_KEY: "provider-secret",
  GATEWAY_TOKEN: "gateway-secret",
  AI_BASE_URL: "https://provider.example/v1",
  AI_MODEL: "test-model"
};

function summaryRequest(content: string, token = env.GATEWAY_TOKEN): Request {
  return new Request("https://gateway.example/v1/summary", {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
      "Authorization": `Bearer ${token}`
    },
    body: JSON.stringify({ title: "Test title", content })
  });
}

afterEach(() => {
  vi.unstubAllGlobals();
});

describe("WristBrief gateway", () => {
  it("returns health without provider access", async () => {
    const response = await worker.fetch(new Request("https://gateway.example/health"), env);

    expect(response.status).toBe(200);
    await expect(response.json()).resolves.toEqual({ ok: true });
  });

  it("rejects unauthorized summary requests", async () => {
    const response = await worker.fetch(summaryRequest("hello", "wrong-token"), env);

    expect(response.status).toBe(401);
    await expect(response.json()).resolves.toEqual({ error: "unauthorized" });
  });

  it("rejects invalid JSON", async () => {
    const request = new Request("https://gateway.example/v1/summary", {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        "Authorization": `Bearer ${env.GATEWAY_TOKEN}`
      },
      body: "{"
    });

    const response = await worker.fetch(request, env);

    expect(response.status).toBe(400);
    await expect(response.json()).resolves.toEqual({ error: "invalid_json" });
  });

  it("enforces the summary input size limit", async () => {
    const response = await worker.fetch(summaryRequest("a".repeat(50_001)), env);

    expect(response.status).toBe(413);
    await expect(response.json()).resolves.toEqual({ error: "content_too_large" });
  });

  it("rejects non-HTTPS provider configuration", async () => {
    const response = await worker.fetch(
      summaryRequest("hello"),
      { ...env, AI_BASE_URL: "http://provider.example/v1" }
    );

    expect(response.status).toBe(500);
    await expect(response.json()).resolves.toEqual({ error: "invalid_provider_url" });
  });

  it("returns a provider summary without exposing credentials", async () => {
    const upstreamFetch = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      expect(String(input)).toBe("https://provider.example/v1/chat/completions");
      expect(init?.headers).toMatchObject({
        "Content-Type": "application/json",
        "Authorization": "Bearer provider-secret"
      });
      return new Response(
        JSON.stringify({
          choices: [{ message: { content: "Three concise points." } }]
        }),
        { status: 200, headers: { "Content-Type": "application/json" } }
      );
    });
    vi.stubGlobal("fetch", upstreamFetch);

    const response = await worker.fetch(summaryRequest("Long feed content"), env);

    expect(response.status).toBe(200);
    await expect(response.json()).resolves.toEqual({
      summary: "Three concise points.",
      model: "test-model"
    });
    expect(upstreamFetch).toHaveBeenCalledTimes(1);
  });

  it("maps upstream provider failures to a safe gateway error", async () => {
    vi.stubGlobal("fetch", vi.fn(async () => new Response("secret upstream body", { status: 429 })));

    const response = await worker.fetch(summaryRequest("hello"), env);

    expect(response.status).toBe(502);
    await expect(response.json()).resolves.toEqual({ error: "provider_error", status: 429 });
  });
});

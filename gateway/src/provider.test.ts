import { afterEach, describe, expect, it, vi } from "vitest";
import {
  AiProviderRegistry,
  OpenAiCompatibleProvider,
  ProviderError,
  createProviderRegistry,
  type AiProvider
} from "./provider";
import { BRIEF_PROMPT_VERSION, BRIEF_SCHEMA_VERSION, type StructuredBrief } from "./structuredBrief";

const env = {
  AI_API_KEY: "provider-secret",
  AI_BASE_URL: "https://provider.example/v1",
  AI_MODEL: "test-model"
};

const structured: StructuredBrief = {
  tiny: "Tiny.",
  brief: "Concise.",
  bullets: ["One"],
  topics: ["Tech"],
  sourceLanguage: "en",
  outputLanguage: "en",
  schemaVersion: BRIEF_SCHEMA_VERSION,
  promptVersion: BRIEF_PROMPT_VERSION
};

const providerBody = (value: unknown) => new Response(JSON.stringify({ choices: [{ message: { content: typeof value === "string" ? value : JSON.stringify(value) } }] }), {
  status: 200,
  headers: { "Content-Type": "application/json" }
});

afterEach(() => vi.unstubAllGlobals());

describe("AI provider registry", () => {
  it("registers and resolves providers by server-side id", () => {
    const provider: AiProvider = { id: "fake", summarize: vi.fn(async () => ({ summary: structured.brief, model: "fake-model", structured })) };
    const registry = new AiProviderRegistry().register(provider);
    expect(registry.require("fake")).toBe(provider);
    expect(() => registry.require("missing")).toThrow("unknown_provider:missing");
  });

  it("creates the OpenAI-compatible adapter as the default managed provider", () => {
    expect(createProviderRegistry(env).require("openai-compatible")).toBeInstanceOf(OpenAiCompatibleProvider);
  });

  it("rejects duplicate provider ids", () => {
    const provider: AiProvider = { id: "fake", summarize: vi.fn(async () => ({ summary: structured.brief, model: "fake-model", structured })) };
    const registry = new AiProviderRegistry().register(provider);
    expect(() => registry.register(provider)).toThrow("duplicate_provider:fake");
  });
});

describe("OpenAI-compatible provider", () => {
  it("returns validated structured output and compatibility summary", async () => {
    const upstreamFetch = vi.fn(async (_input: RequestInfo | URL, init?: RequestInit) => {
      const body = JSON.parse(String(init?.body));
      expect(body.messages[0].content).toContain("untrusted data");
      return providerBody(structured);
    });
    vi.stubGlobal("fetch", upstreamFetch);

    await expect(new OpenAiCompatibleProvider(env).summarize({ title: "Title", content: "Body" })).resolves.toEqual({
      summary: "Concise.", model: "test-model", structured
    });
    expect(upstreamFetch).toHaveBeenCalledTimes(1);
  });

  it("repairs malformed output at most once", async () => {
    const upstreamFetch = vi.fn()
      .mockResolvedValueOnce(providerBody("not-json"))
      .mockResolvedValueOnce(providerBody(structured));
    vi.stubGlobal("fetch", upstreamFetch);

    await expect(new OpenAiCompatibleProvider(env).summarize({ content: "Body" })).resolves.toMatchObject({ summary: "Concise." });
    expect(upstreamFetch).toHaveBeenCalledTimes(2);
    const secondBody = JSON.parse(String(upstreamFetch.mock.calls[1][1]?.body));
    expect(secondBody.messages[0].content).toContain("single repair attempt");
  });

  it("rejects output that is malformed twice without a third provider call", async () => {
    const upstreamFetch = vi.fn().mockResolvedValue(providerBody("still-bad"));
    vi.stubGlobal("fetch", upstreamFetch);
    await expect(new OpenAiCompatibleProvider(env).summarize({ content: "Body" })).rejects.toMatchObject<Partial<ProviderError>>({ code: "invalid_provider_response" });
    expect(upstreamFetch).toHaveBeenCalledTimes(2);
  });

  it("rejects non-HTTPS configuration before any upstream call", async () => {
    const upstreamFetch = vi.fn();
    vi.stubGlobal("fetch", upstreamFetch);
    await expect(new OpenAiCompatibleProvider({ ...env, AI_BASE_URL: "http://provider.example/v1" }).summarize({ content: "Body" })).rejects.toMatchObject<Partial<ProviderError>>({ code: "invalid_provider_url" });
    expect(upstreamFetch).not.toHaveBeenCalled();
  });

  it("maps upstream failures without exposing the body", async () => {
    vi.stubGlobal("fetch", vi.fn(async () => new Response("secret upstream body", { status: 429 })));
    await expect(new OpenAiCompatibleProvider(env).summarize({ content: "Body" })).rejects.toMatchObject<Partial<ProviderError>>({ code: "provider_error", upstreamStatus: 429 });
  });
});

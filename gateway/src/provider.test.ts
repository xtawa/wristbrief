import { afterEach, describe, expect, it, vi } from "vitest";
import {
  AiProviderRegistry,
  GeminiProvider,
  OpenAiCompatibleProvider,
  OpenRouterProvider,
  ProviderError,
  createProviderRegistry,
  type AiProvider
} from "./provider";
import { BRIEF_PROMPT_VERSION, BRIEF_SCHEMA_VERSION, type StructuredBrief } from "./structuredBrief";

const env = {
  AI_API_KEY: "provider-secret",
  AI_BASE_URL: "https://provider.example/v1",
  AI_MODEL: "test-model",
  OPENROUTER_API_KEY: "openrouter-secret",
  OPENROUTER_MODEL: "openrouter/test-model",
  GEMINI_API_KEY: "gemini-secret",
  GEMINI_MODEL: "gemini-test"
};

const structured: StructuredBrief = {
  tiny: "Tiny.", brief: "Concise.", bullets: ["One"], topics: ["Tech"], sourceLanguage: "en", outputLanguage: "en",
  schemaVersion: BRIEF_SCHEMA_VERSION, promptVersion: BRIEF_PROMPT_VERSION
};

const openAiBody = (value: unknown) => new Response(JSON.stringify({ choices: [{ message: { content: typeof value === "string" ? value : JSON.stringify(value) } }] }), { status: 200, headers: { "Content-Type": "application/json" } });
const geminiBody = (value: unknown) => new Response(JSON.stringify({ candidates: [{ content: { parts: [{ text: typeof value === "string" ? value : JSON.stringify(value) }] } }] }), { status: 200, headers: { "Content-Type": "application/json" } });

afterEach(() => vi.unstubAllGlobals());

describe("AI provider registry", () => {
  it("registers and resolves providers by server-side id", () => {
    const provider: AiProvider = { id: "fake", metadata: { id: "fake", model: "fake-model", api: "openai-compatible" }, summarize: vi.fn(async () => ({ summary: structured.brief, model: "fake-model", structured })) };
    const registry = new AiProviderRegistry().register(provider);
    expect(registry.require("fake")).toBe(provider);
    expect(() => registry.require("missing")).toThrowError(ProviderError);
  });

  it("registers only server-configured managed providers and exposes safe metadata", () => {
    const registry = createProviderRegistry(env);
    expect(registry.require("openai-compatible")).toBeInstanceOf(OpenAiCompatibleProvider);
    expect(registry.require("openrouter")).toBeInstanceOf(OpenRouterProvider);
    expect(registry.require("gemini")).toBeInstanceOf(GeminiProvider);
    expect(registry.list()).toEqual([
      { id: "openai-compatible", model: "test-model", api: "openai-compatible" },
      { id: "openrouter", model: "openrouter/test-model", api: "openai-compatible" },
      { id: "gemini", model: "gemini-test", api: "gemini-native" }
    ]);
    expect(JSON.stringify(registry.list())).not.toContain("secret");
  });

  it("does not register optional providers without complete server-side config", () => {
    const registry = createProviderRegistry({ AI_API_KEY: env.AI_API_KEY, AI_BASE_URL: env.AI_BASE_URL, AI_MODEL: env.AI_MODEL });
    expect(registry.list()).toHaveLength(1);
    expect(() => registry.require("gemini")).toThrowError(ProviderError);
  });
});

describe("provider routing", () => {
  it("routes OpenRouter through its fixed managed endpoint", async () => {
    const upstreamFetch = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      expect(String(input)).toBe("https://openrouter.ai/api/v1/chat/completions");
      expect(init?.headers).toMatchObject({ Authorization: "Bearer openrouter-secret" });
      expect(JSON.parse(String(init?.body)).model).toBe("openrouter/test-model");
      return openAiBody(structured);
    });
    vi.stubGlobal("fetch", upstreamFetch);
    await expect(new OpenRouterProvider(env.OPENROUTER_API_KEY, env.OPENROUTER_MODEL).summarize({ content: "Body" })).resolves.toMatchObject({ model: "openrouter/test-model", summary: "Concise." });
  });

  it("routes Gemini through native generateContent without placing the key in the URL", async () => {
    const upstreamFetch = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      expect(String(input)).toBe("https://generativelanguage.googleapis.com/v1beta/models/gemini-test:generateContent");
      expect(String(input)).not.toContain("gemini-secret");
      expect(init?.headers).toMatchObject({ "x-goog-api-key": "gemini-secret" });
      const body = JSON.parse(String(init?.body));
      expect(body.systemInstruction.parts[0].text).toContain("untrusted data");
      expect(body.contents[0].role).toBe("user");
      return geminiBody(structured);
    });
    vi.stubGlobal("fetch", upstreamFetch);
    await expect(new GeminiProvider(env.GEMINI_API_KEY, env.GEMINI_MODEL).summarize({ content: "Body" })).resolves.toMatchObject({ model: "gemini-test", summary: "Concise." });
  });

  it("keeps the OpenAI-compatible HTTPS guard", async () => {
    const upstreamFetch = vi.fn(); vi.stubGlobal("fetch", upstreamFetch);
    await expect(new OpenAiCompatibleProvider({ ...env, AI_BASE_URL: "http://provider.example/v1" }).summarize({ content: "Body" })).rejects.toMatchObject<Partial<ProviderError>>({ code: "invalid_provider_url" });
    expect(upstreamFetch).not.toHaveBeenCalled();
  });
});

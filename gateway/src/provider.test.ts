import { afterEach, describe, expect, it, vi } from "vitest";
import {
  AiProviderRegistry,
  AnthropicProvider,
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
  GEMINI_MODEL: "gemini-test",
  ANTHROPIC_API_KEY: "anthropic-secret",
  ANTHROPIC_MODEL: "claude-3-5-haiku-20241022"
};

const structured: StructuredBrief = {
  tiny: "Tiny.",
  brief: "Concise.",
  long: "A longer phone-friendly summary with more context than the watch brief.",
  bullets: ["One"],
  topics: ["Tech"],
  sourceLanguage: "en",
  outputLanguage: "en",
  schemaVersion: BRIEF_SCHEMA_VERSION,
  promptVersion: BRIEF_PROMPT_VERSION
};

const openAiBody = (value: unknown) => new Response(JSON.stringify({
  choices: [{ message: { content: typeof value === "string" ? value : JSON.stringify(value) } }]
}), { status: 200, headers: { "Content-Type": "application/json" } });

const geminiBody = (value: unknown) => new Response(JSON.stringify({
  candidates: [{ content: { parts: [{ text: typeof value === "string" ? value : JSON.stringify(value) }] } }]
}), { status: 200, headers: { "Content-Type": "application/json" } });

const anthropicBody = (value: unknown) => new Response(JSON.stringify({
  content: [{ type: "text", text: typeof value === "string" ? value : JSON.stringify(value) }]
}), { status: 200, headers: { "Content-Type": "application/json" } });

afterEach(() => vi.unstubAllGlobals());

describe("AI provider registry", () => {
  it("registers and resolves providers by server-side id", () => {
    const provider: AiProvider = {
      id: "fake",
      metadata: { id: "fake", model: "fake-model", api: "openai-compatible" },
      summarize: vi.fn(async () => ({ summary: structured.brief, model: "fake-model", structured }))
    };
    const registry = new AiProviderRegistry().register(provider);
    expect(registry.require("fake")).toBe(provider);
    expect(() => registry.require("missing")).toThrowError(ProviderError);
  });

  it("registers only server-configured managed providers and exposes safe metadata", () => {
    const registry = createProviderRegistry(env);
    expect(registry.require("openai-compatible")).toBeInstanceOf(OpenAiCompatibleProvider);
    expect(registry.require("openrouter")).toBeInstanceOf(OpenRouterProvider);
    expect(registry.require("gemini")).toBeInstanceOf(GeminiProvider);
    expect(registry.require("anthropic")).toBeInstanceOf(AnthropicProvider);
    expect(registry.list()).toEqual([
      { id: "openai-compatible", model: "test-model", api: "openai-compatible" },
      { id: "openrouter", model: "openrouter/test-model", api: "openai-compatible" },
      { id: "gemini", model: "gemini-test", api: "gemini-native" },
      { id: "anthropic", model: "claude-3-5-haiku-20241022", api: "anthropic-native" }
    ]);
    expect(JSON.stringify(registry.list())).not.toContain("secret");
  });

  it("does not register optional providers without complete server-side config", () => {
    const registry = createProviderRegistry({
      AI_API_KEY: env.AI_API_KEY,
      AI_BASE_URL: env.AI_BASE_URL,
      AI_MODEL: env.AI_MODEL
    });
    expect(registry.list()).toHaveLength(1);
    expect(() => registry.require("gemini")).toThrowError(ProviderError);
  });
});

describe("provider routing and security", () => {
  it("routes OpenRouter through its fixed managed endpoint", async () => {
    const upstreamFetch = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      expect(String(input)).toBe("https://openrouter.ai/api/v1/chat/completions");
      expect(init?.headers).toMatchObject({ Authorization: "Bearer openrouter-secret" });
      expect(init?.redirect).toBe("error");
      expect(JSON.parse(String(init?.body)).model).toBe("openrouter/test-model");
      return openAiBody(structured);
    });
    vi.stubGlobal("fetch", upstreamFetch);

    await expect(
      new OpenRouterProvider(env.OPENROUTER_API_KEY, env.OPENROUTER_MODEL).summarize({ content: "Body" })
    ).resolves.toMatchObject({ model: "openrouter/test-model", summary: "Concise.", structured: { long: structured.long } });
  });

  it("routes Gemini through native generateContent without placing the key in the URL", async () => {
    const upstreamFetch = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      expect(String(input)).toBe("https://generativelanguage.googleapis.com/v1beta/models/gemini-test:generateContent");
      expect(String(input)).not.toContain("gemini-secret");
      expect(init?.headers).toMatchObject({ "x-goog-api-key": "gemini-secret" });
      expect(init?.redirect).toBe("error");
      const body = JSON.parse(String(init?.body));
      expect(body.systemInstruction.parts[0].text).toContain("untrusted data");
      expect(body.contents[0].role).toBe("user");
      return geminiBody(structured);
    });
    vi.stubGlobal("fetch", upstreamFetch);

    await expect(
      new GeminiProvider(env.GEMINI_API_KEY, env.GEMINI_MODEL).summarize({ content: "Body" })
    ).resolves.toMatchObject({ model: "gemini-test", summary: "Concise.", structured: { long: structured.long } });
  });

  it("routes Anthropic through native Messages API with proper headers", async () => {
    const upstreamFetch = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      expect(String(input)).toBe("https://api.anthropic.com/v1/messages");
      expect(init?.headers).toMatchObject({
        "x-api-key": "anthropic-secret",
        "anthropic-version": "2023-06-01"
      });
      expect(init?.redirect).toBe("error");
      const body = JSON.parse(String(init?.body));
      expect(body.model).toBe("claude-3-5-haiku-20241022");
      expect(body.system).toContain("untrusted data");
      expect(body.messages[0].role).toBe("user");
      return anthropicBody(structured);
    });
    vi.stubGlobal("fetch", upstreamFetch);

    await expect(
      new AnthropicProvider(env.ANTHROPIC_API_KEY, env.ANTHROPIC_MODEL).summarize({ content: "Body" })
    ).resolves.toMatchObject({ model: "claude-3-5-haiku-20241022", summary: "Concise.", structured: { long: structured.long } });
  });

  it("keeps the OpenAI-compatible HTTPS guard", async () => {
    const upstreamFetch = vi.fn();
    vi.stubGlobal("fetch", upstreamFetch);
    expect(() => new OpenAiCompatibleProvider({ ...env, AI_BASE_URL: "http://provider.example/v1" }))
      .toThrowError(ProviderError);
    expect(upstreamFetch).not.toHaveBeenCalled();
  });

  it("enforces an exact server-side host allowlist", () => {
    expect(() => new OpenAiCompatibleProvider({
      ...env,
      AI_ALLOWED_HOSTS: "trusted.example, other.example"
    })).toThrowError(ProviderError);

    expect(() => new OpenAiCompatibleProvider({
      ...env,
      AI_ALLOWED_HOSTS: "provider.example"
    })).not.toThrow();
  });

  it("rejects credentials/query/hash in provider base URLs", () => {
    for (const base of [
      "https://user:pass@provider.example/v1",
      "https://provider.example/v1?target=elsewhere",
      "https://provider.example/v1#fragment"
    ]) {
      expect(() => new OpenAiCompatibleProvider({ ...env, AI_BASE_URL: base })).toThrowError(ProviderError);
    }
  });
});

describe("provider reliability", () => {
  it.each([429, 500, 503])("retries retryable upstream status %s once", async (status) => {
    const upstreamFetch = vi.fn()
      .mockResolvedValueOnce(new Response("do-not-expose", { status }))
      .mockResolvedValueOnce(openAiBody(structured));
    vi.stubGlobal("fetch", upstreamFetch);

    await expect(new OpenAiCompatibleProvider(env).summarize({ content: "Body" }))
      .resolves.toMatchObject({ summary: "Concise." });
    expect(upstreamFetch).toHaveBeenCalledTimes(2);
  });

  it.each([400, 401, 403])("does not retry non-retryable upstream status %s", async (status) => {
    const upstreamFetch = vi.fn(async () => new Response("secret upstream body", { status }));
    vi.stubGlobal("fetch", upstreamFetch);

    await expect(new OpenAiCompatibleProvider(env).summarize({ content: "Body" }))
      .rejects.toMatchObject<Partial<ProviderError>>({ code: "provider_error", upstreamStatus: status });
    expect(upstreamFetch).toHaveBeenCalledTimes(1);
  });

  it("aborts timed-out calls and retries only within the configured bound", async () => {
    const upstreamFetch = vi.fn((_input: RequestInfo | URL, init?: RequestInit) =>
      new Promise<Response>((_resolve, reject) => {
        init?.signal?.addEventListener("abort", () => reject(new Error("aborted")), { once: true });
      })
    );
    vi.stubGlobal("fetch", upstreamFetch);

    await expect(new OpenAiCompatibleProvider({
      ...env,
      AI_PROVIDER_TIMEOUT_MS: "1",
      AI_PROVIDER_MAX_RETRIES: "1"
    }).summarize({ content: "Body" }))
      .rejects.toMatchObject<Partial<ProviderError>>({ code: "provider_timeout" });
    expect(upstreamFetch).toHaveBeenCalledTimes(2);
  });

  it("does not retry malformed successful JSON", async () => {
    const upstreamFetch = vi.fn(async () => new Response("{", { status: 200 }));
    vi.stubGlobal("fetch", upstreamFetch);

    await expect(new OpenAiCompatibleProvider(env).summarize({ content: "Body" }))
      .rejects.toMatchObject<Partial<ProviderError>>({ code: "invalid_provider_response" });
    expect(upstreamFetch).toHaveBeenCalledTimes(1);
  });
});

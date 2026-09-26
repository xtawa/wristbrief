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
import { setSafeFetchResolverOverride, setSafeFetchTransportOverride, type SafeHttpTransport } from "./net/safeRemoteFetcher";

/**
 * Provider POSTs go through the SSRF-safe pinned transport (resolve, screen
 * every answer, pin the connect), not global fetch. These tests install a
 * transport double that reproduces the old `fetch(url, init)` view, so the
 * handlers below keep asserting the same method, headers, body and signal.
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
        // The pinned transport refuses a 3xx by construction; the captured init
        // keeps the flag so the existing assertion stays meaningful.
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

afterEach(() => {
  vi.unstubAllGlobals();
  setSafeFetchTransportOverride(null);
  setSafeFetchResolverOverride(null);
});

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
    const calls = usePinnedTransport(async (url, init) => {
      expect(url).toBe("https://openrouter.ai/api/v1/chat/completions");
      expect(init?.headers).toMatchObject({ Authorization: "Bearer openrouter-secret" });
      expect(init?.redirect).toBe("error");
      expect(JSON.parse(String(init?.body)).model).toBe("openrouter/test-model");
      return openAiBody(structured);
    });

    await expect(
      new OpenRouterProvider(env.OPENROUTER_API_KEY, env.OPENROUTER_MODEL).summarize({ content: "Body" })
    ).resolves.toMatchObject({ model: "openrouter/test-model", summary: "Concise.", structured: { long: structured.long } });
    expect(calls).toHaveLength(1);
  });

  it("routes Gemini through native generateContent without placing the key in the URL", async () => {
    const calls = usePinnedTransport(async (url, init) => {
      expect(url).toBe("https://generativelanguage.googleapis.com/v1beta/models/gemini-test:generateContent");
      expect(url).not.toContain("gemini-secret");
      expect(init?.headers).toMatchObject({ "x-goog-api-key": "gemini-secret" });
      expect(init?.redirect).toBe("error");
      const body = JSON.parse(String(init?.body));
      expect(body.systemInstruction.parts[0].text).toContain("untrusted data");
      expect(body.contents[0].role).toBe("user");
      return geminiBody(structured);
    });

    await expect(
      new GeminiProvider(env.GEMINI_API_KEY, env.GEMINI_MODEL).summarize({ content: "Body" })
    ).resolves.toMatchObject({ model: "gemini-test", summary: "Concise.", structured: { long: structured.long } });
    expect(calls).toHaveLength(1);
  });

  it("routes Anthropic through native Messages API with proper headers", async () => {
    const calls = usePinnedTransport(async (url, init) => {
      expect(url).toBe("https://api.anthropic.com/v1/messages");
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

    await expect(
      new AnthropicProvider(env.ANTHROPIC_API_KEY, env.ANTHROPIC_MODEL).summarize({ content: "Body" })
    ).resolves.toMatchObject({ model: "claude-3-5-haiku-20241022", summary: "Concise.", structured: { long: structured.long } });
    expect(calls).toHaveLength(1);
  });

  it("keeps the OpenAI-compatible HTTPS guard", async () => {
    const calls = usePinnedTransport(() => openAiBody(structured));
    expect(() => new OpenAiCompatibleProvider({ ...env, AI_BASE_URL: "http://provider.example/v1" }))
      .toThrowError(ProviderError);
    expect(calls).toHaveLength(0);
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
    const calls = usePinnedTransport((_url, _init) => {
      if (calls.length === 1) return new Response("do-not-expose", { status });
      return openAiBody(structured);
    });

    await expect(new OpenAiCompatibleProvider(env).summarize({ content: "Body" }))
      .resolves.toMatchObject({ summary: "Concise." });
    expect(calls).toHaveLength(2);
  });

  it.each([400, 401, 403])("does not retry non-retryable upstream status %s", async (status) => {
    const calls = usePinnedTransport(() => new Response("secret upstream body", { status }));

    await expect(new OpenAiCompatibleProvider(env).summarize({ content: "Body" }))
      .rejects.toMatchObject<Partial<ProviderError>>({ code: "provider_error", upstreamStatus: status });
    expect(calls).toHaveLength(1);
  });

  it("aborts timed-out calls and retries only within the configured bound", async () => {
    const calls = usePinnedTransport((_url, init) =>
      new Promise<Response>((_resolve, reject) => {
        init?.signal?.addEventListener("abort", () => reject(new Error("aborted")), { once: true });
      })
    );

    await expect(new OpenAiCompatibleProvider({
      ...env,
      AI_PROVIDER_TIMEOUT_MS: "1",
      AI_PROVIDER_MAX_RETRIES: "1"
    }).summarize({ content: "Body" }))
      .rejects.toMatchObject<Partial<ProviderError>>({ code: "provider_timeout" });
    expect(calls).toHaveLength(2);
  });

  it("does not retry malformed successful JSON", async () => {
    const calls = usePinnedTransport(() => new Response("{", { status: 200 }));

    await expect(new OpenAiCompatibleProvider(env).summarize({ content: "Body" }))
      .rejects.toMatchObject<Partial<ProviderError>>({ code: "invalid_provider_response" });
    expect(calls).toHaveLength(1);
  });

  it("refuses a provider redirect instead of following it", async () => {
    const calls = usePinnedTransport(() => new Response(null, {
      status: 302,
      headers: { Location: "http://169.254.169.254/latest/meta-data" }
    }));

    await expect(new OpenAiCompatibleProvider(env).summarize({ content: "Body" }))
      .rejects.toMatchObject<Partial<ProviderError>>({ code: "provider_error", upstreamStatus: 302 });
    // The Location was never requested.
    expect(calls).toHaveLength(1);
  });

  it("refuses a provider host that resolves to a private or metadata address", async () => {
    const calls = usePinnedTransport(() => openAiBody(structured));
    for (const address of ["127.0.0.1", "10.0.0.5", "169.254.169.254", "::ffff:169.254.169.254", "64:ff9b::a9fe:a9fe"]) {
      setSafeFetchResolverOverride(async () => [address]);
      await expect(new OpenAiCompatibleProvider(env).summarize({ content: "Body" }))
        .rejects.toMatchObject<Partial<ProviderError>>({ code: "invalid_provider_url" });
    }
    expect(calls).toHaveLength(0);
  });

  it("refuses a mixed answer set and a rebinding second answer", async () => {
    const calls = usePinnedTransport(() => openAiBody(structured));
    setSafeFetchResolverOverride(async () => ["93.184.216.34", "127.0.0.1"]);
    await expect(new OpenAiCompatibleProvider(env).summarize({ content: "Body" }))
      .rejects.toMatchObject<Partial<ProviderError>>({ code: "invalid_provider_url" });
    expect(calls).toHaveLength(0);

    // A second resolution that would return a private address is never consulted:
    // the first screened answer is pinned for the connection.
    let resolutions = 0;
    setSafeFetchResolverOverride(async () => {
      resolutions += 1;
      return resolutions === 1 ? ["93.184.216.34"] : ["127.0.0.1"];
    });
    await expect(new OpenAiCompatibleProvider(env).summarize({ content: "Body" }))
      .resolves.toMatchObject({ summary: "Concise." });
    expect(resolutions).toBe(1);
  });
});

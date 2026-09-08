import { afterEach, describe, expect, it, vi } from "vitest";
import {
  AiProviderRegistry,
  OpenAiCompatibleProvider,
  ProviderError,
  createProviderRegistry,
  type AiProvider
} from "./provider";

const env = {
  AI_API_KEY: "provider-secret",
  AI_BASE_URL: "https://provider.example/v1",
  AI_MODEL: "test-model"
};

afterEach(() => {
  vi.unstubAllGlobals();
});

describe("AI provider registry", () => {
  it("registers and resolves providers by server-side id", () => {
    const provider: AiProvider = {
      id: "fake",
      summarize: vi.fn(async () => ({ summary: "ok", model: "fake-model" }))
    };
    const registry = new AiProviderRegistry().register(provider);

    expect(registry.require("fake")).toBe(provider);
    expect(() => registry.require("missing")).toThrow("unknown_provider:missing");
  });

  it("creates the OpenAI-compatible adapter as the default managed provider", () => {
    const registry = createProviderRegistry(env);
    expect(registry.require("openai-compatible")).toBeInstanceOf(OpenAiCompatibleProvider);
  });

  it("rejects duplicate provider ids", () => {
    const provider: AiProvider = {
      id: "fake",
      summarize: vi.fn(async () => ({ summary: "ok", model: "fake-model" }))
    };
    const registry = new AiProviderRegistry().register(provider);

    expect(() => registry.register(provider)).toThrow("duplicate_provider:fake");
  });
});

describe("OpenAI-compatible provider", () => {
  it("sends summaries only to the configured HTTPS upstream", async () => {
    const upstreamFetch = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      expect(String(input)).toBe("https://provider.example/v1/chat/completions");
      expect(init?.headers).toMatchObject({
        "Content-Type": "application/json",
        "Authorization": "Bearer provider-secret"
      });
      return new Response(JSON.stringify({ choices: [{ message: { content: " concise " } }] }), {
        status: 200,
        headers: { "Content-Type": "application/json" }
      });
    });
    vi.stubGlobal("fetch", upstreamFetch);

    const result = await new OpenAiCompatibleProvider(env).summarize({
      title: "Title",
      content: "Body"
    });

    expect(result).toEqual({ summary: "concise", model: "test-model" });
    expect(upstreamFetch).toHaveBeenCalledTimes(1);
  });

  it("rejects non-HTTPS configuration before any upstream call", async () => {
    const upstreamFetch = vi.fn();
    vi.stubGlobal("fetch", upstreamFetch);

    await expect(
      new OpenAiCompatibleProvider({ ...env, AI_BASE_URL: "http://provider.example/v1" }).summarize({ content: "Body" })
    ).rejects.toMatchObject<Partial<ProviderError>>({ code: "invalid_provider_url" });
    expect(upstreamFetch).not.toHaveBeenCalled();
  });

  it("maps upstream failures without exposing the body", async () => {
    vi.stubGlobal("fetch", vi.fn(async () => new Response("secret upstream body", { status: 429 })));

    await expect(new OpenAiCompatibleProvider(env).summarize({ content: "Body" })).rejects.toMatchObject<Partial<ProviderError>>({
      code: "provider_error",
      upstreamStatus: 429
    });
  });

  it("rejects malformed provider JSON/output", async () => {
    vi.stubGlobal("fetch", vi.fn(async () => new Response("not-json", { status: 200 })));

    await expect(new OpenAiCompatibleProvider(env).summarize({ content: "Body" })).rejects.toMatchObject<Partial<ProviderError>>({
      code: "invalid_provider_response"
    });
  });
});

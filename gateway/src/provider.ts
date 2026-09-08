import {
  buildRepairPrompt,
  buildStructuredBriefPrompt,
  parseStructuredBrief,
  type StructuredBrief
} from "./structuredBrief";

export type SummaryInput = { title?: string; content: string };
export type SummaryOutput = { summary: string; model: string; structured: StructuredBrief };
export type ProviderMetadata = { id: string; model: string; api: "openai-compatible" | "gemini-native" };

export interface AiProvider {
  readonly id: string;
  readonly metadata: ProviderMetadata;
  summarize(input: SummaryInput): Promise<SummaryOutput>;
}

export type ProviderEnv = {
  AI_API_KEY: string;
  AI_BASE_URL: string;
  AI_MODEL: string;
  AI_PROVIDER?: string;
  OPENROUTER_API_KEY?: string;
  OPENROUTER_MODEL?: string;
  GEMINI_API_KEY?: string;
  GEMINI_MODEL?: string;
};

export class ProviderError extends Error {
  constructor(
    public readonly code: "invalid_provider_url" | "provider_error" | "invalid_provider_response" | "provider_not_configured",
    public readonly upstreamStatus?: number
  ) { super(code); }
}

abstract class StructuredProvider implements AiProvider {
  abstract readonly id: string;
  abstract readonly metadata: ProviderMetadata;
  abstract complete(system: string, user: string): Promise<string>;

  async summarize(input: SummaryInput): Promise<SummaryOutput> {
    const firstPrompt = buildStructuredBriefPrompt(input.title, input.content);
    const firstRaw = await this.complete(firstPrompt.system, firstPrompt.user);
    let structured = parseStructuredBrief(firstRaw);
    if (!structured) {
      const repairPrompt = buildRepairPrompt(input.title, input.content, firstRaw);
      structured = parseStructuredBrief(await this.complete(repairPrompt.system, repairPrompt.user));
    }
    if (!structured) throw new ProviderError("invalid_provider_response");
    return { summary: structured.brief, model: this.metadata.model, structured };
  }
}

export class OpenAiCompatibleProvider extends StructuredProvider {
  readonly id = "openai-compatible";
  readonly metadata: ProviderMetadata;
  constructor(private readonly env: ProviderEnv) {
    super();
    this.metadata = { id: this.id, model: env.AI_MODEL, api: "openai-compatible" };
  }
  async complete(system: string, user: string): Promise<string> {
    return completeOpenAi(this.requireHttpsBaseUrl(), this.env.AI_API_KEY, this.env.AI_MODEL, system, user);
  }
  private requireHttpsBaseUrl(): string { return requireHttpsBaseUrl(this.env.AI_BASE_URL); }
}

export class OpenRouterProvider extends StructuredProvider {
  readonly id = "openrouter";
  readonly metadata: ProviderMetadata;
  constructor(private readonly apiKey: string, private readonly model: string) {
    super();
    this.metadata = { id: this.id, model, api: "openai-compatible" };
  }
  async complete(system: string, user: string): Promise<string> {
    return completeOpenAi("https://openrouter.ai/api/v1", this.apiKey, this.model, system, user);
  }
}

export class GeminiProvider extends StructuredProvider {
  readonly id = "gemini";
  readonly metadata: ProviderMetadata;
  constructor(private readonly apiKey: string, private readonly model: string) {
    super();
    this.metadata = { id: this.id, model, api: "gemini-native" };
  }
  async complete(system: string, user: string): Promise<string> {
    const model = encodeURIComponent(this.model);
    const upstream = await fetch(`https://generativelanguage.googleapis.com/v1beta/models/${model}:generateContent`, {
      method: "POST",
      headers: { "Content-Type": "application/json", "x-goog-api-key": this.apiKey },
      body: JSON.stringify({
        systemInstruction: { parts: [{ text: system }] },
        contents: [{ role: "user", parts: [{ text: user }] }],
        generationConfig: { temperature: 0.2, responseMimeType: "application/json" }
      })
    });
    if (!upstream.ok) throw new ProviderError("provider_error", upstream.status);
    let data: unknown;
    try { data = await upstream.json(); } catch { throw new ProviderError("invalid_provider_response"); }
    const content = readGeminiContent(data);
    if (!content) throw new ProviderError("invalid_provider_response");
    return content;
  }
}

export class AiProviderRegistry {
  private readonly providers = new Map<string, AiProvider>();
  register(provider: AiProvider): this {
    if (this.providers.has(provider.id)) throw new Error(`duplicate_provider:${provider.id}`);
    this.providers.set(provider.id, provider); return this;
  }
  require(id: string): AiProvider {
    const provider = this.providers.get(id);
    if (!provider) throw new ProviderError("provider_not_configured");
    return provider;
  }
  list(): ProviderMetadata[] { return [...this.providers.values()].map((provider) => provider.metadata); }
}

export function createProviderRegistry(env: ProviderEnv): AiProviderRegistry {
  const registry = new AiProviderRegistry().register(new OpenAiCompatibleProvider(env));
  if (env.OPENROUTER_API_KEY?.trim() && env.OPENROUTER_MODEL?.trim()) {
    registry.register(new OpenRouterProvider(env.OPENROUTER_API_KEY.trim(), env.OPENROUTER_MODEL.trim()));
  }
  if (env.GEMINI_API_KEY?.trim() && env.GEMINI_MODEL?.trim()) {
    registry.register(new GeminiProvider(env.GEMINI_API_KEY.trim(), env.GEMINI_MODEL.trim()));
  }
  return registry;
}

function requireHttpsBaseUrl(value: string): string {
  const base = value.replace(/\/$/, "");
  let parsed: URL;
  try { parsed = new URL(base); } catch { throw new ProviderError("invalid_provider_url"); }
  if (parsed.protocol !== "https:") throw new ProviderError("invalid_provider_url");
  return base;
}

async function completeOpenAi(base: string, apiKey: string, model: string, system: string, user: string): Promise<string> {
  const upstream = await fetch(`${base}/chat/completions`, {
    method: "POST",
    headers: { "Content-Type": "application/json", "Authorization": `Bearer ${apiKey}` },
    body: JSON.stringify({ model, temperature: 0.2, messages: [{ role: "system", content: system }, { role: "user", content: user }] })
  });
  if (!upstream.ok) throw new ProviderError("provider_error", upstream.status);
  let data: unknown;
  try { data = await upstream.json(); } catch { throw new ProviderError("invalid_provider_response"); }
  const content = readMessageContent(data);
  if (!content) throw new ProviderError("invalid_provider_response");
  return content;
}

function readMessageContent(data: unknown): string | null {
  if (!data || typeof data !== "object") return null;
  const choices = (data as { choices?: unknown }).choices;
  if (!Array.isArray(choices) || choices.length === 0) return null;
  const message = choices[0] && typeof choices[0] === "object" ? (choices[0] as { message?: unknown }).message : null;
  const content = message && typeof message === "object" ? (message as { content?: unknown }).content : null;
  return typeof content === "string" && content.trim() ? content.trim() : null;
}

function readGeminiContent(data: unknown): string | null {
  if (!data || typeof data !== "object") return null;
  const candidates = (data as { candidates?: unknown }).candidates;
  if (!Array.isArray(candidates) || !candidates[0] || typeof candidates[0] !== "object") return null;
  const content = (candidates[0] as { content?: unknown }).content;
  if (!content || typeof content !== "object") return null;
  const parts = (content as { parts?: unknown }).parts;
  if (!Array.isArray(parts)) return null;
  const text = parts.map((part) => part && typeof part === "object" ? (part as { text?: unknown }).text : null).filter((value): value is string => typeof value === "string").join("").trim();
  return text || null;
}

import {
  buildRepairPrompt,
  buildStructuredBriefPrompt,
  parseStructuredBrief,
  type StructuredBrief
} from "./structuredBrief";

export type SummaryInput = { title?: string; content: string };
export type SummaryOutput = { summary: string; model: string; structured: StructuredBrief };
export type ProviderMetadata = { id: string; model: string; api: "openai-compatible" | "gemini-native" | "anthropic-native" };

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
  AI_FALLBACK_PROVIDER?: string;
  AI_PROVIDER_TIMEOUT_MS?: string;
  AI_PROVIDER_MAX_RETRIES?: string;
  AI_ALLOWED_HOSTS?: string;
  OPENROUTER_API_KEY?: string;
  OPENROUTER_MODEL?: string;
  GEMINI_API_KEY?: string;
  GEMINI_MODEL?: string;
  ANTHROPIC_API_KEY?: string;
  ANTHROPIC_MODEL?: string;
};

type ProviderErrorCode =
  | "invalid_provider_url"
  | "provider_error"
  | "provider_timeout"
  | "invalid_provider_response"
  | "provider_not_configured";

export class ProviderError extends Error {
  constructor(
    public readonly code: ProviderErrorCode,
    public readonly upstreamStatus?: number
  ) {
    super(code);
    this.name = "ProviderError";
  }
}

type RequestPolicy = {
  timeoutMs: number;
  maxRetries: number;
  allowedHosts: ReadonlySet<string>;
};

const DEFAULT_TIMEOUT_MS = 15_000;
const DEFAULT_MAX_RETRIES = 1;
const MAX_TIMEOUT_MS = 60_000;
const MAX_RETRIES = 2;
const OPENROUTER_BASE_URL = "https://openrouter.ai/api/v1";
const GEMINI_BASE_URL = "https://generativelanguage.googleapis.com";
const ANTHROPIC_BASE_URL = "https://api.anthropic.com";

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
  private readonly baseUrl: string;
  private readonly policy: RequestPolicy;

  constructor(private readonly env: ProviderEnv) {
    super();
    this.metadata = { id: this.id, model: env.AI_MODEL, api: "openai-compatible" };
    const configured = requireConfiguredHttpsBaseUrl(env.AI_BASE_URL, env.AI_ALLOWED_HOSTS);
    this.baseUrl = configured.baseUrl;
    this.policy = requestPolicy(env, new Set([configured.hostname]));
  }

  async complete(system: string, user: string): Promise<string> {
    return completeOpenAi(this.baseUrl, this.env.AI_API_KEY, this.env.AI_MODEL, system, user, this.policy);
  }
}

export class OpenRouterProvider extends StructuredProvider {
  readonly id = "openrouter";
  readonly metadata: ProviderMetadata;
  private readonly policy: RequestPolicy;

  constructor(private readonly apiKey: string, private readonly model: string, env?: Pick<ProviderEnv, "AI_PROVIDER_TIMEOUT_MS" | "AI_PROVIDER_MAX_RETRIES">) {
    super();
    this.metadata = { id: this.id, model, api: "openai-compatible" };
    this.policy = requestPolicy(env ?? {}, new Set(["openrouter.ai"]));
  }

  async complete(system: string, user: string): Promise<string> {
    return completeOpenAi(OPENROUTER_BASE_URL, this.apiKey, this.model, system, user, this.policy);
  }
}

export class GeminiProvider extends StructuredProvider {
  readonly id = "gemini";
  readonly metadata: ProviderMetadata;
  private readonly policy: RequestPolicy;

  constructor(private readonly apiKey: string, private readonly model: string, env?: Pick<ProviderEnv, "AI_PROVIDER_TIMEOUT_MS" | "AI_PROVIDER_MAX_RETRIES">) {
    super();
    this.metadata = { id: this.id, model, api: "gemini-native" };
    this.policy = requestPolicy(env ?? {}, new Set(["generativelanguage.googleapis.com"]));
  }

  async complete(system: string, user: string): Promise<string> {
    const model = encodeURIComponent(this.model);
    const data = await fetchJson(
      `${GEMINI_BASE_URL}/v1beta/models/${model}:generateContent`,
      {
        method: "POST",
        headers: { "Content-Type": "application/json", "x-goog-api-key": this.apiKey },
        body: JSON.stringify({
          systemInstruction: { parts: [{ text: system }] },
          contents: [{ role: "user", parts: [{ text: user }] }],
          generationConfig: { temperature: 0.2, responseMimeType: "application/json" }
        })
      },
      this.policy
    );
    const content = readGeminiContent(data);
    if (!content) throw new ProviderError("invalid_provider_response");
    return content;
  }
}

export class AnthropicProvider extends StructuredProvider {
  readonly id = "anthropic";
  readonly metadata: ProviderMetadata;
  private readonly policy: RequestPolicy;

  constructor(private readonly apiKey: string, private readonly model: string, env?: Pick<ProviderEnv, "AI_PROVIDER_TIMEOUT_MS" | "AI_PROVIDER_MAX_RETRIES">) {
    super();
    this.metadata = { id: this.id, model, api: "anthropic-native" };
    this.policy = requestPolicy(env ?? {}, new Set(["api.anthropic.com"]));
  }

  async complete(system: string, user: string): Promise<string> {
    const data = await fetchJson(
      `${ANTHROPIC_BASE_URL}/v1/messages`,
      {
        method: "POST",
        headers: {
          "Content-Type": "application/json",
          "x-api-key": this.apiKey,
          "anthropic-version": "2023-06-01"
        },
        body: JSON.stringify({
          model: this.model,
          max_tokens: 1024,
          temperature: 0.2,
          system,
          messages: [{ role: "user", content: user }]
        })
      },
      this.policy
    );
    const content = readAnthropicContent(data);
    if (!content) throw new ProviderError("invalid_provider_response");
    return content;
  }
}

export class AiProviderRegistry {
  private readonly providers = new Map<string, AiProvider>();

  register(provider: AiProvider): this {
    if (this.providers.has(provider.id)) throw new Error(`duplicate_provider:${provider.id}`);
    this.providers.set(provider.id, provider);
    return this;
  }

  require(id: string): AiProvider {
    const provider = this.providers.get(id);
    if (!provider) throw new ProviderError("provider_not_configured");
    return provider;
  }

  list(): ProviderMetadata[] {
    return [...this.providers.values()].map((provider) => provider.metadata);
  }
}

export function createProviderRegistry(env: ProviderEnv): AiProviderRegistry {
  const registry = new AiProviderRegistry().register(new OpenAiCompatibleProvider(env));
  if (env.OPENROUTER_API_KEY?.trim() && env.OPENROUTER_MODEL?.trim()) {
    registry.register(new OpenRouterProvider(env.OPENROUTER_API_KEY.trim(), env.OPENROUTER_MODEL.trim(), env));
  }
  if (env.GEMINI_API_KEY?.trim() && env.GEMINI_MODEL?.trim()) {
    registry.register(new GeminiProvider(env.GEMINI_API_KEY.trim(), env.GEMINI_MODEL.trim(), env));
  }
  if (env.ANTHROPIC_API_KEY?.trim() && env.ANTHROPIC_MODEL?.trim()) {
    registry.register(new AnthropicProvider(env.ANTHROPIC_API_KEY.trim(), env.ANTHROPIC_MODEL.trim(), env));
  }
  return registry;
}

export function isRetryableProviderError(error: unknown): error is ProviderError {
  if (!(error instanceof ProviderError)) return false;
  if (error.code === "provider_timeout") return true;
  return error.code === "provider_error"
    && (error.upstreamStatus === 429 || (typeof error.upstreamStatus === "number" && error.upstreamStatus >= 500));
}

function requireConfiguredHttpsBaseUrl(value: string, allowlistRaw?: string): { baseUrl: string; hostname: string } {
  const base = value.replace(/\/+$/, "");
  let parsed: URL;
  try {
    parsed = new URL(base);
  } catch {
    throw new ProviderError("invalid_provider_url");
  }

  if (
    parsed.protocol !== "https:"
    || parsed.username
    || parsed.password
    || parsed.search
    || parsed.hash
  ) {
    throw new ProviderError("invalid_provider_url");
  }

  const hostname = parsed.hostname.toLowerCase();
  const configuredAllowlist = parseHostAllowlist(allowlistRaw);
  if (configuredAllowlist.size > 0 && !configuredAllowlist.has(hostname)) {
    throw new ProviderError("invalid_provider_url");
  }
  return { baseUrl: base, hostname };
}

function parseHostAllowlist(raw?: string): Set<string> {
  return new Set(
    (raw ?? "")
      .split(",")
      .map((host) => host.trim().toLowerCase())
      .filter(Boolean)
  );
}

function requestPolicy(
  env: Partial<Pick<ProviderEnv, "AI_PROVIDER_TIMEOUT_MS" | "AI_PROVIDER_MAX_RETRIES">>,
  allowedHosts: ReadonlySet<string>
): RequestPolicy {
  return {
    timeoutMs: boundedInteger(env.AI_PROVIDER_TIMEOUT_MS, DEFAULT_TIMEOUT_MS, 1, MAX_TIMEOUT_MS),
    maxRetries: boundedInteger(env.AI_PROVIDER_MAX_RETRIES, DEFAULT_MAX_RETRIES, 0, MAX_RETRIES),
    allowedHosts
  };
}

function boundedInteger(value: string | undefined, fallback: number, min: number, max: number): number {
  if (!value?.trim()) return fallback;
  const parsed = Number(value);
  if (!Number.isInteger(parsed) || parsed < min || parsed > max) return fallback;
  return parsed;
}

async function completeOpenAi(
  base: string,
  apiKey: string,
  model: string,
  system: string,
  user: string,
  policy: RequestPolicy
): Promise<string> {
  const data = await fetchJson(
    `${base}/chat/completions`,
    {
      method: "POST",
      headers: { "Content-Type": "application/json", "Authorization": `Bearer ${apiKey}` },
      body: JSON.stringify({
        model,
        temperature: 0.2,
        messages: [{ role: "system", content: system }, { role: "user", content: user }]
      })
    },
    policy
  );
  const content = readMessageContent(data);
  if (!content) throw new ProviderError("invalid_provider_response");
  return content;
}

async function fetchJson(url: string, init: RequestInit, policy: RequestPolicy): Promise<unknown> {
  requireAllowedHttpsUrl(url, policy.allowedHosts);

  for (let attempt = 0; attempt <= policy.maxRetries; attempt += 1) {
    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), policy.timeoutMs);
    try {
      const upstream = await fetch(url, { ...init, signal: controller.signal, redirect: "error" });
      if (!upstream.ok) {
        const error = new ProviderError("provider_error", upstream.status);
        if (attempt < policy.maxRetries && isRetryableProviderError(error)) continue;
        throw error;
      }
      try {
        return await upstream.json();
      } catch {
        throw new ProviderError("invalid_provider_response");
      }
    } catch (error) {
      const safeError = controller.signal.aborted
        ? new ProviderError("provider_timeout")
        : error instanceof ProviderError
          ? error
          : new ProviderError("provider_error");
      if (attempt < policy.maxRetries && isRetryableProviderError(safeError)) continue;
      throw safeError;
    } finally {
      clearTimeout(timer);
    }
  }

  throw new ProviderError("provider_error");
}

function requireAllowedHttpsUrl(value: string, allowedHosts: ReadonlySet<string>): void {
  let parsed: URL;
  try {
    parsed = new URL(value);
  } catch {
    throw new ProviderError("invalid_provider_url");
  }
  if (parsed.protocol !== "https:" || !allowedHosts.has(parsed.hostname.toLowerCase())) {
    throw new ProviderError("invalid_provider_url");
  }
}

function readMessageContent(data: unknown): string | null {
  if (!data || typeof data !== "object") return null;
  const choices = (data as { choices?: unknown }).choices;
  if (!Array.isArray(choices) || choices.length === 0) return null;
  const message = choices[0] && typeof choices[0] === "object"
    ? (choices[0] as { message?: unknown }).message
    : null;
  const content = message && typeof message === "object"
    ? (message as { content?: unknown }).content
    : null;
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
  const text = parts
    .map((part) => part && typeof part === "object" ? (part as { text?: unknown }).text : null)
    .filter((value): value is string => typeof value === "string")
    .join("")
    .trim();
  return text || null;
}

function readAnthropicContent(data: unknown): string | null {
  if (!data || typeof data !== "object") return null;
  const content = (data as { content?: unknown }).content;
  if (!Array.isArray(content) || content.length === 0) return null;
  const text = content
    .filter((part): part is { type: string; text: string } => Boolean(part && typeof part === "object" && (part as { type?: unknown }).type === "text" && typeof (part as { text?: unknown }).text === "string"))
    .map((part) => part.text)
    .join("")
    .trim();
  return text || null;
}

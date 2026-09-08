export type SummaryInput = {
  title?: string;
  content: string;
};

export type SummaryOutput = {
  summary: string;
  model: string;
};

export interface AiProvider {
  readonly id: string;
  summarize(input: SummaryInput): Promise<SummaryOutput>;
}

export type ProviderEnv = {
  AI_API_KEY: string;
  AI_BASE_URL: string;
  AI_MODEL: string;
};

export class ProviderError extends Error {
  constructor(
    public readonly code: "invalid_provider_url" | "provider_error" | "invalid_provider_response",
    public readonly upstreamStatus?: number
  ) {
    super(code);
  }
}

export class OpenAiCompatibleProvider implements AiProvider {
  readonly id = "openai-compatible";

  constructor(private readonly env: ProviderEnv) {}

  async summarize(input: SummaryInput): Promise<SummaryOutput> {
    const base = this.env.AI_BASE_URL.replace(/\/$/, "");
    let parsed: URL;
    try {
      parsed = new URL(base);
    } catch {
      throw new ProviderError("invalid_provider_url");
    }
    if (parsed.protocol !== "https:") {
      throw new ProviderError("invalid_provider_url");
    }

    const upstream = await fetch(`${base}/chat/completions`, {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        "Authorization": `Bearer ${this.env.AI_API_KEY}`
      },
      body: JSON.stringify({
        model: this.env.AI_MODEL,
        temperature: 0.2,
        messages: [
          {
            role: "system",
            content: "Summarize RSS or podcast content for a Wear OS display. Be concise, factual, and preserve important names, numbers, and dates."
          },
          {
            role: "user",
            content: `${input.title ? `Title: ${input.title}\n\n` : ""}${input.content}`
          }
        ]
      })
    });

    if (!upstream.ok) {
      throw new ProviderError("provider_error", upstream.status);
    }

    let data: unknown;
    try {
      data = await upstream.json();
    } catch {
      throw new ProviderError("invalid_provider_response");
    }

    const summary = readSummary(data);
    if (!summary) {
      throw new ProviderError("invalid_provider_response");
    }

    return { summary, model: this.env.AI_MODEL };
  }
}

export class AiProviderRegistry {
  private readonly providers = new Map<string, AiProvider>();

  register(provider: AiProvider): this {
    if (this.providers.has(provider.id)) {
      throw new Error(`duplicate_provider:${provider.id}`);
    }
    this.providers.set(provider.id, provider);
    return this;
  }

  require(id: string): AiProvider {
    const provider = this.providers.get(id);
    if (!provider) throw new Error(`unknown_provider:${id}`);
    return provider;
  }
}

export function createProviderRegistry(env: ProviderEnv): AiProviderRegistry {
  return new AiProviderRegistry().register(new OpenAiCompatibleProvider(env));
}

function readSummary(data: unknown): string | null {
  if (!data || typeof data !== "object") return null;
  const choices = (data as { choices?: unknown }).choices;
  if (!Array.isArray(choices) || choices.length === 0) return null;
  const first = choices[0];
  if (!first || typeof first !== "object") return null;
  const message = (first as { message?: unknown }).message;
  if (!message || typeof message !== "object") return null;
  const content = (message as { content?: unknown }).content;
  return typeof content === "string" && content.trim() ? content.trim() : null;
}

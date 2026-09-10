import {
  GeminiProvider,
  OpenRouterProvider,
  type AiProvider,
  type ProviderEnv
} from "./provider";

export const BYOK_PROVIDER_IDS = ["openrouter", "gemini"] as const;
export type ByokProviderId = (typeof BYOK_PROVIDER_IDS)[number];

export type ByokProviderConfig = {
  provider?: string;
  model?: string;
  apiKey?: string;
};

export type ByokConfigErrorCode =
  | "byok_provider_unsupported"
  | "byok_model_required"
  | "byok_model_invalid"
  | "byok_api_key_required"
  | "byok_api_key_invalid";

export class ByokConfigError extends Error {
  constructor(public readonly code: ByokConfigErrorCode) {
    super(code);
    this.name = "ByokConfigError";
  }
}

export function createByokProvider(
  config: ByokProviderConfig,
  env: Pick<ProviderEnv, "AI_PROVIDER_TIMEOUT_MS" | "AI_PROVIDER_MAX_RETRIES">
): AiProvider {
  const provider = config.provider?.trim().toLowerCase();
  if (!isByokProviderId(provider)) throw new ByokConfigError("byok_provider_unsupported");

  const model = config.model?.trim();
  if (!model) throw new ByokConfigError("byok_model_required");
  if (model.length > 200 || containsControlCharacters(model)) {
    throw new ByokConfigError("byok_model_invalid");
  }

  const apiKey = config.apiKey?.trim();
  if (!apiKey) throw new ByokConfigError("byok_api_key_required");
  if (apiKey.length > 4096 || containsControlCharacters(apiKey)) {
    throw new ByokConfigError("byok_api_key_invalid");
  }

  return provider === "openrouter"
    ? new OpenRouterProvider(apiKey, model, env)
    : new GeminiProvider(apiKey, model, env);
}

function isByokProviderId(value: string | undefined): value is ByokProviderId {
  return value === "openrouter" || value === "gemini";
}

function containsControlCharacters(value: string): boolean {
  return /[\u0000-\u001F\u007F]/.test(value);
}

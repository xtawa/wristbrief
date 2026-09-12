import {
  AiProviderRegistry,
  AnthropicProvider,
  GeminiProvider,
  OpenAiCompatibleProvider,
  OpenRouterProvider,
  ProviderError,
  createProviderRegistry,
  isRetryableProviderError,
  type AiProvider,
  type ProviderEnv,
  type ProviderMetadata,
  type SummaryInput,
  type SummaryOutput
} from "./provider";
import { ProviderCircuitBreaker } from "./providerCircuitBreaker";
import { D1ProviderConfigStore, resolveProviderSecret, type AiProviderConfigRecord } from "./providerConfigStore";

/**
 * Wraps a built-in provider with the admin config's id and an in-memory
 * circuit breaker. Only circuit-worthy failures (timeout / 429 / 5xx, the same
 * classification as retryable errors) trip the breaker; 400s and configuration
 * errors do not.
 */
class ConfiguredProvider implements AiProvider {
  readonly id: string;
  readonly metadata: ProviderMetadata;

  constructor(
    private readonly config: AiProviderConfigRecord,
    private readonly inner: AiProvider,
    private readonly breaker: ProviderCircuitBreaker,
    private readonly onHealth: (providerId: string, kind: "success" | "failure", state: string) => void
  ) {
    this.id = config.id;
    this.metadata = { ...inner.metadata, id: config.id };
  }

  async summarize(input: SummaryInput): Promise<SummaryOutput> {
    const now = Date.now();
    if (!this.breaker.canAttempt(this.config.id, now, this.config.circuitFailureThreshold, this.config.circuitOpenSeconds)) {
      throw new ProviderError("provider_circuit_open");
    }
    try {
      const output = await this.inner.summarize(input);
      this.breaker.recordSuccess(this.config.id);
      this.onHealth(this.config.id, "success", "CLOSED");
      return output;
    } catch (error) {
      if (isRetryableProviderError(error) || (error instanceof ProviderError && error.code === "provider_timeout")) {
        const state = this.breaker.recordFailure(this.config.id, now, this.config.circuitFailureThreshold, this.config.circuitOpenSeconds);
        this.onHealth(this.config.id, "failure", state);
      }
      throw error;
    }
  }
}

export function createProviderRegistryFromConfigs(
  env: ProviderEnv,
  configs: AiProviderConfigRecord[],
  secretResolver: (ref: string) => string | undefined,
  breaker: ProviderCircuitBreaker,
  onHealth: (providerId: string, kind: "success" | "failure", state: string) => void
): AiProviderRegistry {
  const registry = new AiProviderRegistry();
  const enabled = configs.filter((config) => config.enabled).sort((a, b) => a.priority - b.priority);
  for (const config of enabled) {
    const secret = secretResolver(config.secretRef);
    if (!secret) continue; // secret slot exists in D1 but not in this deployment
    const envOverride: ProviderEnv = {
      ...env,
      AI_API_KEY: secret,
      AI_BASE_URL: config.baseUrl ?? env.AI_BASE_URL,
      AI_MODEL: config.model,
      AI_PROVIDER_TIMEOUT_MS: String(config.timeoutMs),
      AI_PROVIDER_MAX_RETRIES: String(config.maxRetries)
    };
    let inner: AiProvider;
    try {
      switch (config.adapterType) {
        case "openai-compatible":
          inner = new OpenAiCompatibleProvider(envOverride);
          break;
        case "openrouter":
          inner = new OpenRouterProvider(secret, config.model, envOverride);
          break;
        case "gemini":
          inner = new GeminiProvider(secret, config.model, envOverride);
          break;
        case "anthropic":
          inner = new AnthropicProvider(secret, config.model, envOverride);
          break;
      }
    } catch (error) {
      if (error instanceof ProviderError) continue; // invalid config skipped, not fatal
      throw error;
    }
    registry.register(new ConfiguredProvider(config, inner, breaker, onHealth));
  }
  return registry;
}

export type ActiveRegistryEnv = ProviderEnv & {
  ACCOUNT_DB?: D1Database;
};

export const sharedProviderCircuitBreaker = new ProviderCircuitBreaker();

/**
 * The active registry: D1-configured providers when any exist (admin-managed),
 * otherwise the legacy env-derived registry. The migration window keeps both
 * paths alive so production provider selection is never cut over in one step.
 */
export async function createActiveProviderRegistry(env: ActiveRegistryEnv): Promise<AiProviderRegistry> {
  if (env.ACCOUNT_DB) {
    const store = new D1ProviderConfigStore(env.ACCOUNT_DB);
    const configs = await store.list();
    if (configs.some((config) => config.enabled)) {
      const registry = createProviderRegistryFromConfigs(env, configs, (ref) => resolveProviderSecret(env, ref), sharedProviderCircuitBreaker, recordHealthSnapshot(env));
      if (registry.list().length > 0) return registry;
    }
  }
  return createProviderRegistry(env);
}

function recordHealthSnapshot(env: ActiveRegistryEnv): (providerId: string, kind: "success" | "failure", state: string) => void {
  return (providerId, kind, state) => {
    void (async () => {
      try {
        if (!env.ACCOUNT_DB) return;
        const now = Date.now();
        if (kind === "success") {
          await env.ACCOUNT_DB
            .prepare(
              `INSERT INTO ai_provider_health (provider_id, status, consecutive_failures, last_success_at, last_failure_at, circuit_open_until)
               VALUES (?, 'CLOSED', 0, ?, NULL, NULL)
               ON CONFLICT(provider_id) DO UPDATE SET status = 'CLOSED', consecutive_failures = 0, last_success_at = excluded.last_success_at`
            )
            .bind(providerId, now)
            .run();
        } else {
          await env.ACCOUNT_DB
            .prepare(
              `INSERT INTO ai_provider_health (provider_id, status, consecutive_failures, last_success_at, last_failure_at, circuit_open_until)
               VALUES (?, ?, 1, NULL, ?, NULL)
               ON CONFLICT(provider_id) DO UPDATE SET status = excluded.status, consecutive_failures = consecutive_failures + 1, last_failure_at = excluded.last_failure_at`
            )
            .bind(providerId, state, now)
            .run();
        }
      } catch {
        // Health snapshot is best-effort; never fail a provider call for it.
      }
    })();
  };
}

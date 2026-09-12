// @ts-nocheck
import { describe, expect, it } from "vitest";
import { createMigratedTestDb } from "./testDbHelper";
import { D1ProviderConfigStore, validateProviderConfigInput } from "./providerConfigStore";
import { ProviderCircuitBreaker } from "./providerCircuitBreaker";
import { createProviderRegistryFromConfigs, createActiveProviderRegistry } from "./providerRegistryFactory";
import { createProviderRegistry } from "../provider";

const ALLOWED = new Set(["api.openai.com", "api.together.xyz"]);

function validInput(overrides = {}) {
  return {
    id: "primary-llm",
    adapterType: "openai-compatible",
    displayName: "Primary LLM",
    baseUrl: "https://api.openai.com/v1",
    model: "gpt-5-mini",
    secretRef: "AI_PROVIDER_SECRET_1",
    enabled: true,
    priority: 10,
    ...overrides
  };
}

describe("provider config validation", () => {
  it("accepts a valid openai-compatible config within the deployment allowlist", () => {
    const result = validateProviderConfigInput(validInput(), ALLOWED);
    expect(result.ok).toBe(true);
    expect(result.record.baseUrl).toBe("https://api.openai.com/v1");
  });

  it("rejects hosts outside the deployment allowlist (admin can select, never expand)", () => {
    const blocked = validateProviderConfigInput(validInput({ baseUrl: "https://api.evil.example/v1" }), ALLOWED);
    expect(blocked.error).toBe("host_not_allowed");

    // An empty deployment allowlist is unrestricted (same semantics as env providers).
    const open = validateProviderConfigInput(validInput({ baseUrl: "https://api.anywhere.example/v1" }), new Set());
    expect(open.ok).toBe(true);
  });

  it("pins fixed hosts for native adapters and rejects injected base URLs", () => {
    const gemini = validateProviderConfigInput(validInput({ id: "gemini-main", adapterType: "gemini", secretRef: "AI_PROVIDER_SECRET_2", baseUrl: undefined }), ALLOWED);
    expect(gemini.ok).toBe(true);
    expect(gemini.record.baseUrl).toBe("https://generativelanguage.googleapis.com");

    const hijack = validateProviderConfigInput(validInput({ adapterType: "anthropic", baseUrl: "https://evil.example/v1", secretRef: "AI_PROVIDER_SECRET_3" }), ALLOWED);
    expect(hijack.error).toBe("fixed_adapter_host");
  });

  it("rejects invalid ids, adapters, secret slots, and clamps numeric fields", () => {
    expect(validateProviderConfigInput(validInput({ id: "Bad Id!" }), ALLOWED).error).toBe("invalid_provider_id");
    expect(validateProviderConfigInput(validInput({ adapterType: "custom" }), ALLOWED).error).toBe("invalid_adapter_type");
    expect(validateProviderConfigInput(validInput({ secretRef: "AI_PROVIDER_SECRET_11" }), ALLOWED).error).toBe("invalid_secret_ref");
    expect(validateProviderConfigInput(validInput({ secretRef: "AI_API_KEY" }), ALLOWED).error).toBe("invalid_secret_ref");
    expect(validateProviderConfigInput(validInput({ model: "" }), ALLOWED).error).toBe("invalid_model");

    const clamped = validateProviderConfigInput(validInput({ timeoutMs: 999999, maxRetries: 99 }), ALLOWED);
    expect(clamped.record.timeoutMs).toBe(15000);
    expect(clamped.record.maxRetries).toBe(1);
  });
});

describe("provider config store + registry factory", () => {
  it("persists configs and builds a registry where secrets resolve", async () => {
    const { d1 } = createMigratedTestDb();
    const store = new D1ProviderConfigStore(d1);
    await store.create(validateProviderConfigInput(validInput(), ALLOWED).record, 1000, "usr_admin");
    await store.create(
      validateProviderConfigInput(validInput({ id: "backup-llm", adapterType: "openrouter", secretRef: "AI_PROVIDER_SECRET_2", priority: 20, baseUrl: undefined }), ALLOWED).record,
      1000,
      "usr_admin"
    );

    const configs = await store.list();
    expect(configs.map((config) => config.id)).toEqual(["primary-llm", "backup-llm"]);

    const env = {
      AI_API_KEY: "legacy",
      AI_BASE_URL: "https://api.openai.com/v1",
      AI_MODEL: "gpt-5-mini",
      AI_ALLOWED_HOSTS: "api.openai.com,openrouter.ai",
      AI_PROVIDER_SECRET_1: "sk-configured-1"
      // AI_PROVIDER_SECRET_2 deliberately absent
    };
    const registry = createProviderRegistryFromConfigs(env, configs, (ref) => env[ref], new ProviderCircuitBreaker(), () => {});
    // Only the provider whose secret slot is configured is registered.
    expect(registry.list().map((metadata) => metadata.id)).toEqual(["primary-llm"]);
    expect(registry.list()[0].model).toBe("gpt-5-mini");

    const updated = await store.update("primary-llm", { ...validateProviderConfigInput(validInput(), ALLOWED).record, enabled: false }, 2000, "usr_admin");
    expect(updated).toBe(true);
    expect((await store.get("primary-llm")).enabled).toBe(false);
    expect((await store.get("primary-llm")).configRevision).toBe(2);
  });

  it("falls back to the legacy env registry when no D1 config is enabled", async () => {
    const { d1 } = createMigratedTestDb();
    const env = {
      ACCOUNT_DB: d1,
      AI_API_KEY: "legacy-key",
      AI_BASE_URL: "https://api.openai.com/v1",
      AI_MODEL: "gpt-5-mini"
    };
    const registry = await createActiveProviderRegistry(env);
    expect(registry.list().map((metadata) => metadata.id)).toContain("openai-compatible");

    // And prefers D1 configs when one exists.
    const store = new D1ProviderConfigStore(d1);
    await store.create(validateProviderConfigInput(validInput(), ALLOWED).record, 1000, "usr_admin");
    env.AI_PROVIDER_SECRET_1 = "sk-live";
    const active = await createActiveProviderRegistry(env);
    expect(active.list().map((metadata) => metadata.id)).toEqual(["primary-llm"]);
  });
});

describe("provider circuit breaker", () => {
  it("opens after the threshold and re-probes after the window", () => {
    const breaker = new ProviderCircuitBreaker(3, 30);
    expect(breaker.canAttempt("p1", 0)).toBe(true);
    expect(breaker.recordFailure("p1", 1000)).toBe("CLOSED");
    expect(breaker.recordFailure("p1", 2000)).toBe("CLOSED");
    expect(breaker.recordFailure("p1", 3000)).toBe("OPEN");

    expect(breaker.canAttempt("p1", 4000)).toBe(false);
    expect(breaker.canAttempt("p1", 34_000)).toBe(true); // HALF_OPEN probe (30s window from t=3000)
    expect(breaker.recordFailure("p1", 34_500)).toBe("OPEN"); // failed probe re-opens

    expect(breaker.canAttempt("p1", 100_000)).toBe(true);
    breaker.recordSuccess("p1");
    expect(breaker.state("p1")).toBe("CLOSED");
  });

  it("tracks providers independently", () => {
    const breaker = new ProviderCircuitBreaker(1, 30);
    breaker.recordFailure("a", 0);
    expect(breaker.canAttempt("a", 100)).toBe(false);
    expect(breaker.canAttempt("b", 100)).toBe(true);
  });
});

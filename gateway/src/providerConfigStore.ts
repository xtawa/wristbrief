/**
 * D1-backed AI provider configuration. Metadata + secret slot references only:
 * the actual API keys live in Worker secrets (AI_PROVIDER_SECRET_1..10) and are
 * resolved at runtime from env, never read back from or written to D1.
 */
export type ProviderAdapterType = "openai-compatible" | "openrouter" | "gemini" | "anthropic";

export type AiProviderConfigRecord = {
  id: string;
  adapterType: ProviderAdapterType;
  displayName: string;
  baseUrl: string | null;
  model: string;
  secretRef: string;
  enabled: boolean;
  priority: number;
  timeoutMs: number;
  maxRetries: number;
  circuitFailureThreshold: number | null;
  circuitOpenSeconds: number | null;
  configRevision: number;
  updatedAt: number;
  updatedByUserId: string | null;
};

export type ProviderConfigInput = {
  id?: unknown;
  adapterType?: unknown;
  displayName?: unknown;
  baseUrl?: unknown;
  model?: unknown;
  secretRef?: unknown;
  enabled?: unknown;
  priority?: unknown;
  timeoutMs?: unknown;
  maxRetries?: unknown;
  circuitFailureThreshold?: unknown;
  circuitOpenSeconds?: unknown;
};

export type ProviderConfigValidationError = { ok: false; error: string };

const ADAPTER_TYPES: ReadonlySet<string> = new Set(["openai-compatible", "openrouter", "gemini", "anthropic"]);
const SECRET_SLOT_PATTERN = /^AI_PROVIDER_SECRET_([1-9]|10)$/;

/** Fixed hosts per adapter: admins pick an adapter, they cannot invent hosts. */
const ADAPTER_FIXED_HOSTS: Partial<Record<ProviderAdapterType, string>> = {
  openrouter: "openrouter.ai",
  gemini: "generativelanguage.googleapis.com",
  anthropic: "api.anthropic.com"
};

export function validateProviderConfigInput(
  input: ProviderConfigInput,
  deploymentAllowedHosts: ReadonlySet<string>
): { ok: true; record: Omit<AiProviderConfigRecord, "configRevision" | "updatedAt" | "updatedByUserId"> } | ProviderConfigValidationError {
  const id = typeof input.id === "string" ? input.id.trim() : "";
  if (!/^[a-z0-9][a-z0-9-]{1,48}$/.test(id)) return { ok: false, error: "invalid_provider_id" };

  const adapterType = typeof input.adapterType === "string" ? input.adapterType.trim() : "";
  if (!ADAPTER_TYPES.has(adapterType)) return { ok: false, error: "invalid_adapter_type" };

  const displayName = typeof input.displayName === "string" ? input.displayName.trim() : "";
  if (displayName.length < 1 || displayName.length > 120) return { ok: false, error: "invalid_display_name" };

  const model = typeof input.model === "string" ? input.model.trim() : "";
  if (model.length < 1 || model.length > 200) return { ok: false, error: "invalid_model" };

  const secretRef = typeof input.secretRef === "string" ? input.secretRef.trim() : "";
  if (!SECRET_SLOT_PATTERN.test(secretRef)) return { ok: false, error: "invalid_secret_ref" };

  let baseUrl: string | null = null;
  if (adapterType === "openai-compatible") {
    const raw = typeof input.baseUrl === "string" ? input.baseUrl.trim() : "";
    if (!raw) return { ok: false, error: "invalid_provider_url" };
    let parsed: URL;
    try {
      parsed = new URL(raw.replace(/\/+$/, ""));
    } catch {
      return { ok: false, error: "invalid_provider_url" };
    }
    if (parsed.protocol !== "https:" || parsed.username || parsed.password || parsed.search || parsed.hash) {
      return { ok: false, error: "invalid_provider_url" };
    }
    const hostname = parsed.hostname.toLowerCase();
    // Two-layer allowlist: openai-compatible can only pick from the deployment
    // allowlist (AI_ALLOWED_HOSTS). Admin can select, never expand.
    if (deploymentAllowedHosts.size > 0 && !deploymentAllowedHosts.has(hostname)) {
      return { ok: false, error: "host_not_allowed" };
    }
    baseUrl = parsed.toString().replace(/\/+$/, "");
  } else {
    const fixedHost = ADAPTER_FIXED_HOSTS[adapterType as ProviderAdapterType] ?? null;
    baseUrl = fixedHost ? `https://${fixedHost}` : null;
    if (input.baseUrl !== undefined && input.baseUrl !== null && input.baseUrl !== baseUrl) {
      return { ok: false, error: "fixed_adapter_host" };
    }
  }

  const enabled = input.enabled === undefined ? true : input.enabled === true || input.enabled === 1;
  const priority = boundedInteger(input.priority, 100, 0, 1000);
  const timeoutMs = boundedInteger(input.timeoutMs, 15000, 1000, 60000);
  const maxRetries = boundedInteger(input.maxRetries, 1, 0, 2);
  const circuitFailureThreshold = optionalBoundedInteger(input.circuitFailureThreshold, 1, 100);
  const circuitOpenSeconds = optionalBoundedInteger(input.circuitOpenSeconds, 5, 3600);

  return {
    ok: true,
    record: { id, adapterType: adapterType as ProviderAdapterType, displayName, baseUrl, model, secretRef, enabled, priority, timeoutMs, maxRetries, circuitFailureThreshold, circuitOpenSeconds }
  };
}

type ProviderConfigRow = {
  id: string;
  adapter_type: string;
  display_name: string;
  base_url: string | null;
  model: string;
  secret_ref: string;
  enabled: number;
  priority: number;
  timeout_ms: number;
  max_retries: number;
  circuit_failure_threshold: number | null;
  circuit_open_seconds: number | null;
  config_revision: number;
  updated_at: number;
  updated_by_user_id: string | null;
};

export class D1ProviderConfigStore {
  constructor(private readonly db: D1Database) {}

  async list(): Promise<AiProviderConfigRecord[]> {
    const result = await this.db
      .prepare("SELECT * FROM ai_provider_configs ORDER BY enabled DESC, priority ASC, id ASC")
      .all<ProviderConfigRow>();
    return (result.results ?? []).map(mapRow);
  }

  async get(id: string): Promise<AiProviderConfigRecord | null> {
    const row = await this.db
      .prepare("SELECT * FROM ai_provider_configs WHERE id = ? LIMIT 1")
      .bind(id)
      .first<ProviderConfigRow>();
    return row ? mapRow(row) : null;
  }

  async create(record: Omit<AiProviderConfigRecord, "configRevision" | "updatedAt" | "updatedByUserId">, now: number, updatedByUserId: string | null): Promise<void> {
    await this.db
      .prepare(
        `INSERT INTO ai_provider_configs (
           id, adapter_type, display_name, base_url, model, secret_ref, enabled, priority,
           timeout_ms, max_retries, circuit_failure_threshold, circuit_open_seconds,
           config_revision, created_at, updated_at, updated_by_user_id
         ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 1, ?, ?, ?)`
      )
      .bind(
        record.id, record.adapterType, record.displayName, record.baseUrl, record.model, record.secretRef,
        record.enabled ? 1 : 0, record.priority, record.timeoutMs, record.maxRetries,
        record.circuitFailureThreshold, record.circuitOpenSeconds, now, now, updatedByUserId
      )
      .run();
  }

  async update(id: string, record: Omit<AiProviderConfigRecord, "configRevision" | "updatedAt" | "updatedByUserId">, now: number, updatedByUserId: string | null): Promise<boolean> {
    const result = await this.db
      .prepare(
        `UPDATE ai_provider_configs SET
           adapter_type = ?, display_name = ?, base_url = ?, model = ?, secret_ref = ?, enabled = ?,
           priority = ?, timeout_ms = ?, max_retries = ?, circuit_failure_threshold = ?, circuit_open_seconds = ?,
           config_revision = config_revision + 1, updated_at = ?, updated_by_user_id = ?
         WHERE id = ?`
      )
      .bind(
        record.adapterType, record.displayName, record.baseUrl, record.model, record.secretRef,
        record.enabled ? 1 : 0, record.priority, record.timeoutMs, record.maxRetries,
        record.circuitFailureThreshold, record.circuitOpenSeconds, now, updatedByUserId, id
      )
      .run();
    return (result.meta.changes ?? 0) > 0;
  }

  async remove(id: string): Promise<boolean> {
    const result = await this.db
      .prepare("DELETE FROM ai_provider_configs WHERE id = ?")
      .bind(id)
      .run();
    await this.db.prepare("DELETE FROM ai_provider_health WHERE provider_id = ?").bind(id).run();
    return (result.meta.changes ?? 0) > 0;
  }
}

function mapRow(row: ProviderConfigRow): AiProviderConfigRecord {
  return {
    id: row.id,
    adapterType: row.adapter_type as ProviderAdapterType,
    displayName: row.display_name,
    baseUrl: row.base_url,
    model: row.model,
    secretRef: row.secret_ref,
    enabled: row.enabled === 1,
    priority: row.priority,
    timeoutMs: row.timeout_ms,
    maxRetries: row.max_retries,
    circuitFailureThreshold: row.circuit_failure_threshold,
    circuitOpenSeconds: row.circuit_open_seconds,
    configRevision: row.config_revision,
    updatedAt: row.updated_at,
    updatedByUserId: row.updated_by_user_id
  };
}

function boundedInteger(value: unknown, fallback: number, min: number, max: number): number {
  const parsed = typeof value === "number" ? value : typeof value === "string" ? Number(value) : NaN;
  if (!Number.isInteger(parsed) || parsed < min || parsed > max) return fallback;
  return parsed;
}

function optionalBoundedInteger(value: unknown, min: number, max: number): number | null {
  if (value === undefined || value === null || value === "") return null;
  const parsed = typeof value === "number" ? value : Number(value);
  if (!Number.isInteger(parsed) || parsed < min || parsed > max) return null;
  return parsed;
}

/** The secret slot names this deployment understands. */
export const PROVIDER_SECRET_SLOTS = Array.from({ length: 10 }, (_, index) => `AI_PROVIDER_SECRET_${index + 1}`);

export function resolveProviderSecret(env: unknown, secretRef: string): string | undefined {
  const value = (env as Record<string, string | undefined>)[secretRef];
  return typeof value === "string" && value.trim().length > 0 ? value.trim() : undefined;
}

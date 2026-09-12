import { D1ProviderConfigStore, PROVIDER_SECRET_SLOTS, resolveProviderSecret, validateProviderConfigInput, type AiProviderConfigRecord } from "../providerConfigStore";
import { createProviderRegistryFromConfigs, sharedProviderCircuitBreaker } from "../providerRegistryFactory";
import { ProviderError, type ProviderEnv } from "../provider";
import type { D1AdminStore } from "./adminStore";
import type { AdminAuditLog } from "./adminAudit";
import { escapeHtml } from "./adminHtml";

export type ProviderAdminEnv = ProviderEnv & {
  ACCOUNT_DB?: D1Database;
};

export function parseDeploymentAllowedHosts(env: ProviderAdminEnv): ReadonlySet<string> {
  return new Set(
    (env.AI_ALLOWED_HOSTS ?? "")
      .split(",")
      .map((host) => host.trim().toLowerCase())
      .filter(Boolean)
  );
}

/**
 * Public projection of a provider config: the secret slot NAME and whether the
 * slot is configured at deployment level. The secret value itself is never
 * serialized here, logged, or returned by any endpoint.
 */
export function providerConfigForDisplay(config: AiProviderConfigRecord, env: ProviderAdminEnv): Record<string, unknown> {
  return {
    id: config.id,
    adapterType: config.adapterType,
    displayName: config.displayName,
    baseUrl: config.baseUrl,
    model: config.model,
    enabled: config.enabled,
    priority: config.priority,
    timeoutMs: config.timeoutMs,
    maxRetries: config.maxRetries,
    circuitFailureThreshold: config.circuitFailureThreshold,
    circuitOpenSeconds: config.circuitOpenSeconds,
    configRevision: config.configRevision,
    updatedAt: config.updatedAt,
    secretRef: config.secretRef,
    secretConfigured: resolveProviderSecret(env, config.secretRef) !== undefined
  };
}

export async function listProviderConfigs(env: ProviderAdminEnv): Promise<{ status: number; body: Record<string, unknown> }> {
  const store = new D1ProviderConfigStore(env.ACCOUNT_DB!);
  const configs = await store.list();
  const healthRows = await env.ACCOUNT_DB!
    .prepare("SELECT provider_id, status, consecutive_failures, last_success_at, last_failure_at, circuit_open_until FROM ai_provider_health")
    .all<{ provider_id: string; status: string; consecutive_failures: number; last_success_at: number | null; last_failure_at: number | null; circuit_open_until: number | null }>();
  const health = new Map((healthRows.results ?? []).map((row) => [row.provider_id, row]));
  return {
    status: 200,
    body: {
      providers: configs.map((config) => ({
        ...providerConfigForDisplay(config, env),
        health: health.get(config.id) ?? { status: "CLOSED", consecutiveFailures: 0, lastSuccessAt: null, lastFailureAt: null, circuitOpenUntil: null }
      })),
      secretSlots: PROVIDER_SECRET_SLOTS.map((slot) => ({ slot, configured: resolveProviderSecret(env, slot) !== undefined }))
    }
  };
}

export async function upsertProviderConfig(
  env: ProviderAdminEnv,
  body: Record<string, unknown>,
  actorUserId: string,
  audit: AdminAuditLog,
  requestId: string | undefined,
  creating: boolean
): Promise<{ status: number; body: Record<string, unknown> }> {
  const store = new D1ProviderConfigStore(env.ACCOUNT_DB!);
  const allowedHosts = parseDeploymentAllowedHosts(env);
  const validated = validateProviderConfigInput(body, allowedHosts);
  if (!validated.ok) return { status: 400, body: { error: validated.error } };

  const record = validated.record;
  const before = creating ? null : await store.get(record.id);
  if (creating && before) return { status: 409, body: { error: "provider_already_exists" } };
  if (!creating && !before) return { status: 404, body: { error: "not_found" } };

  const now = Date.now();
  if (creating) {
    await store.create(record, now, actorUserId);
  } else {
    await store.update(record.id, record, now, actorUserId);
  }
  // Audit before/after carry the secret slot reference (a version name) only.
  await audit.record({
    actorUserId,
    action: creating ? "provider_created" : "provider_updated",
    targetType: "ai_provider",
    targetId: record.id,
    before: before ? { enabled: before.enabled, secretRef: before.secretRef, model: before.model, priority: before.priority } : undefined,
    after: { enabled: record.enabled, secretRef: record.secretRef, model: record.model, priority: record.priority },
    requestId
  });
  return { status: creating ? 201 : 200, body: { provider: providerConfigForDisplay((await store.get(record.id))!, env) } };
}

export async function deleteProviderConfig(
  env: ProviderAdminEnv,
  providerId: string,
  actorUserId: string,
  audit: AdminAuditLog,
  requestId: string | undefined
): Promise<{ status: number; body: Record<string, unknown> }> {
  const store = new D1ProviderConfigStore(env.ACCOUNT_DB!);
  const before = await store.get(providerId);
  if (!before) return { status: 404, body: { error: "not_found" } };
  await store.remove(providerId);
  await audit.record({
    actorUserId,
    action: "provider_deleted",
    targetType: "ai_provider",
    targetId: providerId,
    before: { enabled: before.enabled, secretRef: before.secretRef, model: before.model },
    requestId
  });
  return { status: 200, body: { deleted: true } };
}

/** Minimal live probe: one tiny summarize call through the guarded registry. */
export async function providerHealthCheck(env: ProviderAdminEnv, providerId: string): Promise<{ status: number; body: Record<string, unknown> }> {
  const store = new D1ProviderConfigStore(env.ACCOUNT_DB!);
  const config = await store.get(providerId);
  if (!config) return { status: 404, body: { error: "not_found" } };
  const configs = await store.list();
  const registry = createProviderRegistryFromConfigs(
    env,
    configs,
    (ref) => resolveProviderSecret(env, ref),
    sharedProviderCircuitBreaker,
    () => {}
  );
  try {
    const provider = registry.require(providerId);
    await provider.summarize({ title: "health check", content: "Reply with the single word: OK" });
    return { status: 200, body: { healthy: true } };
  } catch (error) {
    const code = error instanceof ProviderError ? error.code : "provider_error";
    return { status: 502, body: { healthy: false, error: code } };
  }
}

export async function providerAdminPage(env: ProviderAdminEnv): Promise<string> {
  const { body } = await listProviderConfigs(env);
  const providers = (body.providers as Array<Record<string, unknown>>) ?? [];
  const slots = (body.secretSlots as Array<{ slot: string; configured: boolean }>) ?? [];
  const rows = providers
    .map((provider) => {
      const health = provider.health as Record<string, unknown>;
      return `<tr>
        <td><code>${escapeHtml(String(provider.id))}</code></td>
        <td>${escapeHtml(String(provider.displayName))}</td>
        <td>${escapeHtml(String(provider.adapterType))}</td>
        <td>${escapeHtml(String(provider.model))}</td>
        <td>${provider.enabled ? "enabled" : "disabled"}</td>
        <td>${escapeHtml(String(provider.secretRef))}${provider.secretConfigured ? "" : " <em>(slot not set)</em>"}</td>
        <td>${escapeHtml(String(health?.status ?? "CLOSED"))}</td>
      </tr>`;
    })
    .join("\n");
  const slotRows = slots
    .map((slot) => `<li><code>${escapeHtml(slot.slot)}</code> — ${slot.configured ? "configured" : "not set"}</li>`)
    .join("\n");
  return `<h1>AI providers</h1>
    <div class="card">
      <h2>Secret slots</h2>
      <p class="muted">API keys live in Worker secrets only. This page shows whether a slot is configured, never the key. Rotate by creating a new slot, switching the provider's secret reference, and deleting the old slot.</p>
      <ul>${slotRows}</ul>
    </div>
    <div class="card">
      <h2>Providers</h2>
      ${rows ? `<table>
        <tr><th>Id</th><th>Name</th><th>Adapter</th><th>Model</th><th>Status</th><th>Secret</th><th>Circuit</th></tr>
        ${rows}
      </table>` : "<p class=\"muted\">No providers configured; the deployment falls back to the legacy environment configuration.</p>"}
    </div>
    <p><a href="/admin">Back to dashboard</a></p>`;
}

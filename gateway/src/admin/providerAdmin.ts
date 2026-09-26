import { D1ProviderConfigStore, PROVIDER_SECRET_SLOTS, resolveProviderSecret, validateProviderConfigInput, type AiProviderConfigRecord } from "../providerConfigStore";
import { createProviderRegistryFromConfigs, sharedProviderCircuitBreaker } from "../providerRegistryFactory";
import { ProviderError, type ProviderEnv } from "../provider";
import type { D1AdminStore } from "./adminStore";
import type { AdminAuditLog } from "./adminAudit";
import { escapeHtml } from "./adminHtml";
import { banner, checkboxField, errorSummary, formSecurityFields, selectField, submitButton, textField } from "./adminForms";
import { invalidRequest, type FieldError } from "./adminValidation";
import {
  classifyProviderFailure,
  connectivityKey,
  describeConnectivity,
  readConnectivityChecks,
  recordConnectivityCheck,
  result,
  type ConnectivityResult,
  type StoredConnectivityCheck
} from "./adminConnectivity";

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
  const checks = await readConnectivityChecks(env);
  return {
    status: 200,
    body: {
      providers: configs.map((config) => ({
        ...providerConfigForDisplay(config, env),
        health: health.get(config.id) ?? { status: "CLOSED", consecutiveFailures: 0, lastSuccessAt: null, lastFailureAt: null, circuitOpenUntil: null },
        // Last stored connectivity verdict: "enabled" is a flag, this is evidence.
        lastCheck: checks.get(connectivityKey("ai_provider", config.id)) ?? null
      })),
      secretSlots: PROVIDER_SECRET_SLOTS.map((slot) => ({ slot, configured: resolveProviderSecret(env, slot) !== undefined }))
    }
  };
}

const ADAPTER_TYPES = ["openai-compatible", "openrouter", "gemini", "anthropic"] as const;

/**
 * Field-level projection of the provider validator's error codes, so the JSON
 * API and the HTML form report the same field and the same reason.
 *
 * The top-level `error` keeps the domain-specific code this endpoint has always
 * returned (`host_not_allowed`, `invalid_provider_url`, …): an operator
 * debugging a refused host needs to see that the host was refused, not that the
 * request was malformed. The same reason is repeated on the named field entry,
 * so the structured form and the existing contract agree.
 */
export function providerValidationField(code: string): FieldError {
  switch (code) {
    case "invalid_provider_id":
      return { field: "id", reason: "invalid_format", format: "lower-case id (2-49 characters, a-z 0-9 and -)" };
    case "invalid_adapter_type":
      return { field: "adapterType", reason: "invalid_enum", allowed: [...ADAPTER_TYPES] };
    case "invalid_display_name":
      return { field: "displayName", reason: "invalid_format", format: "name of 1 to 120 characters" };
    case "invalid_model":
      return { field: "model", reason: "invalid_format", format: "model name of 1 to 200 characters" };
    case "invalid_secret_ref":
      return { field: "secretRef", reason: "invalid_enum", allowed: [...PROVIDER_SECRET_SLOTS] };
    case "invalid_provider_url":
      return { field: "baseUrl", reason: "invalid_format", format: "https URL without credentials, query or fragment" };
    case "host_not_allowed":
      return {
        field: "baseUrl",
        reason: "host_not_allowed",
        hint: "This host is not in the deployment allowlist (AI_ALLOWED_HOSTS), so it cannot be used."
      };
    case "fixed_adapter_host":
      return { field: "baseUrl", reason: "not_allowed", hint: "This adapter always uses its own fixed host." };
    default:
      return { field: "provider", reason: code };
  }
}

export function providerValidationFailure(code: string): { status: number; body: Record<string, unknown> } {
  const field = providerValidationField(code);
  return {
    status: 400,
    body: {
      ...invalidRequest([field]),
      // Domain-specific code stays the top-level `error` for compatibility, and
      // is discoverable at `code` and at `fields[0].reason`.
      error: code,
      code,
      reason: field.reason
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
  if (!validated.ok) return providerValidationFailure(validated.error);

  const record = validated.record;
  const before = creating ? null : await store.get(record.id);
  if (creating && before) return { status: 409, body: { error: "provider_already_exists", message: `A provider with id ${record.id} already exists.` } };
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
  if (env.ACCOUNT_DB) {
    await env.ACCOUNT_DB.prepare("DELETE FROM admin_connectivity_checks WHERE target_type = 'ai_provider' AND target_id = ?")
      .bind(providerId)
      .run();
  }
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

export type ProviderHealthCheckOutcome = {
  status: number;
  body: Record<string, unknown>;
  check?: StoredConnectivityCheck;
};

/**
 * Live connectivity check for one configured provider.
 *
 * The probe runs the guarded registry's `summarize` path, which is the strongest
 * signal available here: it proves the host answers, the credential is accepted,
 * and the response shape is usable. The verdict is classified into
 * ok / auth_rejected / unreachable / upstream_error / not_configured / disabled
 * and stored in admin_connectivity_checks so the page can show the last real
 * result rather than assuming an enabled provider works.
 *
 * The API key is read from the deployment secret slot and never appears in the
 * response, the stored row, or the audit log.
 */
export async function providerHealthCheck(
  env: ProviderAdminEnv,
  providerId: string,
  actorUserId?: string | null
): Promise<ProviderHealthCheckOutcome> {
  const store = new D1ProviderConfigStore(env.ACCOUNT_DB!);
  const config = await store.get(providerId);
  if (!config) return { status: 404, body: { error: "not_found" } };

  if (!config.enabled) {
    return finish(env, providerId, result("disabled", { detail: "provider_disabled" }), actorUserId, 409);
  }
  if (resolveProviderSecret(env, config.secretRef) === undefined) {
    return finish(env, providerId, result("not_configured", { detail: "secret_slot_empty" }), actorUserId, 409);
  }

  const configs = await store.list();
  const registry = createProviderRegistryFromConfigs(
    env,
    configs,
    (ref) => resolveProviderSecret(env, ref),
    sharedProviderCircuitBreaker,
    () => {}
  );
  const startedAt = Date.now();
  try {
    const provider = registry.require(providerId);
    await provider.summarize({ title: "health check", content: "Reply with the single word: OK" });
    return finish(env, providerId, result("ok", { latencyMs: Date.now() - startedAt }), actorUserId, 200);
  } catch (error) {
    const classified = classifyProviderFailure(error);
    classified.latencyMs = Math.max(classified.latencyMs, Date.now() - startedAt);
    const httpStatus = error instanceof ProviderError ? error.upstreamStatus : undefined;
    return finish(
      env,
      providerId,
      { ...classified, httpStatus: classified.httpStatus ?? httpStatus ?? null },
      actorUserId,
      502
    );
  }
}

async function finish(
  env: ProviderAdminEnv,
  providerId: string,
  classified: ConnectivityResult,
  actorUserId: string | null | undefined,
  status: number
): Promise<ProviderHealthCheckOutcome> {
  const checkedAt = await recordConnectivityCheck(env, {
    targetType: "ai_provider",
    targetId: providerId,
    result: classified,
    actorUserId: actorUserId ?? null
  });
  const check: StoredConnectivityCheck = {
    ...classified,
    targetType: "ai_provider",
    targetId: providerId,
    checkedAt
  };
  return {
    status,
    check,
    body: {
      healthy: classified.status === "ok",
      status: classified.status,
      reachable: classified.reachable,
      authenticated: classified.authenticated,
      httpStatus: classified.httpStatus,
      latencyMs: classified.latencyMs,
      detail: classified.detail,
      message: describeConnectivity(classified),
      checkedAt,
      stored: Boolean(env.ACCOUNT_DB),
      providerId,
      ...(classified.status === "ok" ? {} : { error: classified.status })
    }
  };
}

export type ProviderAdminPageOptions = {
  formToken: string;
  errors?: FieldError[];
  values?: Record<string, string> | null;
  bannerHtml?: string;
  checkResult?: { providerId: string; check: StoredConnectivityCheck } | null;
};

/**
 * Providers page. Each row carries real forms: an edit form, a connectivity
 * check form, and a delete form, so the whole page is usable with the keyboard
 * and without JavaScript.
 */
export async function providerAdminPage(env: ProviderAdminEnv, options: ProviderAdminPageOptions): Promise<string> {
  const { body } = await listProviderConfigs(env);
  const providers = (body.providers as Array<Record<string, unknown>>) ?? [];
  const slots = (body.secretSlots as Array<{ slot: string; configured: boolean }>) ?? [];
  const values = options.values ?? null;
  const slotRows = slots
    .map((slot) => `<li><code>${escapeHtml(slot.slot)}</code> — ${slot.configured ? "configured" : "<span class=\"muted\">not set</span>"}</li>`)
    .join("\n");

  const rows = providers
    .map((provider) => providerRow(provider, options))
    .join("\n");

  const bannerFor = (provider: Record<string, unknown>) => {
    if (provider.secretConfigured) return "";
    return banner("warning", `Provider ${String(provider.id)} references ${String(provider.secretRef)}, which is not set. It cannot serve requests.`);
  };
  const warnings = providers.map(bannerFor).filter(Boolean).join("");

  return `<h1>AI providers</h1>
${options.bannerHtml ?? ""}
${options.errors ? errorSummary(options.errors) : ""}
<div class="card">
  <h2>Secret slots</h2>
  <p class="muted">API keys live in encrypted server settings only. This page shows whether a slot is configured, never the key. Rotate by saving a new value in the same slot.</p>
  <ul>${slotRows}</ul>
  <p><a href="/admin/provider-keys">Save an API key</a></p>
</div>
${warnings}
<div class="card">
  <h2>Configured providers</h2>
  ${
    rows ||
    `<p class="muted">No providers are configured yet. The deployment falls back to the legacy environment configuration.</p>`
  }
</div>
<div class="card">
  <h2>Add a provider</h2>
  <p class="muted">An enabled provider is only used if its secret slot is configured.</p>
  <form method="post" action="/admin/providers">
    ${formSecurityFields(options.formToken)}
    <div class="grid">
      ${textField({ name: "id", label: "Provider id", value: values?.id ?? "", errors: options.errors, required: true, hint: "Lower-case letters, digits and dashes." })}
      ${selectField({ name: "adapterType", label: "Adapter", value: values?.adapterType ?? "openai-compatible", options: ADAPTER_TYPES.map((value) => ({ value, label: value })), errors: options.errors })}
      ${textField({ name: "displayName", label: "Display name", value: values?.displayName ?? "", errors: options.errors, required: true, maxLength: 120 })}
      ${textField({ name: "model", label: "Model", value: values?.model ?? "", errors: options.errors, required: true, maxLength: 200 })}
      ${textField({ name: "baseUrl", label: "Base URL", value: values?.baseUrl ?? "", errors: options.errors, type: "text", hint: "Required for openai-compatible. The host must be in AI_ALLOWED_HOSTS." })}
      ${selectField({ name: "secretRef", label: "Secret slot", value: values?.secretRef ?? slots[0]?.slot ?? "AI_PROVIDER_SECRET_1", options: slots.map((slot) => ({ value: slot.slot, label: slot.slot })), errors: options.errors })}
      ${textField({ name: "priority", label: "Priority", value: values?.priority ?? "100", errors: options.errors, type: "number", inputMode: "numeric", min: 0, max: 1000 })}
    </div>
    ${checkboxField({ name: "enabled", label: "Enabled", checked: values ? values.enabled === "1" : true, errors: options.errors })}
    <p class="actions">${submitButton("Create provider")}</p>
  </form>
</div>
<p><a href="/admin">Back to dashboard</a></p>`;
}

function providerRow(provider: Record<string, unknown>, options: ProviderAdminPageOptions): string {
  const id = String(provider.id);
  const idPrefix = `p-${id.replace(/[^A-Za-z0-9]/g, "")}-`;
  const check = (provider.lastCheck ?? null) as StoredConnectivityCheck | null;
  const isErrorRow = Boolean(options.errors?.length) && options.values?.id === id;
  const errors = isErrorRow ? options.errors ?? [] : [];
  const justChecked = options.checkResult?.providerId === id ? options.checkResult.check : null;
  const shown = justChecked ?? check;
  const checkLine = shown
    ? `<p class="muted">Last check: <code>${escapeHtml(shown.status)}</code> at ${escapeHtml(shown.checkedAt)} — ${escapeHtml(
        describeConnectivity(shown)
      )}${shown.httpStatus ? ` (HTTP ${shown.httpStatus})` : ""}</p>`
    : `<p class="muted">Never checked.</p>`;

  return `<section class="row" aria-labelledby="${escapeHtml(idPrefix)}title">
  <header>
    <h3 id="${escapeHtml(idPrefix)}title">${escapeHtml(String(provider.displayName ?? id))}</h3>
    <p class="muted"><code class="wrap">${escapeHtml(id)}</code> · ${escapeHtml(String(provider.adapterType ?? ""))} · ${
      provider.enabled ? "enabled" : "disabled"
    } · slot ${escapeHtml(String(provider.secretRef ?? ""))}${provider.secretConfigured ? "" : " (not set)"}</p>
  </header>
  ${checkLine}
  <details>
    <summary>Edit provider</summary>
    <form method="post" action="/admin/providers/${encodeURIComponent(id)}">
      ${formSecurityFields(options.formToken)}
      <div class="grid">
        ${textField({ name: "displayName", label: "Display name", value: String(provider.displayName ?? ""), errors, idPrefix, required: true, maxLength: 120 })}
        ${textField({ name: "model", label: "Model", value: String(provider.model ?? ""), errors, idPrefix, required: true, maxLength: 200 })}
        ${textField({ name: "baseUrl", label: "Base URL", value: String(provider.baseUrl ?? ""), errors, idPrefix })}
        ${selectField({
          name: "secretRef",
          label: "Secret slot",
          value: String(provider.secretRef ?? ""),
          options: PROVIDER_SECRET_SLOTS.map((slot) => ({ value: slot, label: slot })),
          errors,
          idPrefix
        })}
        ${textField({ name: "priority", label: "Priority", value: String(provider.priority ?? 100), errors, idPrefix, type: "number", inputMode: "numeric", min: 0, max: 1000 })}
      </div>
      ${checkboxField({ name: "enabled", label: "Enabled", checked: provider.enabled === true, errors, idPrefix })}
      <input type="hidden" name="adapterType" value="${escapeHtml(String(provider.adapterType ?? ""))}">
      <p class="actions">${submitButton("Save provider")}</p>
    </form>
  </details>
  <form method="post" action="/admin/providers/${encodeURIComponent(id)}/check">
    ${formSecurityFields(options.formToken)}
    <p class="actions">${submitButton("Check connectivity", { secondary: true })}</p>
  </form>
  <form method="post" action="/admin/providers/${encodeURIComponent(id)}/delete">
    ${formSecurityFields(options.formToken)}
    <p class="actions">${submitButton("Delete provider", { secondary: true })}</p>
  </form>
</section>`;
}

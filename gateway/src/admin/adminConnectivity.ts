/**
 * Connectivity verification for the configured model providers and the STT/TTS
 * presets.
 *
 * "Enabled" is a database flag, not evidence that a provider answers. These
 * probes make a real authenticated request, classify the outcome
 * (ok / auth_rejected / unreachable / upstream_error / not_configured /
 * disabled), and store the last result so the page can show a fact instead of an
 * assumption. Secret material is read from `env` and never returned, stored in
 * the check row, or written to the audit log: a result row carries only a status
 * enum, an optional upstream HTTP status, a latency, and a short non-secret
 * detail code.
 */
import { ProviderError } from "../provider";

export type ConnectivityStatus = "ok" | "auth_rejected" | "unreachable" | "upstream_error" | "not_configured" | "disabled";

export type ConnectivityTargetType = "ai_provider" | "audio_provider";

export type ConnectivityResult = {
  status: ConnectivityStatus;
  httpStatus: number | null;
  latencyMs: number;
  detail: string | null;
  reachable: boolean;
  authenticated: boolean;
};

export type StoredConnectivityCheck = ConnectivityResult & {
  targetType: ConnectivityTargetType;
  targetId: string;
  checkedAt: string;
};

export type ConnectivityEnv = {
  ACCOUNT_DB?: D1Database;
  /** Injection point for tests; production always uses the global fetch. */
  ADMIN_FETCH?: typeof fetch;
};

/** Secret slots live as extra string properties on the runtime env object. */
function secretValue(env: ConnectivityEnv, secretRef: string): string | undefined {
  const value = (env as Record<string, unknown>)[secretRef];
  return typeof value === "string" && value.trim().length > 0 ? value.trim() : undefined;
}

const PROBE_TIMEOUT_MS = 8000;

export type AudioAdapter = "mimo" | "deepgram" | "openai";

/**
 * Fixed probe endpoints. The host comes from this table, never from an admin
 * field, so a connectivity check can never be pointed at an arbitrary address.
 */
const AUDIO_PROBE_ENDPOINTS: Record<AudioAdapter, { url: string; headers: (key: string) => Record<string, string> }> = {
  openai: {
    url: "https://api.openai.com/v1/models",
    headers: (key) => ({ Authorization: `Bearer ${key}` })
  },
  deepgram: {
    url: "https://api.deepgram.com/v1/projects",
    headers: (key) => ({ Authorization: `Token ${key}` })
  },
  mimo: {
    url: "https://api.xiaomimimo.com/v1/models",
    headers: (key) => ({ "api-key": key })
  }
};

export function hasSecret(env: ConnectivityEnv, secretRef: string): boolean {
  return secretValue(env, secretRef) !== undefined;
}

/**
 * A real authenticated request to a fixed endpoint for the preset's adapter.
 * 2xx means reachable and authenticated; 401/403 means reachable but rejected.
 */
export async function probeAudioPreset(
  env: ConnectivityEnv,
  config: { id: string; adapter: string; secret_ref: string }
): Promise<ConnectivityResult> {
  const endpoint = AUDIO_PROBE_ENDPOINTS[config.adapter as AudioAdapter];
  if (!endpoint) {
    return result("upstream_error", { detail: "adapter_unknown" });
  }
  if (!hasSecret(env, config.secret_ref)) {
    return result("not_configured", { detail: "secret_slot_empty" });
  }
  const key = secretValue(env, config.secret_ref)!;
  const fetchFn = env.ADMIN_FETCH ?? fetch;
  const startedAt = Date.now();
  try {
    const response = await fetchFn(endpoint.url, {
      method: "GET",
      headers: { ...endpoint.headers(key), Accept: "application/json" },
      signal: AbortSignal.timeout(PROBE_TIMEOUT_MS)
    });
    const latencyMs = Date.now() - startedAt;
    if (response.ok) return result("ok", { httpStatus: response.status, latencyMs });
    if (response.status === 401 || response.status === 403) {
      return result("auth_rejected", { httpStatus: response.status, latencyMs });
    }
    return result("upstream_error", {
      httpStatus: response.status,
      latencyMs,
      detail: response.status === 404 ? "probe_endpoint_unavailable" : `http_${response.status}`
    });
  } catch (error) {
    return result("unreachable", { latencyMs: Date.now() - startedAt, detail: errorDetail(error) });
  }
}

/**
 * Classification for a probe that runs through the guarded AI provider registry.
 * `summarize` is the strongest available signal: it proves the host is
 * reachable, the key is accepted, and the response shape is usable.
 */
export function classifyProviderFailure(error: unknown): ConnectivityResult {
  if (error instanceof ProviderError) {
    if (error.code === "provider_timeout") {
      return result("unreachable", { detail: "provider_timeout" });
    }
    if (error.upstreamStatus === 401 || error.upstreamStatus === 403) {
      return result("auth_rejected", { httpStatus: error.upstreamStatus });
    }
    if (error.code === "provider_not_configured") {
      return result("not_configured", { detail: "provider_not_configured" });
    }
    if (error.code === "provider_circuit_open") {
      return result("upstream_error", { detail: "circuit_open" });
    }
    if (error.upstreamStatus !== undefined) {
      return result("upstream_error", { httpStatus: error.upstreamStatus, detail: `http_${error.upstreamStatus}` });
    }
    return result("unreachable", { detail: error.code });
  }
  return result("unreachable", { detail: errorDetail(error) });
}

export function result(
  status: ConnectivityStatus,
  extra: { httpStatus?: number | null; latencyMs?: number; detail?: string | null } = {}
): ConnectivityResult {
  const reachable = status === "ok" || status === "auth_rejected" || status === "upstream_error";
  return {
    status,
    httpStatus: extra.httpStatus ?? null,
    latencyMs: Math.max(0, Math.round(extra.latencyMs ?? 0)),
    detail: extra.detail ?? null,
    reachable,
    authenticated: status === "ok"
  };
}

function errorDetail(error: unknown): string {
  const name = error instanceof Error ? error.name : "";
  if (name === "TimeoutError" || name === "AbortError") return "timeout";
  if (/^[A-Za-z][A-Za-z0-9]{1,39}$/.test(name)) return name.replace(/([a-z0-9])([A-Z])/g, "$1_$2").toLowerCase();
  return "network_error";
}

/** Human sentence for a stored status. Never includes provider values. */
export function describeConnectivity(check: { status: ConnectivityStatus; detail?: string | null }): string {
  switch (check.status) {
    case "ok":
      return "Reachable and authenticated.";
    case "auth_rejected":
      return "Reachable, but the provider rejected the credential.";
    case "unreachable":
      return check.detail === "timeout" ? "No answer before the timeout." : "Could not be reached.";
    case "upstream_error":
      return check.detail === "probe_endpoint_unavailable"
        ? "Reachable, but this provider has no probe endpoint, so the credential could not be confirmed."
        : "The provider answered with an error.";
    case "not_configured":
      return check.detail === "secret_slot_empty" ? "No credential saved for this key slot." : "Not configured.";
    case "disabled":
      return "Disabled in the console, so it is not used for requests.";
    default:
      return "Unknown.";
  }
}

export function connectivityKey(targetType: ConnectivityTargetType, targetId: string): string {
  return `${targetType}:${targetId}`;
}

export async function recordConnectivityCheck(
  env: ConnectivityEnv,
  input: { targetType: ConnectivityTargetType; targetId: string; result: ConnectivityResult; actorUserId?: string | null }
): Promise<string> {
  const checkedAt = new Date().toISOString();
  if (!env.ACCOUNT_DB) return checkedAt;
  await env.ACCOUNT_DB.prepare(
    `INSERT INTO admin_connectivity_checks (target_type, target_id, status, http_status, latency_ms, detail, checked_at, checked_by_user_id)
     VALUES (?, ?, ?, ?, ?, ?, ?, ?)
     ON CONFLICT(target_type, target_id) DO UPDATE SET
       status = excluded.status, http_status = excluded.http_status, latency_ms = excluded.latency_ms,
       detail = excluded.detail, checked_at = excluded.checked_at, checked_by_user_id = excluded.checked_by_user_id`
  )
    .bind(
      input.targetType,
      input.targetId,
      input.result.status,
      input.result.httpStatus,
      input.result.latencyMs,
      input.result.detail,
      checkedAt,
      input.actorUserId ?? null
    )
    .run();
  return checkedAt;
}

export async function readConnectivityChecks(env: ConnectivityEnv): Promise<Map<string, StoredConnectivityCheck>> {
  const checks = new Map<string, StoredConnectivityCheck>();
  if (!env.ACCOUNT_DB) return checks;
  const rows = await env.ACCOUNT_DB.prepare(
    "SELECT target_type, target_id, status, http_status, latency_ms, detail, checked_at FROM admin_connectivity_checks"
  ).all<{
    target_type: string;
    target_id: string;
    status: string;
    http_status: number | null;
    latency_ms: number | null;
    detail: string | null;
    checked_at: string;
  }>();
  for (const row of rows.results ?? []) {
    const status = row.status as ConnectivityStatus;
    checks.set(connectivityKey(row.target_type as ConnectivityTargetType, row.target_id), {
      targetType: row.target_type as ConnectivityTargetType,
      targetId: row.target_id,
      status,
      httpStatus: row.http_status,
      latencyMs: row.latency_ms ?? 0,
      detail: row.detail,
      checkedAt: row.checked_at,
      reachable: status === "ok" || status === "auth_rejected" || status === "upstream_error",
      authenticated: status === "ok"
    });
  }
  return checks;
}

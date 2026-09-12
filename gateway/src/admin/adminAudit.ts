export type AuditEntry = {
  actorUserId?: string;
  action: string;
  targetType?: string;
  targetId?: string;
  before?: unknown;
  after?: unknown;
  requestId?: string;
  ipPrefixHash?: string;
};

export type AuditRow = {
  id: string;
  actorUserId: string | null;
  action: string;
  targetType: string | null;
  targetId: string | null;
  beforeJson: string | null;
  afterJson: string | null;
  requestId: string | null;
  createdAt: string;
  ipPrefixHash: string | null;
};

/**
 * Append-only admin audit trail. before/after payloads are caller-supplied and must
 * be secret-free by construction: provider changes record secret_version only, and
 * the writer refuses to serialize keys that look like credentials as a guard rail.
 */
// Credential-shaped keys are refused, with one deliberate exception: the
// secret *version* reference (e.g. "V2") is exactly what provider changes must
// record — it names a Worker secret slot without containing any secret material.
const FORBIDDEN_JSON_KEYS = /(^|[^a-z])(api_?key|secret(?!_version)|password|token|credential|private_?key)([^a-z]|$)/i;
const ALLOWED_JSON_KEYS = new Set(["secret_version", "secretVersion"]);

function sanitizeJson(value: unknown): string | null {
  if (value === undefined || value === null) return null;
  const json = JSON.stringify(value);
  if (typeof value === "object") {
    for (const key of Object.keys(value as Record<string, unknown>)) {
      if (ALLOWED_JSON_KEYS.has(key)) continue;
      if (FORBIDDEN_JSON_KEYS.test(key)) {
        throw new Error(`audit_payload_refused:${key}`);
      }
    }
  }
  return json;
}

export class AdminAuditLog {
  constructor(private readonly db: D1Database) {}

  async record(entry: AuditEntry, nowIso: string = new Date().toISOString()): Promise<void> {
    await this.db
      .prepare(
        "INSERT INTO admin_audit_log (id, actor_user_id, action, target_type, target_id, before_json, after_json, request_id, created_at, ip_prefix_hash) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
      )
      .bind(
        crypto.randomUUID(),
        entry.actorUserId ?? null,
        entry.action,
        entry.targetType ?? null,
        entry.targetId ?? null,
        sanitizeJson(entry.before),
        sanitizeJson(entry.after),
        entry.requestId ?? null,
        nowIso,
        entry.ipPrefixHash ?? null
      )
      .run();
  }

  async list(limit = 50, offset = 0): Promise<AuditRow[]> {
    const clampedLimit = Math.min(Math.max(Math.floor(limit), 1), 200);
    const clampedOffset = Math.max(Math.floor(offset), 0);
    const result = await this.db
      .prepare(
        "SELECT id, actor_user_id, action, target_type, target_id, before_json, after_json, request_id, created_at, ip_prefix_hash FROM admin_audit_log ORDER BY created_at DESC, id DESC LIMIT ? OFFSET ?"
      )
      .bind(clampedLimit, clampedOffset)
      .all<{
        id: string;
        actor_user_id: string | null;
        action: string;
        target_type: string | null;
        target_id: string | null;
        before_json: string | null;
        after_json: string | null;
        request_id: string | null;
        created_at: string;
        ip_prefix_hash: string | null;
      }>();
    return (result.results ?? []).map((row) => ({
      id: row.id,
      actorUserId: row.actor_user_id,
      action: row.action,
      targetType: row.target_type,
      targetId: row.target_id,
      beforeJson: row.before_json,
      afterJson: row.after_json,
      requestId: row.request_id,
      createdAt: row.created_at,
      ipPrefixHash: row.ip_prefix_hash
    }));
  }
}

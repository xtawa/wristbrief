/**
 * Operational overview: what the transcript/artifact workers actually did, and
 * how much managed AI quota each account has consumed this period.
 *
 * Every number comes from a real row. Nothing is estimated, extrapolated, or
 * invented, and the empty-database case is reported explicitly (`noJobs`,
 * `noFailures`, `noQuotaRows`) so the page can say "nothing has run yet" instead
 * of rendering a convincing but false zero-filled dashboard.
 */
import type { AdminAuditLog } from "./adminAudit";

export type JobStatusCount = { status: string; count: number };

export type RecentJobFailure = {
  jobId: string;
  contentId: string;
  artifactType: string;
  language: string;
  status: string;
  errorCode: string | null;
  attempts: number;
  occurredAt: string | null;
};

export type QuotaUsageRow = {
  userId: string;
  email: string | null;
  displayName: string | null;
  plan: string;
  periodKey: string;
  used: number;
  quotaLimit: number;
  remaining: number;
  percentUsed: number;
  hasUsageRow: boolean;
};

export type OperationalOverview = {
  period: string;
  generatedAt: string;
  jobs: {
    total: number;
    byStatus: JobStatusCount[];
    active: number;
    failed: number;
  };
  artifacts: {
    total: number;
    byStatus: JobStatusCount[];
  };
  recentFailures: RecentJobFailure[];
  quota: {
    period: string;
    rows: QuotaUsageRow[];
    totalUsers: number;
    usersWithUsage: number;
    usersWithoutUsage: number;
    totalUsed: number;
  };
  empty: { noJobs: boolean; noFailures: boolean; noQuotaRows: boolean };
};

export type OverviewEnv = {
  ACCOUNT_DB?: D1Database;
  FREE_AI_MONTHLY_LIMIT?: string;
  PRO_AI_MONTHLY_LIMIT?: string;
};

const PERIOD_PATTERN = /^\d{4}-(0[1-9]|1[0-2])$/;
const ACTIVE_STATUSES = new Set(["queued", "running"]);

export function defaultPeriod(now: Date = new Date()): string {
  return now.toISOString().slice(0, 7);
}

/** `period` is either a validated `YYYY-MM` string or the current UTC month. */
export function resolvePeriod(value: unknown, now: Date = new Date()): { period: string; valid: boolean; reason?: string } {
  if (value === undefined || value === null || value === "") return { period: defaultPeriod(now), valid: true };
  if (typeof value !== "string" || !PERIOD_PATTERN.test(value)) {
    return { period: defaultPeriod(now), valid: false, reason: "invalid_format" };
  }
  return { period: value, valid: true };
}

export async function buildOperationalOverview(
  env: OverviewEnv,
  options: { period?: string; failureLimit?: number } = {}
): Promise<OperationalOverview> {
  const period = options.period && PERIOD_PATTERN.test(options.period) ? options.period : defaultPeriod();
  const failureLimit = Math.min(Math.max(Math.floor(options.failureLimit ?? 20), 1), 100);
  const proLimit = numeric(env.PRO_AI_MONTHLY_LIMIT, 0);
  const freeLimit = numeric(env.FREE_AI_MONTHLY_LIMIT, 0);
  const db = env.ACCOUNT_DB;

  const empty: OperationalOverview = {
    period,
    generatedAt: new Date().toISOString(),
    jobs: { total: 0, byStatus: [], active: 0, failed: 0 },
    artifacts: { total: 0, byStatus: [] },
    recentFailures: [],
    quota: { period, rows: [], totalUsers: 0, usersWithUsage: 0, usersWithoutUsage: 0, totalUsed: 0 },
    empty: { noJobs: true, noFailures: true, noQuotaRows: true }
  };
  if (!db) return empty;

  const jobRows = await db
    .prepare("SELECT status, COUNT(*) AS count FROM artifact_jobs GROUP BY status ORDER BY status")
    .all<{ status: string; count: number }>();
  const byStatus = (jobRows.results ?? []).map((row) => ({ status: row.status, count: Number(row.count) || 0 }));
  const total = byStatus.reduce((sum, row) => sum + row.count, 0);

  const artifactRows = await db
    .prepare("SELECT status, COUNT(*) AS count FROM transcript_artifacts GROUP BY status ORDER BY status")
    .all<{ status: string; count: number }>();
  const artifactsByStatus = (artifactRows.results ?? []).map((row) => ({
    status: row.status,
    count: Number(row.count) || 0
  }));

  const failureRows = await db
    .prepare(
      `SELECT id, content_id, artifact_type, language, status, error_code, attempt_count, updated_at
       FROM artifact_jobs
       WHERE status = 'failed' OR (error_code IS NOT NULL AND error_code <> '')
       ORDER BY updated_at DESC, id DESC LIMIT ?`
    )
    .bind(failureLimit)
    .all<{
      id: string;
      content_id: string;
      artifact_type: string;
      language: string;
      status: string;
      error_code: string | null;
      attempt_count: number;
      updated_at: number;
    }>();
  const recentFailures: RecentJobFailure[] = (failureRows.results ?? []).map((row) => ({
    jobId: row.id,
    contentId: row.content_id,
    artifactType: row.artifact_type,
    language: row.language,
    status: row.status,
    errorCode: row.error_code ?? null,
    attempts: Number(row.attempt_count) || 0,
    occurredAt: isoTimestamp(row.updated_at)
  }));

  const quotaRows = await db
    .prepare(
      `SELECT u.id AS id, COALESCE(e.normalized_email, g.email) AS email,
              COALESCE(i.display_name, g.display_name) AS display_name,
              COALESCE(m.plan, 'FREE') AS plan,
              q.used AS used, q.quota_limit AS quota_limit,
              CASE WHEN q.user_id IS NULL THEN 0 ELSE 1 END AS has_usage
       FROM users u
       LEFT JOIN email_credentials e ON e.user_id = u.id
       LEFT JOIN identities i ON i.user_id = u.id AND i.provider = 'email'
       LEFT JOIN identities g ON g.user_id = u.id AND g.provider = 'google'
       LEFT JOIN membership_entitlements m ON m.user_id = u.id
       LEFT JOIN managed_ai_usage q ON q.user_id = u.id AND q.period_key = ?
       WHERE NOT EXISTS (SELECT 1 FROM user_roles r WHERE r.user_id = u.id AND r.role = 'admin')
       ORDER BY COALESCE(q.used, 0) DESC, u.created_at DESC, u.id DESC`
    )
    .bind(period)
    .all<{
      id: string;
      email: string | null;
      display_name: string | null;
      plan: string;
      used: number | null;
      quota_limit: number | null;
      has_usage: number;
    }>();

  const rows: QuotaUsageRow[] = (quotaRows.results ?? []).map((row) => {
    const used = Number(row.used) || 0;
    const limit = row.quota_limit === null || row.quota_limit === undefined
      ? row.plan === "PRO"
        ? proLimit
        : freeLimit
      : Number(row.quota_limit);
    return {
      userId: row.id,
      email: row.email ?? null,
      displayName: row.display_name ?? null,
      plan: row.plan,
      periodKey: period,
      used,
      quotaLimit: limit,
      remaining: Math.max(0, limit - used),
      percentUsed: limit > 0 ? Math.min(100, Math.round((used / limit) * 100)) : used > 0 ? 100 : 0,
      hasUsageRow: row.has_usage === 1
    };
  });

  return {
    period,
    generatedAt: new Date().toISOString(),
    jobs: {
      total,
      byStatus,
      active: byStatus.filter((row) => ACTIVE_STATUSES.has(row.status)).reduce((sum, row) => sum + row.count, 0),
      failed: byStatus.find((row) => row.status === "failed")?.count ?? 0
    },
    artifacts: { total: artifactsByStatus.reduce((sum, row) => sum + row.count, 0), byStatus: artifactsByStatus },
    recentFailures,
    quota: {
      period,
      rows,
      totalUsers: rows.length,
      usersWithUsage: rows.filter((row) => row.hasUsageRow).length,
      usersWithoutUsage: rows.filter((row) => !row.hasUsageRow).length,
      totalUsed: rows.reduce((sum, row) => sum + row.used, 0)
    },
    empty: {
      noJobs: total === 0,
      noFailures: recentFailures.length === 0,
      noQuotaRows: rows.every((row) => !row.hasUsageRow)
    }
  };
}

/** Audit helper kept beside the overview so the page and API record the same thing. */
export async function recordOverviewRead(
  audit: AdminAuditLog,
  input: { actorUserId: string; period: string; requestId: string }
): Promise<void> {
  await audit.record({
    actorUserId: input.actorUserId,
    action: "operational_overview_read",
    targetType: "system_setting",
    targetId: `overview:${input.period}`,
    requestId: input.requestId
  });
}

function numeric(value: string | undefined, fallback: number): number {
  const parsed = Number(value);
  return Number.isFinite(parsed) && parsed >= 0 ? Math.floor(parsed) : fallback;
}

function isoTimestamp(value: number | null | undefined): string | null {
  if (value === null || value === undefined) return null;
  const ms = Number(value);
  if (!Number.isFinite(ms) || ms <= 0) return null;
  return new Date(ms).toISOString();
}

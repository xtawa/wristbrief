/**
 * Paginated, searchable view of the accounts an administrator may edit.
 *
 * Scope is deliberate and narrow: the list shows email identity and admin role
 * read-only, and exposes only display name, active/disabled, FREE/PRO plan and
 * the current month's managed AI quota as editable fields. The sole
 * administrator is excluded from the list entirely, so there is no code path
 * here that could demote or delete it.
 *
 * Ordering is fixed (`created_at DESC, id DESC`) so a page window is stable
 * across requests even when several accounts share a timestamp; `total` is
 * computed with the same predicate as the page query.
 */
import { invalidRequest, type FieldError } from "./adminValidation";
import type { OverviewEnv } from "./adminOverview";

export const USER_PAGE_LIMIT_DEFAULT = 20;
export const USER_PAGE_LIMIT_MAX = 100;
export const USER_SEARCH_MAX_LENGTH = 120;
/** Hard cap shared by the JSON route, the HTML form and the page widgets. */
export const USER_QUOTA_MAX = 10000;
export const USER_DISPLAY_NAME_MAX = 120;
export const USER_PLAN_VALUES = ["FREE", "PRO"] as const;
export const USER_STATUS_VALUES = ["active", "disabled"] as const;

export type ManagedUser = {
  id: string;
  email: string | null;
  display_name: string | null;
  status: string;
  plan: string;
  quota: number | null;
  created_at: string | null;
};

export type UserPageQuery = { search: string; limit: number; offset: number; limitClamped: boolean };

export type UserPageWindow = {
  limit: number;
  offset: number;
  total: number;
  returned: number;
  hasMore: boolean;
  page: number;
  pageCount: number;
  /** True when the requested limit exceeded the maximum and was reduced. */
  limitClamped: boolean;
  /** True when the offset is past the last row; the page is legitimately empty. */
  offsetOutOfRange: boolean;
  query: string;
  order: string;
};

export type UserPage = { users: ManagedUser[]; page: UserPageWindow };

export type UserPageParams = { ok: true; query: UserPageQuery } | { ok: false; body: ReturnType<typeof invalidRequest> };

/**
 * Reads `q`, `limit` and `offset` from the query string. Absurd page sizes are
 * clamped to USER_PAGE_LIMIT_MAX rather than materialized, and the effective
 * limit is always reported back in `page.limit`.
 */
export function parseUserPageParams(url: URL): UserPageParams {
  const fields: FieldError[] = [];

  const rawLimit = url.searchParams.get("limit");
  let limit = USER_PAGE_LIMIT_DEFAULT;
  let limitClamped = false;
  if (rawLimit !== null && rawLimit !== "") {
    if (!/^\d{1,12}$/.test(rawLimit.trim())) {
      fields.push({ field: "limit", reason: "invalid_type", expected: "integer" });
    } else {
      const parsed = Number(rawLimit.trim());
      if (parsed < 1) {
        fields.push({ field: "limit", reason: "out_of_range", min: 1, max: USER_PAGE_LIMIT_MAX });
      } else if (parsed > USER_PAGE_LIMIT_MAX) {
        limit = USER_PAGE_LIMIT_MAX;
        limitClamped = true;
      } else {
        limit = parsed;
      }
    }
  }

  const rawOffset = url.searchParams.get("offset");
  let offset = 0;
  if (rawOffset !== null && rawOffset !== "") {
    if (!/^\d{1,12}$/.test(rawOffset.trim())) {
      fields.push({ field: "offset", reason: "invalid_type", expected: "integer" });
    } else {
      offset = Number(rawOffset.trim());
    }
  }

  const rawSearch = url.searchParams.get("q") ?? "";
  const search = rawSearch.trim();
  if (search.length > USER_SEARCH_MAX_LENGTH) {
    fields.push({ field: "q", reason: "too_long", max: USER_SEARCH_MAX_LENGTH, length: search.length });
  }

  if (fields.length) return { ok: false, body: invalidRequest(fields) };
  return { ok: true, query: { search, limit, offset, limitClamped } };
}

export const USER_ORDER_SQL = "u.created_at DESC, u.id DESC";

export async function listUserPage(
  db: D1Database,
  env: OverviewEnv,
  query: UserPageQuery
): Promise<UserPage> {
  const proLimit = numeric(env.PRO_AI_MONTHLY_LIMIT, 0);
  const freeLimit = numeric(env.FREE_AI_MONTHLY_LIMIT, 0);
  const like = query.search ? `%${escapeLike(query.search.toLowerCase())}%` : null;
  const searchClause = like
    ? `AND (lower(COALESCE(e.normalized_email, g.email, '')) LIKE ? ESCAPE '\\'
            OR lower(COALESCE(i.display_name, g.display_name, '')) LIKE ? ESCAPE '\\')`
    : "";

  const countStatement = db.prepare(
    `SELECT COUNT(*) AS total FROM users u
     LEFT JOIN email_credentials e ON e.user_id = u.id
     LEFT JOIN identities i ON i.user_id = u.id AND i.provider = 'email'
     LEFT JOIN identities g ON g.user_id = u.id AND g.provider = 'google'
     WHERE NOT EXISTS (SELECT 1 FROM user_roles r WHERE r.user_id = u.id AND r.role = 'admin') ${searchClause}`
  );
  const countRow = await (like ? countStatement.bind(like, like) : countStatement).first<{ total: number }>();
  const total = Number(countRow?.total ?? 0) || 0;

  const pageStatement = db.prepare(
    `SELECT u.id, COALESCE(e.normalized_email, g.email) AS email,
            COALESCE(i.display_name, g.display_name) AS display_name, u.status, u.created_at,
            COALESCE(m.plan, 'FREE') AS plan,
            COALESCE(q.quota_limit, CASE WHEN m.plan = 'PRO' THEN ? ELSE ? END) AS quota
     FROM users u
     LEFT JOIN email_credentials e ON e.user_id = u.id
     LEFT JOIN identities i ON i.user_id = u.id AND i.provider = 'email'
     LEFT JOIN identities g ON g.user_id = u.id AND g.provider = 'google'
     LEFT JOIN membership_entitlements m ON m.user_id = u.id
     LEFT JOIN managed_ai_usage q ON q.user_id = u.id AND q.period_key = strftime('%Y-%m','now')
     WHERE NOT EXISTS (SELECT 1 FROM user_roles r WHERE r.user_id = u.id AND r.role = 'admin') ${searchClause}
     ORDER BY ${USER_ORDER_SQL} LIMIT ? OFFSET ?`
  );
  const bound = like
    ? pageStatement.bind(proLimit, freeLimit, like, like, query.limit, query.offset)
    : pageStatement.bind(proLimit, freeLimit, query.limit, query.offset);
  const rows = await bound.all<ManagedUser>();

  const users = rows.results ?? [];
  const pageCount = Math.max(1, Math.ceil(total / query.limit));
  return {
    users,
    page: {
      limit: query.limit,
      offset: query.offset,
      total,
      returned: users.length,
      hasMore: query.offset + users.length < total,
      page: Math.floor(query.offset / query.limit) + 1,
      pageCount,
      limitClamped: query.limitClamped,
      offsetOutOfRange: total > 0 && users.length === 0 && query.offset > 0,
      query: query.search,
      order: "created_at DESC, id DESC"
    }
  };
}

/** A `users` array is returned unchanged alongside `page`, so existing clients keep working. */
export async function listUsersCompat(db: D1Database, env: OverviewEnv, limit: number): Promise<ManagedUser[]> {
  const page = await listUserPage(db, env, { search: "", limit, offset: 0, limitClamped: false });
  return page.users;
}

function escapeLike(value: string): string {
  return value.replace(/[\\%_]/g, (match) => `\\${match}`);
}

function numeric(value: string | undefined, fallback: number): number {
  const parsed = Number(value);
  return Number.isFinite(parsed) && parsed >= 0 ? Math.floor(parsed) : fallback;
}

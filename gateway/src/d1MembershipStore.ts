import type { BillingStateStore } from "./billingServer";
import type { Entitlement, MembershipStore } from "./membership";

export type DurableMembershipEnv = {
  ACCOUNT_DB?: D1Database;
};

export class D1MembershipStore implements MembershipStore {
  constructor(
    private readonly db: D1Database,
    private readonly periodKey: () => string = currentUtcMonth,
    private readonly now: () => number = Date.now
  ) {}

  async getEntitlement(userId: string): Promise<Entitlement> {
    const row = await this.db.prepare(
      "SELECT plan, source, expires_at FROM membership_entitlements WHERE user_id = ? LIMIT 1"
    ).bind(userId).first<{ plan?: string; source?: string; expires_at?: string | null }>();
    if (!row) return { plan: "FREE", source: "default" };
    if ((row.plan !== "FREE" && row.plan !== "PRO") || !validSource(row.source)) {
      throw new Error("invalid_membership_state");
    }

    const expiresAt = row.expires_at ?? undefined;
    if (expiresAt) {
      const expiryMs = Date.parse(expiresAt);
      if (!Number.isFinite(expiryMs)) throw new Error("invalid_membership_state");
      if (row.plan === "PRO" && expiryMs <= this.now()) {
        return { plan: "FREE", source: row.source, expiresAt };
      }
    }

    return {
      plan: row.plan,
      source: row.source,
      ...(expiresAt ? { expiresAt } : {})
    };
  }

  async getManagedAiQuota(userId: string): Promise<{ limit: number | null; used: number }> {
    const row = await this.db.prepare(
      "SELECT used, quota_limit FROM managed_ai_usage WHERE user_id = ? AND period_key = ? LIMIT 1"
    ).bind(userId, this.periodKey()).first<{ used?: number; quota_limit?: number | null }>();
    if (!row) return { limit: 0, used: 0 };
    const used = Number(row.used);
    const limit = row.quota_limit === null ? null : Number(row.quota_limit);
    if (!Number.isInteger(used) || used < 0 || (limit !== null && (!Number.isInteger(limit) || limit < 0))) {
      throw new Error("invalid_quota_state");
    }
    return { limit, used };
  }

  async incrementManagedAiUsage(userId: string): Promise<void> {
    const result = await this.db.prepare(
      "UPDATE managed_ai_usage SET used = used + 1, updated_at = CURRENT_TIMESTAMP WHERE user_id = ? AND period_key = ? AND (quota_limit IS NULL OR used < quota_limit)"
    ).bind(userId, this.periodKey()).run();
    if ((result.meta.changes ?? 0) !== 1) throw new Error("managed_ai_quota_unavailable");
  }
}

export class D1BillingStateStore implements BillingStateStore {
  constructor(private readonly db: D1Database) {}

  async userForTokenHash(tokenHash: string): Promise<string | null> {
    const row = await this.db.prepare(
      "SELECT user_id FROM play_purchase_bindings WHERE token_hash = ? LIMIT 1"
    ).bind(tokenHash).first<{ user_id?: string }>();
    return row?.user_id ?? null;
  }

  async bindTokenHash(userId: string, tokenHash: string): Promise<"bound" | "already_bound" | "conflict"> {
    const existing = await this.userForTokenHash(tokenHash);
    if (existing === userId) return "already_bound";
    if (existing) return "conflict";
    await this.db.prepare(
      "INSERT OR IGNORE INTO play_purchase_bindings (token_hash, user_id, created_at, updated_at) VALUES (?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)"
    ).bind(tokenHash, userId).run();
    const owner = await this.userForTokenHash(tokenHash);
    if (owner === userId) return "bound";
    if (owner) return "conflict";
    throw new Error("purchase_binding_failed");
  }

  async setBillingEntitlement(userId: string, entitlement: Entitlement): Promise<void> {
    if (entitlement.source !== "billing") throw new Error("billing_entitlement_source_required");
    await this.db.prepare(
      "INSERT INTO membership_entitlements (user_id, plan, source, expires_at, updated_at) VALUES (?, ?, 'billing', ?, CURRENT_TIMESTAMP) ON CONFLICT(user_id) DO UPDATE SET plan = excluded.plan, source = 'billing', expires_at = excluded.expires_at, updated_at = CURRENT_TIMESTAMP"
    ).bind(userId, entitlement.plan, entitlement.expiresAt ?? null).run();
  }
}

export function createConfiguredD1MembershipStore(env: DurableMembershipEnv): MembershipStore | undefined {
  return env.ACCOUNT_DB ? new D1MembershipStore(env.ACCOUNT_DB) : undefined;
}

export function createConfiguredD1BillingStateStore(env: DurableMembershipEnv): BillingStateStore | undefined {
  return env.ACCOUNT_DB ? new D1BillingStateStore(env.ACCOUNT_DB) : undefined;
}

function currentUtcMonth(): string {
  return new Date().toISOString().slice(0, 7);
}

function validSource(value: string | undefined): value is Entitlement["source"] {
  return value === "legacy" || value === "billing" || value === "admin" || value === "test" || value === "default";
}

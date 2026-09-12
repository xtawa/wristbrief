import { describe, expect, it } from "vitest";
import {
  D1BillingStateStore,
  D1MembershipStore,
  createConfiguredD1BillingStateStore,
  createConfiguredD1MembershipStore
} from "./d1MembershipStore";

class FakeStatement {
  values: unknown[] = [];
  constructor(readonly db: FakeD1, readonly sql: string) {}
  bind(...values: unknown[]) { this.values = values; return this as unknown as D1PreparedStatement; }
  async run<T = unknown>() { return this.db.execute(this) as unknown as Promise<D1Result<T>>; }
  async first<T = unknown>() {
    const result = await this.db.execute(this);
    return (result.results[0] ?? null) as T | null;
  }
  async all<T = unknown>() {
    const result = await this.db.execute(this);
    return { results: result.results as T[], meta: result.meta, success: true } as unknown as D1Result<T>;
  }
}

type EntitlementRow = { plan: string; source: string; expiresAt: string | null };
type QuotaRow = { used: number; limit: number | null };
type BindingRow = { userId: string; productId?: string; status?: string; expiresAt?: string | null };

class FakeD1 {
  readonly entitlements = new Map<string, EntitlementRow>();
  readonly quotas = new Map<string, QuotaRow>();
  readonly bindings = new Map<string, BindingRow>();

  prepare(sql: string): D1PreparedStatement {
    return new FakeStatement(this, sql) as unknown as D1PreparedStatement;
  }

  async execute(statement: FakeStatement): Promise<{ results: Record<string, unknown>[]; meta: { changes: number }; success: true }> {
    const sql = statement.sql;
    const v = statement.values;
    let changes = 0;
    let results: Record<string, unknown>[] = [];

    if (sql.startsWith("SELECT plan, source, expires_at")) {
      const row = this.entitlements.get(String(v[0]));
      if (row) results = [{ plan: row.plan, source: row.source, expires_at: row.expiresAt }];
    } else if (sql.startsWith("SELECT used, quota_limit")) {
      const row = this.quotas.get(`${v[0]}:${v[1]}`);
      if (row) results = [{ used: row.used, quota_limit: row.limit }];
    } else if (sql.startsWith("UPDATE managed_ai_usage SET used = used + 1")) {
      const key = `${v[0]}:${v[1]}`;
      const row = this.quotas.get(key);
      if (row && (row.limit === null || row.used < row.limit)) { row.used += 1; changes = 1; }
    } else if (sql.startsWith("UPDATE managed_ai_usage SET used = MAX(0, used - 1)")) {
      const key = `${v[0]}:${v[1]}`;
      const row = this.quotas.get(key);
      if (row) { row.used = Math.max(0, row.used - 1); changes = 1; }
    } else if (sql.startsWith("SELECT user_id FROM play_purchase_bindings")) {
      const b = this.bindings.get(String(v[0]));
      if (b) results = [{ user_id: b.userId }];
    } else if (sql.startsWith("INSERT OR IGNORE INTO play_purchase_bindings")) {
      const hash = String(v[0]);
      if (!this.bindings.has(hash)) {
        this.bindings.set(hash, { userId: String(v[1]) });
        changes = 1;
      }
    } else if (sql.startsWith("UPDATE play_purchase_bindings SET product_id = ?")) {
      const hash = String(v[3]);
      const userId = String(v[4]);
      const existing = this.bindings.get(hash);
      if (existing && existing.userId === userId) {
        existing.productId = String(v[0]);
        existing.status = String(v[1]);
        existing.expiresAt = v[2] === null ? null : String(v[2]);
        changes = 1;
      }
    } else if (sql.startsWith("SELECT product_id, status, expires_at FROM play_purchase_bindings")) {
      const userId = String(v[0]);
      results = Array.from(this.bindings.values())
        .filter((b) => b.userId === userId)
        .map((b) => ({ product_id: b.productId, status: b.status, expires_at: b.expiresAt }));
    } else if (sql.startsWith("DELETE FROM play_purchase_bindings WHERE user_id = ?")) {
      const userId = String(v[0]);
      for (const [hash, b] of Array.from(this.bindings.entries())) {
        if (b.userId === userId) {
          this.bindings.delete(hash);
          changes += 1;
        }
      }
    } else if (sql.startsWith("DELETE FROM membership_entitlements WHERE user_id = ?")) {
      const userId = String(v[0]);
      if (this.entitlements.delete(userId)) changes += 1;
    } else if (sql.startsWith("INSERT INTO membership_entitlements")) {
      this.entitlements.set(String(v[0]), {
        plan: String(v[1]),
        source: "billing",
        expiresAt: v[2] === null ? null : String(v[2])
      });
      changes = 1;
    } else {
      throw new Error(`unexpected SQL: ${sql}`);
    }
    return { results, meta: { changes }, success: true };
  }
}

describe("D1 membership persistence", () => {
  const now = () => Date.parse("2026-09-11T00:00:00Z");

  it("defaults unknown users to free with zero managed quota", async () => {
    const db = new FakeD1();
    const store = new D1MembershipStore(db as unknown as D1Database, () => "2026-09", now);
    await expect(store.getEntitlement("user-a")).resolves.toEqual({ plan: "FREE", source: "default" });
    await expect(store.getManagedAiQuota("user-a")).resolves.toEqual({ limit: 0, used: 0 });
  });

  it("persists verified billing entitlement and token ownership for the same internal user", async () => {
    const db = new FakeD1();
    const billing = new D1BillingStateStore(db as unknown as D1Database);
    const membership = new D1MembershipStore(db as unknown as D1Database, () => "2026-09", now);
    const tokenHash = "a".repeat(64);

    await expect(billing.bindTokenHash("user-a", tokenHash)).resolves.toBe("bound");
    await billing.setBillingEntitlement("user-a", {
      plan: "PRO",
      source: "billing",
      expiresAt: "2026-10-01T00:00:00Z"
    });

    await expect(billing.userForTokenHash(tokenHash)).resolves.toBe("user-a");
    await expect(membership.getEntitlement("user-a")).resolves.toEqual({
      plan: "PRO",
      source: "billing",
      expiresAt: "2026-10-01T00:00:00Z"
    });
    await expect(billing.bindTokenHash("user-b", tokenHash)).resolves.toBe("conflict");
  });

  it("fails safe to FREE after a persisted PRO entitlement expires even if RTDN was missed", async () => {
    const db = new FakeD1();
    db.entitlements.set("user-a", {
      plan: "PRO",
      source: "billing",
      expiresAt: "2026-09-10T23:59:59Z"
    });
    const membership = new D1MembershipStore(db as unknown as D1Database, () => "2026-09", now);

    await expect(membership.getEntitlement("user-a")).resolves.toEqual({
      plan: "FREE",
      source: "billing",
      expiresAt: "2026-09-10T23:59:59Z"
    });
  });

  it("rejects malformed persisted entitlement expiry instead of granting PRO", async () => {
    const db = new FakeD1();
    db.entitlements.set("user-a", { plan: "PRO", source: "billing", expiresAt: "not-a-date" });
    const membership = new D1MembershipStore(db as unknown as D1Database, () => "2026-09", now);
    await expect(membership.getEntitlement("user-a")).rejects.toThrow("invalid_membership_state");
  });

  it("increments only provisioned quota rows and refuses use at the limit", async () => {
    const db = new FakeD1();
    db.quotas.set("user-a:2026-09", { used: 1, limit: 2 });
    const store = new D1MembershipStore(db as unknown as D1Database, () => "2026-09", now);

    await store.incrementManagedAiUsage("user-a");
    await expect(store.getManagedAiQuota("user-a")).resolves.toEqual({ limit: 2, used: 2 });
    await expect(store.incrementManagedAiUsage("user-a")).rejects.toThrow("managed_ai_quota_unavailable");

    await store.decrementManagedAiUsage("user-a");
    await expect(store.getManagedAiQuota("user-a")).resolves.toEqual({ limit: 2, used: 1 });
  });

  it("only auto-configures durable stores when ACCOUNT_DB is bound", () => {
    expect(createConfiguredD1MembershipStore({})).toBeUndefined();
    expect(createConfiguredD1BillingStateStore({})).toBeUndefined();
    const env = { ACCOUNT_DB: new FakeD1() as unknown as D1Database };
    expect(createConfiguredD1MembershipStore(env)).toBeInstanceOf(D1MembershipStore);
    expect(createConfiguredD1BillingStateStore(env)).toBeInstanceOf(D1BillingStateStore);
  });

  it("records subscriptions and aggregates multi-token entitlements", async () => {
    const db = new FakeD1();
    const billing = new D1BillingStateStore(db as unknown as D1Database);
    const membership = new D1MembershipStore(db as unknown as D1Database, () => "2026-09", now);
    const tokenHash1 = "1".repeat(64);
    const tokenHash2 = "2".repeat(64);

    await billing.bindTokenHash("user-a", tokenHash1);
    await billing.recordSubscription("user-a", tokenHash1, {
      packageName: "ink.underflo.wristbrief",
      productId: "wristbrief_pro_monthly",
      status: "active",
      expiresAt: "2026-10-01T00:00:00Z"
    });

    await billing.bindTokenHash("user-a", tokenHash2);
    await billing.recordSubscription("user-a", tokenHash2, {
      packageName: "ink.underflo.wristbrief",
      productId: "wristbrief_pro_yearly",
      status: "active",
      expiresAt: "2027-09-11T00:00:00Z"
    });

    const entitlement = await billing.recomputeUserEntitlement("user-a");
    expect(entitlement).toEqual({
      plan: "PRO",
      source: "billing",
      expiresAt: "2027-09-11T00:00:00Z"
    });
    await expect(membership.getEntitlement("user-a")).resolves.toEqual({
      plan: "PRO",
      source: "billing",
      expiresAt: "2027-09-11T00:00:00Z"
    });

    // When token 2 is canceled/expired, recomputing still gives PRO from token 1
    await billing.recordSubscription("user-a", tokenHash2, {
      packageName: "ink.underflo.wristbrief",
      productId: "wristbrief_pro_yearly",
      status: "expired"
    });
    const updated = await billing.recomputeUserEntitlement("user-a");
    expect(updated).toEqual({
      plan: "PRO",
      source: "billing",
      expiresAt: "2026-10-01T00:00:00Z"
    });

    // Delete user bindings purges tokens and entitlement
    await billing.deleteUserBindings("user-a");
    await expect(billing.userForTokenHash(tokenHash1)).resolves.toBeNull();
    await expect(billing.userForTokenHash(tokenHash2)).resolves.toBeNull();
    await expect(membership.getEntitlement("user-a")).resolves.toEqual({
      plan: "FREE",
      source: "default"
    });
  });
});


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
}

type EntitlementRow = { plan: string; source: string; expiresAt: string | null };
type QuotaRow = { used: number; limit: number | null };

class FakeD1 {
  readonly entitlements = new Map<string, EntitlementRow>();
  readonly quotas = new Map<string, QuotaRow>();
  readonly bindings = new Map<string, string>();

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
    } else if (sql.startsWith("UPDATE managed_ai_usage SET used")) {
      const key = `${v[0]}:${v[1]}`;
      const row = this.quotas.get(key);
      if (row && (row.limit === null || row.used < row.limit)) { row.used += 1; changes = 1; }
    } else if (sql.startsWith("SELECT user_id FROM play_purchase_bindings")) {
      const userId = this.bindings.get(String(v[0]));
      if (userId) results = [{ user_id: userId }];
    } else if (sql.startsWith("INSERT OR IGNORE INTO play_purchase_bindings")) {
      const hash = String(v[0]);
      if (!this.bindings.has(hash)) { this.bindings.set(hash, String(v[1])); changes = 1; }
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
  it("defaults unknown users to free with zero managed quota", async () => {
    const db = new FakeD1();
    const store = new D1MembershipStore(db as unknown as D1Database, () => "2026-09");
    await expect(store.getEntitlement("user-a")).resolves.toEqual({ plan: "FREE", source: "default" });
    await expect(store.getManagedAiQuota("user-a")).resolves.toEqual({ limit: 0, used: 0 });
  });

  it("persists verified billing entitlement and token ownership for the same internal user", async () => {
    const db = new FakeD1();
    const billing = new D1BillingStateStore(db as unknown as D1Database);
    const membership = new D1MembershipStore(db as unknown as D1Database, () => "2026-09");
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

  it("increments only provisioned quota rows and refuses use at the limit", async () => {
    const db = new FakeD1();
    db.quotas.set("user-a:2026-09", { used: 1, limit: 2 });
    const store = new D1MembershipStore(db as unknown as D1Database, () => "2026-09");

    await store.incrementManagedAiUsage("user-a");
    await expect(store.getManagedAiQuota("user-a")).resolves.toEqual({ limit: 2, used: 2 });
    await expect(store.incrementManagedAiUsage("user-a")).rejects.toThrow("managed_ai_quota_unavailable");
  });

  it("only auto-configures durable stores when ACCOUNT_DB is bound", () => {
    expect(createConfiguredD1MembershipStore({})).toBeUndefined();
    expect(createConfiguredD1BillingStateStore({})).toBeUndefined();
    const env = { ACCOUNT_DB: new FakeD1() as unknown as D1Database };
    expect(createConfiguredD1MembershipStore(env)).toBeInstanceOf(D1MembershipStore);
    expect(createConfiguredD1BillingStateStore(env)).toBeInstanceOf(D1BillingStateStore);
  });
});

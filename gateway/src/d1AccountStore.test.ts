import { describe, expect, it } from "vitest";
import {
  D1AccountIdentityStore,
  D1AccountSessionStore,
  createConfiguredD1AccountStores
} from "./d1AccountStore";

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

class FakeD1 {
  readonly users = new Map<string, string>();
  readonly identities = new Map<string, { userId: string; email: string; displayName: string | null; pictureUrl: string | null }>();
  readonly sessions = new Map<string, { id: string; userId: string; tokenHash: string; createdAt: string; expiresAt: string; revokedAt: string | null }>();

  prepare(sql: string): D1PreparedStatement {
    return new FakeStatement(this, sql) as unknown as D1PreparedStatement;
  }

  async batch<T = unknown>(statements: D1PreparedStatement[]): Promise<D1Result<T>[]> {
    const results: D1Result<T>[] = [];
    for (const statement of statements) {
      results.push(await (this.execute(statement as unknown as FakeStatement) as unknown as Promise<D1Result<T>>));
    }
    return results;
  }

  async execute(statement: FakeStatement): Promise<{ results: Record<string, unknown>[]; meta: { changes: number }; success: true }> {
    const sql = statement.sql;
    const v = statement.values;
    let changes = 0;
    let results: Record<string, unknown>[] = [];

    if (sql.startsWith("INSERT OR IGNORE INTO users")) {
      const id = String(v[0]);
      if (!this.users.has(id)) { this.users.set(id, "active"); changes = 1; }
    } else if (sql.startsWith("INSERT OR IGNORE INTO identities")) {
      const subject = String(v[0]);
      if (!this.identities.has(subject)) {
        this.identities.set(subject, {
          userId: String(v[1]),
          email: String(v[2]),
          displayName: v[3] === null ? null : String(v[3]),
          pictureUrl: v[4] === null ? null : String(v[4])
        });
        changes = 1;
      }
    } else if (sql.startsWith("UPDATE identities SET")) {
      const subject = String(v[3]);
      const existing = this.identities.get(subject);
      if (existing) {
        existing.email = String(v[0]);
        existing.displayName = v[1] === null ? null : String(v[1]);
        existing.pictureUrl = v[2] === null ? null : String(v[2]);
        changes = 1;
      }
    } else if (sql.startsWith("DELETE FROM users")) {
      const id = String(v[0]);
      const used = Array.from(this.identities.values()).some((identity) => identity.userId === id);
      if (!used && this.users.delete(id)) changes = 1;
    } else if (sql.startsWith("SELECT i.user_id")) {
      const identity = this.identities.get(String(v[0]));
      if (identity) results = [{ user_id: identity.userId, status: this.users.get(identity.userId) }];
    } else if (sql.startsWith("INSERT INTO sessions")) {
      const tokenHash = String(v[2]);
      this.sessions.set(tokenHash, {
        id: String(v[0]), userId: String(v[1]), tokenHash,
        createdAt: String(v[3]), expiresAt: String(v[4]), revokedAt: null
      });
      changes = 1;
    } else if (sql.startsWith("SELECT id, user_id")) {
      const session = this.sessions.get(String(v[0]));
      if (session) results = [{
        id: session.id,
        user_id: session.userId,
        token_hash: session.tokenHash,
        created_at: session.createdAt,
        expires_at: session.expiresAt,
        revoked_at: session.revokedAt
      }];
    } else if (sql.startsWith("UPDATE sessions SET revoked_at")) {
      const session = this.sessions.get(String(v[1]));
      if (session) { session.revokedAt ??= String(v[0]); changes = 1; }
    } else {
      throw new Error(`unexpected SQL: ${sql}`);
    }

    return { results, meta: { changes }, success: true };
  }
}

describe("D1 account stores", () => {
  it("atomically resolves Google sub and removes losing candidate users", async () => {
    const db = new FakeD1();
    const store = new D1AccountIdentityStore(db as unknown as D1Database);
    const base = { provider: "google" as const, providerSubject: "sub-1", email: "first@example.com" };

    await expect(store.resolveOrCreateGoogleIdentity({ ...base, userId: "user-a" })).resolves.toEqual({ userId: "user-a", created: true });
    await expect(store.resolveOrCreateGoogleIdentity({ ...base, userId: "user-b", email: "new@example.com" })).resolves.toEqual({ userId: "user-a", created: false });
    expect(db.users.has("user-a")).toBe(true);
    expect(db.users.has("user-b")).toBe(false);
    expect(db.identities.get("sub-1")?.email).toBe("new@example.com");
  });

  it("round-trips and revokes hashed sessions", async () => {
    const db = new FakeD1();
    db.users.set("user-a", "active");
    const store = new D1AccountSessionStore(db as unknown as D1Database);
    const record = {
      id: "session-1", userId: "user-a", tokenHash: "a".repeat(64),
      createdAt: "2026-09-10T00:00:00.000Z", expiresAt: "2026-10-10T00:00:00.000Z"
    };
    await store.createSession(record);
    await expect(store.sessionForTokenHash(record.tokenHash)).resolves.toEqual(record);
    await expect(store.revokeByTokenHash(record.tokenHash, "2026-09-11T00:00:00.000Z")).resolves.toBe(true);
    await expect(store.sessionForTokenHash(record.tokenHash)).resolves.toMatchObject({ revokedAt: "2026-09-11T00:00:00.000Z" });
  });

  it("only configures production stores when ACCOUNT_DB exists", () => {
    expect(createConfiguredD1AccountStores({})).toBeUndefined();
    expect(createConfiguredD1AccountStores({ ACCOUNT_DB: new FakeD1() as unknown as D1Database })).toBeDefined();
  });
});

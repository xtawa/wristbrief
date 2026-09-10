import { describe, expect, it } from "vitest";
import { D1RtdnDedupStore, InMemoryRtdnDedupStore, createConfiguredD1RtdnDedupStore } from "./rtdnDedupStore";

class FakeStatement {
  values: unknown[] = [];
  constructor(private readonly db: FakeD1, readonly sql: string) {}
  bind(...values: unknown[]) { this.values = values; return this as unknown as D1PreparedStatement; }
  async run<T = unknown>() { return this.db.execute(this) as unknown as Promise<D1Result<T>>; }
}

class FakeD1 {
  readonly messages = new Set<string>();

  prepare(sql: string): D1PreparedStatement {
    return new FakeStatement(this, sql) as unknown as D1PreparedStatement;
  }

  async execute(statement: FakeStatement) {
    const messageId = String(statement.values[0]);
    if (statement.sql.startsWith("INSERT OR IGNORE INTO play_rtdn_messages")) {
      if (this.messages.has(messageId)) return { results: [], meta: { changes: 0 }, success: true };
      this.messages.add(messageId);
      return { results: [], meta: { changes: 1 }, success: true };
    }
    if (statement.sql.startsWith("DELETE FROM play_rtdn_messages")) {
      const deleted = this.messages.delete(messageId);
      return { results: [], meta: { changes: deleted ? 1 : 0 }, success: true };
    }
    throw new Error(`unexpected SQL: ${statement.sql}`);
  }
}

describe("RTDN dedup persistence", () => {
  it("atomically rejects a duplicate message id", async () => {
    const store = new InMemoryRtdnDedupStore();
    const results = await Promise.all([store.claim("msg-1"), store.claim("msg-1")]);
    expect(results.filter(Boolean)).toHaveLength(1);
    expect(results.filter((value) => !value)).toHaveLength(1);
  });

  it("allows retry after a failed delivery releases its claim", async () => {
    const store = new InMemoryRtdnDedupStore();
    expect(await store.claim("msg-1")).toBe(true);
    await store.release("msg-1");
    expect(await store.claim("msg-1")).toBe(true);
  });

  it("persists claims in D1 and releases only the selected message", async () => {
    const db = new FakeD1();
    const store = new D1RtdnDedupStore(db as unknown as D1Database);
    await expect(store.claim("msg-a")).resolves.toBe(true);
    await expect(store.claim("msg-a")).resolves.toBe(false);
    await expect(store.claim("msg-b")).resolves.toBe(true);
    await store.release("msg-a");
    await expect(store.claim("msg-a")).resolves.toBe(true);
    await expect(store.claim("msg-b")).resolves.toBe(false);
  });

  it("only auto-configures durable dedup when ACCOUNT_DB is bound", () => {
    expect(createConfiguredD1RtdnDedupStore({})).toBeUndefined();
    expect(createConfiguredD1RtdnDedupStore({ ACCOUNT_DB: new FakeD1() as unknown as D1Database }))
      .toBeInstanceOf(D1RtdnDedupStore);
  });
});

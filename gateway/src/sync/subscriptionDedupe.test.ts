import { describe, expect, it } from "vitest";
import { createMigratedTestDb } from "../testDbHelper";
import { D1SyncStore } from "./d1SyncStore";
import { type SyncMutation } from "./syncTypes";

function subscriptionMutation(entityId: string, feedUrl: string, updatedAt: number, deleted = false): SyncMutation {
  return {
    idempotencyKey: `key_${entityId}_${updatedAt}`,
    entity: "subscription",
    entityId,
    updatedAt,
    deleted,
    payload: deleted ? ({} as Record<string, never>) : { feedUrl, title: "Feed", enabled: true, sendToWatch: true }
  };
}

describe("subscription dedupe by feed URL (0010_sync_integrity)", () => {
  it("collapses two pushes with different subscription ids but the same feed URL into one row", async () => {
    const { d1 } = createMigratedTestDb();
    const store = new D1SyncStore(d1);
    const userId = "usr_dedupe_1";

    await store.pushMutations(userId, { deviceId: "dev_a", mutations: [subscriptionMutation("sub_alpha", "https://example.com/feed.xml", 1000)] });
    await store.pushMutations(userId, { deviceId: "dev_b", mutations: [subscriptionMutation("sub_beta", "https://EXAMPLE.com/feed.xml", 2000)] });

    const rows = await d1
      .prepare("SELECT subscription_id, feed_url, revision FROM user_subscriptions WHERE user_id = ?")
      .bind(userId)
      .all<{ subscription_id: string; feed_url: string; revision: number }>();
    expect(rows.results).toHaveLength(1);
    // The first row wins and is updated in place, not duplicated.
    expect(rows.results[0].subscription_id).toBe("sub_alpha");
    expect(rows.results[0].revision).toBe(2);
  });

  it("tombstones an existing row by subscription id (cross-device deletion)", async () => {
    const { d1 } = createMigratedTestDb();
    const store = new D1SyncStore(d1);
    const userId = "usr_dedupe_2";

    await store.pushMutations(userId, { deviceId: "dev_a", mutations: [subscriptionMutation("sub_alpha", "https://example.com/feed.xml", 1000)] });
    // Both devices derive the same subscription id from the normalized URL,
    // so the deletion resolves by id. (A deletion carries no feedUrl.)
    await store.pushMutations(userId, { deviceId: "dev_b", mutations: [subscriptionMutation("sub_alpha", "https://example.com/feed.xml", 2000, true)] });

    const rows = await d1
      .prepare("SELECT subscription_id, deleted_at FROM user_subscriptions WHERE user_id = ?")
      .bind(userId)
      .all<{ subscription_id: string; deleted_at: number | null }>();
    expect(rows.results).toHaveLength(1);
    expect(rows.results[0].subscription_id).toBe("sub_alpha");
    expect(rows.results[0].deleted_at).toBe(2000);

    // Stale resurrection from the other device is rejected.
    await store.pushMutations(userId, { deviceId: "dev_a", mutations: [subscriptionMutation("sub_alpha", "https://example.com/feed.xml", 1500)] });
    const after = await d1
      .prepare("SELECT deleted_at FROM user_subscriptions WHERE user_id = ?")
      .bind(userId)
      .all<{ deleted_at: number | null }>();
    expect(after.results[0].deleted_at).toBe(2000);
  });

  it("enforces the per-user unique index at the database level", async () => {
    const { d1 } = createMigratedTestDb();
    await d1
      .prepare("INSERT INTO user_subscriptions (user_id, subscription_id, feed_url, title, enabled, send_to_watch, revision, updated_at) VALUES (?, 's1', 'https://a.example/rss', 'A', 1, 1, 1, 1)")
      .bind("usr_index_1")
      .run();
    await expect(
      d1
        .prepare("INSERT INTO user_subscriptions (user_id, subscription_id, feed_url, title, enabled, send_to_watch, revision, updated_at) VALUES (?, 's2', 'https://A.example/rss', 'B', 1, 1, 1, 2)")
        .bind("usr_index_1")
        .run()
    ).rejects.toThrow(/UNIQUE/i);
    // A different user subscribing to the same feed is fine.
    await d1
      .prepare("INSERT INTO user_subscriptions (user_id, subscription_id, feed_url, title, enabled, send_to_watch, revision, updated_at) VALUES (?, 's1', 'https://a.example/rss', 'A', 1, 1, 1, 1)")
      .bind("usr_index_2")
      .run();
  });
});

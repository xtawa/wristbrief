import { describe, expect, it } from "vitest";
import { createMigratedTestDb } from "../testDbHelper";
import { D1SyncStore } from "./d1SyncStore";
import { type SyncMutation } from "./syncTypes";

describe("SyncService & D1SyncStore", () => {
  it("pushes mutations from Device A and pulls deltas onto Device B", async () => {
    const { d1 } = createMigratedTestDb();
    const store = new D1SyncStore(d1);
    const userId = "usr_test_1";

    const mutations: SyncMutation[] = [
      {
        idempotencyKey: "mut_1",
        entity: "subscription",
        entityId: "sub_1",
        updatedAt: 1000,
        payload: {
          feedUrl: "https://example.com/rss.xml",
          title: "Tech News",
          enabled: true,
          sendToWatch: true
        }
      },
      {
        idempotencyKey: "mut_2",
        entity: "item_state",
        entityId: "item_1",
        updatedAt: 1000,
        payload: {
          isRead: true,
          isSaved: false
        }
      },
      {
        idempotencyKey: "mut_3",
        entity: "playback_progress",
        entityId: "ep_1",
        updatedAt: 1000,
        payload: {
          positionMs: 60000,
          durationMs: 300000,
          playbackSpeed: 1.5,
          completed: false,
          progressGeneration: 1
        }
      }
    ];

    const pushRes = await store.pushMutations(userId, {
      deviceId: "dev_phone_a",
      mutations
    });

    expect(pushRes.appliedCount).toBe(3);
    expect(pushRes.newCursor).toBeGreaterThan(0);

    // Device B pulls with cursor 0
    const pullRes = await store.pullDeltas(userId, "dev_phone_b", 0);
    expect(pullRes.subscriptions.length).toBe(1);
    expect(pullRes.subscriptions[0].title).toBe("Tech News");
    expect(pullRes.subscriptions[0].sendToWatch).toBe(true);

    expect(pullRes.itemStates.length).toBe(1);
    expect(pullRes.itemStates[0].isRead).toBe(true);
    expect(pullRes.itemStates[0].isSaved).toBe(false);

    expect(pullRes.playbackProgress.length).toBe(1);
    expect(pullRes.playbackProgress[0].positionMs).toBe(60000);
    expect(pullRes.playbackProgress[0].progressGeneration).toBe(1);
  });

  it("handles subscription deletion tombstones without resurrecting stale state", async () => {
    const { d1 } = createMigratedTestDb();
    const store = new D1SyncStore(d1);
    const userId = "usr_test_2";

    // 1. Create subscription at t=1000
    await store.pushMutations(userId, {
      deviceId: "dev_a",
      mutations: [
        {
          idempotencyKey: "m1",
          entity: "subscription",
          entityId: "sub_del",
          updatedAt: 1000,
          payload: { feedUrl: "https://example.com/feed.xml" }
        }
      ]
    });

    // 2. Delete subscription at t=2000
    await store.pushMutations(userId, {
      deviceId: "dev_a",
      mutations: [
        {
          idempotencyKey: "m2",
          entity: "subscription",
          entityId: "sub_del",
          updatedAt: 2000,
          deleted: true,
          payload: { feedUrl: "https://example.com/feed.xml" }
        }
      ]
    });

    // 3. Stale offline update from dev_b with t=1500 should NOT resurrect
    await store.pushMutations(userId, {
      deviceId: "dev_b",
      mutations: [
        {
          idempotencyKey: "m3",
          entity: "subscription",
          entityId: "sub_del",
          updatedAt: 1500,
          payload: { feedUrl: "https://example.com/feed.xml", title: "Old Title" }
        }
      ]
    });

    const pull = await store.pullDeltas(userId, "dev_c", 0);
    const sub = pull.subscriptions.find((s) => s.subscriptionId === "sub_del");
    expect(sub).toBeDefined();
    expect(sub?.deletedAt).toBe(2000);
  });

  it("arbitrates independent read and saved clocks on item states", async () => {
    const { d1 } = createMigratedTestDb();
    const store = new D1SyncStore(d1);
    const userId = "usr_test_3";

    // 1. Mark item saved at t=1000
    await store.pushMutations(userId, {
      deviceId: "dev_a",
      mutations: [
        {
          idempotencyKey: "m1",
          entity: "item_state",
          entityId: "item_merge",
          updatedAt: 1000,
          payload: { isSaved: true }
        }
      ]
    });

    // 2. Mark item read at t=1500 (without changing saved)
    await store.pushMutations(userId, {
      deviceId: "dev_b",
      mutations: [
        {
          idempotencyKey: "m2",
          entity: "item_state",
          entityId: "item_merge",
          updatedAt: 1500,
          payload: { isRead: true }
        }
      ]
    });

    const pull = await store.pullDeltas(userId, "dev_c", 0);
    const item = pull.itemStates.find((i) => i.itemId === "item_merge");
    expect(item).toBeDefined();
    // Both isRead and isSaved should be true!
    expect(item?.isSaved).toBe(true);
    expect(item?.isRead).toBe(true);
    expect(item?.savedChangedAt).toBe(1000);
    expect(item?.readChangedAt).toBe(1500);
  });

  it("arbitrates playback progress: higher generation wins and completed=true persists", async () => {
    const { d1 } = createMigratedTestDb();
    const store = new D1SyncStore(d1);
    const userId = "usr_test_4";

    // 1. User completes episode at gen 1, t=2000
    await store.pushMutations(userId, {
      deviceId: "dev_phone",
      mutations: [
        {
          idempotencyKey: "p1",
          entity: "playback_progress",
          entityId: "ep_done",
          updatedAt: 2000,
          payload: {
            positionMs: 180000,
            durationMs: 180000,
            completed: true,
            progressGeneration: 1
          }
        }
      ]
    });

    // 2. Stale update from disconnected watch (gen 1, t=1500, position 120000, not completed) should be ignored
    await store.pushMutations(userId, {
      deviceId: "dev_watch",
      mutations: [
        {
          idempotencyKey: "p2",
          entity: "playback_progress",
          entityId: "ep_done",
          updatedAt: 1500,
          payload: {
            positionMs: 120000,
            durationMs: 180000,
            completed: false,
            progressGeneration: 1
          }
        }
      ]
    });

    let pull = await store.pullDeltas(userId, "dev_check", 0);
    let ep = pull.playbackProgress.find((p) => p.contentId === "ep_done");
    expect(ep?.completed).toBe(true);
    expect(ep?.positionMs).toBe(180000);

    // 3. Deliberate replay on watch starts gen 2 at position 0, t=3000 -> Should win!
    await store.pushMutations(userId, {
      deviceId: "dev_watch",
      mutations: [
        {
          idempotencyKey: "p3",
          entity: "playback_progress",
          entityId: "ep_done",
          updatedAt: 3000,
          payload: {
            positionMs: 5000,
            durationMs: 180000,
            completed: false,
            progressGeneration: 2
          }
        }
      ]
    });

    pull = await store.pullDeltas(userId, "dev_check", 0);
    ep = pull.playbackProgress.find((p) => p.contentId === "ep_done");
    expect(ep?.progressGeneration).toBe(2);
    expect(ep?.positionMs).toBe(5000);
    expect(ep?.completed).toBe(false);
  });
});

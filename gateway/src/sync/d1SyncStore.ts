import {
  type DeviceItemStateRecord,
  type DevicePlaybackProgressRecord,
  type DeviceSubscriptionRecord,
  type ItemStatePayload,
  type PlaybackProgressPayload,
  type SubscriptionPayload,
  type SyncMutation,
  type SyncPullResponse,
  type SyncPushRequest,
  type SyncPushResponse
} from "./syncTypes";

export interface SyncStore {
  registerDevice(userId: string, deviceId: string, platform: string, displayName?: string, appVersion?: string): Promise<void>;
  pushMutations(userId: string, request: SyncPushRequest): Promise<SyncPushResponse>;
  pullDeltas(userId: string, deviceId: string, cursor: number, limit?: number): Promise<SyncPullResponse>;
}

export class D1SyncStore implements SyncStore {
  constructor(private readonly db: D1Database) {}

  async registerDevice(
    userId: string,
    deviceId: string,
    platform: string,
    displayName?: string,
    appVersion?: string
  ): Promise<void> {
    const now = Date.now();
    await this.db
      .prepare(`
        INSERT INTO devices (id, user_id, platform, display_name, app_version, created_at, last_seen_at)
        VALUES (?, ?, ?, ?, ?, ?, ?)
        ON CONFLICT(id) DO UPDATE SET
          platform = excluded.platform,
          display_name = coalesce(excluded.display_name, devices.display_name),
          app_version = coalesce(excluded.app_version, devices.app_version),
          last_seen_at = excluded.last_seen_at
        WHERE devices.user_id = excluded.user_id
      `)
      .bind(deviceId, userId, platform, displayName ?? null, appVersion ?? null, now, now)
      .run();

    const registered = await this.db
      .prepare("SELECT user_id FROM devices WHERE id = ?")
      .bind(deviceId)
      .first<{ user_id: string }>();
    if (!registered || registered.user_id !== userId) {
      throw new Error("device_id_already_registered");
    }
  }

  async pushMutations(userId: string, request: SyncPushRequest): Promise<SyncPushResponse> {
    const now = Date.now();
    let appliedCount = 0;

    await this.registerDevice(userId, request.deviceId, "android");

    for (const mutation of request.mutations) {
      if (mutation.entity === "subscription") {
        await this.applySubscriptionMutation(userId, mutation, now);
        appliedCount++;
      } else if (mutation.entity === "item_state") {
        await this.applyItemStateMutation(userId, mutation, now);
        appliedCount++;
      } else if (mutation.entity === "playback_progress") {
        await this.applyPlaybackProgressMutation(userId, request.deviceId, mutation, now);
        appliedCount++;
      }
    }

    // Advance cursor for device
    const newCursor = now;
    await this.db
      .prepare(`
        INSERT INTO user_sync_cursors (user_id, device_id, cursor_value, updated_at)
        VALUES (?, ?, ?, ?)
        ON CONFLICT(user_id, device_id) DO UPDATE SET
          cursor_value = excluded.cursor_value,
          updated_at = excluded.updated_at
      `)
      .bind(userId, request.deviceId, newCursor, now)
      .run();

    return {
      newCursor,
      appliedCount
    };
  }

  async pullDeltas(userId: string, deviceId: string, cursor: number, limit = 100): Promise<SyncPullResponse> {
    const subRows = await this.db
      .prepare(`
        SELECT * FROM user_subscriptions
        WHERE user_id = ? AND updated_at > ?
        ORDER BY updated_at ASC LIMIT ?
      `)
      .bind(userId, cursor, limit)
      .all<Record<string, unknown>>();

    const itemRows = await this.db
      .prepare(`
        SELECT * FROM user_item_states
        WHERE user_id = ? AND updated_at > ?
        ORDER BY updated_at ASC LIMIT ?
      `)
      .bind(userId, cursor, limit)
      .all<Record<string, unknown>>();

    const progressRows = await this.db
      .prepare(`
        SELECT * FROM user_playback_progress
        WHERE user_id = ? AND updated_at > ?
        ORDER BY updated_at ASC LIMIT ?
      `)
      .bind(userId, cursor, limit)
      .all<Record<string, unknown>>();

    const subscriptions: DeviceSubscriptionRecord[] = (subRows.results || []).map((r) => ({
      subscriptionId: String(r.subscription_id),
      feedUrl: String(r.feed_url),
      title: r.title ? String(r.title) : null,
      category: r.category ? String(r.category) : null,
      enabled: Boolean(r.enabled),
      sendToWatch: Boolean(r.send_to_watch),
      watchKeywordsJson: r.watch_keywords_json ? String(r.watch_keywords_json) : null,
      revision: Number(r.revision),
      updatedAt: Number(r.updated_at),
      deletedAt: typeof r.deleted_at === "number" ? r.deleted_at : null
    }));

    const itemStates: DeviceItemStateRecord[] = (itemRows.results || []).map((r) => ({
      itemId: String(r.item_id),
      isRead: Boolean(r.is_read),
      isSaved: Boolean(r.is_saved),
      readChangedAt: typeof r.read_changed_at === "number" ? r.read_changed_at : null,
      savedChangedAt: typeof r.saved_changed_at === "number" ? r.saved_changed_at : null,
      revision: Number(r.revision),
      updatedAt: Number(r.updated_at)
    }));

    const playbackProgress: DevicePlaybackProgressRecord[] = (progressRows.results || []).map((r) => ({
      contentId: String(r.content_id),
      episodeLocalId: r.episode_local_id ? String(r.episode_local_id) : null,
      positionMs: Number(r.position_ms),
      durationMs: Number(r.duration_ms),
      playbackSpeed: Number(r.playback_speed),
      completed: Boolean(r.completed),
      sourceDeviceId: r.source_device_id ? String(r.source_device_id) : null,
      playbackSessionId: r.playback_session_id ? String(r.playback_session_id) : null,
      progressGeneration: Number(r.progress_generation || 1),
      revision: Number(r.revision),
      updatedAt: Number(r.updated_at)
    }));

    const maxTs = Math.max(
      cursor,
      ...subscriptions.map((s) => s.updatedAt),
      ...itemStates.map((i) => i.updatedAt),
      ...playbackProgress.map((p) => p.updatedAt)
    );

    const hasMore =
      (subRows.results?.length ?? 0) >= limit ||
      (itemRows.results?.length ?? 0) >= limit ||
      (progressRows.results?.length ?? 0) >= limit;

    return {
      cursor: maxTs,
      hasMore,
      subscriptions,
      itemStates,
      playbackProgress
    };
  }

  private async applySubscriptionMutation(userId: string, mutation: SyncMutation, now: number): Promise<void> {
    const subId = mutation.entityId;
    const existing = await this.db
      .prepare("SELECT * FROM user_subscriptions WHERE user_id = ? AND subscription_id = ?")
      .bind(userId, subId)
      .first<Record<string, unknown>>();

    if (mutation.deleted) {
      // Tombstone
      if (existing) {
        await this.db
          .prepare("UPDATE user_subscriptions SET deleted_at = ?, updated_at = ?, revision = revision + 1 WHERE user_id = ? AND subscription_id = ?")
          .bind(mutation.updatedAt, now, userId, subId)
          .run();
      } else {
        await this.db
          .prepare(`
            INSERT INTO user_subscriptions (
              user_id, subscription_id, feed_url, title, enabled, send_to_watch, revision, updated_at, deleted_at
            ) VALUES (?, ?, '', '', 0, 0, 1, ?, ?)
          `)
          .bind(userId, subId, now, mutation.updatedAt)
          .run();
      }
      return;
    }

    const payload = mutation.payload as SubscriptionPayload;
    if (existing) {
      // If deleted_at exists and is newer than mutation.updatedAt, reject stale resurrection
      if (typeof existing.deleted_at === "number" && existing.deleted_at >= mutation.updatedAt) {
        return;
      }
      await this.db
        .prepare(`
          UPDATE user_subscriptions SET
            feed_url = ?,
            title = coalesce(?, title),
            category = coalesce(?, category),
            enabled = ?,
            send_to_watch = ?,
            watch_keywords_json = ?,
            deleted_at = NULL,
            updated_at = ?,
            revision = revision + 1
          WHERE user_id = ? AND subscription_id = ?
        `)
        .bind(
          payload.feedUrl,
          payload.title ?? null,
          payload.category ?? null,
          payload.enabled === false ? 0 : 1,
          payload.sendToWatch === false ? 0 : 1,
          payload.watchKeywords ? JSON.stringify(payload.watchKeywords) : null,
          now,
          userId,
          subId
        )
        .run();
    } else {
      await this.db
        .prepare(`
          INSERT INTO user_subscriptions (
            user_id, subscription_id, feed_url, title, category, enabled, send_to_watch, watch_keywords_json, revision, updated_at
          ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, 1, ?)
        `)
        .bind(
          userId,
          subId,
          payload.feedUrl,
          payload.title ?? null,
          payload.category ?? null,
          payload.enabled === false ? 0 : 1,
          payload.sendToWatch === false ? 0 : 1,
          payload.watchKeywords ? JSON.stringify(payload.watchKeywords) : null,
          now
        )
        .run();
    }
  }

  private async applyItemStateMutation(userId: string, mutation: SyncMutation, now: number): Promise<void> {
    const itemId = mutation.entityId;
    const payload = mutation.payload as ItemStatePayload;

    const existing = await this.db
      .prepare("SELECT * FROM user_item_states WHERE user_id = ? AND item_id = ?")
      .bind(userId, itemId)
      .first<Record<string, unknown>>();

    if (existing) {
      let isRead = Boolean(existing.is_read);
      let isSaved = Boolean(existing.is_saved);
      let readChangedAt = typeof existing.read_changed_at === "number" ? existing.read_changed_at : 0;
      let savedChangedAt = typeof existing.saved_changed_at === "number" ? existing.saved_changed_at : 0;

      // Independent clock arbitration for read
      if (typeof payload.isRead === "boolean" && mutation.updatedAt >= readChangedAt) {
        isRead = payload.isRead;
        readChangedAt = mutation.updatedAt;
      }

      // Independent clock arbitration for saved
      if (typeof payload.isSaved === "boolean" && mutation.updatedAt >= savedChangedAt) {
        isSaved = payload.isSaved;
        savedChangedAt = mutation.updatedAt;
      }

      await this.db
        .prepare(`
          UPDATE user_item_states SET
            is_read = ?,
            is_saved = ?,
            read_changed_at = ?,
            saved_changed_at = ?,
            revision = revision + 1,
            updated_at = ?
          WHERE user_id = ? AND item_id = ?
        `)
        .bind(isRead ? 1 : 0, isSaved ? 1 : 0, readChangedAt, savedChangedAt, now, userId, itemId)
        .run();
    } else {
      const isRead = Boolean(payload.isRead);
      const isSaved = Boolean(payload.isSaved);
      await this.db
        .prepare(`
          INSERT INTO user_item_states (
            user_id, item_id, is_read, is_saved, read_changed_at, saved_changed_at, revision, updated_at
          ) VALUES (?, ?, ?, ?, ?, ?, 1, ?)
        `)
        .bind(
          userId,
          itemId,
          isRead ? 1 : 0,
          isSaved ? 1 : 0,
          isRead ? mutation.updatedAt : null,
          isSaved ? mutation.updatedAt : null,
          now
        )
        .run();
    }
  }

  private async applyPlaybackProgressMutation(
    userId: string,
    deviceId: string,
    mutation: SyncMutation,
    now: number
  ): Promise<void> {
    const payload = mutation.payload as PlaybackProgressPayload;
    const contentKey = payload.contentId || payload.episodeLocalId || mutation.entityId;
    const progressGen = payload.progressGeneration || 1;

    const existing = await this.db
      .prepare("SELECT * FROM user_playback_progress WHERE user_id = ? AND content_id = ?")
      .bind(userId, contentKey)
      .first<Record<string, unknown>>();

    if (existing) {
      const existingGen = Number(existing.progress_generation || 1);
      const existingCompleted = Boolean(existing.completed);
      const existingUpdatedAt = Number(existing.updated_at);

      // Arbitration rules:
      // 1. If incoming generation is strictly lower, ignore.
      if (progressGen < existingGen) {
        return;
      }
      // 2. If same generation:
      if (progressGen === existingGen) {
        // completed=true cannot be undone by stale non-completed update
        if (existingCompleted && !payload.completed && mutation.updatedAt <= existingUpdatedAt) {
          return;
        }
        // Older timestamp within same generation is ignored
        if (mutation.updatedAt < existingUpdatedAt) {
          return;
        }
      }

      await this.db
        .prepare(`
          UPDATE user_playback_progress SET
            episode_local_id = coalesce(?, episode_local_id),
            position_ms = ?,
            duration_ms = ?,
            playback_speed = ?,
            completed = ?,
            source_device_id = ?,
            playback_session_id = ?,
            progress_generation = ?,
            revision = revision + 1,
            updated_at = ?
          WHERE user_id = ? AND content_id = ?
        `)
        .bind(
          payload.episodeLocalId ?? null,
          payload.positionMs,
          payload.durationMs,
          payload.playbackSpeed ?? 1.0,
          payload.completed ? 1 : 0,
          deviceId,
          payload.playbackSessionId ?? null,
          progressGen,
          now,
          userId,
          contentKey
        )
        .run();
    } else {
      await this.db
        .prepare(`
          INSERT INTO user_playback_progress (
            user_id, content_id, episode_local_id, position_ms, duration_ms, playback_speed, completed,
            source_device_id, playback_session_id, progress_generation, revision, updated_at
          ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 1, ?)
        `)
        .bind(
          userId,
          contentKey,
          payload.episodeLocalId ?? null,
          payload.positionMs,
          payload.durationMs,
          payload.playbackSpeed ?? 1.0,
          payload.completed ? 1 : 0,
          deviceId,
          payload.playbackSessionId ?? null,
          progressGen,
          now
        )
        .run();
    }
  }
}

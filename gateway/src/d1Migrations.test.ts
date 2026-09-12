// @ts-nocheck
import { describe, expect, it } from "vitest";
import { DatabaseSync } from "node:sqlite";
import fs from "node:fs";
import path from "node:path";

describe("D1 SQL Migrations Chain", () => {
  it("applies migrations 0001 through 0008 cleanly on a fresh SQLite database", () => {
    const migrationsDir = path.resolve(__dirname, "../migrations");
    const migrationFiles = fs
      .readdirSync(migrationsDir)
      .filter((file) => file.endsWith(".sql"))
      .sort();

    expect(migrationFiles.length).toBeGreaterThanOrEqual(8);
    expect(migrationFiles[0]).toBe("0001_membership.sql");
    expect(migrationFiles[migrationFiles.length - 1]).toBe("0008_cloud_sync_and_content_registry.sql");

    const db = new DatabaseSync(":memory:");

    for (const file of migrationFiles) {
      const filePath = path.join(migrationsDir, file);
      const sql = fs.readFileSync(filePath, "utf-8");
      db.exec(sql);
    }

    // Verify all expected tables exist
    const tables = db
      .prepare("SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%' ORDER BY name")
      .all() as Array<{ name: string }>;

    const tableNames = tables.map((t) => t.name);
    expect(tableNames).toContain("membership_entitlements");
    expect(tableNames).toContain("managed_ai_usage");
    expect(tableNames).toContain("play_purchase_bindings");
    expect(tableNames).toContain("users");
    expect(tableNames).toContain("identities");
    expect(tableNames).toContain("sessions");
    expect(tableNames).toContain("play_rtdn_messages");
    expect(tableNames).toContain("legacy_migration_grants");
    expect(tableNames).toContain("devices");
    expect(tableNames).toContain("user_subscriptions");
    expect(tableNames).toContain("user_item_states");
    expect(tableNames).toContain("user_playback_progress");
    expect(tableNames).toContain("user_sync_cursors");
    expect(tableNames).toContain("podcast_contents");
    expect(tableNames).toContain("content_aliases");
    expect(tableNames).toContain("content_fingerprints");
    expect(tableNames).toContain("transcript_artifacts");
    expect(tableNames).toContain("user_artifact_access");
    expect(tableNames).toContain("artifact_jobs");
    expect(tableNames).toContain("credit_transactions");

    // Verify play_purchase_bindings schema includes migration 0007 alterations
    const bindingCols = db
      .prepare("PRAGMA table_info(play_purchase_bindings)")
      .all() as Array<{ name: string; type: string }>;

    const colNames = bindingCols.map((c) => c.name);
    expect(colNames).toContain("token_hash");
    expect(colNames).toContain("user_id");
    expect(colNames).toContain("product_id");
    expect(colNames).toContain("status");
    expect(colNames).toContain("expires_at");

    // Verify podcast_contents and user_playback_progress schema
    const progressCols = db
      .prepare("PRAGMA table_info(user_playback_progress)")
      .all() as Array<{ name: string; type: string }>;
    const progressColNames = progressCols.map((c) => c.name);
    expect(progressColNames).toContain("progress_generation");
    expect(progressColNames).toContain("playback_session_id");

    // Test insertion into podcast_contents and transcript_artifacts
    db.prepare(`
      INSERT INTO podcast_contents (id, content_code, media_type, canonical_title, share_policy, status, created_at, updated_at)
      VALUES (?, ?, ?, ?, ?, ?, ?, ?)
    `).run("cnt_1", "WBEP-7Q2M-4H9D-K8XR", "podcast", "Episode Title", "PUBLIC_REUSE", "active", Date.now(), Date.now());

    const contentRow = db.prepare("SELECT * FROM podcast_contents WHERE id = ?").get("cnt_1") as Record<string, unknown>;
    expect(contentRow.content_code).toBe("WBEP-7Q2M-4H9D-K8XR");

    db.close();
  });
});

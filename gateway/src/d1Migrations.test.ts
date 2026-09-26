// @ts-nocheck
import { describe, expect, it } from "vitest";
import { DatabaseSync } from "node:sqlite";
import fs from "node:fs";
import path from "node:path";

describe("D1 SQL Migrations Chain", () => {
  it("applies the full numbered migration chain cleanly on a fresh SQLite database", () => {
    const migrationsDir = path.resolve(__dirname, "../migrations");
    const migrationFiles = fs
      .readdirSync(migrationsDir)
      .filter((file) => file.endsWith(".sql"))
      .sort();

    expect(migrationFiles.length).toBeGreaterThanOrEqual(12);
    expect(migrationFiles[0]).toBe("0001_membership.sql");
    // Later reserved slots (0016+) belong to other workstreams, so assert that this
    // chain includes 0015 rather than pinning the global maximum migration number.
    expect(migrationFiles).toContain("0015_shared_transcript_jobs.sql");

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
    expect(tableNames).toContain("user_roles");
    expect(tableNames).toContain("bootstrap_state");
    expect(tableNames).toContain("system_settings");
    expect(tableNames).toContain("admin_web_sessions");
    expect(tableNames).toContain("email_credentials");
    expect(tableNames).toContain("email_verification_tokens");
    expect(tableNames).toContain("password_reset_tokens");
    expect(tableNames).toContain("admin_audit_log");
    expect(tableNames).toContain("auth_rate_limits");
    expect(tableNames).toContain("article_content_cache");
    expect(tableNames).toContain("article_media_cache");
    expect(tableNames).toContain("ai_provider_configs");
    expect(tableNames).toContain("ai_provider_health");
    expect(tableNames).toContain("transcript_job_followers");
    expect(tableNames).toContain("artifact_job_inputs");

    // Migration 0009 rebuilds identities to allow provider IN ('google', 'email').
    const identityCols = db.prepare("PRAGMA table_info(identities)").all() as Array<{ name: string }>;
    expect(identityCols.map((c) => c.name)).toContain("provider");
    db.prepare("INSERT INTO users (id) VALUES ('u-email-probe')").run();
    db.prepare(
      "INSERT INTO identities (provider, provider_subject, user_id, email) VALUES ('email', 'cred-1', 'u-email-probe', 'a@example.com')"
    ).run();

    // Bootstrap singleton is seeded exactly once with the window open.
    const bootstrap = db.prepare("SELECT * FROM bootstrap_state").get() as Record<string, unknown>;
    expect(bootstrap.singleton_id).toBe(1);
    expect(bootstrap.first_admin_user_id).toBeNull();
    expect(bootstrap.web_registration_enabled).toBe(1);

    const settings = db.prepare("SELECT value_json FROM system_settings WHERE key = 'registration_mode'").get() as Record<string, unknown>;
    expect(settings.value_json).toBe('"CLOSED"');

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

    // Migration 0015: per-user follower requests for one shared in-flight generation job.
    const now = Date.now();
    db.prepare(`
      INSERT INTO artifact_jobs (id, dedupe_key, user_id, content_id, artifact_type, language, requested_version, status, attempt_count, created_at, updated_at)
      VALUES ('job_leader', 'cnt_1:en:1', 'usr_creator', 'cnt_1', 'transcript', 'en', 1, 'queued', 1, ?, ?)
    `).run(now, now);
    db.prepare(`
      INSERT INTO transcript_job_followers (
        id, job_id, user_id, content_id, language, status, attempt_count, error_code,
        normal_units, quota_multiplier, quota_units, artifact_id, created_at, updated_at
      ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
    `).run("jobu_follower", "job_leader", "usr_follower", "cnt_1", "en", "queued", 1, null, 60, 0.2, 12, null, now, now);
    db.prepare(`
      INSERT INTO transcript_job_followers (
        id, job_id, user_id, content_id, language, status, attempt_count, error_code,
        normal_units, quota_multiplier, quota_units, artifact_id, created_at, updated_at
      ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
    `).run("jobu_other", "job_leader", "usr_other", "cnt_1", "en", "queued", 1, null, 60, 0.2, 12, null, now, now);

    const followerRow = db.prepare("SELECT * FROM transcript_job_followers WHERE id = ?").get("jobu_follower") as Record<string, unknown>;
    expect(followerRow.job_id).toBe("job_leader");
    expect(followerRow.status).toBe("queued");
    expect(followerRow.quota_multiplier).toBe(0.2);

    // A user may hold at most one follower request per generation job.
    expect(() =>
      db
        .prepare(`
          INSERT INTO transcript_job_followers (id, job_id, user_id, content_id, language, status, attempt_count, created_at, updated_at)
          VALUES ('jobu_dupe', 'job_leader', 'usr_follower', 'cnt_1', 'en', 'queued', 1, ?, ?)
        `)
        .run(now, now)
    ).toThrow();

    // Soft-deleting an account purges that account's follower requests.
    db.prepare("INSERT INTO users (id) VALUES ('usr_follower')").run();
    db.prepare("UPDATE users SET status = 'deleted' WHERE id = 'usr_follower'").run();
    const purged = db
      .prepare("SELECT COUNT(*) AS count FROM transcript_job_followers WHERE user_id = 'usr_follower'")
      .get() as { count: number };
    expect(purged.count).toBe(0);

    // Removing the generation job releases the remaining follower's reservation and marks
    // the follower failed instead of leaving a dangling queued row.
    db.prepare(`
      INSERT INTO credit_transactions (id, user_id, operation_type, reference_id, units, multiplier, status, created_at)
      VALUES ('tx_follower', 'usr_other', 'transcript_shared', 'jobu_other', 12, 0.2, 'RESERVED', ?)
    `).run(now);
    db.prepare("DELETE FROM artifact_jobs WHERE id = 'job_leader'").run();
    const releasedTx = db.prepare("SELECT status FROM credit_transactions WHERE id = 'tx_follower'").get() as { status: string };
    expect(releasedTx.status).toBe("RELEASED");
    const failedFollower = db.prepare("SELECT status, error_code FROM transcript_job_followers WHERE id = 'jobu_other'").get() as { status: string; error_code: string };
    expect(failedFollower.status).toBe("failed");
    expect(failedFollower.error_code).toBe("job_removed");

    db.close();
  });
});

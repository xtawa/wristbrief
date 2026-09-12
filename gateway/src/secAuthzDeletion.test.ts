// @ts-nocheck
import { describe, expect, it } from "vitest";
import { DatabaseSync } from "node:sqlite";
import { readFileSync } from "node:fs";
import { join } from "node:path";
import { validateAudioUrl } from "./content/contentResolver";
import { D1ContentStore } from "./content/contentStore";
import { ContentResolver } from "./content/contentResolver";
import { D1ArtifactStore } from "./artifacts/artifactStore";
import { D1QuotaLedgerStore } from "./artifacts/quotaLedger";
import { TranscriptService } from "./artifacts/transcriptService";
import { InMemoryTranscriptStorage } from "./artifacts/transcriptStorage";
import {
  handleTranscriptRequest,
  handleTranscriptGet,
  handleTranscriptStatus
} from "./artifacts/transcriptRoutes";
import { deleteAccount } from "./authServer";

function createTestDatabase(): D1Database {
  const sqlite = new DatabaseSync(":memory:");
  const migrationsDir = join(process.cwd(), "migrations");
  const files = [
    "0001_membership.sql",
    "0002_play_billing.sql",
    "0003_google_identity.sql",
    "0004_account_sessions.sql",
    "0005_play_rtdn_dedup.sql",
    "0006_legacy_migration_grants.sql",
    "0007_play_purchase_entitlements.sql",
    "0008_cloud_sync_and_content_registry.sql"
  ];
  for (const file of files) {
    const sql = readFileSync(join(migrationsDir, file), "utf-8");
    sqlite.exec(sql);
  }

  const d1 = {
    prepare(sql: string) {
      return {
        bind(...values: unknown[]) {
          return {
            async first<T = Record<string, unknown>>(): Promise<T | null> {
              const stmt = sqlite.prepare(sql);
              const normalized = values.map((v) => (v === undefined ? null : v));
              const row = stmt.get(...normalized);
              return (row as T) || null;
            },
            async all<T = Record<string, unknown>>(): Promise<{ results: T[] }> {
              const stmt = sqlite.prepare(sql);
              const normalized = values.map((v) => (v === undefined ? null : v));
              const rows = stmt.all(...normalized);
              return { results: (rows as T[]) || [] };
            },
            async run() {
              const stmt = sqlite.prepare(sql);
              const normalized = values.map((v) => (v === undefined ? null : v));
              stmt.run(...normalized);
              return { success: true };
            }
          };
        }
      };
    },
    async batch(statements: Array<{ run(): Promise<unknown> }>) {
      for (const stmt of statements) {
        await stmt.run();
      }
      return [];
    }
  } as unknown as D1Database;

  return d1;
}

describe("SEC-01 & SEC-02: Object-level Authz and Share Policy", () => {
  it("validates audio URLs for HTTPS and blocks SSRF / loopback / private IPs", () => {
    expect(validateAudioUrl("http://example.com/audio.mp3").ok).toBe(false);
    expect(validateAudioUrl("ftp://example.com/audio.mp3").ok).toBe(false);
    expect(validateAudioUrl("https://localhost/audio.mp3").ok).toBe(false);
    expect(validateAudioUrl("https://127.0.0.1/audio.mp3").ok).toBe(false);
    expect(validateAudioUrl("https://10.1.2.3/audio.mp3").ok).toBe(false);
    expect(validateAudioUrl("https://192.168.1.10/audio.mp3").ok).toBe(false);
    expect(validateAudioUrl("https://172.20.1.5/audio.mp3").ok).toBe(false);
    expect(validateAudioUrl("https://169.254.169.254/latest/meta-data").ok).toBe(false);
    expect(validateAudioUrl("https://service.internal/audio.mp3").ok).toBe(false);
    expect(validateAudioUrl("https://app.local/audio.mp3").ok).toBe(false);

    const valid = validateAudioUrl("https://cdn.example.com/podcast/ep1.mp3?utm_source=rss");
    expect(valid.ok).toBe(true);
    expect(valid.normalizedUrl).toBe("https://cdn.example.com/podcast/ep1.mp3");
  });

  it("enforces object-level job status isolation: User B cannot access User A's job", async () => {
    const db = createTestDatabase();
    const env = { ACCOUNT_DB: db, TRANSCRIPT_STORAGE: new InMemoryTranscriptStorage() };
    const userA = "usr_alice";
    const userB = "usr_bob";

    const reqA = new Request("https://gateway.internal/v1/transcripts/request", {
      method: "POST",
      body: JSON.stringify({
        episode: {
          audioUrl: "https://podcasts.example.com/episodes/1.mp3",
          title: "Episode 1"
        }
      })
    });

    const resA = await handleTranscriptRequest(reqA, env, userA);
    expect(resA.status).toBe(202);
    const jobId = (resA.body as { jobId: string }).jobId;
    expect(jobId).toBeDefined();

    // User A can view their own job
    const statusA = await handleTranscriptStatus(jobId, env, userA);
    expect(statusA.status).toBe(200);

    // User B tries to view User A's job -> 404 job_not_found (SEC-01)
    const statusB = await handleTranscriptStatus(jobId, env, userB);
    expect(statusB.status).toBe(404);
    expect(statusB.body).toEqual({ error: "job_not_found" });
  });

  it("protects private transcript artifacts: User B cannot read User A's private transcript", async () => {
    const db = createTestDatabase();
    const storage = new InMemoryTranscriptStorage();
    const env = { ACCOUNT_DB: db, TRANSCRIPT_STORAGE: storage };
    const userA = "usr_alice";
    const userB = "usr_bob";

    const contentStore = new D1ContentStore(db);
    const contentResolver = new ContentResolver(contentStore);
    const artifactStore = new D1ArtifactStore(db);
    const quotaStore = new D1QuotaLedgerStore(db);
    const service = new TranscriptService(contentResolver, artifactStore, quotaStore, env);

    // User A requests private transcript
    const reqA = new Request("https://gateway.internal/v1/transcripts/request", {
      method: "POST",
      body: JSON.stringify({
        episode: {
          audioUrl: "https://cdn.example.com/private-recording.mp3",
          title: "Private Meeting"
        },
        sharePolicy: "PRIVATE_ACCOUNT"
      })
    });

    const resA = await handleTranscriptRequest(reqA, env, userA);
    expect(resA.status).toBe(202);
    const { jobId, contentCode } = resA.body as { jobId: string; contentCode: string };

    // Complete the job with transcript payload and storage
    const payload = {
      schemaVersion: "1" as const,
      contentCode,
      language: "en",
      durationMs: 60000,
      fullText: "Confidential discussion",
      segments: [{ id: 1, startMs: 0, endMs: 5000, text: "Confidential discussion" }]
    };
    await service.completeJobWithArtifact(jobId, payload, storage);

    // User A can read the private transcript
    const getA = await handleTranscriptGet(contentCode, env, userA);
    expect(getA.status).toBe(200);
    const bodyA = getA.body as Record<string, unknown>;
    expect(bodyA.sharePolicy).toBe("PRIVATE_ACCOUNT");
    expect(bodyA.transcript).toBeDefined();
    expect((bodyA.transcript as { fullText: string }).fullText).toBe("Confidential discussion");

    // User B CANNOT read User A's private transcript (gets 404)
    const getB = await handleTranscriptGet(contentCode, env, userB);
    expect(getB.status).toBe(404);
    expect(getB.body).toEqual({ error: "transcript_not_found" });
  });

  it("allows different users to access PUBLIC_REUSE transcripts", async () => {
    const db = createTestDatabase();
    const storage = new InMemoryTranscriptStorage();
    const env = { ACCOUNT_DB: db, TRANSCRIPT_STORAGE: storage };
    const userA = "usr_alice";
    const userB = "usr_bob";

    const contentStore = new D1ContentStore(db);
    const contentResolver = new ContentResolver(contentStore);
    const artifactStore = new D1ArtifactStore(db);
    const quotaStore = new D1QuotaLedgerStore(db);
    const service = new TranscriptService(contentResolver, artifactStore, quotaStore, env);

    const reqA = new Request("https://gateway.internal/v1/transcripts/request", {
      method: "POST",
      body: JSON.stringify({
        episode: {
          feedUrl: "https://feed.example.com/rss.xml",
          audioUrl: "https://cdn.example.com/public-show.mp3",
          title: "Public Show"
        },
        sharePolicy: "PUBLIC_REUSE"
      })
    });

    const resA = await handleTranscriptRequest(reqA, env, userA);
    expect(resA.status).toBe(202);
    const { jobId, contentCode } = resA.body as { jobId: string; contentCode: string };

    const payload = {
      schemaVersion: "1" as const,
      contentCode,
      language: "en",
      durationMs: 60000,
      fullText: "Public podcast content",
      segments: [{ id: 1, startMs: 0, endMs: 3000, text: "Public podcast content" }]
    };
    await service.completeJobWithArtifact(jobId, payload, storage);

    // User B CAN read public transcript
    const getB = await handleTranscriptGet(contentCode, env, userB);
    expect(getB.status).toBe(200);
    const bodyB = getB.body as Record<string, unknown>;
    expect(bodyB.sharePolicy).toBe("PUBLIC_REUSE");
    expect((bodyB.transcript as { fullText: string }).fullText).toBe("Public podcast content");
  });
});

describe("SEC-03: Account Deletion Complete Cleanup", () => {
  it("cascades deletion to all 0008 tables and private artifacts", async () => {
    const db = createTestDatabase();
    const env = { ACCOUNT_DB: db };
    const userId = "usr_to_delete";

    // Setup user rows across all 0008 tables
    await db.prepare("INSERT INTO users (id, status, created_at, updated_at) VALUES (?, 'active', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)").bind(userId).run();
    const dummyHash = "a".repeat(64);
    await db.prepare("INSERT INTO sessions (id, user_id, token_hash, created_at, expires_at) VALUES ('sess_1', ?, ?, CURRENT_TIMESTAMP, '2099-01-01')").bind(userId, dummyHash).run();
    await db.prepare("INSERT INTO devices (id, user_id, platform, created_at, last_seen_at) VALUES ('dev_1', ?, 'android', 1000, 1000)").bind(userId).run();
    await db.prepare("INSERT INTO user_subscriptions (user_id, subscription_id, feed_url, enabled, send_to_watch, revision, updated_at) VALUES (?, 'sub_1', 'https://feed.com/rss', 1, 1, 1, 1000)").bind(userId).run();
    await db.prepare("INSERT INTO user_item_states (user_id, item_id, is_read, is_saved, revision, updated_at) VALUES (?, 'item_1', 1, 0, 1, 1000)").bind(userId).run();
    await db.prepare("INSERT INTO user_playback_progress (user_id, content_id, position_ms, duration_ms, playback_speed, completed, revision, updated_at) VALUES (?, 'cnt_1', 500, 1000, 1.0, 0, 1, 1000)").bind(userId).run();
    await db.prepare("INSERT INTO user_sync_cursors (user_id, device_id, cursor_value, updated_at) VALUES (?, 'dev_1', 42, 1000)").bind(userId).run();
    await db.prepare("INSERT INTO user_artifact_access (user_id, artifact_id, content_id, access_source, first_accessed_at, last_accessed_at, quota_units_charged) VALUES (?, 'art_1', 'cnt_1', 'creator', 1000, 1000, 1.0)").bind(userId).run();
    await db.prepare("INSERT INTO artifact_jobs (id, dedupe_key, user_id, content_id, artifact_type, language, status, attempt_count, created_at, updated_at) VALUES ('job_del', 'dedupe_del', ?, 'cnt_1', 'transcript', 'en', 'queued', 1, 1000, 1000)").bind(userId).run();
    await db.prepare("INSERT INTO credit_transactions (id, user_id, operation_type, reference_id, units, multiplier, status, created_at) VALUES ('tx_del', ?, 'transcript_generation', 'job_del', 1.0, 1.0, 'RESERVED', 1000)").bind(userId).run();
    await db.prepare("INSERT INTO transcript_artifacts (id, content_id, language, artifact_version, status, share_policy, created_by_user_id, created_at, updated_at) VALUES ('art_priv', 'cnt_priv', 'en', 1, 'ready', 'PRIVATE_ACCOUNT', ?, 1000, 1000)").bind(userId).run();

    // Call deleteAccount
    const res = await deleteAccount({ id: userId, email: "del@example.com" }, env);
    expect(res.status).toBe(204);

    // Verify all 0008 tables are empty for this user
    const dev = await db.prepare("SELECT * FROM devices WHERE user_id = ?").bind(userId).first();
    expect(dev).toBeNull();
    const sub = await db.prepare("SELECT * FROM user_subscriptions WHERE user_id = ?").bind(userId).first();
    expect(sub).toBeNull();
    const item = await db.prepare("SELECT * FROM user_item_states WHERE user_id = ?").bind(userId).first();
    expect(item).toBeNull();
    const prog = await db.prepare("SELECT * FROM user_playback_progress WHERE user_id = ?").bind(userId).first();
    expect(prog).toBeNull();
    const cur = await db.prepare("SELECT * FROM user_sync_cursors WHERE user_id = ?").bind(userId).first();
    expect(cur).toBeNull();
    const access = await db.prepare("SELECT * FROM user_artifact_access WHERE user_id = ?").bind(userId).first();
    expect(access).toBeNull();
    const job = await db.prepare("SELECT * FROM artifact_jobs WHERE user_id = ?").bind(userId).first();
    expect(job).toBeNull();
    const tx = await db.prepare("SELECT * FROM credit_transactions WHERE user_id = ?").bind(userId).first();
    expect(tx).toBeNull();
    const privArt = await db.prepare("SELECT * FROM transcript_artifacts WHERE created_by_user_id = ? AND share_policy = 'PRIVATE_ACCOUNT'").bind(userId).first();
    expect(privArt).toBeNull();

    // User marked as deleted
    const userRow = await db.prepare("SELECT status FROM users WHERE id = ?").bind(userId).first<{ status: string }>();
    expect(userRow?.status).toBe("deleted");
  });
});

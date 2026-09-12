import { describe, expect, it } from "vitest";
import { createMigratedTestDb } from "../testDbHelper";
import { ContentResolver } from "../content/contentResolver";
import { D1ContentStore } from "../content/contentStore";
import { D1ArtifactStore } from "./artifactStore";
import { D1QuotaLedgerStore } from "./quotaLedger";
import { TranscriptService } from "./transcriptService";
import { InMemoryTranscriptStorage } from "./transcriptStorage";
import { type TranscriptRequestInput } from "./transcriptTypes";

class FailingCreateJobStore extends D1ArtifactStore {
  override async createJob(): Promise<never> {
    throw new Error("create_job_failed");
  }
}

class FailingTextStorage extends InMemoryTranscriptStorage {
  private writes = 0;

  override async put(key: string, data: string, contentType?: string): Promise<void> {
    this.writes += 1;
    if (this.writes === 2) throw new Error("storage_write_failed");
    await super.put(key, data);
  }
}

describe("TranscriptService, Shared Cache & 1/5 Quota", () => {
  it("executes complete lifecycle: 1.0x generation, 0.2x shared cache hit, 0x reopen, and single-flight dedup", async () => {
    const { d1 } = createMigratedTestDb();
    const contentStore = new D1ContentStore(d1);
    const contentResolver = new ContentResolver(contentStore, { publicReuseHosts: ["example.com"] });
    const artifactStore = new D1ArtifactStore(d1);
    const quotaStore = new D1QuotaLedgerStore(d1);

    const service = new TranscriptService(contentResolver, artifactStore, quotaStore);

    const episodeInput: TranscriptRequestInput = {
      sharePolicy: "PUBLIC_REUSE",
      episode: {
        feedUrl: "https://example.com/tech-podcast.xml",
        guid: "ep-100",
        audioUrl: "https://cdn.example.com/ep-100.mp3",
        title: "Episode 100: Grand Special",
        durationMs: 3600000 // 60 minutes = 60 normal units
      }
    };

    // --- Scenario 1: User A requests transcript (Cache miss -> 1.0x quota reservation) ---
    const userA = "usr_alice";
    const resA = await service.requestTranscript(userA, episodeInput);

    expect(resA.status).toBe("processing");
    if (resA.status === "processing") {
      expect(resA.jobId).toMatch(/^job_/);
      expect(resA.contentCode).toMatch(/^WBEP-/);

      // Verify 1.0x quota reservation in ledger (60 units * 1.0 = 60)
      const tx = await quotaStore.findByReference(userA, "transcript_generation", resA.jobId);
      expect(tx).toBeDefined();
      expect(tx?.status).toBe("RESERVED");
      expect(tx?.units).toBe(60);
      expect(tx?.multiplier).toBe(1.0);

      // --- Scenario 2: User C requests while job is still processing (Single-flight join) ---
      const userC = "usr_charlie";
      const resC = await service.requestTranscript(userC, episodeInput);
      expect(resC.status).toBe("processing");
      if (resC.status === "processing") {
        // Must join the existing job!
        expect(resC.jobId).toBe(resA.jobId);
      }

      // Complete job with simulated transcription artifact
      const artifact = await service.completeJobWithArtifact(resA.jobId, {
        schemaVersion: "1",
        contentCode: resA.contentCode,
        language: "en",
        durationMs: 3600000,
        fullText: "Welcome to Episode 100 of the Grand Special.",
        segments: [
          { id: 1, startMs: 0, endMs: 5000, text: "Welcome to Episode 100" },
          { id: 2, startMs: 5000, endMs: 10000, text: "of the Grand Special." }
        ]
      }, new InMemoryTranscriptStorage());
      expect(artifact.id).toMatch(/^art_/);
      expect(artifact.status).toBe("ready");

      // Verify User A quota transaction is COMMITTED
      const committedTx = await quotaStore.findByReference(userA, "transcript_generation", resA.jobId);
      expect(committedTx?.status).toBe("COMMITTED");
    }

    // --- Scenario 3: User B requests same episode (Shared Cache Hit -> 0.2x quota charged) ---
    const userB = "usr_bob";
    const resB = await service.requestTranscript(userB, episodeInput);

    expect(resB.status).toBe("ready");
    if (resB.status === "ready") {
      expect(resB.source).toBe("shared_cache");
      expect(resB.quota.normalUnits).toBe(60);
      expect(resB.quota.multiplier).toBe(0.2);
      expect(resB.quota.chargedUnits).toBe(12); // 60 * 0.2 = 12

      // Verify User B quota transaction is COMMITTED in ledger
      const txB = await quotaStore.findByReference(userB, "transcript_shared", resB.artifactId);
      expect(txB?.status).toBe("COMMITTED");
      expect(txB?.units).toBe(12);
    }

    // --- Scenario 4: User B reopens the same transcript (Existing Access -> 0x quota charged) ---
    const resBReopen = await service.requestTranscript(userB, episodeInput);

    expect(resBReopen.status).toBe("ready");
    if (resBReopen.status === "ready") {
      expect(resBReopen.source).toBe("existing_access");
      expect(resBReopen.quota.multiplier).toBe(0);
      expect(resBReopen.quota.chargedUnits).toBe(0);
    }
  });

  it("releases a generation reservation when job creation fails", async () => {
    const { d1 } = createMigratedTestDb();
    const contentResolver = new ContentResolver(new D1ContentStore(d1));
    const artifactStore = new FailingCreateJobStore(d1);
    const quotaStore = new D1QuotaLedgerStore(d1);
    const service = new TranscriptService(contentResolver, artifactStore, quotaStore);

    await expect(service.requestTranscript("usr_fail", {
      episode: { audioUrl: "https://cdn.example.com/fail.mp3", durationMs: 60000 }
    })).rejects.toThrow("create_job_failed");

    const tx = await d1.prepare("SELECT status FROM credit_transactions LIMIT 1").first<{ status: string }>();
    expect(tx?.status).toBe("RELEASED");
  });

  it("can retry a failed queued job without violating the dedupe unique key", async () => {
    const { d1 } = createMigratedTestDb();
    const contentResolver = new ContentResolver(new D1ContentStore(d1));
    const artifactStore = new D1ArtifactStore(d1);
    const quotaStore = new D1QuotaLedgerStore(d1);
    const failingQueue = { send: async () => { throw new Error("queue_down"); } } as unknown as Queue;
    const first = new TranscriptService(contentResolver, artifactStore, quotaStore, { TRANSCRIPT_QUEUE: failingQueue });

    await expect(first.requestTranscript("usr_retry", {
      episode: { audioUrl: "https://cdn.example.com/retry.mp3", durationMs: 60000 }
    })).rejects.toThrow("queue_down");

    const second = new TranscriptService(contentResolver, artifactStore, quotaStore);
    const retry = await second.requestTranscript("usr_retry", {
      episode: { audioUrl: "https://cdn.example.com/retry.mp3", durationMs: 60000 }
    });
    expect(retry.status).toBe("processing");
    if (retry.status === "processing") {
      const job = await artifactStore.getJobById(retry.jobId);
      expect(job?.status).toBe("queued");
      expect(job?.attemptCount).toBe(2);
    }
  });

  it("cleans partial transcript storage and releases quota when finalization fails", async () => {
    const { d1 } = createMigratedTestDb();
    const contentResolver = new ContentResolver(new D1ContentStore(d1));
    const artifactStore = new D1ArtifactStore(d1);
    const quotaStore = new D1QuotaLedgerStore(d1);
    const service = new TranscriptService(contentResolver, artifactStore, quotaStore);
    const requested = await service.requestTranscript("usr_storage", {
      sharePolicy: "PRIVATE_ACCOUNT",
      episode: { audioUrl: "https://cdn.example.com/storage-fail.mp3", durationMs: 60000 }
    });
    if (requested.status !== "processing") throw new Error("expected processing response");

    const storage = new FailingTextStorage();
    await expect(service.completeJobWithArtifact(requested.jobId, {
      schemaVersion: "1",
      contentCode: requested.contentCode,
      language: "en",
      durationMs: 60000,
      fullText: "hello",
      segments: [{ id: 1, startMs: 0, endMs: 1000, text: "hello" }]
    }, storage)).rejects.toThrow("storage_write_failed");

    expect(await storage.get(`transcripts/${(await new D1ContentStore(d1).findByContentCode(requested.contentCode))?.id}/en/1/transcript.json`)).toBeNull();
    const job = await artifactStore.getJobById(requested.jobId);
    expect(job?.status).toBe("failed");
    const tx = await quotaStore.findByReference("usr_storage", "transcript_generation", requested.jobId);
    expect(tx?.status).toBe("RELEASED");
  });
});

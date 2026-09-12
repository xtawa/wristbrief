import { describe, expect, it } from "vitest";
import { createMigratedTestDb } from "../testDbHelper";
import { ContentResolver } from "../content/contentResolver";
import { D1ContentStore } from "../content/contentStore";
import { D1ArtifactStore } from "./artifactStore";
import { D1QuotaLedgerStore } from "./quotaLedger";
import { TranscriptService } from "./transcriptService";
import { type TranscriptRequestInput } from "./transcriptTypes";

describe("TranscriptService, Shared Cache & 1/5 Quota", () => {
  it("executes complete lifecycle: 1.0x generation, 0.2x shared cache hit, 0x reopen, and single-flight dedup", async () => {
    const { d1 } = createMigratedTestDb();
    const contentStore = new D1ContentStore(d1);
    const contentResolver = new ContentResolver(contentStore);
    const artifactStore = new D1ArtifactStore(d1);
    const quotaStore = new D1QuotaLedgerStore(d1);

    const service = new TranscriptService(contentResolver, artifactStore, quotaStore);

    const episodeInput: TranscriptRequestInput = {
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
      });
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
});

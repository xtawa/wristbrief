import { ContentResolver, validateAudioUrl, type EpisodeMetadataInput } from "../content/contentResolver";
import { type ArtifactStore, type TranscriptArtifactRecord } from "./artifactStore";
import {
  calculateNormalTranscriptUnits,
  type QuotaLedgerStore
} from "./quotaLedger";
import { type TranscriptStorage } from "./transcriptStorage";
import {
  type TranscriptPayload,
  type TranscriptProcessingResponse,
  type TranscriptReadyResponse,
  type TranscriptRequestInput,
  type TranscriptRequestResponse
} from "./transcriptTypes";

export interface TranscriptServiceEnv {
  TRANSCRIPT_SHARED_CACHE_MULTIPLIER?: string | number;
  TRANSCRIPT_GENERATION_MULTIPLIER?: string | number;
  TRANSCRIPT_QUEUE?: Queue;
}

export class TranscriptService {
  private readonly sharedMultiplier: number;
  private readonly genMultiplier: number;
  private readonly queue?: Queue;

  constructor(
    private readonly contentResolver: ContentResolver,
    private readonly artifactStore: ArtifactStore,
    private readonly quotaStore: QuotaLedgerStore,
    env?: TranscriptServiceEnv
  ) {
    this.sharedMultiplier = Number(env?.TRANSCRIPT_SHARED_CACHE_MULTIPLIER ?? 0.2);
    this.genMultiplier = Number(env?.TRANSCRIPT_GENERATION_MULTIPLIER ?? 1.0);
    this.queue = env?.TRANSCRIPT_QUEUE;
  }

  async requestTranscript(
    userId: string,
    input: TranscriptRequestInput
  ): Promise<TranscriptRequestResponse> {
    const episode = input.episode;
    const urlCheck = validateAudioUrl(episode.audioUrl);
    if (!urlCheck.ok) {
      throw new Error(`invalid_audio_url: ${urlCheck.error}`);
    }
    const language = input.language || "auto";

    // 1. Resolve canonical content
    const resolveInput: EpisodeMetadataInput = {
      feedId: episode.feedId,
      feedUrl: episode.feedUrl,
      guid: episode.guid,
      audioUrl: episode.audioUrl,
      title: episode.title,
      publishedAt: episode.publishedAt,
      durationMs: episode.durationMs,
      sharePolicy: input.sharePolicy === "PUBLIC_REUSE" ? "PUBLIC_REUSE" : "PRIVATE_ACCOUNT",
      audioSha256: episode.audioSha256
    };

    const resolved = await this.contentResolver.resolve(resolveInput);
    const content = resolved.content;
    const normalUnits = calculateNormalTranscriptUnits(episode.durationMs);

    // 2. Check if user already has access to an existing artifact for this content (0x quota)
    const existingUserGrant = await this.artifactStore.getUserAccessForContent(userId, content.id);
    if (existingUserGrant) {
      const artifact = await this.artifactStore.findArtifactById(existingUserGrant.artifactId);
      if (artifact && artifact.status === "ready") {
        return {
          status: "ready",
          contentCode: content.contentCode,
          artifactId: artifact.id,
          source: "existing_access",
          quota: {
            normalUnits,
            multiplier: 0,
            chargedUnits: 0
          }
        };
      }
    }

    // 3. Check if a reusable shared artifact exists (0.2x quota)
    const sharedArtifact = await this.artifactStore.findPreferredArtifactForContent(content.id, language);
    if (sharedArtifact && sharedArtifact.status === "ready" && sharedArtifact.sharePolicy === "PUBLIC_REUSE") {
      // Shared cache hit: charge 0.2x
      const tx = await this.quotaStore.reserve(
        userId,
        "transcript_shared",
        sharedArtifact.id,
        normalUnits,
        this.sharedMultiplier
      );
      let accessGranted = false;
      try {
        await this.artifactStore.grantUserAccess(
          userId,
          sharedArtifact.id,
          content.id,
          "shared_cache",
          tx.units
        );
        accessGranted = true;
        await this.quotaStore.commit(tx.id);
      } catch (error) {
        try {
          if (accessGranted && this.artifactStore.revokeUserAccess) {
            await this.artifactStore.revokeUserAccess(userId, sharedArtifact.id);
          }
          if (tx.status === "RESERVED") await this.quotaStore.release(tx.id);
        } catch {
          // Preserve the original failure; cleanup is best effort.
        }
        throw error;
      }

      return {
        status: "ready",
        contentCode: content.contentCode,
        artifactId: sharedArtifact.id,
        source: "shared_cache",
        quota: {
          normalUnits,
          multiplier: this.sharedMultiplier,
          chargedUnits: tx.units
        }
      };
    }

    // 4. Cache miss: Need new generation (1.0x quota)
    // Check single-flight deduplication on active jobs
    let dedupeKey = `${content.id}:${language}:1`;
    let existingJob = await this.artifactStore.getJobByDedupeKey(dedupeKey);

    // A failed job can be retried by its creator. A different user must get a
    // separate job and quota reservation rather than reusing the creator's ID.
    if (existingJob?.status === "failed" && existingJob.userId !== userId) {
      dedupeKey = `${dedupeKey}:user:${userId}`;
      existingJob = await this.artifactStore.getJobByDedupeKey(dedupeKey);
    }

    if (existingJob && (existingJob.status === "queued" || existingJob.status === "running")) {
      return {
        status: "processing",
        contentCode: content.contentCode,
        jobId: existingJob.id
      };
    }

    if (existingJob && existingJob.status === "failed") {
      const retryTx = await this.quotaStore.reserve(
        userId,
        "transcript_generation",
        existingJob.id,
        normalUnits,
        this.genMultiplier
      );
      if (retryTx.status !== "RESERVED" || !this.artifactStore.resetJobForRetry) {
        if (retryTx.status === "RESERVED") await this.quotaStore.release(retryTx.id);
        throw new Error("transcript_retry_unavailable");
      }
      try {
        await this.artifactStore.resetJobForRetry(existingJob.id);
        await this.enqueueJob(existingJob.id, content.id, language);
      } catch (error) {
        try {
          await this.quotaStore.release(retryTx.id);
          await this.artifactStore.updateJobStatus(existingJob.id, "failed", "queue_or_job_retry_failed");
        } catch {
          // Preserve the original failure; cleanup is best effort.
        }
        throw error;
      }
      return { status: "processing", contentCode: content.contentCode, jobId: existingJob.id };
    }

    // Create new background job with 1.0x reservation
    const jobId = `job_${crypto.randomUUID()}`;
    const tx = await this.quotaStore.reserve(
      userId,
      "transcript_generation",
      jobId,
      normalUnits,
      this.genMultiplier
    );
    try {
      const job = await this.artifactStore.createJob({
        id: jobId,
        dedupeKey,
        userId,
        contentId: content.id,
        artifactType: "transcript",
        language,
        requestedVersion: 1,
        status: "queued",
        leaseOwner: null,
        leaseExpiresAt: null,
        attemptCount: 1,
        errorCode: null
      });
      await this.enqueueJob(job.id, content.id, language);
      return { status: "processing", contentCode: content.contentCode, jobId: job.id };
    } catch (error) {
      try {
        if (tx.status === "RESERVED") await this.quotaStore.release(tx.id);
        await this.artifactStore.updateJobStatus(jobId, "failed", "queue_or_job_creation_failed");
      } catch {
        // Preserve the original failure; cleanup is best effort.
      }
      throw error;
    }
  }

  async getTranscriptMetadata(contentCode: string): Promise<TranscriptArtifactRecord | null> {
    const content = await this.contentResolver.getByContentCode(contentCode);
    if (!content) return null;
    return this.artifactStore.findPreferredArtifactForContent(content.id);
  }

  async completeJobWithArtifact(
    jobId: string,
    transcript: TranscriptPayload,
    storage?: TranscriptStorage
  ): Promise<TranscriptArtifactRecord> {
    const job = await this.artifactStore.getJobById(jobId);
    if (!job) throw new Error(`Job not found: ${jobId}`);
    if (!storage) throw new Error("transcript_storage_unavailable");

    if (job.status === "completed") {
      const existingArtifact = await this.artifactStore.findPreferredArtifactForContent(job.contentId, job.language);
      if (existingArtifact) return existingArtifact;
    }

    const content = await this.contentResolver.getById(job.contentId);
    const sharePolicy = content?.sharePolicy === "PRIVATE_ACCOUNT" ? "PRIVATE_ACCOUNT" : "PUBLIC_REUSE";

    const artifactId = `art_${crypto.randomUUID()}`;
    const wordCount = transcript.fullText.split(/\s+/).filter(Boolean).length;
    const segmentCount = transcript.segments.length;

    const objectKeyJson = `transcripts/${job.contentId}/${job.language}/1/transcript.json`;
    const objectKeyText = `transcripts/${job.contentId}/${job.language}/1/transcript.txt`;
    const objectKeySegments = `transcripts/${job.contentId}/${job.language}/1/segments.json`;

    let artifactCreated = false;
    try {
      await storage.put(objectKeyJson, JSON.stringify(transcript));
      await storage.put(objectKeyText, transcript.fullText, "text/plain");
      await storage.put(objectKeySegments, JSON.stringify(transcript.segments));

      const artifact = await this.artifactStore.createArtifact({
        id: artifactId,
        contentId: job.contentId,
        language: job.language,
        artifactVersion: job.requestedVersion,
        provider: "managed",
        model: "whisper-large-v3",
        status: "ready",
        objectKeyJson,
        objectKeyText,
        objectKeySegments,
        transcriptHash: null,
        wordCount,
        segmentCount,
        qualityScore: 1.0,
        sharePolicy,
        createdByUserId: job.userId
      });
      artifactCreated = true;

      await this.artifactStore.grantUserAccess(job.userId, artifactId, job.contentId, "creator", 1.0);
      const tx = await this.quotaStore.findByReference(job.userId, "transcript_generation", jobId);
      if (tx && tx.status === "RESERVED") await this.quotaStore.commit(tx.id);
      await this.artifactStore.updateJobStatus(jobId, "completed");
      return artifact;
    } catch (error) {
      try {
        if (artifactCreated && this.artifactStore.deleteArtifact) await this.artifactStore.deleteArtifact(artifactId);
        await storage.delete(objectKeySegments);
        await storage.delete(objectKeyText);
        await storage.delete(objectKeyJson);
        await this.failJob(jobId, "artifact_finalize_failed");
      } catch {
        // Preserve the original failure; cleanup is best effort.
      }
      throw error;
    }
  }

  async failJob(jobId: string, errorCode = "generation_failed"): Promise<void> {
    const job = await this.artifactStore.getJobById(jobId);
    if (!job) return;

    // Release quota reservation on failure
    const tx = await this.quotaStore.findByReference(job.userId, "transcript_generation", jobId);
    if (tx && tx.status === "RESERVED") {
      await this.quotaStore.release(tx.id);
    }

    await this.artifactStore.updateJobStatus(jobId, "failed", errorCode);
  }

  private async enqueueJob(jobId: string, contentId: string, language: string): Promise<void> {
    if (!this.queue) return;
    await this.queue.send({ jobId, contentId, language, artifactType: "transcript" });
  }
}

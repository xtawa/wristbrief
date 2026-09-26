import { ContentResolver, validateAudioUrl, type EpisodeMetadataInput } from "../content/contentResolver";
import { type PodcastContent } from "../content/contentStore";
import {
  type ArtifactJobRecord,
  type ArtifactStore,
  type JobFollowerRecord,
  type TranscriptArtifactRecord
} from "./artifactStore";
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

/**
 * Transcript generation is deduplicated per share scope:
 *
 * - PUBLIC_REUSE content: one generation job (the "leader") serves every requester for a
 *   content+language+version. A second user who arrives while it is in flight gets their
 *   own follower row (`jobu_...`) so they can poll their own status, and is granted access
 *   + charged the 0.2x shared-cache rate only when the artifact is actually readable.
 * - PRIVATE_ACCOUNT content: sharing does not apply, so every account gets its own
 *   generation job, its own artifact version, and its own 1.0x charge. One user can never
 *   join, read, or be billed against another user's private artifact.
 *
 * Share scope is the server-resolved `content.sharePolicy`, never the caller's request
 * alone. `PUBLIC_REUSE_FEED_HOSTS` is empty by default, which fails closed: every content
 * resolves as PRIVATE_ACCOUNT, so the follower/0.2x tier only becomes reachable for feed
 * hosts an operator has explicitly allowlisted.
 */
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

    // 3. Check if a reusable public artifact exists (0.2x quota). Private artifacts are
    //    never reusable across accounts, so they are excluded here.
    const sharedArtifact = await this.artifactStore.findPreferredArtifactForContent(content.id, language);
    if (sharedArtifact && sharedArtifact.status === "ready" && sharedArtifact.sharePolicy === "PUBLIC_REUSE") {
      return this.claimSharedArtifact(userId, content.contentCode, sharedArtifact, normalUnits);
    }

    // 4. Cache miss: dedupe by share scope.
    if (content.sharePolicy === "PUBLIC_REUSE") {
      return this.requestPublicGeneration(userId, content, language, episode, normalUnits);
    }
    return this.requestPrivateGeneration(userId, content, language, episode, normalUnits);
  }

  async getTranscriptMetadata(contentCode: string): Promise<TranscriptArtifactRecord | null> {
    const content = await this.contentResolver.getByContentCode(contentCode);
    if (!content) return null;
    return this.artifactStore.findPreferredArtifactForContent(content.id);
  }

  async completeJobWithArtifact(
    jobId: string,
    transcript: TranscriptPayload,
    storage?: TranscriptStorage,
    source: { provider: string; model: string } = { provider: "managed", model: "unknown" }
  ): Promise<TranscriptArtifactRecord> {
    const job = await this.artifactStore.getJobById(jobId);
    if (!job) throw new Error(`Job not found: ${jobId}`);
    if (!storage) throw new Error("transcript_storage_unavailable");

    if (job.status === "completed") {
      const existingArtifact = await this.artifactStore.findReadableArtifactForContent(
        job.userId,
        job.contentId,
        job.language
      );
      if (existingArtifact) {
        await this.settleFollowers(jobId, existingArtifact);
        return existingArtifact;
      }
    }

    const content = await this.contentResolver.getById(job.contentId);
    const sharePolicy = content?.sharePolicy === "PRIVATE_ACCOUNT" ? "PRIVATE_ACCOUNT" : "PUBLIC_REUSE";

    const artifactId = `art_${crypto.randomUUID()}`;
    const wordCount = transcript.fullText.split(/\s+/).filter(Boolean).length;
    const segmentCount = transcript.segments.length;

    const version = job.requestedVersion;
    const objectKeyJson = `transcripts/${job.contentId}/${job.language}/${version}/transcript.json`;
    const objectKeyText = `transcripts/${job.contentId}/${job.language}/${version}/transcript.txt`;
    const objectKeySegments = `transcripts/${job.contentId}/${job.language}/${version}/segments.json`;

    let artifact: TranscriptArtifactRecord;
    let artifactCreated = false;
    try {
      await storage.put(objectKeyJson, JSON.stringify(transcript));
      await storage.put(objectKeyText, transcript.fullText, "text/plain");
      await storage.put(objectKeySegments, JSON.stringify(transcript.segments));

      artifact = await this.artifactStore.createArtifact({
        id: artifactId,
        contentId: job.contentId,
        language: job.language,
        artifactVersion: version,
        provider: source.provider,
        model: source.model,
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

    // Followers are settled after the artifact exists: the shared-cache charge and the
    // access grant happen together with readability, so a follower can never read a
    // private transcript before being authorized for it. A follower-side failure must not
    // unpublish the finished artifact.
    await this.settleFollowers(jobId, artifact);
    return artifact;
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

    // Every follower of a failed leader must observe the same failure with the leader's
    // error code, and no follower may be left holding a reservation for work that
    // produced nothing.
    const followers = await this.artifactStore.listJobFollowers(jobId);
    for (const follower of followers) {
      const followerTx = await this.quotaStore.findByReference(
        follower.userId,
        "transcript_shared",
        follower.id
      );
      if (followerTx && followerTx.status === "RESERVED") {
        await this.quotaStore.release(followerTx.id);
      }
      if (follower.status === "completed") continue;
      await this.artifactStore.updateJobFollowerStatus(follower.id, "failed", errorCode);
    }
  }

  private async claimSharedArtifact(
    userId: string,
    contentCode: string,
    artifact: TranscriptArtifactRecord,
    normalUnits: number
  ): Promise<TranscriptReadyResponse> {
    const tx = await this.quotaStore.reserve(
      userId,
      "transcript_shared",
      artifact.id,
      normalUnits,
      this.sharedMultiplier
    );
    let accessGranted = false;
    try {
      await this.artifactStore.grantUserAccess(
        userId,
        artifact.id,
        artifact.contentId,
        "shared_cache",
        tx.units
      );
      accessGranted = true;
      await this.quotaStore.commit(tx.id);
    } catch (error) {
      try {
        if (accessGranted && this.artifactStore.revokeUserAccess) {
          await this.artifactStore.revokeUserAccess(userId, artifact.id);
        }
        if (tx.status === "RESERVED") await this.quotaStore.release(tx.id);
      } catch {
        // Preserve the original failure; cleanup is best effort.
      }
      throw error;
    }

    return {
      status: "ready",
      contentCode,
      artifactId: artifact.id,
      source: "shared_cache",
      quota: {
        normalUnits,
        multiplier: this.sharedMultiplier,
        chargedUnits: tx.units
      }
    };
  }

  private async requestPublicGeneration(
    userId: string,
    content: PodcastContent,
    language: string,
    episode: TranscriptRequestInput["episode"],
    normalUnits: number
  ): Promise<TranscriptRequestResponse> {
    const baseDedupeKey = `${content.id}:${language}:1`;
    const leader = await this.artifactStore.getJobByDedupeKey(baseDedupeKey);

    if (leader && (leader.status === "queued" || leader.status === "running")) {
      if (leader.userId === userId) {
        return this.processingResponse(content.contentCode, leader.id);
      }
      return this.joinPublicJob(userId, leader, content, normalUnits);
    }

    if (leader && leader.status === "failed") {
      if (leader.userId === userId) {
        return this.retryJobInPlace(userId, leader, content, language, episode, normalUnits);
      }
      // Another account's generation failed. Do not resurrect a job this user does not
      // own and must not be billed for: start a fresh, still-deduplicated generation.
      return this.startRetryGeneration(userId, content, language, episode, normalUnits, baseDedupeKey);
    }

    if (leader && leader.status === "completed") {
      return this.recoverCompletedGeneration(userId, content, language, normalUnits);
    }

    return this.createGeneration(userId, baseDedupeKey, content, language, episode, normalUnits);
  }

  private async requestPrivateGeneration(
    userId: string,
    content: PodcastContent,
    language: string,
    episode: TranscriptRequestInput["episode"],
    normalUnits: number
  ): Promise<TranscriptRequestResponse> {
    const existingJob = await this.artifactStore.findJobForUserContent(userId, content.id, language);
    if (existingJob && (existingJob.status === "queued" || existingJob.status === "running")) {
      return this.processingResponse(content.contentCode, existingJob.id);
    }
    if (existingJob && existingJob.status === "failed") {
      return this.retryJobInPlace(userId, existingJob, content, language, episode, normalUnits);
    }

    // PRIVATE_ACCOUNT content is generated per account, and transcript_artifacts is
    // UNIQUE(content_id, language, artifact_version), so this user's generation needs its
    // own version slot. Versions in use by artifact rows *and* by pending jobs are skipped,
    // which keeps two concurrent private requests from colliding.
    const version = await this.artifactStore.allocateArtifactVersion(content.id, language);
    const dedupeKey = `${content.id}:${language}:${version}:user:${userId}`;
    return this.createGeneration(userId, dedupeKey, content, language, episode, normalUnits, version);
  }

  private async joinPublicJob(
    userId: string,
    leader: ArtifactJobRecord,
    content: PodcastContent,
    normalUnits: number
  ): Promise<TranscriptProcessingResponse> {
    const existing = await this.artifactStore.getJobFollowerForUser(leader.id, userId);
    if (existing) {
      if (existing.status === "failed") {
        return this.retryFollower(existing, leader, content);
      }
      return this.processingResponse(content.contentCode, existing.id);
    }

    const followerId = `jobu_${crypto.randomUUID()}`;
    const tx = await this.quotaStore.reserve(
      userId,
      "transcript_shared",
      followerId,
      normalUnits,
      this.sharedMultiplier
    );
    try {
      await this.artifactStore.createJobFollower({
        id: followerId,
        jobId: leader.id,
        userId,
        contentId: content.id,
        language: leader.language,
        status: leader.status === "running" ? "running" : "queued",
        attemptCount: leader.attemptCount,
        errorCode: null,
        normalUnits,
        quotaMultiplier: this.sharedMultiplier,
        quotaUnits: tx.units,
        artifactId: null
      });
    } catch (error) {
      try {
        if (tx.status === "RESERVED") await this.quotaStore.release(tx.id);
      } catch {
        // Preserve the original failure; cleanup is best effort.
      }
      // Simultaneous requests from the same user race on UNIQUE(job_id, user_id); reuse
      // whichever follower row won instead of failing the request.
      const raced = await this.artifactStore
        .getJobFollowerForUser(leader.id, userId)
        .catch(() => null);
      if (raced) return this.processingResponse(content.contentCode, raced.id);
      throw error;
    }

    return this.processingResponse(content.contentCode, followerId);
  }

  private async retryFollower(
    follower: JobFollowerRecord,
    leader: ArtifactJobRecord,
    content: PodcastContent
  ): Promise<TranscriptProcessingResponse> {
    const tx = await this.quotaStore.reserve(
      follower.userId,
      "transcript_shared",
      follower.id,
      follower.normalUnits,
      follower.quotaMultiplier || this.sharedMultiplier
    );
    if (tx.status !== "RESERVED") {
      throw new Error("transcript_retry_unavailable");
    }
    await this.artifactStore.updateJobFollowerStatus(
      follower.id,
      leader.status === "running" ? "running" : "queued",
      null
    );
    return this.processingResponse(content.contentCode, follower.id);
  }

  private async startRetryGeneration(
    userId: string,
    content: PodcastContent,
    language: string,
    episode: TranscriptRequestInput["episode"],
    normalUnits: number,
    baseDedupeKey: string
  ): Promise<TranscriptRequestResponse> {
    const dedupeKey = await this.resolveRetryDedupeKey(baseDedupeKey);
    const existing = await this.artifactStore.getJobByDedupeKey(dedupeKey);
    if (existing && (existing.status === "queued" || existing.status === "running")) {
      if (existing.userId === userId) {
        return this.processingResponse(content.contentCode, existing.id);
      }
      return this.joinPublicJob(userId, existing, content, normalUnits);
    }
    return this.createGeneration(userId, dedupeKey, content, language, episode, normalUnits);
  }

  private async resolveRetryDedupeKey(baseDedupeKey: string): Promise<string> {
    // Retries must not violate the artifact_jobs dedupe unique key, and concurrent
    // retries should still share one work item, so try a small bounded series of
    // generation keys before falling back to a key that cannot collide.
    for (let generation = 2; generation <= 4; generation += 1) {
      const candidate = generation === 2 ? `${baseDedupeKey}#retry` : `${baseDedupeKey}#retry${generation - 1}`;
      const existing = await this.artifactStore.getJobByDedupeKey(candidate);
      if (!existing || existing.status === "queued" || existing.status === "running") {
        return candidate;
      }
    }
    return `${baseDedupeKey}#retry:${crypto.randomUUID()}`;
  }

  private async retryJobInPlace(
    userId: string,
    job: ArtifactJobRecord,
    content: PodcastContent,
    language: string,
    episode: TranscriptRequestInput["episode"],
    normalUnits: number
  ): Promise<TranscriptProcessingResponse> {
    const retryTx = await this.quotaStore.reserve(
      userId,
      "transcript_generation",
      job.id,
      normalUnits,
      this.genMultiplier
    );
    if (retryTx.status !== "RESERVED" || !this.artifactStore.resetJobForRetry) {
      if (retryTx.status === "RESERVED") await this.quotaStore.release(retryTx.id);
      throw new Error("transcript_retry_unavailable");
    }
    try {
      await this.artifactStore.resetJobForRetry(job.id);
      await this.enqueueJob(job.id, content.id, language, episode.audioUrl, episode.durationMs);
    } catch (error) {
      try {
        await this.quotaStore.release(retryTx.id);
        await this.artifactStore.updateJobStatus(job.id, "failed", "queue_or_job_retry_failed");
      } catch {
        // Preserve the original failure; cleanup is best effort.
      }
      throw error;
    }
    return this.processingResponse(content.contentCode, job.id);
  }

  private async recoverCompletedGeneration(
    userId: string,
    content: PodcastContent,
    language: string,
    normalUnits: number
  ): Promise<TranscriptRequestResponse> {
    const artifact = await this.artifactStore.findReadableArtifactForContent(
      userId,
      content.id,
      language
    );
    if (!artifact || artifact.status !== "ready") {
      throw new Error("transcript_artifact_unavailable");
    }
    if (artifact.sharePolicy === "PUBLIC_REUSE") {
      return this.claimSharedArtifact(userId, content.contentCode, artifact, normalUnits);
    }
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

  private async createGeneration(
    userId: string,
    dedupeKey: string,
    content: PodcastContent,
    language: string,
    episode: TranscriptRequestInput["episode"],
    normalUnits: number,
    requestedVersion = 1
  ): Promise<TranscriptProcessingResponse> {
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
        requestedVersion,
        status: "queued",
        leaseOwner: null,
        leaseExpiresAt: null,
        attemptCount: 1,
        errorCode: null
      });
      await this.enqueueJob(job.id, content.id, language, episode.audioUrl, episode.durationMs);
      return this.processingResponse(content.contentCode, job.id);
    } catch (error) {
      // Two near-simultaneous requests can race on the dedupe unique key. The loser must
      // not fail and must not start a second expensive generation: release its own
      // reservation and reuse the winner (joining it when it belongs to another user).
      const raced = await this.artifactStore.getJobByDedupeKey(dedupeKey).catch(() => null);
      if (raced && raced.id !== jobId && (raced.status === "queued" || raced.status === "running")) {
        try {
          if (tx.status === "RESERVED") await this.quotaStore.release(tx.id);
        } catch {
          // Preserve the original failure; cleanup is best effort.
        }
        if (raced.userId === userId) {
          return this.processingResponse(content.contentCode, raced.id);
        }
        return this.joinPublicJob(userId, raced, content, normalUnits);
      }

      try {
        if (tx.status === "RESERVED") await this.quotaStore.release(tx.id);
        await this.artifactStore.updateJobStatus(jobId, "failed", "queue_or_job_creation_failed");
      } catch {
        // Preserve the original failure; cleanup is best effort.
      }
      throw error;
    }
  }

  private async settleFollowers(jobId: string, artifact: TranscriptArtifactRecord): Promise<void> {
    const followers = await this.artifactStore.listJobFollowers(jobId);
    for (const follower of followers) {
      await this.settleFollower(follower, artifact);
    }
  }

  private async settleFollower(
    follower: JobFollowerRecord,
    artifact: TranscriptArtifactRecord
  ): Promise<void> {
    try {
      let tx = await this.quotaStore.findByReference(follower.userId, "transcript_shared", follower.id);
      if (!tx || tx.status !== "RESERVED") {
        tx = await this.quotaStore.reserve(
          follower.userId,
          "transcript_shared",
          follower.id,
          follower.normalUnits,
          follower.quotaMultiplier || this.sharedMultiplier
        );
      }

      let accessGranted = false;
      try {
        // Authorization and billing land together: the follower's grant is written first,
        // and the reservation is committed only when that grant exists.
        await this.artifactStore.grantUserAccess(
          follower.userId,
          artifact.id,
          artifact.contentId,
          "shared_cache",
          tx.units
        );
        accessGranted = true;
        await this.quotaStore.commit(tx.id);
      } catch (error) {
        try {
          if (accessGranted && this.artifactStore.revokeUserAccess) {
            await this.artifactStore.revokeUserAccess(follower.userId, artifact.id);
          }
          if (tx.status === "RESERVED") await this.quotaStore.release(tx.id);
        } catch {
          // Preserve the original failure; cleanup is best effort.
        }
        throw error;
      }

      await this.artifactStore.updateJobFollowerStatus(follower.id, "completed", null, artifact.id);
    } catch {
      // One follower's settlement failure must never invalidate another follower's, and
      // must never leave that follower billed for content they cannot read.
      try {
        const tx = await this.quotaStore.findByReference(follower.userId, "transcript_shared", follower.id);
        if (tx && tx.status === "RESERVED") await this.quotaStore.release(tx.id);
        await this.artifactStore.updateJobFollowerStatus(
          follower.id,
          "failed",
          "shared_cache_settlement_failed"
        );
      } catch {
        // Best effort; the follower keeps a pollable failed row whenever this succeeds.
      }
    }
  }

  private processingResponse(contentCode: string, jobId: string): TranscriptProcessingResponse {
    return { status: "processing", contentCode, jobId };
  }

  private async enqueueJob(jobId: string, contentId: string, language: string, audioUrl: string, durationMs?: number): Promise<void> {
    if (!this.queue) return;
    await this.queue.send({ jobId, contentId, language, audioUrl, durationMs, artifactType: "transcript" });
  }
}

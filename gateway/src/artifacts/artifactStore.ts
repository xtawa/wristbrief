export interface TranscriptArtifactRecord {
  id: string;
  contentId: string;
  language: string;
  artifactVersion: number;
  provider: string | null;
  model: string | null;
  status: "ready" | "processing" | "failed";
  objectKeyJson: string | null;
  objectKeyText: string | null;
  objectKeySegments: string | null;
  transcriptHash: string | null;
  wordCount: number | null;
  segmentCount: number | null;
  qualityScore: number | null;
  sharePolicy: "PUBLIC_REUSE" | "PRIVATE_ACCOUNT";
  createdByUserId: string | null;
  createdAt: number;
  updatedAt: number;
}

export interface UserArtifactAccessRecord {
  userId: string;
  artifactId: string;
  contentId: string;
  accessSource: "creator" | "shared_cache" | "admin";
  firstAccessedAt: number;
  lastAccessedAt: number;
  quotaUnitsCharged: number;
}

export interface ArtifactJobRecord {
  id: string;
  dedupeKey: string;
  userId: string;
  contentId: string;
  artifactType: string;
  language: string;
  requestedVersion: number;
  status: "queued" | "running" | "completed" | "failed";
  leaseOwner: string | null;
  leaseExpiresAt: number | null;
  attemptCount: number;
  errorCode: string | null;
  createdAt: number;
  updatedAt: number;
}

/**
 * A per-user request that joined an already in-flight artifact job.
 *
 * The underlying work item stays `artifact_jobs.id` (the leader); this row only carries
 * the joining user's own pollable id, status view, and shared-cache reservation so that
 * two users asking for the same content never share a job id or a billing row.
 */
export interface JobFollowerRecord {
  id: string;
  jobId: string;
  userId: string;
  contentId: string;
  language: string;
  status: "queued" | "running" | "completed" | "failed";
  attemptCount: number;
  errorCode: string | null;
  normalUnits: number;
  quotaMultiplier: number;
  quotaUnits: number;
  artifactId: string | null;
  createdAt: number;
  updatedAt: number;
}

export interface ArtifactStore {
  findArtifactById(artifactId: string): Promise<TranscriptArtifactRecord | null>;
  findPreferredArtifactForContent(contentId: string, language?: string): Promise<TranscriptArtifactRecord | null>;
  /**
   * The best ready artifact this specific user is allowed to read: their own artifact,
   * an artifact they hold an access grant for, or a PUBLIC_REUSE artifact. Never returns
   * another account's PRIVATE_ACCOUNT artifact, so callers can keep 404-on-unauthorized.
   */
  findReadableArtifactForContent(
    userId: string,
    contentId: string,
    language?: string
  ): Promise<TranscriptArtifactRecord | null>;
  createArtifact(record: Omit<TranscriptArtifactRecord, "createdAt" | "updatedAt">): Promise<TranscriptArtifactRecord>;
  hasUserAccess(userId: string, artifactId: string): Promise<boolean>;
  getUserAccessForContent(userId: string, contentId: string): Promise<UserArtifactAccessRecord | null>;
  grantUserAccess(
    userId: string,
    artifactId: string,
    contentId: string,
    accessSource: "creator" | "shared_cache" | "admin",
    quotaUnitsCharged: number
  ): Promise<void>;
  createJob(job: Omit<ArtifactJobRecord, "createdAt" | "updatedAt">): Promise<ArtifactJobRecord>;
  getJobById(jobId: string): Promise<ArtifactJobRecord | null>;
  getJobByDedupeKey(dedupeKey: string): Promise<ArtifactJobRecord | null>;
  /** Most recent job this user owns for one content+language (any status). */
  findJobForUserContent(
    userId: string,
    contentId: string,
    language: string
  ): Promise<ArtifactJobRecord | null>;
  /**
   * Next free artifact version for a content+language. Private content is generated
   * per user, and transcript_artifacts is UNIQUE(content_id, language,
   * artifact_version), so each private generation needs its own version slot.
   */
  allocateArtifactVersion(contentId: string, language: string): Promise<number>;
  updateJobStatus(jobId: string, status: "queued" | "running" | "completed" | "failed", errorCode?: string): Promise<void>;
  resetJobForRetry?(jobId: string): Promise<void>;
  createJobFollower(
    follower: Omit<JobFollowerRecord, "createdAt" | "updatedAt">
  ): Promise<JobFollowerRecord>;
  getJobFollowerById(followerId: string): Promise<JobFollowerRecord | null>;
  getJobFollowerForUser(jobId: string, userId: string): Promise<JobFollowerRecord | null>;
  listJobFollowers(jobId: string): Promise<JobFollowerRecord[]>;
  updateJobFollowerStatus(
    followerId: string,
    status: "queued" | "running" | "completed" | "failed",
    errorCode?: string | null,
    artifactId?: string | null
  ): Promise<void>;
  revokeUserAccess?(userId: string, artifactId: string): Promise<void>;
  deleteArtifact?(artifactId: string): Promise<void>;
}

export class D1ArtifactStore implements ArtifactStore {
  constructor(private readonly db: D1Database) {}

  async findArtifactById(artifactId: string): Promise<TranscriptArtifactRecord | null> {
    const row = await this.db
      .prepare("SELECT * FROM transcript_artifacts WHERE id = ?")
      .bind(artifactId)
      .first<Record<string, unknown>>();
    return row ? this.mapArtifact(row) : null;
  }

  async findPreferredArtifactForContent(contentId: string, language = "auto"): Promise<TranscriptArtifactRecord | null> {
    let query = "SELECT * FROM transcript_artifacts WHERE content_id = ? AND status = 'ready'";
    const bindings: unknown[] = [contentId];

    if (language !== "auto") {
      query += " AND language = ?";
      bindings.push(language);
    }
    query += " ORDER BY artifact_version DESC LIMIT 1";

    const stmt = this.db.prepare(query);
    const row = await stmt.bind(...bindings).first<Record<string, unknown>>();
    return row ? this.mapArtifact(row) : null;
  }

  async findReadableArtifactForContent(
    userId: string,
    contentId: string,
    language = "auto"
  ): Promise<TranscriptArtifactRecord | null> {
    let query = `
      SELECT * FROM transcript_artifacts a
      WHERE a.content_id = ? AND a.status = 'ready'
        AND (
          a.created_by_user_id = ?
          OR a.share_policy = 'PUBLIC_REUSE'
          OR EXISTS (
            SELECT 1 FROM user_artifact_access ua
            WHERE ua.user_id = ? AND ua.artifact_id = a.id
          )
        )`;
    const bindings: unknown[] = [contentId, userId, userId];

    if (language !== "auto") {
      query += " AND a.language = ?";
      bindings.push(language);
    }
    query += " ORDER BY a.artifact_version DESC LIMIT 1";

    const row = await this.db.prepare(query).bind(...bindings).first<Record<string, unknown>>();
    return row ? this.mapArtifact(row) : null;
  }

  async createArtifact(
    record: Omit<TranscriptArtifactRecord, "createdAt" | "updatedAt">
  ): Promise<TranscriptArtifactRecord> {
    const now = Date.now();
    await this.db
      .prepare(`
        INSERT INTO transcript_artifacts (
          id, content_id, language, artifact_version, provider, model, status,
          object_key_json, object_key_text, object_key_segments, transcript_hash,
          word_count, segment_count, quality_score, share_policy, created_by_user_id,
          created_at, updated_at
        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
      `)
      .bind(
        record.id,
        record.contentId,
        record.language,
        record.artifactVersion,
        record.provider,
        record.model,
        record.status,
        record.objectKeyJson,
        record.objectKeyText,
        record.objectKeySegments,
        record.transcriptHash,
        record.wordCount,
        record.segmentCount,
        record.qualityScore,
        record.sharePolicy,
        record.createdByUserId,
        now,
        now
      )
      .run();

    return {
      ...record,
      createdAt: now,
      updatedAt: now
    };
  }

  async hasUserAccess(userId: string, artifactId: string): Promise<boolean> {
    const row = await this.db
      .prepare("SELECT 1 FROM user_artifact_access WHERE user_id = ? AND artifact_id = ?")
      .bind(userId, artifactId)
      .first();
    return Boolean(row);
  }

  async getUserAccessForContent(userId: string, contentId: string): Promise<UserArtifactAccessRecord | null> {
    const row = await this.db
      .prepare("SELECT * FROM user_artifact_access WHERE user_id = ? AND content_id = ? ORDER BY last_accessed_at DESC LIMIT 1")
      .bind(userId, contentId)
      .first<Record<string, unknown>>();

    if (!row) return null;
    return {
      userId: String(row.user_id),
      artifactId: String(row.artifact_id),
      contentId: String(row.content_id),
      accessSource: row.access_source as "creator" | "shared_cache" | "admin",
      firstAccessedAt: Number(row.first_accessed_at),
      lastAccessedAt: Number(row.last_accessed_at),
      quotaUnitsCharged: Number(row.quota_units_charged)
    };
  }

  async grantUserAccess(
    userId: string,
    artifactId: string,
    contentId: string,
    accessSource: "creator" | "shared_cache" | "admin",
    quotaUnitsCharged: number
  ): Promise<void> {
    const now = Date.now();
    await this.db
      .prepare(`
        INSERT INTO user_artifact_access (
          user_id, artifact_id, content_id, access_source, first_accessed_at, last_accessed_at, quota_units_charged
        ) VALUES (?, ?, ?, ?, ?, ?, ?)
        ON CONFLICT(user_id, artifact_id) DO UPDATE SET
          last_accessed_at = excluded.last_accessed_at
      `)
      .bind(userId, artifactId, contentId, accessSource, now, now, quotaUnitsCharged)
      .run();
  }

  async revokeUserAccess(userId: string, artifactId: string): Promise<void> {
    await this.db
      .prepare("DELETE FROM user_artifact_access WHERE user_id = ? AND artifact_id = ?")
      .bind(userId, artifactId)
      .run();
  }

  async deleteArtifact(artifactId: string): Promise<void> {
    await this.db.batch([
      this.db.prepare("DELETE FROM user_artifact_access WHERE artifact_id = ?").bind(artifactId),
      this.db.prepare("DELETE FROM transcript_artifacts WHERE id = ?").bind(artifactId)
    ]);
  }

  async createJob(job: Omit<ArtifactJobRecord, "createdAt" | "updatedAt">): Promise<ArtifactJobRecord> {
    const now = Date.now();
    await this.db
      .prepare(`
        INSERT INTO artifact_jobs (
          id, dedupe_key, user_id, content_id, artifact_type, language, requested_version,
          status, lease_owner, lease_expires_at, attempt_count, error_code, created_at, updated_at
        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
      `)
      .bind(
        job.id,
        job.dedupeKey,
        job.userId,
        job.contentId,
        job.artifactType,
        job.language,
        job.requestedVersion,
        job.status,
        job.leaseOwner,
        job.leaseExpiresAt,
        job.attemptCount,
        job.errorCode,
        now,
        now
      )
      .run();

    return {
      ...job,
      createdAt: now,
      updatedAt: now
    };
  }

  async getJobById(jobId: string): Promise<ArtifactJobRecord | null> {
    const row = await this.db
      .prepare("SELECT * FROM artifact_jobs WHERE id = ?")
      .bind(jobId)
      .first<Record<string, unknown>>();
    return row ? this.mapJob(row) : null;
  }

  async getJobByDedupeKey(dedupeKey: string): Promise<ArtifactJobRecord | null> {
    const row = await this.db
      .prepare("SELECT * FROM artifact_jobs WHERE dedupe_key = ?")
      .bind(dedupeKey)
      .first<Record<string, unknown>>();
    return row ? this.mapJob(row) : null;
  }

  async findJobForUserContent(
    userId: string,
    contentId: string,
    language: string
  ): Promise<ArtifactJobRecord | null> {
    const row = await this.db
      .prepare(`
        SELECT * FROM artifact_jobs
        WHERE user_id = ? AND content_id = ? AND language = ? AND artifact_type = 'transcript'
        ORDER BY created_at DESC, id DESC
        LIMIT 1
      `)
      .bind(userId, contentId, language)
      .first<Record<string, unknown>>();
    return row ? this.mapJob(row) : null;
  }

  async allocateArtifactVersion(contentId: string, language: string): Promise<number> {
    const row = await this.db
      .prepare(`
        SELECT
          COALESCE((SELECT MAX(artifact_version) FROM transcript_artifacts WHERE content_id = ? AND language = ?), 0) AS max_artifact,
          COALESCE((SELECT MAX(requested_version) FROM artifact_jobs WHERE content_id = ? AND language = ?), 0) AS max_job
      `)
      .bind(contentId, language, contentId, language)
      .first<Record<string, unknown>>();

    const maxArtifact = Number(row?.max_artifact ?? 0);
    const maxJob = Number(row?.max_job ?? 0);
    return Math.max(maxArtifact, maxJob) + 1;
  }

  async updateJobStatus(
    jobId: string,
    status: "queued" | "running" | "completed" | "failed",
    errorCode?: string
  ): Promise<void> {
    const now = Date.now();
    await this.db
      .prepare("UPDATE artifact_jobs SET status = ?, error_code = coalesce(?, error_code), updated_at = ? WHERE id = ?")
      .bind(status, errorCode ?? null, now, jobId)
      .run();
  }

  async resetJobForRetry(jobId: string): Promise<void> {
    await this.db
      .prepare("UPDATE artifact_jobs SET status = 'queued', error_code = NULL, attempt_count = attempt_count + 1, updated_at = ? WHERE id = ? AND status = 'failed'")
      .bind(Date.now(), jobId)
      .run();
  }

  async createJobFollower(
    follower: Omit<JobFollowerRecord, "createdAt" | "updatedAt">
  ): Promise<JobFollowerRecord> {
    const now = Date.now();
    await this.db
      .prepare(`
        INSERT INTO transcript_job_followers (
          id, job_id, user_id, content_id, language, status, attempt_count, error_code,
          normal_units, quota_multiplier, quota_units, artifact_id, created_at, updated_at
        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
      `)
      .bind(
        follower.id,
        follower.jobId,
        follower.userId,
        follower.contentId,
        follower.language,
        follower.status,
        follower.attemptCount,
        follower.errorCode,
        follower.normalUnits,
        follower.quotaMultiplier,
        follower.quotaUnits,
        follower.artifactId,
        now,
        now
      )
      .run();

    return {
      ...follower,
      createdAt: now,
      updatedAt: now
    };
  }

  async getJobFollowerById(followerId: string): Promise<JobFollowerRecord | null> {
    const row = await this.db
      .prepare("SELECT * FROM transcript_job_followers WHERE id = ?")
      .bind(followerId)
      .first<Record<string, unknown>>();
    return row ? this.mapJobFollower(row) : null;
  }

  async getJobFollowerForUser(jobId: string, userId: string): Promise<JobFollowerRecord | null> {
    const row = await this.db
      .prepare("SELECT * FROM transcript_job_followers WHERE job_id = ? AND user_id = ?")
      .bind(jobId, userId)
      .first<Record<string, unknown>>();
    return row ? this.mapJobFollower(row) : null;
  }

  async listJobFollowers(jobId: string): Promise<JobFollowerRecord[]> {
    const result = await this.db
      .prepare("SELECT * FROM transcript_job_followers WHERE job_id = ? ORDER BY created_at ASC, id ASC")
      .bind(jobId)
      .all<Record<string, unknown>>();
    return (result.results ?? []).map((row) => this.mapJobFollower(row));
  }

  async updateJobFollowerStatus(
    followerId: string,
    status: "queued" | "running" | "completed" | "failed",
    errorCode: string | null = null,
    artifactId: string | null = null
  ): Promise<void> {
    await this.db
      .prepare(`
        UPDATE transcript_job_followers
        SET status = ?, error_code = ?, artifact_id = coalesce(?, artifact_id), updated_at = ?
        WHERE id = ?
      `)
      .bind(status, errorCode, artifactId, Date.now(), followerId)
      .run();
  }

  private mapArtifact(row: Record<string, unknown>): TranscriptArtifactRecord {
    return {
      id: String(row.id),
      contentId: String(row.content_id),
      language: String(row.language),
      artifactVersion: Number(row.artifact_version),
      provider: row.provider ? String(row.provider) : null,
      model: row.model ? String(row.model) : null,
      status: (row.status as "ready" | "processing" | "failed") || "ready",
      objectKeyJson: row.object_key_json ? String(row.object_key_json) : null,
      objectKeyText: row.object_key_text ? String(row.object_key_text) : null,
      objectKeySegments: row.object_key_segments ? String(row.object_key_segments) : null,
      transcriptHash: row.transcript_hash ? String(row.transcript_hash) : null,
      wordCount: typeof row.word_count === "number" ? row.word_count : null,
      segmentCount: typeof row.segment_count === "number" ? row.segment_count : null,
      qualityScore: typeof row.quality_score === "number" ? row.quality_score : null,
      sharePolicy: (row.share_policy as "PUBLIC_REUSE" | "PRIVATE_ACCOUNT") || "PRIVATE_ACCOUNT",
      createdByUserId: row.created_by_user_id ? String(row.created_by_user_id) : null,
      createdAt: Number(row.created_at),
      updatedAt: Number(row.updated_at)
    };
  }

  private mapJob(row: Record<string, unknown>): ArtifactJobRecord {
    return {
      id: String(row.id),
      dedupeKey: String(row.dedupe_key),
      userId: String(row.user_id),
      contentId: String(row.content_id),
      artifactType: String(row.artifact_type),
      language: String(row.language),
      requestedVersion: Number(row.requested_version || 1),
      status: (row.status as "queued" | "running" | "completed" | "failed") || "queued",
      leaseOwner: row.lease_owner ? String(row.lease_owner) : null,
      leaseExpiresAt: typeof row.lease_expires_at === "number" ? row.lease_expires_at : null,
      attemptCount: Number(row.attempt_count || 1),
      errorCode: row.error_code ? String(row.error_code) : null,
      createdAt: Number(row.created_at),
      updatedAt: Number(row.updated_at)
    };
  }

  private mapJobFollower(row: Record<string, unknown>): JobFollowerRecord {
    return {
      id: String(row.id),
      jobId: String(row.job_id),
      userId: String(row.user_id),
      contentId: String(row.content_id),
      language: String(row.language),
      status: (row.status as JobFollowerRecord["status"]) || "queued",
      attemptCount: Number(row.attempt_count || 1),
      errorCode: row.error_code ? String(row.error_code) : null,
      normalUnits: Number(row.normal_units || 0),
      quotaMultiplier: Number(row.quota_multiplier || 0),
      quotaUnits: Number(row.quota_units || 0),
      artifactId: row.artifact_id ? String(row.artifact_id) : null,
      createdAt: Number(row.created_at),
      updatedAt: Number(row.updated_at)
    };
  }
}

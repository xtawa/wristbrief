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

export interface ArtifactStore {
  findArtifactById(artifactId: string): Promise<TranscriptArtifactRecord | null>;
  findPreferredArtifactForContent(contentId: string, language?: string): Promise<TranscriptArtifactRecord | null>;
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
  updateJobStatus(jobId: string, status: "queued" | "running" | "completed" | "failed", errorCode?: string): Promise<void>;
  resetJobForRetry?(jobId: string): Promise<void>;
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
}

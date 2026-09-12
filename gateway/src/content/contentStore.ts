export interface PodcastContent {
  id: string;
  contentCode: string;
  mediaType: string;
  canonicalTitle: string | null;
  canonicalPublishedAt: number | null;
  canonicalDurationMs: number | null;
  sharePolicy: "PUBLIC_REUSE" | "PRIVATE_ACCOUNT";
  status: "active" | "merged" | "tombstone";
  preferredTranscriptArtifactId: string | null;
  createdAt: number;
  updatedAt: number;
}

export interface ContentAlias {
  aliasType: string;
  aliasValueHash: string;
  contentId: string;
  confidence: "EXACT" | "HIGH" | "LOW";
  verified: boolean;
  createdAt: number;
}

export interface ContentStore {
  findById(contentId: string): Promise<PodcastContent | null>;
  findByContentCode(contentCode: string): Promise<PodcastContent | null>;
  findByAlias(aliasType: string, aliasValueHash: string): Promise<PodcastContent | null>;
  createContent(
    content: Omit<PodcastContent, "createdAt" | "updatedAt">,
    aliases?: Array<Omit<ContentAlias, "createdAt">>
  ): Promise<PodcastContent>;
  addAlias(alias: Omit<ContentAlias, "createdAt">): Promise<void>;
  updatePreferredArtifact(contentId: string, artifactId: string): Promise<void>;
}

export async function computeSha256Hex(input: string): Promise<string> {
  const encoder = new TextEncoder();
  const data = encoder.encode(input);
  const hashBuffer = await crypto.subtle.digest("SHA-256", data);
  const hashArray = Array.from(new Uint8Array(hashBuffer));
  return hashArray.map((b) => b.toString(16).padStart(2, "0")).join("");
}

export class D1ContentStore implements ContentStore {
  constructor(private readonly db: D1Database) {}

  async findById(contentId: string): Promise<PodcastContent | null> {
    const row = await this.db
      .prepare("SELECT * FROM podcast_contents WHERE id = ?")
      .bind(contentId)
      .first<Record<string, unknown>>();
    return row ? this.mapRow(row) : null;
  }

  async findByContentCode(contentCode: string): Promise<PodcastContent | null> {
    const row = await this.db
      .prepare("SELECT * FROM podcast_contents WHERE content_code = ?")
      .bind(contentCode)
      .first<Record<string, unknown>>();
    return row ? this.mapRow(row) : null;
  }

  async findByAlias(aliasType: string, aliasValueHash: string): Promise<PodcastContent | null> {
    const aliasRow = await this.db
      .prepare("SELECT content_id FROM content_aliases WHERE alias_type = ? AND alias_value_hash = ?")
      .bind(aliasType, aliasValueHash)
      .first<{ content_id: string }>();

    if (!aliasRow?.content_id) return null;
    return this.findById(aliasRow.content_id);
  }

  async createContent(
    content: Omit<PodcastContent, "createdAt" | "updatedAt">,
    aliases: Array<Omit<ContentAlias, "createdAt">> = []
  ): Promise<PodcastContent> {
    const now = Date.now();
    const statements: D1PreparedStatement[] = [
      this.db
        .prepare(`
          INSERT INTO podcast_contents (
            id, content_code, media_type, canonical_title, canonical_published_at,
            canonical_duration_ms, share_policy, status, preferred_transcript_artifact_id,
            created_at, updated_at
          ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        `)
        .bind(
          content.id,
          content.contentCode,
          content.mediaType,
          content.canonicalTitle,
          content.canonicalPublishedAt,
          content.canonicalDurationMs,
          content.sharePolicy,
          content.status,
          content.preferredTranscriptArtifactId,
          now,
          now
        )
    ];

    for (const alias of aliases) {
      statements.push(
        this.db
          .prepare(`
            INSERT OR IGNORE INTO content_aliases (
              alias_type, alias_value_hash, content_id, confidence, verified, created_at
            ) VALUES (?, ?, ?, ?, ?, ?)
          `)
          .bind(
            alias.aliasType,
            alias.aliasValueHash,
            alias.contentId,
            alias.confidence,
            alias.verified ? 1 : 0,
            now
          )
      );
    }

    await this.db.batch(statements);

    return {
      ...content,
      createdAt: now,
      updatedAt: now
    };
  }

  async addAlias(alias: Omit<ContentAlias, "createdAt">): Promise<void> {
    const now = Date.now();
    await this.db
      .prepare(`
        INSERT OR IGNORE INTO content_aliases (
          alias_type, alias_value_hash, content_id, confidence, verified, created_at
        ) VALUES (?, ?, ?, ?, ?, ?)
      `)
      .bind(
        alias.aliasType,
        alias.aliasValueHash,
        alias.contentId,
        alias.confidence,
        alias.verified ? 1 : 0,
        now
      )
      .run();
  }

  async updatePreferredArtifact(contentId: string, artifactId: string): Promise<void> {
    const now = Date.now();
    await this.db
      .prepare("UPDATE podcast_contents SET preferred_transcript_artifact_id = ?, updated_at = ? WHERE id = ?")
      .bind(artifactId, now, contentId)
      .run();
  }

  private mapRow(row: Record<string, unknown>): PodcastContent {
    return {
      id: String(row.id),
      contentCode: String(row.content_code),
      mediaType: String(row.media_type),
      canonicalTitle: row.canonical_title ? String(row.canonical_title) : null,
      canonicalPublishedAt: typeof row.canonical_published_at === "number" ? row.canonical_published_at : null,
      canonicalDurationMs: typeof row.canonical_duration_ms === "number" ? row.canonical_duration_ms : null,
      sharePolicy: (row.share_policy as "PUBLIC_REUSE" | "PRIVATE_ACCOUNT") || "PRIVATE_ACCOUNT",
      status: (row.status as "active" | "merged" | "tombstone") || "active",
      preferredTranscriptArtifactId: row.preferred_transcript_artifact_id ? String(row.preferred_transcript_artifact_id) : null,
      createdAt: Number(row.created_at),
      updatedAt: Number(row.updated_at)
    };
  }
}

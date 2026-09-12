import { generateContentCode } from "./contentCode";
import {
  computeSha256Hex,
  type ContentAlias,
  type ContentStore,
  type PodcastContent
} from "./contentStore";

export interface EpisodeMetadataInput {
  feedId?: string;
  feedUrl?: string;
  guid?: string;
  audioUrl: string;
  title?: string;
  publishedAt?: string | number;
  durationMs?: number;
  sharePolicy?: "PUBLIC_REUSE" | "PRIVATE_ACCOUNT";
  audioSha256?: string;
}

export interface ResolveResult {
  contentId: string;
  contentCode: string;
  isNew: boolean;
  content: PodcastContent;
}

/**
 * Normalizes an audio URL by stripping tracking parameters (e.g., utm_*, etc.)
 */
export function normalizeAudioUrl(rawUrl: string): string {
  try {
    const parsed = new URL(rawUrl);
    const paramsToKeep = new URLSearchParams();
    for (const [key, value] of parsed.searchParams.entries()) {
      if (!key.toLowerCase().startsWith("utm_") && key.toLowerCase() !== "ref") {
        paramsToKeep.append(key, value);
      }
    }
    parsed.search = paramsToKeep.toString();
    parsed.hash = "";
    return parsed.toString();
  } catch {
    return rawUrl.trim();
  }
}

export class ContentResolver {
  constructor(private readonly store: ContentStore) {}

  /**
   * Stage A: Candidate resolution using cheap aliases (audio SHA, enclosure URL, feed+guid)
   */
  async resolve(input: EpisodeMetadataInput): Promise<ResolveResult> {
    const aliasesToCheck: Array<{ type: string; value: string; confidence: "EXACT" | "HIGH" }> = [];

    // 1. Audio SHA-256 (Highest confidence)
    if (input.audioSha256 && input.audioSha256.length === 64) {
      aliasesToCheck.push({
        type: "audio_sha256",
        value: input.audioSha256.toLowerCase(),
        confidence: "EXACT"
      });
    }

    // 2. Enclosure URL
    const normalizedUrl = normalizeAudioUrl(input.audioUrl);
    if (normalizedUrl) {
      const urlHash = await computeSha256Hex(normalizedUrl);
      aliasesToCheck.push({
        type: "enclosure_url",
        value: urlHash,
        confidence: "HIGH"
      });
    }

    // 3. Feed URL + GUID
    if (input.feedUrl && input.guid) {
      const feedGuidKey = `${input.feedUrl.trim().toLowerCase()}#${input.guid.trim()}`;
      const feedGuidHash = await computeSha256Hex(feedGuidKey);
      aliasesToCheck.push({
        type: "feed_guid",
        value: feedGuidHash,
        confidence: "HIGH"
      });
    }

    // Check existing aliases sequentially
    for (const alias of aliasesToCheck) {
      const existing = await this.store.findByAlias(alias.type, alias.value);
      if (existing && existing.status === "active") {
        return {
          contentId: existing.id,
          contentCode: existing.contentCode,
          isNew: false,
          content: existing
        };
      }
    }

    // No existing content matched -> Create canonical row
    const contentId = `cnt_${crypto.randomUUID()}`;
    const contentCode = generateContentCode();
    const publishedAt =
      typeof input.publishedAt === "number"
        ? input.publishedAt
        : input.publishedAt
        ? new Date(input.publishedAt).getTime()
        : null;

    const newContent: Omit<PodcastContent, "createdAt" | "updatedAt"> = {
      id: contentId,
      contentCode,
      mediaType: "podcast",
      canonicalTitle: input.title?.trim() || null,
      canonicalPublishedAt: publishedAt && !isNaN(publishedAt) ? publishedAt : null,
      canonicalDurationMs: input.durationMs && input.durationMs > 0 ? input.durationMs : null,
      sharePolicy: input.sharePolicy || "PUBLIC_REUSE",
      status: "active",
      preferredTranscriptArtifactId: null
    };

    const aliasesToCreate: Array<Omit<ContentAlias, "createdAt">> = aliasesToCheck.map((a) => ({
      aliasType: a.type,
      aliasValueHash: a.value,
      contentId,
      confidence: a.confidence,
      verified: a.confidence === "EXACT"
    }));

    const created = await this.store.createContent(newContent, aliasesToCreate);

    return {
      contentId: created.id,
      contentCode: created.contentCode,
      isNew: true,
      content: created
    };
  }

  async getByContentCode(code: string): Promise<PodcastContent | null> {
    return this.store.findByContentCode(code);
  }
}

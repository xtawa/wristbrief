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

export interface AudioUrlValidationResult {
  ok: boolean;
  error?: string;
  normalizedUrl?: string;
}

export function validateAudioUrl(rawUrl: string): AudioUrlValidationResult {
  if (typeof rawUrl !== "string" || !rawUrl.trim()) {
    return { ok: false, error: "audio_url_required" };
  }
  const trimmed = rawUrl.trim();
  if (trimmed.length > 2048) {
    return { ok: false, error: "audio_url_too_long" };
  }

  let parsed: URL;
  try {
    parsed = new URL(trimmed);
  } catch {
    return { ok: false, error: "invalid_audio_url_format" };
  }

  if (parsed.protocol !== "https:") {
    return { ok: false, error: "https_required" };
  }

  const hostname = parsed.hostname.toLowerCase().replace(/\.$/, "");
  if (!hostname) {
    return { ok: false, error: "invalid_hostname" };
  }

  // Check localhost and loopback
  if (
    hostname === "localhost" ||
    hostname === "127.0.0.1" ||
    hostname === "::1" ||
    hostname === "[::1]" ||
    hostname === "0.0.0.0"
  ) {
    return { ok: false, error: "loopback_not_allowed" };
  }

  // Check internal TLDs
  if (
    hostname.endsWith(".local") ||
    hostname.endsWith(".internal") ||
    hostname.endsWith(".localhost") ||
    hostname.endsWith(".lan") ||
    hostname.endsWith(".home.arpa")
  ) {
    return { ok: false, error: "internal_domain_not_allowed" };
  }

  // Check IPv4 private and link-local ranges
  const ipv4Match = /^(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})$/.exec(hostname);
  if (ipv4Match) {
    const [, p1, p2, p3, p4] = ipv4Match.map(Number);
    if (p1 > 255 || p2 > 255 || p3 > 255 || p4 > 255) {
      return { ok: false, error: "invalid_ip_address" };
    }
    // 127.0.0.0/8 (Loopback)
    if (p1 === 127) return { ok: false, error: "loopback_not_allowed" };
    // 0.0.0.0/8 (Current network)
    if (p1 === 0) return { ok: false, error: "private_ip_not_allowed" };
    // 10.0.0.0/8 (RFC 1918)
    if (p1 === 10) return { ok: false, error: "private_ip_not_allowed" };
    // 172.16.0.0/12 (RFC 1918)
    if (p1 === 172 && p2 >= 16 && p2 <= 31) return { ok: false, error: "private_ip_not_allowed" };
    // 192.168.0.0/16 (RFC 1918)
    if (p1 === 192 && p2 === 168) return { ok: false, error: "private_ip_not_allowed" };
    // 169.254.0.0/16 (Link-local)
    if (p1 === 169 && p2 === 254) return { ok: false, error: "private_ip_not_allowed" };
  }

  // IPv6 check for private / unique local addresses (fc00::/7, fe80::/10)
  const ipv6Host = hostname.replace(/^\[|\]$/g, "");
  const mappedIpv4 = /^::ffff:(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})$/.exec(ipv6Host);
  if (mappedIpv4) {
    const [, p1, p2, p3, p4] = mappedIpv4.map(Number);
    if (p1 > 255 || p2 > 255 || p3 > 255 || p4 > 255) {
      return { ok: false, error: "invalid_ip_address" };
    }
    if (p1 === 127) return { ok: false, error: "loopback_not_allowed" };
    if (
      p1 === 0 ||
      p1 === 10 ||
      (p1 === 172 && p2 >= 16 && p2 <= 31) ||
      (p1 === 192 && p2 === 168) ||
      (p1 === 169 && p2 === 254)
    ) {
      return { ok: false, error: "private_ip_not_allowed" };
    }
  }
  const firstIpv6Hextet = /^([0-9a-f]{1,4}):/.exec(ipv6Host)?.[1];
  const firstIpv6Value = firstIpv6Hextet ? Number.parseInt(firstIpv6Hextet, 16) : -1;
  if (
    ipv6Host === "::" ||
    ipv6Host === "::1" ||
    ipv6Host.startsWith("fc") ||
    ipv6Host.startsWith("fd") ||
    (firstIpv6Value >= 0xfe80 && firstIpv6Value <= 0xfebf)
  ) {
    return { ok: false, error: "private_ip_not_allowed" };
  }

  return { ok: true, normalizedUrl: normalizeAudioUrl(trimmed) };
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
    const urlValidation = validateAudioUrl(input.audioUrl);
    const normalizedUrl = urlValidation.ok && urlValidation.normalizedUrl
      ? urlValidation.normalizedUrl
      : normalizeAudioUrl(input.audioUrl);
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

    // Server-enforced share policy:
    // Sharing is opt-in. A feed URL alone must never make account content public.
    const sharePolicy: "PUBLIC_REUSE" | "PRIVATE_ACCOUNT" =
      input.sharePolicy === "PUBLIC_REUSE" ? "PUBLIC_REUSE" : "PRIVATE_ACCOUNT";

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
      sharePolicy,
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

  async getById(id: string): Promise<PodcastContent | null> {
    return this.store.findById(id);
  }
}

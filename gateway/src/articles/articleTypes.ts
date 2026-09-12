/**
 * Structured article document shared by the gateway extractor/sanitizer and the
 * native Compose renderer. No arbitrary HTML/CSS ever crosses this boundary.
 */
export const ARTICLE_EXTRACTION_VERSION = 1;

export type InlineSpan =
  | { type: "text"; text: string }
  | { type: "bold"; text: string }
  | { type: "italic"; text: string }
  | { type: "code"; text: string }
  | { type: "link"; text: string; url: string };

export type ArticleBlock =
  | { type: "paragraph"; spans: InlineSpan[] }
  | { type: "heading"; level: 1 | 2 | 3 | 4 | 5 | 6; spans: InlineSpan[] }
  | { type: "unordered_list"; items: InlineSpan[][] }
  | { type: "ordered_list"; items: InlineSpan[][] }
  | { type: "quote"; spans: InlineSpan[] }
  | { type: "code_block"; text: string }
  | { type: "image"; mediaId: string; alt: string }
  | { type: "divider" };

export type ArticleDocument = {
  title: string;
  author: string | null;
  publishedAt: string | null;
  canonicalUrl: string;
  sourceName: string | null;
  blocks: ArticleBlock[];
  extractionVersion: number;
};

export type ArticleSourceMeta = {
  title: string;
  author?: string | null;
  publishedAt?: string | null;
  sourceName?: string | null;
};

export function countTextLength(blocks: ArticleBlock[]): number {
  let total = 0;
  for (const block of blocks) {
    switch (block.type) {
      case "paragraph":
      case "heading":
      case "quote":
        total += spansLength(block.spans);
        break;
      case "unordered_list":
      case "ordered_list":
        for (const item of block.items) total += spansLength(item);
        break;
      case "code_block":
        total += block.text.length;
        break;
    }
  }
  return total;
}

function spansLength(spans: InlineSpan[]): number {
  return spans.reduce((sum, span) => sum + span.text.length, 0);
}

export async function articleKeyFor(url: string): Promise<string> {
  const digest = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(url));
  return Array.from(new Uint8Array(digest), (byte) => byte.toString(16).padStart(2, "0")).join("");
}

/** Opaque-but-decodable media id: the base64url of the (validated) image URL. */
export function mediaIdForUrl(url: string): string {
  let binary = "";
  for (const byte of new TextEncoder().encode(url)) binary += String.fromCharCode(byte);
  return btoa(binary).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/g, "");
}

export function urlForMediaId(mediaId: string): string | null {
  if (!/^[A-Za-z0-9_-]+$/.test(mediaId) || mediaId.length > 4096) return null;
  try {
    const normalized = mediaId.replace(/-/g, "+").replace(/_/g, "/");
    const binary = atob(normalized);
    const bytes = new Uint8Array(binary.length);
    for (let i = 0; i < binary.length; i++) bytes[i] = binary.charCodeAt(i);
    return new TextDecoder().decode(bytes);
  } catch {
    return null;
  }
}

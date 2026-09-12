import { decodeUtf8, safeFetch } from "../net/safeRemoteFetcher";
import { getCachedArticle, putCachedArticle, recordArticleFailure, type ArticleCacheEnv } from "./articleCache";
import { htmlToArticleDocument } from "./articleExtractor";
import { articleKeyFor, countTextLength, type ArticleDocument, type ArticleSourceMeta } from "./articleTypes";

export type ArticleRouteEnv = ArticleCacheEnv;

export type ArticleRouteResult = {
  status: number;
  body: Record<string, unknown>;
  document?: ArticleDocument;
};

const MAX_HTML_BYTES = 4 * 1024 * 1024;
const FETCH_TIMEOUT_MS = 15_000;
const FETCH_ERROR_CODES = new Set(["blocked_scheme", "blocked_host", "credentials_in_url"]);
const MIN_RSS_CONTENT_CHARS = 500; // below this, prefer server-side extraction

/**
 * POST /v1/articles/resolve — build (or reuse) a sanitized ArticleDocument for
 * an article. Content priority: RSS-provided full content (already fetched by
 * the client's feed parser, passed through the same sanitizer) > server-side
 * page fetch + extraction. Both paths produce the same structured document; the
 * client never renders raw HTML.
 */
export async function handleArticleResolve(
  env: ArticleRouteEnv,
  body: unknown,
  now = Date.now()
): Promise<ArticleRouteResult> {
  const request = (body ?? {}) as { url?: unknown; content?: unknown; title?: unknown; author?: unknown; publishedAt?: unknown; sourceName?: unknown };
  if (typeof request.url !== "string" || request.url.length === 0 || request.url.length > 2048) {
    return { status: 400, body: { error: "invalid_url" } };
  }
  let canonicalUrl: URL;
  try {
    canonicalUrl = new URL(request.url);
  } catch {
    return { status: 400, body: { error: "invalid_url" } };
  }
  if (canonicalUrl.protocol !== "http:" && canonicalUrl.protocol !== "https:") {
    return { status: 400, body: { error: "invalid_url" } };
  }

  const articleKey = await articleKeyFor(canonicalUrl.toString());
  const cached = await getCachedArticle(env, articleKey, now);
  if (cached) {
    return { status: 200, body: { articleKey, cached: true, document: cached }, document: cached };
  }

  const meta: ArticleSourceMeta = {
    title: typeof request.title === "string" ? request.title : canonicalUrl.hostname,
    author: typeof request.author === "string" ? request.author : null,
    publishedAt: typeof request.publishedAt === "string" ? request.publishedAt : null,
    sourceName: typeof request.sourceName === "string" ? request.sourceName : null
  };

  // Level 1/2: RSS content (content:encoded or description) provided by the client.
  if (typeof request.content === "string" && request.content.trim().length > 0 && request.content.length <= MAX_HTML_BYTES) {
    const document = htmlToArticleDocument(request.content, meta, canonicalUrl.toString());
    if (countTextLength(document.blocks) >= MIN_RSS_CONTENT_CHARS) {
      await putCachedArticle(env, canonicalUrl.toString(), document, now);
      return { status: 200, body: { articleKey, source: "rss", document }, document };
    }
    // Short RSS content falls through to extraction; remember it as a fallback.
    const extracted = await extractFromPage(env, canonicalUrl.toString(), meta, now);
    if (extracted) return extracted;
    if (document.blocks.length > 0) {
      await putCachedArticle(env, canonicalUrl.toString(), document, now);
      return { status: 200, body: { articleKey, source: "rss", document }, document };
    }
    return { status: 422, body: { error: "content_unavailable" } };
  }

  // Level 3: server-side full-text extraction from the canonical page.
  const extracted = await extractFromPage(env, canonicalUrl.toString(), meta, now);
  if (extracted) return extracted;
  return { status: 422, body: { error: "content_unavailable" } };
}

/** GET /v1/articles/:articleKey — cached document or 404. */
export async function handleArticleGet(env: ArticleRouteEnv, articleKey: string, now = Date.now()): Promise<ArticleRouteResult> {
  if (!/^[a-f0-9]{64}$/.test(articleKey)) return { status: 400, body: { error: "invalid_article_key" } };
  const cached = await getCachedArticle(env, articleKey, now);
  if (!cached) return { status: 404, body: { error: "not_found" } };
  return { status: 200, body: { articleKey, document: cached }, document: cached };
}

async function extractFromPage(
  env: ArticleRouteEnv,
  url: string,
  meta: ArticleSourceMeta,
  now: number
): Promise<ArticleRouteResult | null> {
  const fetched = await safeFetch(url, {
    maxBytes: MAX_HTML_BYTES,
    timeoutMs: FETCH_TIMEOUT_MS,
    allowedContentTypes: [/^text\/html/i, /^application\/xhtml\+xml/i]
  });
  if (!fetched.ok) {
    const code = FETCH_ERROR_CODES.has(fetched.error) ? "fetch_blocked" : fetched.error === "too_large" ? "content_too_large" : "fetch_failed";
    await recordArticleFailure(env, url, code, now);
    return null;
  }
  const document = htmlToArticleDocument(decodeUtf8(fetched.bytes), meta, url);
  if (document.blocks.length === 0) {
    await recordArticleFailure(env, url, "extraction_empty", now);
    return null;
  }
  await putCachedArticle(env, url, document, now);
  return { status: 200, body: { articleKey: await articleKeyFor(url), source: "extraction", document }, document };
}

import { articleKeyFor, type ArticleDocument } from "./articleTypes";

export type ArticleCacheEnv = {
  ACCOUNT_DB?: D1Database;
  TRANSCRIPTS_BUCKET?: R2Bucket; // shared R2 bucket; articles live under articles/
};

const CONTENT_TTL_MS = 24 * 60 * 60 * 1000;

function r2KeyFor(articleKey: string): string {
  return `articles/${articleKey}/v1.json`;
}

export async function getCachedArticle(
  env: ArticleCacheEnv,
  articleKey: string,
  now = Date.now()
): Promise<ArticleDocument | null> {
  if (!env.ACCOUNT_DB) return null;
  const row = await env.ACCOUNT_DB
    .prepare("SELECT status, r2_object_key, expires_at FROM article_content_cache WHERE article_key = ? LIMIT 1")
    .bind(articleKey)
    .first<{ status: string; r2_object_key: string | null; expires_at: number }>();
  if (!row || row.status !== "ready" || !row.r2_object_key) return null;
  if (row.expires_at <= now) return null;
  if (!env.TRANSCRIPTS_BUCKET) return null;
  const object = await env.TRANSCRIPTS_BUCKET.get(row.r2_object_key);
  if (!object) return null;
  try {
    return JSON.parse(await object.text()) as ArticleDocument;
  } catch {
    return null;
  }
}

export async function putCachedArticle(
  env: ArticleCacheEnv,
  canonicalUrl: string,
  document: ArticleDocument,
  now = Date.now()
): Promise<void> {
  if (!env.ACCOUNT_DB || !env.TRANSCRIPTS_BUCKET) return;
  const articleKey = await articleKeyFor(canonicalUrl);
  const objectKey = r2KeyFor(articleKey);
  const body = JSON.stringify(document);
  await env.TRANSCRIPTS_BUCKET.put(objectKey, body, {
    httpMetadata: { contentType: "application/json" }
  });
  const contentHash = await sha256Hex(body);
  await env.ACCOUNT_DB
    .prepare(
      `INSERT INTO article_content_cache (
         article_key, canonical_url_hash, source_url_hash, extraction_version, status,
         r2_object_key, etag, fetched_at, expires_at, content_hash, error_code
       ) VALUES (?, ?, ?, ?, 'ready', ?, ?, ?, ?, ?, NULL)
       ON CONFLICT(article_key) DO UPDATE SET
         status = 'ready', r2_object_key = excluded.r2_object_key, etag = excluded.etag,
         fetched_at = excluded.fetched_at, expires_at = excluded.expires_at, content_hash = excluded.content_hash`
    )
    .bind(articleKey, await sha256Hex(canonicalUrl), await sha256Hex(canonicalUrl), document.extractionVersion, objectKey, contentHash, now, now + CONTENT_TTL_MS, contentHash)
    .run();
}

export async function recordArticleFailure(env: ArticleCacheEnv, canonicalUrl: string, errorCode: string, now = Date.now()): Promise<void> {
  if (!env.ACCOUNT_DB) return;
  const articleKey = await articleKeyFor(canonicalUrl);
  await env.ACCOUNT_DB
    .prepare(
      `INSERT INTO article_content_cache (
         article_key, canonical_url_hash, source_url_hash, extraction_version, status,
         fetched_at, expires_at, error_code
       ) VALUES (?, ?, ?, 0, 'failed', ?, ?, ?)
       ON CONFLICT(article_key) DO UPDATE SET status = 'failed', fetched_at = excluded.fetched_at,
         expires_at = excluded.expires_at, error_code = excluded.error_code`
    )
    .bind(articleKey, await sha256Hex(canonicalUrl), await sha256Hex(canonicalUrl), now, now + 15 * 60 * 1000, errorCode)
    .run();
}

export async function sha256Hex(value: string): Promise<string> {
  const digest = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(value));
  return Array.from(new Uint8Array(digest), (byte) => byte.toString(16).padStart(2, "0")).join("");
}

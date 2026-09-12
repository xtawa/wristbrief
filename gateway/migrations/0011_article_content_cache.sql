-- Article full-text reader: D1 holds metadata only; sanitized ArticleDocument
-- JSON lives in R2 (articles/{key}/v1.json). Never store raw publisher HTML in
-- D1. Cached documents carry a finite TTL and always keep the source link.
PRAGMA foreign_keys = ON;

CREATE TABLE IF NOT EXISTS article_content_cache (
  article_key TEXT PRIMARY KEY NOT NULL,
  canonical_url_hash TEXT NOT NULL,
  source_url_hash TEXT NOT NULL,
  extraction_version INTEGER NOT NULL,
  status TEXT NOT NULL CHECK (status IN ('ready', 'failed')),
  r2_object_key TEXT,
  etag TEXT,
  fetched_at INTEGER NOT NULL,
  expires_at INTEGER NOT NULL,
  content_hash TEXT,
  error_code TEXT
);

CREATE INDEX IF NOT EXISTS idx_article_content_cache_expires
  ON article_content_cache(expires_at);

CREATE INDEX IF NOT EXISTS idx_article_content_cache_canonical
  ON article_content_cache(canonical_url_hash);

-- Media proxy metadata: mediaId is the base64url of the image URL (opaque to
-- clients, decodable by the proxy); image bytes are cached in R2.
CREATE TABLE IF NOT EXISTS article_media_cache (
  media_id TEXT PRIMARY KEY NOT NULL,
  content_type TEXT NOT NULL,
  byte_length INTEGER NOT NULL,
  r2_object_key TEXT,
  fetched_at INTEGER NOT NULL,
  expires_at INTEGER NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_article_media_cache_expires
  ON article_media_cache(expires_at);

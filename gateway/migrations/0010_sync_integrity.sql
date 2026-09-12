-- Sync integrity: per-user subscription uniqueness by normalized feed URL.
-- The mobile client derives subscription_id from a hash of the normalized URL,
-- but older pushes (or differently-normalized URLs) could create a second row
-- for the same source. A partial unique index on (user_id, lower(feed_url))
-- enforces one live row per source per user; the sync store resolves conflicts
-- in favor of the existing row instead of throwing.
PRAGMA foreign_keys = ON;

-- De-duplicate any pre-existing rows first (keep the most recently updated per
-- user + normalized URL) so the unique index can be created on dirty data.
DELETE FROM user_subscriptions
WHERE rowid NOT IN (
  SELECT keep_rowid FROM (
    SELECT user_id, lower(feed_url) AS url_key, max(rowid) AS keep_rowid
    FROM user_subscriptions
    WHERE feed_url != ''
    GROUP BY user_id, lower(feed_url)
  )
) AND feed_url != '';

CREATE UNIQUE INDEX IF NOT EXISTS idx_user_subscriptions_user_feed_url
  ON user_subscriptions(user_id, lower(feed_url))
  WHERE feed_url != '';

-- Faster tombstone pulls for multi-device deletion propagation.
CREATE INDEX IF NOT EXISTS idx_user_subscriptions_deleted_at
  ON user_subscriptions(user_id, deleted_at);

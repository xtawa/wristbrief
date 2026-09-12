-- Cloud Sync, Content Code, and Shared Transcript Registry
CREATE TABLE IF NOT EXISTS devices (
  id TEXT PRIMARY KEY,
  user_id TEXT NOT NULL,
  platform TEXT NOT NULL,
  display_name TEXT,
  app_version TEXT,
  created_at INTEGER NOT NULL,
  last_seen_at INTEGER NOT NULL,
  revoked_at INTEGER
);
CREATE INDEX IF NOT EXISTS idx_devices_user_id ON devices(user_id);

CREATE TABLE IF NOT EXISTS user_subscriptions (
  user_id TEXT NOT NULL,
  subscription_id TEXT NOT NULL,
  feed_url TEXT NOT NULL,
  title TEXT,
  category TEXT,
  enabled INTEGER NOT NULL,
  send_to_watch INTEGER NOT NULL,
  watch_keywords_json TEXT,
  revision INTEGER NOT NULL,
  updated_at INTEGER NOT NULL,
  deleted_at INTEGER,
  PRIMARY KEY (user_id, subscription_id)
);
CREATE INDEX IF NOT EXISTS idx_user_subscriptions_user_updated ON user_subscriptions(user_id, updated_at);

CREATE TABLE IF NOT EXISTS user_item_states (
  user_id TEXT NOT NULL,
  item_id TEXT NOT NULL,
  is_read INTEGER NOT NULL,
  is_saved INTEGER NOT NULL,
  read_changed_at INTEGER,
  saved_changed_at INTEGER,
  revision INTEGER NOT NULL,
  updated_at INTEGER NOT NULL,
  PRIMARY KEY (user_id, item_id)
);
CREATE INDEX IF NOT EXISTS idx_user_item_states_user_updated ON user_item_states(user_id, updated_at);

CREATE TABLE IF NOT EXISTS user_playback_progress (
  user_id TEXT NOT NULL,
  content_id TEXT NOT NULL,
  episode_local_id TEXT,
  position_ms INTEGER NOT NULL,
  duration_ms INTEGER NOT NULL,
  playback_speed REAL NOT NULL,
  completed INTEGER NOT NULL,
  source_device_id TEXT,
  playback_session_id TEXT,
  progress_generation INTEGER NOT NULL DEFAULT 1,
  revision INTEGER NOT NULL,
  updated_at INTEGER NOT NULL,
  PRIMARY KEY (user_id, content_id)
);
CREATE INDEX IF NOT EXISTS idx_user_playback_progress_user_updated ON user_playback_progress(user_id, updated_at);

CREATE TABLE IF NOT EXISTS user_sync_cursors (
  user_id TEXT NOT NULL,
  device_id TEXT NOT NULL,
  cursor_value INTEGER NOT NULL,
  updated_at INTEGER NOT NULL,
  PRIMARY KEY (user_id, device_id)
);

CREATE TABLE IF NOT EXISTS podcast_contents (
  id TEXT PRIMARY KEY,
  content_code TEXT NOT NULL UNIQUE,
  media_type TEXT NOT NULL,
  canonical_title TEXT,
  canonical_published_at INTEGER,
  canonical_duration_ms INTEGER,
  share_policy TEXT NOT NULL,
  status TEXT NOT NULL,
  preferred_transcript_artifact_id TEXT,
  created_at INTEGER NOT NULL,
  updated_at INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_podcast_contents_code ON podcast_contents(content_code);

CREATE TABLE IF NOT EXISTS content_aliases (
  alias_type TEXT NOT NULL,
  alias_value_hash TEXT NOT NULL,
  content_id TEXT NOT NULL,
  confidence TEXT NOT NULL,
  verified INTEGER NOT NULL,
  created_at INTEGER NOT NULL,
  PRIMARY KEY (alias_type, alias_value_hash)
);
CREATE INDEX IF NOT EXISTS idx_content_aliases_content_id ON content_aliases(content_id);

CREATE TABLE IF NOT EXISTS content_fingerprints (
  content_id TEXT NOT NULL,
  fingerprint_type TEXT NOT NULL,
  fingerprint_value TEXT NOT NULL,
  verified_at INTEGER NOT NULL,
  PRIMARY KEY (fingerprint_type, fingerprint_value)
);
CREATE INDEX IF NOT EXISTS idx_content_fingerprints_content ON content_fingerprints(content_id);

CREATE TABLE IF NOT EXISTS transcript_artifacts (
  id TEXT PRIMARY KEY,
  content_id TEXT NOT NULL,
  language TEXT NOT NULL,
  artifact_version INTEGER NOT NULL,
  provider TEXT,
  model TEXT,
  status TEXT NOT NULL,
  object_key_json TEXT,
  object_key_text TEXT,
  object_key_segments TEXT,
  transcript_hash TEXT,
  word_count INTEGER,
  segment_count INTEGER,
  quality_score REAL,
  share_policy TEXT NOT NULL,
  created_by_user_id TEXT,
  created_at INTEGER NOT NULL,
  updated_at INTEGER NOT NULL,
  UNIQUE (content_id, language, artifact_version)
);
CREATE INDEX IF NOT EXISTS idx_transcript_artifacts_content ON transcript_artifacts(content_id);

CREATE TABLE IF NOT EXISTS user_artifact_access (
  user_id TEXT NOT NULL,
  artifact_id TEXT NOT NULL,
  content_id TEXT NOT NULL,
  access_source TEXT NOT NULL,
  first_accessed_at INTEGER NOT NULL,
  last_accessed_at INTEGER NOT NULL,
  quota_units_charged REAL NOT NULL,
  PRIMARY KEY (user_id, artifact_id)
);
CREATE INDEX IF NOT EXISTS idx_user_artifact_access_user_content ON user_artifact_access(user_id, content_id);

CREATE TABLE IF NOT EXISTS artifact_jobs (
  id TEXT PRIMARY KEY,
  dedupe_key TEXT NOT NULL UNIQUE,
  user_id TEXT NOT NULL,
  content_id TEXT NOT NULL,
  artifact_type TEXT NOT NULL,
  language TEXT NOT NULL,
  requested_version INTEGER,
  status TEXT NOT NULL,
  lease_owner TEXT,
  lease_expires_at INTEGER,
  attempt_count INTEGER NOT NULL,
  error_code TEXT,
  created_at INTEGER NOT NULL,
  updated_at INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_artifact_jobs_dedupe ON artifact_jobs(dedupe_key);
CREATE INDEX IF NOT EXISTS idx_artifact_jobs_content ON artifact_jobs(content_id);

CREATE TABLE IF NOT EXISTS credit_transactions (
  id TEXT PRIMARY KEY,
  user_id TEXT NOT NULL,
  operation_type TEXT NOT NULL,
  reference_id TEXT NOT NULL,
  units REAL NOT NULL,
  multiplier REAL NOT NULL,
  status TEXT NOT NULL,
  created_at INTEGER NOT NULL,
  committed_at INTEGER,
  UNIQUE (user_id, operation_type, reference_id)
);
CREATE INDEX IF NOT EXISTS idx_credit_transactions_user ON credit_transactions(user_id);

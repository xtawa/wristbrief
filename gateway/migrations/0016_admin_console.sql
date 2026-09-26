-- Admin console operational state.
--
-- Two things the console needs to remember that are not derivable from anywhere
-- else: the outcome of the last connectivity check per configured provider
-- (AI providers and the STT/TTS presets), and the history of SMTP test sends.
--
-- Neither table stores credential material. Connectivity rows keep only a
-- status enum, an optional upstream HTTP status, a latency, and a short
-- non-secret detail code; SMTP test rows keep the recipient, a status enum and a
-- transport error *class* (e.g. "EAUTH"), never a message body or a password.
-- API keys, SMTP passwords and session/CSRF tokens stay out of D1 entirely.
PRAGMA foreign_keys = ON;

CREATE TABLE IF NOT EXISTS admin_connectivity_checks (
  target_type TEXT NOT NULL CHECK (target_type IN ('ai_provider', 'audio_provider')),
  target_id TEXT NOT NULL,
  status TEXT NOT NULL CHECK (status IN ('ok', 'auth_rejected', 'unreachable', 'upstream_error', 'not_configured', 'disabled')),
  http_status INTEGER,
  latency_ms INTEGER,
  detail TEXT,
  checked_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
  checked_by_user_id TEXT,
  PRIMARY KEY (target_type, target_id)
);

CREATE INDEX IF NOT EXISTS idx_admin_connectivity_checks_checked_at
  ON admin_connectivity_checks(checked_at DESC);

CREATE TABLE IF NOT EXISTS admin_smtp_tests (
  id TEXT PRIMARY KEY NOT NULL,
  status TEXT NOT NULL CHECK (status IN ('sent', 'not_configured', 'send_failed')),
  recipient TEXT NOT NULL,
  error_class TEXT,
  actor_user_id TEXT,
  created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_admin_smtp_tests_created_at
  ON admin_smtp_tests(created_at DESC);

-- The operational overview aggregates job outcomes by status and lists the most
-- recent failures; artifact_jobs only had dedupe/content indexes before.
CREATE INDEX IF NOT EXISTS idx_artifact_jobs_status_updated_at
  ON artifact_jobs(status, updated_at DESC);

CREATE INDEX IF NOT EXISTS idx_artifact_jobs_updated_at
  ON artifact_jobs(updated_at DESC);

-- Stable keyset for the paginated users list (created_at DESC, id DESC).
CREATE INDEX IF NOT EXISTS idx_users_created_at_id
  ON users(created_at DESC, id DESC);

-- Current-period managed AI usage lookups for the quota overview.
CREATE INDEX IF NOT EXISTS idx_managed_ai_usage_period_key
  ON managed_ai_usage(period_key, user_id);

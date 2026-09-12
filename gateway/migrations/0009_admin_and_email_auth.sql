-- Admin roles, first-admin bootstrap, web admin sessions, and email/password auth.
-- Raw secrets (password hashes aside, which are Argon2id encoded strings) are never
-- persisted: verification/reset/admin-session tables store SHA-256 token hashes only.
PRAGMA foreign_keys = ON;

-- The identities provider CHECK cannot be ALTERed in SQLite; rebuild the table to
-- allow the "email" provider alongside "google". Same columns, same PK, same FK.
CREATE TABLE IF NOT EXISTS identities_provider_v2 (
  provider TEXT NOT NULL CHECK (provider IN ('google', 'email')),
  provider_subject TEXT NOT NULL,
  user_id TEXT NOT NULL,
  email TEXT,
  display_name TEXT,
  picture_url TEXT,
  created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (provider, provider_subject),
  FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
);

INSERT INTO identities_provider_v2 (provider, provider_subject, user_id, email, display_name, picture_url, created_at, updated_at)
  SELECT provider, provider_subject, user_id, email, display_name, picture_url, created_at, updated_at FROM identities;

DROP TABLE identities;
ALTER TABLE identities_provider_v2 RENAME TO identities;

CREATE INDEX IF NOT EXISTS idx_identities_user_id
  ON identities(user_id);

-- Roles are a separate table (not users.is_admin) so future roles (support,
-- readonly-admin) and auditing stay explicit and the core identity table stays clean.
CREATE TABLE IF NOT EXISTS user_roles (
  user_id TEXT NOT NULL,
  role TEXT NOT NULL CHECK (role IN ('admin')),
  created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
  created_by_user_id TEXT,
  PRIMARY KEY (user_id, role),
  FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
);

-- Singleton bootstrap gate. The first successful registration wins a single-statement
-- CAS UPDATE (see emailAuthService) that flips first_admin_user_id from NULL and closes
-- the bootstrap window exactly once; reopening registration later can never re-enter
-- the first-admin branch.
CREATE TABLE IF NOT EXISTS bootstrap_state (
  singleton_id INTEGER PRIMARY KEY CHECK (singleton_id = 1),
  first_admin_user_id TEXT UNIQUE,
  web_registration_enabled INTEGER NOT NULL DEFAULT 1 CHECK (web_registration_enabled IN (0, 1)),
  bootstrap_completed_at TEXT,
  revision INTEGER NOT NULL DEFAULT 1
);

INSERT OR IGNORE INTO bootstrap_state (singleton_id, first_admin_user_id, web_registration_enabled, bootstrap_completed_at, revision)
  VALUES (1, NULL, 1, NULL, 1);

-- Admin-managed settings. registration_mode gates post-bootstrap sign-ups
-- ("OPEN" | "CLOSED"); the bootstrap window above gates the first one.
CREATE TABLE IF NOT EXISTS system_settings (
  key TEXT PRIMARY KEY,
  value_json TEXT NOT NULL,
  revision INTEGER NOT NULL DEFAULT 1,
  updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_by_user_id TEXT
);

INSERT OR IGNORE INTO system_settings (key, value_json, revision, updated_at, updated_by_user_id)
  VALUES ('registration_mode', '"CLOSED"', 1, CURRENT_TIMESTAMP, NULL);

-- Browser sessions for the server-rendered /admin area. Distinct from the mobile
-- Bearer sessions table: HttpOnly cookie + CSRF secret, never localStorage.
CREATE TABLE IF NOT EXISTS admin_web_sessions (
  id TEXT PRIMARY KEY NOT NULL,
  user_id TEXT NOT NULL,
  session_hash TEXT NOT NULL UNIQUE CHECK (length(session_hash) = 64),
  csrf_secret_hash TEXT NOT NULL CHECK (length(csrf_secret_hash) = 64),
  created_at TEXT NOT NULL,
  expires_at TEXT NOT NULL,
  last_seen_at TEXT NOT NULL,
  revoked_at TEXT,
  ip_prefix_hash TEXT,
  user_agent_hash TEXT,
  FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_admin_web_sessions_user_id
  ON admin_web_sessions(user_id);

-- Email/password credentials. provider_subject for email identities is
-- email_credentials.id (never the email string itself).
CREATE TABLE IF NOT EXISTS email_credentials (
  id TEXT PRIMARY KEY NOT NULL,
  user_id TEXT NOT NULL,
  normalized_email TEXT NOT NULL UNIQUE,
  password_hash TEXT NOT NULL,
  password_algo TEXT NOT NULL CHECK (password_algo IN ('argon2id')),
  password_params_json TEXT,
  verified_at TEXT,
  disabled_at TEXT,
  created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
  FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_email_credentials_user_id
  ON email_credentials(user_id);

CREATE TABLE IF NOT EXISTS email_verification_tokens (
  id TEXT PRIMARY KEY NOT NULL,
  credential_id TEXT NOT NULL,
  token_hash TEXT NOT NULL UNIQUE CHECK (length(token_hash) = 64),
  expires_at TEXT NOT NULL,
  consumed_at TEXT,
  created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
  FOREIGN KEY (credential_id) REFERENCES email_credentials(id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_email_verification_tokens_credential_id
  ON email_verification_tokens(credential_id);

CREATE TABLE IF NOT EXISTS password_reset_tokens (
  id TEXT PRIMARY KEY NOT NULL,
  credential_id TEXT NOT NULL,
  token_hash TEXT NOT NULL UNIQUE CHECK (length(token_hash) = 64),
  expires_at TEXT NOT NULL,
  consumed_at TEXT,
  created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
  FOREIGN KEY (credential_id) REFERENCES email_credentials(id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_password_reset_tokens_credential_id
  ON password_reset_tokens(credential_id);

-- Admin audit trail. before_json/after_json must never contain secrets; provider
-- changes record secret_version only (by construction in the audit writer).
CREATE TABLE IF NOT EXISTS admin_audit_log (
  id TEXT PRIMARY KEY NOT NULL,
  actor_user_id TEXT,
  action TEXT NOT NULL,
  target_type TEXT,
  target_id TEXT,
  before_json TEXT,
  after_json TEXT,
  request_id TEXT,
  created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
  ip_prefix_hash TEXT
);

CREATE INDEX IF NOT EXISTS idx_admin_audit_log_created_at
  ON admin_audit_log(created_at);

-- Fixed-window counters for auth/admin rate limits (register, login, verify,
-- forgot, reset, admin login, admin recovery). Buckets combine route x IP prefix
-- x normalized-email hash x user id.
CREATE TABLE IF NOT EXISTS auth_rate_limits (
  bucket TEXT NOT NULL,
  window_start INTEGER NOT NULL,
  count INTEGER NOT NULL DEFAULT 0,
  PRIMARY KEY (bucket, window_start)
);

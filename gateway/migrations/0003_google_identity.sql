-- D1-compatible Google account identity foundation.
-- This migration is intentionally schema-only; deployment must apply it explicitly.
PRAGMA foreign_keys = ON;

CREATE TABLE IF NOT EXISTS users (
  id TEXT PRIMARY KEY NOT NULL,
  status TEXT NOT NULL DEFAULT 'active' CHECK (status IN ('active', 'disabled', 'deleted')),
  created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS identities (
  provider TEXT NOT NULL CHECK (provider IN ('google')),
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

CREATE INDEX IF NOT EXISTS idx_identities_user_id
  ON identities(user_id);

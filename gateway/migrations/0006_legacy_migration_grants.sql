CREATE TABLE IF NOT EXISTS legacy_migration_grants (
  token_hash TEXT PRIMARY KEY,
  user_id TEXT NOT NULL,
  created_at TEXT NOT NULL,
  expires_at TEXT NOT NULL,
  consumed_at TEXT,
  FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_legacy_migration_grants_user
  ON legacy_migration_grants(user_id, expires_at);

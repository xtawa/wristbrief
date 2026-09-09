-- D1-compatible Google Play purchase-token ownership foundation.
-- Store only SHA-256 token hashes; raw purchase tokens must never be persisted.
CREATE TABLE IF NOT EXISTS play_purchase_bindings (
  token_hash TEXT PRIMARY KEY NOT NULL CHECK (length(token_hash) = 64),
  user_id TEXT NOT NULL,
  created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_play_purchase_bindings_user
  ON play_purchase_bindings(user_id);

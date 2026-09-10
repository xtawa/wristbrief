-- Idempotency ledger for authenticated Google Play RTDN Pub/Sub deliveries.
-- Pub/Sub message ids are opaque transport identifiers; no purchase token or notification body is persisted here.
CREATE TABLE IF NOT EXISTS play_rtdn_messages (
  message_id TEXT PRIMARY KEY NOT NULL CHECK (length(message_id) BETWEEN 1 AND 512),
  processed_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_play_rtdn_messages_processed_at
  ON play_rtdn_messages(processed_at);

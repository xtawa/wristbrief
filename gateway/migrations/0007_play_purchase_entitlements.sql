-- D1-compatible play purchase subscription lifecycle and multi-token aggregation.
-- Adds product_id, status, and expires_at to track individual subscription tokens.
ALTER TABLE play_purchase_bindings ADD COLUMN product_id TEXT;
ALTER TABLE play_purchase_bindings ADD COLUMN status TEXT DEFAULT 'active';
ALTER TABLE play_purchase_bindings ADD COLUMN expires_at TEXT;

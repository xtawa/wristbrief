-- D1-compatible membership foundation. Not applied automatically; bind and migrate in deployment setup.
CREATE TABLE IF NOT EXISTS membership_entitlements (
  user_id TEXT PRIMARY KEY NOT NULL,
  plan TEXT NOT NULL CHECK (plan IN ('FREE', 'PRO')),
  source TEXT NOT NULL,
  expires_at TEXT,
  updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS managed_ai_usage (
  user_id TEXT NOT NULL,
  period_key TEXT NOT NULL,
  used INTEGER NOT NULL DEFAULT 0 CHECK (used >= 0),
  quota_limit INTEGER CHECK (quota_limit IS NULL OR quota_limit >= 0),
  updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (user_id, period_key)
);

CREATE INDEX IF NOT EXISTS idx_membership_entitlements_plan
  ON membership_entitlements(plan);

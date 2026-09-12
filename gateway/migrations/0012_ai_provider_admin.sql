-- Server-managed AI provider configuration. D1 stores metadata + a reference
-- to a Worker secret slot only (AI_PROVIDER_SECRET_1..10); API keys never touch
-- D1, the admin UI, logs, or clients.
PRAGMA foreign_keys = ON;

CREATE TABLE IF NOT EXISTS ai_provider_configs (
  id TEXT PRIMARY KEY NOT NULL,
  adapter_type TEXT NOT NULL CHECK (adapter_type IN ('openai-compatible', 'openrouter', 'gemini', 'anthropic')),
  display_name TEXT NOT NULL,
  base_url TEXT,
  model TEXT NOT NULL,
  secret_ref TEXT NOT NULL CHECK (secret_ref GLOB 'AI_PROVIDER_SECRET_[0-9]'),
  enabled INTEGER NOT NULL DEFAULT 1 CHECK (enabled IN (0, 1)),
  priority INTEGER NOT NULL DEFAULT 100,
  timeout_ms INTEGER NOT NULL DEFAULT 15000 CHECK (timeout_ms BETWEEN 1000 AND 60000),
  max_retries INTEGER NOT NULL DEFAULT 1 CHECK (max_retries BETWEEN 0 AND 2),
  circuit_failure_threshold INTEGER,
  circuit_open_seconds INTEGER,
  config_revision INTEGER NOT NULL DEFAULT 1,
  created_at INTEGER NOT NULL,
  updated_at INTEGER NOT NULL,
  updated_by_user_id TEXT
);

CREATE INDEX IF NOT EXISTS idx_ai_provider_configs_enabled
  ON ai_provider_configs(enabled, priority);

-- Best-effort health snapshot for the in-memory circuit breaker. Workers run
-- multiple isolates, so this is advisory state, not a strict global breaker
-- (a strict one would need a Durable Object, which is out of scope by design).
CREATE TABLE IF NOT EXISTS ai_provider_health (
  provider_id TEXT PRIMARY KEY NOT NULL,
  status TEXT NOT NULL CHECK (status IN ('CLOSED', 'OPEN', 'HALF_OPEN')),
  consecutive_failures INTEGER NOT NULL DEFAULT 0,
  last_success_at INTEGER,
  last_failure_at INTEGER,
  circuit_open_until INTEGER
);

CREATE TABLE IF NOT EXISTS audio_provider_configs (
  id TEXT PRIMARY KEY,
  capability TEXT NOT NULL CHECK (capability IN ('stt', 'tts')),
  adapter TEXT NOT NULL CHECK (adapter IN ('mimo', 'deepgram')),
  model TEXT NOT NULL,
  voice TEXT,
  secret_ref TEXT NOT NULL,
  enabled INTEGER NOT NULL DEFAULT 0 CHECK (enabled IN (0, 1)),
  priority INTEGER NOT NULL DEFAULT 100,
  updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
);
INSERT OR IGNORE INTO audio_provider_configs (id, capability, adapter, model, voice, secret_ref, enabled, priority) VALUES
  ('deepgram-stt', 'stt', 'deepgram', 'nova-3', NULL, 'AI_PROVIDER_SECRET_2', 0, 10),
  ('mimo-stt', 'stt', 'mimo', 'mimo-v2.5-asr', NULL, 'AI_PROVIDER_SECRET_1', 0, 20),
  ('mimo-tts', 'tts', 'mimo', 'mimo-v2.5-tts', 'mimo_default', 'AI_PROVIDER_SECRET_1', 0, 10),
  ('deepgram-tts', 'tts', 'deepgram', 'aura-2-thalia-en', NULL, 'AI_PROVIDER_SECRET_2', 0, 20);
CREATE TABLE IF NOT EXISTS artifact_job_inputs (
  job_id TEXT PRIMARY KEY REFERENCES artifact_jobs(id) ON DELETE CASCADE,
  audio_url TEXT NOT NULL,
  duration_ms INTEGER NOT NULL DEFAULT 0
);

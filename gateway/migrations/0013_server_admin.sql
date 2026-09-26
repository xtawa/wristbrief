-- Persistent server administration. Mobile users remain separate from the sole web admin.
CREATE TABLE IF NOT EXISTS admin_security (
  user_id TEXT PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
  must_change_password INTEGER NOT NULL DEFAULT 1 CHECK (must_change_password IN (0, 1)),
  password_changed_at TEXT
);

CREATE TABLE IF NOT EXISTS encrypted_settings (
  key TEXT PRIMARY KEY,
  encrypted_value TEXT NOT NULL,
  updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
);

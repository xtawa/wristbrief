import { hashPassword } from "../src/emailAuth/passwordHasher";
import { D1EmailCredentialStore } from "../src/emailAuth/emailCredentialStore";

export const ADMIN_EMAIL = "zeromostia@gmail.com";

/** Seed one admin from an operator-provided temporary password, never from a public form. */
export async function ensureServerAdmin(db: D1Database, temporaryPassword?: string): Promise<void> {
  const existing = await new D1EmailCredentialStore(db).findByNormalizedEmail(ADMIN_EMAIL);
  if (existing) {
    const role = await db.prepare("SELECT 1 AS present FROM user_roles WHERE user_id = ? AND role = 'admin'")
      .bind(existing.userId).first();
    if (!role) throw new Error("Configured admin email is already a non-admin account; manual recovery required");
    const admins = await db.prepare("SELECT COUNT(*) AS count FROM user_roles WHERE role = 'admin'").first<{ count: number }>();
    if (admins?.count !== 1) throw new Error("Server mode requires exactly one administrator");
    await db.prepare("INSERT OR IGNORE INTO admin_security (user_id, must_change_password) VALUES (?, 1)").bind(existing.userId).run();
    return;
  }
  const admins = await db.prepare("SELECT COUNT(*) AS count FROM user_roles WHERE role = 'admin'").first<{ count: number }>();
  if (admins?.count) throw new Error("An administrator already exists under another email; manual migration required");
  if (!temporaryPassword || temporaryPassword.length < 16) {
    throw new Error("Set WRISTBRIEF_ADMIN_INITIAL_PASSWORD (at least 16 characters) for first startup");
  }
  const userId = `usr_${crypto.randomUUID()}`;
  const credentialId = `cred_${crypto.randomUUID()}`;
  const hashed = await hashPassword(temporaryPassword);
  await db.batch([
    db.prepare("INSERT INTO users (id, status) VALUES (?, 'active')").bind(userId),
    db.prepare("INSERT INTO email_credentials (id, user_id, normalized_email, password_hash, password_algo, verified_at) VALUES (?, ?, ?, ?, 'argon2id', CURRENT_TIMESTAMP)")
      .bind(credentialId, userId, ADMIN_EMAIL, hashed),
    db.prepare("INSERT INTO identities (provider, provider_subject, user_id, email) VALUES ('email', ?, ?, ?)")
      .bind(credentialId, userId, ADMIN_EMAIL),
    db.prepare("INSERT INTO user_roles (user_id, role) VALUES (?, 'admin')").bind(userId),
    db.prepare("INSERT INTO admin_security (user_id, must_change_password) VALUES (?, 1)").bind(userId),
    db.prepare("UPDATE bootstrap_state SET first_admin_user_id = ?, web_registration_enabled = 0, bootstrap_completed_at = CURRENT_TIMESTAMP WHERE singleton_id = 1")
      .bind(userId),
    // Mobile account creation stays available; /admin never offers account creation.
    db.prepare("UPDATE system_settings SET value_json = '\"OPEN\"' WHERE key = 'registration_mode'")
  ]);
}

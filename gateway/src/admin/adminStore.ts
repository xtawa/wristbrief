export type BootstrapStateRow = {
  singletonId: number;
  firstAdminUserId: string | null;
  webRegistrationEnabled: boolean;
  bootstrapCompletedAt: string | null;
  revision: number;
};

export type RegistrationMode = "CLOSED" | "OPEN";

/**
 * D1 access for admin roles, the bootstrap singleton, and system settings.
 * No secrets are ever read or written through this store.
 */
export class D1AdminStore {
  constructor(private readonly db: D1Database) {}

  async readBootstrapState(): Promise<BootstrapStateRow | null> {
    const row = await this.db
      .prepare("SELECT singleton_id, first_admin_user_id, web_registration_enabled, bootstrap_completed_at, revision FROM bootstrap_state WHERE singleton_id = 1")
      .first<{ singleton_id: number; first_admin_user_id: string | null; web_registration_enabled: number; bootstrap_completed_at: string | null; revision: number }>();
    if (!row) return null;
    return {
      singletonId: row.singleton_id,
      firstAdminUserId: row.first_admin_user_id,
      webRegistrationEnabled: row.web_registration_enabled === 1,
      bootstrapCompletedAt: row.bootstrap_completed_at,
      revision: row.revision
    };
  }

  /**
   * Single-statement CAS that claims the first-admin slot exactly once. The
   * bootstrap window (first_admin_user_id IS NULL AND web_registration_enabled = 1)
   * can only be won by one writer; losers observe zero changed rows.
   */
  async claimFirstAdmin(candidateUserId: string, completedAtIso: string): Promise<boolean> {
    const result = await this.db
      .prepare(
        `UPDATE bootstrap_state
         SET first_admin_user_id = ?, web_registration_enabled = 0, bootstrap_completed_at = ?, revision = revision + 1
         WHERE singleton_id = 1 AND first_admin_user_id IS NULL AND web_registration_enabled = 1`
      )
      .bind(candidateUserId, completedAtIso)
      .run();
    return (result.meta.changes ?? 0) > 0;
  }

  async readRegistrationMode(): Promise<RegistrationMode> {
    const row = await this.db
      .prepare("SELECT value_json FROM system_settings WHERE key = 'registration_mode'")
      .first<{ value_json: string }>();
    try {
      const value = JSON.parse(row?.value_json ?? '"CLOSED"');
      return value === "OPEN" ? "OPEN" : "CLOSED";
    } catch {
      return "CLOSED";
    }
  }

  async writeRegistrationMode(mode: RegistrationMode, updatedByUserId: string | null): Promise<void> {
    await this.db
      .prepare(
        `INSERT INTO system_settings (key, value_json, revision, updated_at, updated_by_user_id)
         VALUES ('registration_mode', ?, 1, CURRENT_TIMESTAMP, ?)
         ON CONFLICT(key) DO UPDATE SET value_json = excluded.value_json, revision = revision + 1, updated_at = CURRENT_TIMESTAMP, updated_by_user_id = excluded.updated_by_user_id`
      )
      .bind(JSON.stringify(mode), updatedByUserId)
      .run();
  }

  async rolesForUser(userId: string): Promise<string[]> {
    const result = await this.db
      .prepare("SELECT role FROM user_roles WHERE user_id = ? ORDER BY role")
      .bind(userId)
      .all<{ role: string }>();
    return (result.results ?? []).map((row) => row.role);
  }

  async addRole(userId: string, role: string, createdByUserId: string | null): Promise<void> {
    await this.db
      .prepare("INSERT OR IGNORE INTO user_roles (user_id, role, created_at, created_by_user_id) VALUES (?, ?, CURRENT_TIMESTAMP, ?)")
      .bind(userId, role, createdByUserId)
      .run();
  }

  async removeRole(userId: string, role: string): Promise<boolean> {
    const result = await this.db
      .prepare("DELETE FROM user_roles WHERE user_id = ? AND role = ?")
      .bind(userId, role)
      .run();
    return (result.meta.changes ?? 0) > 0;
  }

  async countAdmins(): Promise<number> {
    const row = await this.db
      .prepare("SELECT COUNT(*) AS admins FROM user_roles WHERE role = 'admin'")
      .first<{ admins: number }>();
    return row?.admins ?? 0;
  }

  async userIsActive(userId: string): Promise<boolean> {
    const row = await this.db
      .prepare("SELECT status FROM users WHERE id = ? LIMIT 1")
      .bind(userId)
      .first<{ status: string }>();
    return row?.status === "active";
  }

  async revokeAdminWebSessionsForUser(userId: string, revokedAtIso: string): Promise<void> {
    await this.db
      .prepare("UPDATE admin_web_sessions SET revoked_at = COALESCE(revoked_at, ?) WHERE user_id = ?")
      .bind(revokedAtIso, userId)
      .run();
  }

  async revokeSessionsForUser(userId: string, revokedAtIso: string): Promise<void> {
    await this.db
      .prepare("UPDATE sessions SET revoked_at = COALESCE(revoked_at, ?) WHERE user_id = ?")
      .bind(revokedAtIso, userId)
      .run();
    await this.revokeAdminWebSessionsForUser(userId, revokedAtIso);
  }
}

export type AdminStoreEnv = {
  ACCOUNT_DB?: D1Database;
};

export function createConfiguredAdminStore(env: AdminStoreEnv): D1AdminStore | undefined {
  return env.ACCOUNT_DB ? new D1AdminStore(env.ACCOUNT_DB) : undefined;
}

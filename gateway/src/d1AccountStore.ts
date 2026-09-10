import type {
  AccountIdentityStore,
  GoogleIdentityRecord,
  GoogleIdentityResolution
} from "./accountIdentity";
import type {
  AccountSessionRecord,
  AccountSessionStore
} from "./accountSession";

export type AccountPersistenceEnv = {
  ACCOUNT_DB?: D1Database;
};

export type D1AccountStores = {
  identityStore: AccountIdentityStore;
  sessionStore: AccountSessionStore;
};

export class D1AccountIdentityStore implements AccountIdentityStore {
  constructor(private readonly db: D1Database) {}

  async resolveOrCreateGoogleIdentity(input: GoogleIdentityRecord): Promise<GoogleIdentityResolution> {
    const statements = [
      this.db.prepare(
        "INSERT OR IGNORE INTO users (id, status, created_at, updated_at) VALUES (?, 'active', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)"
      ).bind(input.userId),
      this.db.prepare(
        "INSERT OR IGNORE INTO identities (provider, provider_subject, user_id, email, display_name, picture_url, created_at, updated_at) VALUES ('google', ?, ?, ?, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)"
      ).bind(input.providerSubject, input.userId, input.email, input.displayName ?? null, input.pictureUrl ?? null),
      this.db.prepare(
        "UPDATE identities SET email = ?, display_name = ?, picture_url = ?, updated_at = CURRENT_TIMESTAMP WHERE provider = 'google' AND provider_subject = ?"
      ).bind(input.email, input.displayName ?? null, input.pictureUrl ?? null, input.providerSubject),
      this.db.prepare(
        "DELETE FROM users WHERE id = ? AND NOT EXISTS (SELECT 1 FROM identities WHERE user_id = ?)"
      ).bind(input.userId, input.userId),
      this.db.prepare(
        "SELECT i.user_id, u.status FROM identities i JOIN users u ON u.id = i.user_id WHERE i.provider = 'google' AND i.provider_subject = ? LIMIT 1"
      ).bind(input.providerSubject)
    ];

    const results = await this.db.batch<{ user_id?: string; status?: string }>(statements);
    const row = results[4]?.results?.[0];
    if (!row?.user_id) throw new Error("identity_persistence_failed");
    if (row.status !== "active") throw new Error("account_disabled");
    return { userId: row.user_id, created: row.user_id === input.userId };
  }

  async linkGoogleIdentityToUser(input: GoogleIdentityRecord): Promise<GoogleIdentityResolution> {
    const statements = [
      this.db.prepare(
        "INSERT OR IGNORE INTO users (id, status, created_at, updated_at) VALUES (?, 'active', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)"
      ).bind(input.userId),
      this.db.prepare(
        "INSERT OR IGNORE INTO identities (provider, provider_subject, user_id, email, display_name, picture_url, created_at, updated_at) VALUES ('google', ?, ?, ?, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)"
      ).bind(input.providerSubject, input.userId, input.email, input.displayName ?? null, input.pictureUrl ?? null),
      this.db.prepare(
        "UPDATE identities SET email = ?, display_name = ?, picture_url = ?, updated_at = CURRENT_TIMESTAMP WHERE provider = 'google' AND provider_subject = ? AND user_id = ?"
      ).bind(input.email, input.displayName ?? null, input.pictureUrl ?? null, input.providerSubject, input.userId),
      this.db.prepare(
        "SELECT i.user_id, u.status FROM identities i JOIN users u ON u.id = i.user_id WHERE i.provider = 'google' AND i.provider_subject = ? LIMIT 1"
      ).bind(input.providerSubject)
    ];

    const results = await this.db.batch<{ user_id?: string; status?: string }>(statements);
    const row = results[3]?.results?.[0];
    if (!row?.user_id) throw new Error("identity_persistence_failed");
    // Unlike resolve-or-create, input.userId is an authenticated legacy principal rather than a
    // disposable random candidate. Never delete it when the Google subject belongs to someone else.
    if (row.user_id !== input.userId) throw new Error("identity_conflict");
    if (row.status !== "active") throw new Error("account_disabled");
    return { userId: row.user_id, created: (results[1]?.meta?.changes ?? 0) > 0 };
  }
}

export class D1AccountSessionStore implements AccountSessionStore {
  constructor(private readonly db: D1Database) {}

  async createSession(record: AccountSessionRecord): Promise<void> {
    await this.db.prepare(
      "INSERT INTO sessions (id, user_id, token_hash, created_at, expires_at, revoked_at) VALUES (?, ?, ?, ?, ?, NULL)"
    ).bind(record.id, record.userId, record.tokenHash, record.createdAt, record.expiresAt).run();
  }

  async sessionForTokenHash(tokenHash: string): Promise<AccountSessionRecord | null> {
    const row = await this.db.prepare(
      "SELECT id, user_id, token_hash, created_at, expires_at, revoked_at FROM sessions WHERE token_hash = ? LIMIT 1"
    ).bind(tokenHash).first<{
      id: string;
      user_id: string;
      token_hash: string;
      created_at: string;
      expires_at: string;
      revoked_at: string | null;
    }>();
    if (!row) return null;
    return {
      id: row.id,
      userId: row.user_id,
      tokenHash: row.token_hash,
      createdAt: row.created_at,
      expiresAt: row.expires_at,
      ...(row.revoked_at ? { revokedAt: row.revoked_at } : {})
    };
  }

  async revokeByTokenHash(tokenHash: string, revokedAt: string): Promise<boolean> {
    const result = await this.db.prepare(
      "UPDATE sessions SET revoked_at = COALESCE(revoked_at, ?) WHERE token_hash = ?"
    ).bind(revokedAt, tokenHash).run();
    return (result.meta.changes ?? 0) > 0;
  }
}

export function createConfiguredD1AccountStores(env: AccountPersistenceEnv): D1AccountStores | undefined {
  if (!env.ACCOUNT_DB) return undefined;
  return {
    identityStore: new D1AccountIdentityStore(env.ACCOUNT_DB),
    sessionStore: new D1AccountSessionStore(env.ACCOUNT_DB)
  };
}

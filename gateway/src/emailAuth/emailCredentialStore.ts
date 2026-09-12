import type { EmailTokenType } from "./emailTokens";

export type EmailCredentialRecord = {
  id: string;
  userId: string;
  normalizedEmail: string;
  passwordHash: string;
  passwordAlgo: string;
  verifiedAt: string | null;
  disabledAt: string | null;
};

export type EmailTokenRecord = {
  id: string;
  credentialId: string;
  tokenHash: string;
  expiresAt: string;
  consumedAt: string | null;
};

export interface EmailCredentialStore {
  findByNormalizedEmail(normalizedEmail: string): Promise<EmailCredentialRecord | null>;
  findByCredentialId(credentialId: string): Promise<EmailCredentialRecord | null>;
  createCredential(record: EmailCredentialRecord): Promise<void>;
  markVerified(credentialId: string, verifiedAt: string): Promise<void>;
  updatePasswordHash(credentialId: string, passwordHash: string): Promise<void>;
}

export interface EmailTokenStore {
  createToken(type: EmailTokenType, record: EmailTokenRecord): Promise<void>;
  findActiveToken(type: EmailTokenType, tokenHash: string, nowIso: string): Promise<EmailTokenRecord | null>;
  consumeToken(type: EmailTokenType, tokenId: string, consumedAt: string): Promise<boolean>;
  revokeActiveTokens(type: EmailTokenType, credentialId: string, revokedAt: string): Promise<void>;
}

type CredentialRow = {
  id: string;
  user_id: string;
  normalized_email: string;
  password_hash: string;
  password_algo: string;
  verified_at: string | null;
  disabled_at: string | null;
};

export class D1EmailCredentialStore implements EmailCredentialStore {
  constructor(private readonly db: D1Database) {}

  async findByNormalizedEmail(normalizedEmail: string): Promise<EmailCredentialRecord | null> {
    return this.mapRow(
      await this.db
        .prepare(
          "SELECT id, user_id, normalized_email, password_hash, password_algo, verified_at, disabled_at FROM email_credentials WHERE normalized_email = ? LIMIT 1"
        )
        .bind(normalizedEmail)
        .first<CredentialRow>()
    );
  }

  async findByCredentialId(credentialId: string): Promise<EmailCredentialRecord | null> {
    return this.mapRow(
      await this.db
        .prepare(
          "SELECT id, user_id, normalized_email, password_hash, password_algo, verified_at, disabled_at FROM email_credentials WHERE id = ? LIMIT 1"
        )
        .bind(credentialId)
        .first<CredentialRow>()
    );
  }

  async createCredential(record: EmailCredentialRecord): Promise<void> {
    await this.db
      .prepare(
        "INSERT INTO email_credentials (id, user_id, normalized_email, password_hash, password_algo, verified_at, disabled_at, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)"
      )
      .bind(record.id, record.userId, record.normalizedEmail, record.passwordHash, record.passwordAlgo, record.verifiedAt, record.disabledAt)
      .run();
  }

  async markVerified(credentialId: string, verifiedAt: string): Promise<void> {
    await this.db
      .prepare("UPDATE email_credentials SET verified_at = COALESCE(verified_at, ?), updated_at = CURRENT_TIMESTAMP WHERE id = ?")
      .bind(verifiedAt, credentialId)
      .run();
  }

  async updatePasswordHash(credentialId: string, passwordHash: string): Promise<void> {
    await this.db
      .prepare("UPDATE email_credentials SET password_hash = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ?")
      .bind(passwordHash, credentialId)
      .run();
  }

  private mapRow(row: CredentialRow | null): EmailCredentialRecord | null {
    if (!row) return null;
    return {
      id: row.id,
      userId: row.user_id,
      normalizedEmail: row.normalized_email,
      passwordHash: row.password_hash,
      passwordAlgo: row.password_algo,
      verifiedAt: row.verified_at,
      disabledAt: row.disabled_at
    };
  }
}

const TOKEN_TABLE: Record<EmailTokenType, string> = {
  verification: "email_verification_tokens",
  password_reset: "password_reset_tokens"
};

export class D1EmailTokenStore implements EmailTokenStore {
  constructor(private readonly db: D1Database) {}

  async createToken(type: EmailTokenType, record: EmailTokenRecord): Promise<void> {
    await this.db
      .prepare(
        `INSERT INTO ${TOKEN_TABLE[type]} (id, credential_id, token_hash, expires_at, consumed_at, created_at) VALUES (?, ?, ?, ?, NULL, CURRENT_TIMESTAMP)`
      )
      .bind(record.id, record.credentialId, record.tokenHash, record.expiresAt)
      .run();
  }

  async findActiveToken(type: EmailTokenType, tokenHash: string, nowIso: string): Promise<EmailTokenRecord | null> {
    const row = await this.db
      .prepare(
        `SELECT id, credential_id, token_hash, expires_at, consumed_at FROM ${TOKEN_TABLE[type]} WHERE token_hash = ? AND consumed_at IS NULL AND expires_at > ? LIMIT 1`
      )
      .bind(tokenHash, nowIso)
      .first<{ id: string; credential_id: string; token_hash: string; expires_at: string; consumed_at: string | null }>();
    if (!row) return null;
    return {
      id: row.id,
      credentialId: row.credential_id,
      tokenHash: row.token_hash,
      expiresAt: row.expires_at,
      consumedAt: row.consumed_at
    };
  }

  async consumeToken(type: EmailTokenType, tokenId: string, consumedAt: string): Promise<boolean> {
    const result = await this.db
      .prepare(`UPDATE ${TOKEN_TABLE[type]} SET consumed_at = COALESCE(consumed_at, ?) WHERE id = ? AND consumed_at IS NULL`)
      .bind(consumedAt, tokenId)
      .run();
    return (result.meta.changes ?? 0) > 0;
  }

  async revokeActiveTokens(type: EmailTokenType, credentialId: string, revokedAt: string): Promise<void> {
    await this.db
      .prepare(`UPDATE ${TOKEN_TABLE[type]} SET consumed_at = COALESCE(consumed_at, ?) WHERE credential_id = ? AND consumed_at IS NULL`)
      .bind(revokedAt, credentialId)
      .run();
  }
}

export class InMemoryEmailCredentialStore implements EmailCredentialStore {
  private readonly byId = new Map<string, EmailCredentialRecord>();
  private readonly byEmail = new Map<string, string>();

  async findByNormalizedEmail(normalizedEmail: string): Promise<EmailCredentialRecord | null> {
    const id = this.byEmail.get(normalizedEmail);
    return id ? { ...(this.byId.get(id) as EmailCredentialRecord) } : null;
  }

  async findByCredentialId(credentialId: string): Promise<EmailCredentialRecord | null> {
    const record = this.byId.get(credentialId);
    return record ? { ...record } : null;
  }

  async createCredential(record: EmailCredentialRecord): Promise<void> {
    if (this.byEmail.has(record.normalizedEmail)) throw new Error("email_already_registered");
    this.byId.set(record.id, { ...record });
    this.byEmail.set(record.normalizedEmail, record.id);
  }

  async markVerified(credentialId: string, verifiedAt: string): Promise<void> {
    const record = this.byId.get(credentialId);
    if (record) record.verifiedAt = record.verifiedAt ?? verifiedAt;
  }

  async updatePasswordHash(credentialId: string, passwordHash: string): Promise<void> {
    const record = this.byId.get(credentialId);
    if (record) record.passwordHash = passwordHash;
  }
}

export class InMemoryEmailTokenStore implements EmailTokenStore {
  private readonly tokens = new Map<string, EmailTokenRecord>();

  async createToken(_type: EmailTokenType, record: EmailTokenRecord): Promise<void> {
    this.tokens.set(record.id, { ...record });
  }

  async findActiveToken(_type: EmailTokenType, tokenHash: string, nowIso: string): Promise<EmailTokenRecord | null> {
    for (const record of this.tokens.values()) {
      if (record.tokenHash === tokenHash && !record.consumedAt && record.expiresAt > nowIso) {
        return { ...record };
      }
    }
    return null;
  }

  async consumeToken(_type: EmailTokenType, tokenId: string, consumedAt: string): Promise<boolean> {
    const record = this.tokens.get(tokenId);
    if (!record || record.consumedAt) return false;
    record.consumedAt = consumedAt;
    return true;
  }

  async revokeActiveTokens(_type: EmailTokenType, credentialId: string, revokedAt: string): Promise<void> {
    for (const record of this.tokens.values()) {
      if (record.credentialId === credentialId && !record.consumedAt) record.consumedAt = revokedAt;
    }
  }
}

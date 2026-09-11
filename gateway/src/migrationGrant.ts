export type LegacyMigrationGrantRecord = {
  tokenHash: string;
  userId: string;
  createdAt: string;
  expiresAt: string;
  consumedAt?: string;
};

export interface LegacyMigrationGrantStore {
  createGrant(record: LegacyMigrationGrantRecord): Promise<void>;
  consumeGrant(tokenHash: string, consumedAt: string): Promise<string | null>;
}

export type MigrationGrantEnv = {
  ACCOUNT_DB?: D1Database;
  LEGACY_MIGRATION_GRANT_STORE?: LegacyMigrationGrantStore;
  LEGACY_MIGRATION_GRANT_TTL_SECONDS?: string;
};

const DEFAULT_TTL_SECONDS = 10 * 60;
const MIN_TTL_SECONDS = 60;
const MAX_TTL_SECONDS = 30 * 60;
const TOKEN_PATTERN = /^wbm_[A-Za-z0-9_-]{43}$/;

export class LegacyMigrationGrantService {
  constructor(
    private readonly store: LegacyMigrationGrantStore,
    private readonly ttlSeconds = DEFAULT_TTL_SECONDS,
    private readonly now: () => Date = () => new Date()
  ) {
    if (!Number.isInteger(ttlSeconds) || ttlSeconds < MIN_TTL_SECONDS || ttlSeconds > MAX_TTL_SECONDS) {
      throw new Error("invalid_migration_grant_ttl");
    }
  }

  async issue(userId: string): Promise<{ token: string; expiresAt: string }> {
    if (!validUserId(userId)) throw new Error("invalid_existing_user_id");
    const createdAt = this.now();
    const expiresAt = new Date(createdAt.getTime() + this.ttlSeconds * 1000);
    const token = `wbm_${randomBase64Url(32)}`;
    await this.store.createGrant({
      tokenHash: await sha256(token),
      userId,
      createdAt: createdAt.toISOString(),
      expiresAt: expiresAt.toISOString()
    });
    return { token, expiresAt: expiresAt.toISOString() };
  }

  async consume(token: string): Promise<string | null> {
    if (!TOKEN_PATTERN.test(token)) return null;
    return this.store.consumeGrant(await sha256(token), this.now().toISOString());
  }
}

export class InMemoryLegacyMigrationGrantStore implements LegacyMigrationGrantStore {
  private readonly records = new Map<string, LegacyMigrationGrantRecord>();

  async createGrant(record: LegacyMigrationGrantRecord): Promise<void> {
    this.records.set(record.tokenHash, { ...record });
  }

  async consumeGrant(tokenHash: string, consumedAt: string): Promise<string | null> {
    const record = this.records.get(tokenHash);
    if (!record || record.consumedAt || Date.parse(record.expiresAt) <= Date.parse(consumedAt)) return null;
    record.consumedAt = consumedAt;
    return record.userId;
  }
}

export class D1LegacyMigrationGrantStore implements LegacyMigrationGrantStore {
  constructor(private readonly db: D1Database) {}

  async createGrant(record: LegacyMigrationGrantRecord): Promise<void> {
    await this.db.prepare(
      "INSERT INTO legacy_migration_grants (token_hash, user_id, created_at, expires_at, consumed_at) VALUES (?, ?, ?, ?, NULL)"
    ).bind(record.tokenHash, record.userId, record.createdAt, record.expiresAt).run();
  }

  async consumeGrant(tokenHash: string, consumedAt: string): Promise<string | null> {
    const row = await this.db.prepare(
      "UPDATE legacy_migration_grants SET consumed_at = ? WHERE token_hash = ? AND consumed_at IS NULL AND expires_at > ? RETURNING user_id"
    ).bind(consumedAt, tokenHash, consumedAt).first<{ user_id?: string }>();
    return row?.user_id ?? null;
  }
}

export function createConfiguredMigrationGrantStore(env: MigrationGrantEnv): LegacyMigrationGrantStore | undefined {
  return env.LEGACY_MIGRATION_GRANT_STORE ?? (env.ACCOUNT_DB ? new D1LegacyMigrationGrantStore(env.ACCOUNT_DB) : undefined);
}

export function migrationGrantTtlSeconds(value: string | undefined): number {
  if (value === undefined || value.trim() === "") return DEFAULT_TTL_SECONDS;
  if (!/^\d+$/.test(value.trim())) throw new Error("invalid_migration_grant_ttl");
  const ttl = Number(value.trim());
  if (ttl < MIN_TTL_SECONDS || ttl > MAX_TTL_SECONDS) throw new Error("invalid_migration_grant_ttl");
  return ttl;
}

function validUserId(value: string): boolean {
  return value.length > 0 && value.length <= 128 && value === value.trim() && !/[\u0000-\u001F\u007F]/.test(value);
}

function randomBase64Url(byteLength: number): string {
  const bytes = new Uint8Array(byteLength);
  crypto.getRandomValues(bytes);
  let binary = "";
  for (const byte of bytes) binary += String.fromCharCode(byte);
  return btoa(binary).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/g, "");
}

async function sha256(value: string): Promise<string> {
  const digest = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(value));
  return Array.from(new Uint8Array(digest), (byte) => byte.toString(16).padStart(2, "0")).join("");
}

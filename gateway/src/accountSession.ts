const SESSION_TOKEN_BYTES = 32;
const DEFAULT_SESSION_TTL_SECONDS = 30 * 24 * 60 * 60;
const MAX_SESSION_TTL_SECONDS = 90 * 24 * 60 * 60;
const SESSION_TOKEN_PREFIX = "wbs_";

export type AccountSessionRecord = {
  id: string;
  userId: string;
  tokenHash: string;
  createdAt: string;
  expiresAt: string;
  revokedAt?: string;
};

export type IssuedAccountSession = {
  token: string;
  expiresAt: string;
};

export interface AccountSessionStore {
  createSession(record: AccountSessionRecord): Promise<void>;
  sessionForTokenHash(tokenHash: string): Promise<AccountSessionRecord | null>;
  revokeByTokenHash(tokenHash: string, revokedAt: string): Promise<boolean>;
  revokeAllForUser?(userId: string, revokedAt: string): Promise<void>;
}

type AccountSessionServiceOptions = {
  now?: () => number;
  newSessionId?: () => string;
  randomBytes?: (length: number) => Uint8Array;
  ttlSeconds?: number;
};

export class AccountSessionService {
  private readonly now: () => number;
  private readonly newSessionId: () => string;
  private readonly randomBytes: (length: number) => Uint8Array;
  private readonly ttlSeconds: number;

  constructor(
    private readonly store: AccountSessionStore,
    options: AccountSessionServiceOptions = {}
  ) {
    this.now = options.now ?? Date.now;
    this.newSessionId = options.newSessionId ?? (() => crypto.randomUUID());
    this.randomBytes = options.randomBytes ?? ((length) => crypto.getRandomValues(new Uint8Array(length)));
    this.ttlSeconds = normalizeTtlSeconds(options.ttlSeconds);
  }

  async issue(userId: string): Promise<IssuedAccountSession> {
    if (!validUserId(userId)) throw new Error("invalid_user_id");

    const sessionId = this.newSessionId();
    if (!validOpaqueId(sessionId, 128)) throw new Error("invalid_session_id");

    const tokenBytes = this.randomBytes(SESSION_TOKEN_BYTES);
    if (tokenBytes.byteLength !== SESSION_TOKEN_BYTES) throw new Error("invalid_session_entropy");
    const token = `${SESSION_TOKEN_PREFIX}${base64Url(tokenBytes)}`;
    const tokenHash = await sha256(token);
    const createdAtMs = this.now();
    const createdAt = new Date(createdAtMs).toISOString();
    const expiresAt = new Date(createdAtMs + this.ttlSeconds * 1000).toISOString();

    await this.store.createSession({
      id: sessionId,
      userId,
      tokenHash,
      createdAt,
      expiresAt
    });
    return { token, expiresAt };
  }

  async authenticateAuthorizationHeader(header: string | null): Promise<string | null> {
    const token = parseBearerSessionToken(header);
    if (!token) return null;
    const record = await this.store.sessionForTokenHash(await sha256(token));
    if (!record || record.revokedAt) return null;
    const expiresAtMs = Date.parse(record.expiresAt);
    if (!Number.isFinite(expiresAtMs) || expiresAtMs <= this.now()) return null;
    return validUserId(record.userId) ? record.userId : null;
  }

  async revokeAuthorizationHeader(header: string | null): Promise<boolean> {
    const token = parseBearerSessionToken(header);
    if (!token) return false;
    return this.store.revokeByTokenHash(await sha256(token), new Date(this.now()).toISOString());
  }
}

export class InMemoryAccountSessionStore implements AccountSessionStore {
  private readonly byHash = new Map<string, AccountSessionRecord>();

  async createSession(record: AccountSessionRecord): Promise<void> {
    if (this.byHash.has(record.tokenHash)) throw new Error("session_token_collision");
    this.byHash.set(record.tokenHash, { ...record });
  }

  async sessionForTokenHash(tokenHash: string): Promise<AccountSessionRecord | null> {
    const record = this.byHash.get(tokenHash);
    return record ? { ...record } : null;
  }

  async revokeByTokenHash(tokenHash: string, revokedAt: string): Promise<boolean> {
    const record = this.byHash.get(tokenHash);
    if (!record) return false;
    record.revokedAt = record.revokedAt ?? revokedAt;
    return true;
  }

  async revokeAllForUser(userId: string, revokedAt: string): Promise<void> {
    for (const record of this.byHash.values()) {
      if (record.userId === userId) {
        record.revokedAt = record.revokedAt ?? revokedAt;
      }
    }
  }

  records(): AccountSessionRecord[] {
    return Array.from(this.byHash.values(), (record) => ({ ...record }));
  }
}

export function parseBearerSessionToken(header: string | null): string | null {
  if (!header || header.length > 256) return null;
  const match = /^Bearer (wbs_[A-Za-z0-9_-]{43})$/.exec(header);
  return match?.[1] ?? null;
}

function normalizeTtlSeconds(value: number | undefined): number {
  if (value === undefined) return DEFAULT_SESSION_TTL_SECONDS;
  if (!Number.isFinite(value)) throw new Error("invalid_session_ttl");
  const seconds = Math.floor(value);
  if (seconds < 60 || seconds > MAX_SESSION_TTL_SECONDS) throw new Error("invalid_session_ttl");
  return seconds;
}

function validUserId(value: string): boolean {
  return validOpaqueId(value, 128);
}

function validOpaqueId(value: string, maxLength: number): boolean {
  return value.length > 0 && value.length <= maxLength && value === value.trim() && !/[\u0000-\u001F\u007F]/.test(value);
}

function base64Url(bytes: Uint8Array): string {
  let binary = "";
  for (const byte of bytes) binary += String.fromCharCode(byte);
  return btoa(binary).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/g, "");
}

async function sha256(value: string): Promise<string> {
  const digest = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(value));
  return Array.from(new Uint8Array(digest), (byte) => byte.toString(16).padStart(2, "0")).join("");
}

import { sha256Hex } from "../emailAuth/emailTokens";

export const ADMIN_COOKIE_NAME = "__Host-wristbrief_admin";
const ADMIN_SESSION_TOKEN_PREFIX = "wba_";
const SESSION_TOKEN_BYTES = 32;
const CSRF_TOKEN_BYTES = 32;
const DEFAULT_ADMIN_SESSION_TTL_SECONDS = 12 * 60 * 60;

export type AdminWebSession = {
  sessionId: string;
  userId: string;
  csrfSecretHash: string;
  expiresAt: string;
};

export type IssuedAdminWebSession = {
  token: string;
  csrfToken: string;
  expiresAt: string;
};

/**
 * Browser sessions for the /admin area. Distinct from mobile Bearer sessions:
 * the credential lives in a `__Host-` prefixed Secure/HttpOnly/SameSite=Strict
 * cookie and every mutating request must additionally present the CSRF token
 * whose SHA-256 hash is stored alongside the session.
 */
export class AdminWebSessionService {
  constructor(
    private readonly db: D1Database,
    private readonly ttlSeconds: number = DEFAULT_ADMIN_SESSION_TTL_SECONDS
  ) {}

  async issue(
    userId: string,
    binding: { ipPrefixHash?: string; userAgentHash?: string } = {}
  ): Promise<IssuedAdminWebSession> {
    const token = `${ADMIN_SESSION_TOKEN_PREFIX}${base64Url(crypto.getRandomValues(new Uint8Array(SESSION_TOKEN_BYTES)))}`;
    const csrfToken = base64Url(crypto.getRandomValues(new Uint8Array(CSRF_TOKEN_BYTES)));
    const sessionId = crypto.randomUUID();
    const now = Date.now();
    const createdAt = new Date(now).toISOString();
    const expiresAt = new Date(now + this.ttlSeconds * 1000).toISOString();
    await this.db
      .prepare(
        "INSERT INTO admin_web_sessions (id, user_id, session_hash, csrf_secret_hash, created_at, expires_at, last_seen_at, revoked_at, ip_prefix_hash, user_agent_hash) VALUES (?, ?, ?, ?, ?, ?, ?, NULL, ?, ?)"
      )
      .bind(
        sessionId,
        userId,
        await sha256Hex(token),
        await sha256Hex(csrfToken),
        createdAt,
        expiresAt,
        createdAt,
        binding.ipPrefixHash ?? null,
        binding.userAgentHash ?? null
      )
      .run();
    return { token, csrfToken, expiresAt };
  }

  async authenticateCookie(cookieHeader: string | null): Promise<AdminWebSession | null> {
    const token = parseAdminCookie(cookieHeader);
    if (!token) return null;
    const row = await this.db
      .prepare(
        "SELECT id, user_id, csrf_secret_hash, expires_at, revoked_at FROM admin_web_sessions WHERE session_hash = ? LIMIT 1"
      )
      .bind(await sha256Hex(token))
      .first<{ id: string; user_id: string; csrf_secret_hash: string; expires_at: string; revoked_at: string | null }>();
    if (!row || row.revoked_at) return null;
    const expiresAtMs = Date.parse(row.expires_at);
    if (!Number.isFinite(expiresAtMs) || expiresAtMs <= Date.now()) return null;
    return { sessionId: row.id, userId: row.user_id, csrfSecretHash: row.csrf_secret_hash, expiresAt: row.expires_at };
  }

  async touch(sessionId: string): Promise<void> {
    await this.db
      .prepare("UPDATE admin_web_sessions SET last_seen_at = CURRENT_TIMESTAMP WHERE id = ?")
      .bind(sessionId)
      .run();
  }

  async revokeByCookie(cookieHeader: string | null): Promise<boolean> {
    const token = parseAdminCookie(cookieHeader);
    if (!token) return false;
    const result = await this.db
      .prepare("UPDATE admin_web_sessions SET revoked_at = COALESCE(revoked_at, ?) WHERE session_hash = ?")
      .bind(new Date().toISOString(), await sha256Hex(token))
      .run();
    return (result.meta.changes ?? 0) > 0;
  }

  async revokeForUser(userId: string, revokedAtIso: string): Promise<void> {
    await this.db
      .prepare("UPDATE admin_web_sessions SET revoked_at = COALESCE(revoked_at, ?) WHERE user_id = ?")
      .bind(revokedAtIso, userId)
      .run();
  }
}

function parseAdminCookie(cookieHeader: string | null): string | null {
  if (!cookieHeader) return null;
  for (const part of cookieHeader.split(";")) {
    const [name, ...rest] = part.trim().split("=");
    if (name === ADMIN_COOKIE_NAME) {
      const token = rest.join("=");
      return /^wba_[A-Za-z0-9_-]{43}$/.test(token) ? token : null;
    }
  }
  return null;
}

function base64Url(bytes: Uint8Array): string {
  let binary = "";
  for (const byte of bytes) binary += String.fromCharCode(byte);
  return btoa(binary).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/g, "");
}

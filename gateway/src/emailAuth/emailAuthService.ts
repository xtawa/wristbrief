import { AccountSessionService } from "../accountSession";
import { D1AdminStore } from "../admin/adminStore";
import type { AdminAuditLog } from "../admin/adminAudit";
import { dummyVerifyPassword, hashPassword, verifyPassword, PASSWORD_ALGO } from "./passwordHasher";
import { issueEmailToken, sha256Hex } from "./emailTokens";
import type { EmailCredentialStore, EmailTokenStore } from "./emailCredentialStore";
import type { EmailSender } from "./emailSender";

export type EmailAuthResult = {
  status: number;
  body: Record<string, unknown>;
};

export type EmailAuthServiceDeps = {
  db: D1Database;
  credentialStore: EmailCredentialStore;
  tokenStore: EmailTokenStore;
  sessions: AccountSessionService;
  emailSender: EmailSender;
  adminStore: D1AdminStore;
  audit?: AdminAuditLog;
  now?: () => number;
  newUserId?: () => string;
  newCredentialId?: () => string;
  verificationTtlSeconds?: number;
  resetTtlSeconds?: number;
  publicBaseUrl?: string;
};

const DEFAULT_VERIFICATION_TTL_SECONDS = 24 * 60 * 60;
const DEFAULT_RESET_TTL_SECONDS = 60 * 60;

/**
 * Email/password authentication on top of the existing identity model:
 * `(provider='email', provider_subject=email_credentials.id)` maps to the same
 * immutable internal user_id as Google identities. Email strings are never used
 * as identity subjects and Google/email identities are never auto-merged by
 * matching email addresses.
 *
 * First-admin bootstrap uses a single-statement CAS UPDATE on bootstrap_state
 * (claimFirstAdmin). The winner is created after the CAS succeeds, so losers
 * never leave candidate users behind and exactly one admin can ever be produced
 * by the bootstrap window, no matter how many registrations race.
 */
export class EmailAuthService {
  private readonly now: () => number;
  private readonly newUserId: () => string;
  private readonly newCredentialId: () => string;
  private readonly verificationTtlSeconds: number;
  private readonly resetTtlSeconds: number;

  constructor(private readonly deps: EmailAuthServiceDeps) {
    this.now = deps.now ?? Date.now;
    this.newUserId = deps.newUserId ?? (() => crypto.randomUUID());
    this.newCredentialId = deps.newCredentialId ?? (() => crypto.randomUUID());
    this.verificationTtlSeconds = deps.verificationTtlSeconds ?? DEFAULT_VERIFICATION_TTL_SECONDS;
    this.resetTtlSeconds = deps.resetTtlSeconds ?? DEFAULT_RESET_TTL_SECONDS;
  }

  async register(rawEmail: unknown, rawPassword: unknown): Promise<EmailAuthResult> {
    const email = normalizeEmail(rawEmail);
    if (!email) return result(400, "invalid_email");
    const passwordError = passwordPolicyError(rawPassword, email);
    if (passwordError) return result(400, passwordError);

    if (await this.deps.credentialStore.findByNormalizedEmail(email)) {
      return result(409, "email_already_registered");
    }

    const bootstrap = await this.deps.adminStore.readBootstrapState();
    if (!bootstrap) return result(503, "auth_unavailable");

    if (bootstrap.firstAdminUserId === null) {
      // Bootstrap window: exactly one winner, who becomes the first admin and
      // closes the window. Reopening registration later can never re-enter here.
      if (!bootstrap.webRegistrationEnabled) return result(403, "registration_closed");
      const userId = this.newUserId();
      const claimed = await this.deps.adminStore.claimFirstAdmin(userId, new Date(this.now()).toISOString());
      if (!claimed) return result(403, "registration_closed");
      const created = await this.createAccount(userId, email, rawPassword as string, { role: "admin" });
      if (created.status !== 200) return created;
      return {
        status: 200,
        body: { ...created.body, firstAdmin: true }
      };
    }

    const mode = await this.deps.adminStore.readRegistrationMode();
    if (mode !== "OPEN") return result(403, "registration_closed");
    const created = await this.createAccount(this.newUserId(), email, rawPassword as string, {});
    if (created.status !== 200) return created;
    return {
      status: 200,
      body: { ...created.body, firstAdmin: false }
    };
  }

  async login(rawEmail: unknown, rawPassword: unknown): Promise<EmailAuthResult> {
    const email = normalizeEmail(rawEmail);
    const password = typeof rawPassword === "string" ? rawPassword : "";
    if (!email || !password) return result(401, "invalid_credentials");

    const credential = await this.deps.credentialStore.findByNormalizedEmail(email);
    if (!credential) {
      await dummyVerifyPassword();
      return result(401, "invalid_credentials");
    }
    if (credential.disabledAt) return result(403, "account_disabled");
    if (!(await verifyPassword(password, credential.passwordHash))) {
      return result(401, "invalid_credentials");
    }
    const status = await this.userStatus(credential.userId);
    if (status === null) return result(503, "auth_unavailable");
    if (status !== "active") return result(403, "account_disabled");

    const session = await this.deps.sessions.issue(credential.userId);
    return {
      status: 200,
      body: {
        sessionToken: session.token,
        expiresAt: session.expiresAt,
        user: { id: credential.userId },
        emailVerified: credential.verifiedAt !== null
      }
    };
  }

  async requestVerification(rawEmail: unknown): Promise<EmailAuthResult> {
    const email = normalizeEmail(rawEmail);
    // Uniform 202 regardless of existence to avoid email enumeration.
    if (email) {
      const credential = await this.deps.credentialStore.findByNormalizedEmail(email);
      if (credential && !credential.verifiedAt && !credential.disabledAt) {
        const { token, tokenHash, id } = await issueEmailToken();
        await this.deps.tokenStore.createToken("verification", {
          id,
          credentialId: credential.id,
          tokenHash,
          expiresAt: new Date(this.now() + this.verificationTtlSeconds * 1000).toISOString(),
          consumedAt: null
        });
        await this.deps.emailSender.send({
          to: email,
          subject: "Verify your WristBrief email address",
          text: verificationEmailText(token, this.deps.publicBaseUrl)
        });
      }
    }
    return { status: 202, body: {} };
  }

  async confirmVerification(rawToken: unknown): Promise<EmailAuthResult> {
    const token = parseToken(rawToken);
    if (!token) return result(400, "invalid_or_expired_token");
    const record = await this.deps.tokenStore.findActiveToken("verification", await sha256Hex(token), new Date(this.now()).toISOString());
    if (!record) return result(400, "invalid_or_expired_token");
    if (!(await this.deps.tokenStore.consumeToken("verification", record.id, new Date(this.now()).toISOString()))) {
      return result(400, "invalid_or_expired_token");
    }
    await this.deps.credentialStore.markVerified(record.credentialId, new Date(this.now()).toISOString());
    return { status: 200, body: { verified: true } };
  }

  async forgotPassword(rawEmail: unknown): Promise<EmailAuthResult> {
    const email = normalizeEmail(rawEmail);
    if (email) {
      const credential = await this.deps.credentialStore.findByNormalizedEmail(email);
      if (credential && !credential.disabledAt) {
        // Invalidate outstanding reset tokens; only the newest link works.
        await this.deps.tokenStore.revokeActiveTokens("password_reset", credential.id, new Date(this.now()).toISOString());
        const { token, tokenHash, id } = await issueEmailToken();
        await this.deps.tokenStore.createToken("password_reset", {
          id,
          credentialId: credential.id,
          tokenHash,
          expiresAt: new Date(this.now() + this.resetTtlSeconds * 1000).toISOString(),
          consumedAt: null
        });
        await this.deps.emailSender.send({
          to: email,
          subject: "Reset your WristBrief password",
          text: resetEmailText(token, this.deps.publicBaseUrl)
        });
      }
    }
    // Uniform 202 regardless of existence to avoid email enumeration.
    return { status: 202, body: {} };
  }

  async resetPassword(rawToken: unknown, rawPassword: unknown): Promise<EmailAuthResult> {
    const token = parseToken(rawToken);
    if (!token) return result(400, "invalid_or_expired_token");
    const email = ""; // policy needs the email only for the equality check; resolved below
    const passwordError = passwordPolicyError(rawPassword, email);
    if (passwordError) return result(400, passwordError);

    const record = await this.deps.tokenStore.findActiveToken("password_reset", await sha256Hex(token), new Date(this.now()).toISOString());
    if (!record) return result(400, "invalid_or_expired_token");
    if (!(await this.deps.tokenStore.consumeToken("password_reset", record.id, new Date(this.now()).toISOString()))) {
      return result(400, "invalid_or_expired_token");
    }

    const credential = await this.deps.credentialStore.findByCredentialId(record.credentialId);
    if (!credential) return result(400, "invalid_or_expired_token");
    const password = rawPassword as string;
    if (password.toLowerCase() === credential.normalizedEmail) return result(400, "weak_password");

    await this.deps.credentialStore.updatePasswordHash(credential.id, await hashPassword(password));
    // A completed reset invalidates every existing session (mobile Bearer + admin web).
    await this.deps.adminStore.revokeSessionsForUser(credential.userId, new Date(this.now()).toISOString());
    await this.deps.emailSender.send({
      to: credential.normalizedEmail,
      subject: "Your WristBrief password was changed",
      text: "Your WristBrief password was just changed and all sessions were signed out. If this was not you, reset your password immediately."
    });
    await this.deps.audit?.record({
      actorUserId: credential.userId,
      action: "password_reset_completed",
      targetType: "email_credential",
      targetId: credential.id
    });
    return { status: 200, body: { reset: true } };
  }

  async linkEmailToUser(userId: string, rawEmail: unknown, rawPassword: unknown): Promise<EmailAuthResult> {
    const email = normalizeEmail(rawEmail);
    if (!email) return result(400, "invalid_email");
    const passwordError = passwordPolicyError(rawPassword, email);
    if (passwordError) return result(400, passwordError);
    if (!(await this.deps.adminStore.userIsActive(userId))) return result(403, "account_disabled");

    if (await this.deps.credentialStore.findByNormalizedEmail(email)) {
      return result(409, "email_already_registered");
    }

    const credentialId = this.newCredentialId();
    try {
      await this.deps.db
        .prepare(
          "INSERT INTO identities (provider, provider_subject, user_id, email, created_at, updated_at) VALUES ('email', ?, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)"
        )
        .bind(credentialId, userId, email)
        .run();
      await this.deps.credentialStore.createCredential({
        id: credentialId,
        userId,
        normalizedEmail: email,
        passwordHash: await hashPassword(rawPassword as string),
        passwordAlgo: PASSWORD_ALGO,
        verifiedAt: null,
        disabledAt: null
      });
    } catch (error) {
      if (isUniqueViolation(error)) return result(409, "email_already_registered");
      return result(503, "auth_unavailable");
    }
    await this.deps.audit?.record({ actorUserId: userId, action: "identity_email_linked", targetType: "identity", targetId: credentialId });
    return { status: 200, body: { linked: true } };
  }

  private async createAccount(
    userId: string,
    normalizedEmail: string,
    password: string,
    options: { role?: "admin" }
  ): Promise<EmailAuthResult> {
    const credentialId = this.newCredentialId();
    const passwordHash = await hashPassword(password);
    const statements = [
      this.deps.db
        .prepare("INSERT OR IGNORE INTO users (id, status, created_at, updated_at) VALUES (?, 'active', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)")
        .bind(userId),
      this.deps.db
        .prepare(
          "INSERT INTO identities (provider, provider_subject, user_id, email, created_at, updated_at) VALUES ('email', ?, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)"
        )
        .bind(credentialId, userId, normalizedEmail),
      this.deps.db
        .prepare(
          "INSERT INTO email_credentials (id, user_id, normalized_email, password_hash, password_algo, verified_at, disabled_at, created_at, updated_at) VALUES (?, ?, ?, ?, ?, NULL, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)"
        )
        .bind(credentialId, userId, normalizedEmail, passwordHash, PASSWORD_ALGO)
    ];
    if (options.role === "admin") {
      statements.push(
        this.deps.db
          .prepare("INSERT OR IGNORE INTO user_roles (user_id, role, created_at, created_by_user_id) VALUES (?, 'admin', CURRENT_TIMESTAMP, NULL)")
          .bind(userId)
      );
    }
    try {
      await this.deps.db.batch(statements);
    } catch (error) {
      if (isUniqueViolation(error)) return result(409, "email_already_registered");
      return result(503, "auth_unavailable");
    }
    const statusRow = await this.deps.db.prepare("SELECT status FROM users WHERE id = ?").bind(userId).first<{ status: string }>();
    if (statusRow?.status !== "active") return result(403, "account_disabled");
    const session = await this.deps.sessions.issue(userId);
    return {
      status: 200,
      body: {
        sessionToken: session.token,
        expiresAt: session.expiresAt,
        user: { id: userId }
      }
    };
  }

  private async userStatus(userId: string): Promise<string | null> {
    const row = await this.deps.db
      .prepare("SELECT status FROM users WHERE id = ? LIMIT 1")
      .bind(userId)
      .first<{ status: string }>();
    return row?.status ?? null;
  }
}

function result(status: number, error: string): EmailAuthResult {
  return { status, body: { error } };
}

function isUniqueViolation(error: unknown): boolean {
  const message = error instanceof Error ? error.message : String(error);
  return /UNIQUE constraint/i.test(message);
}

export function normalizeEmail(value: unknown): string | null {
  if (typeof value !== "string") return null;
  const email = value.trim().toLowerCase();
  if (email.length < 3 || email.length > 254) return null;
  // Conservative comparison form: trim + lowercase only. No Gmail dot/+ alias
  // stripping and no provider-specific alias guessing.
  if (!/^[^\s@,;:"<>]+@[^\s@.]+(\.[^\s@.]+)+$/.test(email)) return null;
  return email;
}

const PASSWORD_MIN_LENGTH = 10;
const PASSWORD_MAX_LENGTH = 256;

export function passwordPolicyError(value: unknown, normalizedEmail: string): string | null {
  if (typeof value !== "string") return "weak_password";
  if (value.length < PASSWORD_MIN_LENGTH || value.length > PASSWORD_MAX_LENGTH) return "weak_password";
  if (/[\u0000-\u001F\u007F]/.test(value)) return "weak_password";
  if (!/[a-zA-Z]/.test(value) || !/[0-9]/.test(value)) return "weak_password";
  if (normalizedEmail && value.toLowerCase() === normalizedEmail) return "weak_password";
  return null;
}

function parseToken(value: unknown): string | null {
  if (typeof value !== "string") return null;
  const token = value.trim();
  if (token.length < 20 || token.length > 256) return null;
  if (!/^[A-Za-z0-9_-]+$/.test(token)) return null;
  return token;
}

function verificationEmailText(token: string, publicBaseUrl: string | undefined): string {
  return [
    "Welcome to WristBrief.",
    "",
    "Confirm your email address by opening the link below (valid for 24 hours):",
    `${publicBaseUrl ?? ""}/v1/auth/email/verify/confirm?token=${token}`,
    "",
    "If you did not create this account, you can ignore this email."
  ].join("\n");
}

function resetEmailText(token: string, publicBaseUrl: string | undefined): string {
  return [
    "A password reset was requested for your WristBrief account.",
    "",
    "Open the link below within 60 minutes to choose a new password:",
    `${publicBaseUrl ?? ""}/v1/auth/email/password/reset?token=${token}`,
    "",
    "If you did not request this, ignore this email — your password stays unchanged."
  ].join("\n");
}

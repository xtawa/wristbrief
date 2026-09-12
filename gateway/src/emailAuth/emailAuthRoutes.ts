import { AccountSessionService } from "../accountSession";
import { createConfiguredD1AccountStores } from "../d1AccountStore";
import { D1AdminStore } from "../admin/adminStore";
import { AdminAuditLog } from "../admin/adminAudit";
import { createConfiguredGoogleIdTokenVerifier, type GoogleIdentityEnv } from "../googleIdentity";
import { AUTH_RATE_LIMIT_RULES, createConfiguredRateLimiter, ipPrefixOf, type RateLimiter } from "../rateLimit";
import { D1EmailCredentialStore, D1EmailTokenStore } from "./emailCredentialStore";
import { EmailAuthService, type EmailAuthResult } from "./emailAuthService";
import { createConfiguredEmailSender, type EmailSender, type EmailSenderEnv } from "./emailSender";
import { sha256Hex } from "./emailTokens";

export type EmailAuthEnv = EmailSenderEnv & GoogleIdentityEnv & {
  ACCOUNT_DB?: D1Database;
  ACCOUNT_SESSION_TTL_SECONDS?: string;
  GATEWAY_PUBLIC_BASE_URL?: string;
};

const MAX_EMAIL_BODY_BYTES = 20 * 1024;

export function createConfiguredEmailAuthService(env: EmailAuthEnv): EmailAuthService | undefined {
  if (!env.ACCOUNT_DB) return undefined;
  const db = env.ACCOUNT_DB;
  return new EmailAuthService({
    db,
    credentialStore: new D1EmailCredentialStore(db),
    tokenStore: new D1EmailTokenStore(db),
    sessions: new AccountSessionService(createConfiguredD1AccountStores(env)!.sessionStore, {
      ttlSeconds: parseSessionTtl(env.ACCOUNT_SESSION_TTL_SECONDS)
    }),
    emailSender: createConfiguredEmailSender(env),
    adminStore: new D1AdminStore(db),
    audit: new AdminAuditLog(db),
    publicBaseUrl: env.GATEWAY_PUBLIC_BASE_URL
  });
}

async function rateLimited(
  request: Request,
  env: EmailAuthEnv,
  ruleName: keyof typeof AUTH_RATE_LIMIT_RULES,
  email: unknown
): Promise<boolean> {
  const rule = AUTH_RATE_LIMIT_RULES[ruleName];
  const limiter: RateLimiter = createConfiguredRateLimiter(env, rule);
  const ipKey = `${rule.name}:ip:${ipPrefixOf(request)}`;
  if (!(await limiter.check(ipKey)).allowed) return true;
  if (typeof email === "string" && email.includes("@")) {
    const emailKey = `${rule.name}:email:${await sha256Hex(email.trim().toLowerCase())}`;
    if (!(await limiter.check(emailKey)).allowed) return true;
  }
  return false;
}

function bodyField(value: unknown, field: string): unknown {
  return value && typeof value === "object" ? (value as Record<string, unknown>)[field] : undefined;
}

export async function handleEmailRegister(request: Request, env: EmailAuthEnv, body: unknown): Promise<EmailAuthResult> {
  if (await rateLimited(request, env, "emailRegister", bodyField(body, "email"))) {
    return { status: 429, body: { error: "rate_limited" } };
  }
  const service = createConfiguredEmailAuthService(env);
  if (!service) return { status: 503, body: { error: "auth_not_configured" } };
  return service.register(bodyField(body, "email"), bodyField(body, "password"));
}

export async function handleEmailLogin(request: Request, env: EmailAuthEnv, body: unknown): Promise<EmailAuthResult> {
  if (await rateLimited(request, env, "emailLogin", bodyField(body, "email"))) {
    return { status: 429, body: { error: "rate_limited" } };
  }
  const service = createConfiguredEmailAuthService(env);
  if (!service) return { status: 503, body: { error: "auth_not_configured" } };
  return service.login(bodyField(body, "email"), bodyField(body, "password"));
}

export async function handleEmailVerifyRequest(request: Request, env: EmailAuthEnv, body: unknown): Promise<EmailAuthResult> {
  if (await rateLimited(request, env, "emailVerifyRequest", bodyField(body, "email"))) {
    return { status: 429, body: { error: "rate_limited" } };
  }
  const service = createConfiguredEmailAuthService(env);
  if (!service) return { status: 503, body: { error: "auth_not_configured" } };
  return service.requestVerification(bodyField(body, "email"));
}

export async function handleEmailVerifyConfirm(request: Request, env: EmailAuthEnv, body: unknown, url: URL): Promise<EmailAuthResult> {
  const service = createConfiguredEmailAuthService(env);
  if (!service) return { status: 503, body: { error: "auth_not_configured" } };
  const token = bodyField(body, "token") ?? url.searchParams.get("token");
  return service.confirmVerification(token);
}

export async function handleEmailForgot(request: Request, env: EmailAuthEnv, body: unknown): Promise<EmailAuthResult> {
  if (await rateLimited(request, env, "passwordForgot", bodyField(body, "email"))) {
    return { status: 429, body: { error: "rate_limited" } };
  }
  const service = createConfiguredEmailAuthService(env);
  if (!service) return { status: 503, body: { error: "auth_not_configured" } };
  return service.forgotPassword(bodyField(body, "email"));
}

export async function handleEmailReset(request: Request, env: EmailAuthEnv, body: unknown): Promise<EmailAuthResult> {
  if (await rateLimited(request, env, "passwordReset", bodyField(body, "token"))) {
    return { status: 429, body: { error: "rate_limited" } };
  }
  const service = createConfiguredEmailAuthService(env);
  if (!service) return { status: 503, body: { error: "auth_not_configured" } };
  return service.resetPassword(bodyField(body, "token"), bodyField(body, "password"));
}

export async function handleListIdentities(userId: string, env: EmailAuthEnv): Promise<EmailAuthResult> {
  if (!env.ACCOUNT_DB) return { status: 503, body: { error: "auth_not_configured" } };
  const rows = await env.ACCOUNT_DB
    .prepare("SELECT provider, provider_subject, email, display_name, created_at FROM identities WHERE user_id = ? ORDER BY created_at")
    .bind(userId)
    .all<{ provider: string; provider_subject: string; email: string | null; display_name: string | null; created_at: string }>();
  return {
    status: 200,
    body: {
      identities: (rows.results ?? []).map((row) => ({
        provider: row.provider,
        providerSubject: row.provider_subject,
        email: row.email,
        displayName: row.display_name,
        createdAt: row.created_at
      }))
    }
  };
}

export async function handleLinkEmailIdentity(userId: string, env: EmailAuthEnv, body: unknown): Promise<EmailAuthResult> {
  const service = createConfiguredEmailAuthService(env);
  if (!service) return { status: 503, body: { error: "auth_not_configured" } };
  return service.linkEmailToUser(userId, bodyField(body, "email"), bodyField(body, "password"));
}

export async function handleLinkGoogleIdentity(userId: string, env: EmailAuthEnv, body: unknown): Promise<EmailAuthResult> {
  if (!env.ACCOUNT_DB) return { status: 503, body: { error: "auth_not_configured" } };
  const idToken = bodyField(body, "idToken");
  if (typeof idToken !== "string" || !idToken) return { status: 400, body: { error: "invalid_request" } };
  const verifier = createConfiguredGoogleIdTokenVerifier(env);
  if (!verifier) return { status: 503, body: { error: "auth_not_configured" } };
  const verified = await verifier.verify(idToken);
  if (!verified) return { status: 401, body: { error: "invalid_google_identity" } };

  const db = env.ACCOUNT_DB;
  const existing = await db
    .prepare("SELECT user_id FROM identities WHERE provider = 'google' AND provider_subject = ? LIMIT 1")
    .bind(verified.subject)
    .first<{ user_id: string }>();
  if (existing && existing.user_id !== userId) {
    // Same Google subject already belongs to another account: never auto-merge by email.
    return { status: 409, body: { error: "identity_already_linked" } };
  }
  if (existing) return { status: 200, body: { linked: true, already: true } };

  try {
    await db
      .prepare(
        "INSERT INTO identities (provider, provider_subject, user_id, email, display_name, picture_url, created_at, updated_at) VALUES ('google', ?, ?, ?, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)"
      )
      .bind(verified.subject, userId, verified.email, verified.displayName ?? null, verified.pictureUrl ?? null)
      .run();
  } catch {
    return { status: 409, body: { error: "identity_already_linked" } };
  }
  await new AdminAuditLog(db).record({ actorUserId: userId, action: "identity_google_linked", targetType: "identity", targetId: verified.subject });
  return { status: 200, body: { linked: true } };
}

export async function handleUnlinkIdentity(userId: string, env: EmailAuthEnv, provider: string, providerSubject: string): Promise<EmailAuthResult> {
  if (!env.ACCOUNT_DB) return { status: 503, body: { error: "auth_not_configured" } };
  if (provider !== "google" && provider !== "email") return { status: 400, body: { error: "invalid_request" } };
  if (!providerSubject || providerSubject.length > 255) return { status: 400, body: { error: "invalid_request" } };
  const db = env.ACCOUNT_DB;

  const owned = await db
    .prepare("SELECT COUNT(*) AS total FROM identities WHERE user_id = ?")
    .bind(userId)
    .first<{ total: number }>();
  if ((owned?.total ?? 0) <= 1) {
    return { status: 409, body: { error: "last_login_identity" } };
  }

  const statements = [
    db.prepare("DELETE FROM identities WHERE user_id = ? AND provider = ? AND provider_subject = ?").bind(userId, provider, providerSubject)
  ];
  if (provider === "email") {
    // The credential row is the password login itself; leaving it behind would
    // re-create the identity on the next login.
    statements.push(
      db.prepare("DELETE FROM email_credentials WHERE user_id = ? AND id = ?").bind(userId, providerSubject)
    );
  }
  const results = await db.batch(statements);
  if ((results[0].meta.changes ?? 0) === 0) return { status: 404, body: { error: "not_found" } };
  await new AdminAuditLog(db).record({ actorUserId: userId, action: "identity_unlinked", targetType: "identity", targetId: `${provider}:${providerSubject}` });
  return { status: 200, body: { unlinked: true } };
}

function parseSessionTtl(value: string | undefined): number | undefined {
  if (value === undefined || value.trim() === "") return undefined;
  if (!/^\d+$/.test(value.trim())) throw new Error("invalid_session_ttl");
  return Number(value.trim());
}

/** Path dispatcher for the POST /v1/auth/email/* routes. */
export async function handleEmailAuthRoute(
  pathname: string,
  request: Request,
  env: EmailAuthEnv,
  body: unknown
): Promise<EmailAuthResult> {
  switch (pathname) {
    case "/v1/auth/email/register":
      return handleEmailRegister(request, env, body);
    case "/v1/auth/email/login":
      return handleEmailLogin(request, env, body);
    case "/v1/auth/email/verify/request":
      return handleEmailVerifyRequest(request, env, body);
    case "/v1/auth/email/verify/confirm":
      return handleEmailVerifyConfirm(request, env, body, new URL(request.url));
    case "/v1/auth/email/password/forgot":
      return handleEmailForgot(request, env, body);
    case "/v1/auth/email/password/reset":
      return handleEmailReset(request, env, body);
    default:
      return { status: 404, body: { error: "not_found" } };
  }
}

export { MAX_EMAIL_BODY_BYTES };

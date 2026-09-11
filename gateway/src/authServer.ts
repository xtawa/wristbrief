import {
  AccountIdentityService,
  type AccountIdentityStore
} from "./accountIdentity";
import {
  AccountSessionService,
  type AccountSessionStore
} from "./accountSession";
import {
  createConfiguredD1AccountStores,
  type AccountPersistenceEnv
} from "./d1AccountStore";
import {
  createConfiguredGoogleIdTokenVerifier,
  type GoogleIdTokenVerifier,
  type GoogleIdentityEnv
} from "./googleIdentity";
import {
  createConfiguredMigrationGrantStore,
  LegacyMigrationGrantService,
  migrationGrantTtlSeconds,
  type MigrationGrantEnv
} from "./migrationGrant";

const MAX_GOOGLE_ID_TOKEN_LENGTH = 16_384;
const MAX_MIGRATION_GRANT_LENGTH = 128;

export type AuthServerEnv = GoogleIdentityEnv & AccountPersistenceEnv & MigrationGrantEnv & {
  GOOGLE_ID_TOKEN_VERIFIER?: GoogleIdTokenVerifier;
  ACCOUNT_IDENTITY_STORE?: AccountIdentityStore;
  ACCOUNT_SESSION_STORE?: AccountSessionStore;
  ACCOUNT_SESSION_TTL_SECONDS?: string;
};

export type AuthResult = {
  status: number;
  body: Record<string, unknown>;
};

export type GoogleExchangeOptions = {
  existingUserId?: string;
};

export async function issueLegacyMigrationGrant(userId: string, env: AuthServerEnv): Promise<AuthResult> {
  const store = createConfiguredMigrationGrantStore(env);
  if (!store) return result(503, "auth_not_configured");
  try {
    const service = new LegacyMigrationGrantService(store, migrationGrantTtlSeconds(env.LEGACY_MIGRATION_GRANT_TTL_SECONDS));
    const grant = await service.issue(userId);
    return { status: 200, body: { migrationGrant: grant.token, expiresAt: grant.expiresAt } };
  } catch {
    return result(503, "auth_unavailable");
  }
}

export async function exchangeGoogleIdToken(
  body: unknown,
  env: AuthServerEnv,
  options: GoogleExchangeOptions = {}
): Promise<AuthResult> {
  const idToken = parseIdToken(body);
  if (!idToken) return result(400, "invalid_request");
  const migrationGrant = parseMigrationGrant(body);
  if (migrationGrant === "invalid") return result(400, "invalid_request");
  if (migrationGrant && options.existingUserId) return result(400, "invalid_request");

  const verifier = env.GOOGLE_ID_TOKEN_VERIFIER ?? createConfiguredGoogleIdTokenVerifier(env);
  const configuredD1 = createConfiguredD1AccountStores(env);
  const identityStore = env.ACCOUNT_IDENTITY_STORE ?? configuredD1?.identityStore;
  const sessionStore = env.ACCOUNT_SESSION_STORE ?? configuredD1?.sessionStore;
  if (!verifier || !identityStore || !sessionStore) return result(503, "auth_not_configured");

  const verified = await verifier.verify(idToken);
  if (!verified) return result(401, "invalid_google_identity");

  let existingUserId = options.existingUserId;
  if (migrationGrant) {
    const grantStore = createConfiguredMigrationGrantStore(env);
    if (!grantStore) return result(503, "auth_not_configured");
    try {
      existingUserId = await new LegacyMigrationGrantService(
        grantStore,
        migrationGrantTtlSeconds(env.LEGACY_MIGRATION_GRANT_TTL_SECONDS)
      ).consume(migrationGrant) ?? undefined;
    } catch {
      return result(503, "auth_unavailable");
    }
    if (!existingUserId) return result(401, "invalid_migration_grant");
  }

  try {
    const identities = new AccountIdentityService(identityStore);
    const identity = existingUserId
      ? await identities.linkGoogleToExistingUser(existingUserId, verified)
      : await identities.resolveGoogle(verified);
    const sessions = new AccountSessionService(sessionStore, {
      ttlSeconds: parseSessionTtl(env.ACCOUNT_SESSION_TTL_SECONDS)
    });
    const session = await sessions.issue(identity.userId);
    return {
      status: 200,
      body: {
        sessionToken: session.token,
        expiresAt: session.expiresAt,
        user: { id: identity.userId },
        created: identity.created
      }
    };
  } catch (error) {
    if (error instanceof Error && error.message === "identity_conflict") return result(409, "identity_conflict");
    if (error instanceof Error && error.message === "account_disabled") return result(403, "account_disabled");
    return result(503, "auth_unavailable");
  }
}

function parseIdToken(value: unknown): string | null {
  if (!value || typeof value !== "object") return null;
  const token = (value as Record<string, unknown>).idToken;
  if (typeof token !== "string" || token.length < 1 || token.length > MAX_GOOGLE_ID_TOKEN_LENGTH) return null;
  if (token !== token.trim() || /[\u0000-\u001F\u007F]/.test(token)) return null;
  return token;
}

function parseMigrationGrant(value: unknown): string | null | "invalid" {
  if (!value || typeof value !== "object") return null;
  const grant = (value as Record<string, unknown>).migrationGrant;
  if (grant === undefined) return null;
  if (typeof grant !== "string" || grant.length < 1 || grant.length > MAX_MIGRATION_GRANT_LENGTH) return "invalid";
  if (grant !== grant.trim() || /[\u0000-\u001F\u007F]/.test(grant)) return "invalid";
  return grant;
}

function parseSessionTtl(value: string | undefined): number | undefined {
  if (value === undefined || value.trim() === "") return undefined;
  if (!/^\d+$/.test(value.trim())) throw new Error("invalid_session_ttl");
  return Number(value.trim());
}

function result(status: number, error: string): AuthResult {
  return { status, body: { error } };
}

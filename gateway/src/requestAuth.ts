import type { AuthenticatedUser, MembershipEnv } from "./membership";
import { authenticateActiveGatewayUser } from "./membership";
import { AccountSessionService, parseBearerSessionToken, type AccountSessionStore } from "./accountSession";
import { createConfiguredD1AccountStores, type AccountPersistenceEnv } from "./d1AccountStore";

export type RequestAuthEnv = MembershipEnv & AccountPersistenceEnv & {
  ACCOUNT_SESSION_STORE?: AccountSessionStore;
};

export async function authenticateRequestUser(
  request: Request,
  env: RequestAuthEnv
): Promise<AuthenticatedUser | null> {
  const authorization = request.headers.get("Authorization");
  const presentedSession = parseBearerSessionToken(authorization);

  if (presentedSession) {
    const configuredD1 = createConfiguredD1AccountStores(env);
    const sessionStore = env.ACCOUNT_SESSION_STORE ?? configuredD1?.sessionStore;
    if (!sessionStore) return null;
    try {
      const userId = await new AccountSessionService(sessionStore).authenticateAuthorizationHeader(authorization);
      return userId ? { id: userId } : null;
    } catch {
      return null;
    }
  }

  return authenticateActiveGatewayUser(request, env);
}

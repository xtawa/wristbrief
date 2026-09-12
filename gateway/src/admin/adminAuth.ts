import type { D1AdminStore } from "./adminStore";
import type { AdminWebSessionService, AdminWebSession } from "./adminSession";
import { verifyCsrfTokenAsync, verifySameOrigin, verifySecFetchSite } from "./adminCsrf";

export type AdminAuthOutcome =
  | { ok: true; session: AdminWebSession; userId: string }
  | { ok: false; status: 401 | 403; error: string };

/**
 * Authorization chain for /v1/admin/*: valid admin web session -> internal
 * user_id -> active account -> admin role. Never decides adminship from email,
 * header hints, Play plan, or query strings.
 */
export async function requireAdminUser(
  request: Request,
  adminStore: D1AdminStore,
  sessionService: AdminWebSessionService
): Promise<AdminAuthOutcome> {
  const session = await sessionService.authenticateCookie(request.headers.get("Cookie"));
  if (!session) return { ok: false, status: 401, error: "unauthorized" };
  if (!(await adminStore.userIsActive(session.userId))) {
    return { ok: false, status: 403, error: "account_disabled" };
  }
  const roles = await adminStore.rolesForUser(session.userId);
  if (!roles.includes("admin")) return { ok: false, status: 403, error: "forbidden" };
  await sessionService.touch(session.sessionId);
  return { ok: true, session, userId: session.userId };
}

export async function requireAdminCsrf(
  request: Request,
  session: AdminWebSession
): Promise<boolean> {
  if (!verifySameOrigin(request) || !verifySecFetchSite(request)) return false;
  return verifyCsrfTokenAsync(session.csrfSecretHash, request.headers.get("X-CSRF-Token"));
}

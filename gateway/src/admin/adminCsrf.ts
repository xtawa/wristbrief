import { sha256Hex } from "../emailAuth/emailTokens";

/**
 * CSRF protection for mutating admin requests. SameSite=Strict alone is not
 * trusted: every POST/PATCH/PUT/DELETE must present (1) a valid admin cookie,
 * (2) an `X-CSRF-Token` header whose SHA-256 matches the session's stored
 * `csrf_secret_hash` — or the derived form token for a real form submission —
 * and (3) an Origin that is absent, opaque, or same-origin with the request URL,
 * plus a `Sec-Fetch-Site` that is not `cross-site`.
 *
 * There is exactly ONE origin policy in the admin console
 * (`verifySameOrigin`, mirrored by the route-level early check so an oversized
 * or hostile body is refused before it is read):
 *   - absent            -> "no usable origin": fall through to the CSRF token;
 *   - `null` (opaque)   -> same;
 *   - present, same host-> accepted;
 *   - present, different host or unparseable -> refused.
 * The Origin header is not a secret a determined client cannot forge, so it is
 * never the only gate; the CSRF token is. Treating an absent header as hostile
 * only breaks legitimate same-origin clients (Chrome omits Origin on some
 * same-origin requests) while adding no protection the token does not give.
 */

/**
 * The serialized opaque origin.
 *
 * A browser sends `Origin: null` for a form submission from a document whose
 * referrer policy is `no-referrer` (and from sandboxed/file/data documents). It
 * means "this document has no usable origin", not "this is some other site", so
 * it is treated exactly like an absent header and the request is judged on its
 * CSRF token and `Sec-Fetch-Site` instead. A *present and different* origin
 * (`https://evil.example`) is still refused.
 */
export const OPAQUE_ORIGIN = "null";

export function isOpaqueOrigin(origin: string | null): boolean {
  return origin === OPAQUE_ORIGIN;
}

export async function verifyCsrfTokenAsync(csrfSecretHash: string, headerToken: string | null): Promise<boolean> {
  if (!headerToken || headerToken.length > 256) return false;
  const hash = await sha256Hex(headerToken);
  return hash.length === csrfSecretHash.length && timingSafeEqual(hash, csrfSecretHash);
}

/**
 * The single origin policy for the admin console.
 *
 * An absent header and the opaque `null` value both mean "no usable origin" and
 * are accepted, leaving the CSRF token as the gate; a present header must match
 * the request's host. This is deliberately identical to the route-level early
 * check in adminRoutes, so the form path and the JSON path cannot disagree.
 */
export function verifySameOrigin(request: Request): boolean {
  const origin = request.headers.get("Origin");
  if (origin === null || isOpaqueOrigin(origin)) return true;
  let originHost: string;
  try {
    originHost = new URL(origin).host;
  } catch {
    return false;
  }
  return originHost === new URL(request.url).host;
}

export function verifySecFetchSite(request: Request): boolean {
  // Absent header (non-browser or older client) is allowed; a browser-sent
  // cross-site value is rejected.
  const site = request.headers.get("Sec-Fetch-Site");
  return !site || site === "same-origin" || site === "same-site" || site === "none";
}

export function readCookie(request: Request, name: string): string | null {
  const header = request.headers.get("Cookie");
  if (!header) return null;
  for (const part of header.split(";")) {
    const [key, ...rest] = part.trim().split("=");
    if (key === name) {
      const value = rest.join("=");
      return value || null;
    }
  }
  return null;
}

/** Random, URL-safe, cookie-safe token for the anonymous login form. */
export function createLoginCsrfToken(): string {
  return base64Url(crypto.getRandomValues(new Uint8Array(32)));
}

export const LOGIN_CSRF_COOKIE = "wristbrief_admin_login_csrf";

/**
 * Anti-CSRF value for the no-JavaScript form path.
 *
 * The page scripts use double submit: the raw CSRF token lives in a
 * non-HttpOnly cookie and is echoed back in the `X-CSRF-Token` header, where it
 * is compared against `admin_web_sessions.csrf_secret_hash`. A plain HTML form
 * cannot set a header, and the raw CSRF token must never be written into a page
 * (a page would then leak it through the response body, caches or logs), so a
 * form carries this derived value instead:
 *
 *   sha256("admin-form-token:v1:" + sessionId + ":" + csrfSecretHash)
 *
 * It is bound to the session, useless as an `X-CSRF-Token`, and only rendered
 * inside pages that already required the admin cookie. Forgery is still blocked
 * by SameSite=Strict on the session cookie plus the Origin / Sec-Fetch-Site
 * checks, which apply to both paths.
 */
export async function deriveAdminFormToken(session: { sessionId: string; csrfSecretHash: string }): Promise<string> {
  return sha256Hex(`admin-form-token:v1:${session.sessionId}:${session.csrfSecretHash}`);
}

export async function verifyAdminFormToken(
  session: { sessionId: string; csrfSecretHash: string },
  token: string | null | undefined
): Promise<boolean> {
  if (!token || token.length > 256) return false;
  const expected = await deriveAdminFormToken(session);
  return timingSafeEqual(expected, token.trim().toLowerCase());
}

function base64Url(bytes: Uint8Array): string {
  let binary = "";
  for (const byte of bytes) binary += String.fromCharCode(byte);
  return btoa(binary).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/g, "");
}

function timingSafeEqual(a: string, b: string): boolean {
  let diff = 0;
  if (a.length !== b.length) return false;
  for (let i = 0; i < a.length; i++) diff |= a.charCodeAt(i) ^ b.charCodeAt(i);
  return diff === 0;
}

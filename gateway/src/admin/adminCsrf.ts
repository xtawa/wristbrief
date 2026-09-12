import { sha256Hex } from "../emailAuth/emailTokens";

/**
 * CSRF protection for mutating admin requests. SameSite=Strict alone is not
 * trusted: every POST/PATCH/PUT/DELETE must present (1) a valid admin cookie,
 * (2) an X-CSRF-Token header whose SHA-256 matches the session's stored
 * csrf_secret_hash, and (3) an Origin (and, when sent, Sec-Fetch-Site) that is
 * same-origin with the request URL.
 */
export async function verifyCsrfTokenAsync(csrfSecretHash: string, headerToken: string | null): Promise<boolean> {
  if (!headerToken || headerToken.length > 256) return false;
  const hash = await sha256Hex(headerToken);
  return hash.length === csrfSecretHash.length && timingSafeEqual(hash, csrfSecretHash);
}

export function verifySameOrigin(request: Request): boolean {
  const origin = request.headers.get("Origin");
  if (!origin) return false;
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

function timingSafeEqual(a: string, b: string): boolean {
  let diff = 0;
  for (let i = 0; i < a.length; i++) diff |= a.charCodeAt(i) ^ b.charCodeAt(i);
  return diff === 0;
}

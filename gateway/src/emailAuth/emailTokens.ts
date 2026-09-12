const TOKEN_BYTES = 32;

export type EmailTokenType = "verification" | "password_reset";

export type IssuedEmailToken = {
  token: string;
  tokenHash: string;
  id: string;
};

export async function sha256Hex(value: string): Promise<string> {
  const digest = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(value));
  return Array.from(new Uint8Array(digest), (byte) => byte.toString(16).padStart(2, "0")).join("");
}

/**
 * One-time opaque tokens (verification links, password reset links). The raw token
 * only ever appears in the outbound email URL; D1 stores the SHA-256 hash. In
 * production the raw token is never returned to any client.
 */
export async function issueEmailToken(newId: () => string = () => crypto.randomUUID()): Promise<IssuedEmailToken> {
  const bytes = crypto.getRandomValues(new Uint8Array(TOKEN_BYTES));
  let binary = "";
  for (const byte of bytes) binary += String.fromCharCode(byte);
  const token = btoa(binary).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/g, "");
  return { token, tokenHash: await sha256Hex(token), id: newId() };
}

export function constantTimeEqualsHex(a: string, b: string): boolean {
  if (a.length !== b.length) return false;
  let diff = 0;
  for (let i = 0; i < a.length; i++) diff |= a.charCodeAt(i) ^ b.charCodeAt(i);
  return diff === 0;
}

const GOOGLE_OIDC_JWKS_URL = "https://www.googleapis.com/oauth2/v3/certs";
const GOOGLE_ISSUERS = new Set(["accounts.google.com", "https://accounts.google.com"]);
const DEFAULT_JWKS_TTL_MS = 5 * 60 * 1000;
const MAX_JWKS_TTL_MS = 60 * 60 * 1000;
const CLOCK_SKEW_SECONDS = 300;
const MAX_ID_TOKEN_LENGTH = 16_384;

export type GoogleIdentityEnv = {
  GOOGLE_OAUTH_CLIENT_ID?: string;
};

export type VerifiedGoogleIdentity = {
  subject: string;
  email: string;
  emailVerified: true;
  displayName?: string;
  pictureUrl?: string;
};

export interface GoogleIdTokenVerifier {
  verify(idToken: string, expectedNonce?: string): Promise<VerifiedGoogleIdentity | null>;
}

type JsonWebKeyWithKid = JsonWebKey & {
  kid?: string;
  alg?: string;
  use?: string;
};

type GoogleIdTokenHeader = {
  alg?: string;
  kid?: string;
};

type GoogleIdTokenClaims = {
  iss?: string;
  sub?: string;
  aud?: string;
  iat?: number;
  nbf?: number;
  exp?: number;
  email?: string;
  email_verified?: boolean;
  name?: string;
  picture?: string;
  nonce?: string;
};

type GoogleJwksResponse = {
  keys?: JsonWebKeyWithKid[];
};

type GoogleOidcIdTokenVerifierOptions = {
  fetchImpl?: typeof fetch;
  now?: () => number;
};

export class GoogleOidcIdTokenVerifier implements GoogleIdTokenVerifier {
  private readonly fetchImpl: typeof fetch;
  private readonly now: () => number;
  private cachedKeys: JsonWebKeyWithKid[] = [];
  private keysExpireAt = 0;

  constructor(
    private readonly expectedAudience: string,
    options: GoogleOidcIdTokenVerifierOptions = {}
  ) {
    this.fetchImpl = options.fetchImpl ?? fetch;
    this.now = options.now ?? Date.now;
  }

  async verify(idToken: string, expectedNonce?: string): Promise<VerifiedGoogleIdentity | null> {
    try {
      if (!isCompactJwt(idToken)) return null;
      const parsed = parseJwt(idToken);
      if (!parsed || parsed.header.alg !== "RS256" || !parsed.header.kid) return null;

      let key = await this.keyFor(parsed.header.kid, false);
      if (!key) key = await this.keyFor(parsed.header.kid, true);
      if (!key) return null;

      const cryptoKey = await crypto.subtle.importKey(
        "jwk",
        key,
        { name: "RSASSA-PKCS1-v1_5", hash: "SHA-256" },
        false,
        ["verify"]
      );
      const signatureValid = await crypto.subtle.verify(
        "RSASSA-PKCS1-v1_5",
        cryptoKey,
        parsed.signature,
        new TextEncoder().encode(parsed.signingInput)
      );
      if (!signatureValid) return null;

      return this.validIdentity(parsed.claims, expectedNonce);
    } catch {
      return null;
    }
  }

  private validIdentity(claims: GoogleIdTokenClaims, expectedNonce?: string): VerifiedGoogleIdentity | null {
    const nowSeconds = Math.floor(this.now() / 1000);
    if (!claims.iss || !GOOGLE_ISSUERS.has(claims.iss)) return null;
    if (claims.aud !== this.expectedAudience) return null;
    if (typeof claims.iat !== "number" || claims.iat > nowSeconds + CLOCK_SKEW_SECONDS) return null;
    if (typeof claims.nbf === "number" && claims.nbf > nowSeconds + CLOCK_SKEW_SECONDS) return null;
    if (typeof claims.exp !== "number" || claims.exp <= nowSeconds - CLOCK_SKEW_SECONDS || claims.exp <= claims.iat) return null;
    if (expectedNonce !== undefined && claims.nonce !== expectedNonce) return null;

    const subject = exactBoundedClaim(claims.sub, 255);
    const email = exactBoundedClaim(claims.email, 320);
    if (!subject || !email || claims.email_verified !== true) return null;

    const displayName = optionalBoundedClaim(claims.name, 256);
    const pictureUrl = safeHttpsUrl(claims.picture);
    return {
      subject,
      email,
      emailVerified: true,
      ...(displayName ? { displayName } : {}),
      ...(pictureUrl ? { pictureUrl } : {})
    };
  }

  private async keyFor(kid: string, forceRefresh: boolean): Promise<JsonWebKeyWithKid | null> {
    if (!forceRefresh && this.cachedKeys.length > 0 && this.now() < this.keysExpireAt) {
      return this.cachedKeys.find((key) => key.kid === kid && key.kty === "RSA") ?? null;
    }

    const response = await this.fetchImpl(GOOGLE_OIDC_JWKS_URL, {
      method: "GET",
      headers: { Accept: "application/json" },
      redirect: "error"
    });
    if (!response.ok) return null;

    const payload = await response.json() as GoogleJwksResponse;
    const keys = Array.isArray(payload.keys)
      ? payload.keys.filter((key) => key && key.kty === "RSA" && (!key.alg || key.alg === "RS256") && (!key.use || key.use === "sig"))
      : [];
    if (keys.length === 0) return null;

    this.cachedKeys = keys;
    this.keysExpireAt = this.now() + cacheTtlMs(response.headers.get("Cache-Control"));
    return keys.find((key) => key.kid === kid) ?? null;
  }
}

export function createConfiguredGoogleIdTokenVerifier(
  env: GoogleIdentityEnv
): GoogleIdTokenVerifier | undefined {
  const clientId = env.GOOGLE_OAUTH_CLIENT_ID?.trim();
  if (!clientId || !isGoogleOAuthClientId(clientId)) return undefined;
  return new GoogleOidcIdTokenVerifier(clientId);
}

function isCompactJwt(value: string): boolean {
  return value.length > 0 &&
    value.length <= MAX_ID_TOKEN_LENGTH &&
    /^[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+$/.test(value);
}

function parseJwt(token: string): {
  header: GoogleIdTokenHeader;
  claims: GoogleIdTokenClaims;
  signingInput: string;
  signature: ArrayBuffer;
} | null {
  const parts = token.split(".");
  if (parts.length !== 3) return null;
  try {
    const header = JSON.parse(decodeBase64UrlUtf8(parts[0])) as GoogleIdTokenHeader;
    const claims = JSON.parse(decodeBase64UrlUtf8(parts[1])) as GoogleIdTokenClaims;
    const signature = decodeBase64UrlBytes(parts[2]).buffer as ArrayBuffer;
    return { header, claims, signingInput: `${parts[0]}.${parts[1]}`, signature };
  } catch {
    return null;
  }
}

function exactBoundedClaim(value: unknown, maxLength: number): string | null {
  if (typeof value !== "string" || value.length < 1 || value.length > maxLength) return null;
  if (value !== value.trim() || /[\u0000-\u001F\u007F]/.test(value)) return null;
  return value;
}

function optionalBoundedClaim(value: unknown, maxLength: number): string | undefined {
  if (typeof value !== "string") return undefined;
  const trimmed = value.trim();
  if (!trimmed || trimmed.length > maxLength || /[\u0000-\u001F\u007F]/.test(trimmed)) return undefined;
  return trimmed;
}

function safeHttpsUrl(value: unknown): string | undefined {
  if (typeof value !== "string" || value.length > 2048) return undefined;
  try {
    const parsed = new URL(value);
    return parsed.protocol === "https:" ? parsed.toString() : undefined;
  } catch {
    return undefined;
  }
}

function isGoogleOAuthClientId(value: string): boolean {
  return value.length <= 320 &&
    !/[\u0000-\u001F\u007F]/.test(value) &&
    value.endsWith(".apps.googleusercontent.com");
}

function decodeBase64UrlUtf8(value: string): string {
  return new TextDecoder().decode(decodeBase64UrlBytes(value));
}

function decodeBase64UrlBytes(value: string): Uint8Array {
  const normalized = value.replace(/-/g, "+").replace(/_/g, "/");
  const padding = "=".repeat((4 - (normalized.length % 4)) % 4);
  const binary = atob(normalized + padding);
  return Uint8Array.from(binary, (char) => char.charCodeAt(0));
}

function cacheTtlMs(cacheControl: string | null): number {
  const match = /(?:^|,)\s*max-age=(\d+)/i.exec(cacheControl ?? "");
  if (!match) return DEFAULT_JWKS_TTL_MS;
  const seconds = Number(match[1]);
  if (!Number.isFinite(seconds) || seconds <= 0) return DEFAULT_JWKS_TTL_MS;
  return Math.min(seconds * 1000, MAX_JWKS_TTL_MS);
}

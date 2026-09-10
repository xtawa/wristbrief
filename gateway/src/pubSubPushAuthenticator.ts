import type { PubSubPushAuthenticator } from "./billingServer";

const GOOGLE_OIDC_JWKS_URL = "https://www.googleapis.com/oauth2/v3/certs";
const GOOGLE_ISSUERS = new Set(["accounts.google.com", "https://accounts.google.com"]);
const DEFAULT_JWKS_TTL_MS = 5 * 60 * 1000;
const MAX_JWKS_TTL_MS = 60 * 60 * 1000;
const CLOCK_SKEW_SECONDS = 300;

export type PubSubPushAuthEnv = {
  PUBSUB_PUSH_SERVICE_ACCOUNT_EMAIL?: string;
  PUBSUB_PUSH_AUDIENCE?: string;
};

type JsonWebKeyWithKid = JsonWebKey & {
  kid?: string;
  alg?: string;
  use?: string;
};

type JwksResponse = {
  keys?: JsonWebKeyWithKid[];
};

type JwtHeader = {
  alg?: string;
  kid?: string;
};

type JwtClaims = {
  iss?: string;
  sub?: string;
  aud?: string | string[];
  iat?: number;
  exp?: number;
  email?: string;
  email_verified?: boolean;
};

type GooglePubSubPushAuthenticatorOptions = {
  fetchImpl?: typeof fetch;
  now?: () => number;
};

export class GooglePubSubPushAuthenticator implements PubSubPushAuthenticator {
  private readonly fetchImpl: typeof fetch;
  private readonly now: () => number;
  private cachedKeys: JsonWebKeyWithKid[] = [];
  private keysExpireAt = 0;

  constructor(
    private readonly expectedServiceAccountEmail: string,
    private readonly expectedAudience: string,
    options: GooglePubSubPushAuthenticatorOptions = {}
  ) {
    this.fetchImpl = options.fetchImpl ?? fetch;
    this.now = options.now ?? Date.now;
  }

  async verify(request: Request): Promise<boolean> {
    try {
      const token = bearerToken(request.headers.get("Authorization"));
      if (!token) return false;
      const parsed = parseJwt(token);
      if (!parsed || parsed.header.alg !== "RS256" || !parsed.header.kid) return false;

      let key = await this.keyFor(parsed.header.kid, false);
      if (!key) key = await this.keyFor(parsed.header.kid, true);
      if (!key) return false;

      const cryptoKey = await crypto.subtle.importKey(
        "jwk",
        key,
        { name: "RSASSA-PKCS1-v1_5", hash: "SHA-256" },
        false,
        ["verify"]
      );
      const validSignature = await crypto.subtle.verify(
        "RSASSA-PKCS1-v1_5",
        cryptoKey,
        parsed.signature,
        new TextEncoder().encode(parsed.signingInput)
      );
      if (!validSignature) return false;

      return this.validClaims(parsed.claims);
    } catch {
      return false;
    }
  }

  private validClaims(claims: JwtClaims): boolean {
    const nowSeconds = Math.floor(this.now() / 1000);
    const audienceMatches = typeof claims.aud === "string"
      ? claims.aud === this.expectedAudience
      : Array.isArray(claims.aud) && claims.aud.length === 1 && claims.aud[0] === this.expectedAudience;

    return Boolean(
      claims.iss &&
      GOOGLE_ISSUERS.has(claims.iss) &&
      claims.sub &&
      audienceMatches &&
      typeof claims.iat === "number" &&
      claims.iat <= nowSeconds + CLOCK_SKEW_SECONDS &&
      typeof claims.exp === "number" &&
      claims.exp > nowSeconds - CLOCK_SKEW_SECONDS &&
      claims.exp > claims.iat &&
      claims.email === this.expectedServiceAccountEmail &&
      claims.email_verified === true
    );
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

    const payload = await response.json() as JwksResponse;
    const keys = Array.isArray(payload.keys)
      ? payload.keys.filter((key) => key && key.kty === "RSA" && (!key.alg || key.alg === "RS256") && (!key.use || key.use === "sig"))
      : [];
    if (keys.length === 0) return null;

    this.cachedKeys = keys;
    this.keysExpireAt = this.now() + cacheTtlMs(response.headers.get("Cache-Control"));
    return keys.find((key) => key.kid === kid) ?? null;
  }
}

export function createConfiguredPubSubPushAuthenticator(
  env: PubSubPushAuthEnv
): PubSubPushAuthenticator | undefined {
  const email = env.PUBSUB_PUSH_SERVICE_ACCOUNT_EMAIL?.trim();
  const audience = env.PUBSUB_PUSH_AUDIENCE?.trim();
  if (!email || !audience || !isHttpsUrl(audience)) return undefined;
  return new GooglePubSubPushAuthenticator(email, audience);
}

function bearerToken(header: string | null): string | null {
  if (!header || header.length > 16384) return null;
  const match = /^Bearer ([A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+)$/.exec(header);
  return match?.[1] ?? null;
}

function parseJwt(token: string): {
  header: JwtHeader;
  claims: JwtClaims;
  signingInput: string;
  signature: ArrayBuffer;
} | null {
  const parts = token.split(".");
  if (parts.length !== 3) return null;
  try {
    const header = JSON.parse(decodeBase64UrlUtf8(parts[0])) as JwtHeader;
    const claims = JSON.parse(decodeBase64UrlUtf8(parts[1])) as JwtClaims;
    const signature = decodeBase64UrlBytes(parts[2]).buffer as ArrayBuffer;
    return {
      header,
      claims,
      signingInput: `${parts[0]}.${parts[1]}`,
      signature
    };
  } catch {
    return null;
  }
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

function isHttpsUrl(value: string): boolean {
  try {
    return new URL(value).protocol === "https:";
  } catch {
    return false;
  }
}

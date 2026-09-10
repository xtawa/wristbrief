import type { GooglePlayPurchaseVerifier, PlaySubscriptionStatus, VerifiedPlaySubscription } from "./billingServer";

const ANDROID_PUBLISHER_SCOPE = "https://www.googleapis.com/auth/androidpublisher";
const GOOGLE_TOKEN_ENDPOINT = "https://oauth2.googleapis.com/token";
const ANDROID_PUBLISHER_ORIGIN = "https://androidpublisher.googleapis.com";
const TOKEN_TTL_SECONDS = 3600;
const TOKEN_REFRESH_SKEW_SECONDS = 60;

export type GooglePlayVerifierEnv = {
  GOOGLE_PLAY_SERVICE_ACCOUNT_EMAIL?: string;
  GOOGLE_PLAY_SERVICE_ACCOUNT_PRIVATE_KEY?: string;
};

export interface GoogleAccessTokenProvider {
  getAccessToken(): Promise<string>;
}

export class GooglePlayVerificationError extends Error {
  constructor(
    readonly code:
      | "google_auth_not_configured"
      | "google_auth_failed"
      | "play_api_failed"
      | "invalid_play_response"
      | "ambiguous_play_product",
    readonly upstreamStatus?: number
  ) {
    super(code);
    this.name = "GooglePlayVerificationError";
  }
}

type FetchLike = typeof fetch;

type SubscriptionPurchaseV2 = {
  subscriptionState?: string;
  lineItems?: Array<{
    productId?: string;
    expiryTime?: string;
  }>;
};

export class GoogleAndroidPublisherPurchaseVerifier implements GooglePlayPurchaseVerifier {
  constructor(
    private readonly accessTokens: GoogleAccessTokenProvider,
    private readonly fetchImpl: FetchLike = fetch
  ) {}

  async verifySubscription(input: { packageName: string; purchaseToken: string }): Promise<VerifiedPlaySubscription> {
    const packageName = input.packageName.trim();
    const purchaseToken = input.purchaseToken.trim();
    if (!packageName || !purchaseToken) throw new GooglePlayVerificationError("invalid_play_response");

    const accessToken = await this.accessTokens.getAccessToken();
    const endpoint = `${ANDROID_PUBLISHER_ORIGIN}/androidpublisher/v3/applications/${encodeURIComponent(packageName)}/purchases/subscriptionsv2/tokens/${encodeURIComponent(purchaseToken)}`;
    let response: Response;
    try {
      response = await this.fetchImpl(endpoint, {
        method: "GET",
        headers: { Accept: "application/json", Authorization: `Bearer ${accessToken}` },
        redirect: "error"
      });
    } catch {
      throw new GooglePlayVerificationError("play_api_failed");
    }
    if (!response.ok) throw new GooglePlayVerificationError("play_api_failed", response.status);

    let payload: SubscriptionPurchaseV2;
    try {
      payload = await response.json() as SubscriptionPurchaseV2;
    } catch {
      throw new GooglePlayVerificationError("invalid_play_response");
    }

    const status = mapSubscriptionState(payload.subscriptionState);
    const product = parseSingleProduct(payload.lineItems);
    // Any Play state that can grant Pro must carry a valid server-supplied expiry. Otherwise a
    // malformed/stale response could create a non-expiring billing entitlement.
    if (grantsSubscriptionAccess(status) && !product.expiresAt) {
      throw new GooglePlayVerificationError("invalid_play_response");
    }
    return {
      packageName,
      productId: product.productId,
      status,
      expiresAt: product.expiresAt
    };
  }
}

export class GoogleServiceAccountAccessTokenProvider implements GoogleAccessTokenProvider {
  private cached?: { token: string; expiresAt: number };

  constructor(
    private readonly clientEmail: string,
    private readonly privateKeyPem: string,
    private readonly fetchImpl: FetchLike = fetch,
    private readonly now: () => number = () => Date.now()
  ) {}

  async getAccessToken(): Promise<string> {
    const nowSeconds = Math.floor(this.now() / 1000);
    if (this.cached && this.cached.expiresAt - TOKEN_REFRESH_SKEW_SECONDS > nowSeconds) return this.cached.token;

    const assertion = await this.createAssertion(nowSeconds);
    let response: Response;
    try {
      response = await this.fetchImpl(GOOGLE_TOKEN_ENDPOINT, {
        method: "POST",
        headers: { "Content-Type": "application/x-www-form-urlencoded" },
        body: new URLSearchParams({
          grant_type: "urn:ietf:params:oauth:grant-type:jwt-bearer",
          assertion
        }).toString(),
        redirect: "error"
      });
    } catch {
      throw new GooglePlayVerificationError("google_auth_failed");
    }
    if (!response.ok) throw new GooglePlayVerificationError("google_auth_failed", response.status);

    let payload: { access_token?: unknown; expires_in?: unknown };
    try {
      payload = await response.json() as { access_token?: unknown; expires_in?: unknown };
    } catch {
      throw new GooglePlayVerificationError("google_auth_failed");
    }
    const token = typeof payload.access_token === "string" ? payload.access_token.trim() : "";
    const expiresIn = typeof payload.expires_in === "number" && Number.isFinite(payload.expires_in)
      ? Math.max(1, Math.floor(payload.expires_in))
      : TOKEN_TTL_SECONDS;
    if (!token) throw new GooglePlayVerificationError("google_auth_failed");
    this.cached = { token, expiresAt: nowSeconds + expiresIn };
    return token;
  }

  private async createAssertion(nowSeconds: number): Promise<string> {
    const email = this.clientEmail.trim();
    const privateKey = this.privateKeyPem.trim();
    if (!email || !privateKey) throw new GooglePlayVerificationError("google_auth_not_configured");

    const header = base64UrlJson({ alg: "RS256", typ: "JWT" });
    const claims = base64UrlJson({
      iss: email,
      scope: ANDROID_PUBLISHER_SCOPE,
      aud: GOOGLE_TOKEN_ENDPOINT,
      iat: nowSeconds,
      exp: nowSeconds + TOKEN_TTL_SECONDS
    });
    const unsigned = `${header}.${claims}`;

    let key: CryptoKey;
    try {
      key = await crypto.subtle.importKey(
        "pkcs8",
        pemToArrayBuffer(privateKey),
        { name: "RSASSA-PKCS1-v1_5", hash: "SHA-256" },
        false,
        ["sign"]
      );
    } catch {
      throw new GooglePlayVerificationError("google_auth_failed");
    }

    let signature: ArrayBuffer;
    try {
      signature = await crypto.subtle.sign("RSASSA-PKCS1-v1_5", key, new TextEncoder().encode(unsigned));
    } catch {
      throw new GooglePlayVerificationError("google_auth_failed");
    }
    return `${unsigned}.${base64UrlBytes(new Uint8Array(signature))}`;
  }
}

export function createConfiguredGooglePlayPurchaseVerifier(
  env: GooglePlayVerifierEnv,
  fetchImpl: FetchLike = fetch
): GooglePlayPurchaseVerifier | undefined {
  const email = env.GOOGLE_PLAY_SERVICE_ACCOUNT_EMAIL?.trim();
  const privateKey = env.GOOGLE_PLAY_SERVICE_ACCOUNT_PRIVATE_KEY?.trim();
  if (!email || !privateKey) return undefined;
  return new GoogleAndroidPublisherPurchaseVerifier(
    new GoogleServiceAccountAccessTokenProvider(email, privateKey, fetchImpl),
    fetchImpl
  );
}

function parseSingleProduct(lineItems: SubscriptionPurchaseV2["lineItems"]): { productId: string; expiresAt?: string } {
  if (!Array.isArray(lineItems) || lineItems.length === 0) throw new GooglePlayVerificationError("invalid_play_response");
  const valid = lineItems.flatMap((item) => {
    const productId = typeof item?.productId === "string" ? item.productId.trim() : "";
    if (!productId) return [];
    const expiresAt = typeof item.expiryTime === "string" && Number.isFinite(Date.parse(item.expiryTime))
      ? new Date(item.expiryTime).toISOString()
      : undefined;
    return [{ productId, expiresAt }];
  });
  if (!valid.length) throw new GooglePlayVerificationError("invalid_play_response");
  if (new Set(valid.map((item) => item.productId)).size !== 1) throw new GooglePlayVerificationError("ambiguous_play_product");
  return valid.reduce((best, item) => {
    if (!best.expiresAt) return item.expiresAt ? item : best;
    if (!item.expiresAt) return best;
    return item.expiresAt > best.expiresAt ? item : best;
  });
}

function mapSubscriptionState(value: string | undefined): PlaySubscriptionStatus {
  switch (value) {
    case "SUBSCRIPTION_STATE_ACTIVE": return "active";
    case "SUBSCRIPTION_STATE_IN_GRACE_PERIOD": return "grace";
    case "SUBSCRIPTION_STATE_ON_HOLD": return "on_hold";
    case "SUBSCRIPTION_STATE_CANCELED": return "canceled";
    case "SUBSCRIPTION_STATE_EXPIRED": return "expired";
    case "SUBSCRIPTION_STATE_PENDING":
    case "SUBSCRIPTION_STATE_PAUSED":
    case "SUBSCRIPTION_STATE_PENDING_PURCHASE_CANCELED": return "revoked";
    default: throw new GooglePlayVerificationError("invalid_play_response");
  }
}

function grantsSubscriptionAccess(status: PlaySubscriptionStatus): boolean {
  return status === "active" || status === "grace" || status === "canceled";
}

function pemToArrayBuffer(pem: string): ArrayBuffer {
  const normalized = pem.replace(/\\n/g, "\n");
  const base64 = normalized.replace(/-----BEGIN PRIVATE KEY-----/g, "").replace(/-----END PRIVATE KEY-----/g, "").replace(/\s+/g, "");
  if (!base64) throw new GooglePlayVerificationError("google_auth_failed");
  let binary: string;
  try { binary = atob(base64); } catch { throw new GooglePlayVerificationError("google_auth_failed"); }
  return Uint8Array.from(binary, (char) => char.charCodeAt(0)).buffer;
}

function base64UrlJson(value: unknown): string {
  return base64UrlBytes(new TextEncoder().encode(JSON.stringify(value)));
}

function base64UrlBytes(bytes: Uint8Array): string {
  let binary = "";
  for (const byte of bytes) binary += String.fromCharCode(byte);
  return btoa(binary).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/g, "");
}

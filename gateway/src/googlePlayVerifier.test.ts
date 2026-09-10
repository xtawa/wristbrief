import { describe, expect, it } from "vitest";
import {
  GoogleAndroidPublisherPurchaseVerifier,
  GooglePlayVerificationError,
  GoogleServiceAccountAccessTokenProvider,
  createConfiguredGooglePlayPurchaseVerifier
} from "./googlePlayVerifier";

const packageName = "ink.underflo.wristbrief";
const purchaseToken = "secret/token+value";
const accessTokens = { async getAccessToken() { return "oauth-access-token"; } };

function playResponse(state = "SUBSCRIPTION_STATE_ACTIVE", productId = "wristbrief_pro") {
  return new Response(JSON.stringify({ subscriptionState: state, lineItems: [{ productId, expiryTime: "2026-10-01T00:00:00Z" }] }), { status: 200 });
}

function asFetch(fake: (...args: unknown[]) => Promise<Response>): typeof fetch {
  return fake as unknown as typeof fetch;
}

describe("Google Android Publisher purchase verifier", () => {
  it("calls only the fixed subscriptionsv2 endpoint with OAuth in the header", async () => {
    let calledUrl = "";
    let calledInit: RequestInit | undefined;
    const fetchImpl = asFetch(async (...args) => {
      calledUrl = String(args[0] ?? "");
      calledInit = args[1] as RequestInit | undefined;
      return playResponse();
    });
    const verifier = new GoogleAndroidPublisherPurchaseVerifier(accessTokens, fetchImpl);
    await expect(verifier.verifySubscription({ packageName, purchaseToken })).resolves.toEqual({
      packageName, productId: "wristbrief_pro", status: "active", expiresAt: "2026-10-01T00:00:00.000Z"
    });
    expect(calledUrl).toBe("https://androidpublisher.googleapis.com/androidpublisher/v3/applications/ink.underflo.wristbrief/purchases/subscriptionsv2/tokens/secret%2Ftoken%2Bvalue");
    expect(calledInit).toMatchObject({ method: "GET", redirect: "error", headers: { Accept: "application/json", Authorization: "Bearer oauth-access-token" } });
  });

  it.each([
    ["SUBSCRIPTION_STATE_ACTIVE", "active"],
    ["SUBSCRIPTION_STATE_IN_GRACE_PERIOD", "grace"],
    ["SUBSCRIPTION_STATE_ON_HOLD", "on_hold"],
    ["SUBSCRIPTION_STATE_CANCELED", "canceled"],
    ["SUBSCRIPTION_STATE_EXPIRED", "expired"],
    ["SUBSCRIPTION_STATE_PENDING", "revoked"],
    ["SUBSCRIPTION_STATE_PAUSED", "revoked"],
    ["SUBSCRIPTION_STATE_PENDING_PURCHASE_CANCELED", "revoked"]
  ])("maps %s to %s", async (playState, expected) => {
    const verifier = new GoogleAndroidPublisherPurchaseVerifier(accessTokens, asFetch(async () => playResponse(playState)));
    await expect(verifier.verifySubscription({ packageName, purchaseToken })).resolves.toMatchObject({ status: expected });
  });

  it("fails closed when Play returns multiple distinct products", async () => {
    const fetchImpl = asFetch(async () => new Response(JSON.stringify({
      subscriptionState: "SUBSCRIPTION_STATE_ACTIVE",
      lineItems: [
        { productId: "wristbrief_pro", expiryTime: "2026-10-01T00:00:00Z" },
        { productId: "other_product", expiryTime: "2026-11-01T00:00:00Z" }
      ]
    }), { status: 200 }));
    const verifier = new GoogleAndroidPublisherPurchaseVerifier(accessTokens, fetchImpl);
    await expect(verifier.verifySubscription({ packageName, purchaseToken })).rejects.toMatchObject({ code: "ambiguous_play_product" });
  });

  it("does not surface purchase tokens or upstream error bodies", async () => {
    const upstreamBody = "credential details that must stay private";
    const verifier = new GoogleAndroidPublisherPurchaseVerifier(
      accessTokens,
      asFetch(async () => new Response(upstreamBody, { status: 403 }))
    );
    let caught: unknown;
    try { await verifier.verifySubscription({ packageName, purchaseToken }); } catch (error) { caught = error; }
    expect(caught).toBeInstanceOf(GooglePlayVerificationError);
    expect(caught).toMatchObject({ code: "play_api_failed", upstreamStatus: 403 });
    expect(String(caught)).not.toContain(purchaseToken);
    expect(String(caught)).not.toContain(upstreamBody);
  });

  it("only auto-configures production verification when both service-account secrets exist", () => {
    expect(createConfiguredGooglePlayPurchaseVerifier({})).toBeUndefined();
    expect(createConfiguredGooglePlayPurchaseVerifier({ GOOGLE_PLAY_SERVICE_ACCOUNT_EMAIL: "service@example.iam.gserviceaccount.com" })).toBeUndefined();
    expect(createConfiguredGooglePlayPurchaseVerifier({
      GOOGLE_PLAY_SERVICE_ACCOUNT_EMAIL: "service@example.iam.gserviceaccount.com",
      GOOGLE_PLAY_SERVICE_ACCOUNT_PRIVATE_KEY: "not-a-real-key"
    })).toBeDefined();
  });
});

describe("Google service-account access tokens", () => {
  it("signs the Android Publisher OAuth assertion and caches the returned access token", async () => {
    const generated = await crypto.subtle.generateKey(
      { name: "RSASSA-PKCS1-v1_5", modulusLength: 2048, publicExponent: new Uint8Array([1, 0, 1]), hash: "SHA-256" },
      true,
      ["sign", "verify"]
    );
    if (!("privateKey" in generated)) throw new Error("expected generated RSA key pair");
    const pem = toPem(new Uint8Array(await crypto.subtle.exportKey("pkcs8", generated.privateKey)));
    let fetchCount = 0;
    let tokenEndpoint = "";
    const fetchImpl = asFetch(async (...args) => {
      fetchCount += 1;
      tokenEndpoint = String(args[0] ?? "");
      const init = args[1] as RequestInit | undefined;
      const assertion = new URLSearchParams(String(init?.body ?? "")).get("assertion");
      expect(assertion).toBeTruthy();
      const [, payload] = assertion!.split(".");
      expect(JSON.parse(decodeBase64Url(payload!))).toMatchObject({
        iss: "service@example.iam.gserviceaccount.com",
        scope: "https://www.googleapis.com/auth/androidpublisher",
        aud: "https://oauth2.googleapis.com/token",
        iat: 1_800_000_000,
        exp: 1_800_003_600
      });
      return new Response(JSON.stringify({ access_token: "server-oauth-token", expires_in: 3600 }), { status: 200 });
    });
    const provider = new GoogleServiceAccountAccessTokenProvider(
      "service@example.iam.gserviceaccount.com", pem, fetchImpl, () => 1_800_000_000_000
    );
    await expect(provider.getAccessToken()).resolves.toBe("server-oauth-token");
    await expect(provider.getAccessToken()).resolves.toBe("server-oauth-token");
    expect(fetchCount).toBe(1);
    expect(tokenEndpoint).toBe("https://oauth2.googleapis.com/token");
  });
});

function toPem(bytes: Uint8Array): string {
  let binary = "";
  for (const byte of bytes) binary += String.fromCharCode(byte);
  const lines = (btoa(binary).match(/.{1,64}/g) ?? []).join("\n");
  return `-----BEGIN PRIVATE KEY-----\n${lines}\n-----END PRIVATE KEY-----`;
}

function decodeBase64Url(value: string): string {
  const base64 = value.replace(/-/g, "+").replace(/_/g, "/");
  return atob(base64.padEnd(Math.ceil(base64.length / 4) * 4, "="));
}

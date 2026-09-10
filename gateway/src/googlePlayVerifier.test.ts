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
const TEST_PRIVATE_KEY = `-----BEGIN PRIVATE KEY-----
MIIEvgIBADANBgkqhkiG9w0BAQEFAASCBKgwggSkAgEAAoIBAQCuChfDzTXztj6m
+UDiilFjlExboqXXde9oBKaOpKSCm8lsoypenleUFlH6mGTo7OpoJwz/kgY+y2YU
VYq0Lqh3JFffM+WmJ430pjpzqDw1kMedLTCTdhclMZOZfpesgHs0yessE7qruaAR
VX8sVrSaRiyuEWrQvaCxJzGymof/Kbb/0+825/xCrmcJhH4shzTiptRyTjp+55a5
aNoY/8Rcq7zjCRHsJKyS2VaN7+ZesoF1lBVD9BvLAa6S7e4dQkVq83/Uuu9io9hm
esQKHPyjL/lpeydxZwhtQARrLqowF4ELZbyh0Oa0XboiD4t+HeaId8jFWx+eZ/M9
ok/AkqULAgMBAAECggEAVcyp/KCNaZG9/2lAIK7aAwKYRlC/+6Grnhc02Xu3kqAk
xAcIm07qlBoQ3j8E+IPb5+7gjJo7s/doYrvtPnkGGn5zR5l5LNmxNRxCEsSssien
EQi3Outr660uO+zZ73QDyXmGE9vCGmjPzdvdb0lrjebsnf/+LdxefRd6e7eNNjBT
bZalapy9svOHMUextXojcaBk/DZzMckVGgtaoR36+SIv2ZuID0glBxgpyILmI89f
b1chJOiFuCMoEIyqyfVK6LN3u8X+URwHW+CFK0FBf+S9Eer7FgM5hwAGr1xyQouK
/5fcZ+ijP/1tQY7dGESXIJc29KbmW1s+P+0N1QSK2QKBgQDyKwQXd5XG08JGAO+1
nehw2zssGJ7gqyj2RDiFmxnl0ygs40PtRg0i1zSYrLsq9aHhrz4etMqsDW8PjFVF
SgcC72JEDalG4ucMmwvV1D6TYj410E+POvznHFteM4gfBAjtnoeCEtn76n9hG6hT
Wti120uckiVIaMQ4lvpDGA1W7wKBgQC3+uZGLMpx+J910P2qrye9XgB0tUjz0Xnw
X4Gq0IDeK/nSGTWal2ZyElEqbRkCs0nCKjsNFd0i4KCSps+3lOWRds1koxs+siDN
eaXlOBN6CNIH4LQBXX+pcppsro6sVPu6waJ6S/ynCanB8q5BkNCj8ijfRZ4jMZoM
uqUeotgzpQKBgQCB0jeeUPhVH15NYuEebqmWHqDJEeXSMU+DPa1DSpOGlj06Wxcm
EuCl5dKHaknoTMfckbJuiaMAuy7nmeZ4f6fkleoK63vsyWTtaAzfM6ncHtWUybhs
40WCq7v7cwqs/d+/arXgMDT1jI9+wnHpRZFZZFdTdzFPcQKIB7/fyfwUtwKBgCaO
F8/A9zPnLFOeVmp8hlFy75mFnCIsVed/vEa1pyvn/1R+H/Ojor5pBEVl04XzWJOu
Ee3uFQeIF15kQsdwFqPjR6u9WqpK3gDxa0ib9cjvLESmXFKk62nxMFdyRUfsD0zV
41zJq8q9hau1Vbs7w913lAUmK7uoW2YrfwrqiXBZAoGBAIX+cnL+F+6sCsZhNWFz
09KMX1lhYrIO0vckLiQ158PQJGNwHgmmNYgBMvbVfBAZoDmvrmpWRrxUj8LfxIPU
6LZRH1E/TkB1gOBykJFvr++RUeSjCOxxMEq0xPYXbZqwE9kqU5+je01gaaYZaC86
2s57Bg1Pxi+Z6aIvQR4DgN/u
-----END PRIVATE KEY-----`;

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
      "service@example.iam.gserviceaccount.com", TEST_PRIVATE_KEY, fetchImpl, () => 1_800_000_000_000
    );
    await expect(provider.getAccessToken()).resolves.toBe("server-oauth-token");
    await expect(provider.getAccessToken()).resolves.toBe("server-oauth-token");
    expect(fetchCount).toBe(1);
    expect(tokenEndpoint).toBe("https://oauth2.googleapis.com/token");
  });
});

function decodeBase64Url(value: string): string {
  const base64 = value.replace(/-/g, "+").replace(/_/g, "/");
  return atob(base64.padEnd(Math.ceil(base64.length / 4) * 4, "="));
}

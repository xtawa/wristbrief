import { describe, expect, it } from "vitest";
import {
  FakeGooglePlayPurchaseVerifier,
  FakePubSubPushAuthenticator,
  InMemoryBillingStateStore,
  entitlementFor,
  processPlayRtdn,
  restorePlayPurchase
} from "./billingServer";

const packageName = "ink.underflo.wristbrief";
const productId = "wristbrief_pro";

function env(status: "active" | "canceled" | "expired" | "grace" | "on_hold" | "revoked" = "active") {
  const store = new InMemoryBillingStateStore();
  const verifier = new FakeGooglePlayPurchaseVerifier({
    packageName,
    productId,
    status,
    expiresAt: "2026-10-01T00:00:00Z"
  });
  return {
    store,
    verifier,
    value: {
      PLAY_PACKAGE_NAME: packageName,
      PLAY_SUBSCRIPTION_PRODUCT_IDS: productId,
      PLAY_PURCHASE_VERIFIER: verifier,
      PLAY_BILLING_STATE_STORE: store,
      PUBSUB_PUSH_AUTHENTICATOR: new FakePubSubPushAuthenticator(true)
    }
  };
}

function rtdnRequest(payload: Record<string, unknown>) {
  const data = btoa(JSON.stringify(payload));
  return new Request("https://gateway.example/v1/billing/rtdn", {
    method: "POST",
    body: JSON.stringify({ message: { data } })
  });
}

describe("Play billing server foundation", () => {
  it("maps Play subscription states to server-owned entitlement", () => {
    expect(entitlementFor({ packageName, productId, status: "active" }).plan).toBe("PRO");
    expect(entitlementFor({ packageName, productId, status: "grace" }).plan).toBe("PRO");
    expect(entitlementFor({ packageName, productId, status: "canceled" }).plan).toBe("PRO");
    expect(entitlementFor({ packageName, productId, status: "on_hold" }).plan).toBe("FREE");
    expect(entitlementFor({ packageName, productId, status: "expired" }).plan).toBe("FREE");
    expect(entitlementFor({ packageName, productId, status: "revoked" }).plan).toBe("FREE");
  });

  it("restores only server-allowlisted package/product purchases", async () => {
    const setup = env();
    const response = await restorePlayPurchase({ id: "u1" }, {
      packageName,
      productId,
      purchaseToken: "token-secret"
    }, setup.value);
    expect(response.status).toBe(200);
    expect(response.body).toMatchObject({ entitlement: { plan: "PRO", source: "billing" } });
    expect(JSON.stringify(response.body)).not.toContain("token-secret");
    expect(setup.verifier.calls).toBe(1);
    expect(setup.store.entitlements.get("u1")?.plan).toBe("PRO");
  });

  it("prevents one purchase token from being linked to two users", async () => {
    const setup = env();
    const body = { packageName, productId, purchaseToken: "same-token" };
    expect((await restorePlayPurchase({ id: "u1" }, body, setup.value)).status).toBe(200);
    const second = await restorePlayPurchase({ id: "u2" }, body, setup.value);
    expect(second).toEqual({ status: 409, body: { error: "purchase_already_linked" } });
    expect(setup.verifier.calls).toBe(1);
  });

  it("rejects client-selected package or product before Play verification", async () => {
    const setup = env();
    const response = await restorePlayPurchase({ id: "u1" }, {
      packageName: "attacker.package",
      productId,
      purchaseToken: "token"
    }, setup.value);
    expect(response.status).toBe(400);
    expect(setup.verifier.calls).toBe(0);
  });

  it("authenticates Pub/Sub push and re-queries Play instead of trusting notification type", async () => {
    const setup = env("active");
    await restorePlayPurchase({ id: "u1" }, { packageName, productId, purchaseToken: "rtdn-token" }, setup.value);
    setup.store.entitlements.set("u1", { plan: "FREE", source: "test" });

    const response = await processPlayRtdn(rtdnRequest({
      packageName,
      subscriptionNotification: {
        notificationType: 13,
        purchaseToken: "rtdn-token",
        subscriptionId: "attacker-value-is-ignored"
      }
    }), setup.value);

    expect(response.status).toBe(204);
    expect(setup.verifier.calls).toBe(2);
    expect(setup.store.entitlements.get("u1")?.plan).toBe("PRO");
  });

  it("acknowledges authenticated Play Console test notifications without touching entitlement", async () => {
    const setup = env();
    const response = await processPlayRtdn(rtdnRequest({
      version: "1.0",
      packageName,
      eventTimeMillis: "1503350156918",
      testNotification: { version: "1.0" }
    }), setup.value);

    expect(response).toEqual({ status: 204, body: {} });
    expect(setup.verifier.calls).toBe(0);
    expect(setup.store.entitlements.size).toBe(0);
  });

  it("rejects ambiguous RTDN carrying both test and subscription payloads", async () => {
    const setup = env();
    const response = await processPlayRtdn(rtdnRequest({
      packageName,
      testNotification: { version: "1.0" },
      subscriptionNotification: { purchaseToken: "should-not-be-used" }
    }), setup.value);

    expect(response).toEqual({ status: 400, body: { error: "invalid_rtdn" } });
    expect(setup.verifier.calls).toBe(0);
  });

  it("rejects unauthenticated Pub/Sub push before reading entitlement state", async () => {
    const setup = env();
    const response = await processPlayRtdn(new Request("https://gateway.example/v1/billing/rtdn", {
      method: "POST",
      body: JSON.stringify({})
    }), { ...setup.value, PUBSUB_PUSH_AUTHENTICATOR: new FakePubSubPushAuthenticator(false) });
    expect(response).toEqual({ status: 401, body: { error: "unauthorized" } });
    expect(setup.verifier.calls).toBe(0);
  });
});

import { describe, expect, it } from "vitest";
import {
  FakeGooglePlayPurchaseVerifier,
  FakePubSubPushAuthenticator,
  InMemoryBillingStateStore,
  entitlementFor,
  processPlayRtdn,
  restorePlayPurchase
} from "./billingServer";
import { InMemoryRtdnDedupStore } from "./rtdnDedupStore";

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
      PUBSUB_PUSH_AUTHENTICATOR: new FakePubSubPushAuthenticator(true),
      RTDN_DEDUP_STORE: new InMemoryRtdnDedupStore()
    }
  };
}

let nextMessage = 0;
function rtdnRequest(payload: Record<string, unknown>, messageId = `msg-${++nextMessage}`) {
  const data = btoa(JSON.stringify(payload));
  return new Request("https://gateway.example/v1/billing/rtdn", {
    method: "POST",
    body: JSON.stringify({ message: { data, messageId } })
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

  it("deduplicates the same Pub/Sub message before a second Play verification", async () => {
    const setup = env("active");
    await restorePlayPurchase({ id: "u1" }, { packageName, productId, purchaseToken: "dedup-token" }, setup.value);
    const payload = { packageName, subscriptionNotification: { purchaseToken: "dedup-token" } };

    expect((await processPlayRtdn(rtdnRequest(payload, "pubsub-42"), setup.value).then((value) => value.status))).toBe(204);
    expect((await processPlayRtdn(rtdnRequest(payload, "pubsub-42"), setup.value).then((value) => value.status))).toBe(204);
    expect(setup.verifier.calls).toBe(2); // one restore + exactly one RTDN verification
  });

  it("releases the message claim when Play verification fails so Pub/Sub retry can recover", async () => {
    const setup = env("active");
    await restorePlayPurchase({ id: "u1" }, { packageName, productId, purchaseToken: "retry-token" }, setup.value);
    let attempts = 0;
    const retryingVerifier = {
      async verifySubscription() {
        attempts += 1;
        if (attempts === 1) throw new Error("temporary_play_failure");
        return { packageName, productId, status: "active" as const };
      }
    };
    const retryEnv = { ...setup.value, PLAY_PURCHASE_VERIFIER: retryingVerifier };
    const payload = { packageName, subscriptionNotification: { purchaseToken: "retry-token" } };

    await expect(processPlayRtdn(rtdnRequest(payload, "pubsub-retry"), retryEnv)).rejects.toThrow("temporary_play_failure");
    await expect(processPlayRtdn(rtdnRequest(payload, "pubsub-retry"), retryEnv)).resolves.toEqual({ status: 204, body: {} });
    expect(attempts).toBe(2);
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

  it("rejects RTDN without a Pub/Sub message id", async () => {
    const setup = env();
    const data = btoa(JSON.stringify({ packageName, subscriptionNotification: { purchaseToken: "token" } }));
    const response = await processPlayRtdn(new Request("https://gateway.example/v1/billing/rtdn", {
      method: "POST",
      body: JSON.stringify({ message: { data } })
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

  it("aggregates multiple active tokens and preserves PRO if one expires or cancels", async () => {
    const store = new InMemoryBillingStateStore();
    const verifier = {
      calls: 0,
      subscriptions: new Map<string, { packageName: string; productId: string; status: "active" | "canceled" | "expired" | "grace"; expiresAt?: string }>(),
      async verifySubscription(input: { packageName: string; purchaseToken: string }) {
        this.calls += 1;
        const sub = this.subscriptions.get(input.purchaseToken);
        if (!sub) throw new Error("not_found");
        return sub;
      }
    };
    const testEnv = {
      PLAY_PACKAGE_NAME: packageName,
      PLAY_SUBSCRIPTION_PRODUCT_IDS: productId,
      PLAY_PURCHASE_VERIFIER: verifier,
      PLAY_BILLING_STATE_STORE: store,
      PUBSUB_PUSH_AUTHENTICATOR: new FakePubSubPushAuthenticator(true),
      RTDN_DEDUP_STORE: new InMemoryRtdnDedupStore()
    };

    // Token 1: Monthly PRO expiring Oct 2026
    verifier.subscriptions.set("token-monthly", {
      packageName,
      productId,
      status: "active",
      expiresAt: "2026-10-01T00:00:00Z"
    });
    // Token 2: Annual PRO expiring Oct 2027
    verifier.subscriptions.set("token-annual", {
      packageName,
      productId,
      status: "active",
      expiresAt: "2027-10-01T00:00:00Z"
    });

    const res1 = await restorePlayPurchase({ id: "user-multi" }, { packageName, productId, purchaseToken: "token-monthly" }, testEnv);
    expect(res1.status).toBe(200);
    expect(store.entitlements.get("user-multi")?.expiresAt).toBe("2026-10-01T00:00:00Z");

    const res2 = await restorePlayPurchase({ id: "user-multi" }, { packageName, productId, purchaseToken: "token-annual" }, testEnv);
    expect(res2.status).toBe(200);
    // Aggregation should take the later expiry
    expect(store.entitlements.get("user-multi")?.expiresAt).toBe("2027-10-01T00:00:00Z");

    // Annual token expires via RTDN; monthly token is still active, so user remains PRO
    verifier.subscriptions.set("token-annual", {
      packageName,
      productId,
      status: "expired"
    });
    const rtdnRes = await processPlayRtdn(rtdnRequest({
      packageName,
      subscriptionNotification: { purchaseToken: "token-annual" }
    }, "annual-expired"), testEnv);
    expect(rtdnRes.status).toBe(204);

    const aggregated = store.entitlements.get("user-multi");
    expect(aggregated?.plan).toBe("PRO");
    expect(aggregated?.expiresAt).toBe("2026-10-01T00:00:00Z");

    // Delete user bindings purges all bindings and entitlements
    await store.deleteUserBindings("user-multi");
    expect(store.entitlements.has("user-multi")).toBe(false);
  });
});

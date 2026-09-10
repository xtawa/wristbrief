import { describe, expect, it } from "vitest";
import worker from "./index";
import {
  FakeGooglePlayPurchaseVerifier,
  FakePubSubPushAuthenticator,
  InMemoryBillingStateStore
} from "./billingServer";
import { InMemoryRtdnDedupStore } from "./rtdnDedupStore";

const baseEnv = {
  AI_API_KEY: "provider-secret",
  GATEWAY_TOKEN: "gateway-secret",
  AI_BASE_URL: "https://provider.example/v1",
  AI_MODEL: "test-model",
  PLAY_PACKAGE_NAME: "ink.underflo.wristbrief",
  PLAY_SUBSCRIPTION_PRODUCT_IDS: "wristbrief_pro"
};

function restoreRequest(body: unknown, token = baseEnv.GATEWAY_TOKEN) {
  return new Request("https://gateway.example/v1/billing/restore", {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
      "Authorization": `Bearer ${token}`
    },
    body: JSON.stringify(body)
  });
}

let nextMessage = 0;
function rtdnRequest(purchaseToken: string, messageId = `route-msg-${++nextMessage}`) {
  const notification = {
    packageName: baseEnv.PLAY_PACKAGE_NAME,
    subscriptionNotification: {
      purchaseToken,
      subscriptionId: "attacker-controlled-value",
      notificationType: 999
    }
  };
  const data = btoa(JSON.stringify(notification));
  return new Request("https://gateway.example/v1/billing/rtdn", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ message: { data, messageId } })
  });
}

describe("billing HTTP routes", () => {
  it("requires gateway authentication for restore", async () => {
    const response = await worker.fetch(
      restoreRequest({
        packageName: baseEnv.PLAY_PACKAGE_NAME,
        productId: "wristbrief_pro",
        purchaseToken: "purchase-token"
      }, "wrong-token"),
      baseEnv
    );
    expect(response.status).toBe(401);
    await expect(response.json()).resolves.toEqual({ error: "unauthorized" });
  });

  it("restores only server-verified purchases without returning the raw purchase token", async () => {
    const store = new InMemoryBillingStateStore();
    const verifier = new FakeGooglePlayPurchaseVerifier({
      packageName: baseEnv.PLAY_PACKAGE_NAME,
      productId: "wristbrief_pro",
      status: "active",
      expiresAt: "2026-10-01T00:00:00Z"
    });
    const purchaseToken = "sensitive-purchase-token";
    const response = await worker.fetch(
      restoreRequest({
        packageName: baseEnv.PLAY_PACKAGE_NAME,
        productId: "wristbrief_pro",
        purchaseToken
      }),
      {
        ...baseEnv,
        GATEWAY_USER_ID: "user-a",
        PLAY_PURCHASE_VERIFIER: verifier,
        PLAY_BILLING_STATE_STORE: store
      }
    );

    expect(response.status).toBe(200);
    const text = await response.text();
    expect(text).not.toContain(purchaseToken);
    expect(JSON.parse(text)).toEqual({
      entitlement: {
        plan: "PRO",
        source: "billing",
        expiresAt: "2026-10-01T00:00:00Z"
      },
      subscription: {
        productId: "wristbrief_pro",
        status: "active",
        expiresAt: "2026-10-01T00:00:00Z"
      }
    });
    expect(verifier.calls).toBe(1);
  });

  it("returns 409 when the same purchase token is restored by another user", async () => {
    const store = new InMemoryBillingStateStore();
    const verifier = new FakeGooglePlayPurchaseVerifier({
      packageName: baseEnv.PLAY_PACKAGE_NAME,
      productId: "wristbrief_pro",
      status: "active"
    });
    const purchaseToken = "single-owner-token";
    const common = {
      ...baseEnv,
      PLAY_PURCHASE_VERIFIER: verifier,
      PLAY_BILLING_STATE_STORE: store
    };

    const first = await worker.fetch(
      restoreRequest({ packageName: baseEnv.PLAY_PACKAGE_NAME, productId: "wristbrief_pro", purchaseToken }),
      { ...common, GATEWAY_USER_ID: "user-a" }
    );
    expect(first.status).toBe(200);

    const second = await worker.fetch(
      restoreRequest({ packageName: baseEnv.PLAY_PACKAGE_NAME, productId: "wristbrief_pro", purchaseToken }),
      { ...common, GATEWAY_USER_ID: "user-b" }
    );
    expect(second.status).toBe(409);
    await expect(second.json()).resolves.toEqual({ error: "purchase_already_linked" });
  });

  it("authenticates RTDN separately and re-verifies Play before changing entitlement", async () => {
    const store = new InMemoryBillingStateStore();
    const verifier = new FakeGooglePlayPurchaseVerifier({
      packageName: baseEnv.PLAY_PACKAGE_NAME,
      productId: "wristbrief_pro",
      status: "revoked"
    });
    const purchaseToken = "rtdn-token";

    const restore = await worker.fetch(
      restoreRequest({ packageName: baseEnv.PLAY_PACKAGE_NAME, productId: "wristbrief_pro", purchaseToken }),
      {
        ...baseEnv,
        GATEWAY_USER_ID: "user-a",
        PLAY_PURCHASE_VERIFIER: new FakeGooglePlayPurchaseVerifier({
          packageName: baseEnv.PLAY_PACKAGE_NAME,
          productId: "wristbrief_pro",
          status: "active"
        }),
        PLAY_BILLING_STATE_STORE: store
      }
    );
    expect(restore.status).toBe(200);

    const response = await worker.fetch(rtdnRequest(purchaseToken), {
      ...baseEnv,
      PLAY_PURCHASE_VERIFIER: verifier,
      PLAY_BILLING_STATE_STORE: store,
      PUBSUB_PUSH_AUTHENTICATOR: new FakePubSubPushAuthenticator(true),
      RTDN_DEDUP_STORE: new InMemoryRtdnDedupStore()
    });

    expect(response.status).toBe(204);
    expect(await response.text()).toBe("");
    expect(verifier.calls).toBe(1);
    expect(store.entitlements.get("user-a")).toEqual({
      plan: "FREE",
      source: "billing",
      expiresAt: undefined
    });
  });

  it("rejects unauthenticated RTDN without invoking Play verification", async () => {
    const verifier = new FakeGooglePlayPurchaseVerifier({
      packageName: baseEnv.PLAY_PACKAGE_NAME,
      productId: "wristbrief_pro",
      status: "active"
    });
    const response = await worker.fetch(rtdnRequest("unknown-token"), {
      ...baseEnv,
      PLAY_PURCHASE_VERIFIER: verifier,
      PLAY_BILLING_STATE_STORE: new InMemoryBillingStateStore(),
      PUBSUB_PUSH_AUTHENTICATOR: new FakePubSubPushAuthenticator(false),
      RTDN_DEDUP_STORE: new InMemoryRtdnDedupStore()
    });
    expect(response.status).toBe(401);
    await expect(response.json()).resolves.toEqual({ error: "unauthorized" });
    expect(verifier.calls).toBe(0);
  });
});

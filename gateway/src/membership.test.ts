import { describe, expect, it } from "vitest";
import worker from "./index";
import {
  FakeBillingVerifier,
  InMemoryMembershipStore,
  MembershipService,
  authenticateGatewayUser
} from "./membership";

describe("membership foundation", () => {
  it("maps the gateway token to a server-owned user id", () => {
    const request = new Request("https://gateway.example/v1/me", {
      headers: { Authorization: "Bearer secret", "X-WristBrief-User-ID": "attacker" }
    });
    expect(authenticateGatewayUser(request, { GATEWAY_TOKEN: "secret", GATEWAY_USER_ID: "server-user" }))
      .toEqual({ id: "server-user" });
  });

  it("does not consume managed quota for BYOK usage", async () => {
    const store = new InMemoryMembershipStore([
      { userId: "u1", plan: "FREE", managedAiLimit: 2, managedAiUsed: 1 }
    ]);
    const service = new MembershipService(store);
    expect((await service.canUseAi("u1", "byok")).allowed).toBe(true);
    await service.recordAiUsage("u1", "byok");
    expect((await service.snapshot({ id: "u1" })).managedAiQuota).toEqual({ limit: 2, used: 1, remaining: 1 });
  });

  it("consumes managed quota and blocks the next request at the limit", async () => {
    const store = new InMemoryMembershipStore([
      { userId: "u1", plan: "FREE", managedAiLimit: 1, managedAiUsed: 0 }
    ]);
    const service = new MembershipService(store);

    expect((await service.canUseAi("u1", "managed")).allowed).toBe(true);
    await service.recordAiUsage("u1", "managed");

    expect((await service.snapshot({ id: "u1" })).managedAiQuota).toEqual({ limit: 1, used: 1, remaining: 0 });
    expect((await service.canUseAi("u1", "managed")).allowed).toBe(false);
  });

  it("blocks managed AI after quota exhaustion", async () => {
    const store = new InMemoryMembershipStore([
      { userId: "u1", plan: "FREE", managedAiLimit: 1, managedAiUsed: 1 }
    ]);
    const service = new MembershipService(store);
    expect(await service.canUseAi("u1", "managed")).toMatchObject({ allowed: false });
  });

  it("returns authenticated /v1/me membership state", async () => {
    const store = new InMemoryMembershipStore([
      { userId: "server-user", plan: "FREE", managedAiLimit: 5, managedAiUsed: 2 }
    ]);
    const response = await worker.fetch(new Request("https://gateway.example/v1/me", {
      headers: { Authorization: "Bearer secret" }
    }), {
      GATEWAY_TOKEN: "secret",
      GATEWAY_USER_ID: "server-user",
      MEMBERSHIP_STORE: store,
      AI_API_KEY: "provider-secret",
      AI_BASE_URL: "https://provider.example/v1",
      AI_MODEL: "test-model"
    });
    expect(response.status).toBe(200);
    await expect(response.json()).resolves.toEqual({
      user: { id: "server-user" },
      entitlement: { plan: "FREE", source: "test" },
      managedAiQuota: { limit: 5, used: 2, remaining: 3 }
    });
  });

  it("ignores client isPro claims and blocks summary before provider work", async () => {
    const store = new InMemoryMembershipStore([
      { userId: "server-user", plan: "FREE", managedAiLimit: 0, managedAiUsed: 0 }
    ]);
    const originalFetch = globalThis.fetch;
    let upstreamCalls = 0;
    globalThis.fetch = (async () => {
      upstreamCalls += 1;
      return new Response("unexpected");
    }) as typeof fetch;
    try {
      const response = await worker.fetch(new Request("https://gateway.example/v1/summary", {
        method: "POST",
        headers: { "Content-Type": "application/json", Authorization: "Bearer secret" },
        body: JSON.stringify({ content: "hello", isPro: true })
      }), {
        GATEWAY_TOKEN: "secret",
        GATEWAY_USER_ID: "server-user",
        MEMBERSHIP_STORE: store,
        AI_API_KEY: "provider-secret",
        AI_BASE_URL: "https://provider.example/v1",
        AI_MODEL: "test-model"
      });
      expect(response.status).toBe(429);
      await expect(response.json()).resolves.toEqual({ error: "quota_exceeded", quota: { limit: 0, used: 0, remaining: 0 } });
      expect(upstreamCalls).toBe(0);
    } finally {
      globalThis.fetch = originalFetch;
    }
  });

  it("provides a fake billing verifier without external credentials", async () => {
    const verifier = new FakeBillingVerifier({ plan: "PRO", source: "billing" });
    await expect(verifier.verify({ userId: "u1", productId: "pro", purchaseToken: "not-logged" }))
      .resolves.toEqual({ plan: "PRO", source: "billing" });
  });
});

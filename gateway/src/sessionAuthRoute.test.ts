import { describe, expect, it } from "vitest";
import worker from "./index";
import { AccountSessionService, InMemoryAccountSessionStore } from "./accountSession";
import { InMemoryMembershipStore } from "./membership";

const providerEnv = {
  AI_API_KEY: "test-key",
  AI_BASE_URL: "https://api.example.com/v1",
  AI_MODEL: "test-model"
};

async function sessionFor(store: InMemoryAccountSessionStore, userId: string) {
  return new AccountSessionService(store).issue(userId);
}

describe("WristBrief session route authentication", () => {
  it("uses the session principal for /v1/me membership and quota", async () => {
    const sessionStore = new InMemoryAccountSessionStore();
    const session = await sessionFor(sessionStore, "google-user-1");
    const membershipStore = new InMemoryMembershipStore([
      { userId: "google-user-1", plan: "PRO", managedAiLimit: 50, managedAiUsed: 7, source: "billing" }
    ]);

    const response = await worker.fetch(new Request("https://gateway.example/v1/me", {
      headers: { Authorization: `Bearer ${session.token}` }
    }), {
      ...providerEnv,
      ACCOUNT_SESSION_STORE: sessionStore,
      MEMBERSHIP_STORE: membershipStore
    });

    expect(response.status).toBe(200);
    await expect(response.json()).resolves.toEqual({
      user: { id: "google-user-1" },
      entitlement: { plan: "PRO", source: "billing" },
      managedAiQuota: { limit: 50, used: 7, remaining: 43 }
    });
  });

  it("does not let a session user inherit legacy PRO when durable membership is missing", async () => {
    const sessionStore = new InMemoryAccountSessionStore();
    const session = await sessionFor(sessionStore, "google-user-1");
    const response = await worker.fetch(new Request("https://gateway.example/v1/me", {
      headers: { Authorization: `Bearer ${session.token}` }
    }), {
      ...providerEnv,
      ACCOUNT_SESSION_STORE: sessionStore,
      GATEWAY_TOKEN: "legacy-secret",
      GATEWAY_USER_ID: "legacy-user"
    });

    expect(response.status).toBe(200);
    await expect(response.json()).resolves.toEqual({
      user: { id: "google-user-1" },
      entitlement: { plan: "FREE", source: "default" },
      managedAiQuota: { limit: 0, used: 0, remaining: 0 }
    });
  });

  it("returns unauthorized after a session is revoked", async () => {
    const sessionStore = new InMemoryAccountSessionStore();
    const sessions = new AccountSessionService(sessionStore);
    const session = await sessions.issue("google-user-1");
    await sessions.revokeAuthorizationHeader(`Bearer ${session.token}`);

    const response = await worker.fetch(new Request("https://gateway.example/v1/me", {
      headers: { Authorization: `Bearer ${session.token}` }
    }), {
      ...providerEnv,
      ACCOUNT_SESSION_STORE: sessionStore,
      MEMBERSHIP_STORE: new InMemoryMembershipStore([
        { userId: "google-user-1", plan: "PRO", managedAiLimit: 50 }
      ])
    });
    expect(response.status).toBe(401);
  });

  it("keeps the legacy bearer working during migration", async () => {
    const response = await worker.fetch(new Request("https://gateway.example/v1/me", {
      headers: { Authorization: "Bearer legacy-secret" }
    }), {
      ...providerEnv,
      GATEWAY_TOKEN: "legacy-secret",
      GATEWAY_USER_ID: "legacy-user"
    });
    expect(response.status).toBe(200);
    await expect(response.json()).resolves.toMatchObject({
      user: { id: "legacy-user" },
      entitlement: { plan: "PRO", source: "legacy" }
    });
  });
});

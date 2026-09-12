import { describe, expect, it } from "vitest";
import { AccountSessionService, InMemoryAccountSessionStore } from "./accountSession";
import { authenticateRequestUser } from "./requestAuth";
import { authenticateGatewayUser, createMembershipService } from "./membership";

async function issueSession(store: InMemoryAccountSessionStore, userId: string) {
  return new AccountSessionService(store).issue(userId);
}

describe("request authentication migration", () => {
  function accountDb(status: string | null): D1Database {
    return {
      prepare: () => ({
        bind: () => ({
          first: async () => status === null ? null : { status }
        })
      })
    } as unknown as D1Database;
  }

  it("authenticates WristBrief sessions as their internal user id", async () => {
    const store = new InMemoryAccountSessionStore();
    const issued = await issueSession(store, "google-user-1");
    const request = new Request("https://gateway.example/v1/me", {
      headers: { Authorization: `Bearer ${issued.token}` }
    });

    await expect(authenticateRequestUser(request, {
      ACCOUNT_SESSION_STORE: store,
      GATEWAY_TOKEN: "legacy-secret",
      GATEWAY_USER_ID: "legacy-user"
    })).resolves.toEqual({ id: "google-user-1" });
  });

  it("preserves the explicit legacy bearer migration path", async () => {
    const request = new Request("https://gateway.example/v1/me", {
      headers: { Authorization: "Bearer legacy-secret" }
    });
    await expect(authenticateRequestUser(request, {
      GATEWAY_TOKEN: "legacy-secret",
      GATEWAY_USER_ID: "legacy-user"
    })).resolves.toEqual({ id: "legacy-user" });
  });

  it("allows the legacy principal when its persisted account is active", async () => {
    const request = new Request("https://gateway.example/v1/me", {
      headers: { Authorization: "Bearer legacy-secret" }
    });
    await expect(authenticateRequestUser(request, {
      ACCOUNT_DB: accountDb("active"),
      GATEWAY_TOKEN: "legacy-secret",
      GATEWAY_USER_ID: "legacy-user"
    })).resolves.toEqual({ id: "legacy-user" });
  });

  it("rejects the legacy principal when its persisted account is deleted", async () => {
    const request = new Request("https://gateway.example/v1/me", {
      headers: { Authorization: "Bearer legacy-secret" }
    });
    await expect(authenticateRequestUser(request, {
      ACCOUNT_DB: accountDb("deleted"),
      GATEWAY_TOKEN: "legacy-secret",
      GATEWAY_USER_ID: "legacy-user"
    })).resolves.toBeNull();
  });

  it("fails closed for revoked sessions instead of falling back to legacy auth", async () => {
    const store = new InMemoryAccountSessionStore();
    const sessions = new AccountSessionService(store);
    const issued = await sessions.issue("google-user-1");
    await sessions.revokeAuthorizationHeader(`Bearer ${issued.token}`);

    const request = new Request("https://gateway.example/v1/me", {
      headers: { Authorization: `Bearer ${issued.token}` }
    });
    await expect(authenticateRequestUser(request, {
      ACCOUNT_SESSION_STORE: store,
      GATEWAY_TOKEN: issued.token,
      GATEWAY_USER_ID: "legacy-user"
    })).resolves.toBeNull();
  });

  it("never treats a missing legacy token as the literal bearer undefined", () => {
    const request = new Request("https://gateway.example/v1/me", {
      headers: { Authorization: "Bearer undefined" }
    });
    expect(authenticateGatewayUser(request, {})).toBeNull();
  });

  it("keeps legacy compatibility PRO scoped only to the configured legacy principal", async () => {
    const service = createMembershipService({ GATEWAY_USER_ID: "legacy-user" });
    await expect(service.snapshot({ id: "legacy-user" })).resolves.toMatchObject({
      entitlement: { plan: "PRO", source: "legacy" },
      managedAiQuota: { limit: null }
    });
    await expect(service.snapshot({ id: "google-user-1" })).resolves.toEqual({
      user: { id: "google-user-1" },
      entitlement: { plan: "FREE", source: "default" },
      managedAiQuota: { limit: 0, used: 0, remaining: 0 }
    });
  });
});

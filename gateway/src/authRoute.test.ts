import { describe, expect, it } from "vitest";
import worker from "./index";
import { InMemoryAccountIdentityStore } from "./accountIdentity";
import { InMemoryAccountSessionStore } from "./accountSession";
import type { GoogleIdTokenVerifier } from "./googleIdentity";
import { InMemoryMembershipStore } from "./membership";
import { InMemoryLegacyMigrationGrantStore } from "./migrationGrant";

const verifier: GoogleIdTokenVerifier = {
  async verify(idToken: string) {
    if (idToken !== "route-google-token") return null;
    return {
      subject: "route-google-sub",
      email: "route@example.com",
      emailVerified: true as const
    };
  }
};

function baseEnv() {
  return {
    GOOGLE_ID_TOKEN_VERIFIER: verifier,
    ACCOUNT_IDENTITY_STORE: new InMemoryAccountIdentityStore(),
    ACCOUNT_SESSION_STORE: new InMemoryAccountSessionStore()
  };
}

describe("POST /v1/auth/google", () => {
  it("is public only for Google-token exchange and returns a WristBrief session", async () => {
    const env = baseEnv() as unknown as Parameters<typeof worker.fetch>[1];
    const sessionStore = env.ACCOUNT_SESSION_STORE as InMemoryAccountSessionStore;

    const response = await worker.fetch(new Request("https://gateway.example/v1/auth/google", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ idToken: "route-google-token" })
    }), env);

    expect(response.status).toBe(200);
    const body = await response.json() as { sessionToken: string; user: { id: string } };
    expect(body.sessionToken).toMatch(/^wbs_[A-Za-z0-9_-]{43}$/);
    expect(body.user.id).toBeTruthy();
    expect(JSON.stringify(body)).not.toContain("route-google-token");
    expect(JSON.stringify(sessionStore.records())).not.toContain(body.sessionToken);
  });

  it("issues a short-lived migration grant only to a valid legacy principal and consumes it during Google sign-in", async () => {
    const raw = baseEnv();
    const env = {
      ...raw,
      GATEWAY_TOKEN: "legacy-gateway-secret",
      GATEWAY_USER_ID: "legacy-user-42",
      LEGACY_MIGRATION_GRANT_STORE: new InMemoryLegacyMigrationGrantStore()
    } as unknown as Parameters<typeof worker.fetch>[1];

    const denied = await worker.fetch(new Request("https://gateway.example/v1/auth/legacy-migration-grant", {
      method: "POST"
    }), env);
    expect(denied.status).toBe(401);

    const grantResponse = await worker.fetch(new Request("https://gateway.example/v1/auth/legacy-migration-grant", {
      method: "POST",
      headers: { Authorization: "Bearer legacy-gateway-secret" }
    }), env);
    expect(grantResponse.status).toBe(200);
    const grantBody = await grantResponse.json() as { migrationGrant: string; expiresAt: string };
    expect(grantBody.migrationGrant).toMatch(/^wbm_[A-Za-z0-9_-]{43}$/);

    const exchange = () => worker.fetch(new Request("https://gateway.example/v1/auth/google", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ idToken: "route-google-token", migrationGrant: grantBody.migrationGrant })
    }), env);

    const linked = await exchange();
    expect(linked.status).toBe(200);
    await expect(linked.json()).resolves.toMatchObject({ user: { id: "legacy-user-42" } });
    expect(raw.ACCOUNT_IDENTITY_STORE.identityForGoogleSubject("route-google-sub")?.userId).toBe("legacy-user-42");

    const replay = await exchange();
    expect(replay.status).toBe(401);
    await expect(replay.json()).resolves.toMatchObject({ error: "invalid_migration_grant" });
  });

  it("links to the authenticated legacy user idempotently and preserves membership state", async () => {
    const raw = baseEnv();
    const membership = new InMemoryMembershipStore([{
      userId: "legacy-user-42",
      plan: "PRO",
      source: "billing",
      expiresAt: "2026-12-31T00:00:00Z",
      managedAiLimit: 20,
      managedAiUsed: 7
    }]);
    const env = {
      ...raw,
      GATEWAY_TOKEN: "legacy-gateway-secret",
      GATEWAY_USER_ID: "legacy-user-42",
      MEMBERSHIP_STORE: membership
    } as unknown as Parameters<typeof worker.fetch>[1];

    const makeLinkRequest = () => new Request("https://gateway.example/v1/auth/google", {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        Authorization: "Bearer legacy-gateway-secret"
      },
      body: JSON.stringify({ idToken: "route-google-token", linkLegacy: true })
    });

    const first = await worker.fetch(makeLinkRequest(), env);
    expect(first.status).toBe(200);
    const firstBody = await first.json() as { user: { id: string }; sessionToken: string };
    expect(firstBody.user.id).toBe("legacy-user-42");
    expect(raw.ACCOUNT_IDENTITY_STORE.identityForGoogleSubject("route-google-sub")?.userId).toBe("legacy-user-42");
    expect(firstBody.sessionToken).toMatch(/^wbs_[A-Za-z0-9_-]{43}$/);

    const second = await worker.fetch(makeLinkRequest(), env);
    expect(second.status).toBe(200);
    const secondBody = await second.json() as { user: { id: string }; sessionToken: string };
    expect(secondBody.user.id).toBe("legacy-user-42");

    const me = await worker.fetch(new Request("https://gateway.example/v1/me", {
      headers: { Authorization: `Bearer ${firstBody.sessionToken}` }
    }), env);
    expect(me.status).toBe(200);
    await expect(me.json()).resolves.toMatchObject({
      user: { id: "legacy-user-42" },
      entitlement: { plan: "PRO", source: "billing", expiresAt: "2026-12-31T00:00:00Z" },
      managedAiQuota: { limit: 20, used: 7, remaining: 13 }
    });
  });

  it("rejects requested legacy linking without the valid legacy bearer", async () => {
    const env = {
      ...baseEnv(),
      GATEWAY_TOKEN: "legacy-gateway-secret",
      GATEWAY_USER_ID: "legacy-user-42"
    } as unknown as Parameters<typeof worker.fetch>[1];

    for (const authorization of [undefined, "Bearer wrong-secret"]) {
      const headers = new Headers({ "Content-Type": "application/json" });
      if (authorization) headers.set("Authorization", authorization);
      const response = await worker.fetch(new Request("https://gateway.example/v1/auth/google", {
        method: "POST",
        headers,
        body: JSON.stringify({ idToken: "route-google-token", linkLegacy: true })
      }), env);
      expect(response.status).toBe(401);
      await expect(response.json()).resolves.toMatchObject({ error: "legacy_auth_required" });
    }
  });

  it("never trusts a client-supplied existingUserId", async () => {
    const env = baseEnv() as unknown as Parameters<typeof worker.fetch>[1];
    const response = await worker.fetch(new Request("https://gateway.example/v1/auth/google", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ idToken: "route-google-token", existingUserId: "attacker-selected-user" })
    }), env);

    expect(response.status).toBe(200);
    const body = await response.json() as { user: { id: string } };
    expect(body.user.id).not.toBe("attacker-selected-user");
  });

  it("rejects invalid Google identity without a legacy gateway token", async () => {
    const env = baseEnv() as unknown as Parameters<typeof worker.fetch>[1];

    const response = await worker.fetch(new Request("https://gateway.example/v1/auth/google", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ idToken: "wrong-token" })
    }), env);
    expect(response.status).toBe(401);
    await expect(response.json()).resolves.toMatchObject({ error: "invalid_google_identity" });
  });
});

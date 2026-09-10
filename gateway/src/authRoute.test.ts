import { describe, expect, it } from "vitest";
import worker from "./index";
import { InMemoryAccountIdentityStore } from "./accountIdentity";
import { InMemoryAccountSessionStore } from "./accountSession";
import type { GoogleIdTokenVerifier } from "./googleIdentity";

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

describe("POST /v1/auth/google", () => {
  it("is public only for Google-token exchange and returns a WristBrief session", async () => {
    const identityStore = new InMemoryAccountIdentityStore();
    const sessionStore = new InMemoryAccountSessionStore();
    const env = {
      GOOGLE_ID_TOKEN_VERIFIER: verifier,
      ACCOUNT_IDENTITY_STORE: identityStore,
      ACCOUNT_SESSION_STORE: sessionStore
    } as unknown as Parameters<typeof worker.fetch>[1];

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

  it("rejects invalid Google identity without a legacy gateway token", async () => {
    const env = {
      GOOGLE_ID_TOKEN_VERIFIER: verifier,
      ACCOUNT_IDENTITY_STORE: new InMemoryAccountIdentityStore(),
      ACCOUNT_SESSION_STORE: new InMemoryAccountSessionStore()
    } as unknown as Parameters<typeof worker.fetch>[1];

    const response = await worker.fetch(new Request("https://gateway.example/v1/auth/google", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ idToken: "wrong-token" })
    }), env);
    expect(response.status).toBe(401);
    await expect(response.json()).resolves.toMatchObject({ error: "invalid_google_identity" });
  });
});

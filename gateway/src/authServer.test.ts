import { describe, expect, it } from "vitest";
import { InMemoryAccountIdentityStore } from "./accountIdentity";
import { InMemoryAccountSessionStore } from "./accountSession";
import { exchangeGoogleIdToken } from "./authServer";
import type { GoogleIdTokenVerifier, VerifiedGoogleIdentity } from "./googleIdentity";

class FakeVerifier implements GoogleIdTokenVerifier {
  constructor(private readonly identities: Record<string, VerifiedGoogleIdentity | undefined>) {}
  async verify(idToken: string): Promise<VerifiedGoogleIdentity | null> {
    return this.identities[idToken] ?? null;
  }
}

function identity(subject: string, email: string): VerifiedGoogleIdentity {
  return { subject, email, emailVerified: true };
}

describe("Google auth exchange", () => {
  it("exchanges a verified Google sub for a WristBrief user and opaque session", async () => {
    const identities = new InMemoryAccountIdentityStore();
    const sessions = new InMemoryAccountSessionStore();
    const rawGoogleToken = "google-id-token-secret";
    const env = {
      GOOGLE_ID_TOKEN_VERIFIER: new FakeVerifier({ [rawGoogleToken]: identity("google-sub-1", "person@example.com") }),
      ACCOUNT_IDENTITY_STORE: identities,
      ACCOUNT_SESSION_STORE: sessions,
      ACCOUNT_SESSION_TTL_SECONDS: "3600"
    };

    const first = await exchangeGoogleIdToken({ idToken: rawGoogleToken }, env);
    expect(first.status).toBe(200);
    expect(first.body.created).toBe(true);
    expect(first.body.user).toMatchObject({ id: expect.any(String) });
    expect(first.body.sessionToken).toMatch(/^wbs_[A-Za-z0-9_-]{43}$/);
    expect(JSON.stringify(first.body)).not.toContain(rawGoogleToken);
    expect(JSON.stringify(sessions.records())).not.toContain(String(first.body.sessionToken));

    const second = await exchangeGoogleIdToken({ idToken: rawGoogleToken }, env);
    expect(second.status).toBe(200);
    expect(second.body.created).toBe(false);
    expect((second.body.user as { id: string }).id).toBe((first.body.user as { id: string }).id);
    expect(second.body.sessionToken).not.toBe(first.body.sessionToken);
  });

  it("does not merge distinct Google subjects just because email matches", async () => {
    const identityStore = new InMemoryAccountIdentityStore();
    const sessionStore = new InMemoryAccountSessionStore();
    const env = {
      GOOGLE_ID_TOKEN_VERIFIER: new FakeVerifier({
        "token-a": identity("google-sub-a", "same@example.com"),
        "token-b": identity("google-sub-b", "same@example.com")
      }),
      ACCOUNT_IDENTITY_STORE: identityStore,
      ACCOUNT_SESSION_STORE: sessionStore
    };

    const a = await exchangeGoogleIdToken({ idToken: "token-a" }, env);
    const b = await exchangeGoogleIdToken({ idToken: "token-b" }, env);
    expect((a.body.user as { id: string }).id).not.toBe((b.body.user as { id: string }).id);
  });

  it("fails closed before session creation for invalid Google identity", async () => {
    const sessions = new InMemoryAccountSessionStore();
    const response = await exchangeGoogleIdToken({ idToken: "bad-google-token" }, {
      GOOGLE_ID_TOKEN_VERIFIER: new FakeVerifier({}),
      ACCOUNT_IDENTITY_STORE: new InMemoryAccountIdentityStore(),
      ACCOUNT_SESSION_STORE: sessions
    });
    expect(response).toEqual({ status: 401, body: { error: "invalid_google_identity" } });
    expect(sessions.records()).toHaveLength(0);
  });

  it("requires server-side verifier and persistence configuration", async () => {
    await expect(exchangeGoogleIdToken({ idToken: "some-token" }, {})).resolves.toEqual({
      status: 503,
      body: { error: "auth_not_configured" }
    });
    await expect(exchangeGoogleIdToken({}, {})).resolves.toEqual({
      status: 400,
      body: { error: "invalid_request" }
    });
  });

  it("maps invalid session TTL configuration to a safe server error", async () => {
    const response = await exchangeGoogleIdToken({ idToken: "token" }, {
      GOOGLE_ID_TOKEN_VERIFIER: new FakeVerifier({ token: identity("sub", "person@example.com") }),
      ACCOUNT_IDENTITY_STORE: new InMemoryAccountIdentityStore(),
      ACCOUNT_SESSION_STORE: new InMemoryAccountSessionStore(),
      ACCOUNT_SESSION_TTL_SECONDS: "not-a-number"
    });
    expect(response).toEqual({ status: 503, body: { error: "auth_unavailable" } });
  });
});

import { describe, expect, it } from "vitest";
import { GoogleOidcIdTokenVerifier, createConfiguredGoogleIdTokenVerifier } from "./googleIdentity";

const clientId = "wristbrief-web-client.apps.googleusercontent.com";
const now = () => 1893453000 * 1000;
const token = "eyJhbGciOiJSUzI1NiIsImtpZCI6Imdvb2dsZS10ZXN0LWtleSIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJodHRwczovL2FjY291bnRzLmdvb2dsZS5jb20iLCJzdWIiOiIxMjM0NTY3ODkwMTIzNDU2Nzg5MDEiLCJhdWQiOiJ3cmlzdGJyaWVmLXdlYi1jbGllbnQuYXBwcy5nb29nbGV1c2VyY29udGVudC5jb20iLCJpYXQiOjE4OTM0NTI0MDAsImV4cCI6MTg5MzQ1NjAwMCwiZW1haWwiOiJwZXJzb25AZXhhbXBsZS5jb20iLCJlbWFpbF92ZXJpZmllZCI6dHJ1ZSwibmFtZSI6IlRlc3QgVXNlciIsInBpY3R1cmUiOiJodHRwczovL2V4YW1wbGUuaW52YWxpZC9hdmF0YXIucG5nIiwibm9uY2UiOiJub25jZS0xMjMifQ.DNng4bAKPJG7KxdZg_xUH2UHHfDYV8W-Jo7LP_Ei9EC_0KYUI8qtyKaSzLO3HjE4kd_iaMIPF0JzxwkKc_Qtwy7D5Ofkste6xFZU4b2hL49lSNYC5MB_xYT469qFrFplot2T9lNZSqOfLwI3OhZJdutTSTZO3PvxhawS__DtTQnz-uxKPEQLPczakSnjDWPi5UpWeT24lkZH_56q-_4ouHn1EEa1_EgCQMLMZkPa7oAKCNkiHiqqzCzj6s03HZAUEWmLiKSU1HJgrRkC3ExjuVkcGXjtABSYqheixEvL6yPmRYzERLT3c6iJbuI3ggZVV1KW7uwPG0isUQlg5TX00w";
const unverifiedEmailToken = "eyJhbGciOiJSUzI1NiIsImtpZCI6Imdvb2dsZS10ZXN0LWtleSIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJodHRwczovL2FjY291bnRzLmdvb2dsZS5jb20iLCJzdWIiOiIxMjM0NTY3ODkwMTIzNDU2Nzg5MDEiLCJhdWQiOiJ3cmlzdGJyaWVmLXdlYi1jbGllbnQuYXBwcy5nb29nbGV1c2VyY29udGVudC5jb20iLCJpYXQiOjE4OTM0NTI0MDAsImV4cCI6MTg5MzQ1NjAwMCwiZW1haWwiOiJwZXJzb25AZXhhbXBsZS5jb20iLCJlbWFpbF92ZXJpZmllZCI6ZmFsc2UsIm5hbWUiOiJUZXN0IFVzZXIiLCJwaWN0dXJlIjoiaHR0cHM6Ly9leGFtcGxlLmludmFsaWQvYXZhdGFyLnBuZyIsIm5vbmNlIjoibm9uY2UtMTIzIn0.YMZ1X6I80x5n_gwyx6k5300BNuSAv7LQ7dGWi3AnuBqGe1_Ofa9qHnt_nlYPTrd3zAf5z9GMMvnXslVULwmMogOdXi3DqZqst45c8Y5jjEWhDKMIWLm1bwcqde9F3OOzkVAQ62XUkuo_lYHrj4DVsNfozt0wuuDp9RUT0ef2pHihok6UhK7CQjQ2XxCETXTpEp3yJWSN0QULbVo17EBDGneAnEG2lTFoF8gfl17KVu5uBbHwRuC6gerOQk4PXNsUvXe5fR6TrbQs8x6jlKyXBs1tSLi6HfezfLpYXcYtPkHjwLf_PtLCIWug-Rie5VPXvuXZEv9rUwMzL3DkjvymEg";
const jwk = {"kty":"RSA","kid":"google-test-key","alg":"RS256","use":"sig","n":"rgoXw80187Y-pvlA4opRY5RMW6Kl13XvaASmjqSkgpvJbKMqXp5XlBZR-phk6OzqaCcM_5IGPstmFFWKtC6odyRX3zPlpieN9KY6c6g8NZDHnS0wk3YXJTGTmX6XrIB7NMnrLBO6q7mgEVV_LFa0mkYsrhFq0L2gsScxspqH_ym2_9PvNuf8Qq5nCYR-LIc04qbUck46fueWuWjaGP_EXKu84wkR7CSsktlWje_mXrKBdZQVQ_QbywGuku3uHUJFavN_1LrvYqPYZnrEChz8oy_5aXsncWcIbUAEay6qMBeBC2W8odDmtF26Ig-Lfh3miHfIxVsfnmfzPaJPwJKlCw","e":"AQAB"};

function asFetch(fake: (...args: unknown[]) => Promise<Response>): typeof fetch {
  return fake as unknown as typeof fetch;
}

function fetchFake(calls: string[]) {
  return asFetch(async (...args) => {
    calls.push(String(args[0] ?? ""));
    return new Response(JSON.stringify({ keys: [jwk] }), {
      status: 200,
      headers: { "Content-Type": "application/json", "Cache-Control": "public, max-age=600" }
    });
  });
}

describe("GoogleOidcIdTokenVerifier", () => {
  it("verifies a signed Google ID token, returns sub-based identity metadata, and caches fixed JWKS", async () => {
    const calls: string[] = [];
    const verifier = new GoogleOidcIdTokenVerifier(clientId, { fetchImpl: fetchFake(calls), now });

    await expect(verifier.verify(token, "nonce-123")).resolves.toEqual({
      subject: "123456789012345678901",
      email: "person@example.com",
      emailVerified: true,
      displayName: "Test User",
      pictureUrl: "https://example.invalid/avatar.png"
    });
    await expect(verifier.verify(token, "nonce-123")).resolves.toMatchObject({ subject: "123456789012345678901" });
    expect(calls).toEqual(["https://www.googleapis.com/oauth2/v3/certs"]);
  });

  it("binds the default fetch implementation to globalThis for Cloudflare Workers", async () => {
    const originalFetch = globalThis.fetch;
    globalThis.fetch = function (this: typeof globalThis) {
      if (this !== globalThis) throw new TypeError("Illegal invocation");
      return Promise.resolve(new Response(JSON.stringify({ keys: [jwk] }), {
        status: 200,
        headers: { "Content-Type": "application/json", "Cache-Control": "public, max-age=600" }
      }));
    } as typeof fetch;

    try {
      const verifier = new GoogleOidcIdTokenVerifier(clientId, { now });
      await expect(verifier.verify(token)).resolves.toMatchObject({
        subject: "123456789012345678901"
      });
    } finally {
      globalThis.fetch = originalFetch;
    }
  });

  it("uses a Cloudflare Workers-compatible redirect mode for JWKS", async () => {
    const redirectModes: string[] = [];
    const fetchImpl = asFetch(async (...args: unknown[]) => {
      const init = args[1] as { redirect?: string } | undefined;
      redirectModes.push(init?.redirect ?? "");
      return new Response(JSON.stringify({ keys: [jwk] }), {
        status: 200,
        headers: { "Content-Type": "application/json", "Cache-Control": "public, max-age=600" }
      });
    });
    const verifier = new GoogleOidcIdTokenVerifier(clientId, { fetchImpl, now });

    await expect(verifier.verify(token)).resolves.toMatchObject({
      subject: "123456789012345678901"
    });
    expect(redirectModes).toEqual(["manual"]);
  });

  it("fails closed for wrong audience, nonce, expiry, or unverified email", async () => {
    const fetchImpl = fetchFake([]);
    await expect(new GoogleOidcIdTokenVerifier("other.apps.googleusercontent.com", { fetchImpl, now }).verify(token)).resolves.toBeNull();
    await expect(new GoogleOidcIdTokenVerifier(clientId, { fetchImpl, now }).verify(token, "wrong-nonce")).resolves.toBeNull();
    await expect(new GoogleOidcIdTokenVerifier(clientId, { fetchImpl, now: () => 1893460000 * 1000 }).verify(token)).resolves.toBeNull();
    await expect(new GoogleOidcIdTokenVerifier(clientId, { fetchImpl, now }).verify(unverifiedEmailToken)).resolves.toBeNull();
  });

  it("rejects malformed and tampered tokens without surfacing token content", async () => {
    const verifier = new GoogleOidcIdTokenVerifier(clientId, { fetchImpl: fetchFake([]), now });
    await expect(verifier.verify("not-a-jwt")).resolves.toBeNull();
    await expect(verifier.verify(token.replace("DNng4bAKPJ", "ANng4bAKPJ"))).resolves.toBeNull();
  });

  it("only auto-configures for a Google OAuth client id", () => {
    expect(createConfiguredGoogleIdTokenVerifier({})).toBeUndefined();
    expect(createConfiguredGoogleIdTokenVerifier({ GOOGLE_OAUTH_CLIENT_ID: "not-a-google-client" })).toBeUndefined();
    expect(createConfiguredGoogleIdTokenVerifier({ GOOGLE_OAUTH_CLIENT_ID: clientId })).toBeDefined();
  });
});

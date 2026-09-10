import { describe, expect, it } from "vitest";
import { GooglePubSubPushAuthenticator, createConfiguredPubSubPushAuthenticator } from "./pubSubPushAuthenticator";

const audience = "https://gateway.example/v1/billing/rtdn";
const serviceAccountEmail = "wristbrief-rtdn@test-project.iam.gserviceaccount.com";
const now = () => 1893453000 * 1000;
const token = "eyJhbGciOiJSUzI1NiIsImtpZCI6InRlc3Qta2V5IiwidHlwIjoiSldUIn0.eyJpc3MiOiJodHRwczovL2FjY291bnRzLmdvb2dsZS5jb20iLCJzdWIiOiIxMjM0NTY3ODkiLCJhdWQiOiJodHRwczovL2dhdGV3YXkuZXhhbXBsZS92MS9iaWxsaW5nL3J0ZG4iLCJpYXQiOjE4OTM0NTI0MDAsImV4cCI6MTg5MzQ1NjAwMCwiZW1haWwiOiJ3cmlzdGJyaWVmLXJ0ZG5AdGVzdC1wcm9qZWN0LmlhbS5nc2VydmljZWFjY291bnQuY29tIiwiZW1haWxfdmVyaWZpZWQiOnRydWV9.WqKsQlEUNQGOaKrAq_iP7YpnMuYJZrq--HGO6205nX8alLBlD8mHPL15PTBSxk-XIyIMIudcXmYUflpOovtv4flBikBk3g5beI9Ut1vmHiPRvhEGQf-dr0FOGCD8nTXEq-_rB03G9ZRYfBz4DquJbG_PZzM4j8TLXCyp2RJywzJ4cwEFFSEDvkhyIW2i-Qn7JXy6uU9pIp1zi1NjAAxAdYjnPS9ZB_aIFcXit5zNxDZ1slXQTZgh7WQFTlTsbwrJTjqS0Xm_rfJJHFdld_p2rYo2H4q4kjfWRxtEWGmHrHXqSGI9FeXmOiC7kyX1O-3myGVOCLu70fdpbndBhErJ5Q";
const jwk = {"kty":"RSA","kid":"test-key","alg":"RS256","use":"sig","n":"r3gwgVpdydA6QeKuDUaK4HrWnKNj_ptdmLSTy6pfkqm3uVn9x840ja3wtIWBlOpz-7_6LLb-Jqc9Ctbj3Rd4AEbBHD90O45nhtkMuhOeaTs4UvcVvQAeaWCgznaOnRm-6V_QiCkikHNy0kH7YZNpUObfbh27bPM5Dz6lA6CQBcSoqkjoBkXp_UGPQRuIp8f65bzpVebstf5isSclebU2-lRX7SqEWK3bXlHjmbvZdT8CsSu0LYzLNLewifWBLnF01Ry8MlEu5I6KcWNQa4lAjwNN9zWHirIoFJ4UTIFD42AL5Mo_gckkoDE-V4Qj--g1Ed3C2jOS48CJT9lxDiNJtw","e":"AQAB"};

function fetchFake(calls: string[]) {
  return (async (input: RequestInfo | URL) => {
    calls.push(String(input));
    return new Response(JSON.stringify({ keys: [jwk] }), {
      status: 200,
      headers: { "Content-Type": "application/json", "Cache-Control": "public, max-age=600" }
    });
  }) as typeof fetch;
}

function request(value = token) {
  return new Request(audience, {
    method: "POST",
    headers: { Authorization: `Bearer ${value}` }
  });
}

describe("GooglePubSubPushAuthenticator", () => {
  it("verifies a signed Google OIDC JWT and caches the fixed Google JWKS", async () => {
    const calls: string[] = [];
    const verifier = new GooglePubSubPushAuthenticator(serviceAccountEmail, audience, {
      fetchImpl: fetchFake(calls),
      now
    });

    expect(await verifier.verify(request())).toBe(true);
    expect(await verifier.verify(request())).toBe(true);
    expect(calls).toEqual(["https://www.googleapis.com/oauth2/v3/certs"]);
  });

  it("fails closed for the wrong audience or service account", async () => {
    const fetchImpl = fetchFake([]);
    const wrongAudience = new GooglePubSubPushAuthenticator(serviceAccountEmail, "https://gateway.example/other", { fetchImpl, now });
    const wrongEmail = new GooglePubSubPushAuthenticator("other@test-project.iam.gserviceaccount.com", audience, { fetchImpl, now });

    expect(await wrongAudience.verify(request())).toBe(false);
    expect(await wrongEmail.verify(request())).toBe(false);
  });

  it("rejects missing, malformed, and tampered bearer JWTs", async () => {
    const verifier = new GooglePubSubPushAuthenticator(serviceAccountEmail, audience, {
      fetchImpl: fetchFake([]),
      now
    });

    expect(await verifier.verify(new Request(audience, { method: "POST" }))).toBe(false);
    expect(await verifier.verify(request("not-a-jwt"))).toBe(false);
    const tampered = token.replace("WqKsQlEUNQ", "AqKsQlEUNQ");
    expect(await verifier.verify(request(tampered))).toBe(false);
  });

  it("does not become configured without an HTTPS audience and service-account email", () => {
    expect(createConfiguredPubSubPushAuthenticator({})).toBeUndefined();
    expect(createConfiguredPubSubPushAuthenticator({
      PUBSUB_PUSH_SERVICE_ACCOUNT_EMAIL: serviceAccountEmail,
      PUBSUB_PUSH_AUDIENCE: "http://gateway.example/v1/billing/rtdn"
    })).toBeUndefined();
    expect(createConfiguredPubSubPushAuthenticator({
      PUBSUB_PUSH_SERVICE_ACCOUNT_EMAIL: serviceAccountEmail,
      PUBSUB_PUSH_AUDIENCE: audience
    })).toBeDefined();
  });
});

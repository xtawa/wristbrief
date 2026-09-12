// @ts-nocheck
import { describe, expect, it } from "vitest";
import { handleAdminRoute } from "./adminRoutes";
import { createEmailAuthTestSetup } from "../emailAuth/emailAuthTestSetup";

const BASE = "https://gateway.test";
const REQUEST_ID = "req-provider-test";

async function adminSession(setup, email = "admin@example.com", password = "Password12345") {
  const response = await handleAdminRoute(
    new Request(`${BASE}/v1/admin/session`, {
      method: "POST",
      headers: { "Content-Type": "application/json", Origin: BASE },
      body: JSON.stringify({ email, password })
    }),
    { ACCOUNT_DB: setup.d1, AI_ALLOWED_HOSTS: "api.openai.com", AI_PROVIDER_SECRET_1: "sk-live-1" },
    REQUEST_ID
  );
  if (response.status !== 200) return null;
  const cookies = response.headers.getSetCookie?.() ?? [];
  const adminCookie = cookies.find((cookie) => cookie.startsWith("__Host-wristbrief_admin="));
  const body = await response.json();
  return { cookie: adminCookie.split(";")[0], csrfToken: body.csrfToken };
}

function providerEnv(setup, extra = {}) {
  return {
    ACCOUNT_DB: setup.d1,
    AI_ALLOWED_HOSTS: "api.openai.com",
    AI_PROVIDER_SECRET_1: "sk-live-1",
    ...extra
  };
}

describe("admin provider configuration", () => {
  it("creates, lists, and updates providers without ever exposing secrets", async () => {
    const setup = createEmailAuthTestSetup();
    await setup.service.register("admin@example.com", "Password12345");
    const session = await adminSession(setup);
    expect(session).not.toBeNull();

    const create = await handleAdminRoute(
      new Request(`${BASE}/v1/admin/providers`, {
        method: "POST",
        headers: { "Content-Type": "application/json", Origin: BASE, Cookie: session.cookie, "X-CSRF-Token": session.csrfToken },
        body: JSON.stringify({
          id: "primary-llm",
          adapterType: "openai-compatible",
          displayName: "Primary LLM",
          baseUrl: "https://api.openai.com/v1",
          model: "gpt-5-mini",
          secretRef: "AI_PROVIDER_SECRET_1"
        })
      }),
      providerEnv(setup),
      REQUEST_ID
    );
    expect(create.status).toBe(201);
    const created = await create.json();
    expect(created.provider.secretConfigured).toBe(true);
    // Secret material never appears in any response.
    expect(JSON.stringify(created)).not.toContain("sk-live-1");

    const list = await handleAdminRoute(
      new Request(`${BASE}/v1/admin/providers`, { headers: { Cookie: session.cookie } }),
      providerEnv(setup),
      REQUEST_ID
    );
    expect(list.status).toBe(200);
    const listed = await list.json();
    expect(listed.providers).toHaveLength(1);
    expect(listed.secretSlots[0]).toEqual({ slot: "AI_PROVIDER_SECRET_1", configured: true });

    const patch = await handleAdminRoute(
      new Request(`${BASE}/v1/admin/providers/primary-llm`, {
        method: "PATCH",
        headers: { "Content-Type": "application/json", Origin: BASE, Cookie: session.cookie, "X-CSRF-Token": session.csrfToken },
        body: JSON.stringify({
          adapterType: "openai-compatible",
          displayName: "Primary LLM v2",
          baseUrl: "https://api.openai.com/v1",
          model: "gpt-5-mini",
          secretRef: "AI_PROVIDER_SECRET_2"
        })
      }),
      providerEnv(setup, { AI_PROVIDER_SECRET_2: "sk-live-2" }),
      REQUEST_ID
    );
    expect(patch.status).toBe(200);
    const updated = await patch.json();
    expect(updated.provider.displayName).toBe("Primary LLM v2");
    expect(updated.provider.configRevision).toBe(2);

    // Audit records the secret slot reference only — never a value.
    const audit = await setup.d1.prepare("SELECT action, before_json, after_json FROM admin_audit_log").all();
    const providerEntries = audit.results.filter((row) => row.action.startsWith("provider_"));
    expect(providerEntries.length).toBeGreaterThanOrEqual(2);
    expect(JSON.stringify(providerEntries)).not.toContain("sk-live");
    expect(providerEntries.some((row) => row.before_json?.includes("AI_PROVIDER_SECRET_1"))).toBe(true);
    expect(providerEntries.some((row) => row.after_json?.includes("AI_PROVIDER_SECRET_2"))).toBe(true);
  });

  it("refuses hosts outside the deployment allowlist and non-admin access", async () => {
    const setup = createEmailAuthTestSetup();
    await setup.service.register("admin@example.com", "Password12345");
    const session = await adminSession(setup);

    const blocked = await handleAdminRoute(
      new Request(`${BASE}/v1/admin/providers`, {
        method: "POST",
        headers: { "Content-Type": "application/json", Origin: BASE, Cookie: session.cookie, "X-CSRF-Token": session.csrfToken },
        body: JSON.stringify({
          id: "evil-provider",
          adapterType: "openai-compatible",
          displayName: "Evil",
          baseUrl: "https://api.evil.example/v1",
          model: "x",
          secretRef: "AI_PROVIDER_SECRET_1"
        })
      }),
      providerEnv(setup),
      REQUEST_ID
    );
    expect(blocked.status).toBe(400);
    expect((await blocked.json()).error).toBe("host_not_allowed");

    // Unauthenticated and non-admin access.
    const unauthenticated = await handleAdminRoute(
      new Request(`${BASE}/v1/admin/providers`),
      providerEnv(setup),
      REQUEST_ID
    );
    expect(unauthenticated.status).toBe(401);

    await setup.adminStore.writeRegistrationMode("OPEN", null);
    await setup.service.register("plain@example.com", "Password123456");
    const plainSession = await adminSession(setup, "plain@example.com", "Password123456");
    expect(plainSession).toBeNull(); // non-admins cannot even open an admin session

    // CSRF is required for mutations.
    const noCsrf = await handleAdminRoute(
      new Request(`${BASE}/v1/admin/providers`, {
        method: "POST",
        headers: { "Content-Type": "application/json", Origin: BASE, Cookie: session.cookie },
        body: JSON.stringify({})
      }),
      providerEnv(setup),
      REQUEST_ID
    );
    expect(noCsrf.status).toBe(403);
  });
});

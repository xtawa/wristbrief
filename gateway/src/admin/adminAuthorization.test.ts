// @ts-nocheck
import { describe, expect, it } from "vitest";
import { handleAdminRoute } from "./adminRoutes";
import { createEmailAuthTestSetup } from "../emailAuth/emailAuthTestSetup";

const BASE = "https://gateway.test";
const REQUEST_ID = "req-test";

async function adminSessionCookie(setup, email: string, password: string): Promise<{ cookie: string; csrfToken: string } | null> {
  const response = await handleAdminRoute(
    new Request(`${BASE}/v1/admin/session`, {
      method: "POST",
      headers: { "Content-Type": "application/json", Origin: BASE },
      body: JSON.stringify({ email, password })
    }),
    { ACCOUNT_DB: setup.d1 },
    REQUEST_ID
  );
  if (response.status !== 200) return null;
  const cookies = response.headers.getSetCookie?.() ?? [];
  const adminCookie = cookies.find((cookie) => cookie.startsWith("__Host-wristbrief_admin="));
  if (!adminCookie) return null;
  const body = await response.json();
  return { cookie: adminCookie.split(";")[0], csrfToken: body.csrfToken };
}

describe("admin authorization and CSRF", () => {
  it("redirects unauthenticated page access and rejects unauthenticated API access", async () => {
    const setup = createEmailAuthTestSetup();
    const page = await handleAdminRoute(
      new Request(`${BASE}/admin`),
      { ACCOUNT_DB: setup.d1 },
      REQUEST_ID
    );
    expect(page.status).toBe(302);
    expect(page.headers.get("Location")).toBe("/admin/login");

    const api = await handleAdminRoute(
      new Request(`${BASE}/v1/admin/settings`),
      { ACCOUNT_DB: setup.d1 },
      REQUEST_ID
    );
    expect(api.status).toBe(401);
  });

  it("rejects non-admin accounts from the admin session endpoint", async () => {
    const setup = createEmailAuthTestSetup();
    await setup.service.register("admin@example.com", "Password12345");
    await setup.adminStore.writeRegistrationMode("OPEN", null);
    await setup.service.register("plain@example.com", "Password123456");

    const plain = await adminSessionCookie(setup, "plain@example.com", "Password123456");
    expect(plain).toBeNull();

    const admin = await adminSessionCookie(setup, "admin@example.com", "Password12345");
    expect(admin).not.toBeNull();
    expect(admin.csrfToken).toBeTruthy();
  });

  it("serves settings to admins and enforces CSRF + Origin on mutations", async () => {
    const setup = createEmailAuthTestSetup();
    await setup.service.register("admin@example.com", "Password12345");
    const session = await adminSessionCookie(setup, "admin@example.com", "Password12345");

    const read = await handleAdminRoute(
      new Request(`${BASE}/v1/admin/settings`, { headers: { Cookie: session.cookie } }),
      { ACCOUNT_DB: setup.d1 },
      REQUEST_ID
    );
    expect(read.status).toBe(200);
    expect((await read.json()).registrationMode).toBe("CLOSED");

    // No CSRF token -> rejected even with valid session and Origin.
    const noCsrf = await handleAdminRoute(
      new Request(`${BASE}/v1/admin/settings`, {
        method: "PATCH",
        headers: { Cookie: session.cookie, "Content-Type": "application/json", Origin: BASE },
        body: JSON.stringify({ registrationMode: "OPEN" })
      }),
      { ACCOUNT_DB: setup.d1 },
      REQUEST_ID
    );
    expect(noCsrf.status).toBe(403);
    expect((await noCsrf.json()).error).toBe("csrf_required");

    // Wrong Origin -> rejected even with valid CSRF token.
    const badOrigin = await handleAdminRoute(
      new Request(`${BASE}/v1/admin/settings`, {
        method: "PATCH",
        headers: { Cookie: session.cookie, "Content-Type": "application/json", Origin: "https://evil.example", "X-CSRF-Token": session.csrfToken },
        body: JSON.stringify({ registrationMode: "OPEN" })
      }),
      { ACCOUNT_DB: setup.d1 },
      REQUEST_ID
    );
    expect(badOrigin.status).toBe(403);

    // Cross-site browser fetch marker -> rejected.
    const crossSite = await handleAdminRoute(
      new Request(`${BASE}/v1/admin/settings`, {
        method: "PATCH",
        headers: { Cookie: session.cookie, "Content-Type": "application/json", Origin: BASE, "Sec-Fetch-Site": "cross-site", "X-CSRF-Token": session.csrfToken },
        body: JSON.stringify({ registrationMode: "OPEN" })
      }),
      { ACCOUNT_DB: setup.d1 },
      REQUEST_ID
    );
    expect(crossSite.status).toBe(403);

    // Valid session + CSRF + Origin -> applied and audited.
    const patch = await handleAdminRoute(
      new Request(`${BASE}/v1/admin/settings`, {
        method: "PATCH",
        headers: { Cookie: session.cookie, "Content-Type": "application/json", Origin: BASE, "X-CSRF-Token": session.csrfToken },
        body: JSON.stringify({ registrationMode: "OPEN" })
      }),
      { ACCOUNT_DB: setup.d1 },
      REQUEST_ID
    );
    expect(patch.status).toBe(200);
    expect((await patch.json()).registrationMode).toBe("OPEN");

    const audit = await setup.d1.prepare("SELECT action, before_json, after_json FROM admin_audit_log ORDER BY created_at DESC").all();
    const entry = audit.results.find((row) => row.action === "registration_mode_changed");
    expect(entry.before_json).toContain("CLOSED");
    expect(entry.after_json).toContain("OPEN");
  });

  it("revokes admin sessions on logout and refuses the cookie afterwards", async () => {
    const setup = createEmailAuthTestSetup();
    await setup.service.register("admin@example.com", "Password12345");
    const session = await adminSessionCookie(setup, "admin@example.com", "Password12345");

    const logout = await handleAdminRoute(
      new Request(`${BASE}/v1/admin/logout`, {
        method: "POST",
        headers: { Cookie: session.cookie, Origin: BASE, "X-CSRF-Token": session.csrfToken }
      }),
      { ACCOUNT_DB: setup.d1 },
      REQUEST_ID
    );
    expect(logout.status).toBe(200);

    const after = await handleAdminRoute(
      new Request(`${BASE}/v1/admin/settings`, { headers: { Cookie: session.cookie } }),
      { ACCOUNT_DB: setup.d1 },
      REQUEST_ID
    );
    expect(after.status).toBe(401);
  });

  it("reports public bootstrap status", async () => {
    const setup = createEmailAuthTestSetup();
    const before = await handleAdminRoute(
      new Request(`${BASE}/v1/admin/bootstrap-status`),
      { ACCOUNT_DB: setup.d1 },
      REQUEST_ID
    );
    expect(before.status).toBe(200);
    expect((await before.json()).registrationOpen).toBe(true);

    await setup.service.register("admin@example.com", "Password12345");
    const after = await handleAdminRoute(
      new Request(`${BASE}/v1/admin/bootstrap-status`),
      { ACCOUNT_DB: setup.d1 },
      REQUEST_ID
    );
    const body = await after.json();
    expect(body.registrationOpen).toBe(false);
    expect(body.bootstrapCompleted).toBe(true);
  });

  it("requires the exact recovery secret and an existing user", async () => {
    const setup = createEmailAuthTestSetup();
    const env = { ACCOUNT_DB: setup.d1, ADMIN_RECOVERY_SECRET: "recovery-secret-value" };
    const call = (body: Record<string, unknown>, authorization: string, override?: string) =>
      handleAdminRoute(
        new Request(`${BASE}/v1/admin/recovery`, {
          method: "POST",
          headers: { "Content-Type": "application/json", Authorization: authorization },
          body: JSON.stringify(body)
        }),
        override ? { ...env, ADMIN_RECOVERY_OVERRIDE: override } : env,
        REQUEST_ID
      );

    const wrongSecret = await call({ userId: "u-missing" }, "Bearer wrong-secret", "true");
    expect(wrongSecret.status).toBe(401);

    const missing = await call({ userId: "u-missing" }, "Bearer recovery-secret-value", "true");
    expect(missing.status).toBe(404);
  });

  it("promotes via recovery only when no admin exists or override is set", async () => {
    const setup = createEmailAuthTestSetup();
    const env = { ACCOUNT_DB: setup.d1, ADMIN_RECOVERY_SECRET: "recovery-secret-value" };
    const call = (userId: string, override?: string) =>
      handleAdminRoute(
        new Request(`${BASE}/v1/admin/recovery`, {
          method: "POST",
          headers: { "Content-Type": "application/json", Authorization: "Bearer recovery-secret-value" },
          body: JSON.stringify({ userId })
        }),
        override ? { ...env, ADMIN_RECOVERY_OVERRIDE: override } : env,
        REQUEST_ID
      );

    // Bootstrap admin, then an ordinary second user as promotion target.
    await setup.service.register("admin@example.com", "Password12345");
    await setup.adminStore.writeRegistrationMode("OPEN", null);
    const second = await setup.service.register("target@example.com", "Password123456");
    const targetUserId = second.body.user.id;

    // An admin already exists and override is off -> recovery refused.
    const refused = await call(targetUserId);
    expect(refused.status).toBe(403);

    // Explicit override mode -> promotion allowed and audited.
    const promoted = await call(targetUserId, "true");
    expect(promoted.status).toBe(200);
    expect(await setup.adminStore.rolesForUser(targetUserId)).toContain("admin");

    const audit = await setup.d1.prepare("SELECT action FROM admin_audit_log").all();
    expect(audit.results.map((row) => row.action)).toContain("admin_role_granted_via_recovery");
  });
});

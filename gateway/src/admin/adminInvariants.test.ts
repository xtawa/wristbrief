// @ts-nocheck
/**
 * Re-assertion of the non-negotiable product invariants after the console
 * rewrite: exactly one web administrator, no promotion path, no web
 * registration, closed public recovery in single-admin mode, and a forced
 * first-login password change that cannot be bypassed.
 */
import { describe, expect, it } from "vitest";
import {
  ADMIN_EMAIL,
  ADMIN_PASSWORD,
  adminEnv,
  callAdmin,
  createSetup,
  formRequest,
  formTokenFrom,
  jsonRequest,
  openSession,
  readPage,
  registerAdmin,
  seedAccounts,
  sessionRequest
} from "./adminTestHelpers";

const ROTATED_PASSWORD = "Rotated-Admin-Password-2026";

async function invariantFixture(extraEnv = {}) {
  const setup = createSetup();
  const adminId = await registerAdmin(setup);
  const readers = await seedAccounts(setup, 1);
  const env = adminEnv(setup, extraEnv);
  const session = await openSession(setup, env);
  expect(session.ok).toBe(true);
  return { setup, env, session, adminId, reader: readers[0] };
}

describe("sole administrator invariants", () => {
  it("cannot be demoted, disabled, renamed or removed through any admin route", async () => {
    const { setup, env, session, adminId } = await invariantFixture();

    const patched = await callAdmin(
      sessionRequest(`/v1/admin/users/${adminId}`, session, {
        method: "PATCH",
        body: { displayName: "Not Admin", status: "disabled", plan: "FREE", quotaLimit: 0 }
      }),
      env
    );
    expect(patched.status).toBe(403);
    expect(patched.json.error).toBe("admin_account_protected");

    const posted = await callAdmin(
      formRequest(`/admin/users/${adminId}`, session, {
        csrfToken: formTokenFrom((await readPage(setup, env, session, "/admin/users")).text),
        displayName: "Not Admin",
        status: "disabled",
        plan: "FREE",
        quotaLimit: "0"
      }),
      env
    );
    expect(posted.status).toBe(403);

    const admin = await setup.d1.prepare("SELECT status FROM users WHERE id = ?").bind(adminId).first();
    expect(admin.status).toBe("active");
    expect(await setup.adminStore.rolesForUser(adminId)).toEqual(["admin"]);
    const admins = await setup.d1.prepare("SELECT COUNT(*) AS count FROM user_roles WHERE role = 'admin'").first();
    expect(admins.count).toBe(1);
  });

  it("is never listed on the users page or in the users API", async () => {
    const { setup, env, session, adminId } = await invariantFixture();

    const api = await callAdmin(sessionRequest("/v1/admin/users", session), env);
    expect(api.json.users.some((user) => user.id === adminId)).toBe(false);
    expect(api.json.page.total).toBe(1);

    const page = await readPage(setup, env, session, "/admin/users");
    expect(page.text).not.toContain(adminId);
    expect(page.text).not.toContain(`/admin/users/${adminId}`);
    expect(page.text).toContain("The sole administrator is never listed and cannot be edited, demoted or removed.");
  });

  it("cannot be joined by promoting an ordinary account", async () => {
    const { setup, env, session, reader } = await invariantFixture();

    const attempt = await callAdmin(
      sessionRequest(`/v1/admin/users/${reader.id}`, session, {
        method: "PATCH",
        body: { displayName: "Reader", status: "active", plan: "PRO", quotaLimit: 10, role: "admin", isAdmin: true, admin: true }
      }),
      env
    );
    expect(attempt.status).toBe(200);
    expect(await setup.adminStore.rolesForUser(reader.id)).toEqual([]);
    const admins = await setup.d1.prepare("SELECT COUNT(*) AS count FROM user_roles WHERE role = 'admin'").first();
    expect(admins.count).toBe(1);

    // There is no route that grants a role.
    for (const path of ["/v1/admin/users/x/roles", "/v1/admin/roles", "/v1/admin/admins"]) {
      const response = await callAdmin(sessionRequest(path, session, { method: "POST", body: { role: "admin" } }), env);
      expect(response.status).toBe(404);
    }
    const wrongMethod = await callAdmin(
      sessionRequest(`/v1/admin/users/${reader.id}`, session, { method: "PUT", body: { role: "admin" } }),
      env
    );
    expect(wrongMethod.status).toBe(404);
  });

  it("keeps public admin recovery closed in single-admin mode", async () => {
    const { setup, env, session, reader } = await invariantFixture({
      ADMIN_RECOVERY_SECRET: "recovery-secret-value",
      ADMIN_RECOVERY_OVERRIDE: "true"
    });

    const recovery = await callAdmin(
      jsonRequest("/v1/admin/recovery", {
        method: "POST",
        body: { userId: reader.id },
        headers: { Authorization: "Bearer recovery-secret-value" }
      }),
      env
    );
    expect(recovery.status).toBe(404);
    expect(recovery.json.error).toBe("not_found");
    expect(await setup.adminStore.rolesForUser(reader.id)).toEqual([]);

    const page = await callAdmin(jsonRequest("/admin/recovery"), env);
    expect(page.status).toBe(404);
    const posted = await callAdmin(jsonRequest("/admin/recovery", { method: "POST", body: { userId: reader.id } }), env);
    expect(posted.status).toBe(404);
    expect(session.ok).toBe(true);
  });

  it("has no web registration path", async () => {
    // No seeded accounts here: this test also asserts that mobile registration
    // starts closed, which the seeding helper would have opened.
    const setup = createSetup();
    const adminId = await registerAdmin(setup);
    const env = adminEnv(setup);
    const session = await openSession(setup, env);
    expect(session.ok).toBe(true);

    // Fetched without a session, i.e. as an anonymous visitor sees it.
    const login = await callAdmin(jsonRequest("/admin/login"), env);
    expect(login.status).toBe(200);
    expect(login.text).not.toContain("Create the first admin account");
    expect(login.text).not.toContain("/v1/auth/email/register");
    expect(login.text).not.toContain("Create account");
    expect(login.text).toContain('<form method="post" action="/admin/login">');

    const before = await setup.d1.prepare("SELECT COUNT(*) AS count FROM users").first();
    const register = await callAdmin(jsonRequest("/admin/register", { method: "POST", body: { email: "sneak@example.com", password: "Password12345" } }), env);
    expect(register.status).toBe(404);
    const after = await setup.d1.prepare("SELECT COUNT(*) AS count FROM users").first();
    expect(after.count).toBe(before.count);
    expect(adminId).toBeTruthy();

    const status = await callAdmin(jsonRequest("/v1/admin/bootstrap-status"), env);
    expect(status.json.registrationOpen).toBe(false);
    expect(status.json.mobileRegistrationOpen).toBe(false);
    expect(status.json.bootstrapCompleted).toBe(true);

    // Opening mobile registration never opens web administration.
    await setup.adminStore.writeRegistrationMode("OPEN", null);
    const opened = await callAdmin(jsonRequest("/v1/admin/bootstrap-status"), env);
    expect(opened.json.registrationOpen).toBe(false);
    expect(opened.json.mobileRegistrationOpen).toBe(true);
  });
});

describe("forced first-login password change", () => {
  async function pendingPasswordChange() {
    const fixture = await invariantFixture();
    await fixture.setup.d1
      .prepare("INSERT INTO admin_security (user_id, must_change_password) VALUES (?, 1) ON CONFLICT(user_id) DO UPDATE SET must_change_password = 1")
      .bind(fixture.adminId)
      .run();
    return fixture;
  }

  it("gates every page and write until the temporary password is replaced", async () => {
    const { setup, env, session, adminId, reader } = await pendingPasswordChange();

    expect((await callAdmin(sessionRequest("/admin", session), env)).status).toBe(302);
    const page = await callAdmin(sessionRequest("/admin", session), env);
    expect(page.response.headers.get("Location")).toBe("/admin/change-password");
    expect((await callAdmin(sessionRequest("/admin/users", session), env)).response.headers.get("Location")).toBe("/admin/change-password");
    expect((await callAdmin(sessionRequest("/v1/admin/users", session), env)).status).toBe(403);
    expect((await callAdmin(sessionRequest("/v1/admin/users", session), env)).json.error).toBe("password_change_required");
    expect((await callAdmin(sessionRequest("/admin/smtp", session), env)).status).toBe(302);

    // A form POST is redirected before the handler runs, so nothing is written.
    const formToken = formTokenFrom((await readPage(setup, env, session, "/admin/change-password")).text);
    const writeAttempt = await callAdmin(
      formRequest(`/admin/users/${reader.id}`, session, {
        csrfToken: formToken,
        displayName: "Sneaky",
        status: "disabled",
        plan: "PRO",
        quotaLimit: "1"
      }),
      env
    );
    expect(writeAttempt.status).toBe(302);
    const unchanged = await setup.d1.prepare("SELECT status FROM users WHERE id = ?").bind(reader.id).first();
    expect(unchanged.status).toBe("active");
    const apiAttempt = await callAdmin(
      sessionRequest(`/v1/admin/users/${reader.id}`, session, { method: "PATCH", body: { displayName: "Sneaky", status: "active", plan: "FREE", quotaLimit: 1 } }),
      env
    );
    expect(apiAttempt.status).toBe(403);
    expect(adminId).toBeTruthy();
  });

  it("validates the new password beside the field and only then clears the flag", async () => {
    const { setup, env, session, adminId } = await pendingPasswordChange();
    const page = await readPage(setup, env, session, "/admin/change-password");
    expect(page.status).toBe(200);
    expect(page.text).toContain('<form method="post" action="/admin/change-password">');
    expect(page.text).toContain('for="currentPassword"');
    expect(page.text).toContain('for="newPassword"');
    expect(page.text).toContain('for="confirmPassword"');
    expect(page.text).toContain("This step cannot be skipped.");
    const token = formTokenFrom(page.text);

    const stillPending = async () => {
      const row = await setup.d1.prepare("SELECT must_change_password FROM admin_security WHERE user_id = ?").bind(adminId).first();
      return row.must_change_password === 1;
    };

    // Wrong temporary password -> the error sits on that field.
    const wrongCurrent = await callAdmin(
      formRequest("/admin/change-password", session, {
        csrfToken: token,
        currentPassword: "not-the-temporary-password",
        newPassword: ROTATED_PASSWORD,
        confirmPassword: ROTATED_PASSWORD
      }),
      env
    );
    expect(wrongCurrent.status).toBe(401);
    expect(wrongCurrent.text).toContain('id="err-currentPassword"');
    expect(await stillPending()).toBe(true);

    // Too short -> error on newPassword.
    const tooShort = await callAdmin(
      formRequest("/admin/change-password", session, {
        csrfToken: token,
        currentPassword: ADMIN_PASSWORD,
        newPassword: "short",
        confirmPassword: "short"
      }),
      env
    );
    expect(tooShort.status).toBe(400);
    expect(tooShort.text).toContain('id="err-newPassword"');
    expect(tooShort.text).toContain("Use at least 16 characters.");
    const newPasswordInput = /<input[^>]*id="newPassword"[^>]*>/.exec(tooShort.text)?.[0] ?? "";
    expect(newPasswordInput).toContain('aria-invalid="true"');
    expect(newPasswordInput).toContain('aria-describedby="err-newPassword hint-newPassword"');
    expect(await stillPending()).toBe(true);

    // Mismatched confirmation -> error on confirmPassword.
    const mismatch = await callAdmin(
      formRequest("/admin/change-password", session, {
        csrfToken: token,
        currentPassword: ADMIN_PASSWORD,
        newPassword: ROTATED_PASSWORD,
        confirmPassword: `${ROTATED_PASSWORD}-x`
      }),
      env
    );
    expect(mismatch.status).toBe(400);
    expect(mismatch.text).toContain('id="err-confirmPassword"');
    expect(mismatch.text).toContain("The two passwords do not match.");
    expect(await stillPending()).toBe(true);

    // Reusing the temporary password -> error on newPassword.
    const unchanged = await callAdmin(
      formRequest("/admin/change-password", session, {
        csrfToken: token,
        currentPassword: ADMIN_PASSWORD,
        newPassword: ADMIN_PASSWORD,
        confirmPassword: ADMIN_PASSWORD
      }),
      env
    );
    expect(unchanged.status).toBe(400);
    expect(unchanged.text).toContain('id="err-newPassword"');
    expect(await stillPending()).toBe(true);

    // The real change clears the flag, revokes every session and signs out.
    const changed = await callAdmin(
      formRequest("/admin/change-password", session, {
        csrfToken: token,
        currentPassword: ADMIN_PASSWORD,
        newPassword: ROTATED_PASSWORD,
        confirmPassword: ROTATED_PASSWORD
      }),
      env
    );
    expect(changed.status).toBe(303);
    expect(changed.response.headers.get("Location")).toBe("/admin/login?signedout=1");
    expect(await stillPending()).toBe(false);

    // The old cookie is dead.
    const stale = await callAdmin(sessionRequest("/admin/users", session), env);
    expect(stale.status).toBe(302);
    expect(stale.response.headers.get("Location")).toBe("/admin/login");

    // The new password signs in and the console is reachable.
    const fresh = await openSession(setup, env, { password: ROTATED_PASSWORD });
    expect(fresh.ok).toBe(true);
    const reached = await callAdmin(sessionRequest("/admin/users", fresh), env);
    expect(reached.status).toBe(200);
    expect(reached.text).toContain("What you can change here");

    const audit = await setup.d1.prepare("SELECT action FROM admin_audit_log WHERE action = 'admin_password_changed'").all();
    expect(audit.results).toHaveLength(1);
  });

  it("keeps the JSON change-password path equivalent and rejects a weak password", async () => {
    const { setup, env, session, adminId } = await pendingPasswordChange();

    const weak = await callAdmin(
      sessionRequest("/v1/admin/change-password", session, {
        method: "POST",
        body: { currentPassword: ADMIN_PASSWORD, newPassword: "short" }
      }),
      env
    );
    expect(weak.status).toBe(400);
    expect(weak.json.error).toBe("invalid_request");
    expect(weak.json.fields).toContainEqual(expect.objectContaining({ field: "newPassword", reason: "too_short", min: 16 }));

    const wrong = await callAdmin(
      sessionRequest("/v1/admin/change-password", session, {
        method: "POST",
        body: { currentPassword: "wrong-password", newPassword: ROTATED_PASSWORD }
      }),
      env
    );
    expect(wrong.status).toBe(401);
    expect(wrong.json.error).toBe("invalid_credentials");

    const row = await setup.d1.prepare("SELECT must_change_password FROM admin_security WHERE user_id = ?").bind(adminId).first();
    expect(row.must_change_password).toBe(1);
  });
});

// @ts-nocheck
import { describe, expect, it } from "vitest";
import {
  ADMIN_BASE,
  ADMIN_EMAIL,
  REQUEST_ID,
  adminEnv,
  callAdmin,
  createSetup,
  fakeSettings,
  formRequest,
  formTokenFrom,
  jsonRequest,
  openSession,
  readPage,
  registerAdmin,
  seedAccounts,
  sessionRequest
} from "./adminTestHelpers";

async function withSession(extraEnv = {}) {
  const setup = createSetup();
  const adminId = await registerAdmin(setup);
  const env = adminEnv(setup, extraEnv);
  const session = await openSession(setup, env);
  expect(session.ok).toBe(true);
  return { setup, env, session, adminId };
}

describe("admin request-body limits and content types", () => {
  it("rejects an oversized body with 413 and names the endpoint limit", async () => {
    const { env, session } = await withSession();

    const oversized = await callAdmin(
      sessionRequest("/v1/admin/settings", session, {
        method: "PATCH",
        body: { registrationMode: "OPEN", pad: "x".repeat(5000) }
      }),
      env
    );
    expect(oversized.status).toBe(413);
    expect(oversized.json.error).toBe("request_too_large");
    expect(oversized.json.maxBytes).toBeGreaterThan(0);
    expect(oversized.json.maxBytes).toBeLessThan(5000);

    // The body guard is a transport check: it runs before preconditions that can
    // answer 503, so an oversized request is always 413. This env deliberately
    // has no settings service, and the same endpoint without an oversized body
    // still reports the missing dependency.
    const underProviderCap = await callAdmin(
      sessionRequest("/v1/admin/provider-keys", session, {
        method: "PUT",
        body: { slot: "AI_PROVIDER_SECRET_1", secret: "s".repeat(20000) }
      }),
      env
    );
    expect(underProviderCap.status).toBe(413);
    expect(underProviderCap.json.error).toBe("request_too_large");

    const smallBody = await callAdmin(
      sessionRequest("/v1/admin/provider-keys", session, {
        method: "PUT",
        body: { slot: "AI_PROVIDER_SECRET_1", secret: "short" }
      }),
      env
    );
    expect(smallBody.status).toBe(503);
    expect(smallBody.json.error).toBe("settings_unavailable");
  });

  it("rejects malformed JSON bodies with 400 and a machine reason", async () => {
    const { env, session } = await withSession();

    const malformed = await callAdmin(
      sessionRequest("/v1/admin/settings", session, {
        method: "PATCH",
        rawBody: '{"registrationMode": ',
        headers: { "Content-Type": "application/json" }
      }),
      env
    );
    expect(malformed.status).toBe(400);
    expect(malformed.json.error).toBe("malformed_json");

    const arrayBody = await callAdmin(
      sessionRequest("/v1/admin/settings", session, { method: "PATCH", rawBody: "[1,2,3]" }),
      env
    );
    expect(arrayBody.status).toBe(400);
    expect(arrayBody.json.error).toBe("malformed_json");
    expect(arrayBody.json.expected).toBe("object");
  });

  it("rejects an unsupported content type with 400 and lists what is supported", async () => {
    const { env, session } = await withSession();
    const response = await callAdmin(
      sessionRequest("/v1/admin/settings", session, {
        method: "PATCH",
        rawBody: "registrationMode=OPEN",
        headers: { "Content-Type": "text/plain" }
      }),
      env
    );
    expect(response.status).toBe(400);
    expect(response.json.error).toBe("unsupported_media_type");
    expect(response.json.supported).toContain("application/json");
    expect(response.json.supported).toContain("application/x-www-form-urlencoded");
  });

  it("still accepts a bodyless write request such as logout", async () => {
    const { env, session } = await withSession();
    const response = await callAdmin(
      sessionRequest("/v1/admin/logout", session, { method: "POST" }),
      env
    );
    expect(response.status).toBe(200);
    expect(response.json.ok).toBe(true);
  });
});

describe("structured per-field validation errors", () => {
  it("names the field, reason and bounds for an out-of-range quota", async () => {
    const { setup, env, session } = await withSession();
    const [reader] = await seedAccounts(setup, 1);

    const response = await callAdmin(
      sessionRequest(`/v1/admin/users/${reader.id}`, session, {
        method: "PATCH",
        body: { displayName: "Reader", status: "active", plan: "PRO", quotaLimit: 99999 }
      }),
      env
    );
    expect(response.status).toBe(400);
    expect(response.json.error).toBe("invalid_request");
    expect(response.json.fields).toContainEqual(
      expect.objectContaining({ field: "quotaLimit", reason: "out_of_range", min: 0, max: 10000 })
    );
    // Nothing was written.
    const row = await setup.d1.prepare("SELECT status FROM users WHERE id = ?").bind(reader.id).first();
    expect(row.status).toBe("active");
  });

  it.each([
    [
      "settings",
      (session, setup) => sessionRequest("/v1/admin/settings", session, { method: "PATCH", body: { registrationMode: "MAYBE" } }),
      { field: "registrationMode", reason: "invalid_enum" }
    ],
    [
      "smtp port",
      (session) =>
        sessionRequest("/v1/admin/smtp", session, {
          method: "PUT",
          body: { host: "mail.example.com", port: 70000, username: "u", from: "a@b.com", password: "p" }
        }),
      { field: "port", reason: "out_of_range", min: 1, max: 65535 }
    ],
    [
      "smtp host",
      (session) =>
        sessionRequest("/v1/admin/smtp", session, {
          method: "PUT",
          body: { host: "not a host", port: 587, username: "u", from: "a@b.com", password: "p" }
        }),
      { field: "host", reason: "invalid_format" }
    ],
    [
      "provider key slot",
      (session) => sessionRequest("/v1/admin/provider-keys", session, { method: "PUT", body: { slot: "AI_PROVIDER_SECRET_99", secret: "k" } }),
      { field: "slot", reason: "invalid_enum" }
    ],
    [
      "audio priority",
      (session) =>
        sessionRequest("/v1/admin/audio/mimo-tts", session, {
          method: "PATCH",
          body: { model: "mimo-v2.5-tts", voice: "", secretRef: "AI_PROVIDER_SECRET_1", priority: 5000, enabled: true }
        }),
      { field: "priority", reason: "out_of_range", min: 0, max: 1000 }
    ],
    [
      "password length",
      (session) => sessionRequest("/v1/admin/change-password", session, { method: "POST", body: { currentPassword: "Password12345", newPassword: "short" } }),
      { field: "newPassword", reason: "too_short", min: 16 }
    ]
  ])("returns a structured field error for %s", async (_label, build, expected) => {
    const { setup, env, session } = await withSession({ ADMIN_SETTINGS_SERVICE: fakeSettings().service });
    const response = await callAdmin(build(session, setup), env);
    expect(response.status).toBe(400);
    expect(response.json.error).toBe("invalid_request");
    expect(response.json.fields).toContainEqual(expect.objectContaining(expected));
  });

  it("keeps the domain-specific code for a refused provider host alongside the field error", async () => {
    const { env, session } = await withSession({ AI_ALLOWED_HOSTS: "api.openai.com" });
    const response = await callAdmin(
      sessionRequest("/v1/admin/providers", session, {
        method: "POST",
        body: {
          id: "evil",
          adapterType: "openai-compatible",
          displayName: "Evil",
          baseUrl: "https://api.evil.example/v1",
          model: "x",
          secretRef: "AI_PROVIDER_SECRET_1"
        }
      }),
      env
    );
    expect(response.status).toBe(400);
    expect(response.json.error).toBe("host_not_allowed");
    expect(response.json.code).toBe("host_not_allowed");
    expect(response.json.fields).toContainEqual(
      expect.objectContaining({ field: "baseUrl", reason: "host_not_allowed" })
    );
  });

  it("reports a missing required field for the recovery endpoint", async () => {
    const setup = createSetup();
    await registerAdmin(setup);
    const env = adminEnv(setup, {
      SINGLE_ADMIN_MODE: undefined,
      ADMIN_RECOVERY_SECRET: "recovery-secret-value",
      ADMIN_RECOVERY_OVERRIDE: "true"
    });
    const response = await callAdmin(
      jsonRequest("/v1/admin/recovery", {
        method: "POST",
        body: {},
        headers: { Authorization: "Bearer recovery-secret-value" }
      }),
      env
    );
    expect(response.status).toBe(400);
    expect(response.json.fields).toContainEqual(expect.objectContaining({ field: "userId", reason: "required" }));
  });
});

describe("HTML form path shares the JSON validation", () => {
  it("renders the field error next to the offending input and links to it", async () => {
    const { setup, env, session } = await withSession();
    const [reader] = await seedAccounts(setup, 1);

    const page = await readPage(setup, env, session, "/admin/users");
    expect(page.status).toBe(200);
    const token = formTokenFrom(page.text);
    expect(token).toBeTruthy();

    const submitted = await callAdmin(
      formRequest(`/admin/users/${reader.id}?limit=20&offset=0&q=`, session, {
        csrfToken: token,
        displayName: "Reader",
        status: "active",
        plan: "PRO",
        quotaLimit: "99999"
      }),
      env
    );
    expect(submitted.status).toBe(400);
    const prefix = `u-${reader.id.replace(/[^A-Za-z0-9]/g, "")}-`;
    // The error is attached to the input, not just shown somewhere on the page.
    expect(submitted.text).toContain(`id="err-${prefix}quotaLimit"`);
    expect(submitted.text).toContain('role="alert"');
    expect(submitted.text).toContain(`href="#${prefix}quotaLimit"`);
    // The input itself is wired to the message (and to its hint) for both a
    // sighted operator and a screen reader.
    const inputTag = new RegExp(`<input[^>]*id="${prefix}quotaLimit"[^>]*>`).exec(submitted.text)?.[0] ?? "";
    expect(inputTag, `quotaLimit input tag was: ${inputTag}`).toContain('aria-invalid="true"');
    expect(inputTag, `quotaLimit input tag was: ${inputTag}`).toContain(
      `aria-describedby="err-${prefix}quotaLimit hint-${prefix}quotaLimit"`
    );
    // Same human sentence as the JSON path.
    expect(submitted.text).toContain("Enter a whole number between 0 and 10000.");
    // The submitted value is preserved so the operator can correct it.
    expect(submitted.text).toContain('value="99999"');
    // Nothing was written and nothing was audited.
    const row = await setup.d1.prepare("SELECT status FROM users WHERE id = ?").bind(reader.id).first();
    expect(row.status).toBe("active");
    const audited = await setup.d1.prepare("SELECT COUNT(*) AS count FROM admin_audit_log WHERE action = 'user_updated'").first();
    expect(audited.count).toBe(0);
  });

  it("accepts an urlencoded body for the settings form and redirects with 303", async () => {
    const { setup, env, session } = await withSession({ ADMIN_SETTINGS_SERVICE: fakeSettings({ smtp: { host: "mail.example.com", port: 587, username: "u", from: "a@b.com", password: "p" } }).service });
    const page = await readPage(setup, env, session, "/admin/settings");
    const token = formTokenFrom(page.text);
    expect(token).toBeTruthy();

    const posted = await callAdmin(
      formRequest("/admin/settings", session, { csrfToken: token, registrationMode: "OPEN" }),
      env
    );
    expect(posted.status).toBe(303);
    expect(posted.response.headers.get("Location")).toBe("/admin/settings?saved=1");
    expect(await setup.adminStore.readRegistrationMode()).toBe("OPEN");
  });

  it("returns an HTML error page (not JSON) when the form body is too large", async () => {
    const { setup, env, session } = await withSession({ ADMIN_SETTINGS_SERVICE: fakeSettings().service });
    const page = await readPage(setup, env, session, "/admin/smtp");
    const token = formTokenFrom(page.text);
    const posted = await callAdmin(
      formRequest("/admin/smtp", session, {
        csrfToken: token,
        host: "mail.example.com",
        port: "587",
        username: "u",
        password: "x".repeat(20000),
        from: "a@b.com"
      }),
      env
    );
    expect(posted.status).toBe(413);
    // An operator gets the page back, not a JSON blob.
    expect(posted.response.headers.get("Content-Type")).toContain("text/html");
    expect(posted.text.startsWith("<!doctype html>")).toBe(true);
    expect(posted.text).toContain("Email delivery");
    expect(posted.text).not.toContain('"error":"request_too_large"');
    // The message says the request was too large and names the endpoint limit.
    expect(posted.text).toContain("too large");
    expect(posted.text).toContain("16384 byte limit");
    // The submitted password is not echoed back into the re-rendered form.
    expect(posted.text).not.toContain("x".repeat(20000));
    expect(posted.text).not.toContain('value="' + "x".repeat(40));
  });

  it("rejects a form post whose CSRF field is missing", async () => {
    const { env, session } = await withSession({ ADMIN_SETTINGS_SERVICE: fakeSettings().service });
    const posted = await callAdmin(
      formRequest("/admin/settings", session, { registrationMode: "OPEN" }),
      env
    );
    expect(posted.status).toBe(403);
  });

  it("never echoes the submitted secret back into the form", async () => {
    const setup = createSetup();
    await registerAdmin(setup);
    const env = adminEnv(setup, { ADMIN_SETTINGS_SERVICE: fakeSettings().service });
    const session = await openSession(setup, env);
    const page = await readPage(setup, env, session, "/admin/smtp");
    const token = formTokenFrom(page.text);
    const sentinel = "SENTINEL-SMTP-PASSWORD-abcdef";

    // Missing host -> 400 with the password field also present in the body.
    const posted = await callAdmin(
      formRequest("/admin/smtp", session, { csrfToken: token, host: "", port: "587", username: "u", password: sentinel, from: "a@b.com" }),
      env
    );
    expect(posted.status).toBe(400);
    expect(posted.text).not.toContain(sentinel);
  });
});

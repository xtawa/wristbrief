// @ts-nocheck
import { describe, expect, it } from "vitest";
import {
  ADMIN_EMAIL,
  ADMIN_PASSWORD,
  adminEnv,
  callAdmin,
  createSetup,
  fakeSettings,
  formRequest,
  formTokenFrom,
  openSession,
  readPage,
  registerAdmin,
  sessionRequest
} from "./adminTestHelpers";

const SMTP_SETTINGS = { host: "mail.example.com", port: 587, secure: false, username: "sender", from: "sender@example.com" };

async function smtpFixture(options = {}) {
  const setup = createSetup();
  await registerAdmin(setup);
  const fake = fakeSettings(options);
  const env = adminEnv(setup, { ADMIN_SETTINGS_SERVICE: fake.service });
  const session = await openSession(setup, env);
  expect(session.ok).toBe(true);
  return { setup, env, session, fake };
}

async function saveSmtp(env, session, password = "smtp-private-password") {
  return callAdmin(
    sessionRequest("/v1/admin/smtp", session, {
      method: "PUT",
      body: { ...SMTP_SETTINGS, password }
    }),
    env
  );
}

describe("SMTP test send", () => {
  it("reports not_configured instead of pretending to send", async () => {
    const { setup, env, session, fake } = await smtpFixture();
    const response = await callAdmin(
      sessionRequest("/v1/admin/smtp/test", session, { method: "POST", body: {} }),
      env
    );
    expect(response.status).toBe(409);
    expect(response.json.result).toBe("not_configured");
    expect(response.json.error).toBe("smtp_not_configured");
    expect(fake.state.sent).toHaveLength(0);

    const history = await setup.d1.prepare("SELECT status FROM admin_smtp_tests").all();
    expect(history.results.map((row) => row.status)).toEqual(["not_configured"]);
    const audit = await setup.d1.prepare("SELECT action FROM admin_audit_log").all();
    expect(audit.results.map((row) => row.action)).toContain("smtp_test_not_configured");
  });

  it("sends a real message through the saved settings and audits only the outcome", async () => {
    const { setup, env, session, fake } = await smtpFixture();
    const saved = await saveSmtp(env, session);
    expect(saved.status).toBe(200);
    expect(saved.json.ok).toBe(true);
    // The read-back never contains the password, only whether one is stored.
    expect(saved.json.status.passwordConfigured).toBe(true);
    expect(JSON.stringify(saved.json)).not.toContain("smtp-private-password");

    const sent = await callAdmin(
      sessionRequest("/v1/admin/smtp/test", session, { method: "POST", body: { recipient: ADMIN_EMAIL } }),
      env
    );
    expect(sent.status).toBe(200);
    expect(sent.json.result).toBe("sent");
    expect(sent.json.recipient).toBe(ADMIN_EMAIL);
    expect(fake.state.sent).toHaveLength(1);
    expect(fake.state.sent[0].to).toBe(ADMIN_EMAIL);
    expect(fake.state.sent[0].text).not.toContain("smtp-private-password");
    expect(fake.state.sent[0].text).not.toContain(SMTP_SETTINGS.username);

    const audit = await setup.d1.prepare("SELECT action, after_json FROM admin_audit_log WHERE action LIKE 'smtp_test%'").all();
    expect(audit.results.map((row) => row.action)).toContain("smtp_test_sent");
    expect(JSON.stringify(audit.results)).not.toContain("smtp-private-password");
    expect(JSON.stringify(audit.results)).not.toContain(SMTP_SETTINGS.username);
  });

  it("defaults the recipient to the administrator's own address", async () => {
    const { env, session, fake } = await smtpFixture();
    await saveSmtp(env, session);
    const sent = await callAdmin(sessionRequest("/v1/admin/smtp/test", session, { method: "POST", body: {} }), env);
    expect(sent.status).toBe(200);
    expect(fake.state.sent[0].to).toBe(ADMIN_EMAIL);
  });

  it("reports a send failure with the transport error class and never a fake success", async () => {
    const failure = Object.assign(new Error("Invalid login: 535 authentication failed for sender"), { code: "EAUTH" });
    const { setup, env, session, fake } = await smtpFixture({ failWith: failure });
    await saveSmtp(env, session);

    const response = await callAdmin(sessionRequest("/v1/admin/smtp/test", session, { method: "POST", body: {} }), env);
    expect(response.status).toBe(502);
    expect(response.json.result).toBe("send_failed");
    expect(response.json.errorClass).toBe("EAUTH");
    expect(response.json.error).toBe("smtp_send_failed");
    // The driver message (which can name the account) is not returned. ISO timestamps such as
    // checkedAt can legitimately contain "535" in their milliseconds, so strip them before the
    // reply-code check instead of letting the wall clock decide the result.
    const withoutIsoTimestamps = (value: string) => value.replace(/\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d+)?Z/g, "<ts>");
    expect(JSON.stringify(response.json)).not.toContain("Invalid login");
    expect(withoutIsoTimestamps(JSON.stringify(response.json))).not.toContain("535");
    expect(JSON.stringify(response.json)).not.toContain("authentication failed");
    expect(fake.state.sent).toHaveLength(0);

    const row = await setup.d1.prepare("SELECT status, error_class FROM admin_smtp_tests").first();
    expect(row).toMatchObject({ status: "send_failed", error_class: "EAUTH" });
    const audit = await setup.d1.prepare("SELECT after_json FROM admin_audit_log WHERE action = 'smtp_test_failed'").first();
    expect(audit.after_json).toContain("EAUTH");
    expect(audit.after_json).not.toContain("Invalid login");
    expect(withoutIsoTimestamps(audit.after_json)).not.toContain("535");
  });

  it("falls back to a generic error class when the driver exposes no code", async () => {
    const { env, session } = await smtpFixture({ failWith: new Error("boom") });
    await saveSmtp(env, session);
    const response = await callAdmin(sessionRequest("/v1/admin/smtp/test", session, { method: "POST", body: {} }), env);
    expect(response.status).toBe(502);
    expect(response.json.errorClass).toBe("ERROR");
  });

  it("refuses an arbitrary recipient unless the deployment allowlists it", async () => {
    const { env, session, fake } = await smtpFixture();
    await saveSmtp(env, session);

    const refused = await callAdmin(
      sessionRequest("/v1/admin/smtp/test", session, { method: "POST", body: { recipient: "attacker@evil.example" } }),
      env
    );
    expect(refused.status).toBe(400);
    expect(refused.json.fields).toContainEqual(
      expect.objectContaining({ field: "recipient", reason: "not_allowed" })
    );
    expect(fake.state.sent).toHaveLength(0);

    const malformed = await callAdmin(
      sessionRequest("/v1/admin/smtp/test", session, { method: "POST", body: { recipient: "not-an-address" } }),
      env
    );
    expect(malformed.status).toBe(400);
    expect(malformed.json.fields).toContainEqual(expect.objectContaining({ field: "recipient", reason: "invalid_format" }));
    expect(fake.state.sent).toHaveLength(0);
  });

  it("allows an extra recipient named by the deployment", async () => {
    const setup = createSetup();
    await registerAdmin(setup);
    const fake = fakeSettings();
    const env = adminEnv(setup, { ADMIN_SETTINGS_SERVICE: fake.service, ADMIN_SMTP_TEST_RECIPIENTS: "ops@example.com" });
    const session = await openSession(setup, env);
    await saveSmtp(env, session);

    const response = await callAdmin(
      sessionRequest("/v1/admin/smtp/test", session, { method: "POST", body: { recipient: "ops@example.com" } }),
      env
    );
    expect(response.status).toBe(200);
    expect(fake.state.sent[0].to).toBe("ops@example.com");
  });

  it("rate-limits repeated test sends per administrator", async () => {
    const { setup, env, session, fake } = await smtpFixture();
    await saveSmtp(env, session);
    for (let attempt = 0; attempt < 3; attempt += 1) {
      const sent = await callAdmin(sessionRequest("/v1/admin/smtp/test", session, { method: "POST", body: {} }), env);
      expect(sent.status).toBe(200);
    }
    const limited = await callAdmin(sessionRequest("/v1/admin/smtp/test", session, { method: "POST", body: {} }), env);
    expect(limited.status).toBe(429);
    expect(limited.json.error).toBe("rate_limited");
    expect(limited.json.retryAfterSeconds).toBeGreaterThan(0);
    expect(fake.state.sent).toHaveLength(3);
    expect(
      (await setup.d1.prepare("SELECT COUNT(*) AS count FROM admin_smtp_tests").first()).count
    ).toBe(3);
  });

  it("drives the whole flow from the no-JavaScript form on the page", async () => {
    const { setup, env, session, fake } = await smtpFixture();
    const page = await readPage(setup, env, session, "/admin/smtp");
    expect(page.status).toBe(200);
    const token = formTokenFrom(page.text);
    expect(page.text).toContain('<form method="post" action="/admin/smtp">');
    expect(page.text).toContain('<form method="post" action="/admin/smtp/test">');
    expect(page.text).toContain("No SMTP settings are saved yet");

    const savedForm = await callAdmin(
      formRequest("/admin/smtp", session, {
        csrfToken: token,
        host: SMTP_SETTINGS.host,
        port: String(SMTP_SETTINGS.port),
        username: SMTP_SETTINGS.username,
        password: "form-private-password",
        from: SMTP_SETTINGS.from
      }),
      env
    );
    expect(savedForm.status).toBe(303);
    expect(savedForm.response.headers.get("Location")).toBe("/admin/smtp?saved=1");

    const testForm = await callAdmin(
      formRequest("/admin/smtp/test", session, { csrfToken: token, recipient: ADMIN_EMAIL }),
      env
    );
    expect(testForm.status).toBe(303);
    expect(testForm.response.headers.get("Location")).toContain("test=sent");
    expect(fake.state.sent).toHaveLength(1);

    // A refused recipient re-renders the page with the error beside that field.
    const refused = await callAdmin(
      formRequest("/admin/smtp/test", session, { csrfToken: token, recipient: "attacker@evil.example" }),
      env
    );
    expect(refused.status).toBe(400);
    expect(refused.text).toContain('id="err-recipient"');
    const recipientInput = /<input[^>]*id="recipient"[^>]*>/.exec(refused.text)?.[0] ?? "";
    expect(recipientInput, `recipient input tag was: ${recipientInput}`).toContain('aria-invalid="true"');
    expect(recipientInput, `recipient input tag was: ${recipientInput}`).toContain(
      'aria-describedby="err-recipient hint-recipient"'
    );
    // The submitted value is preserved so the operator can correct it.
    expect(recipientInput).toContain('value="attacker@evil.example"');
  });

  it("returns an error page when the deployment has no settings service", async () => {
    const setup = createSetup();
    await registerAdmin(setup);
    const env = adminEnv(setup);
    const session = await openSession(setup, env);
    const response = await callAdmin(sessionRequest("/v1/admin/smtp/test", session, { method: "POST", body: {} }), env);
    expect(response.status).toBe(503);
    expect(response.json.error).toBe("settings_unavailable");
  });

  it("requires the admin password to have been rotated before the test send is reachable", async () => {
    const setup = createSetup();
    const adminId = await registerAdmin(setup);
    const fake = fakeSettings();
    const env = adminEnv(setup, { ADMIN_SETTINGS_SERVICE: fake.service });
    const session = await openSession(setup, env);
    await setup.d1.prepare("INSERT INTO admin_security (user_id, must_change_password) VALUES (?, 1)").bind(adminId).run();

    const blocked = await callAdmin(sessionRequest("/v1/admin/smtp/test", session, { method: "POST", body: {} }), env);
    expect(blocked.status).toBe(403);
    expect(blocked.json.error).toBe("password_change_required");
  });
});

describe("SMTP password handling", () => {
  it("keeps a blank password field from clearing the saved credential", async () => {
    const { env, session, fake } = await smtpFixture();
    await saveSmtp(env, session, "first-private-password");
    const second = await callAdmin(
      sessionRequest("/v1/admin/smtp", session, {
        method: "PUT",
        body: { ...SMTP_SETTINGS, host: "mail2.example.com", password: "" }
      }),
      env
    );
    expect(second.status).toBe(200);
    expect(fake.state.smtp.password).toBe("first-private-password");
    expect(fake.state.smtp.host).toBe("mail2.example.com");
  });

  it("requires a password the first time settings are saved", async () => {
    const { env, session } = await smtpFixture();
    const response = await callAdmin(
      sessionRequest("/v1/admin/smtp", session, { method: "PUT", body: { ...SMTP_SETTINGS, password: "" } }),
      env
    );
    expect(response.status).toBe(400);
    expect(response.json.fields).toContainEqual(expect.objectContaining({ field: "password", reason: "required" }));
  });

  it("rejects a password that exceeds the field limit", async () => {
    const { env, session } = await smtpFixture();
    const response = await callAdmin(
      sessionRequest("/v1/admin/smtp", session, { method: "PUT", body: { ...SMTP_SETTINGS, password: "x".repeat(5000) } }),
      env
    );
    expect(response.status).toBe(400);
    expect(response.json.fields).toContainEqual(expect.objectContaining({ field: "password", reason: "too_long" }));
  });

  it("still works when the admin password itself is never revealed", async () => {
    // Guard rail: the credential store holds an Argon2id hash, never the password.
    const setup = createSetup();
    await registerAdmin(setup);
    const row = await setup.d1.prepare("SELECT password_hash FROM email_credentials").first();
    expect(row.password_hash).not.toContain(ADMIN_PASSWORD);
    expect(row.password_hash.startsWith("$argon2")).toBe(true);
  });
});

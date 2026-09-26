// @ts-nocheck
/**
 * The hard invariant: secrets never leave the server through a read path.
 *
 * A distinctive sentinel is written into every credential an administrator can
 * set (SMTP password, provider API key, the per-preset audio key, the provider
 * secret slot) and into the administrator's own password. Every HTML page, every
 * JSON read response, the audit trail, the SMTP test history and the connectivity
 * rows are then searched for it. Session and CSRF tokens are checked the same way.
 */
import { describe, expect, it } from "vitest";
import {
  ADMIN_EMAIL,
  adminEnv,
  callAdmin,
  createSetup,
  fakeSettings,
  jsonRequest,
  openSession,
  readPage,
  registerAdmin,
  seedAccounts,
  sessionRequest
} from "./adminTestHelpers";

const SMTP_PASSWORD = "SENTINEL-SMTP-PASSWORD-9f2c";
const PROVIDER_KEY = "SENTINEL-PROVIDER-KEY-4b71";
const AI_SLOT_SECRET = "SENTINEL-AI-SLOT-SECRET-77aa";
const AUDIO_SLOT_SECRET = "SENTINEL-AUDIO-SLOT-SECRET-31cd";
const ADMIN_PASSWORD = "Sentinel-Admin-Password-2026";
const SENTINELS = [SMTP_PASSWORD, PROVIDER_KEY, AI_SLOT_SECRET, AUDIO_SLOT_SECRET, ADMIN_PASSWORD];

const READ_PAGES = [
  "/admin",
  "/admin/overview",
  "/admin/users",
  "/admin/smtp",
  "/admin/provider-keys",
  "/admin/audio",
  "/admin/settings",
  "/admin/providers",
  "/admin/audit",
  "/admin/change-password",
  "/admin/login",
  "/admin/does-not-exist"
];

const READ_JSON = [
  "/v1/admin/bootstrap-status",
  "/v1/admin/settings",
  "/v1/admin/users",
  "/v1/admin/providers",
  "/v1/admin/audio",
  "/v1/admin/overview",
  "/v1/admin/audit"
];

async function writeEverythingUnderTest() {
  const setup = createSetup();
  const adminId = await registerAdmin(setup, ADMIN_EMAIL, ADMIN_PASSWORD);
  const readers = await seedAccounts(setup, 2, { displayName: "Reader" });
  const fake = fakeSettings();
  const env = adminEnv(setup, {
    ADMIN_SETTINGS_SERVICE: fake.service,
    AI_ALLOWED_HOSTS: "api.openai.com",
    AI_PROVIDER_SECRET_1: AUDIO_SLOT_SECRET,
    AI_PROVIDER_SECRET_2: AI_SLOT_SECRET,
    ADMIN_FETCH: async () => new Response("{}", { status: 200, headers: { "Content-Type": "application/json" } })
  });
  const session = await openSession(setup, env, { password: ADMIN_PASSWORD });
  expect(session.ok).toBe(true);

  // 1. SMTP credential.
  const smtp = await callAdmin(
    sessionRequest("/v1/admin/smtp", session, {
      method: "PUT",
      body: { host: "mail.example.com", port: 587, secure: false, username: "sender", password: SMTP_PASSWORD, from: "sender@example.com" }
    }),
    env
  );
  expect(smtp.status).toBe(200);

  // 2. Provider API key.
  const key = await callAdmin(
    sessionRequest("/v1/admin/provider-keys", session, { method: "PUT", body: { slot: "AI_PROVIDER_SECRET_3", secret: PROVIDER_KEY } }),
    env
  );
  expect(key.status).toBe(200);

  // 3. AI provider pointing at the sentinel-filled slot.
  const provider = await callAdmin(
    sessionRequest("/v1/admin/providers", session, {
      method: "POST",
      body: {
        id: "sentinel-provider",
        adapterType: "openai-compatible",
        displayName: "Sentinel",
        baseUrl: "https://api.openai.com/v1",
        model: "gpt-5-mini",
        secretRef: "AI_PROVIDER_SECRET_2"
      }
    }),
    env
  );
  expect(provider.status).toBe(201);

  // 4. SMTP test send (attempts a real delivery through the fake transport).
  const test = await callAdmin(sessionRequest("/v1/admin/smtp/test", session, { method: "POST", body: {} }), env);
  expect(test.status).toBe(200);

  // 5. Audio preset connectivity check (uses the sentinel-filled slot).
  await callAdmin(
    sessionRequest("/v1/admin/audio/mimo-tts", session, {
      method: "PATCH",
      body: { model: "mimo-v2.5-tts", voice: "mimo_default", secretRef: "AI_PROVIDER_SECRET_1", enabled: true, priority: 10 }
    }),
    env
  );
  await callAdmin(sessionRequest("/v1/admin/audio/mimo-tts/check", session, { method: "POST", body: {} }), env);

  // 6. An ordinary membership edit, so the audit trail has a user entry too.
  await callAdmin(
    sessionRequest(`/v1/admin/users/${readers[0].id}`, session, {
      method: "PATCH",
      body: { displayName: "Renamed", status: "disabled", plan: "PRO", quotaLimit: 33 }
    }),
    env
  );

  return { setup, env, session, fake, adminId, readers };
}

describe("secret redaction across every admin read path", () => {
  it("never echoes a credential sentinel in a page, an API read, an audit row or a history row", async () => {
    const { setup, env, session, fake, readers } = await writeEverythingUnderTest();
    expect(fake.state.smtp.password).toBe(SMTP_PASSWORD);
    expect(fake.state.secrets.AI_PROVIDER_SECRET_3).toBe(PROVIDER_KEY);
    expect(fake.state.sent).toHaveLength(1);
    // The delivered test message carries no credential at all.
    for (const sentinel of SENTINELS) expect(fake.state.sent[0].text).not.toContain(sentinel);

    const collected = [];
    for (const path of READ_PAGES) {
      const page = await readPage(setup, env, session, path);
      collected.push({ path, text: page.text });
    }
    for (const path of READ_JSON) {
      const read = await callAdmin(sessionRequest(path, session), env);
      collected.push({ path, text: read.text });
    }
    expect(collected.length).toBe(READ_PAGES.length + READ_JSON.length);
    for (const entry of collected) {
      for (const sentinel of SENTINELS) {
        expect(
          entry.text.includes(sentinel),
          `${entry.path} leaked a credential sentinel`
        ).toBe(false);
      }
    }

    // Nothing secret reached the audit trail.
    const audit = await setup.d1.prepare("SELECT * FROM admin_audit_log").all();
    expect(audit.results.length).toBeGreaterThan(4);
    const auditDump = JSON.stringify(audit.results);
    for (const sentinel of SENTINELS) expect(auditDump).not.toContain(sentinel);
    // The audit trail does record that a change happened, and for which target.
    expect(audit.results.map((row) => row.action)).toEqual(
      expect.arrayContaining(["smtp_updated", "provider_secret_updated", "provider_created", "smtp_test_sent", "audio_provider_updated", "user_updated"])
    );
    expect(audit.results.find((row) => row.action === "provider_secret_updated").target_id).toBe("AI_PROVIDER_SECRET_3");

    // Nor the SMTP history, the connectivity rows, or the session rows.
    const smtpRows = await setup.d1.prepare("SELECT * FROM admin_smtp_tests").all();
    expect(JSON.stringify(smtpRows.results)).not.toContain(SMTP_PASSWORD);
    const checks = await setup.d1.prepare("SELECT * FROM admin_connectivity_checks").all();
    expect(checks.results.length).toBeGreaterThan(0);
    for (const sentinel of SENTINELS) expect(JSON.stringify(checks.results)).not.toContain(sentinel);
    const sessions = await setup.d1.prepare("SELECT * FROM admin_web_sessions").all();
    for (const sentinel of SENTINELS) expect(JSON.stringify(sessions.results)).not.toContain(sentinel);

    // The stored password hash is a hash: it does not contain the password.
    const credential = await setup.d1.prepare("SELECT password_hash FROM email_credentials WHERE normalized_email = ?").bind(ADMIN_EMAIL).first();
    expect(credential.password_hash).not.toContain(ADMIN_PASSWORD);
    expect(credential.password_hash.startsWith("$argon2")).toBe(true);

    // The only thing an operator may learn about a slot is that it is set.
    const providerList = await callAdmin(sessionRequest("/v1/admin/providers", session), env);
    expect(providerList.json.providers.find((row) => row.id === "sentinel-provider").secretConfigured).toBe(true);
    expect(providerList.json.secretSlots).toContainEqual({ slot: "AI_PROVIDER_SECRET_2", configured: true });

    // A disabled account keeps its membership edit; nothing secret is involved.
    const updated = await setup.d1.prepare("SELECT status FROM users WHERE id = ?").bind(readers[0].id).first();
    expect(updated.status).toBe("disabled");
  });

  it("never echoes the session token or the CSRF token into a page body", async () => {
    const { setup, env, session } = await writeEverythingUnderTest();
    const sessionToken = session.cookie.split("=")[1];
    expect(session.csrfToken).toBeTruthy();

    for (const path of READ_PAGES) {
      const page = await readPage(setup, env, session, path);
      expect(page.text.includes(session.csrfToken), `${path} leaked the CSRF token`).toBe(false);
      expect(page.text.includes(sessionToken), `${path} leaked the session token`).toBe(false);
    }

    // The form posts carry a session-bound derivation, never the CSRF token.
    const users = await readPage(setup, env, session, "/admin/users");
    const formToken = /<input type="hidden" name="csrfToken" value="([^"]+)">/.exec(users.text)?.[1];
    expect(formToken).toBeTruthy();
    expect(formToken).not.toBe(session.csrfToken);
    expect(session.csrfToken.startsWith(formToken.slice(0, 8))).toBe(false);

    // And that derived token cannot be used as an X-CSRF-Token header.
    const misuse = await callAdmin(
      jsonRequest("/v1/admin/settings", {
        method: "PATCH",
        body: { registrationMode: "OPEN" },
        headers: { Cookie: session.cookie, "X-CSRF-Token": formToken }
      }),
      env
    );
    expect(misuse.status).toBe(403);
    expect(misuse.json.error).toBe("csrf_required");
  });

  it("does not leak a password typed into a failed form submission", async () => {
    const setup = createSetup();
    await registerAdmin(setup, ADMIN_EMAIL, ADMIN_PASSWORD);
    const fake = fakeSettings({ smtp: { host: "mail.example.com", port: 587, username: "sender", from: "sender@example.com", password: "kept-password" } });
    const env = adminEnv(setup, { ADMIN_SETTINGS_SERVICE: fake.service });
    const session = await openSession(setup, env, { password: ADMIN_PASSWORD });

    const page = await readPage(setup, env, session, "/admin/smtp");
    const token = /<input type="hidden" name="csrfToken" value="([^"]+)">/.exec(page.text)?.[1];
    const typed = "SENTINEL-TYPED-INTO-FORM";
    const posted = await callAdmin(
      jsonRequest("/admin/smtp", {
        method: "POST",
        rawBody: new URLSearchParams({
          csrfToken: token,
          host: "bad host",
          port: "587",
          username: "sender",
          password: typed,
          from: "sender@example.com"
        }).toString(),
        headers: { Cookie: session.cookie, "Content-Type": "application/x-www-form-urlencoded" }
      }),
      env
    );
    expect(posted.status).toBe(400);
    expect(posted.text).toContain("Enter a valid hostname.");
    expect(posted.text).not.toContain(typed);
    expect(posted.text).not.toContain("kept-password");
  });
});

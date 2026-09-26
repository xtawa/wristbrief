// @ts-nocheck
/**
 * Shared fixtures for the admin console suites.
 *
 * Sessions go through the real D1 stores, so the tests exercise the same cookie,
 * CSRF and audit paths as the running server. Each request gets its own client IP
 * prefix so the per-IP rate limiters do not leak between tests.
 */
import { handleAdminRoute } from "./adminRoutes";
import { createEmailAuthTestSetup } from "../emailAuth/emailAuthTestSetup";

export const ADMIN_BASE = "https://gateway.test";
export const REQUEST_ID = "req-admin-console-test";
export const ADMIN_EMAIL = "admin@example.com";
export const ADMIN_PASSWORD = "Password12345";
export const USER_PASSWORD = "Password123456";

let ipCounter = 0;

export function nextIp(): string {
  ipCounter += 1;
  return `10.0.${ipCounter}.7`;
}

export function adminEnv(setup, extra = {}) {
  return {
    ACCOUNT_DB: setup.d1,
    SINGLE_ADMIN_MODE: true,
    FREE_AI_MONTHLY_LIMIT: "10",
    PRO_AI_MONTHLY_LIMIT: "100",
    ...extra
  };
}

/** Registers the bootstrap administrator. The first registration wins the CAS. */
export async function registerAdmin(setup, email = ADMIN_EMAIL, password = ADMIN_PASSWORD) {
  const registered = await setup.service.register(email, password);
  return registered?.body?.user?.id ?? null;
}

/** Opens registration and creates `count` ordinary accounts. */
export async function seedAccounts(setup, count, options = {}) {
  await setup.adminStore.writeRegistrationMode("OPEN", null);
  const ids = [];
  for (let index = 0; index < count; index += 1) {
    const email = `${options.prefix ?? "reader"}${String(index).padStart(3, "0")}@example.com`;
    const registered = await setup.service.register(email, USER_PASSWORD);
    const id = registered?.body?.user?.id;
    if (!id) throw new Error(`seed registration failed for ${email}`);
    ids.push({ id, email });
    if (options.displayName) {
      await setup.d1
        .prepare("UPDATE identities SET display_name = ? WHERE user_id = ?")
        .bind(`${options.displayName} ${index}`, id)
        .run();
    }
  }
  return ids;
}

/**
 * Fast account seeding for list/pagination tests.
 *
 * Registering 25 accounts would run 25 Argon2id hashes (and can push a test past
 * its timeout), and it produces ties on `created_at`, which makes the page window
 * non-deterministic. These rows are inserted directly with distinct timestamps so
 * a page window can be asserted exactly and the query under test is still the
 * real SQL.
 */
export async function seedSqlAccounts(setup, count, options = {}) {
  const ids = [];
  const base = Date.parse("2026-01-01T00:00:00.000Z");
  for (let index = 0; index < count; index += 1) {
    const id = `usr_seed_${String(index).padStart(3, "0")}`;
    const email = `${options.prefix ?? "reader"}${String(index).padStart(3, "0")}@example.com`;
    const displayName = options.displayName ? `${options.displayName} ${index}` : null;
    const createdAt = new Date(base + index * 1000).toISOString();
    await setup.d1
      .prepare("INSERT INTO users (id, status, created_at, updated_at) VALUES (?, 'active', ?, ?)")
      .bind(id, createdAt, createdAt)
      .run();
    await setup.d1
      .prepare("INSERT INTO identities (provider, provider_subject, user_id, email, display_name) VALUES ('google', ?, ?, ?, ?)")
      .bind(`google-${index}`, id, email, displayName)
      .run();
    ids.push({ id, email });
  }
  return ids;
}

export async function openSession(setup, env = adminEnv(setup), credentials = {}) {
  const response = await handleAdminRoute(
    new Request(`${ADMIN_BASE}/v1/admin/session`, {
      method: "POST",
      headers: { "Content-Type": "application/json", Origin: ADMIN_BASE, "CF-Connecting-IP": nextIp() },
      body: JSON.stringify({
        email: credentials.email ?? ADMIN_EMAIL,
        password: credentials.password ?? ADMIN_PASSWORD
      })
    }),
    env,
    REQUEST_ID
  );
  if (response.status !== 200) return { ok: false, status: response.status, body: await response.json() };
  const cookies = response.headers.getSetCookie?.() ?? [];
  const sessionCookie = cookies.find((cookie) => cookie.startsWith("__Host-wristbrief_admin="));
  const csrfCookie = cookies.find((cookie) => cookie.startsWith("wristbrief_admin_csrf="));
  const body = await response.json();
  return {
    ok: true,
    cookie: sessionCookie.split(";")[0],
    csrfCookie: csrfCookie.split(";")[0],
    csrfToken: body.csrfToken,
    expiresAt: body.expiresAt
  };
}

/** Reads the derived form token out of a rendered page's hidden field. */
export function formTokenFrom(html) {
  const match = /<input type="hidden" name="csrfToken" value="([^"]+)">/.exec(html);
  return match ? match[1] : null;
}

export function jsonRequest(path, options = {}) {
  const headers = {
    Origin: ADMIN_BASE,
    "CF-Connecting-IP": nextIp(),
    ...(options.headers ?? {})
  };
  if (options.body !== undefined || typeof options.rawBody === "string") {
    headers["Content-Type"] = headers["Content-Type"] ?? "application/json";
  }
  return new Request(`${ADMIN_BASE}${path}`, {
    method: options.method ?? "GET",
    headers,
    body: options.rawBody !== undefined ? options.rawBody : options.body !== undefined ? JSON.stringify(options.body) : undefined
  });
}

export function sessionRequest(path, session, options = {}) {
  return jsonRequest(path, {
    ...options,
    headers: {
      Cookie: session.cookie,
      "X-CSRF-Token": session.csrfToken,
      ...(options.headers ?? {})
    }
  });
}

export function formRequest(path, session, fields, options = {}) {
  return jsonRequest(path, {
    method: options.method ?? "POST",
    headers: {
      Cookie: session.cookie,
      "Content-Type": "application/x-www-form-urlencoded",
      ...(options.headers ?? {})
    },
    rawBody: new URLSearchParams(fields).toString()
  });
}

export async function callAdmin(request, env, id = REQUEST_ID) {
  const response = await handleAdminRoute(request, env, id);
  const text = await response.text();
  let json = null;
  try {
    json = JSON.parse(text);
  } catch {
    json = null;
  }
  return { response, status: response.status, text, json };
}

/**
 * A request shaped exactly like the one a browser sends for a form.
 *
 * Real browsers POST admin forms with `Origin: null` (the opaque origin a
 * `no-referrer`/sandboxed document serializes to) and often with no
 * `Sec-Fetch-Site` at all, which the JSON-shaped helpers above can never
 * reproduce because they always set a concrete Origin. Omit `origin` to send the
 * literal opaque value (the browser default), pass `origin: null` to omit the
 * header entirely, or pass a URL string to send that specific origin.
 */
export function browserRequest(path, options = {}) {
  const headers = { "CF-Connecting-IP": nextIp() };
  if (!("origin" in options)) headers.Origin = "null";
  else if (options.origin !== null) headers.Origin = options.origin;
  if (options.cookie) headers.Cookie = options.cookie;
  if (options.secFetchSite) headers["Sec-Fetch-Site"] = options.secFetchSite;
  const hasForm = options.form !== undefined;
  if (hasForm) headers["Content-Type"] = "application/x-www-form-urlencoded";
  return new Request(`${ADMIN_BASE}${path}`, {
    method: options.method ?? (hasForm ? "POST" : "GET"),
    headers,
    body: hasForm ? new URLSearchParams(options.form).toString() : undefined
  });
}

/** `name=value` cookies a response asked the browser to store. */
export function setCookies(response) {
  return (response.headers.getSetCookie?.() ?? []).map((cookie) => cookie.split(";")[0]);
}

export function cookieHeader(cookies) {
  return cookies.join("; ");
}

/** Reads one named cookie out of a `Cookie`-style list. */
export function cookieValue(cookies, name) {
  for (const cookie of cookies) {
    const [key, ...rest] = cookie.split("=");
    if (key === name) return rest.join("=");
  }
  return null;
}

export async function readPage(setup, env, session, path) {
  return callAdmin(jsonRequest(path, { headers: { Cookie: session.cookie } }), env);
}

/**
 * A settings service double. The real implementation is ServerSettings in
 * server/settings.ts; this mirrors its contract (including the boolean
 * save return) so the console logic is what is under test.
 */
export function fakeSettings(options = {}) {
  const state = {
    smtp: options.smtp ?? null,
    secrets: {},
    sent: [],
    failWith: options.failWith ?? null,
    rejectSave: options.rejectSave ?? false
  };
  return {
    state,
    service: {
      smtpStatus() {
        if (!state.smtp) return { configured: false };
        return {
          configured: true,
          host: state.smtp.host,
          port: state.smtp.port,
          secure: state.smtp.secure === true,
          username: state.smtp.username,
          from: state.smtp.from,
          passwordConfigured: Boolean(state.smtp.password)
        };
      },
      async saveSmtp(value) {
        if (state.rejectSave) return false;
        const input = value ?? {};
        const next = { ...(state.smtp ?? {}), ...input };
        // Mirrors ServerSettings: a blank password keeps the stored one.
        if (!input.password) next.password = state.smtp?.password;
        state.smtp = next;
        return true;
      },
      async setProviderSecret(slot, secret) {
        state.secrets[slot] = secret;
        return true;
      },
      async send(email) {
        if (state.failWith) throw state.failWith;
        state.sent.push(email);
      }
    }
  };
}

export function createSetup() {
  return createEmailAuthTestSetup();
}

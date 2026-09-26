// @ts-nocheck
/**
 * Requests shaped the way a real browser sends them.
 *
 * Every other suite calls the handlers with an explicit
 * `Origin: http://gateway.test`, so it structurally cannot notice that a real
 * browser submits admin forms with `Origin: null` — the opaque origin a document
 * with a `no-referrer` referrer policy serializes to. That shipped a P0: reads
 * worked, and every human form submission (sign-in, forced password change, and
 * therefore every mutation) was rejected before reaching a handler.
 *
 * These tests pin the browser-shaped request contract:
 *  - `Origin: null` is treated as "no usable origin" and judged on the CSRF token;
 *  - an absent Origin is likewise accepted;
 *  - a present-but-different Origin is still refused;
 *  - `Sec-Fetch-Site: cross-site` is still refused;
 *  - the anonymous sign-in form is protected by its own double-submit cookie,
 *    because it has no session token to check;
 *  - a full sign-in -> forced password change round trip works end to end.
 */
import { describe, expect, it } from "vitest";
import { verifySameOrigin } from "./adminCsrf";
import {
  ADMIN_EMAIL,
  ADMIN_PASSWORD,
  REQUEST_ID,
  adminEnv,
  browserRequest,
  callAdmin,
  cookieHeader,
  cookieValue,
  createSetup,
  formTokenFrom,
  openSession,
  registerAdmin
} from "./adminTestHelpers";

const ROTATED_PASSWORD = "Rotated-Admin-Password-2026";
const LOGIN_CSRF_COOKIE = "wristbrief_admin_login_csrf";

async function adminWithForcedChange() {
  const setup = createSetup();
  const adminId = await registerAdmin(setup);
  await setup.d1
    .prepare("INSERT INTO admin_security (user_id, must_change_password) VALUES (?, 1) ON CONFLICT(user_id) DO UPDATE SET must_change_password = 1")
    .bind(adminId)
    .run();
  const env = adminEnv(setup);
  return { setup, env, adminId };
}

describe("browser-shaped admin form requests", () => {
  it("signs in and completes the forced password change with Origin: null and no Sec-Fetch-Site", async () => {
    const { setup, env, adminId } = await adminWithForcedChange();

    // 1. The sign-in page, fetched anonymously the way a browser does.
    const loginPage = await callAdmin(browserRequest("/admin/login"), env);
    expect(loginPage.status).toBe(200);
    const loginCookies = loginPage.response.headers.getSetCookie?.() ?? [];
    const loginCsrf = cookieValue(loginCookies.map((cookie) => cookie.split(";")[0]), LOGIN_CSRF_COOKIE);
    expect(loginCsrf).toBeTruthy();
    const loginFormToken = formTokenFrom(loginPage.text);
    expect(loginFormToken).toBe(loginCsrf);
    // The cookie is a form double-submit value, not a session credential.
    expect(loginCookies.find((cookie) => cookie.startsWith(LOGIN_CSRF_COOKIE)).toLowerCase()).toContain("samesite=strict");
    expect(loginCookies.find((cookie) => cookie.startsWith(LOGIN_CSRF_COOKIE)).toLowerCase()).not.toContain("httponly");
    expect(loginPage.response.headers.get("Referrer-Policy")).toBe("same-origin");

    // 2. Submit the form with the opaque origin a real browser sends.
    const login = await callAdmin(
      browserRequest("/admin/login", {
        form: { csrfToken: loginFormToken, email: ADMIN_EMAIL, password: ADMIN_PASSWORD },
        cookie: `${LOGIN_CSRF_COOKIE}=${loginCsrf}`
      }),
      env
    );
    expect(login.status).toBe(303);
    expect(login.response.headers.get("Location")).toBe("/admin/change-password");
    const sessionCookies = (login.response.headers.getSetCookie?.() ?? []).map((cookie) => cookie.split(";")[0]);
    expect(sessionCookies.some((cookie) => cookie.startsWith("__Host-wristbrief_admin="))).toBe(true);
    const sessionCookieHeader = cookieHeader(sessionCookies);

    // 3. The forced change page, then its form POST, both with Origin: null.
    const changePage = await callAdmin(browserRequest("/admin/change-password", { cookie: sessionCookieHeader }), env);
    expect(changePage.status).toBe(200);
    const changeToken = formTokenFrom(changePage.text);
    expect(changeToken).toBeTruthy();

    const change = await callAdmin(
      browserRequest("/admin/change-password", {
        form: {
          csrfToken: changeToken,
          currentPassword: ADMIN_PASSWORD,
          newPassword: ROTATED_PASSWORD,
          confirmPassword: ROTATED_PASSWORD
        },
        cookie: sessionCookieHeader
      }),
      env
    );
    expect(change.status).toBe(303);
    expect(change.response.headers.get("Location")).toBe("/admin/login?signedout=1");

    // 4. The change really applied.
    const row = await setup.d1.prepare("SELECT must_change_password FROM admin_security WHERE user_id = ?").bind(adminId).first();
    expect(row.must_change_password).toBe(0);
    const rotated = await openSession(setup, env, { password: ROTATED_PASSWORD });
    expect(rotated.ok).toBe(true);
    const stale = await openSession(setup, env, { password: ADMIN_PASSWORD });
    expect(stale.ok).toBe(false);
    expect(stale.status).toBe(401);
  });

  it("accepts an absent Origin for a mutating form and still rejects a different one", async () => {
    const setup = createSetup();
    await registerAdmin(setup);
    const env = adminEnv(setup);
    const session = await openSession(setup, env);
    const formToken = await (async () => {
      const page = await callAdmin(browserRequest("/admin/settings", { cookie: session.cookie }), env);
      return formTokenFrom(page.text);
    })();

    const fields = { csrfToken: formToken, registrationMode: "CLOSED" };

    // Opaque origin: accepted.
    const opaque = await callAdmin(browserRequest("/admin/settings", { form: fields, cookie: session.cookie }), env);
    expect(opaque.status).toBe(303);

    // No Origin header at all: accepted (non-browser client, CSRF token checked).
    const absent = await callAdmin(
      browserRequest("/admin/settings", { form: fields, cookie: session.cookie, origin: null }),
      env
    );
    expect(absent.status).toBe(303);

    // A real but different origin: still refused.
    const evil = await callAdmin(
      browserRequest("/admin/settings", { form: fields, cookie: session.cookie, origin: "https://evil.example" }),
      env
    );
    expect(evil.status).toBe(403);

    // A browser-reported cross-site request: still refused.
    const crossSite = await callAdmin(
      browserRequest("/admin/settings", { form: fields, cookie: session.cookie, secFetchSite: "cross-site" }),
      env
    );
    expect(crossSite.status).toBe(403);

    // An opaque origin does not excuse a missing or wrong CSRF token.
    const noToken = await callAdmin(
      browserRequest("/admin/settings", { form: { registrationMode: "CLOSED" }, cookie: session.cookie }),
      env
    );
    expect(noToken.status).toBe(403);
    const wrongToken = await callAdmin(
      browserRequest("/admin/settings", { form: { csrfToken: "not-the-form-token", registrationMode: "CLOSED" }, cookie: session.cookie }),
      env
    );
    expect(wrongToken.status).toBe(403);
    expect(await setup.adminStore.readRegistrationMode()).toBe("CLOSED");
  });

  it("protects the anonymous sign-in form with a double-submit cookie", async () => {
    const setup = createSetup();
    await registerAdmin(setup);
    const env = adminEnv(setup);

    const page = await callAdmin(browserRequest("/admin/login"), env);
    const loginCsrf = cookieValue((page.response.headers.getSetCookie?.() ?? []).map((cookie) => cookie.split(";")[0]), LOGIN_CSRF_COOKIE);
    const formToken = formTokenFrom(page.text);
    const credentials = { email: ADMIN_EMAIL, password: ADMIN_PASSWORD };

    // No cookie at all (a cross-site forgery cannot set one): refused.
    const noCookie = await callAdmin(browserRequest("/admin/login", { form: { csrfToken: formToken, ...credentials } }), env);
    expect(noCookie.status).toBe(403);
    expect(noCookie.text).toContain("did not come from this console");

    // Cookie present but the field does not match: refused.
    const mismatch = await callAdmin(
      browserRequest("/admin/login", { form: { csrfToken: "forged-token", ...credentials }, cookie: `${LOGIN_CSRF_COOKIE}=${loginCsrf}` }),
      env
    );
    expect(mismatch.status).toBe(403);

    // The refusal re-renders a usable token so an honest retry succeeds.
    const retryToken = formTokenFrom(mismatch.text);
    expect(retryToken).toBe(loginCsrf);
    const retry = await callAdmin(
      browserRequest("/admin/login", { form: { csrfToken: retryToken, ...credentials }, cookie: `${LOGIN_CSRF_COOKIE}=${loginCsrf}` }),
      env
    );
    expect(retry.status).toBe(303);

    // A cross-site origin is refused before anything else.
    const crossSite = await callAdmin(
      browserRequest("/admin/login", {
        form: { csrfToken: formToken, ...credentials },
        cookie: `${LOGIN_CSRF_COOKIE}=${loginCsrf}`,
        origin: "https://evil.example"
      }),
      env
    );
    expect(crossSite.status).toBe(403);

    // A fresh browser (no cookie) still gets a usable form.
    const fresh = await callAdmin(browserRequest("/admin/login"), env);
    const freshCookie = cookieValue(
      (fresh.response.headers.getSetCookie?.() ?? []).map((cookie) => cookie.split(";")[0]),
      LOGIN_CSRF_COOKIE
    );
    expect(freshCookie).toBeTruthy();
    const freshSubmit = await callAdmin(
      browserRequest("/admin/login", { form: { csrfToken: formTokenFrom(fresh.text), ...credentials }, cookie: `${LOGIN_CSRF_COOKIE}=${freshCookie}` }),
      env
    );
    expect(freshSubmit.status).toBe(303);
  });

  it("reuses an existing login cookie instead of rotating it on every page view", async () => {
    const setup = createSetup();
    await registerAdmin(setup);
    const env = adminEnv(setup);

    const first = await callAdmin(browserRequest("/admin/login"), env);
    const firstCookie = cookieValue((first.response.headers.getSetCookie?.() ?? []).map((cookie) => cookie.split(";")[0]), LOGIN_CSRF_COOKIE);

    // A second visit with the cookie presents the same value and sets nothing new,
    // so a form opened in an older tab still submits.
    const second = await callAdmin(browserRequest("/admin/login", { cookie: `${LOGIN_CSRF_COOKIE}=${firstCookie}` }), env);
    expect(second.status).toBe(200);
    expect(second.response.headers.getSetCookie?.() ?? []).toHaveLength(0);
    expect(formTokenFrom(second.text)).toBe(firstCookie);

    // And that older form still signs in.
    const submit = await callAdmin(
      browserRequest("/admin/login", {
        form: { csrfToken: firstCookie, email: ADMIN_EMAIL, password: ADMIN_PASSWORD },
        cookie: `${LOGIN_CSRF_COOKIE}=${firstCookie}`
      }),
      env
    );
    expect(submit.status).toBe(303);
  });

  it("uses one origin policy for absent, opaque and hostile origins", () => {
    const request = (origin) =>
      new Request("https://gateway.example.com/admin/settings", {
        method: "POST",
        headers: origin === undefined ? {} : { Origin: origin }
      });
    // No usable origin: accepted, and the CSRF token is the gate instead.
    expect(verifySameOrigin(request(undefined))).toBe(true);
    expect(verifySameOrigin(request("null"))).toBe(true);
    // A real, matching origin: accepted.
    expect(verifySameOrigin(request("https://gateway.example.com"))).toBe(true);
    // A present but different or unparseable origin: still refused.
    expect(verifySameOrigin(request("https://evil.example"))).toBe(false);
    expect(verifySameOrigin(request("not a url"))).toBe(false);
    expect(REQUEST_ID).toBeTruthy();
  });
});

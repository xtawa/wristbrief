import { D1AdminStore } from "./adminStore";
import { AdminAuditLog } from "./adminAudit";
import { AdminWebSessionService, ADMIN_COOKIE_NAME, type AdminWebSession } from "./adminSession";
import { requireAdminCsrf, requireAdminUser } from "./adminAuth";
import { adminPage, htmlResponse } from "./adminHtml";
import { banner, adminShell } from "./adminForms";
import { hashPassword, verifyPassword } from "../emailAuth/passwordHasher";
import { D1EmailCredentialStore } from "../emailAuth/emailCredentialStore";
import { AUTH_RATE_LIMIT_RULES, createConfiguredRateLimiter, ipPrefixOf } from "../rateLimit";
import { sha256Hex } from "../emailAuth/emailTokens";
import { PROVIDER_SECRET_SLOTS } from "../providerConfigStore";
import { AdminInput, EMAIL_FORMAT, describeFieldError, invalidRequest, type FieldError, type InvalidRequest } from "./adminValidation";
import { ADMIN_BODY_LIMITS, bodyFailureMessage, readAdminBody, type AdminBodySuccess } from "./adminBody";
import {
  deleteProviderConfig,
  listProviderConfigs,
  providerAdminPage,
  providerHealthCheck,
  upsertProviderConfig,
  type ProviderAdminEnv
} from "./providerAdmin";
import { buildOperationalOverview, defaultPeriod, resolvePeriod } from "./adminOverview";
import {
  USER_DISPLAY_NAME_MAX,
  USER_PAGE_LIMIT_DEFAULT,
  USER_PLAN_VALUES,
  USER_QUOTA_MAX,
  USER_STATUS_VALUES,
  listUserPage,
  parseUserPageParams
} from "./adminUsers";
import {
  audioPage,
  auditPage,
  changePasswordPage,
  dashboardPage,
  loginPage,
  overviewPage,
  providerKeysPage,
  settingsPage,
  smtpPage,
  usersPage,
  type AudioProviderRow
} from "./adminPages";
import { probeAudioPreset, readConnectivityChecks, recordConnectivityCheck, type ConnectivityResult } from "./adminConnectivity";
import { readSmtpTestHistory, runSmtpTest } from "./adminSmtpTest";
import { createLoginCsrfToken, deriveAdminFormToken, isOpaqueOrigin, readCookie, LOGIN_CSRF_COOKIE } from "./adminCsrf";

export type AdminRoutesEnv = {
  ACCOUNT_DB?: D1Database;
  ADMIN_RECOVERY_SECRET?: string;
  ADMIN_RECOVERY_OVERRIDE?: string;
  SINGLE_ADMIN_MODE?: boolean;
  FREE_AI_MONTHLY_LIMIT?: string;
  PRO_AI_MONTHLY_LIMIT?: string;
  /** Extra allowed recipients for the SMTP test send, comma separated. */
  ADMIN_SMTP_TEST_RECIPIENTS?: string;
  /** Injection point for connectivity probes in tests. */
  ADMIN_FETCH?: typeof fetch;
  ADMIN_SETTINGS_SERVICE?: {
    smtpStatus(): Record<string, unknown>;
    saveSmtp(value: unknown): Promise<boolean>;
    setProviderSecret(slot: string, secret: string): Promise<boolean>;
    /** Present on the server settings object; used by the SMTP test send. */
    send?(email: { to: string; subject: string; text: string }): Promise<void>;
  };
} & ProviderAdminEnv;

const CSRF_COOKIE_NAME = "wristbrief_admin_csrf";

/** Paths reachable while the administrator still has the temporary password. */
const PASSWORD_CHANGE_EXEMPT = new Set([
  "/admin/login",
  "/v1/admin/session",
  "/v1/admin/bootstrap-status",
  "/admin/change-password",
  "/v1/admin/change-password",
  "/v1/admin/logout"
]);

/**
 * Dispatcher for /admin (server-rendered HTML) and /v1/admin (JSON API).
 * Returns null for non-admin paths so the caller's route chain continues.
 *
 * Authorization: admin web session cookie -> internal user_id -> active account
 * -> admin role. Mutating requests additionally require CSRF (the
 * `X-CSRF-Token` header, or the derived form token for a real form POST) plus a
 * same-origin Origin.
 *
 * Every write endpoint reads its body through `readAdminBody` with an explicit
 * byte cap and validates through `AdminInput`, so the JSON API and the HTML forms
 * return the same structured field errors from one validation source.
 */
export async function handleAdminRoute(request: Request, env: AdminRoutesEnv, requestId: string): Promise<Response | null> {
  const url = new URL(request.url);
  const path = url.pathname;
  if (!path.startsWith("/admin") && !path.startsWith("/v1/admin")) return null;
  if (!env.ACCOUNT_DB) return json({ error: "admin_not_configured" }, 503, requestId);

  const db = env.ACCOUNT_DB;
  const adminStore = new D1AdminStore(db);
  const sessions = new AdminWebSessionService(db);
  const audit = new AdminAuditLog(db);
  const respond = (value: unknown, status = 200) => json(value, status, requestId);
  const isHtml = path.startsWith("/admin");

  /**
   * Reads the body under a cap and checks CSRF. Returns a discriminated result so
   * each route can answer in its own format (JSON payload or re-rendered page).
   */
  const readGuarded = async (
    session: AdminWebSession,
    limit: number
  ): Promise<{ ok: true; body: AdminBodySuccess } | { ok: false; status: number; payload: Record<string, unknown> }> => {
    if (!sameOriginOk(request) || !secFetchSiteOk(request)) {
      return { ok: false, status: 403, payload: { error: "cross_origin_rejected" } };
    }
    const body = await readAdminBody(request, limit);
    if (!body.ok) return { ok: false, status: body.status, payload: body.body };
    if (!(await requireAdminCsrf(request, session, body.formCsrfToken))) {
      return { ok: false, status: 403, payload: { error: "csrf_required" } };
    }
    return { ok: true, body };
  };

  if (env.SINGLE_ADMIN_MODE && !PASSWORD_CHANGE_EXEMPT.has(path)) {
    const auth = await requireAdminUser(request, adminStore, sessions);
    if (auth.ok && (await mustChangePassword(db, auth.userId))) {
      return isHtml ? redirect("/admin/change-password") : respond({ error: "password_change_required" }, 403);
    }
  }

  // ---- public ----
  if (request.method === "GET" && path === "/v1/admin/bootstrap-status") {
    const bootstrap = await adminStore.readBootstrapState();
    const mode = await adminStore.readRegistrationMode();
    if (env.SINGLE_ADMIN_MODE) return respond({ bootstrapCompleted: true, registrationOpen: false, mobileRegistrationOpen: mode === "OPEN" });
    return respond({
      bootstrapCompleted: bootstrap?.bootstrapCompletedAt != null,
      registrationOpen: bootstrap?.firstAdminUserId == null
        ? bootstrap?.webRegistrationEnabled === true
        : mode === "OPEN"
    });
  }

  if (request.method === "GET" && path === "/admin/login") {
    const auth = await requireAdminUser(request, adminStore, sessions);
    if (auth.ok) return redirect("/admin");
    const bootstrap = await adminStore.readBootstrapState();
    const mode = await adminStore.readRegistrationMode();
    const registrationOpen = !env.SINGLE_ADMIN_MODE && (bootstrap?.firstAdminUserId == null
      ? bootstrap?.webRegistrationEnabled === true
      : mode === "OPEN");
    // Pre-session CSRF: a per-browser value in a SameSite=Strict cookie, echoed
    // back as a hidden field. Reused while valid so an older tab still submits.
    const existing = readCookie(request, LOGIN_CSRF_COOKIE);
    const loginToken = existing && /^[A-Za-z0-9_-]{43}$/.test(existing) ? existing : createLoginCsrfToken();
    const freshCookie =
      loginToken === existing
        ? []
        : [`${LOGIN_CSRF_COOKIE}=${loginToken}; SameSite=Strict; Path=/; Max-Age=43200`];
    return htmlResponse(
      loginPage({
        registrationOpen,
        singleAdminMode: Boolean(env.SINGLE_ADMIN_MODE),
        values: null,
        formToken: loginToken,
        message: url.searchParams.get("signedout") ? "Password changed. Sign in again with your new password." : undefined
      }),
      200,
      requestId,
      freshCookie
    );
  }

  // No-JavaScript sign-in. The sign-in form is anonymous, so instead of a session
  // CSRF token it carries a double-submit value: the hidden field must equal the
  // SameSite=Strict cookie this server set. A cross-site page can neither read
  // nor set that cookie, and `Sec-Fetch-Site: cross-site` is refused outright.
  if (request.method === "POST" && path === "/admin/login") {
    const base = { registrationOpen: false, singleAdminMode: Boolean(env.SINGLE_ADMIN_MODE) };
    // Anonymous form: the hidden field must match the SameSite=Strict cookie this
    // server set. A cross-site page can neither read nor set that cookie, so a
    // forgery always fails. When the cookie is missing or malformed a fresh value
    // is minted, sent back, and used as the comparison value, which both rejects
    // the forgery and leaves an honest operator with a form that can immediately
    // be retried.
    const existingCookie = readCookie(request, LOGIN_CSRF_COOKIE);
    const reuseCookie = existingCookie && /^[A-Za-z0-9_-]{43}$/.test(existingCookie) ? existingCookie : null;
    const loginFormToken = reuseCookie ?? createLoginCsrfToken();
    const loginCookieHeaders = reuseCookie
      ? []
      : [`${LOGIN_CSRF_COOKIE}=${loginFormToken}; SameSite=Strict; Path=/; Max-Age=43200`];
    const loginPageResponse = (
      options: { errors?: FieldError[]; values?: Record<string, string> | null; message?: string },
      status: number
    ) => htmlResponse(loginPage({ ...base, formToken: loginFormToken, ...options }), status, requestId, loginCookieHeaders);

    if (!sameOriginOk(request) || !secFetchSiteOk(request)) {
      return loginPageResponse({ message: "Cross-origin sign-in was rejected." }, 403);
    }
    const body = await readAdminBody(request, ADMIN_BODY_LIMITS.login);
    if (!body.ok) {
      return loginPageResponse({ message: bodyFailureMessage(body.body) }, body.status);
    }
    if (!matchesToken(loginFormToken, body.formCsrfToken)) {
      return loginPageResponse(
        { message: "That sign-in form expired or did not come from this console. Reload the page and try again." },
        403
      );
    }
    const input = new AdminInput(body.value);
    const email = input.string("email", { required: true, max: 254, format: EMAIL_FORMAT });
    const password = input.string("password", { required: true, max: 256, trim: false, noControlCharacters: false });
    if (!input.ok || email === undefined || password === undefined) {
      return loginPageResponse({ errors: input.fieldErrors, values: { email: String(body.value.email ?? "") } }, 400);
    }
    const limiter = createConfiguredRateLimiter(env, AUTH_RATE_LIMIT_RULES.adminLogin);
    if (!(await limiter.check(`${AUTH_RATE_LIMIT_RULES.adminLogin.name}:ip:${ipPrefixOf(request)}`)).allowed) {
      return loginPageResponse({ message: "Too many sign-in attempts. Try again later.", values: { email } }, 429);
    }
    const outcome = await authenticateAdmin(db, adminStore, email, password);
    if (!outcome.ok) {
      return loginPageResponse({ message: signInMessage(outcome.error), values: { email } }, outcome.status);
    }
    const issued = await sessions.issue(outcome.userId, {
      ipPrefixHash: await sha256Hex(ipPrefixOf(request)),
      userAgentHash: await sha256Hex(request.headers.get("User-Agent") ?? "")
    });
    await audit.record({ actorUserId: outcome.userId, action: "admin_session_created", targetType: "admin_web_session", requestId });
    const next = (await mustChangePassword(db, outcome.userId)) ? "/admin/change-password" : "/admin";
    return jsonResponseWithCookies({ ok: true, next }, 200, requestId, sessionCookies(issued.token, issued.csrfToken, issued.expiresAt), 303, next);
  }

  // ---- dashboard ----
  if (request.method === "GET" && (path === "/admin" || path === "/admin/")) {
    const auth = await requireAdminUser(request, adminStore, sessions);
    if (!auth.ok) return redirect("/admin/login");
    const bootstrap = await adminStore.readBootstrapState();
    return htmlResponse(
      dashboardPage({
        formToken: await deriveAdminFormToken(auth.session),
        bootstrap: bootstrap ? { firstAdminUserId: bootstrap.firstAdminUserId, bootstrapCompletedAt: bootstrap.bootstrapCompletedAt } : null,
        admins: await adminStore.countAdmins(),
        mode: await adminStore.readRegistrationMode(),
        singleAdminMode: Boolean(env.SINGLE_ADMIN_MODE),
        overview: await buildOperationalOverview(env)
      }),
      200,
      requestId
    );
  }

  // ---- operational overview ----
  if (request.method === "GET" && path === "/v1/admin/overview") {
    const auth = await requireAdminUser(request, adminStore, sessions);
    if (!auth.ok) return respond({ error: auth.error }, auth.status);
    const period = resolvePeriod(url.searchParams.get("period"));
    if (!period.valid) return respond(invalidRequest([{ field: "period", reason: "invalid_format", format: "YYYY-MM" }]), 400);
    return respond(await buildOperationalOverview(env, { period: period.period }));
  }

  if (env.SINGLE_ADMIN_MODE && request.method === "GET" && path === "/admin/overview") {
    const auth = await requireAdminUser(request, adminStore, sessions);
    if (!auth.ok) return redirect("/admin/login");
    const period = resolvePeriod(url.searchParams.get("period"));
    return htmlResponse(
      overviewPage({
        formToken: await deriveAdminFormToken(auth.session),
        overview: await buildOperationalOverview(env, { period: period.period }),
        errors: period.valid ? [] : [{ field: "period", reason: "invalid_format", format: "YYYY-MM" }]
      }),
      200,
      requestId
    );
  }

  // ---- users ----
  if (env.SINGLE_ADMIN_MODE && request.method === "GET" && path === "/v1/admin/users") {
    const auth = await requireAdminUser(request, adminStore, sessions);
    if (!auth.ok) return respond({ error: auth.error }, auth.status);
    const params = parseUserPageParams(url);
    if (!params.ok) return respond(params.body, 400);
    const page = await listUserPage(db, env, params.query);
    // `users` keeps its original shape; `page` describes the effective window.
    return respond({ users: page.users, page: page.page });
  }

  if (env.SINGLE_ADMIN_MODE && request.method === "GET" && path === "/admin/users") {
    const auth = await requireAdminUser(request, adminStore, sessions);
    if (!auth.ok) return redirect("/admin/login");
    const params = parseUserPageParams(url);
    const query = params.ok ? params.query : { search: "", limit: USER_PAGE_LIMIT_DEFAULT, offset: 0, limitClamped: false };
    const page = await listUserPage(db, env, query);
    const updated = url.searchParams.get("updated");
    const errorCode = url.searchParams.get("error");
    // Pagination parameters are not form fields on this page, so a bad `limit` or
    // `offset` is reported as a banner rather than a field summary that would
    // link to an input that does not exist.
    const paginationProblem = params.ok ? undefined : `Could not use those list options: ${describeFieldError(params.body.fields[0]!)}`;
    return htmlResponse(
      usersPage({
        formToken: await deriveAdminFormToken(auth.session),
        page,
        errors: [],
        errorsUserId: null,
        values: null,
        adminEmail: await adminEmailOf(db, auth.userId),
        bannerHtml: updated
          ? banner("success", `Saved changes for ${updated}.`)
          : errorCode
            ? banner("error", `That change was refused (${errorCode}).`)
            : paginationProblem
              ? banner("error", paginationProblem)
              : undefined
      }),
      200,
      requestId
    );
  }

  if (env.SINGLE_ADMIN_MODE && request.method === "PATCH" && path.startsWith("/v1/admin/users/")) {
    const auth = await requireAdminUser(request, adminStore, sessions);
    if (!auth.ok) return respond({ error: auth.error }, auth.status);
    const id = decodeURIComponent(path.slice("/v1/admin/users/".length));
    if (!id || id.includes("/")) return respond(invalidRequest([{ field: "id", reason: "invalid_format" }]), 400);
    const guard = await readGuarded(auth.session, ADMIN_BODY_LIMITS.user);
    if (!guard.ok) return respond(guard.payload, guard.status);
    const failure = await protectSoleAdmin(db, adminStore, id, auth.userId);
    if (failure) return respond(failure.body, failure.status);
    const update = validateUserUpdate(new AdminInput(guard.body.value));
    if (!update.ok) return respond(update.failure, 400);
    await applyUserUpdate(db, sessions, id, update.value);
    await audit.record({
      actorUserId: auth.userId,
      action: "user_updated",
      targetType: "user",
      targetId: id,
      after: { status: update.value.status, plan: update.value.plan, quotaLimit: update.value.quotaLimit, displayName: update.value.displayName },
      requestId
    });
    return respond({ ok: true, user: { id, ...update.value } });
  }

  if (env.SINGLE_ADMIN_MODE && request.method === "POST" && path.startsWith("/admin/users/")) {
    const auth = await requireAdminUser(request, adminStore, sessions);
    if (!auth.ok) return redirect("/admin/login");
    const id = decodeURIComponent(path.slice("/admin/users/".length));
    const returnTo = `/admin/users?${new URLSearchParams({
      q: url.searchParams.get("q") ?? "",
      limit: url.searchParams.get("limit") ?? String(USER_PAGE_LIMIT_DEFAULT),
      offset: url.searchParams.get("offset") ?? "0"
    }).toString()}`;
    const guard = await readGuarded(auth.session, ADMIN_BODY_LIMITS.user);
    if (!guard.ok) {
      return renderUsersPage(db, env, auth.session, requestId, {
        errors: [{ field: "form", reason: String(guard.payload.error ?? "invalid_request"), message: bodyFailureMessage(guard.payload) }],
        errorsUserId: id,
        values: null,
        returnTo,
        status: guard.status
      });
    }
    const failure = await protectSoleAdmin(db, adminStore, id, auth.userId);
    if (failure) {
      return renderUsersPage(db, env, auth.session, requestId, {
        errors: [],
        errorsUserId: null,
        values: null,
        returnTo,
        status: failure.status,
        bannerHtml: banner("error", String(failure.body.message ?? "That account cannot be changed."))
      });
    }
    const update = validateUserUpdate(new AdminInput(guard.body.value));
    if (!update.ok) {
      return renderUsersPage(db, env, auth.session, requestId, {
        errors: update.failure.fields,
        errorsUserId: id,
        values: flattenValues(guard.body.value),
        returnTo,
        status: 400
      });
    }
    await applyUserUpdate(db, sessions, id, update.value);
    await audit.record({
      actorUserId: auth.userId,
      action: "user_updated",
      targetType: "user",
      targetId: id,
      after: { status: update.value.status, plan: update.value.plan, quotaLimit: update.value.quotaLimit, displayName: update.value.displayName },
      requestId
    });
    return redirectSeeOther(`${returnTo}&updated=${encodeURIComponent(id)}`);
  }

  // ---- settings ----
  if (request.method === "GET" && path === "/v1/admin/settings") {
    const auth = await requireAdminUser(request, adminStore, sessions);
    if (!auth.ok) return respond({ error: auth.error }, auth.status);
    return respond({ registrationMode: await adminStore.readRegistrationMode() });
  }

  if (env.SINGLE_ADMIN_MODE && request.method === "GET" && path === "/admin/settings") {
    const auth = await requireAdminUser(request, adminStore, sessions);
    if (!auth.ok) return redirect("/admin/login");
    const mode = await adminStore.readRegistrationMode();
    return htmlResponse(
      settingsPage({
        formToken: await deriveAdminFormToken(auth.session),
        mode,
        smtpConfigured: env.ADMIN_SETTINGS_SERVICE?.smtpStatus().configured === true,
        values: null,
        bannerHtml: url.searchParams.get("saved") ? banner("success", `Registration mode saved as ${mode}.`) : undefined
      }),
      200,
      requestId
    );
  }

  if ((request.method === "PATCH" || request.method === "POST") && (path === "/v1/admin/settings" || path === "/admin/settings")) {
    const auth = await requireAdminUser(request, adminStore, sessions);
    if (!auth.ok) return isHtml ? redirect("/admin/login") : respond({ error: auth.error }, auth.status);
    const guard = await readGuarded(auth.session, ADMIN_BODY_LIMITS.settings);
    if (!guard.ok) {
      if (!isHtml) return respond(guard.payload, guard.status);
      return renderSettingsPage(db, env, adminStore, auth.session, requestId, [], bodyFailureMessage(guard.payload), guard.status);
    }
    const input = new AdminInput(guard.body.value);
    const mode = input.enum("registrationMode", ["OPEN", "CLOSED"] as const);
    if (!input.ok || mode === undefined) {
      if (!isHtml) return respond(input.failure, 400);
      return renderSettingsPage(db, env, adminStore, auth.session, requestId, input.fieldErrors, undefined, 400, flattenValues(guard.body.value));
    }
    if (env.SINGLE_ADMIN_MODE && mode === "OPEN" && env.ADMIN_SETTINGS_SERVICE?.smtpStatus().configured !== true) {
      const message = "Configure SMTP before opening registration so new accounts can verify their address.";
      if (!isHtml) return respond({ error: "smtp_not_configured", message }, 409);
      return renderSettingsPage(db, env, adminStore, auth.session, requestId, [{ field: "registrationMode", reason: "not_allowed", hint: message }], undefined, 409, flattenValues(guard.body.value));
    }
    const before = await adminStore.readRegistrationMode();
    await adminStore.writeRegistrationMode(mode, auth.userId);
    await audit.record({
      actorUserId: auth.userId,
      action: "registration_mode_changed",
      targetType: "system_setting",
      targetId: "registration_mode",
      before: { registrationMode: before },
      after: { registrationMode: mode },
      requestId
    });
    return isHtml ? redirectSeeOther("/admin/settings?saved=1") : respond({ registrationMode: mode });
  }

  // ---- SMTP ----
  if (env.SINGLE_ADMIN_MODE && request.method === "GET" && path === "/admin/smtp") {
    const auth = await requireAdminUser(request, adminStore, sessions);
    if (!auth.ok) return redirect("/admin/login");
    return renderSmtpPage(db, env, auth.session, requestId, {
      errors: [],
      values: null,
      status: 200,
      testResult: smtpTestBanner(url.searchParams),
      bannerHtml: url.searchParams.get("saved") ? banner("success", "SMTP settings saved. The password was stored encrypted and is never displayed again.") : undefined
    });
  }

  if (env.SINGLE_ADMIN_MODE && request.method === "PUT" && path === "/v1/admin/smtp") {
    const auth = await requireAdminUser(request, adminStore, sessions);
    if (!auth.ok) return respond({ error: auth.error }, auth.status);
    const guard = await readGuarded(auth.session, ADMIN_BODY_LIMITS.smtp);
    if (!guard.ok) return respond(guard.payload, guard.status);
    if (!env.ADMIN_SETTINGS_SERVICE) return respond({ error: "settings_unavailable" }, 503);
    const input = new AdminInput(guard.body.value);
    const value = validateSmtp(input, env.ADMIN_SETTINGS_SERVICE.smtpStatus());
    if (!input.ok || value === undefined) return respond(input.failure, 400);
    if (!(await env.ADMIN_SETTINGS_SERVICE.saveSmtp(value))) {
      return respond(invalidRequest([{ field: "smtp", reason: "rejected", message: "The SMTP settings were refused by the server." }]), 400);
    }
    await audit.record({ actorUserId: auth.userId, action: "smtp_updated", targetType: "system_setting", targetId: "smtp", requestId });
    return respond({ ok: true, status: env.ADMIN_SETTINGS_SERVICE.smtpStatus() });
  }

  if (env.SINGLE_ADMIN_MODE && request.method === "POST" && path === "/admin/smtp") {
    const auth = await requireAdminUser(request, adminStore, sessions);
    if (!auth.ok) return redirect("/admin/login");
    const guard = await readGuarded(auth.session, ADMIN_BODY_LIMITS.smtp);
    if (!guard.ok) {
      return renderSmtpPage(db, env, auth.session, requestId, { errors: [], values: null, status: guard.status, bannerHtml: banner("error", bodyFailureMessage(guard.payload)) });
    }
    if (!env.ADMIN_SETTINGS_SERVICE) {
      return renderSmtpPage(db, env, auth.session, requestId, { errors: [], values: null, status: 503, bannerHtml: banner("error", "This deployment has no settings service.") });
    }
    const input = new AdminInput(guard.body.value);
    const value = validateSmtp(input, env.ADMIN_SETTINGS_SERVICE.smtpStatus());
    if (!input.ok || value === undefined) {
      return renderSmtpPage(db, env, auth.session, requestId, { errors: input.fieldErrors, values: flattenValues(guard.body.value), status: 400 });
    }
    if (!(await env.ADMIN_SETTINGS_SERVICE.saveSmtp(value))) {
      return renderSmtpPage(db, env, auth.session, requestId, {
        errors: [{ field: "smtp", reason: "rejected", message: "The SMTP settings were refused by the server." }],
        values: flattenValues(guard.body.value),
        status: 400
      });
    }
    await audit.record({ actorUserId: auth.userId, action: "smtp_updated", targetType: "system_setting", targetId: "smtp", requestId });
    return redirectSeeOther("/admin/smtp?saved=1");
  }

  if (env.SINGLE_ADMIN_MODE && request.method === "POST" && path === "/v1/admin/smtp/test") {
    const auth = await requireAdminUser(request, adminStore, sessions);
    if (!auth.ok) return respond({ error: auth.error }, auth.status);
    const guard = await readGuarded(auth.session, ADMIN_BODY_LIMITS.smtpTest);
    if (!guard.ok) return respond(guard.payload, guard.status);
    const outcome = await runSmtpTest({
      env,
      settings: env.ADMIN_SETTINGS_SERVICE,
      actorUserId: auth.userId,
      actorEmail: await adminEmailOf(db, auth.userId),
      requestedRecipient: guard.body.value.recipient,
      audit,
      requestId
    });
    return respond(outcome.body, outcome.status);
  }

  if (env.SINGLE_ADMIN_MODE && request.method === "POST" && path === "/admin/smtp/test") {
    const auth = await requireAdminUser(request, adminStore, sessions);
    if (!auth.ok) return redirect("/admin/login");
    const guard = await readGuarded(auth.session, ADMIN_BODY_LIMITS.smtpTest);
    if (!guard.ok) {
      return renderSmtpPage(db, env, auth.session, requestId, { errors: [], values: null, status: guard.status, bannerHtml: banner("error", bodyFailureMessage(guard.payload)) });
    }
    const outcome = await runSmtpTest({
      env,
      settings: env.ADMIN_SETTINGS_SERVICE,
      actorUserId: auth.userId,
      actorEmail: await adminEmailOf(db, auth.userId),
      requestedRecipient: guard.body.value.recipient,
      audit,
      requestId
    });
    if (Array.isArray(outcome.body.fields)) {
      return renderSmtpPage(db, env, auth.session, requestId, {
        errors: outcome.body.fields as FieldError[],
        values: { recipient: String(guard.body.value.recipient ?? "") },
        status: outcome.status
      });
    }
    return redirectSeeOther(
      `/admin/smtp?test=${encodeURIComponent(String(outcome.body.result ?? "error"))}${
        outcome.body.errorClass ? `&errorClass=${encodeURIComponent(String(outcome.body.errorClass))}` : ""
      }`
    );
  }

  // ---- provider keys ----
  if (env.SINGLE_ADMIN_MODE && request.method === "GET" && path === "/admin/provider-keys") {
    const auth = await requireAdminUser(request, adminStore, sessions);
    if (!auth.ok) return redirect("/admin/login");
    return htmlResponse(
      renderProviderKeys(env, await deriveAdminFormToken(auth.session), undefined, null,
        url.searchParams.get("saved") ? banner("success", "The key was saved. It cannot be read back.") : undefined),
      200,
      requestId
    );
  }

  if (env.SINGLE_ADMIN_MODE && request.method === "PUT" && path === "/v1/admin/provider-keys") {
    const auth = await requireAdminUser(request, adminStore, sessions);
    if (!auth.ok) return respond({ error: auth.error }, auth.status);
    // The body guard is a transport-level check and runs before any
    // precondition that can answer 503, so an oversized request is always 413.
    const guard = await readGuarded(auth.session, ADMIN_BODY_LIMITS.providerKey);
    if (!guard.ok) return respond(guard.payload, guard.status);
    if (!env.ADMIN_SETTINGS_SERVICE) return respond({ error: "settings_unavailable" }, 503);
    const input = new AdminInput(guard.body.value);
    const slot = input.enum("slot", PROVIDER_SECRET_SLOTS as unknown as readonly string[]);
    const secret = input.string("secret", { required: true, max: 8192, trim: false, noControlCharacters: false });
    if (!input.ok || slot === undefined || secret === undefined) return respond(input.failure, 400);
    if (!(await env.ADMIN_SETTINGS_SERVICE.setProviderSecret(slot, secret))) {
      return respond(invalidRequest([{ field: "secret", reason: "rejected", message: "The key was refused by the server." }]), 400);
    }
    await audit.record({ actorUserId: auth.userId, action: "provider_secret_updated", targetType: "system_setting", targetId: slot, requestId });
    return respond({ ok: true, slot });
  }

  if (env.SINGLE_ADMIN_MODE && request.method === "POST" && path === "/admin/provider-keys") {
    const auth = await requireAdminUser(request, adminStore, sessions);
    if (!auth.ok) return redirect("/admin/login");
    const formToken = await deriveAdminFormToken(auth.session);
    const guard = await readGuarded(auth.session, ADMIN_BODY_LIMITS.providerKey);
    if (!guard.ok) {
      return htmlResponse(renderProviderKeys(env, formToken, undefined, null, banner("error", bodyFailureMessage(guard.payload))), guard.status, requestId);
    }
    if (!env.ADMIN_SETTINGS_SERVICE) {
      return htmlResponse(renderProviderKeys(env, formToken, undefined, null, banner("error", "This deployment has no settings service.")), 503, requestId);
    }
    const input = new AdminInput(guard.body.value);
    const slot = input.enum("slot", PROVIDER_SECRET_SLOTS as unknown as readonly string[]);
    const secret = input.string("secret", { required: true, max: 8192, trim: false, noControlCharacters: false });
    if (!input.ok || slot === undefined || secret === undefined) {
      return htmlResponse(renderProviderKeys(env, formToken, input.fieldErrors, flattenValues(guard.body.value), undefined), 400, requestId);
    }
    if (!(await env.ADMIN_SETTINGS_SERVICE.setProviderSecret(slot, secret))) {
      return htmlResponse(
        renderProviderKeys(env, formToken, [{ field: "secret", reason: "rejected", message: "The key was refused by the server." }], flattenValues(guard.body.value), undefined),
        400,
        requestId
      );
    }
    await audit.record({ actorUserId: auth.userId, action: "provider_secret_updated", targetType: "system_setting", targetId: slot, requestId });
    return redirectSeeOther("/admin/provider-keys?saved=1");
  }

  // ---- audio presets ----
  if (env.SINGLE_ADMIN_MODE && request.method === "GET" && path === "/v1/admin/audio") {
    const auth = await requireAdminUser(request, adminStore, sessions);
    if (!auth.ok) return respond({ error: auth.error }, auth.status);
    const rows = await listAudioProviders(db);
    const checks = await readConnectivityChecks(env);
    return respond({
      providers: rows,
      lastChecks: Object.fromEntries(rows.map((row) => [row.id, checks.get(`audio_provider:${row.id}`) ?? null]))
    });
  }

  if (env.SINGLE_ADMIN_MODE && request.method === "PATCH" && path.startsWith("/v1/admin/audio/")) {
    const auth = await requireAdminUser(request, adminStore, sessions);
    if (!auth.ok) return respond({ error: auth.error }, auth.status);
    const id = decodeURIComponent(path.slice("/v1/admin/audio/".length));
    if (!(await audioProviderExists(db, id))) return respond({ error: "provider_not_found" }, 404);
    const guard = await readGuarded(auth.session, ADMIN_BODY_LIMITS.audio);
    if (!guard.ok) return respond(guard.payload, guard.status);
    const update = validateAudioUpdate(new AdminInput(guard.body.value));
    if (!update.ok) return respond(update.failure, 400);
    await applyAudioUpdate(db, id, update.value);
    await audit.record({ actorUserId: auth.userId, action: "audio_provider_updated", targetType: "audio_provider", targetId: id, requestId });
    return respond({ ok: true });
  }

  if (env.SINGLE_ADMIN_MODE && request.method === "POST" && path.startsWith("/v1/admin/audio/") && path.endsWith("/check")) {
    const auth = await requireAdminUser(request, adminStore, sessions);
    if (!auth.ok) return respond({ error: auth.error }, auth.status);
    const id = decodeURIComponent(path.slice("/v1/admin/audio/".length, -"/check".length));
    const guard = await readGuarded(auth.session, ADMIN_BODY_LIMITS.connectivity);
    if (!guard.ok) return respond(guard.payload, guard.status);
    const row = await audioProviderById(db, id);
    if (!row) return respond({ error: "provider_not_found" }, 404);
    const check = await runAudioCheck(env, row, auth.userId);
    return respond(connectivityBody(check), check.status === "ok" ? 200 : check.status === "not_configured" || check.status === "disabled" ? 409 : 502);
  }

  if (env.SINGLE_ADMIN_MODE && request.method === "POST" && path.startsWith("/admin/audio/") && path.endsWith("/check")) {
    const auth = await requireAdminUser(request, adminStore, sessions);
    if (!auth.ok) return redirect("/admin/login");
    const id = decodeURIComponent(path.slice("/admin/audio/".length, -"/check".length));
    const guard = await readGuarded(auth.session, ADMIN_BODY_LIMITS.connectivity);
    if (!guard.ok) return redirect(`/admin/audio?error=${encodeURIComponent(String(guard.payload.error ?? "invalid_request"))}`);
    const row = await audioProviderById(db, id);
    if (!row) return redirect("/admin/audio?error=provider_not_found");
    const check = await runAudioCheck(env, row, auth.userId);
    return redirectSeeOther(
      `/admin/audio?checked=${encodeURIComponent(id)}&result=${encodeURIComponent(check.status)}${check.detail ? `&detail=${encodeURIComponent(check.detail)}` : ""}`
    );
  }

  if (env.SINGLE_ADMIN_MODE && request.method === "GET" && path === "/admin/audio") {
    const auth = await requireAdminUser(request, adminStore, sessions);
    if (!auth.ok) return redirect("/admin/login");
    return renderAudioPage(db, env, auth.session, requestId, {
      errors: [],
      errorsId: null,
      values: null,
      status: 200,
      checkParam: audioCheckBanner(url.searchParams)
    });
  }

  if (env.SINGLE_ADMIN_MODE && request.method === "POST" && path.startsWith("/admin/audio/")) {
    const auth = await requireAdminUser(request, adminStore, sessions);
    if (!auth.ok) return redirect("/admin/login");
    const id = decodeURIComponent(path.slice("/admin/audio/".length));
    const guard = await readGuarded(auth.session, ADMIN_BODY_LIMITS.audio);
    if (!guard.ok) return redirect(`/admin/audio?error=${encodeURIComponent(String(guard.payload.error ?? "invalid_request"))}`);
    if (!(await audioProviderExists(db, id))) return redirect("/admin/audio?error=provider_not_found");
    const update = validateAudioUpdate(new AdminInput(guard.body.value));
    if (!update.ok) {
      return renderAudioPage(db, env, auth.session, requestId, {
        errors: update.failure.fields,
        errorsId: id,
        values: flattenValues(guard.body.value),
        status: 400
      });
    }
    await applyAudioUpdate(db, id, update.value);
    await audit.record({ actorUserId: auth.userId, action: "audio_provider_updated", targetType: "audio_provider", targetId: id, requestId });
    return redirectSeeOther(`/admin/audio?saved=${encodeURIComponent(id)}`);
  }

  // ---- AI providers ----
  if (request.method === "GET" && path === "/v1/admin/providers") {
    const auth = await requireAdminUser(request, adminStore, sessions);
    if (!auth.ok) return respond({ error: auth.error }, auth.status);
    const result = await listProviderConfigs(env);
    return respond(result.body, result.status);
  }

  if (env.SINGLE_ADMIN_MODE && request.method === "GET" && path === "/admin/providers") {
    const auth = await requireAdminUser(request, adminStore, sessions);
    if (!auth.ok) return redirect("/admin/login");
    const checked = url.searchParams.get("checked");
    const result = url.searchParams.get("result");
    const bannerHtml = url.searchParams.get("saved")
      ? banner("success", "Provider saved.")
      : url.searchParams.get("deleted")
        ? banner("success", "Provider deleted.")
        : checked && result
          ? banner(result === "ok" ? "success" : "warning", `Connectivity check for ${checked}: ${result}.`)
          : url.searchParams.get("error")
            ? banner("error", `That change was refused (${url.searchParams.get("error")}).`)
            : undefined;
    return renderProvidersPage(env, auth.session, requestId, { status: 200, bannerHtml });
  }

  if (request.method === "POST" && path === "/v1/admin/providers") {
    const auth = await requireAdminUser(request, adminStore, sessions);
    if (!auth.ok) return respond({ error: auth.error }, auth.status);
    const guard = await readGuarded(auth.session, ADMIN_BODY_LIMITS.provider);
    if (!guard.ok) return respond(guard.payload, guard.status);
    const result = await upsertProviderConfig(env, guard.body.value, auth.userId, audit, requestId, true);
    return respond(result.body, result.status);
  }

  if (request.method === "PATCH" && path.startsWith("/v1/admin/providers/")) {
    const auth = await requireAdminUser(request, adminStore, sessions);
    if (!auth.ok) return respond({ error: auth.error }, auth.status);
    const providerId = decodeURIComponent(path.slice("/v1/admin/providers/".length));
    const guard = await readGuarded(auth.session, ADMIN_BODY_LIMITS.provider);
    if (!guard.ok) return respond(guard.payload, guard.status);
    const result = await upsertProviderConfig(env, { ...guard.body.value, id: providerId }, auth.userId, audit, requestId, false);
    return respond(result.body, result.status);
  }

  if (request.method === "DELETE" && path.startsWith("/v1/admin/providers/")) {
    const auth = await requireAdminUser(request, adminStore, sessions);
    if (!auth.ok) return respond({ error: auth.error }, auth.status);
    const providerId = decodeURIComponent(path.slice("/v1/admin/providers/".length));
    const guard = await readGuarded(auth.session, ADMIN_BODY_LIMITS.provider);
    if (!guard.ok) return respond(guard.payload, guard.status);
    const result = await deleteProviderConfig(env, providerId, auth.userId, audit, requestId);
    return respond(result.body, result.status);
  }

  if (request.method === "POST" && path.startsWith("/v1/admin/providers/") && path.endsWith("/health-check")) {
    const auth = await requireAdminUser(request, adminStore, sessions);
    if (!auth.ok) return respond({ error: auth.error }, auth.status);
    const providerId = decodeURIComponent(path.slice("/v1/admin/providers/".length, -"/health-check".length));
    const guard = await readGuarded(auth.session, ADMIN_BODY_LIMITS.connectivity);
    if (!guard.ok) return respond(guard.payload, guard.status);
    const result = await providerHealthCheck(env, providerId, auth.userId);
    return respond(result.body, result.status);
  }

  if (env.SINGLE_ADMIN_MODE && request.method === "POST" && path.startsWith("/admin/providers/") && path.endsWith("/check")) {
    const auth = await requireAdminUser(request, adminStore, sessions);
    if (!auth.ok) return redirect("/admin/login");
    const providerId = decodeURIComponent(path.slice("/admin/providers/".length, -"/check".length));
    const guard = await readGuarded(auth.session, ADMIN_BODY_LIMITS.connectivity);
    if (!guard.ok) return redirect(`/admin/providers?error=${encodeURIComponent(String(guard.payload.error ?? "invalid_request"))}`);
    const result = await providerHealthCheck(env, providerId, auth.userId);
    if (result.status === 404) return redirect("/admin/providers?error=not_found");
    return redirectSeeOther(`/admin/providers?checked=${encodeURIComponent(providerId)}&result=${encodeURIComponent(String(result.body.status ?? "unknown"))}`);
  }

  if (env.SINGLE_ADMIN_MODE && request.method === "POST" && path.startsWith("/admin/providers/") && path.endsWith("/delete")) {
    const auth = await requireAdminUser(request, adminStore, sessions);
    if (!auth.ok) return redirect("/admin/login");
    const providerId = decodeURIComponent(path.slice("/admin/providers/".length, -"/delete".length));
    const guard = await readGuarded(auth.session, ADMIN_BODY_LIMITS.provider);
    if (!guard.ok) return redirect(`/admin/providers?error=${encodeURIComponent(String(guard.payload.error ?? "invalid_request"))}`);
    const result = await deleteProviderConfig(env, providerId, auth.userId, audit, requestId);
    if (result.status >= 400) return redirect(`/admin/providers?error=${encodeURIComponent(String(result.body.error ?? "not_found"))}`);
    return redirectSeeOther("/admin/providers?deleted=1");
  }

  if (env.SINGLE_ADMIN_MODE && request.method === "POST" && path === "/admin/providers") {
    const auth = await requireAdminUser(request, adminStore, sessions);
    if (!auth.ok) return redirect("/admin/login");
    const guard = await readGuarded(auth.session, ADMIN_BODY_LIMITS.provider);
    if (!guard.ok) return redirect(`/admin/providers?error=${encodeURIComponent(String(guard.payload.error ?? "invalid_request"))}`);
    const values = flattenValues(guard.body.value);
    const result = await upsertProviderConfig(env, guard.body.value, auth.userId, audit, requestId, true);
    if (result.status >= 400) {
      return renderProvidersPage(env, auth.session, requestId, {
        status: result.status,
        errors: (result.body.fields as FieldError[] | undefined) ?? [{ field: "provider", reason: String(result.body.error ?? "invalid_request") }],
        values
      });
    }
    return redirectSeeOther("/admin/providers?saved=1");
  }

  if (env.SINGLE_ADMIN_MODE && request.method === "POST" && path.startsWith("/admin/providers/")) {
    const auth = await requireAdminUser(request, adminStore, sessions);
    if (!auth.ok) return redirect("/admin/login");
    const providerId = decodeURIComponent(path.slice("/admin/providers/".length));
    const guard = await readGuarded(auth.session, ADMIN_BODY_LIMITS.provider);
    if (!guard.ok) return redirect(`/admin/providers?error=${encodeURIComponent(String(guard.payload.error ?? "invalid_request"))}`);
    const values = { ...flattenValues(guard.body.value), id: providerId };
    const result = await upsertProviderConfig(env, { ...guard.body.value, id: providerId }, auth.userId, audit, requestId, false);
    if (result.status >= 400) {
      return renderProvidersPage(env, auth.session, requestId, {
        status: result.status,
        errors: (result.body.fields as FieldError[] | undefined) ?? [{ field: "provider", reason: String(result.body.error ?? "invalid_request") }],
        values
      });
    }
    return redirectSeeOther("/admin/providers?saved=1");
  }

  // ---- audit ----
  if (request.method === "GET" && path === "/v1/admin/audit") {
    const auth = await requireAdminUser(request, adminStore, sessions);
    if (!auth.ok) return respond({ error: auth.error }, auth.status);
    const input = new AdminInput(Object.fromEntries(url.searchParams));
    const limit = input.integer("limit", { min: 1, max: 200, required: false, fallback: 50 });
    const offset = input.integer("offset", { min: 0, max: 1_000_000, required: false, fallback: 0 });
    if (!input.ok) return respond(input.failure, 400);
    return respond({ entries: await audit.list(limit, offset), limit, offset });
  }

  if (env.SINGLE_ADMIN_MODE && request.method === "GET" && path === "/admin/audit") {
    const auth = await requireAdminUser(request, adminStore, sessions);
    if (!auth.ok) return redirect("/admin/login");
    return htmlResponse(
      auditPage({ formToken: await deriveAdminFormToken(auth.session), entries: await audit.list(50) }),
      200,
      requestId
    );
  }

  // ---- session / password / logout ----
  if (request.method === "POST" && path === "/v1/admin/session") {
    const limiter = createConfiguredRateLimiter(env, AUTH_RATE_LIMIT_RULES.adminLogin);
    if (!(await limiter.check(`${AUTH_RATE_LIMIT_RULES.adminLogin.name}:ip:${ipPrefixOf(request)}`)).allowed) {
      return respond({ error: "rate_limited" }, 429);
    }
    const body = await readAdminBody(request, ADMIN_BODY_LIMITS.session);
    if (!body.ok) return respond(body.body, body.status);
    const email = typeof body.value.email === "string" ? body.value.email.trim().toLowerCase() : "";
    const password = typeof body.value.password === "string" ? body.value.password : "";
    if (!email || !password) return respond({ error: "invalid_credentials" }, 401);

    const credentials = new D1EmailCredentialStore(db);
    const credential = await credentials.findByNormalizedEmail(email);
    if (!credential || credential.disabledAt || !(await verifyPassword(password, credential.passwordHash))) {
      return respond({ error: "invalid_credentials" }, 401);
    }
    if (!(await adminStore.userIsActive(credential.userId))) return respond({ error: "account_disabled" }, 403);
    const roles = await adminStore.rolesForUser(credential.userId);
    if (!roles.includes("admin")) return respond({ error: "forbidden" }, 403);

    const issued = await sessions.issue(credential.userId, {
      ipPrefixHash: await sha256Hex(ipPrefixOf(request)),
      userAgentHash: await sha256Hex(request.headers.get("User-Agent") ?? "")
    });
    await audit.record({ actorUserId: credential.userId, action: "admin_session_created", targetType: "admin_web_session", requestId });
    return jsonResponseWithCookies({ csrfToken: issued.csrfToken, expiresAt: issued.expiresAt }, 200, requestId, sessionCookies(issued.token, issued.csrfToken, issued.expiresAt));
  }

  if (request.method === "GET" && path === "/admin/change-password" && env.SINGLE_ADMIN_MODE) {
    const auth = await requireAdminUser(request, adminStore, sessions);
    if (!auth.ok) return redirect("/admin/login");
    if (!(await mustChangePassword(db, auth.userId))) return redirect("/admin");
    return htmlResponse(
      changePasswordPage({
        formToken: await deriveAdminFormToken(auth.session),
        values: null,
        adminEmail: await adminEmailOf(db, auth.userId),
        bannerHtml: url.searchParams.get("signedout") ? banner("success", "Password changed. Sign in again with your new password.") : undefined
      }),
      200,
      requestId
    );
  }

  if (request.method === "POST" && (path === "/v1/admin/change-password" || path === "/admin/change-password") && env.SINGLE_ADMIN_MODE) {
    const auth = await requireAdminUser(request, adminStore, sessions);
    if (!auth.ok) return isHtml ? redirect("/admin/login") : respond({ error: auth.error }, auth.status);
    const guard = await readGuarded(auth.session, ADMIN_BODY_LIMITS.password);
    if (!guard.ok) {
      if (!isHtml) return respond(guard.payload, guard.status);
      return renderChangePasswordPage(db, auth.session, requestId, [], banner("error", bodyFailureMessage(guard.payload)), guard.status);
    }
    const input = new AdminInput(guard.body.value);
    const current = input.string("currentPassword", { required: true, max: 256, trim: false, noControlCharacters: false });
    const next = input.string("newPassword", { required: true, min: 16, max: 256, trim: false, noControlCharacters: false });
    if (next !== undefined && current !== undefined && next === current) {
      input.fail("newPassword", "unchanged", { message: "The new password must differ from the temporary password." });
    }
    if (guard.body.contentType === "form") {
      const confirm = input.string("confirmPassword", { required: true, max: 256, trim: false, noControlCharacters: false });
      if (confirm !== undefined && next !== undefined && confirm !== next) {
        input.fail("confirmPassword", "mismatch", { message: "The two passwords do not match." });
      }
    }
    if (!input.ok || current === undefined || next === undefined) {
      if (!isHtml) return respond(input.failure, 400);
      return renderChangePasswordPage(db, auth.session, requestId, input.fieldErrors, undefined, 400);
    }
    // The credential belongs to the account that is actually signed in. Reaching
    // this point already required the admin role, and the sole-admin rule makes
    // that account the only web administrator, so this is still exactly one
    // account and it can only ever change its own password (never another's).
    const ownEmail = await adminEmailOf(db, auth.userId);
    const credential = ownEmail ? await new D1EmailCredentialStore(db).findByNormalizedEmail(ownEmail) : null;
    if (!credential || credential.userId !== auth.userId || !(await verifyPassword(current, credential.passwordHash))) {
      if (!isHtml) return respond({ error: "invalid_credentials" }, 401);
      return renderChangePasswordPage(db, auth.session, requestId, [{ field: "currentPassword", reason: "invalid_credentials", message: "That temporary password is not correct." }], undefined, 401);
    }
    await new D1EmailCredentialStore(db).updatePasswordHash(credential.id, await hashPassword(next));
    await db.prepare("UPDATE admin_security SET must_change_password = 0, password_changed_at = CURRENT_TIMESTAMP WHERE user_id = ?")
      .bind(auth.userId).run();
    await sessions.revokeForUser(auth.userId, new Date().toISOString());
    await audit.record({ actorUserId: auth.userId, action: "admin_password_changed", targetType: "user", targetId: auth.userId, requestId });
    if (isHtml) return jsonResponseWithCookies({ ok: true }, 200, requestId, expiredCookies(), 303, "/admin/login?signedout=1");
    return jsonResponseWithCookies({ ok: true }, 200, requestId, expiredCookies());
  }

  if (request.method === "POST" && path === "/v1/admin/logout") {
    const auth = await requireAdminUser(request, adminStore, sessions);
    if (!auth.ok) return respond({ error: auth.error }, auth.status);
    const guard = await readGuarded(auth.session, ADMIN_BODY_LIMITS.session);
    if (!guard.ok) return respond(guard.payload, guard.status);
    await sessions.revokeByCookie(request.headers.get("Cookie"));
    await audit.record({ actorUserId: auth.userId, action: "admin_session_revoked", targetType: "admin_web_session", requestId });
    return jsonResponseWithCookies({ ok: true }, 200, requestId, expiredCookies());
  }

  if (env.SINGLE_ADMIN_MODE && request.method === "POST" && path === "/admin/logout") {
    const auth = await requireAdminUser(request, adminStore, sessions);
    if (!auth.ok) return redirect("/admin/login");
    const guard = await readGuarded(auth.session, ADMIN_BODY_LIMITS.session);
    if (!guard.ok) return redirect("/admin/login");
    await sessions.revokeByCookie(request.headers.get("Cookie"));
    await audit.record({ actorUserId: auth.userId, action: "admin_session_revoked", targetType: "admin_web_session", requestId });
    return jsonResponseWithCookies({ ok: true }, 200, requestId, expiredCookies(), 303, "/admin/login");
  }

  if (request.method === "POST" && path === "/v1/admin/recovery") {
    // SINGLE_ADMIN_MODE has no public recovery path at all: the route is closed
    // before the secret is even read.
    if (env.SINGLE_ADMIN_MODE) return respond({ error: "not_found" }, 404);
    const limiter = createConfiguredRateLimiter(env, AUTH_RATE_LIMIT_RULES.adminRecovery);
    if (!(await limiter.check(`${AUTH_RATE_LIMIT_RULES.adminRecovery.name}:ip:${ipPrefixOf(request)}`)).allowed) {
      return respond({ error: "rate_limited" }, 429);
    }
    const secret = env.ADMIN_RECOVERY_SECRET;
    if (!secret) return respond({ error: "recovery_not_configured" }, 503);
    const provided = request.headers.get("Authorization")?.replace(/^Bearer\s+/i, "") ?? "";
    if (!(await timingSafeEqualHex(await sha256Hex(provided), await sha256Hex(secret)))) {
      return respond({ error: "unauthorized" }, 401);
    }
    // Only when no admin exists, or explicit recovery mode is enabled by deployment.
    const override = env.ADMIN_RECOVERY_OVERRIDE === "true";
    if ((await adminStore.countAdmins()) > 0 && !override) {
      return respond({ error: "recovery_not_allowed" }, 403);
    }
    const body = await readAdminBody(request, ADMIN_BODY_LIMITS.recovery);
    if (!body.ok) return respond(body.body, body.status);
    const input = new AdminInput(body.value);
    const userId = input.string("userId", { required: true, max: 128 });
    if (!input.ok || userId === undefined) return respond(input.failure, 400);
    if (!(await adminStore.userIsActive(userId))) return respond({ error: "user_not_found" }, 404);
    await adminStore.addRole(userId, "admin", null);
    await audit.record({
      action: "admin_role_granted_via_recovery",
      targetType: "user",
      targetId: userId,
      requestId,
      ipPrefixHash: await sha256Hex(ipPrefixOf(request))
    });
    // The recovery secret must be rotated by the operator after use (docs/SECURITY.md).
    return respond({ promoted: true });
  }

  if (isHtml) {
    // Unknown admin paths answer as a page so a mistyped URL is not a JSON blob
    // in the browser. There is deliberately no /admin/register route.
    return htmlResponse(
      adminPage("Not found", `<h1>Not found</h1>
<p>That admin page does not exist.</p>
<p class="muted">Web administration has no registration page and no account-creation shortcut.</p>
<p><a href="/admin">Back to dashboard</a></p>`),
      404,
      requestId
    );
  }
  return respond({ error: "not_found" }, 404);

  // ------------------------------------------------------- page re-rendering

  async function renderUsersPage(
    database: D1Database,
    environment: AdminRoutesEnv,
    session: AdminWebSession,
    id: string,
    options: {
      errors: FieldError[];
      errorsUserId: string | null;
      values: Record<string, string> | null;
      returnTo: string;
      status: number;
      bannerHtml?: string;
    }
  ): Promise<Response> {
    const params = parseUserPageParams(new URL(options.returnTo, "https://admin.local"));
    const query = params.ok ? params.query : { search: "", limit: USER_PAGE_LIMIT_DEFAULT, offset: 0, limitClamped: false };
    return htmlResponse(
      usersPage({
        formToken: await deriveAdminFormToken(session),
        page: await listUserPage(database, environment, query),
        errors: options.errors,
        errorsUserId: options.errorsUserId,
        values: options.values,
        adminEmail: await adminEmailOf(database, session.userId),
        bannerHtml: options.bannerHtml
      }),
      options.status,
      id
    );
  }

  async function renderSmtpPage(
    database: D1Database,
    environment: AdminRoutesEnv,
    session: AdminWebSession,
    id: string,
    options: {
      errors: FieldError[];
      values: Record<string, string> | null;
      status: number;
      bannerHtml?: string;
      testResult?: { kind: "success" | "warning" | "error"; text: string } | null;
    }
  ): Promise<Response> {
    return htmlResponse(
      smtpPage({
        formToken: await deriveAdminFormToken(session),
        settings: environment.ADMIN_SETTINGS_SERVICE?.smtpStatus() ?? { configured: false },
        values: options.values,
        testResult: options.testResult ?? null,
        history: await readSmtpTestHistory(environment, 5),
        adminEmail: await adminEmailOf(database, session.userId),
        errors: options.errors,
        bannerHtml: options.bannerHtml
      }),
      options.status,
      id
    );
  }

  async function renderSettingsPage(
    database: D1Database,
    environment: AdminRoutesEnv,
    store: D1AdminStore,
    session: AdminWebSession,
    id: string,
    errors: FieldError[],
    bannerText: string | undefined,
    status: number,
    values: Record<string, string> | null = null
  ): Promise<Response> {
    return htmlResponse(
      settingsPage({
        formToken: await deriveAdminFormToken(session),
        mode: await store.readRegistrationMode(),
        smtpConfigured: environment.ADMIN_SETTINGS_SERVICE?.smtpStatus().configured === true,
        values,
        errors,
        bannerHtml: bannerText ? banner("error", bannerText) : undefined
      }),
      status,
      id
    );
  }

  async function renderChangePasswordPage(
    database: D1Database,
    session: AdminWebSession,
    id: string,
    errors: FieldError[],
    bannerHtml: string | undefined,
    status: number
  ): Promise<Response> {
    return htmlResponse(
      changePasswordPage({
        formToken: await deriveAdminFormToken(session),
        values: null,
        adminEmail: await adminEmailOf(database, session.userId),
        errors,
        bannerHtml
      }),
      status,
      id
    );
  }

  async function renderAudioPage(
    database: D1Database,
    environment: AdminRoutesEnv,
    session: AdminWebSession,
    id: string,
    options: {
      errors: FieldError[];
      errorsId: string | null;
      values: Record<string, string> | null;
      status: number;
      checkParam?: string | null;
    }
  ): Promise<Response> {
    return htmlResponse(
      audioPage({
        formToken: await deriveAdminFormToken(session),
        rows: await listAudioProviders(database),
        checks: await readConnectivityChecks(environment),
        secretSlots: [...PROVIDER_SECRET_SLOTS],
        errors: options.errors,
        errorsId: options.errorsId,
        values: options.values,
        bannerHtml: options.checkParam ?? undefined
      }),
      options.status,
      id
    );
  }

  async function renderProvidersPage(
    environment: AdminRoutesEnv,
    session: AdminWebSession,
    id: string,
    options: { status: number; errors?: FieldError[]; values?: Record<string, string> | null; bannerHtml?: string }
  ): Promise<Response> {
    const formToken = await deriveAdminFormToken(session);
    // providerAdminPage returns the page body only; it must be wrapped in the
    // shell (document head, viewport meta, styles, skip link, section nav) or the
    // browser renders it as an unstyled ~980px fragment with no navigation.
    const body = await providerAdminPage(environment, {
      formToken,
      errors: options.errors,
      values: options.values,
      bannerHtml: options.bannerHtml
    });
    return htmlResponse(adminShell("AI providers", "providers", formToken, body), options.status, id);
  }

  /**
   * Provider-key page. Only the slot names and whether each slot is populated
   * are rendered; the values themselves are never read back out of the runtime.
   */
  function renderProviderKeys(
    environment: AdminRoutesEnv,
    formToken: string,
    errors: FieldError[] | undefined,
    values: Record<string, string> | null,
    bannerHtml: string | undefined
  ): string {
    const runtime = environment as Record<string, unknown>;
    const slots = PROVIDER_SECRET_SLOTS.map((slot) => ({
      slot,
      configured: typeof runtime[slot] === "string" && String(runtime[slot]).trim().length > 0
    }));
    return providerKeysPage({ formToken, slots, errors, values, bannerHtml });
  }
}

// ------------------------------------------------------------------ helpers

function sameOriginOk(request: Request): boolean {
  const origin = request.headers.get("Origin");
  // Absent (non-browser client) and opaque (a same-document form submission from
  // a page we served, serialized as "null") both mean "no usable origin"; the
  // request is then judged on its CSRF token and Sec-Fetch-Site instead. A real
  // but different origin is still refused.
  if (origin === null || isOpaqueOrigin(origin)) return true;
  try {
    return new URL(origin).host === new URL(request.url).host;
  } catch {
    return false;
  }
}

function matchesToken(expected: string | null, provided: string | null): boolean {
  if (!expected || !provided || expected.length !== provided.length) return false;
  let diff = 0;
  for (let i = 0; i < expected.length; i++) diff |= expected.charCodeAt(i) ^ provided.charCodeAt(i);
  return diff === 0;
}

function secFetchSiteOk(request: Request): boolean {
  const site = request.headers.get("Sec-Fetch-Site");
  return !site || site === "same-origin" || site === "same-site" || site === "none";
}

/**
 * Submitted values handed back to a re-rendered form. Credential-shaped fields
 * are dropped here as well as at the field level, so a failed submit can never
 * echo a password or API key into the page.
 */
const REDACTED_FORM_FIELDS = new Set(["password", "secret", "currentPassword", "newPassword", "confirmPassword"]);

function flattenValues(value: Record<string, unknown>): Record<string, string> {
  const flattened: Record<string, string> = {};
  for (const [key, entry] of Object.entries(value)) {
    if (entry === null || entry === undefined) continue;
    if (REDACTED_FORM_FIELDS.has(key)) continue;
    flattened[key] = typeof entry === "string" ? entry : Array.isArray(entry) ? entry.join(",") : String(entry);
  }
  return flattened;
}

function signInMessage(error: string): string {
  if (error === "account_disabled") return "That account is disabled.";
  if (error === "forbidden") return "That account is not an administrator.";
  return "Email or password is not correct.";
}

async function authenticateAdmin(
  db: D1Database,
  adminStore: D1AdminStore,
  email: string,
  password: string
): Promise<{ ok: true; userId: string } | { ok: false; status: number; error: string }> {
  const credential = await new D1EmailCredentialStore(db).findByNormalizedEmail(email);
  if (!credential || credential.disabledAt || !(await verifyPassword(password, credential.passwordHash))) {
    return { ok: false, status: 401, error: "invalid_credentials" };
  }
  if (!(await adminStore.userIsActive(credential.userId))) return { ok: false, status: 403, error: "account_disabled" };
  const roles = await adminStore.rolesForUser(credential.userId);
  if (!roles.includes("admin")) return { ok: false, status: 403, error: "forbidden" };
  return { ok: true, userId: credential.userId };
}

async function mustChangePassword(db: D1Database, userId: string): Promise<boolean> {
  const row = await db.prepare("SELECT must_change_password FROM admin_security WHERE user_id = ?")
    .bind(userId).first<{ must_change_password: number }>();
  return row?.must_change_password === 1;
}

async function adminEmailOf(db: D1Database, userId: string): Promise<string> {
  const row = await db.prepare(
    `SELECT COALESCE(e.normalized_email, i.email) AS email FROM users u
     LEFT JOIN email_credentials e ON e.user_id = u.id
     LEFT JOIN identities i ON i.user_id = u.id AND i.provider = 'email'
     WHERE u.id = ?`
  ).bind(userId).first<{ email: string | null }>();
  return row?.email ?? "";
}

/**
 * The sole administrator can never be edited, demoted or disabled from the
 * console: the account is rejected here, before any field is validated.
 */
async function protectSoleAdmin(
  db: D1Database,
  adminStore: D1AdminStore,
  userId: string,
  actorUserId: string
): Promise<{ status: number; body: Record<string, unknown> } | null> {
  const before = await db.prepare("SELECT id, status FROM users WHERE id = ?").bind(userId).first<{ id: string; status: string }>();
  if (!before) return { status: 404, body: { error: "user_not_found" } };
  if (userId === actorUserId || (await adminStore.rolesForUser(userId)).includes("admin")) {
    return { status: 403, body: { error: "admin_account_protected", message: "The administrator account cannot be changed here." } };
  }
  return null;
}

function validateUserUpdate(input: AdminInput):
  | { ok: true; value: { displayName: string; status: "active" | "disabled"; plan: "FREE" | "PRO"; quotaLimit: number } }
  | { ok: false; failure: InvalidRequest } {
  if (!input.has("displayName")) input.fail("displayName", "required");
  const displayName = input.string("displayName", { required: false, max: USER_DISPLAY_NAME_MAX });
  const status = input.enum("status", USER_STATUS_VALUES);
  const plan = input.enum("plan", USER_PLAN_VALUES);
  const quotaLimit = input.integer("quotaLimit", { min: 0, max: USER_QUOTA_MAX });
  if (!input.ok || displayName === undefined || status === undefined || plan === undefined || quotaLimit === undefined) {
    return { ok: false, failure: input.failure };
  }
  return { ok: true, value: { displayName, status, plan, quotaLimit } };
}

async function applyUserUpdate(
  db: D1Database,
  sessions: AdminWebSessionService,
  id: string,
  value: { displayName: string; status: string; plan: string; quotaLimit: number }
): Promise<void> {
  await db.batch([
    db.prepare("UPDATE users SET status = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ?").bind(value.status, id),
    db.prepare("UPDATE identities SET display_name = ?, updated_at = CURRENT_TIMESTAMP WHERE user_id = ?").bind(value.displayName.trim(), id),
    db.prepare("INSERT INTO membership_entitlements (user_id, plan, source) VALUES (?, ?, 'admin') ON CONFLICT(user_id) DO UPDATE SET plan = excluded.plan, source = 'admin', expires_at = NULL, updated_at = CURRENT_TIMESTAMP").bind(id, value.plan),
    db.prepare("INSERT INTO managed_ai_usage (user_id, period_key, used, quota_limit) VALUES (?, strftime('%Y-%m','now'), 0, ?) ON CONFLICT(user_id, period_key) DO UPDATE SET quota_limit = excluded.quota_limit, updated_at = CURRENT_TIMESTAMP").bind(id, value.quotaLimit)
  ]);
  if (value.status === "disabled") {
    // A disabled account must lose every live credential immediately.
    await sessions.revokeForUser(id, new Date().toISOString());
    await db.prepare("UPDATE sessions SET revoked_at = CURRENT_TIMESTAMP WHERE user_id = ? AND revoked_at IS NULL").bind(id).run();
  }
}

function validateSmtp(input: AdminInput, status: Record<string, unknown>): Record<string, unknown> | undefined {
  const configured = status?.configured === true;
  const host = input.string("host", {
    required: true,
    max: 253,
    format: { name: "hostname", test: (value) => /^[a-z0-9.-]{3,253}$/i.test(value) }
  });
  const port = input.integer("port", { min: 1, max: 65535 });
  const username = input.string("username", { required: true, max: 320 });
  const from = input.string("from", { required: true, max: 320, format: EMAIL_FORMAT });
  const password = input.string("password", { required: !configured, max: 4096, trim: false, noControlCharacters: false });
  const secure = input.boolean("secure", { fallback: false });
  if (
    !input.ok ||
    host === undefined ||
    port === undefined ||
    username === undefined ||
    from === undefined ||
    password === undefined ||
    secure === undefined
  ) {
    return undefined;
  }
  return { host, port, username, from, password, secure };
}

function validateAudioUpdate(input: AdminInput):
  | { ok: true; value: { model: string; voice: string; secretRef: string; priority: number; enabled: boolean } }
  | { ok: false; failure: InvalidRequest } {
  const model = input.string("model", {
    required: true,
    max: 100,
    format: { name: "model name", test: (value) => /^[a-zA-Z0-9._-]{2,100}$/.test(value) }
  });
  const voice = input.string("voice", { required: false, max: 80 });
  const secretRef = input.enum("secretRef", PROVIDER_SECRET_SLOTS as unknown as readonly string[]);
  const priority = input.integer("priority", { min: 0, max: 1000 });
  const enabled = input.boolean("enabled", { fallback: false });
  if (!input.ok || model === undefined || voice === undefined || secretRef === undefined || priority === undefined || enabled === undefined) {
    return { ok: false, failure: input.failure };
  }
  return { ok: true, value: { model, voice, secretRef, priority, enabled } };
}

async function applyAudioUpdate(
  db: D1Database,
  id: string,
  value: { model: string; voice: string; secretRef: string; priority: number; enabled: boolean }
): Promise<void> {
  await db.prepare("UPDATE audio_provider_configs SET model = ?, voice = ?, secret_ref = ?, enabled = ?, priority = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ?")
    .bind(value.model, value.voice || null, value.secretRef, value.enabled ? 1 : 0, value.priority, id).run();
}

async function listAudioProviders(db: D1Database): Promise<AudioProviderRow[]> {
  const rows = await db.prepare(
    "SELECT id, capability, adapter, model, voice, secret_ref, enabled, priority FROM audio_provider_configs ORDER BY capability, priority, id"
  ).all<AudioProviderRow>();
  return rows.results ?? [];
}

async function audioProviderById(db: D1Database, id: string): Promise<AudioProviderRow | null> {
  return db.prepare(
    "SELECT id, capability, adapter, model, voice, secret_ref, enabled, priority FROM audio_provider_configs WHERE id = ?"
  ).bind(id).first<AudioProviderRow>();
}

async function audioProviderExists(db: D1Database, id: string): Promise<boolean> {
  const row = await db.prepare("SELECT id FROM audio_provider_configs WHERE id = ?").bind(id).first<{ id: string }>();
  return Boolean(row);
}

async function runAudioCheck(
  env: AdminRoutesEnv,
  row: AudioProviderRow,
  actorUserId: string
): Promise<ConnectivityResult & { storedAt: string }> {
  if (row.enabled !== 1) {
    const disabled: ConnectivityResult = {
      status: "disabled",
      httpStatus: null,
      latencyMs: 0,
      detail: "preset_disabled",
      reachable: false,
      authenticated: false
    };
    const storedAt = await recordConnectivityCheck(env, { targetType: "audio_provider", targetId: row.id, result: disabled, actorUserId });
    return { ...disabled, storedAt };
  }
  const result = await probeAudioPreset(env, row);
  const storedAt = await recordConnectivityCheck(env, { targetType: "audio_provider", targetId: row.id, result, actorUserId });
  return { ...result, storedAt };
}

function connectivityBody(check: ConnectivityResult & { storedAt: string }): Record<string, unknown> {
  return {
    healthy: check.status === "ok",
    status: check.status,
    reachable: check.reachable,
    authenticated: check.authenticated,
    httpStatus: check.httpStatus,
    latencyMs: check.latencyMs,
    detail: check.detail,
    checkedAt: check.storedAt,
    stored: true
  };
}

function audioCheckBanner(params: URLSearchParams): string | null {
  const saved = params.get("saved");
  if (saved) return banner("success", `Saved ${saved}.`);
  const checked = params.get("checked");
  if (checked) {
    const status = params.get("result");
    const detail = params.get("detail");
    if (status === "ok") return banner("success", `${checked} answered and accepted the saved credential.`);
    const suffix = detail ? ` (${detail})` : "";
    const kind = status === "auth_rejected" || status === "disabled" ? "warning" : "error";
    return banner(kind, `${checked}: ${status}${suffix}.`);
  }
  if (params.get("error")) return banner("error", `That action was refused (${params.get("error")}).`);
  return null;
}

function smtpTestBanner(params: URLSearchParams): { kind: "success" | "warning" | "error"; text: string } | null {
  const test = params.get("test");
  if (!test) return null;
  const errorClass = params.get("errorClass");
  if (test === "sent") return { kind: "success", text: "The mail server accepted the test message." };
  if (test === "not_configured") return { kind: "warning", text: "No SMTP settings are saved, so no message was sent." };
  return { kind: "error", text: `The mail server refused the test message${errorClass ? ` (${errorClass})` : ""}.` };
}

function sessionCookies(token: string, csrfToken: string, expiresAt: string): string[] {
  return [
    `${ADMIN_COOKIE_NAME}=${token}; Secure; HttpOnly; SameSite=Strict; Path=/; Expires=${new Date(expiresAt).toUTCString()}`,
    // Double-submit CSRF cookie: readable by same-origin page script only.
    `${CSRF_COOKIE_NAME}=${csrfToken}; Secure; SameSite=Strict; Path=/; Expires=${new Date(expiresAt).toUTCString()}`
  ];
}

function expiredCookies(): string[] {
  return [
    `${ADMIN_COOKIE_NAME}=; Secure; HttpOnly; SameSite=Strict; Path=/; Max-Age=0`,
    `${CSRF_COOKIE_NAME}=; Secure; SameSite=Strict; Path=/; Max-Age=0`
  ];
}

function json(value: unknown, status: number, requestId: string): Response {
  return new Response(JSON.stringify(value), {
    status,
    headers: {
      "Content-Type": "application/json; charset=utf-8",
      "Cache-Control": "no-store",
      "X-Request-ID": requestId
    }
  });
}

function jsonResponseWithCookies(
  value: unknown,
  status: number,
  requestId: string,
  cookies: string[],
  redirectStatus?: number,
  location?: string
): Response {
  const headers = new Headers({
    "Content-Type": "application/json; charset=utf-8",
    "Cache-Control": "no-store",
    "X-Request-ID": requestId
  });
  for (const cookie of cookies) headers.append("Set-Cookie", cookie);
  if (redirectStatus && location) {
    headers.set("Location", location);
    return new Response(JSON.stringify(value), { status: redirectStatus, headers });
  }
  return new Response(JSON.stringify(value), { status, headers });
}

function redirect(location: string): Response {
  return new Response(null, { status: 302, headers: { Location: location, "Cache-Control": "no-store" } });
}

function redirectSeeOther(location: string): Response {
  return new Response(null, { status: 303, headers: { Location: location, "Cache-Control": "no-store" } });
}

async function timingSafeEqualHex(a: string, b: string): Promise<boolean> {
  if (a.length !== b.length) return false;
  let diff = 0;
  for (let i = 0; i < a.length; i++) diff |= a.charCodeAt(i) ^ b.charCodeAt(i);
  return diff === 0;
}

/** Re-exported for tests that need the untouched default period helper. */
export { defaultPeriod };

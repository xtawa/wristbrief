import { D1AdminStore } from "./adminStore";
import { AdminAuditLog } from "./adminAudit";
import { AdminWebSessionService, ADMIN_COOKIE_NAME } from "./adminSession";
import { requireAdminCsrf, requireAdminUser } from "./adminAuth";
import { adminPage, escapeHtml, htmlResponse } from "./adminHtml";
import { verifyPassword } from "../emailAuth/passwordHasher";
import { D1EmailCredentialStore } from "../emailAuth/emailCredentialStore";
import { AUTH_RATE_LIMIT_RULES, createConfiguredRateLimiter, ipPrefixOf } from "../rateLimit";
import { sha256Hex } from "../emailAuth/emailTokens";
import {
  deleteProviderConfig,
  listProviderConfigs,
  providerAdminPage,
  providerHealthCheck,
  upsertProviderConfig,
  type ProviderAdminEnv
} from "./providerAdmin";

export type AdminRoutesEnv = {
  ACCOUNT_DB?: D1Database;
  ADMIN_RECOVERY_SECRET?: string;
  ADMIN_RECOVERY_OVERRIDE?: string;
} & ProviderAdminEnv;

const CSRF_COOKIE_NAME = "wristbrief_admin_csrf";

/**
 * Dispatcher for /admin (server-rendered HTML) and /v1/admin (JSON API).
 * Returns null for non-admin paths so the caller's route chain continues.
 *
 * Authorization: admin web session cookie -> internal user_id -> active account
 * -> admin role. Mutating requests additionally require CSRF token + same-origin.
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

  // ---- public ----
  if (request.method === "GET" && path === "/v1/admin/bootstrap-status") {
    const bootstrap = await adminStore.readBootstrapState();
    const mode = await adminStore.readRegistrationMode();
    return respond({
      bootstrapCompleted: bootstrap?.bootstrapCompletedAt != null,
      registrationOpen: bootstrap?.firstAdminUserId == null
        ? bootstrap?.webRegistrationEnabled === true
        : mode === "OPEN"
    });
  }

  if (request.method === "GET" && (path === "/admin" || path === "/admin/")) {
    const auth = await requireAdminUser(request, adminStore, sessions);
    if (!auth.ok) return redirect("/admin/login");
    const bootstrap = await adminStore.readBootstrapState();
    const mode = await adminStore.readRegistrationMode();
    const admins = await adminStore.countAdmins();
    return htmlResponse(
      adminPage(
        "Dashboard",
        `<h1>WristBrief Admin</h1>
         <div class="card">
           <h2>Bootstrap</h2>
           <p>First admin: <code>${escapeHtml(bootstrap?.firstAdminUserId ?? "not claimed")}</code></p>
           <p>Completed at: ${escapeHtml(bootstrap?.bootstrapCompletedAt ?? "—")}</p>
           <p>Admins: ${admins}</p>
         </div>
         <div class="card">
           <h2>Registration</h2>
           <p>Mode: <code>${mode}</code></p>
           <p><a href="/admin/settings">Manage settings</a></p>
         </div>
         <div class="card">
           <h2>AI providers</h2>
           <p><a href="/admin/providers">Manage providers</a></p>
         </div>
         <p><a href="/admin/audit">Audit log</a> · <button id="logout">Sign out</button></p>
         ${logoutScript()}`
      ),
      200,
      requestId
    );
  }

  if (request.method === "GET" && path === "/admin/login") {
    const auth = await requireAdminUser(request, adminStore, sessions);
    if (auth.ok) return redirect("/admin");
    const bootstrap = await adminStore.readBootstrapState();
    const mode = await adminStore.readRegistrationMode();
    const registrationOpen = bootstrap?.firstAdminUserId == null
      ? bootstrap?.webRegistrationEnabled === true
      : mode === "OPEN";
    return htmlResponse(
      adminPage(
        "Sign in",
        `<h1>WristBrief Admin</h1>
         <div class="card">
           <h2>Sign in</h2>
           <label>Email<input id="email" type="email" autocomplete="username"></label>
           <label>Password<input id="password" type="password" autocomplete="current-password"></label>
           <button id="login">Sign in</button>
           <p id="msg" class="muted"></p>
         </div>
         ${registrationOpen ? registrationCard() : `<p class="muted">Registration is closed.</p>`}
         ${loginScript()}`
      ),
      200,
      requestId
    );
  }

  if (request.method === "GET" && (path === "/admin/settings" || path === "/admin/audit" || path === "/admin/providers")) {
    const auth = await requireAdminUser(request, adminStore, sessions);
    if (!auth.ok) return redirect("/admin/login");
    if (path === "/admin/providers") {
      return htmlResponse(adminPage("AI providers", await providerAdminPage(env)), 200, requestId);
    }
    if (path === "/admin/settings") {
      const mode = await adminStore.readRegistrationMode();
      return htmlResponse(
        adminPage(
          "Settings",
          `<h1>Settings</h1>
           <div class="card">
             <h2>Registration</h2>
             <p>Allow new registrations. The first-admin bootstrap can never be re-entered: new users are always ordinary accounts.</p>
             <p>Current mode: <code id="mode">${mode}</code></p>
             <button id="toggle">${mode === "OPEN" ? "Close registrations" : "Allow new registrations"}</button>
             <p id="msg" class="muted"></p>
           </div>
           <p><a href="/admin">Back to dashboard</a></p>
           ${settingsScript()}`
        ),
        200,
        requestId
      );
    }
    const entries = await audit.list(50);
    return htmlResponse(
      adminPage(
        "Audit log",
        `<h1>Audit log</h1>
         <div class="card">
         <table>
           <tr><th>When</th><th>Actor</th><th>Action</th><th>Target</th><th>Details</th></tr>
           ${entries
             .map(
               (entry) => `<tr>
                 <td>${escapeHtml(entry.createdAt)}</td>
                 <td><code>${escapeHtml(entry.actorUserId ?? "system")}</code></td>
                 <td>${escapeHtml(entry.action)}</td>
                 <td>${escapeHtml(entry.targetType ?? "")} ${escapeHtml(entry.targetId ?? "")}</td>
                 <td class="muted">${escapeHtml(entry.beforeJson ? `${entry.beforeJson} → ` : "")}${escapeHtml(entry.afterJson ?? "")}</td>
               </tr>`
             )
             .join("\n")}
         </table>
         </div>
         <p><a href="/admin">Back to dashboard</a></p>`
      ),
      200,
      requestId
    );
  }

  if (request.method === "POST" && path === "/v1/admin/session") {
    const limiter = createConfiguredRateLimiter(env, AUTH_RATE_LIMIT_RULES.adminLogin);
    if (!(await limiter.check(`${AUTH_RATE_LIMIT_RULES.adminLogin.name}:ip:${ipPrefixOf(request)}`)).allowed) {
      return respond({ error: "rate_limited" }, 429);
    }
    const body = await readJson(request);
    const email = typeof body?.email === "string" ? body.email.trim().toLowerCase() : "";
    const password = typeof body?.password === "string" ? body.password : "";
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
    return jsonResponseWithCookies(
      { csrfToken: issued.csrfToken, expiresAt: issued.expiresAt },
      200,
      requestId,
      sessionCookies(issued.token, issued.csrfToken, issued.expiresAt)
    );
  }

  if (request.method === "POST" && path === "/v1/admin/logout") {
    const auth = await requireAdminUser(request, adminStore, sessions);
    if (!auth.ok) return respond({ error: auth.error }, auth.status);
    if (!(await requireAdminCsrf(request, auth.session))) return respond({ error: "csrf_required" }, 403);
    await sessions.revokeByCookie(request.headers.get("Cookie"));
    await audit.record({ actorUserId: auth.userId, action: "admin_session_revoked", targetType: "admin_web_session", requestId });
    return jsonResponseWithCookies({ ok: true }, 200, requestId, expiredCookies());
  }

  if (request.method === "GET" && path === "/v1/admin/settings") {
    const auth = await requireAdminUser(request, adminStore, sessions);
    if (!auth.ok) return respond({ error: auth.error }, auth.status);
    return respond({ registrationMode: await adminStore.readRegistrationMode() });
  }

  if ((request.method === "PATCH" || request.method === "POST") && path === "/v1/admin/settings") {
    const auth = await requireAdminUser(request, adminStore, sessions);
    if (!auth.ok) return respond({ error: auth.error }, auth.status);
    if (!(await requireAdminCsrf(request, auth.session))) return respond({ error: "csrf_required" }, 403);
    const body = await readJson(request);
    const mode = (body as { registrationMode?: unknown })?.registrationMode;
    if (mode !== "OPEN" && mode !== "CLOSED") return respond({ error: "invalid_request" }, 400);
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
    return respond({ registrationMode: mode });
  }

  if (request.method === "GET" && path === "/v1/admin/providers") {
    const auth = await requireAdminUser(request, adminStore, sessions);
    if (!auth.ok) return respond({ error: auth.error }, auth.status);
    const result = await listProviderConfigs(env);
    return respond(result.body, result.status);
  }

  if (request.method === "POST" && path === "/v1/admin/providers") {
    const auth = await requireAdminUser(request, adminStore, sessions);
    if (!auth.ok) return respond({ error: auth.error }, auth.status);
    if (!(await requireAdminCsrf(request, auth.session))) return respond({ error: "csrf_required" }, 403);
    const body = await readJson(request);
    const result = await upsertProviderConfig(env, body ?? {}, auth.userId, audit, requestId, true);
    return respond(result.body, result.status);
  }

  if (request.method === "PATCH" && path.startsWith("/v1/admin/providers/")) {
    const auth = await requireAdminUser(request, adminStore, sessions);
    if (!auth.ok) return respond({ error: auth.error }, auth.status);
    if (!(await requireAdminCsrf(request, auth.session))) return respond({ error: "csrf_required" }, 403);
    const providerId = decodeURIComponent(path.slice("/v1/admin/providers/".length));
    const body = await readJson(request);
    const result = await upsertProviderConfig(env, { ...(body ?? {}), id: providerId }, auth.userId, audit, requestId, false);
    return respond(result.body, result.status);
  }

  if (request.method === "DELETE" && path.startsWith("/v1/admin/providers/")) {
    const auth = await requireAdminUser(request, adminStore, sessions);
    if (!auth.ok) return respond({ error: auth.error }, auth.status);
    if (!(await requireAdminCsrf(request, auth.session))) return respond({ error: "csrf_required" }, 403);
    const providerId = decodeURIComponent(path.slice("/v1/admin/providers/".length));
    const result = await deleteProviderConfig(env, providerId, auth.userId, audit, requestId);
    return respond(result.body, result.status);
  }

  if (request.method === "POST" && path.startsWith("/v1/admin/providers/") && path.endsWith("/health-check")) {
    const auth = await requireAdminUser(request, adminStore, sessions);
    if (!auth.ok) return respond({ error: auth.error }, auth.status);
    if (!(await requireAdminCsrf(request, auth.session))) return respond({ error: "csrf_required" }, 403);
    const providerId = decodeURIComponent(path.slice("/v1/admin/providers/".length, -"/health-check".length));
    const result = await providerHealthCheck(env, providerId);
    return respond(result.body, result.status);
  }

  if (request.method === "GET" && path === "/v1/admin/audit") {
    const auth = await requireAdminUser(request, adminStore, sessions);
    if (!auth.ok) return respond({ error: auth.error }, auth.status);
    const limit = Number(url.searchParams.get("limit") ?? 50);
    const offset = Number(url.searchParams.get("offset") ?? 0);
    return respond({ entries: await audit.list(limit, offset) });
  }

  if (request.method === "POST" && path === "/v1/admin/recovery") {
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
    const body = await readJson(request);
    const userId = (body as { userId?: unknown })?.userId;
    if (typeof userId !== "string" || !userId) return respond({ error: "invalid_request" }, 400);
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

  return respond({ error: "not_found" }, 404);
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

async function readJson(request: Request): Promise<Record<string, unknown> | null> {
  try {
    const value = await request.json();
    return value && typeof value === "object" ? (value as Record<string, unknown>) : null;
  } catch {
    return null;
  }
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

function jsonResponseWithCookies(value: unknown, status: number, requestId: string, cookies: string[]): Response {
  const headers = new Headers({
    "Content-Type": "application/json; charset=utf-8",
    "Cache-Control": "no-store",
    "X-Request-ID": requestId
  });
  for (const cookie of cookies) headers.append("Set-Cookie", cookie);
  return new Response(JSON.stringify(value), { status, headers });
}

function redirect(location: string): Response {
  return new Response(null, { status: 302, headers: { Location: location, "Cache-Control": "no-store" } });
}

async function timingSafeEqualHex(a: string, b: string): Promise<boolean> {
  if (a.length !== b.length) return false;
  let diff = 0;
  for (let i = 0; i < a.length; i++) diff |= a.charCodeAt(i) ^ b.charCodeAt(i);
  return diff === 0;
}

function csrfCookieScript(): string {
  return `function csrfToken(){const m=document.cookie.match(/(?:^|; )${CSRF_COOKIE_NAME}=([^;]+)/);return m?decodeURIComponent(m[1]):"";}`;
}

function registrationCard(): string {
  return `<div class="card">
    <h2>Create the first admin account</h2>
    <p class="muted">The first registration becomes the administrator and closes the bootstrap window. Later registrations are ordinary accounts and must be allowed from Settings.</p>
    <label>Email<input id="reg-email" type="email" autocomplete="username"></label>
    <label>Password<input id="reg-password" type="password" autocomplete="new-password"></label>
    <button id="register">Create account</button>
    <p id="reg-msg" class="muted"></p>
  </div>`;
}

function loginScript(): string {
  return `<script>
${csrfCookieScript()}
async function post(path, body){
  const res = await fetch(path, { method: "POST", headers: { "Content-Type": "application/json", "X-CSRF-Token": csrfToken() }, body: JSON.stringify(body) });
  return { status: res.status, body: await res.json().catch(() => ({})) };
}
document.getElementById("login").onclick = async () => {
  const r = await post("/v1/admin/session", { email: document.getElementById("email").value, password: document.getElementById("password").value });
  if (r.status === 200) { location.href = "/admin"; return; }
  document.getElementById("msg").textContent = r.body.error ?? "Sign-in failed";
};
const registerBtn = document.getElementById("register");
if (registerBtn) registerBtn.onclick = async () => {
  const r = await post("/v1/auth/email/register", { email: document.getElementById("reg-email").value, password: document.getElementById("reg-password").value });
  if (r.status === 200) { document.getElementById("reg-msg").textContent = "Account created. Sign in below."; return; }
  document.getElementById("reg-msg").textContent = r.body.error ?? "Registration failed";
};
</script>`;
}

function settingsScript(): string {
  return `<script>
${csrfCookieScript()}
document.getElementById("toggle").onclick = async () => {
  const current = document.getElementById("mode").textContent;
  const next = current === "OPEN" ? "CLOSED" : "OPEN";
  const res = await fetch("/v1/admin/settings", { method: "PATCH", headers: { "Content-Type": "application/json", "X-CSRF-Token": csrfToken() }, body: JSON.stringify({ registrationMode: next }) });
  const body = await res.json().catch(() => ({}));
  if (res.ok) { location.reload(); return; }
  document.getElementById("msg").textContent = body.error ?? "Update failed";
};
</script>`;
}

function logoutScript(): string {
  return `<script>
${csrfCookieScript()}
document.getElementById("logout").onclick = async () => {
  await fetch("/v1/admin/logout", { method: "POST", headers: { "X-CSRF-Token": csrfToken() } });
  location.href = "/admin/login";
};
</script>`;
}

import { D1AdminStore } from "./adminStore";
import { AdminAuditLog } from "./adminAudit";
import { AdminWebSessionService, ADMIN_COOKIE_NAME } from "./adminSession";
import { requireAdminCsrf, requireAdminUser } from "./adminAuth";
import { adminPage, escapeHtml, htmlResponse } from "./adminHtml";
import { hashPassword, verifyPassword } from "../emailAuth/passwordHasher";
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
  SINGLE_ADMIN_MODE?: boolean;
  FREE_AI_MONTHLY_LIMIT?: string;
  PRO_AI_MONTHLY_LIMIT?: string;
  ADMIN_SETTINGS_SERVICE?: {
    smtpStatus(): Record<string, unknown>;
    saveSmtp(value: unknown): Promise<boolean>;
    setProviderSecret(slot: string, secret: string): Promise<boolean>;
  };
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

  if (env.SINGLE_ADMIN_MODE && !["/admin/login", "/v1/admin/session", "/v1/admin/bootstrap-status", "/admin/change-password", "/v1/admin/change-password", "/v1/admin/logout"].includes(path)) {
    const auth = await requireAdminUser(request, adminStore, sessions);
    if (auth.ok) {
      const security = await db.prepare("SELECT must_change_password FROM admin_security WHERE user_id = ?")
        .bind(auth.userId).first<{ must_change_password: number }>();
      if (security?.must_change_password === 1) {
        return path.startsWith("/admin") ? redirect("/admin/change-password") : respond({ error: "password_change_required" }, 403);
      }
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
           <h2>Mobile account registration</h2>
           <p>Mode: <code>${mode}</code></p>
           <p><a href="/admin/settings">Manage settings</a></p>
         </div>
         <div class="card">
           <h2>AI providers</h2>
           <p><a href="/admin/providers">Manage providers</a></p>
         </div>
         ${env.SINGLE_ADMIN_MODE ? `<div class="card"><h2>People and delivery</h2>
           <p><a href="/admin/users">Manage users and membership</a> · <a href="/admin/smtp">SMTP email</a> · <a href="/admin/provider-keys">Provider keys</a> · <a href="/admin/audio">Speech providers</a></p></div>` : ""}
         <p><a href="/admin/audit">Audit log</a> · <button id="logout">Sign out</button></p>
         ${logoutScript()}`
      ),
      200,
      requestId
    );
  }

  if (env.SINGLE_ADMIN_MODE && request.method === "GET" && ["/admin/users", "/admin/smtp", "/admin/provider-keys"].includes(path)) {
    const auth = await requireAdminUser(request, adminStore, sessions);
    if (!auth.ok) return redirect("/admin/login");
    if (path === "/admin/users") {
      const rows = await listUsers(db, 100, env);
      return htmlResponse(adminPage("Users", `<h1>Users</h1><p>Membership edits are manual overrides. The sole administrator cannot be edited here.</p>
        <div class="card"><table><thead><tr><th>Email</th><th>Name</th><th>Account</th><th>Role</th><th>Plan</th><th>AI quota / month</th><th>Action</th></tr></thead><tbody>
        ${rows.map((row) => `<tr><td>${escapeHtml(row.email ?? row.id)}</td><td><input id="name-${escapeHtml(row.id)}" value="${escapeHtml(row.display_name ?? "")}" maxlength="120"></td><td><select id="status-${escapeHtml(row.id)}"><option value="active" ${row.status === "active" ? "selected" : ""}>Active</option><option value="disabled" ${row.status === "disabled" ? "selected" : ""}>Disabled</option></select></td><td>user</td>
          <td><select id="plan-${escapeHtml(row.id)}"><option value="FREE" ${row.plan === "FREE" ? "selected" : ""}>Free</option><option value="PRO" ${row.plan === "PRO" ? "selected" : ""}>Member</option></select></td>
          <td><input id="quota-${escapeHtml(row.id)}" type="number" min="0" max="10000" value="${row.quota ?? 0}"></td>
          <td><button data-user="${escapeHtml(row.id)}">Save</button></td></tr>`).join("")}</tbody></table><p id="msg" role="status"></p></div>
        <p><a href="/admin">Back to dashboard</a></p><script>${csrfCookieScript()}
        document.querySelectorAll('[data-user]').forEach(button=>button.onclick=async()=>{const id=button.dataset.user;
          const res=await fetch('/v1/admin/users/'+encodeURIComponent(id),{method:'PATCH',headers:{'Content-Type':'application/json','X-CSRF-Token':csrfToken()},body:JSON.stringify({displayName:document.getElementById('name-'+id).value,status:document.getElementById('status-'+id).value,plan:document.getElementById('plan-'+id).value,quotaLimit:Number(document.getElementById('quota-'+id).value)})});
          const body=await res.json().catch(()=>({}));document.getElementById('msg').textContent=res.ok?'Saved':body.error||'Update failed';});</script>`), 200, requestId);
    }
    if (path === "/admin/smtp") {
      const settings = env.ADMIN_SETTINGS_SERVICE?.smtpStatus() ?? { configured: false };
      return htmlResponse(adminPage("SMTP", `<h1>Email delivery</h1><p>Credentials are encrypted in the server database. Leave password blank to keep the saved value.</p>
        <div class="card"><label>SMTP host<input id="host" value="${escapeHtml(String(settings.host ?? ""))}"></label>
        <label>Port<input id="port" type="number" value="${settings.port ?? 587}"></label>
        <label>Username<input id="username" value="${escapeHtml(String(settings.username ?? ""))}"></label>
        <label>Password<input id="password" type="password" autocomplete="new-password"></label>
        <label>From address<input id="from" value="${escapeHtml(String(settings.from ?? ""))}"></label>
        <label><input id="secure" type="checkbox" ${settings.secure ? "checked" : ""}> Direct TLS (port 465)</label>
        <button id="save">Save SMTP</button><p id="msg" role="status"></p></div>
        <p><a href="/admin">Back to dashboard</a></p><script>${csrfCookieScript()}
        document.getElementById('save').onclick=async()=>{const value=id=>document.getElementById(id).value;
          const res=await fetch('/v1/admin/smtp',{method:'PUT',headers:{'Content-Type':'application/json','X-CSRF-Token':csrfToken()},body:JSON.stringify({host:value('host'),port:Number(value('port')),username:value('username'),password:value('password'),from:value('from'),secure:document.getElementById('secure').checked})});
          const body=await res.json().catch(()=>({}));document.getElementById('msg').textContent=res.ok?'Saved':body.error||'Update failed';};</script>`), 200, requestId);
    }
    return htmlResponse(adminPage("Provider keys", `<h1>Provider API keys</h1><p>Choose the same secret slot used in a model configuration. Existing key values cannot be read back.</p>
      <div class="card"><label>Secret slot<select id="slot">${Array.from({ length: 10 }, (_, i) => `<option>AI_PROVIDER_SECRET_${i + 1}</option>`).join("")}</select></label>
      <label>New API key<input id="secret" type="password" autocomplete="off"></label><button id="save">Save key</button><p id="msg" role="status"></p></div>
      <p><a href="/admin/providers">Models</a> · <a href="/admin">Dashboard</a></p><script>${csrfCookieScript()}
      document.getElementById('save').onclick=async()=>{const res=await fetch('/v1/admin/provider-keys',{method:'PUT',headers:{'Content-Type':'application/json','X-CSRF-Token':csrfToken()},body:JSON.stringify({slot:document.getElementById('slot').value,secret:document.getElementById('secret').value})});
      const body=await res.json().catch(()=>({}));document.getElementById('msg').textContent=res.ok?'Saved':body.error||'Update failed';document.getElementById('secret').value='';};</script>`), 200, requestId);
  }

  if (env.SINGLE_ADMIN_MODE && request.method === "GET" && path === "/admin/audio") {
    const auth = await requireAdminUser(request, adminStore, sessions);
    if (!auth.ok) return redirect("/admin/login");
    const rows = (await db.prepare("SELECT id, capability, adapter, model, voice, secret_ref, enabled, priority FROM audio_provider_configs ORDER BY capability, priority").all<{
      id: string; capability: string; adapter: string; model: string; voice: string | null; secret_ref: string; enabled: number; priority: number
    }>()).results;
    return htmlResponse(adminPage("Speech providers", `<h1>Speech providers</h1><p>STT transcribes podcast audio. TTS turns text into MP3. Both start disabled until you save a key and enable a provider.</p>
      <div class="card"><table><thead><tr><th>ID</th><th>Task</th><th>Service</th><th>Model</th><th>Voice</th><th>Key slot</th><th>Priority</th><th>Enabled</th><th></th></tr></thead><tbody>
      ${rows.map((r) => `<tr data-id="${escapeHtml(r.id)}"><td>${escapeHtml(r.id)}</td><td>${escapeHtml(r.capability)}</td><td>${escapeHtml(r.adapter)}</td><td><input data-field="model" value="${escapeHtml(r.model)}"></td><td><input data-field="voice" value="${escapeHtml(r.voice ?? "")}"></td><td><select data-field="secretRef">${Array.from({length:10},(_,i)=>`<option ${r.secret_ref===`AI_PROVIDER_SECRET_${i+1}`?"selected":""}>AI_PROVIDER_SECRET_${i+1}</option>`).join("")}</select></td><td><input data-field="priority" type="number" value="${r.priority}"></td><td><input data-field="enabled" type="checkbox" ${r.enabled?"checked":""}></td><td><button data-save="${escapeHtml(r.id)}">Save</button></td></tr>`).join("")}</tbody></table><p id="msg" role="status"></p></div>
      <p><a href="/admin/provider-keys">Save API keys</a> · <a href="/admin">Dashboard</a></p><script>${csrfCookieScript()}
      document.querySelectorAll('[data-save]').forEach(b=>b.onclick=async()=>{const tr=b.closest('tr');const field=k=>tr.querySelector('[data-field="'+k+'"]');const res=await fetch('/v1/admin/audio/'+encodeURIComponent(b.dataset.save),{method:'PATCH',headers:{'Content-Type':'application/json','X-CSRF-Token':csrfToken()},body:JSON.stringify({model:field('model').value,voice:field('voice').value,secretRef:field('secretRef').value,priority:Number(field('priority').value),enabled:field('enabled').checked})});document.getElementById('msg').textContent=res.ok?'Saved':(await res.json()).error;});</script>`),200,requestId);
  }

  if (env.SINGLE_ADMIN_MODE && path === "/v1/admin/audio" && request.method === "GET") {
    const auth = await requireAdminUser(request, adminStore, sessions);
    if (!auth.ok) return respond({error:auth.error},auth.status);
    const rows = await db.prepare("SELECT id, capability, adapter, model, voice, secret_ref, enabled, priority FROM audio_provider_configs ORDER BY capability, priority").all();
    return respond({providers:rows.results});
  }

  if (env.SINGLE_ADMIN_MODE && path.startsWith("/v1/admin/audio/") && request.method === "PATCH") {
    const auth = await requireAdminUser(request, adminStore, sessions);
    if (!auth.ok) return respond({error:auth.error},auth.status);
    if (!(await requireAdminCsrf(request,auth.session))) return respond({error:"csrf_required"},403);
    const id = decodeURIComponent(path.slice("/v1/admin/audio/".length));
    const existing = await db.prepare("SELECT id FROM audio_provider_configs WHERE id = ?").bind(id).first();
    if (!existing) return respond({error:"provider_not_found"},404);
    const body = await readJson(request);
    if (!body || typeof body.model !== "string" || !/^[a-zA-Z0-9._-]{2,100}$/.test(body.model) ||
      typeof body.voice !== "string" || body.voice.length > 80 ||
      typeof body.secretRef !== "string" || !/^AI_PROVIDER_SECRET_([1-9]|10)$/.test(body.secretRef) ||
      typeof body.enabled !== "boolean" || !Number.isInteger(body.priority) || (body.priority as number) < 0 || (body.priority as number) > 1000) {
      return respond({error:"invalid_audio_provider"},400);
    }
    await db.prepare("UPDATE audio_provider_configs SET model = ?, voice = ?, secret_ref = ?, enabled = ?, priority = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ?")
      .bind(body.model,body.voice || null,body.secretRef,body.enabled?1:0,body.priority,id).run();
    await audit.record({actorUserId:auth.userId,action:"audio_provider_updated",targetType:"audio_provider",targetId:id,requestId});
    return respond({ok:true});
  }

  if (request.method === "GET" && path === "/admin/login") {
    const auth = await requireAdminUser(request, adminStore, sessions);
    if (auth.ok) return redirect("/admin");
    const bootstrap = await adminStore.readBootstrapState();
    const mode = await adminStore.readRegistrationMode();
    const registrationOpen = !env.SINGLE_ADMIN_MODE && (bootstrap?.firstAdminUserId == null
      ? bootstrap?.webRegistrationEnabled === true
      : mode === "OPEN");
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
         ${registrationOpen ? registrationCard() : env.SINGLE_ADMIN_MODE ? "" : `<p class="muted">Registration is closed.</p>`}
         ${loginScript()}`
      ),
      200,
      requestId
    );
  }

  if (request.method === "GET" && path === "/admin/change-password" && env.SINGLE_ADMIN_MODE) {
    const auth = await requireAdminUser(request, adminStore, sessions);
    if (!auth.ok) return redirect("/admin/login");
    const security = await db.prepare("SELECT must_change_password FROM admin_security WHERE user_id = ?")
      .bind(auth.userId).first<{ must_change_password: number }>();
    if (security?.must_change_password !== 1) return redirect("/admin");
    return htmlResponse(adminPage("Change temporary password", `<h1>Set your own password</h1>
      <p>The temporary password must be replaced before using the admin panel.</p>
      <div class="card"><label>Temporary password<input id="old" type="password" autocomplete="current-password"></label>
      <label>New password (at least 16 characters)<input id="next" type="password" autocomplete="new-password"></label>
      <button id="change">Change password</button><p id="msg" role="status"></p></div>
      <script>${csrfCookieScript()}
      document.getElementById('change').onclick=async()=>{
        const res=await fetch('/v1/admin/change-password',{method:'POST',headers:{'Content-Type':'application/json','X-CSRF-Token':csrfToken()},body:JSON.stringify({currentPassword:document.getElementById('old').value,newPassword:document.getElementById('next').value})});
        const body=await res.json().catch(()=>({}));if(res.ok){location.href='/admin/login';return;}
        document.getElementById('msg').textContent=body.error||'Password change failed';};</script>`), 200, requestId);
  }

  if (request.method === "POST" && path === "/v1/admin/change-password" && env.SINGLE_ADMIN_MODE) {
    const auth = await requireAdminUser(request, adminStore, sessions);
    if (!auth.ok) return respond({ error: auth.error }, auth.status);
    if (!(await requireAdminCsrf(request, auth.session))) return respond({ error: "csrf_required" }, 403);
    const body = await readJson(request);
    const current = body?.currentPassword;
    const next = body?.newPassword;
    if (typeof current !== "string" || typeof next !== "string" || next.length < 16 || next.length > 256 || current === next) {
      return respond({ error: "invalid_password" }, 400);
    }
    const credential = await new D1EmailCredentialStore(db).findByNormalizedEmail("zeromostia@gmail.com");
    if (!credential || credential.userId !== auth.userId || !(await verifyPassword(current, credential.passwordHash))) {
      return respond({ error: "invalid_credentials" }, 401);
    }
    await new D1EmailCredentialStore(db).updatePasswordHash(credential.id, await hashPassword(next));
    await db.prepare("UPDATE admin_security SET must_change_password = 0, password_changed_at = CURRENT_TIMESTAMP WHERE user_id = ?")
      .bind(auth.userId).run();
    await sessions.revokeForUser(auth.userId, new Date().toISOString());
    await audit.record({ actorUserId: auth.userId, action: "admin_password_changed", targetType: "user", targetId: auth.userId, requestId });
    return jsonResponseWithCookies({ ok: true }, 200, requestId, expiredCookies());
  }

  if (env.SINGLE_ADMIN_MODE && request.method === "GET" && path === "/v1/admin/users") {
    const auth = await requireAdminUser(request, adminStore, sessions);
    if (!auth.ok) return respond({ error: auth.error }, auth.status);
    return respond({ users: await listUsers(db, 100, env) });
  }

  if (env.SINGLE_ADMIN_MODE && request.method === "PATCH" && path.startsWith("/v1/admin/users/")) {
    const auth = await requireAdminUser(request, adminStore, sessions);
    if (!auth.ok) return respond({ error: auth.error }, auth.status);
    if (!(await requireAdminCsrf(request, auth.session))) return respond({ error: "csrf_required" }, 403);
    const id = decodeURIComponent(path.slice("/v1/admin/users/".length));
    if (!id || id.includes("/")) return respond({ error: "invalid_user" }, 400);
    const before = await db.prepare("SELECT id, status FROM users WHERE id = ?").bind(id).first<{ id: string; status: string }>();
    if (!before) return respond({ error: "user_not_found" }, 404);
    if (id === auth.userId || (await adminStore.rolesForUser(id)).includes("admin")) return respond({ error: "admin_account_protected" }, 403);
    const body = await readJson(request);
    const { status, plan, quotaLimit, displayName } = body ?? {};
    if ((status !== "active" && status !== "disabled") || (plan !== "FREE" && plan !== "PRO") ||
        !Number.isInteger(quotaLimit) || (quotaLimit as number) < 0 || (quotaLimit as number) > 10000 ||
        typeof displayName !== "string" || displayName.length > 120 || /[\u0000-\u001f]/.test(displayName)) {
      return respond({ error: "invalid_user_update" }, 400);
    }
    await db.batch([
      db.prepare("UPDATE users SET status = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ?").bind(status, id),
      db.prepare("UPDATE identities SET display_name = ?, updated_at = CURRENT_TIMESTAMP WHERE user_id = ?").bind(displayName.trim(), id),
      db.prepare("INSERT INTO membership_entitlements (user_id, plan, source) VALUES (?, ?, 'admin') ON CONFLICT(user_id) DO UPDATE SET plan = excluded.plan, source = 'admin', expires_at = NULL, updated_at = CURRENT_TIMESTAMP").bind(id, plan),
      db.prepare("INSERT INTO managed_ai_usage (user_id, period_key, used, quota_limit) VALUES (?, strftime('%Y-%m','now'), 0, ?) ON CONFLICT(user_id, period_key) DO UPDATE SET quota_limit = excluded.quota_limit, updated_at = CURRENT_TIMESTAMP").bind(id, quotaLimit)
    ]);
    if (status === "disabled") {
      await sessions.revokeForUser(id, new Date().toISOString());
      await db.prepare("UPDATE sessions SET revoked_at = CURRENT_TIMESTAMP WHERE user_id = ? AND revoked_at IS NULL").bind(id).run();
    }
    await audit.record({ actorUserId: auth.userId, action: "user_updated", targetType: "user", targetId: id,
      before: { status: before.status }, after: { status, plan, quotaLimit, displayName: displayName.trim() }, requestId });
    return respond({ ok: true });
  }

  if (env.SINGLE_ADMIN_MODE && request.method === "PUT" && (path === "/v1/admin/smtp" || path === "/v1/admin/provider-keys")) {
    const auth = await requireAdminUser(request, adminStore, sessions);
    if (!auth.ok) return respond({ error: auth.error }, auth.status);
    if (!(await requireAdminCsrf(request, auth.session))) return respond({ error: "csrf_required" }, 403);
    if (!env.ADMIN_SETTINGS_SERVICE) return respond({ error: "settings_unavailable" }, 503);
    const body = await readJson(request);
    if (path === "/v1/admin/smtp") {
      if (!(await env.ADMIN_SETTINGS_SERVICE.saveSmtp(body))) return respond({ error: "invalid_smtp_settings" }, 400);
      await audit.record({ actorUserId: auth.userId, action: "smtp_updated", targetType: "system_setting", targetId: "smtp", requestId });
    } else {
      if (typeof body?.slot !== "string" || typeof body?.secret !== "string" || !(await env.ADMIN_SETTINGS_SERVICE.setProviderSecret(body.slot, body.secret))) {
        return respond({ error: "invalid_provider_secret" }, 400);
      }
      await audit.record({ actorUserId: auth.userId, action: "provider_secret_updated", targetType: "system_setting", targetId: body.slot, requestId });
    }
    return respond({ ok: true });
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
             <h2>Mobile account registration</h2>
             <p>Allow new mobile accounts through the API. Web administration has no registration. New users are always ordinary accounts.</p>
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
    if (env.SINGLE_ADMIN_MODE && mode === "OPEN" && env.ADMIN_SETTINGS_SERVICE?.smtpStatus().configured !== true) {
      return respond({ error: "smtp_not_configured" }, 409);
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

type ManagedUser = { id: string; email: string | null; display_name: string | null; status: string; plan: string; quota: number | null };

async function listUsers(db: D1Database, limit: number, env: AdminRoutesEnv): Promise<ManagedUser[]> {
  const rows = await db.prepare(`SELECT u.id, COALESCE(e.normalized_email, g.email) AS email,
    COALESCE(i.display_name, g.display_name) AS display_name, u.status,
    COALESCE(m.plan, 'FREE') AS plan, COALESCE(q.quota_limit, CASE WHEN m.plan = 'PRO' THEN ? ELSE ? END) AS quota
    FROM users u
    LEFT JOIN email_credentials e ON e.user_id = u.id
    LEFT JOIN identities i ON i.user_id = u.id AND i.provider = 'email'
    LEFT JOIN identities g ON g.user_id = u.id AND g.provider = 'google'
    LEFT JOIN membership_entitlements m ON m.user_id = u.id
    LEFT JOIN managed_ai_usage q ON q.user_id = u.id AND q.period_key = strftime('%Y-%m','now')
    WHERE NOT EXISTS (SELECT 1 FROM user_roles r WHERE r.user_id = u.id AND r.role = 'admin')
    ORDER BY u.created_at DESC LIMIT ?`).bind(Number(env.PRO_AI_MONTHLY_LIMIT || 0), Number(env.FREE_AI_MONTHLY_LIMIT || 0), limit).all<ManagedUser>();
  return rows.results;
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

/**
 * Server-rendered HTML for every /admin page.
 *
 * All pages share `adminPage` (shell + CSS), `adminNav` (section links and a real
 * sign-out POST form) and the form helpers, so each page is a small amount of
 * markup. Every mutation on these pages has a real `<form method="post">`; the
 * inline scripts that the pages still ship are a convenience layer on top and
 * never the only path to an action.
 */
import { adminPage, escapeHtml } from "./adminHtml";
import {
  adminNav,
  adminShell,
  banner,
  checkboxField,
  errorSummary,
  formSecurityFields,
  paginationNav,
  searchForm,
  selectField,
  submitButton,
  textField
} from "./adminForms";
import type { FieldError } from "./adminValidation";
import type { ManagedUser, UserPage } from "./adminUsers";
import { USER_DISPLAY_NAME_MAX, USER_PAGE_LIMIT_MAX, USER_QUOTA_MAX } from "./adminUsers";
import type { OperationalOverview } from "./adminOverview";
import { describeConnectivity, type StoredConnectivityCheck } from "./adminConnectivity";
import type { SmtpTestHistoryRow } from "./adminSmtpTest";
import type { AuditRow } from "./adminAudit";

export type PageOptions = {
  formToken: string;
  errors?: FieldError[];
  errorPrefix?: string;
  /** Pre-rendered banner markup (use `banner()` to build it). */
  bannerHtml?: string;
};

function shell(title: string, current: string, formToken: string, body: string): string {
  return adminShell(title, current, formToken, body);
}

function backLink(href: string, label: string): string {
  return `<p><a href="${escapeHtml(href)}">${escapeHtml(label)}</a></p>`;
}

function pick(values: Record<string, string> | null | undefined, key: string, fallback: string): string {
  const value = values?.[key];
  return value === undefined ? fallback : value;
}

function text(value: unknown, fallback = "—"): string {
  if (value === null || value === undefined || value === "") return fallback;
  return escapeHtml(String(value));
}

// ---------------------------------------------------------------- dashboard

export function dashboardPage(options: {
  formToken: string;
  bootstrap: { firstAdminUserId: string | null; bootstrapCompletedAt: string | null } | null;
  admins: number;
  mode: string;
  singleAdminMode: boolean;
  overview: OperationalOverview;
}): string {
  const jobs = options.overview.jobs;
  const quota = options.overview.quota;
  const health = options.overview.empty.noFailures
    ? `<p class="muted">No failed jobs recorded.</p>`
    : `<p><strong>${jobs.failed}</strong> failed job${jobs.failed === 1 ? "" : "s"} recorded — <a href="/admin/overview">review them</a>.</p>`;
  const body = `<h1>WristBrief Admin</h1>
<div class="card">
  <h2>This deployment</h2>
  <p>Administrator: <code>${text(options.bootstrap?.firstAdminUserId, "not claimed")}</code></p>
  <p>Bootstrap completed: ${text(options.bootstrap?.bootstrapCompletedAt)}</p>
  <p>Administrator accounts: <strong>${options.admins}</strong>${options.singleAdminMode ? " (single-admin mode: exactly one is allowed)" : ""}</p>
  <p>Mobile account registration: <code>${escapeHtml(options.mode)}</code> · <a href="/admin/settings">Manage settings</a></p>
</div>
<div class="card">
  <h2>At a glance</h2>
  <dl class="stats">
    <div><dt>Jobs recorded</dt><dd>${jobs.total}</dd></div>
    <div><dt>Active jobs</dt><dd>${jobs.active}</dd></div>
    <div><dt>Failed jobs</dt><dd>${jobs.failed}</dd></div>
    <div><dt>Accounts tracked</dt><dd>${quota.totalUsers}</dd></div>
  </dl>
  ${health}
  <p><a href="/admin/overview">Open the operational overview</a></p>
</div>
<div class="card">
  <h2>People and delivery</h2>
  <p><a href="/admin/users">Users and membership</a> · <a href="/admin/smtp">SMTP email</a> · <a href="/admin/provider-keys">Provider keys</a> · <a href="/admin/audio">Speech providers</a></p>
</div>
<div class="card">
  <h2>AI providers</h2>
  <p><a href="/admin/providers">Manage providers</a> · <a href="/admin/audit">Audit log</a></p>
</div>`;
  return shell("Dashboard", "dashboard", options.formToken, body);
}

// ----------------------------------------------------------------- overview

export function overviewPage(options: PageOptions & { overview: OperationalOverview }): string {
  const { overview } = options;
  const statusRows = overview.jobs.byStatus.length
    ? overview.jobs.byStatus
        .map(
          (row) =>
            `<tr><td><code>${escapeHtml(row.status)}</code></td><td>${row.count}</td><td>${
              overview.jobs.total ? Math.round((row.count / overview.jobs.total) * 100) : 0
            }%</td></tr>`
        )
        .join("")
    : "";
  const failureRows = overview.recentFailures
    .map(
      (failure) => `<tr>
        <td><code class="wrap">${escapeHtml(failure.jobId)}</code></td>
        <td>${escapeHtml(failure.artifactType)}</td>
        <td>${escapeHtml(failure.language)}</td>
        <td><code>${text(failure.errorCode, "none")}</code></td>
        <td>${failure.attempts}</td>
        <td>${failure.occurredAt ? `<time datetime="${escapeHtml(failure.occurredAt)}">${escapeHtml(failure.occurredAt)}</time>` : "—"}</td>
      </tr>`
    )
    .join("");
  const quotaRows = overview.quota.rows
    .map(
      (row) => `<tr>
        <td>${text(row.email ?? row.displayName ?? row.userId)}</td>
        <td>${escapeHtml(row.plan)}</td>
        <td>${row.used}</td>
        <td>${row.quotaLimit}</td>
        <td>${row.remaining}</td>
        <td>${row.hasUsageRow ? `${row.percentUsed}%` : '<span class="muted">no usage row yet</span>'}</td>
      </tr>`
    )
    .join("");

  const periodForm = `<form class="search" method="get" action="/admin/overview">
  <p class="field"><label for="period">Period (YYYY-MM)</label>
  <input type="text" id="period" name="period" value="${escapeHtml(overview.period)}" pattern="\\d{4}-(0[1-9]|1[0-2])" inputmode="numeric"></p>
  ${submitButton("Show period")}
</form>`;

  const body = `<h1>Operational overview</h1>
${options.bannerHtml ?? ""}
${errorSummary(options.errors ?? [], options.errorPrefix ?? "")}
<p class="muted">Generated ${escapeHtml(overview.generatedAt)} from recorded rows only. Period keys use UTC months.</p>
<div class="card">
  <h2>Job outcomes</h2>
  ${
    overview.empty.noJobs
      ? `<p class="muted">No transcript or artifact jobs have been recorded yet, so there is nothing to summarise.</p>`
      : `<dl class="stats">
    <div><dt>Total</dt><dd>${overview.jobs.total}</dd></div>
    <div><dt>Queued or running</dt><dd>${overview.jobs.active}</dd></div>
    <div><dt>Failed</dt><dd>${overview.jobs.failed}</dd></div>
    <div><dt>Transcript artifacts</dt><dd>${overview.artifacts.total}</dd></div>
  </dl>
  <div class="card wide"><table>
    <caption class="muted">Jobs by status</caption>
    <thead><tr><th scope="col">Status</th><th scope="col">Jobs</th><th scope="col">Share</th></tr></thead>
    <tbody>${statusRows}</tbody>
  </table></div>`
  }
</div>
<div class="card wide">
  <h2>Recent failures</h2>
  ${
    overview.empty.noFailures
      ? `<p class="muted">No failures recorded. This is a real empty result, not a placeholder.</p>`
      : `<table>
    <thead><tr><th scope="col">Job</th><th scope="col">Artifact</th><th scope="col">Language</th><th scope="col">Error code</th><th scope="col">Attempts</th><th scope="col">Last update</th></tr></thead>
    <tbody>${failureRows}</tbody>
  </table>`
  }
</div>
<div class="card wide">
  <h2>Managed AI quota — period <code>${escapeHtml(overview.quota.period)}</code></h2>
  ${periodForm}
  <p class="muted">${overview.quota.totalUsers} editable account${overview.quota.totalUsers === 1 ? "" : "s"} · ${overview.quota.usersWithUsage} with a usage row this period · ${overview.quota.usersWithoutUsage} without one · ${overview.quota.totalUsed} units used in total.</p>
  ${
    overview.quota.rows.length === 0
      ? `<p class="muted">There are no editable accounts yet, so quota usage is empty.</p>`
      : `<table>
    <thead><tr><th scope="col">Account</th><th scope="col">Plan</th><th scope="col">Used</th><th scope="col">Limit</th><th scope="col">Remaining</th><th scope="col">Consumed</th></tr></thead>
    <tbody>${quotaRows}</tbody>
  </table>`
  }
  ${overview.empty.noQuotaRows && overview.quota.rows.length ? `<p class="muted">No account has consumed quota in ${escapeHtml(overview.quota.period)} yet.</p>` : ""}
</div>
${backLink("/admin", "Back to dashboard")}`;
  return shell("Operational overview", "overview", options.formToken, body);
}

// -------------------------------------------------------------------- users

export function usersPage(options: PageOptions & {
  page: UserPage;
  errorsUserId: string | null;
  values: Record<string, string> | null;
  adminEmail: string;
}): string {
  const { page } = options;
  const listQuery = {
    q: page.page.query,
    limit: page.page.limit,
    offset: page.page.offset
  };
  const rows = page.users.map((user) => userRow(user, options, listQuery)).join("\n");

  const scopeNote = `<div class="card">
  <h2>What you can change here</h2>
  <p><strong>Editable:</strong> display name, account status (active/disabled), plan (FREE/PRO) and this month's managed AI quota.</p>
  <p><strong>Not editable from this console:</strong> the email address that identifies the account, its password, its administrator status, and billing history. Membership edits are manual overrides recorded in the audit log.</p>
  <p class="muted">The sole administrator is never listed and cannot be edited, demoted or removed. Disabling an account revokes its existing sessions immediately.</p>
</div>`;

  const emptyState = page.page.offsetOutOfRange
    ? `<p class="muted">Page ${page.page.page} is past the end of the results (${
        page.page.total
      } matching account${page.page.total === 1 ? "" : "s"}). <a href="/admin/users?limit=${page.page.limit}&amp;offset=0${
        page.page.query ? `&amp;q=${encodeURIComponent(page.page.query)}` : ""
      }">Return to the first page</a>.</p>`
    : page.page.query
      ? `<p class="muted">No account matches <strong>${escapeHtml(page.page.query)}</strong>.</p>`
      : `<p class="muted">No accounts yet. Mobile registrations appear here once they are allowed in Settings.</p>`;

  const body = `<h1>Users</h1>
${options.bannerHtml ?? ""}
<p class="muted">Signed in as <code>${escapeHtml(options.adminEmail)}</code>. Ordering: ${escapeHtml(
    page.page.order
  )} (stable across pages).</p>
${searchForm({
    action: "/admin/users",
    query: page.page.query,
    limit: page.page.limit,
    label: "Search by email or display name",
    placeholder: "reader@example.com",
    extra: {}
  })}
${
    page.page.limitClamped
      ? banner("info", `Page size was reduced to the maximum of ${USER_PAGE_LIMIT_MAX} rows.`)
      : ""
  }
${scopeNote}
<div class="card">
  <h2>${page.page.total} account${page.page.total === 1 ? "" : "s"}</h2>
  ${
    page.users.length
      ? rows
      : emptyState
  }
  ${paginationNav({
    path: "/admin/users",
    limit: page.page.limit,
    offset: page.page.offset,
    total: page.page.total,
    query: page.page.query,
    label: "Users"
  })}
</div>
${backLink("/admin", "Back to dashboard")}`;
  return shell("Users", "users", options.formToken, body);
}

function userRow(
  user: ManagedUser,
  options: PageOptions & { errorsUserId: string | null; values: Record<string, string> | null },
  listQuery: { q: string; limit: number; offset: number }
): string {
  const idPrefix = `u-${user.id.replace(/[^A-Za-z0-9]/g, "")}-`;
  const isErrorRow = options.errorsUserId === user.id;
  const errors = isErrorRow ? options.errors ?? [] : [];
  const values = isErrorRow && options.values ? options.values : null;
  const action = `/admin/users/${encodeURIComponent(user.id)}?${new URLSearchParams({
    q: listQuery.q,
    limit: String(listQuery.limit),
    offset: String(listQuery.offset)
  }).toString()}`;
  return `<section class="row" aria-labelledby="${escapeHtml(idPrefix)}title">
  <header>
    <h3 id="${escapeHtml(idPrefix)}title">${text(user.email ?? user.display_name ?? user.id)}</h3>
    <p class="muted">Role: user · Account id: <code class="wrap">${escapeHtml(user.id)}</code></p>
  </header>
  ${isErrorRow ? errorSummary(errors, idPrefix) : ""}
  <form method="post" action="${escapeHtml(action)}">
    ${formSecurityFields(options.formToken)}
    <div class="grid">
      ${textField({
        name: "displayName",
        label: "Display name",
        value: pick(values, "displayName", user.display_name ?? ""),
        errors,
        idPrefix,
        maxLength: USER_DISPLAY_NAME_MAX,
        hint: `Up to ${USER_DISPLAY_NAME_MAX} characters. Shown on the phone.`
      })}
      ${selectField({
        name: "status",
        label: "Account status",
        value: pick(values, "status", user.status),
        options: [
          { value: "active", label: "Active" },
          { value: "disabled", label: "Disabled (revokes sessions)" }
        ],
        errors,
        idPrefix
      })}
      ${selectField({
        name: "plan",
        label: "Plan",
        value: pick(values, "plan", user.plan),
        options: [
          { value: "FREE", label: "Free" },
          { value: "PRO", label: "Member (PRO)" }
        ],
        errors,
        idPrefix
      })}
      ${textField({
        name: "quotaLimit",
        label: "Managed AI quota this month",
        value: pick(values, "quotaLimit", String(user.quota ?? 0)),
        errors,
        idPrefix,
        type: "number",
        inputMode: "numeric",
        min: 0,
        max: USER_QUOTA_MAX,
        hint: `Whole number between 0 and ${USER_QUOTA_MAX}.`
      })}
    </div>
    <p class="actions">${submitButton("Save changes")}</p>
  </form>
</section>`;
}

// --------------------------------------------------------------------- smtp

export function smtpPage(options: PageOptions & {
  settings: Record<string, unknown>;
  values: Record<string, string> | null;
  testResult: { kind: "success" | "warning" | "error"; text: string } | null;
  history: SmtpTestHistoryRow[];
  adminEmail: string;
}): string {
  const { settings } = options;
  const values = options.values;
  const configured = settings.configured === true;
  const history = options.history
    .map(
      (row) => `<tr>
        <td>${escapeHtml(row.createdAt)}</td>
        <td><code>${escapeHtml(row.status)}</code></td>
        <td>${escapeHtml(row.recipient)}</td>
        <td>${text(row.errorClass, "—")}</td>
      </tr>`
    )
    .join("");
  const body = `<h1>Email delivery</h1>
${options.bannerHtml ?? ""}
${
    configured
      ? banner("info", "SMTP settings are saved. The password cannot be read back and is never shown again.")
      : banner("warning", "No SMTP settings are saved yet. Save them before sending a test message.")
  }
${errorSummary(options.errors ?? [], options.errorPrefix ?? "")}
<div class="card">
  <h2>SMTP configuration</h2>
  <form method="post" action="/admin/smtp">
    ${formSecurityFields(options.formToken)}
    <div class="grid">
      ${textField({ name: "host", label: "SMTP host", value: pick(values, "host", String(settings.host ?? "")), errors: options.errors, required: true, hint: "For example smtp.example.com." })}
      ${textField({ name: "port", label: "Port", value: pick(values, "port", String(settings.port ?? 587)), errors: options.errors, type: "number", inputMode: "numeric", min: 1, max: 65535, required: true })}
      ${textField({ name: "username", label: "Username", value: pick(values, "username", String(settings.username ?? "")), errors: options.errors, required: true, autocomplete: "off" })}
      ${textField({
        name: "password",
        label: "Password",
        value: "",
        errors: options.errors,
        type: "password",
        autocomplete: "new-password",
        omitValueOnError: true,
        hint: configured
          ? "A password is already saved. Leave blank to keep it."
          : "Required the first time settings are saved."
      })}
      ${textField({ name: "from", label: "From address", value: pick(values, "from", String(settings.from ?? "")), errors: options.errors, type: "email", inputMode: "email", required: true })}
    </div>
    ${checkboxField({ name: "secure", label: "Direct TLS (implicit TLS, usually port 465)", checked: pick(values, "secure", settings.secure ? "1" : "") === "1", errors: options.errors })}
    <p class="actions">${submitButton("Save SMTP settings")}</p>
  </form>
</div>
<div class="card">
  <h2>Send a test message</h2>
  <p class="muted">This attempts a real delivery through the saved configuration. Test sends are limited to a few per quarter hour and default to your own address (${escapeHtml(
    options.adminEmail
  )}).</p>
  ${options.testResult ? banner(options.testResult.kind, options.testResult.text, "test-result") : ""}
  <form method="post" action="/admin/smtp/test">
    ${formSecurityFields(options.formToken)}
    ${textField({
      name: "recipient",
      label: "Recipient",
      value: pick(values, "recipient", options.adminEmail),
      errors: options.errors,
      type: "email",
      inputMode: "email",
      hint: "Defaults to your own administrator address."
    })}
    <p class="actions">${submitButton("Send test message")}</p>
  </form>
</div>
<div class="card wide">
  <h2>Recent test sends</h2>
  ${
    options.history.length
      ? `<table><thead><tr><th scope="col">When</th><th scope="col">Result</th><th scope="col">Recipient</th><th scope="col">Error class</th></tr></thead><tbody>${history}</tbody></table>`
      : `<p class="muted">No test message has been sent yet.</p>`
  }
</div>
${backLink("/admin", "Back to dashboard")}`;
  return shell("SMTP", "smtp", options.formToken, body);
}

// ------------------------------------------------------------ provider keys

export function providerKeysPage(options: PageOptions & { slots: Array<{ slot: string; configured: boolean }>; values: Record<string, string> | null }): string {
  const slotList = options.slots
    .map(
      (slot) =>
        `<li><code>${escapeHtml(slot.slot)}</code> — ${slot.configured ? "configured" : "<span class=\"muted\">not set</span>"}</li>`
    )
    .join("");
  const body = `<h1>Provider API keys</h1>
${options.bannerHtml ?? ""}
${errorSummary(options.errors ?? [], options.errorPrefix ?? "")}
<div class="card">
  <h2>Saved slots</h2>
  <p class="muted">Keys are encrypted at rest and can never be read back from this page or the API. Saving a key replaces the stored value.</p>
  <ul>${slotList}</ul>
</div>
<div class="card">
  <h2>Save a key</h2>
  <form method="post" action="/admin/provider-keys">
    ${formSecurityFields(options.formToken)}
    ${selectField({
      name: "slot",
      label: "Secret slot",
      value: pick(options.values, "slot", options.slots[0]?.slot ?? "AI_PROVIDER_SECRET_1"),
      options: options.slots.map((slot) => ({ value: slot.slot, label: slot.slot })),
      errors: options.errors
    })}
    ${textField({
      name: "secret",
      label: "New API key",
      value: "",
      errors: options.errors,
      type: "password",
      autocomplete: "off",
      omitValueOnError: true,
      hint: "Never displayed again after saving."
    })}
    <p class="actions">${submitButton("Save key")}</p>
  </form>
</div>
${backLink("/admin", "Back to dashboard")}`;
  return shell("Provider keys", "provider-keys", options.formToken, body);
}

// -------------------------------------------------------------------- audio

export type AudioProviderRow = {
  id: string;
  capability: string;
  adapter: string;
  model: string;
  voice: string | null;
  secret_ref: string;
  enabled: number;
  priority: number;
};

export function audioPage(options: PageOptions & {
  rows: AudioProviderRow[];
  checks: Map<string, StoredConnectivityCheck>;
  errorsId: string | null;
  values: Record<string, string> | null;
  secretSlots: string[];
}): string {
  const rows = options.rows.map((row) => audioRow(row, options)).join("\n");
  const untested = options.rows.filter(
    (row) => row.enabled === 1 && !options.checks.has(`audio_provider:${row.id}`)
  );
  const broken = options.rows.filter((row) => {
    const check = options.checks.get(`audio_provider:${row.id}`);
    return row.enabled === 1 && check !== undefined && check.status !== "ok";
  });
  const warnings = [
    untested.length
      ? banner("warning", `${untested.length} enabled preset${untested.length === 1 ? " has" : "s have"} never been checked. Enabled does not mean working — run a check.`)
      : "",
    broken.length
      ? banner("error", `${broken.length} enabled preset${broken.length === 1 ? "" : "s"} failed the last connectivity check.`)
      : ""
  ]
    .filter(Boolean)
    .join("");

  const body = `<h1>Speech providers</h1>
${options.bannerHtml ?? ""}
${warnings}
<p class="muted">Speech recognition (STT) and speech synthesis (TTS) are configured separately. A check makes one authenticated request to the provider and stores the outcome.</p>
<div class="card">
  <h2>Presets</h2>
  ${
    options.rows.length
      ? rows
      : `<p class="muted">No speech presets are configured. Apply migration 0014 or reinstall the deployment.</p>`
  }
</div>
${backLink("/admin", "Back to dashboard")}`;
  return shell("Speech providers", "audio", options.formToken, body);
}

function audioRow(row: AudioProviderRow, options: PageOptions & {
  checks: Map<string, StoredConnectivityCheck>;
  errorsId: string | null;
  values: Record<string, string> | null;
  secretSlots: string[];
}): string {
  const idPrefix = `a-${row.id.replace(/[^A-Za-z0-9]/g, "")}-`;
  const isErrorRow = options.errorsId === row.id;
  const errors = isErrorRow ? options.errors ?? [] : [];
  const values = isErrorRow && options.values ? options.values : null;
  const check = options.checks.get(`audio_provider:${row.id}`);
  const checkLine = check
    ? `<p class="muted">Last check: <code>${escapeHtml(check.status)}</code> at ${escapeHtml(check.checkedAt)} — ${escapeHtml(describeConnectivity(check))}${check.httpStatus ? ` (HTTP ${check.httpStatus})` : ""}</p>`
    : `<p class="muted">Never checked.</p>`;
  const kind = row.capability === "stt" ? "Speech recognition (STT)" : "Speech synthesis (TTS)";
  return `<section class="row" aria-labelledby="${escapeHtml(idPrefix)}title">
  <header>
    <h3 id="${escapeHtml(idPrefix)}title">${escapeHtml(row.id)}</h3>
    <p class="muted">${escapeHtml(kind)} · ${escapeHtml(row.adapter)} · ${row.enabled ? "enabled" : "disabled"}</p>
  </header>
  ${isErrorRow ? errorSummary(errors, idPrefix) : ""}
  ${checkLine}
  <form method="post" action="/admin/audio/${encodeURIComponent(row.id)}">
    ${formSecurityFields(options.formToken)}
    <div class="grid">
      ${textField({ name: "model", label: "Model", value: pick(values, "model", row.model), errors, idPrefix, required: true, maxLength: 100 })}
      ${textField({ name: "voice", label: "Voice", value: pick(values, "voice", row.voice ?? ""), errors, idPrefix, required: false, maxLength: 80, hint: "Leave blank for providers without a voice." })}
      ${selectField({
        name: "secretRef",
        label: "Key slot",
        value: pick(values, "secretRef", row.secret_ref),
        options: options.secretSlots.map((slot) => ({ value: slot, label: slot })),
        errors,
        idPrefix
      })}
      ${textField({ name: "priority", label: "Priority", value: pick(values, "priority", String(row.priority)), errors, idPrefix, type: "number", inputMode: "numeric", min: 0, max: 1000 })}
    </div>
    ${checkboxField({ name: "enabled", label: "Enabled", checked: pick(values, "enabled", row.enabled ? "1" : "") === "1", errors, idPrefix })}
    <p class="actions">${submitButton("Save preset")}</p>
  </form>
  <form method="post" action="/admin/audio/${encodeURIComponent(row.id)}/check">
    ${formSecurityFields(options.formToken)}
    <p class="actions">${submitButton("Check connectivity", { secondary: true })}</p>
  </form>
</section>`;
}

// ----------------------------------------------------------------- settings

export function settingsPage(options: PageOptions & { mode: string; smtpConfigured: boolean; values: Record<string, string> | null }): string {
  const body = `<h1>Settings</h1>
${options.bannerHtml ?? ""}
${errorSummary(options.errors ?? [], options.errorPrefix ?? "")}
<div class="card">
  <h2>Mobile account registration</h2>
  <p>Allow new mobile accounts through the API. Web administration has no registration, and a newly registered account is always an ordinary account — never an administrator.</p>
  <p>Current mode: <code>${escapeHtml(options.mode)}</code>${
    options.smtpConfigured ? "" : ' · <span class="muted">SMTP must be configured before registration can be opened</span>'
  }</p>
  <form method="post" action="/admin/settings">
    ${formSecurityFields(options.formToken)}
    ${selectField({
      name: "registrationMode",
      label: "Registration mode",
      value: pick(options.values, "registrationMode", options.mode),
      options: [
        { value: "CLOSED", label: "Closed — existing accounts only" },
        { value: "OPEN", label: "Open — allow new mobile accounts" }
      ],
      errors: options.errors
    })}
    <p class="actions">${submitButton("Save settings")}</p>
  </form>
</div>
${backLink("/admin", "Back to dashboard")}`;
  return shell("Settings", "settings", options.formToken, body);
}

// -------------------------------------------------------------------- audit

export function auditPage(options: PageOptions & { entries: AuditRow[] }): string {
  const rows = options.entries
    .map(
      (entry) => `<tr>
        <td>${escapeHtml(entry.createdAt)}</td>
        <td><code>${text(entry.actorUserId, "system")}</code></td>
        <td>${escapeHtml(entry.action)}</td>
        <td>${text(entry.targetType, "")} ${text(entry.targetId, "")}</td>
        <td class="muted">${entry.beforeJson ? `${escapeHtml(entry.beforeJson)} → ` : ""}${escapeHtml(entry.afterJson ?? "")}</td>
      </tr>`
    )
    .join("");
  const body = `<h1>Audit log</h1>
<p class="muted">Audit entries record that a change happened, for which target, and non-secret fields only. Credentials, keys, tokens and message bodies are never written here.</p>
<div class="card wide">
  ${
    options.entries.length
      ? `<table>
    <thead><tr><th scope="col">When</th><th scope="col">Actor</th><th scope="col">Action</th><th scope="col">Target</th><th scope="col">Details</th></tr></thead>
    <tbody>${rows}</tbody>
  </table>`
      : `<p class="muted">No audit entries recorded yet.</p>`
  }
</div>
${backLink("/admin", "Back to dashboard")}`;
  return shell("Audit log", "audit", options.formToken, body);
}

// ------------------------------------------------------------------- login

export function loginPage(options: {
  registrationOpen: boolean;
  singleAdminMode: boolean;
  errors?: FieldError[];
  values?: Record<string, string> | null;
  message?: string;
  /**
   * Pre-session anti-CSRF value. The sign-in form has no session yet, so it
   * carries a value that is also stored in a SameSite=Strict cookie and checked
   * by double submit (see adminCsrf.LOGIN_CSRF_COOKIE).
   */
  formToken?: string;
}): string {
  const values = options.values ?? null;
  const body = `<h1>WristBrief Admin</h1>
${options.message ? banner("error", options.message, "login-message") : ""}
${errorSummary(options.errors ?? [], "login-")}
<div class="card">
  <h2>Sign in</h2>
  <p class="muted">Administration is limited to the provisioned administrator account.</p>
  <form method="post" action="/admin/login">
    ${options.formToken ? formSecurityFields(options.formToken) : ""}
    ${textField({
      name: "email",
      label: "Email",
      value: pick(values, "email", ""),
      errors: options.errors,
      idPrefix: "login-",
      type: "email",
      inputMode: "email",
      autocomplete: "username",
      required: true
    })}
    ${textField({
      name: "password",
      label: "Password",
      value: "",
      errors: options.errors,
      idPrefix: "login-",
      type: "password",
      autocomplete: "current-password",
      required: true,
      omitValueOnError: true
    })}
    <p class="actions">${submitButton("Sign in")}</p>
  </form>
</div>
${
    options.registrationOpen
      ? `${legacyRegistrationCard()}${loginScript()}`
      : options.singleAdminMode
        ? ""
        : `<p class="muted">Registration is closed. New mobile accounts are created through the app.</p>`
  }`;
  return adminPage("Sign in", body);
}

/**
 * Bootstrap-only first-account card. The console itself has no registration:
 * this card exists solely for the pre-bootstrap window of a deployment that does
 * not run in SINGLE_ADMIN_MODE, and it posts to the public registration API on
 * the mobile policy. It is never rendered in single-admin mode, and the sign-in
 * form above it is deliberately NOT script-driven: the plain form POST is the
 * only sign-in path, so Enter works with or without JavaScript.
 */
function legacyRegistrationCard(): string {
  return `<div class="card">
  <h2>Create the first admin account</h2>
  <p class="muted">The first registration becomes the administrator and closes the bootstrap window. Later registrations are ordinary accounts and must be allowed from Settings.</p>
  <p class="field"><label for="reg-email">Email</label><input id="reg-email" name="reg-email" type="email" autocomplete="username"></p>
  <p class="field"><label for="reg-password">Password</label><input id="reg-password" name="reg-password" type="password" autocomplete="new-password"></p>
  <p class="actions"><button type="button" id="register">Create account</button></p>
  <p id="reg-msg" class="muted" role="status"></p>
</div>`;
}

function loginScript(): string {
  return `<script>
function csrfToken(){const m=document.cookie.match(/(?:^|; )wristbrief_admin_csrf=([^;]+)/);return m?decodeURIComponent(m[1]):"";}
const registerBtn = document.getElementById("register");
if (registerBtn) registerBtn.onclick = async () => {
  const target = document.getElementById("reg-msg");
  try {
    const res = await fetch("/v1/auth/email/register", { method: "POST", headers: { "Content-Type": "application/json", "X-CSRF-Token": csrfToken() }, body: JSON.stringify({ email: document.getElementById("reg-email").value, password: document.getElementById("reg-password").value }) });
    const body = await res.json().catch(() => ({}));
    target.textContent = res.ok ? "Account created. Sign in above." : (body.error ?? "Registration failed");
  } catch { target.textContent = "Registration failed"; }
};
</script>`;
}

// --------------------------------------------------------- change password

export function changePasswordPage(options: PageOptions & { values: Record<string, string> | null; adminEmail: string }): string {
  const body = `<h1>Set your own password</h1>
${options.bannerHtml ?? ""}
<p>The temporary password must be replaced before the admin console can be used. This step cannot be skipped.</p>
${errorSummary(options.errors ?? [], options.errorPrefix ?? "")}
<div class="card">
  <h2>Change password for <code>${escapeHtml(options.adminEmail)}</code></h2>
  <form method="post" action="/admin/change-password">
    ${formSecurityFields(options.formToken)}
    ${textField({
      name: "currentPassword",
      label: "Temporary password",
      value: "",
      errors: options.errors,
      type: "password",
      autocomplete: "current-password",
      required: true,
      omitValueOnError: true
    })}
    ${textField({
      name: "newPassword",
      label: "New password",
      value: "",
      errors: options.errors,
      type: "password",
      autocomplete: "new-password",
      required: true,
      omitValueOnError: true,
      hint: "At least 16 characters and different from the temporary password."
    })}
    ${textField({
      name: "confirmPassword",
      label: "Repeat new password",
      value: "",
      errors: options.errors,
      type: "password",
      autocomplete: "new-password",
      required: true,
      omitValueOnError: true
    })}
    <p class="actions">${submitButton("Change password")}</p>
  </form>
</div>
<p class="muted">After changing the password every admin session is revoked, including this one, and you will sign in again.</p>`;
  return adminPage("Change temporary password", body);
}

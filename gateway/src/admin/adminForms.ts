/**
 * Markup helpers for the server-rendered admin forms.
 *
 * The console is operated on narrow screens and sometimes without JavaScript, so
 * every control here is a real `<label for>` + `<input id>` / `<select id>` /
 * `<button>` inside a real `<form>`; nothing depends on a click handler being
 * attached. Validation errors are rendered in two places: a summary at the top of
 * the form whose entries link to the field, and an inline `role="alert"` message
 * tied to the input through `aria-describedby` + `aria-invalid`, so a screen
 * reader announces the reason and a keyboard user can jump straight to it.
 *
 * Field errors carry the logical field name (`quotaLimit`). A page that renders
 * the same form once per row passes `idPrefix`, which is used only for the DOM id
 * and the error anchor, never for the error lookup.
 */
import { adminPage, escapeHtml } from "./adminHtml";
import { describeFieldError, fieldErrorIndex, type FieldError } from "./adminValidation";

export function fieldErrorId(domId: string): string {
  return `err-${domId}`;
}

/** DOM ids must survive being used as an anchor target; keep them boring. */
export function safeIdPrefix(prefix: string): string {
  return prefix.replace(/[^A-Za-z0-9_-]/g, "-");
}

export function errorSummary(errors: FieldError[], idPrefix = ""): string {
  if (!errors.length) return "";
  const prefix = safeIdPrefix(idPrefix);
  const items = errors
    .map(
      (error) =>
        `<li><a href="#${escapeHtml(prefix + error.field)}">${escapeHtml(error.field)}</a>: ${escapeHtml(
          describeFieldError(error)
        )}</li>`
    )
    .join("");
  return `<div class="errors" role="alert" tabindex="-1" id="${escapeHtml(prefix)}error-summary">
    <p><strong>${errors.length === 1 ? "There is a problem" : `There are ${errors.length} problems`}</strong></p>
    <ul>${items}</ul>
  </div>`;
}

type FieldIdentity = { key: string; domId: string };

function fieldIdentity(name: string, id: string | undefined, idPrefix: string | undefined): FieldIdentity {
  const key = id ?? name;
  return { key, domId: idPrefix ? `${safeIdPrefix(idPrefix)}${key}` : key };
}

function inlineError(identity: FieldIdentity, errors: Map<string, FieldError>): string {
  const error = errors.get(identity.key);
  if (!error) return "";
  return `<p class="field-error" id="${escapeHtml(fieldErrorId(identity.domId))}" role="alert">${escapeHtml(
    describeFieldError(error)
  )}</p>`;
}

function describedBy(identity: FieldIdentity, errors: Map<string, FieldError>, hintId?: string): string {
  const ids: string[] = [];
  if (errors.has(identity.key)) ids.push(fieldErrorId(identity.domId));
  if (hintId) ids.push(hintId);
  return ids.length ? ` aria-describedby="${escapeHtml(ids.join(" "))}"` : "";
}

export type TextFieldOptions = {
  name: string;
  label: string;
  value?: string | number | null;
  errors?: FieldError[];
  type?: "text" | "email" | "password" | "number" | "search";
  hint?: string;
  required?: boolean;
  autocomplete?: string;
  inputMode?: "text" | "numeric" | "email" | "search";
  min?: number;
  max?: number;
  maxLength?: number;
  id?: string;
  idPrefix?: string;
  /** Never re-render a value that failed validation (used for secrets). */
  omitValueOnError?: boolean;
};

export function textField(options: TextFieldOptions): string {
  const errors = fieldErrorIndex(options.errors ?? []);
  const identity = fieldIdentity(options.name, options.id, options.idPrefix);
  const hintId = options.hint ? `hint-${identity.domId}` : undefined;
  const failed = errors.has(identity.key);
  const showValue = !(options.omitValueOnError && failed);
  const attributes = [
    `type="${options.type ?? "text"}"`,
    `id="${escapeHtml(identity.domId)}"`,
    `name="${escapeHtml(options.name)}"`,
    `value="${escapeHtml(showValue ? String(options.value ?? "") : "")}"`,
    options.required ? "required" : "",
    options.autocomplete ? `autocomplete="${escapeHtml(options.autocomplete)}"` : "",
    options.inputMode ? `inputmode="${escapeHtml(options.inputMode)}"` : "",
    options.min !== undefined ? `min="${options.min}"` : "",
    options.max !== undefined ? `max="${options.max}"` : "",
    options.maxLength !== undefined ? `maxlength="${options.maxLength}"` : "",
    failed ? 'aria-invalid="true"' : "",
    describedBy(identity, errors, hintId)
  ]
    .filter(Boolean)
    .join(" ");
  return `<p class="field">
  <label for="${escapeHtml(identity.domId)}">${escapeHtml(options.label)}${
    options.required ? ' <span class="req" aria-hidden="true">*</span>' : ""
  }</label>
  ${options.hint ? `<span class="hint" id="${escapeHtml(hintId!)}">${escapeHtml(options.hint)}</span>` : ""}
  <input ${attributes}>
  ${inlineError(identity, errors)}
</p>`;
}

export type SelectOption = { value: string; label: string };

export function selectField(options: {
  name: string;
  label: string;
  value: string | null | undefined;
  options: SelectOption[];
  errors?: FieldError[];
  hint?: string;
  idPrefix?: string;
  id?: string;
}): string {
  const errors = fieldErrorIndex(options.errors ?? []);
  const identity = fieldIdentity(options.name, options.id, options.idPrefix);
  const hintId = options.hint ? `hint-${identity.domId}` : undefined;
  const rendered = options.options
    .map(
      (option) =>
        `<option value="${escapeHtml(option.value)}"${
          option.value === (options.value ?? "") ? " selected" : ""
        }>${escapeHtml(option.label)}</option>`
    )
    .join("");
  return `<p class="field">
  <label for="${escapeHtml(identity.domId)}">${escapeHtml(options.label)}</label>
  ${options.hint ? `<span class="hint" id="${escapeHtml(hintId!)}">${escapeHtml(options.hint)}</span>` : ""}
  <select id="${escapeHtml(identity.domId)}" name="${escapeHtml(options.name)}"${
    errors.has(identity.key) ? ' aria-invalid="true"' : ""
  }${describedBy(identity, errors, hintId)}>${rendered}</select>
  ${inlineError(identity, errors)}
</p>`;
}

export function checkboxField(options: {
  name: string;
  label: string;
  checked: boolean;
  errors?: FieldError[];
  hint?: string;
  idPrefix?: string;
  id?: string;
  required?: boolean;
}): string {
  const errors = fieldErrorIndex(options.errors ?? []);
  const identity = fieldIdentity(options.name, options.id, options.idPrefix);
  const hintId = options.hint ? `hint-${identity.domId}` : undefined;
  return `<p class="field checkbox">
  <input type="checkbox" id="${escapeHtml(identity.domId)}" name="${escapeHtml(options.name)}" value="1"${
    options.checked ? " checked" : ""
  }${options.required ? " required" : ""}${errors.has(identity.key) ? ' aria-invalid="true"' : ""}${describedBy(
    identity,
    errors,
    hintId
  )}>
  <label for="${escapeHtml(identity.domId)}">${escapeHtml(options.label)}</label>
  ${options.hint ? `<span class="hint" id="${escapeHtml(hintId!)}">${escapeHtml(options.hint)}</span>` : ""}
  ${inlineError(identity, errors)}
</p>`;
}

/**
 * The no-JavaScript CSRF field. The value is a session-bound derivation, not the
 * double-submit CSRF token, and it is only ever rendered inside pages that
 * already required the admin cookie. See adminCsrf.deriveAdminFormToken.
 */
export function formSecurityFields(formToken: string, extra: Record<string, string> = {}): string {
  return Object.entries({ csrfToken: formToken, ...extra })
    .map(([name, value]) => `<input type="hidden" name="${escapeHtml(name)}" value="${escapeHtml(value)}">`)
    .join("");
}

export function submitButton(
  label: string,
  options: { name?: string; value?: string; id?: string; secondary?: boolean } = {}
): string {
  const attributes = [
    'type="submit"',
    options.id ? `id="${escapeHtml(options.id)}"` : "",
    options.name ? `name="${escapeHtml(options.name)}"` : "",
    options.value ? `value="${escapeHtml(options.value)}"` : "",
    options.secondary ? 'class="secondary"' : ""
  ]
    .filter(Boolean)
    .join(" ");
  return `<button ${attributes}>${escapeHtml(label)}</button>`;
}

export function banner(kind: "success" | "warning" | "error" | "info", text: string, id = "banner"): string {
  return `<div class="banner ${kind}" role="status" id="${escapeHtml(id)}">${escapeHtml(text)}</div>`;
}

export type PaginationOptions = {
  path: string;
  limit: number;
  offset: number;
  total: number;
  query?: string;
  extra?: Record<string, string>;
  label?: string;
};

export type PaginationLinks = { previous: string | null; next: string | null; page: number; pageCount: number };

export function paginationLinks(options: PaginationOptions): PaginationLinks {
  const pageCount = Math.max(1, Math.ceil(options.total / options.limit));
  const page = Math.floor(options.offset / options.limit) + 1;
  const build = (offset: number) => {
    const params = new URLSearchParams();
    if (options.query) params.set("q", options.query);
    params.set("limit", String(options.limit));
    params.set("offset", String(offset));
    for (const [key, value] of Object.entries(options.extra ?? {})) params.set(key, value);
    return `${options.path}?${params.toString()}`;
  };
  return {
    previous: options.offset > 0 ? build(Math.max(0, options.offset - options.limit)) : null,
    next: options.offset + options.limit < options.total ? build(options.offset + options.limit) : null,
    page,
    pageCount
  };
}

export function paginationNav(options: PaginationOptions): string {
  const links = paginationLinks(options);
  const label = options.label ?? "Results";
  const range =
    options.total === 0
      ? "No results"
      : `${options.offset + 1}–${Math.min(options.offset + options.limit, options.total)} of ${options.total}`;
  const parts: string[] = [];
  if (links.previous) parts.push(`<a href="${escapeHtml(links.previous)}" rel="prev">Previous</a>`);
  if (links.next) parts.push(`<a href="${escapeHtml(links.next)}" rel="next">Next</a>`);
  return `<nav class="pager" aria-label="${escapeHtml(label)} pages">
  <p class="muted">Page ${links.page} of ${links.pageCount} · showing ${escapeHtml(range)}</p>
  <p>${parts.join(" · ") || '<span class="muted">No other pages</span>'}</p>
</nav>`;
}

export type NavItem = { href: string; label: string; key: string };

export const ADMIN_NAV: NavItem[] = [
  { href: "/admin", label: "Dashboard", key: "dashboard" },
  { href: "/admin/overview", label: "Health", key: "overview" },
  { href: "/admin/users", label: "Users", key: "users" },
  { href: "/admin/smtp", label: "Email", key: "smtp" },
  { href: "/admin/providers", label: "Models", key: "providers" },
  { href: "/admin/provider-keys", label: "Keys", key: "provider-keys" },
  { href: "/admin/audio", label: "Speech", key: "audio" },
  { href: "/admin/settings", label: "Settings", key: "settings" },
  { href: "/admin/audit", label: "Audit", key: "audit" }
];

/**
 * Section navigation plus a real sign-out form. The sign-out control is a
 * submit button in a POST form rather than a click handler so it stays reachable
 * with the keyboard and without JavaScript.
 */
export function adminNav(current: string, formToken: string): string {
  const link = (item: NavItem) =>
    item.key === current
      ? `<li><a href="${escapeHtml(item.href)}" aria-current="page"><strong>${escapeHtml(item.label)}</strong></a></li>`
      : `<li><a href="${escapeHtml(item.href)}">${escapeHtml(item.label)}</a></li>`;
  const primary = ADMIN_NAV.slice(0, 3).map(link).join("");
  const services = ADMIN_NAV.slice(3).map(link).join("");
  return `<nav class="admin-nav" aria-label="Admin sections">
  <div class="nav-group"><span class="nav-label">Workspace</span><ul class="crumbs">${primary}</ul></div>
  <div class="nav-group"><span class="nav-label">Manage</span><ul class="crumbs">${services}</ul></div>
  <div class="nav-logout"><form method="post" action="/admin/logout">${formSecurityFields(formToken)}<button type="submit" class="secondary">Sign out</button></form></div>
</nav>`;
}

/**
 * One page assembly point, so every /admin page gets the same shell: document
 * type, viewport meta, stylesheet, skip link and section nav. A page assembled
 * without this is a bare HTML fragment, which a browser then renders at its
 * default ~980px layout width with no styling — exactly the bug this prevents.
 */
export function adminShell(title: string, current: string, formToken: string, body: string): string {
  return adminPage(title, `${adminNav(current, formToken)}\n${body}`);
}

export function searchForm(options: {
  action: string;
  query: string;
  limit: number;
  label: string;
  placeholder?: string;
  extra?: Record<string, string>;
}): string {
  const hidden = Object.entries({ limit: String(options.limit), ...(options.extra ?? {}) })
    .map(([name, value]) => `<input type="hidden" name="${escapeHtml(name)}" value="${escapeHtml(value)}">`)
    .join("");
  return `<form class="search" method="get" action="${escapeHtml(options.action)}" role="search">
  <p class="field">
    <label for="q">${escapeHtml(options.label)}</label>
    <input type="search" id="q" name="q" value="${escapeHtml(options.query)}" placeholder="${escapeHtml(
      options.placeholder ?? ""
    )}" autocomplete="off">
  </p>
  ${hidden}
  ${submitButton("Search")}
</form>`;
}

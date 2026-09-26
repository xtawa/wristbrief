export function escapeHtml(value: string): string {
  return value
    .replace(/&/g, "&amp;")
    .replace(/</g, "&lt;")
    .replace(/>/g, "&gt;")
    .replace(/"/g, "&quot;")
    .replace(/'/g, "&#39;");
}

export type AdminPageOptions = {
  /** Marks the current item in the section nav. */
  current?: string;
  formToken?: string;
};

/**
 * Minimal server-rendered page shell for the /admin area: no SPA framework, no
 * external assets, strict CSP, and a tiny amount of inline script for the
 * optional fetch-based path. Every critical action also has a real form POST, so
 * the pages stay fully operable with JavaScript disabled.
 *
 * Layout rules that matter for the browser walkthrough:
 *  - no fixed widths, `overflow-wrap:anywhere`, so nothing overflows at ~360px;
 *  - `:focus-visible` outline on every interactive control;
 *  - a skip link and real headings/labels for keyboard and screen-reader use.
 */
export function adminPage(title: string, body: string): string {
  return `<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>${escapeHtml(title)} · WristBrief Admin</title>
<style>
:root { color-scheme: light; --ink:#182d30; --muted:#5d7274; --edge:#d7e2df; --accent:#096f67; --canvas:#f3f7f4; --danger:#ab2328; --danger-soft:#fdecea; --warn:#8a5a00; --ok:#0a6b3d; }
* { box-sizing:border-box; }
html { -webkit-text-size-adjust:100%; }
body { font: 15px/1.55 system-ui,-apple-system,"Segoe UI",sans-serif; margin:0; background:radial-gradient(circle at 95% 0%,#dcece5,transparent 38%),var(--canvas); color:var(--ink); overflow-wrap:anywhere; }
main { max-width:1080px; margin:0 auto; padding:40px 20px 96px; }
main::before { content:"WRISTBRIEF  /  CONTROL ROOM"; display:block; color:var(--accent); font-size:11px; font-weight:800; letter-spacing:.16em; margin-bottom:20px; }
h1 { font-size:clamp(26px,5vw,40px); line-height:1.12; letter-spacing:-.035em; margin:0 0 18px; }
h2 { font-size:17px; margin:0 0 10px; }
h3 { font-size:15px; margin:0 0 8px; }
p { margin:0 0 10px; }
.card { background:#fff; border:1px solid var(--edge); border-radius:18px; padding:22px; margin:16px 0; box-shadow:0 8px 28px #143c3009; }
.card.wide { overflow-x:auto; }
.table-scroll { overflow-x:auto; margin-top:16px; }
label { color:var(--muted); font-size:13px; font-weight:650; }
input,select,button,textarea { font:inherit; }
input:not([type=checkbox]),select { display:block; width:100%; min-width:0; padding:10px 12px; margin-top:5px; border:1px solid #bed0cb; border-radius:9px; background:#fff; color:var(--ink); }
input[type=checkbox] { accent-color:var(--accent); width:20px; height:20px; }
button { padding:10px 16px; border:0; border-radius:9px; background:var(--accent); color:#fff; font-weight:700; cursor:pointer; min-height:42px; }
button:hover { background:#07564f; }
button.secondary { background:#e6eeea; color:var(--ink); }
button.secondary:hover { background:#d5e2dc; }
button:focus-visible,a:focus-visible,input:focus-visible,select:focus-visible,summary:focus-visible { outline:3px solid #e1a536; outline-offset:2px; }
a { color:var(--accent); text-underline-offset:3px; font-weight:650; }
.muted { color:var(--muted); font-size:13px; }
.req { color:var(--danger); }
.skip { position:absolute; left:-9999px; top:0; background:#fff; padding:10px 14px; border-radius:0 0 10px 0; }
.skip:focus { left:0; z-index:5; }
.admin-nav { display:flex; flex-wrap:wrap; align-items:flex-end; gap:12px 26px; margin:0 0 28px; border-bottom:1px solid var(--edge); padding:0 0 16px; }
.nav-group { min-width:0; }
.nav-label { display:block; color:var(--muted); font-size:11px; font-weight:750; letter-spacing:.09em; text-transform:uppercase; margin-bottom:7px; }
.crumbs { display:flex; flex-wrap:wrap; gap:6px; align-items:center; margin:0; padding:0; list-style:none; font-size:13px; }
.crumbs a { display:block; border-radius:8px; padding:7px 9px; text-decoration:none; }
.crumbs a:hover { background:#e6eeea; text-decoration:underline; }
.crumbs a[aria-current=page] { color:#fff; background:var(--accent); }
.nav-logout { margin-left:auto; }
.crumbs form { display:inline; }
.crumbs button { min-height:34px; padding:6px 12px; }
.field { margin:0 0 14px; }
.field > label { display:block; margin:0 0 2px; }
.field > input, .field > select { margin-top:2px; }
.field.checkbox { display:flex; flex-wrap:wrap; align-items:center; gap:8px; }
.field.checkbox label { margin:0; }
.field.checkbox .field-error { flex-basis:100%; }
.hint { display:block; color:var(--muted); font-size:12px; margin:2px 0; }
.field-error { color:var(--danger); font-size:13px; font-weight:700; margin:4px 0 0; }
input[aria-invalid="true"], select[aria-invalid="true"] { border:2px solid var(--danger); }
.errors { background:var(--danger-soft); border:1px solid #f0b4ae; border-left:5px solid var(--danger); border-radius:10px; padding:12px 16px; margin:0 0 18px; }
.errors ul { margin:6px 0 0; padding-left:20px; }
.errors a { color:var(--danger); }
.banner { border-radius:10px; padding:10px 14px; margin:0 0 16px; font-size:14px; font-weight:600; }
.banner.success { background:#e6f5ec; border:1px solid #a8d8bd; color:var(--ok); }
.banner.error { background:var(--danger-soft); border:1px solid #f0b4ae; color:var(--danger); }
.banner.warning { background:#fdf3e0; border:1px solid #eccb8c; color:var(--warn); }
.banner.info { background:#e9f2f0; border:1px solid #bcd6d1; color:var(--ink); }
.grid { display:grid; grid-template-columns:repeat(auto-fit,minmax(210px,1fr)); gap:0 16px; }
.pager { display:flex; flex-wrap:wrap; gap:4px 18px; align-items:baseline; border-top:1px solid var(--edge); margin:14px 0 0; padding-top:10px; }
.search { display:flex; flex-wrap:wrap; gap:10px; align-items:flex-end; }
.search .field { flex:1 1 220px; margin:0; }
table { border-collapse:collapse; width:100%; font-size:13px; }
td,th { text-align:left; border-bottom:1px solid var(--edge); padding:10px 8px; vertical-align:middle; }
th { color:var(--muted); font-size:11px; text-transform:uppercase; letter-spacing:.07em; }
code { background:#e9f0eb; border-radius:5px; padding:2px 5px; }
code.wrap { overflow-wrap:anywhere; }
.row { border:1px solid var(--edge); border-radius:14px; padding:16px; margin:0 0 14px; background:#fff; }
.row > header { display:flex; flex-wrap:wrap; gap:4px 12px; align-items:baseline; margin:0 0 10px; }
.row > header h3 { margin:0; }
.row .actions { display:flex; flex-wrap:wrap; gap:10px; align-items:center; margin-top:6px; }
dl.stats { display:grid; grid-template-columns:repeat(auto-fit,minmax(140px,1fr)); gap:12px; margin:0; }
dl.stats > div { background:#f4f8f6; border:1px solid var(--edge); border-radius:12px; padding:12px; }
dl.stats dt { color:var(--muted); font-size:11px; text-transform:uppercase; letter-spacing:.06em; }
dl.stats dd { margin:4px 0 0; font-size:22px; font-weight:800; }
details { margin:8px 0; }
summary { cursor:pointer; font-weight:650; color:var(--accent); }
@media(max-width:640px){main{padding:24px 12px 64px}.card{padding:15px}td,th{padding:8px 6px}dl.stats dd{font-size:19px}.admin-nav{gap:12px}.nav-logout{margin-left:0}.nav-group{width:100%}}
</style>
</head>
<body>
<a class="skip" href="#main-content">Skip to main content</a>
<main id="main-content">
${body}
</main>
</body>
</html>`;
}

/**
 * `same-origin` rather than `no-referrer`: a `no-referrer` response makes Chrome
 * serialize the Origin of a same-document form submission as the opaque `null`,
 * which is a strictly worse signal to reason about. `same-origin` still sends no
 * referrer to any other site, so nothing leaks off-origin, and same-origin form
 * posts then carry a real Origin that the CSRF layer can verify.
 */
export function htmlResponse(html: string, status = 200, requestId?: string, cookies: string[] = []): Response {
  const headers: Record<string, string> = {
    "Content-Type": "text/html; charset=utf-8",
    "Cache-Control": "no-store",
    "Content-Security-Policy":
      "default-src 'none'; style-src 'unsafe-inline'; script-src 'unsafe-inline'; connect-src 'self'; form-action 'self'; base-uri 'none'; frame-ancestors 'none'",
    "Referrer-Policy": "same-origin",
    "X-Content-Type-Options": "nosniff"
  };
  if (requestId) headers["X-Request-ID"] = requestId;
  if (!cookies.length) return new Response(html, { status, headers });
  const withCookies = new Headers(headers);
  for (const cookie of cookies) withCookies.append("Set-Cookie", cookie);
  return new Response(html, { status, headers: withCookies });
}

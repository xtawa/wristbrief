export function escapeHtml(value: string): string {
  return value
    .replace(/&/g, "&amp;")
    .replace(/</g, "&lt;")
    .replace(/>/g, "&gt;")
    .replace(/"/g, "&quot;")
    .replace(/'/g, "&#39;");
}

/**
 * Minimal server-rendered page shell for the /admin area: no SPA framework, no
 * external assets, strict CSP, and a tiny amount of inline script for CSRF-aware
 * API calls (script-src 'unsafe-inline' is scoped to these pages only).
 */
export function adminPage(title: string, body: string): string {
  return `<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>${escapeHtml(title)} · WristBrief Admin</title>
<style>
:root { color-scheme: light; --ink:#182d30; --muted:#5d7274; --edge:#d7e2df; --accent:#096f67; --canvas:#f3f7f4; }
* { box-sizing:border-box; }
body { font: 15px/1.55 system-ui,-apple-system,"Segoe UI",sans-serif; margin:0; background:radial-gradient(circle at 95% 0%,#dcece5,transparent 38%),var(--canvas); color:var(--ink); }
main { max-width:1080px; margin:0 auto; padding:48px 24px 96px; }
main::before { content:"WRISTBRIEF  /  CONTROL ROOM"; display:block; color:var(--accent); font-size:11px; font-weight:800; letter-spacing:.16em; margin-bottom:28px; }
h1 { font-size:clamp(28px,4vw,42px); line-height:1.12; letter-spacing:-.035em; margin:0 0 24px; } h2 { font-size:17px; margin:0 0 12px; }
.card { background:#fff; border:1px solid var(--edge); border-radius:18px; padding:24px; margin:16px 0; box-shadow:0 8px 28px #143c3009; overflow-x:auto; }
label { display:block; color:var(--muted); font-size:13px; font-weight:650; margin:12px 0; }
input,select,button { font:inherit; }
input:not([type=checkbox]),select { display:block; width:100%; min-width:100px; padding:10px 12px; margin-top:5px; border:1px solid #bed0cb; border-radius:9px; background:#fff; color:var(--ink); }
input[type=checkbox] { accent-color:var(--accent); }
button { padding:10px 16px; border:0; border-radius:9px; background:var(--accent); color:#fff; font-weight:700; cursor:pointer; min-height:42px; }
button:hover { background:#07564f; } button:focus-visible,a:focus-visible,input:focus-visible,select:focus-visible { outline:3px solid #e1a536; outline-offset:2px; }
a { color:var(--accent); text-underline-offset:3px; font-weight:650; }
.muted { color:var(--muted); font-size:13px; }
table { border-collapse:collapse; width:100%; font-size:13px; }
td,th { text-align:left; border-bottom:1px solid var(--edge); padding:12px 10px; vertical-align:middle; }
th { color:var(--muted); font-size:11px; text-transform:uppercase; letter-spacing:.07em; }
td input,td select { min-width:110px; } code { background:#e9f0eb; border-radius:5px; padding:2px 5px; }
@media(max-width:640px){main{padding:32px 14px 64px}.card{padding:17px}td,th{padding:8px 6px}}
</style>
</head>
<body>
<main>
${body}
</main>
</body>
</html>`;
}

export function htmlResponse(html: string, status = 200, requestId?: string): Response {
  const headers: Record<string, string> = {
    "Content-Type": "text/html; charset=utf-8",
    "Cache-Control": "no-store",
    "Content-Security-Policy":
      "default-src 'none'; style-src 'unsafe-inline'; script-src 'unsafe-inline'; connect-src 'self'; form-action 'self'; base-uri 'none'; frame-ancestors 'none'",
    "Referrer-Policy": "no-referrer",
    "X-Content-Type-Options": "nosniff"
  };
  if (requestId) headers["X-Request-ID"] = requestId;
  return new Response(html, { status, headers });
}

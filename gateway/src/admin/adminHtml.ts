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
:root { color-scheme: light dark; }
body { font-family: system-ui, sans-serif; margin: 0; background: #f5f5f7; color: #1d1d1f; }
main { max-width: 720px; margin: 0 auto; padding: 32px 20px 64px; }
h1 { font-size: 24px; } h2 { font-size: 18px; margin-top: 32px; }
.card { background: #fff; border: 1px solid #e5e5ea; border-radius: 12px; padding: 20px; margin: 16px 0; }
input, button { font: inherit; }
input { display: block; width: 100%; box-sizing: border-box; margin: 6px 0 14px; padding: 8px 10px; border: 1px solid #c7c7cc; border-radius: 8px; }
button { padding: 8px 16px; border: none; border-radius: 8px; background: #0071e3; color: #fff; cursor: pointer; }
.muted { color: #6e6e73; font-size: 13px; }
table { border-collapse: collapse; width: 100%; font-size: 13px; }
td, th { text-align: left; border-bottom: 1px solid #e5e5ea; padding: 6px 8px; vertical-align: top; }
code { background: #e8e8ed; border-radius: 4px; padding: 1px 4px; }
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

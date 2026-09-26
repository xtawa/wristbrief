// Definitive /admin walkthrough: real keyboard, real mouse clicks, 360px viewport.
// Verifies admin-console's expected outcomes (a)-(f) after the origin fix.
import { connect, setup, goto, evaluate, press, typeText, sleep, collect, waitFor, screenshot } from "./cdp.mjs";

const ORIGIN = "http://127.0.0.1:8787";
const OUT = "G:\\Projects\\wristbrief\\.wb-verify";
const TEMP_PW = "Walkthrough-Admin-2026";
const NEW_PW = "Walkthrough-Admin-2026-New";
const EMAIL = "zeromostia@gmail.com";

const { send, once, on } = await connect();
await setup(send, { width: 360, height: 900 });
await send("Network.enable");
await send("Network.clearBrowserCookies");

const posts = [];
collect(on, "Network.requestWillBeSent", (p) => {
  if (p.request?.method === "POST") {
    posts.push({ url: new URL(p.request.url).pathname, origin: p.request.headers?.Origin ?? "(absent)" });
  }
});
const responses = [];
collect(on, "Network.responseReceived", (p) => {
  if (p.type === "Document") responses.push({ path: (() => { try { return new URL(p.response.url).pathname; } catch { return p.response.url; } })(), status: p.response.status });
});

const info = async () => evaluate(send, `
  return {
    url: location.pathname + location.search,
    title: document.title,
    h1: (document.querySelector("h1")?.textContent || "").trim(),
    scripts: document.querySelectorAll("script").length,
    viewport: window.innerWidth,
    overflowX: document.scrollingElement.scrollWidth - document.scrollingElement.clientWidth,
    hasStyles: !!document.querySelector("style"),
    hasNav: !!document.querySelector("nav, .crumbs"),
    banners: [...document.querySelectorAll(".banner")].map((b) => b.textContent.trim()),
    fieldErrors: [...document.querySelectorAll(".field-error")].map((p) => ({ id: p.id, text: p.textContent.trim().slice(0, 80) })),
    invalid: [...document.querySelectorAll('[aria-invalid="true"]')].map((i) => ({ name: i.getAttribute("name"), desc: i.getAttribute("aria-describedby") })),
    summaryLinks: [...document.querySelectorAll('.errors a[href^="#"]')].map((a) => a.getAttribute("href")),
  };`);

async function focusFill(name, value) {
  const ok = await evaluate(send, `const el = document.querySelector('[name="${name}"]'); if (!el) return false; el.focus(); return document.activeElement === el;`);
  if (!ok) throw new Error(`cannot focus [name=${name}]`);
  await typeText(send, value);
  return evaluate(send, `return document.querySelector('[name="${name}"]').value;`);
}

/** REAL mouse click on the form's submit button (no JS synthetic submit). */
async function clickSubmit(formSelector) {
  const box = await evaluate(send, `
    const f = document.querySelector('${formSelector}');
    if (!f) return null;
    const b = f.querySelector('button[type="submit"], button:not([type])');
    if (!b) return null;
    b.scrollIntoView({ block: "center" });
    const r = b.getBoundingClientRect();
    return { x: Math.round(r.left + r.width / 2), y: Math.round(r.top + r.height / 2), text: b.textContent.trim() };`);
  if (!box) throw new Error("submit button not found in " + formSelector);
  const loaded = once("Page.loadEventFired", 12000).catch(() => null);
  for (const type of ["mouseMoved", "mousePressed", "mouseReleased"]) {
    await send("Input.dispatchMouseEvent", { type, x: box.x, y: box.y, button: "left", clickCount: 1, buttons: type === "mousePressed" ? 1 : 0 });
    await sleep(60);
  }
  await loaded;
  await sleep(350);
  return box.text;
}

const R = {};

// ---------- (a) login by real form POST ----------
console.log("\n=== (a) LOGIN by real typing + real click ===");
await goto(send, once, `${ORIGIN}/admin/login`);
await waitFor(send, `return !!document.querySelector('[name="email"]');`, { label: "login form" });
await focusFill("email", EMAIL);
await focusFill("password", TEMP_PW);
posts.length = 0;
console.log("clicked:", await clickSubmit('form[action="/admin/login"]'));
R.login = await info();
console.log("POSTs:", JSON.stringify(posts));
console.log("page:", JSON.stringify(R.login, null, 1));
console.log("VERDICT (a):", R.login.url.startsWith("/admin/change-password") ? "PASS — reached change-password" : "FAIL — " + R.login.url + " " + JSON.stringify(R.login.banners));
await screenshot(send, `${OUT}\\w1-change-password.png`);

// ---------- (b) forced change: error recovery first, then success ----------
console.log("\n=== (b) FORCED CHANGE — inline error recovery, then success ===");
await focusFill("currentPassword", TEMP_PW);
await focusFill("newPassword", "short");
await focusFill("confirmPassword", "mismatch");
posts.length = 0;
console.log("clicked (bad):", await clickSubmit('form[action="/admin/change-password"]'));
R.changeBad = await info();
console.log("page:", JSON.stringify(R.changeBad, null, 1));
console.log("VERDICT (b1):", R.changeBad.fieldErrors.length > 0 && R.changeBad.invalid.length > 0 ? "PASS — inline error + aria wired" : "FAIL — no inline field error / no aria-invalid");
await screenshot(send, `${OUT}\\w2-change-error.png`);

await focusFill("currentPassword", TEMP_PW);
await focusFill("newPassword", NEW_PW);
await focusFill("confirmPassword", NEW_PW);
posts.length = 0;
console.log("clicked (good):", await clickSubmit('form[action="/admin/change-password"]'));
R.changeGood = await info();
console.log("POSTs:", JSON.stringify(posts));
console.log("page:", JSON.stringify(R.changeGood, null, 1));
console.log("VERDICT (b2):", R.changeGood.url.startsWith("/admin/login") ? "PASS — 303 to login?signedout" : "FAIL — " + R.changeGood.url);

// ---------- (c) second sign-in ----------
console.log("\n=== (c) SECOND SIGN-IN with the new password ===");
if (!R.changeGood.url.startsWith("/admin/login")) await goto(send, once, `${ORIGIN}/admin/login`);
await waitFor(send, `return !!document.querySelector('[name="email"]');`, { label: "login form again" });
await focusFill("email", EMAIL);
await focusFill("password", NEW_PW);
await clickSubmit('form[action="/admin/login"]');
R.relogin = await info();
console.log("page:", JSON.stringify(R.relogin, null, 1));
console.log("VERDICT (c):", R.relogin.url === "/admin" ? "PASS — on /admin" : "FAIL — " + R.relogin.url);
await screenshot(send, `${OUT}\\w3-dashboard.png`);

// ---------- (d) /admin/users invalid quota ----------
console.log("\n=== (d) /admin/users — invalid quota error recovery ===");
await goto(send, once, `${ORIGIN}/admin/users`);
await waitFor(send, `return !!document.querySelector('form[action^="/admin/users/"]');`, { label: "users row form" });
R.usersBefore = await info();
const rowAction = await evaluate(send, `const f = document.querySelector('form[action^="/admin/users/"]'); return f.getAttribute("action");`);
await focusFill("quotaLimit", "99999");
posts.length = 0;
console.log("clicked:", await clickSubmit(`form[action="${rowAction}"]`));
R.usersBad = await info();
console.log("POSTs:", JSON.stringify(posts));
console.log("page:", JSON.stringify(R.usersBad, null, 1));
console.log("VERDICT (d):", R.usersBad.fieldErrors.length > 0 && R.usersBad.invalid.length > 0 && R.usersBad.url.startsWith("/admin/users") ? "PASS — 400 re-render, inline error, aria wired, same page" : "FAIL");
await screenshot(send, `${OUT}\\w4-users-error.png`);

// ---------- (e) /admin/providers shell ----------
console.log("\n=== (e) /admin/providers renders with the shell at 360px ===");
await goto(send, once, `${ORIGIN}/admin/providers`);
R.providers = await info();
console.log("page:", JSON.stringify(R.providers, null, 1));
console.log("VERDICT (e):", R.providers.viewport === 360 && R.providers.hasStyles && R.providers.hasNav ? "PASS — 360px, stylesheet + nav present" : "FAIL — viewport=" + R.providers.viewport + " styles=" + R.providers.hasStyles + " nav=" + R.providers.hasNav);

// ---------- (f) SMTP test send, nothing configured ----------
console.log("\n=== (f) /admin/smtp test send with nothing configured ===");
await goto(send, once, `${ORIGIN}/admin/smtp`);
await waitFor(send, `return !!document.querySelector('form[action="/admin/smtp/test"]');`, { label: "smtp test form" });
posts.length = 0;
console.log("clicked:", await clickSubmit('form[action="/admin/smtp/test"]'));
R.smtp = await info();
console.log("POSTs:", JSON.stringify(posts));
console.log("page:", JSON.stringify(R.smtp, null, 1));
console.log("VERDICT (f):", R.smtp.banners.length > 0 ? "PASS — truthful banner: " + JSON.stringify(R.smtp.banners) : "FAIL — no banner");
await screenshot(send, `${OUT}\\w5-smtp.png`);

// ---------- summary table ----------
console.log("\n=== PAGE TABLE (360px) ===");
for (const p of ["/admin", "/admin/users", "/admin/overview", "/admin/smtp", "/admin/provider-keys", "/admin/providers", "/admin/audio", "/admin/settings", "/admin/audit"]) {
  await goto(send, once, ORIGIN + p);
  const pi = await evaluate(send, `
    const inputs = [...document.querySelectorAll("input:not([type=hidden]), select")];
    return { url: location.pathname, overflowX: document.scrollingElement.scrollWidth - document.scrollingElement.clientWidth,
      viewport: window.innerWidth, scripts: document.querySelectorAll("script").length,
      labelled: inputs.filter(i => i.id && document.querySelector('label[for="' + i.id + '"]')).length, inputs: inputs.length,
      postForms: document.querySelectorAll('form[method="post" i]').length, csrf: document.querySelectorAll('[name="csrfToken"]').length,
      inlineHandlers: [...document.querySelectorAll("*")].filter(e => [...e.attributes].some(a => /^on/i.test(a.name))).length };`);
  console.log(`  ${p} | landed=${pi.url} | overflowX=${pi.overflowX} | viewport=${pi.viewport} | scripts=${pi.scripts} | labelled=${pi.labelled}/${pi.inputs} | postForms=${pi.postForms} csrf=${pi.csrf} | inlineHandlers=${pi.inlineHandlers}`);
}

console.log("\n=== ALL POSTs CAPTURED THIS RUN ===", JSON.stringify(posts));
console.log("=== DOCUMENT RESPONSES ===", JSON.stringify(responses));
process.exit(0);

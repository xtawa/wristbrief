// Stages (d)-(f) + page table, after the origin fix. Real clicks, 360px.
import { connect, setup, goto, evaluate, typeText, sleep, collect, waitFor, screenshot } from "./cdp.mjs";

const ORIGIN = "http://127.0.0.1:8787";
const OUT = "G:\\Projects\\wristbrief\\.wb-verify";
const NEW_PW = "Walkthrough-Admin-2026-New";
const EMAIL = "zeromostia@gmail.com";

const { send, once, on } = await connect();
await setup(send, { width: 360, height: 900 });
await send("Network.enable");
await send("Network.clearBrowserCookies");

const posts = [];
collect(on, "Network.requestWillBeSent", (p) => {
  if (p.request?.method === "POST") posts.push({ url: new URL(p.request.url).pathname, origin: p.request.headers?.Origin ?? "(absent)" });
});

const info = () => evaluate(send, `
  return {
    url: location.pathname + location.search,
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
    rows: document.querySelectorAll('form[action^="/admin/users/"]').length,
  };`);

async function focusFill(name, value) {
  const ok = await evaluate(send, `const el = document.querySelector('[name="${name}"]'); if (!el) return false; el.focus(); return document.activeElement === el;`);
  if (!ok) throw new Error("cannot focus " + name);
  await typeText(send, value);
  return evaluate(send, `return document.querySelector('[name="${name}"]').value;`);
}

async function clickSubmit(sel) {
  const box = await evaluate(send, `
    const f = document.querySelector('${sel}');
    if (!f) return null;
    const b = f.querySelector('button[type="submit"], button:not([type])');
    if (!b) return null;
    b.scrollIntoView({ block: "center" });
    const r = b.getBoundingClientRect();
    return { x: Math.round(r.left + r.width / 2), y: Math.round(r.top + r.height / 2), text: b.textContent.trim() };`);
  if (!box) throw new Error("no submit button in " + sel);
  const loaded = once("Page.loadEventFired", 12000).catch(() => null);
  for (const type of ["mouseMoved", "mousePressed", "mouseReleased"]) {
    await send("Input.dispatchMouseEvent", { type, x: box.x, y: box.y, button: "left", clickCount: 1, buttons: type === "mousePressed" ? 1 : 0 });
    await sleep(60);
  }
  await loaded;
  await sleep(350);
  return box.text;
}

// login with the new password
await goto(send, once, `${ORIGIN}/admin/login`);
await waitFor(send, `return !!document.querySelector('[name="email"]');`, { label: "login" });
await focusFill("email", EMAIL);
await focusFill("password", NEW_PW);
await clickSubmit('form[action="/admin/login"]');
console.log("logged in, at:", await evaluate(send, "return location.pathname;"));

// ---------- (d) users page: invalid quota ----------
console.log("\n=== (d) /admin/users invalid-quota error recovery ===");
await goto(send, once, `${ORIGIN}/admin/users`);
await waitFor(send, `return document.querySelectorAll('form[action^="/admin/users/"]').length > 0;`, { label: "user row form" });
const before = await info();
console.log("rows on page:", before.rows, "| url:", before.url);
const rowAction = await evaluate(send, `return document.querySelector('form[action^="/admin/users/"]').getAttribute("action");`);
await focusFill("quotaLimit", "99999");
posts.length = 0;
console.log("clicked:", await clickSubmit(`form[action="${rowAction}"]`));
const bad = await info();
console.log("POSTs:", JSON.stringify(posts));
console.log("page:", JSON.stringify(bad, null, 1));
console.log("VERDICT (d):", bad.fieldErrors.length > 0 && bad.invalid.length > 0 && bad.url.startsWith("/admin/users") ? "PASS — 400 re-render, inline error + aria, same page" : "FAIL");
await screenshot(send, `${OUT}\\w6-users-quota-error.png`);

// ---------- (e) providers page shell ----------
console.log("\n=== (e) /admin/providers shell at 360px ===");
await goto(send, once, `${ORIGIN}/admin/providers`);
const prov = await info();
console.log(JSON.stringify(prov, null, 1));
console.log("VERDICT (e):", prov.viewport === 360 && prov.hasStyles ? "PASS — 360px with shell styles" : "FAIL");

// ---------- (f) SMTP test send, nothing configured ----------
console.log("\n=== (f) /admin/smtp test send (nothing configured) ===");
await goto(send, once, `${ORIGIN}/admin/smtp`);
const hasTestForm = await evaluate(send, `return !!document.querySelector('form[action="/admin/smtp/test"]');`);
console.log("test form present:", hasTestForm);
if (hasTestForm) {
  posts.length = 0;
  console.log("clicked:", await clickSubmit('form[action="/admin/smtp/test"]'));
  const smtp = await info();
  console.log("POSTs:", JSON.stringify(posts));
  console.log("page:", JSON.stringify(smtp, null, 1));
  console.log("VERDICT (f):", smtp.banners.length > 0 && !/sent/i.test(smtp.banners.join(" ")) ? "PASS — truthful banner: " + JSON.stringify(smtp.banners) : "CHECK — banners=" + JSON.stringify(smtp.banners));
  await screenshot(send, `${OUT}\\w7-smtp.png`);
}

// ---------- page table ----------
console.log("\n=== PAGE TABLE at 360px ===");
for (const p of ["/admin", "/admin/users", "/admin/overview", "/admin/smtp", "/admin/provider-keys", "/admin/providers", "/admin/audio", "/admin/settings", "/admin/audit"]) {
  await goto(send, once, ORIGIN + p);
  const pi = await evaluate(send, `
    const inputs = [...document.querySelectorAll("input:not([type=hidden]), select")];
    return { url: location.pathname, overflowX: document.scrollingElement.scrollWidth - document.scrollingElement.clientWidth,
      viewport: window.innerWidth, scripts: document.querySelectorAll("script").length, hasDoctype: document.doctype !== null,
      labelled: inputs.filter(i => i.id && document.querySelector('label[for="' + i.id + '"]')).length, inputs: inputs.length,
      postForms: document.querySelectorAll('form[method="post" i]').length, csrf: document.querySelectorAll('[name="csrfToken"]').length,
      inlineHandlers: [...document.querySelectorAll("*")].filter(e => [...e.attributes].some(a => /^on/i.test(a.name))).length };`);
  console.log(`  ${p} | landed=${pi.url} | doctype=${pi.hasDoctype} | viewport=${pi.viewport} | overflowX=${pi.overflowX} | scripts=${pi.scripts} | labelled=${pi.labelled}/${pi.inputs} | post=${pi.postForms} csrf=${pi.csrf} | inline=${pi.inlineHandlers}`);
}
console.log("\nALL POSTs:", JSON.stringify(posts));
process.exit(0);

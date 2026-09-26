// Blast radius: do REAL form POSTs work anywhere in the admin console?
// Uses an in-page fetch to establish a session (because the real form POST is the thing under test),
// then performs a genuine keyboard+mouse form submission on /admin/change-password.
import { connect, setup, goto, evaluate, press, typeText, sleep, collect, waitFor } from "./cdp.mjs";

const ORIGIN = "http://127.0.0.1:8787";
const TEMP_PW = "Walkthrough-Admin-2026";
const NEW_PW = "Walkthrough-Admin-2026-New";

const { send, once, on } = await connect();
await setup(send, { width: 360, height: 900 });
await send("Network.enable");
await send("Network.clearBrowserCookies");

const posts = [];
collect(on, "Network.requestWillBeSent", (p) => {
  if (p.request?.method === "POST") {
    posts.push({ url: p.request.url, origin: p.request.headers?.Origin ?? "(absent)", secFetchSite: p.request.headers?.["Sec-Fetch-Site"] ?? "(absent)" });
  }
});

async function focus(name) {
  return evaluate(send, `const el = document.querySelector('[name="${name}"]'); if (!el) return false; el.focus(); return document.activeElement === el;`);
}

// --- Step 1: establish a session with an in-page fetch (script POST works) ---
await goto(send, once, `${ORIGIN}/admin/login`);
await waitFor(send, `return !!document.querySelector('[name="email"]');`, { label: "login form" });
const login = await evaluate(send, `
  const body = new URLSearchParams({ email: "zeromostia@gmail.com", password: "${TEMP_PW}" });
  const res = await fetch("/admin/login", { method: "POST", headers: { "Content-Type": "application/x-www-form-urlencoded" }, body, redirect: "manual" });
  return { status: res.status, type: res.type };`);
console.log("STEP 1 — scripted fetch login:", JSON.stringify(login), "(opaqueredirect == 303 accepted)");

// --- Step 2: reach the forced password-change page ---
await goto(send, once, `${ORIGIN}/admin/change-password`);
await waitFor(send, `return !!document.querySelector('[name="newPassword"]');`, { label: "change-password form" });
console.log("STEP 2 — landed on:", await evaluate(send, "return location.pathname;"));

// Tab order check (keyboard reachability of the three fields + submit)
const order = [];
for (let i = 0; i < 4; i++) {
  await press(send, "Tab");
  order.push(await evaluate(send, `const a = document.activeElement; return a.getAttribute("name") || a.tagName;`));
}
console.log("STEP 2 — tab order:", JSON.stringify(order));

// --- Step 3: REAL form submission (typed + clicked) ---
await focus("currentPassword");
await typeText(send, TEMP_PW);
await focus("newPassword");
await typeText(send, NEW_PW);
await focus("confirmPassword");
await typeText(send, NEW_PW);
const box = await evaluate(send, `
  const b = document.querySelector('button[type="submit"]');
  b.scrollIntoView({ block: "center" });
  const r = b.getBoundingClientRect();
  return { x: Math.round(r.left + r.width / 2), y: Math.round(r.top + r.height / 2), text: b.textContent.trim() };`);
console.log("STEP 3 — clicking:", JSON.stringify(box));
posts.length = 0;
const loaded = once("Page.loadEventFired", 12000).catch(() => null);
for (const type of ["mouseMoved", "mousePressed", "mouseReleased"]) {
  await send("Input.dispatchMouseEvent", { type, x: box.x, y: box.y, button: "left", clickCount: 1, buttons: type === "mousePressed" ? 1 : 0 });
  await sleep(60);
}
await loaded;
await sleep(500);
console.log("STEP 3 — REAL form POST captured:", JSON.stringify(posts));
console.log(
  "STEP 3 — result page:",
  JSON.stringify(
    await evaluate(send, `return { url: location.pathname + location.search, banners: [...document.querySelectorAll(".banner")].map(b => b.textContent.trim()), fieldErrors: [...document.querySelectorAll(".field-error")].map(p => p.textContent.trim().slice(0,60)) };`)
  )
);

// --- Step 4: same submission via in-page fetch + CSRF token (proves the handler works) ---
const viaFetch = await evaluate(send, `
  const tok = document.querySelector('[name="csrfToken"]')?.value || "";
  const body = new URLSearchParams({ csrfToken: tok, currentPassword: "${TEMP_PW}", newPassword: "${NEW_PW}", confirmPassword: "${NEW_PW}" });
  const res = await fetch("/admin/change-password", { method: "POST", headers: { "Content-Type": "application/x-www-form-urlencoded" }, body, redirect: "manual" });
  return { status: res.status, type: res.type, hadToken: !!tok };`);
console.log("STEP 4 — scripted fetch change-password:", JSON.stringify(viaFetch), "(opaqueredirect == 303 accepted)");

// --- Step 5: overflow at 360px on the pages reachable now ---
console.log("STEP 5 — narrow-screen overflow on authenticated pages:");
for (const p of ["/admin", "/admin/users", "/admin/overview", "/admin/smtp", "/admin/providers", "/admin/audio", "/admin/settings", "/admin/audit"]) {
  await goto(send, once, ORIGIN + p);
  const pi = await evaluate(send, `
    return { url: location.pathname + location.search, h1: (document.querySelector("h1")?.textContent||"").trim(),
      overflowX: document.scrollingElement.scrollWidth - document.scrollingElement.clientWidth,
      docWidth: document.scrollingElement.scrollWidth, viewport: window.innerWidth,
      forms: document.querySelectorAll("form").length, inputs: document.querySelectorAll("input").length, scripts: document.querySelectorAll("script").length };`);
  console.log(`  ${p} -> ${pi.url} | h1="${pi.h1}" | overflowX=${pi.overflowX}px (doc ${pi.docWidth} / viewport ${pi.viewport}) | forms=${pi.forms} inputs=${pi.inputs} scripts=${pi.scripts}`);
}

process.exit(0);

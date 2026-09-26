// WristBrief /admin browser walkthrough — narrow viewport (360px), real keyboard, error recovery.
import { connect, setup, goto, evaluate, press, typeText, screenshot, sleep, ACTIVE, PAGEINFO } from "./cdp.mjs";

const ORIGIN = "http://127.0.0.1:8787";
const OUT = "G:\\Projects\\wristbrief\\.wb-verify";
const TEMP_PW = "Walkthrough-Admin-2026";
const NEW_PW = "Walkthrough-Admin-2026-New";
const ADMIN_EMAIL = "zeromostia@gmail.com";

const log = (...a) => console.log(...a);
const info = (o) => JSON.stringify(o, null, 1);

async function active(send) { return evaluate(send, ACTIVE); }
async function pageInfo(send) { return evaluate(send, PAGEINFO); }

async function tabOrder(send, steps = 5, label = "") {
  const seen = [];
  for (let i = 0; i < steps; i++) {
    await press(send, "Tab");
    seen.push(await active(send));
  }
  log(`  TAB ORDER ${label}: ${JSON.stringify(seen)}`);
  return seen;
}

async function focusByName(send, name) {
  const ok = await evaluate(send, `
    const el = document.querySelector('[name="${name}"]');
    if (!el) return false;
    el.focus();
    return document.activeElement === el;`);
  return ok;
}

async function fill(send, name, value) {
  const focused = await focusByName(send, name);
  if (!focused) throw new Error(`cannot focus [name=${name}]`);
  await typeText(send, value);
  return evaluate(send, `return document.querySelector('[name="${name}"]').value;`);
}

async function submitAndWait(send, once, selector) {
  const loaded = once("Page.loadEventFired", 12000).catch(() => null);
  const ok = await evaluate(send, `
    const f = document.querySelector('${selector}');
    if (!f) return false;
    if (typeof f.requestSubmit === "function") f.requestSubmit(); else f.submit();
    return true;`);
  if (!ok) throw new Error("submit target not found: " + selector);
  await loaded;
  await sleep(300);
}

async function main() {
  const { send, once } = await connect();
  await setup(send, { width: 360, height: 900 });
  const report = {};

  // ---------- STAGE 1: login page ----------
  log("\n=== STAGE 1: GET /admin/login (360px viewport) ===");
  await goto(send, once, `${ORIGIN}/admin/login`);
  report.loginPage = await pageInfo(send);
  log(info(report.loginPage));
  await tabOrder(send, 4, "from page load");
  await screenshot(send, `${OUT}\\01-login-360.png`);

  // ---------- STAGE 2: keyboard sign-in ----------
  log("\n=== STAGE 2: keyboard sign-in (Tab + type + Enter) ===");
  const emailFilled = await fill(send, "email", ADMIN_EMAIL);
  log("  email value:", emailFilled);
  await press(send, "Tab");
  log("  after Tab:", JSON.stringify(await active(send)));
  const pwFilled = await fill(send, "password", TEMP_PW);
  log("  password length typed:", String(pwFilled).length);
  const nav = once("Page.loadEventFired", 12000).catch(() => null);
  await press(send, "Enter");
  await nav;
  await sleep(400);
  report.afterLogin = await pageInfo(send);
  log(info(report.afterLogin));
  await screenshot(send, `${OUT}\\02-after-login.png`);

  // ---------- STAGE 3: forced change-password, error recovery ----------
  log("\n=== STAGE 3: forced change-password — ERROR RECOVERY ===");
  await tabOrder(send, 4, "change-password page");
  // 3a: too-short + mismatched new password must re-render with a field error and NOT clear the flag
  await fill(send, "currentPassword", TEMP_PW);
  await fill(send, "newPassword", "short");
  await fill(send, "confirmPassword", "different");
  await submitAndWait(send, once, 'form[action="/admin/change-password"]');
  report.shortPasswordAttempt = await pageInfo(send);
  log(info(report.shortPasswordAttempt));
  await screenshot(send, `${OUT}\\03-change-pw-error.png`);

  // 3b: correct change
  await fill(send, "currentPassword", TEMP_PW);
  await fill(send, "newPassword", NEW_PW);
  await fill(send, "confirmPassword", NEW_PW);
  await submitAndWait(send, once, 'form[action="/admin/change-password"]');
  report.afterChange = await pageInfo(send);
  log(info(report.afterChange));

  // ---------- STAGE 4: re-sign-in with new password ----------
  log("\n=== STAGE 4: re-sign-in with the new password (documented as required) ===");
  if (!String(report.afterChange.url).startsWith("/admin/login")) {
    await goto(send, once, `${ORIGIN}/admin/login`);
  }
  await fill(send, "email", ADMIN_EMAIL);
  await fill(send, "password", NEW_PW);
  await submitAndWait(send, once, 'form[action="/admin/login"]');
  report.afterReLogin = await pageInfo(send);
  log(info(report.afterReLogin));
  await screenshot(send, `${OUT}\\04-dashboard-360.png`);

  // ---------- STAGE 5: /admin/users — search, paging, invalid quota ----------
  log("\n=== STAGE 5: /admin/users — narrow width, keyboard, invalid field ===");
  await goto(send, once, `${ORIGIN}/admin/users`);
  report.usersPage = await pageInfo(send);
  log(info(report.usersPage));
  await screenshot(send, `${OUT}\\05-users-360.png`);
  // Find a user row form and post an out-of-range quota
  const rowForm = await evaluate(send, `
    const f = document.querySelector('form[action^="/admin/users/"]');
    return f ? { action: f.getAttribute("action"), fields: [...f.elements].map(e => e.name + ":" + (e.type||"")) } : null;`);
  log("  first user row form:", JSON.stringify(rowForm));
  if (rowForm) {
    const quotaFilled = await fill(send, "quotaLimit", "99999");
    log("  quota typed:", quotaFilled);
    await submitAndWait(send, once, `form[action="${rowForm.action}"]`);
    report.invalidQuota = await pageInfo(send);
    log(info(report.invalidQuota));
    await screenshot(send, `${OUT}\\06-users-quota-error.png`);
  }

  // ---------- STAGE 6: /admin/smtp test send ----------
  log("\n=== STAGE 6: /admin/smtp — truthful not-configured test send ===");
  await goto(send, once, `${ORIGIN}/admin/smtp`);
  report.smtpPage = await pageInfo(send);
  log(info(report.smtpPage));
  const testForm = await evaluate(send, `
    const fs = [...document.querySelectorAll("form")];
    const t = fs.find(f => /test/i.test(f.textContent) || /test/i.test(f.getAttribute("action")||""));
    return t ? { action: t.getAttribute("action"), fields: [...t.elements].map(e => e.name) } : fs.map(f => f.getAttribute("action"));`);
  log("  test-send form:", JSON.stringify(testForm));
  if (testForm && testForm.action) {
    await submitAndWait(send, once, `form[action="${testForm.action}"]`);
    report.smtpTestSend = await pageInfo(send);
    log(info(report.smtpTestSend));
  }
  await screenshot(send, `${OUT}\\07-smtp-360.png`);

  // ---------- STAGE 7: remaining pages + overflow check ----------
  log("\n=== STAGE 7: remaining pages at 360px (horizontal overflow) ===");
  const pages = ["/admin", "/admin/overview", "/admin/providers", "/admin/provider-keys", "/admin/audio", "/admin/settings", "/admin/audit"];
  report.pages = {};
  for (const p of pages) {
    await goto(send, once, ORIGIN + p);
    const pi = await pageInfo(send);
    report.pages[p] = { url: pi.url, h1: pi.h1, status: pi.status, forms: pi.forms, inputs: pi.inputs, buttons: pi.buttons, scripts: pi.scripts, overflowX: pi.overflowX, viewport: pi.viewport, banners: pi.banners };
    log(`  ${p} -> url=${pi.url} h1="${pi.h1}" forms=${pi.forms} inputs=${pi.inputs} buttons=${pi.buttons} scripts=${pi.scripts} overflowX=${pi.overflowX}px (viewport ${pi.viewport})`);
    if (p === "/admin/overview") await screenshot(send, `${OUT}\\08-overview-360.png`);
    if (p === "/admin/audio") await screenshot(send, `${OUT}\\09-audio-360.png`);
  }

  log("\n=== SUMMARY (JSON) ===");
  log(JSON.stringify(report, null, 1));
  process.exit(0);
}

main().catch((e) => {
  console.error("WALKTHROUGH FAILED:", e.message);
  process.exit(1);
});

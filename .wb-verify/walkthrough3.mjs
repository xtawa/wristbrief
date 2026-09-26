// Re-login with the new password, then measure narrow-screen overflow + keyboard reachability
// on the authenticated admin pages (GET navigations are unaffected by the form-POST origin bug).
import { connect, setup, goto, evaluate, press, sleep, collect, waitFor } from "./cdp.mjs";

const ORIGIN = "http://127.0.0.1:8787";
const NEW_PW = "Walkthrough-Admin-2026-New";

const { send, once, on } = await connect();
await setup(send, { width: 360, height: 900 });
await send("Network.enable");
await send("Network.clearBrowserCookies");

const posts = [];
collect(on, "Network.requestWillBeSent", (p) => {
  if (p.request?.method === "POST") posts.push({ url: p.request.url, origin: p.request.headers?.Origin ?? "(absent)" });
});

await goto(send, once, `${ORIGIN}/admin/login`);
await waitFor(send, `return !!document.querySelector('[name="email"]');`, { label: "login form" });
const login = await evaluate(send, `
  const body = new URLSearchParams({ email: "zeromostia@gmail.com", password: "${NEW_PW}" });
  const res = await fetch("/admin/login", { method: "POST", headers: { "Content-Type": "application/x-www-form-urlencoded" }, body, redirect: "manual" });
  return { status: res.status, type: res.type };`);
console.log("re-login with NEW password:", JSON.stringify(login));

const pages = ["/admin", "/admin/users", "/admin/overview", "/admin/smtp", "/admin/provider-keys", "/admin/providers", "/admin/audio", "/admin/settings", "/admin/audit"];
console.log("\npage | title | overflowX | doc/viewport | forms | inputs | buttons | scripts | labels | labelled-inputs");
for (const p of pages) {
  await goto(send, once, ORIGIN + p);
  const pi = await evaluate(send, `
    const inputs = [...document.querySelectorAll("input:not([type=hidden]), select")];
    const labelled = inputs.filter(i => i.id && document.querySelector('label[for="' + i.id + '"]'));
    return {
      url: location.pathname + location.search,
      h1: (document.querySelector("h1")?.textContent||"").trim(),
      overflowX: document.scrollingElement.scrollWidth - document.scrollingElement.clientWidth,
      docWidth: document.scrollingElement.scrollWidth, viewport: window.innerWidth,
      forms: document.querySelectorAll("form").length,
      inputs: inputs.length, buttons: document.querySelectorAll("button").length,
      scripts: document.querySelectorAll("script").length,
      labels: document.querySelectorAll("label").length,
      labelledInputs: labelled.length,
      postForms: document.querySelectorAll('form[method="post" i]').length,
      csrfFields: document.querySelectorAll('[name="csrfToken"]').length,
      inlineHandlers: [...document.querySelectorAll("*")].filter(e => [...e.attributes].some(a => /^on/i.test(a.name))).length,
    };`);
  console.log(`${p} | ${pi.h1} | ${pi.overflowX}px | ${pi.docWidth}/${pi.viewport} | f=${pi.forms} in=${pi.inputs} btn=${pi.buttons} js=${pi.scripts} lab=${pi.labels} | labelled=${pi.labelledInputs}/${pi.inputs} post=${pi.postForms} csrf=${pi.csrfFields} inlineHandlers=${pi.inlineHandlers} | landed=${pi.url}`);
}

// Keyboard reachability sample on /admin/users: count focusables reachable by Tab
await goto(send, once, `${ORIGIN}/admin/users`);
const focusables = await evaluate(send, `
  return [...document.querySelectorAll('a[href],button,input:not([type=hidden]),select,textarea,[tabindex]:not([tabindex="-1"])')].length;`);
const reached = [];
for (let i = 0; i < Math.min(focusables + 2, 18); i++) {
  await press(send, "Tab");
  reached.push(await evaluate(send, `const a = document.activeElement; return (a.name || a.tagName) + (a.id ? "#" + a.id : "");`));
}
console.log(`\n/admin/users focusables=${focusables}; Tab sequence: ${JSON.stringify(reached)}`);

console.log("\nPOSTs seen this run:", JSON.stringify(posts));
process.exit(0);

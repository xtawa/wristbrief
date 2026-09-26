// Capture the ACTUAL headers the browser sends on the admin login POST.
import { connect, setup, goto, evaluate, press, typeText, sleep, collect } from "./cdp.mjs";

const ORIGIN = "http://127.0.0.1:8787";

const { send, once, on } = await connect();
await setup(send, { width: 360, height: 900 });
await send("Network.enable");

const seen = [];
collect(on, "Network.requestWillBeSent", (p) => {
  if (p.request?.method === "POST") {
    seen.push({
      url: p.request.url,
      method: p.request.method,
      headers: p.request.headers,
      postData: p.request.postData,
      hasUserGesture: p.request.hasUserGesture,
    });
  }
});

await goto(send, once, `${ORIGIN}/admin/login`);

async function focus(name) {
  return evaluate(send, `
    const el = document.querySelector('[name="${name}"]');
    el.focus();
    return document.activeElement === el;`);
}
await focus("email");
await typeText(send, "zeromostia@gmail.com");
await focus("password");
await typeText(send, "Walkthrough-Admin-2026");

const loaded = once("Page.loadEventFired", 12000).catch(() => null);
await press(send, "Enter");
await loaded;
await sleep(500);

console.log("=== POSTs the browser actually sent ===");
for (const s of seen) {
  console.log(JSON.stringify({ url: s.url, headers: s.headers, postData: s.postData, hasUserGesture: s.hasUserGesture }, null, 1));
}

console.log("=== resulting page ===");
console.log(
  JSON.stringify(
    await evaluate(send, `
      return {
        url: location.pathname + location.search,
        banners: [...document.querySelectorAll(".banner")].map(b => b.textContent.trim()),
      };`),
    null,
    1
  )
);

console.log("=== in-page fetch POST /admin/login (control: browser-set Origin) ===");
console.log(
  JSON.stringify(
    await evaluate(send, `
      const body = new URLSearchParams({ email: "zeromostia@gmail.com", password: "Walkthrough-Admin-2026" });
      const res = await fetch("/admin/login", {
        method: "POST",
        headers: { "Content-Type": "application/x-www-form-urlencoded" },
        body, redirect: "manual",
      });
      return { status: res.status, type: res.type, location: res.headers.get("location") };`),
    null,
    1
  )
);

process.exit(0);

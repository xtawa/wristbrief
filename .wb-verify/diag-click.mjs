// Submit by a REAL mouse click on the submit button; capture the POST Origin header.
import { connect, setup, goto, evaluate, typeText, sleep, collect, waitFor } from "./cdp.mjs";

const ORIGIN = "http://127.0.0.1:8787";
const LABEL = process.env.LABEL || "browser";

const { send, once, on } = await connect();
await setup(send, { width: 360, height: 900 });
await send("Network.enable");
await send("Network.clearBrowserCookies");
if (process.env.MOVE_ONSCREEN === "1") {
  const { windowId } = await send("Browser.getWindowForTarget");
  await send("Browser.setWindowBounds", {
    windowId,
    bounds: { left: 40, top: 40, width: 420, height: 840, windowState: "normal" },
  });
  await sleep(1000);
}
await send("Page.bringToFront").catch(() => {});

const posts = [];
collect(on, "Network.requestWillBeSent", (p) => {
  if (p.request?.method === "POST") {
    posts.push({ url: p.request.url, origin: p.request.headers?.Origin ?? "(absent)", secFetchSite: p.request.headers?.["Sec-Fetch-Site"] ?? "(absent)" });
  }
});

await goto(send, once, `${ORIGIN}/admin/login`);
await waitFor(send, `return !!document.querySelector('[name="email"]') && !!document.querySelector('[name="password"]');`, { label: "login form present" });
console.log(`[${LABEL}] landed on:`, await evaluate(send, "return location.pathname + location.search;"));

async function focus(name) {
  return evaluate(send, `const el = document.querySelector('[name="${name}"]'); el.focus(); return document.activeElement === el;`);
}
await focus("email");
await typeText(send, "zeromostia@gmail.com");
await focus("password");
await typeText(send, "Walkthrough-Admin-2026");

// Real mouse click at the submit button's centre.
const box = await evaluate(send, `
  const b = document.querySelector('button[type="submit"]');
  if (!b) return null;
  b.scrollIntoView({ block: "center" });
  const r = b.getBoundingClientRect();
  return { x: Math.round(r.left + r.width / 2), y: Math.round(r.top + r.height / 2), w: Math.round(r.width), h: Math.round(r.height), text: b.textContent.trim() };`);
console.log(`[${LABEL}] submit button box:`, JSON.stringify(box));
if (!box) { console.log("no submit button"); process.exit(1); }

const loaded = once("Page.loadEventFired", 12000).catch(() => null);
for (const type of ["mouseMoved", "mousePressed", "mouseReleased"]) {
  await send("Input.dispatchMouseEvent", {
    type, x: box.x, y: box.y, button: "left", clickCount: 1, buttons: type === "mousePressed" ? 1 : 0,
  });
  await sleep(60);
}
await loaded;
await sleep(500);

console.log(`[${LABEL}] POSTs captured (${posts.length}):`);
for (const p of posts) console.log("  ", JSON.stringify(p));
console.log(`[${LABEL}] resulting page:`, JSON.stringify(await evaluate(send, `return { url: location.pathname + location.search, banners: [...document.querySelectorAll(".banner")].map(b => b.textContent.trim()) };`)));
process.exit(0);

// Minimal CDP client: real browser, narrow viewport, real keyboard input.
// No external deps (Node 22 global WebSocket).
const BASE = `http://127.0.0.1:${process.env.CDP_PORT || 9222}`;

async function pageTarget() {
  const list = await (await fetch(`${BASE}/json/list`)).json();
  const pages = list.filter((t) => t.type === "page");
  const page =
    pages.find((t) => t.url.startsWith("http://127.0.0.1:8787")) ??
    pages.find((t) => t.url.startsWith("http")) ??
    pages.find((t) => t.url === "about:blank") ??
    pages[0];
  if (!page) throw new Error("no page target");
  return page.webSocketDebuggerUrl;
}

export async function connect() {
  const ws = new WebSocket(await pageTarget());
  await new Promise((res, rej) => {
    ws.addEventListener("open", res, { once: true });
    ws.addEventListener("error", rej, { once: true });
  });
  let id = 0;
  const pending = new Map();
  const waiters = new Map();
  const listeners = new Map();
  ws.addEventListener("message", (ev) => {
    const msg = JSON.parse(ev.data);
    if (msg.id !== undefined && pending.has(msg.id)) {
      const { resolve, reject } = pending.get(msg.id);
      pending.delete(msg.id);
      msg.error ? reject(new Error(JSON.stringify(msg.error))) : resolve(msg.result);
      return;
    }
    if (msg.method) {
      for (const cb of listeners.get(msg.method) ?? []) cb(msg.params);
    }
    if (msg.method && waiters.has(msg.method)) {
      const list = waiters.get(msg.method);
      waiters.delete(msg.method);
      for (const r of list) r(msg.params);
    }
  });
  const send = (method, params = {}) =>
    new Promise((resolve, reject) => {
      const myId = ++id;
      pending.set(myId, { resolve, reject });
      ws.send(JSON.stringify({ id: myId, method, params }));
    });
  const on = (method, cb) => {
    const list = listeners.get(method) ?? [];
    list.push(cb);
    listeners.set(method, list);
  };
  const once = (method, timeoutMs = 15000) =>
    new Promise((resolve, reject) => {
      const list = waiters.get(method) ?? [];
      list.push(resolve);
      waiters.set(method, list);
      setTimeout(() => reject(new Error(`timeout waiting ${method}`)), timeoutMs);
    });
  return { ws, send, once, on };
}

export async function setup(send, { width = 360, height = 900 } = {}) {
  await send("Page.enable");
  await send("Runtime.enable");
  await send("DOM.enable");
  await send("Emulation.setDeviceMetricsOverride", {
    width,
    height,
    deviceScaleFactor: 2,
    mobile: true,
  });
}

export async function goto(send, once, url) {
  const loaded = once("Page.loadEventFired");
  await send("Page.navigate", { url });
  await loaded;
  await sleep(250);
}

export const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

/** Poll until expression returns truthy, or throw. Robust against navigation races. */
export async function waitFor(send, expression, { timeoutMs = 12000, label = "condition" } = {}) {
  const deadline = Date.now() + timeoutMs;
  let last;
  while (Date.now() < deadline) {
    try {
      last = await evaluate(send, expression);
      if (last) return last;
    } catch (e) {
      last = e.message;
    }
    await sleep(150);
  }
  throw new Error(`waitFor timed out (${label}); last=${JSON.stringify(last)}`);
}

export async function evaluate(send, expression) {
  const res = await send("Runtime.evaluate", {
    expression: `(async () => { ${expression} })()`,
    returnByValue: true,
    awaitPromise: true,
  });
  if (res.exceptionDetails) {
    throw new Error("eval failed: " + JSON.stringify(res.exceptionDetails.exception?.description ?? res.exceptionDetails));
  }
  return res.result.value;
}

/** Accumulate every occurrence of a CDP event (unlike once(), which resolves the first). */
export function collect(on, method, sink) {
  on(method, sink);
}

const KEYS = {
  Tab: { code: "Tab", vk: 9, text: "\t" },
  Enter: { code: "Enter", vk: 13, text: "\r" },
  Escape: { code: "Escape", vk: 27 },
};

export async function press(send, key) {
  const k = KEYS[key];
  if (!k) throw new Error("unknown key " + key);
  // A `keyDown` carrying `text` is what triggers the browser's default action
  // (implicit form submission for Enter, focus move for Tab). `rawKeyDown`
  // deliberately produces no char/text, so it does NOT submit a form.
  await send("Input.dispatchKeyEvent", {
    type: "keyDown",
    key,
    code: k.code,
    windowsVirtualKeyCode: k.vk,
    nativeVirtualKeyCode: k.vk,
    ...(k.text ? { text: k.text, unmodifiedText: k.text } : {}),
  });
  await send("Input.dispatchKeyEvent", {
    type: "keyUp", key, code: k.code, windowsVirtualKeyCode: k.vk, nativeVirtualKeyCode: k.vk,
  });
  await sleep(80);
}

export async function typeText(send, text) {
  await send("Input.insertText", { text });
  await sleep(60);
}

export async function screenshot(send, path) {
  const { data } = await send("Page.captureScreenshot", { format: "png" });
  const { writeFile } = await import("node:fs/promises");
  await writeFile(path, Buffer.from(data, "base64"));
  return path;
}

export const ACTIVE = `return (() => {
  const a = document.activeElement;
  if (!a) return null;
  return { tag: a.tagName, name: a.getAttribute("name"), id: a.id, type: a.getAttribute("type"), text: (a.textContent||"").trim().slice(0,40) };
})();`;

export const PAGEINFO = `return {
  url: location.pathname + location.search,
  title: document.title,
  forms: document.querySelectorAll("form").length,
  labels: document.querySelectorAll("label").length,
  inputs: document.querySelectorAll("input").length,
  buttons: document.querySelectorAll("button").length,
  scripts: document.querySelectorAll("script").length,
  status: document.body ? document.body.getAttribute("data-status") : null,
  h1: (document.querySelector("h1")?.textContent || "").trim(),
  banners: [...document.querySelectorAll(".banner")].map((b) => b.textContent.trim()),
  fieldErrors: [...document.querySelectorAll(".field-error")].map((p) => ({ id: p.id, text: p.textContent.trim().slice(0,90) })),
  invalidInputs: [...document.querySelectorAll('[aria-invalid="true"]')].map((i) => ({ name: i.getAttribute("name"), desc: i.getAttribute("aria-describedby") })),
  overflowX: document.scrollingElement.scrollWidth - document.scrollingElement.clientWidth,
  docWidth: document.scrollingElement.scrollWidth,
  viewport: window.innerWidth,
};`;

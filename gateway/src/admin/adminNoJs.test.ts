// @ts-nocheck
/**
 * The console must be fully operable with a keyboard and with JavaScript
 * disabled, at a narrow viewport. These assertions are static, so they hold for
 * every page at once rather than for the one page a manual pass happens to open:
 *
 *  - no page ships any script at all in single-admin mode, so nothing a user
 *    needs can depend on one running;
 *  - no inline event handler attributes (no div-soup click targets);
 *  - every non-hidden input and every select has an id and a matching
 *    `<label for>`;
 *  - every POST form carries the CSRF field, so a keyboard submit can never 403;
 *  - the shell has a viewport meta, a skip link and a `:focus-visible` outline.
 */
import { describe, expect, it } from "vitest";
import {
  adminEnv,
  callAdmin,
  createSetup,
  fakeSettings,
  jsonRequest,
  openSession,
  readPage,
  registerAdmin,
  seedSqlAccounts
} from "./adminTestHelpers";

const AUTHENTICATED_PAGES = [
  "/admin",
  "/admin/overview",
  "/admin/users",
  "/admin/smtp",
  "/admin/provider-keys",
  "/admin/audio",
  "/admin/settings",
  "/admin/providers",
  "/admin/audit"
];

function tags(html, name) {
  return [...html.matchAll(new RegExp(`<${name}\\b[^>]*>`, "gi"))].map((match) => match[0]);
}

/** Attribute value if it is present with a value, `""` if it is a bare boolean
 *  attribute (`required`, `checked`, …), or null when it is absent at all. */
function attr(tag, name) {
  const quoted = new RegExp(`\\b${name}="([^"]*)"`, "i").exec(tag);
  if (quoted) return quoted[1];
  return hasAttr(tag, name) ? "" : null;
}

/** True when the attribute appears at all, whether or not it carries a value. */
function hasAttr(tag, name) {
  return new RegExp(`\\b${name}(?=[\\s/>])`, "i").test(tag);
}

async function pageFixture() {
  const setup = createSetup();
  const adminId = await registerAdmin(setup);
  const readers = await seedSqlAccounts(setup, 2, { displayName: "Reader" });
  const fake = fakeSettings();
  const env = adminEnv(setup, { ADMIN_SETTINGS_SERVICE: fake.service });
  const session = await openSession(setup, env);
  expect(session.ok).toBe(true);
  return { setup, env, session, adminId, readers };
}

describe("the console works without JavaScript", () => {
  it("ships no script and no inline event handler on any page", async () => {
    const { setup, env, session } = await pageFixture();
    for (const path of AUTHENTICATED_PAGES) {
      const page = await readPage(setup, env, session, path);
      expect(page.status, `${path} status`).toBe(200);
      expect(page.text.includes("<script"), `${path} must not ship a script`).toBe(false);
      expect(page.text.includes("onclick="), `${path} must not use inline handlers`).toBe(false);
      expect(page.text.includes("onchange="), `${path} must not use inline handlers`).toBe(false);
      expect(page.text.includes("onsubmit="), `${path} must not use inline handlers`).toBe(false);
      expect(page.text.includes("javascript:"), `${path} must not use javascript: links`).toBe(false);
    }
  });

  it("gives every control a label and every POST form a CSRF field", async () => {
    const { setup, env, session } = await pageFixture();
    for (const path of AUTHENTICATED_PAGES) {
      const { text } = await readPage(setup, env, session, path);

      for (const input of tags(text, "input")) {
        const type = (attr(input, "type") ?? "text").toLowerCase();
        if (["hidden", "submit", "button", "reset"].includes(type)) continue;
        const id = attr(input, "id");
        expect(id, `${path}: every input needs an id (${input})`).toBeTruthy();
        expect(text.includes(`for="${id}"`), `${path}: input ${id} has no matching label`).toBe(true);
      }
      for (const select of tags(text, "select")) {
        const id = attr(select, "id");
        expect(id, `${path}: every select needs an id`).toBeTruthy();
        expect(text.includes(`for="${id}"`), `${path}: select ${id} has no matching label`).toBe(true);
      }

      const postForms = (text.match(/<form method="post"/g) ?? []).length;
      const csrfFields = (text.match(/name="csrfToken"/g) ?? []).length;
      expect(postForms, `${path}: expected at least one POST form`).toBeGreaterThan(0);
      expect(csrfFields, `${path}: every POST form needs a CSRF field`).toBe(postForms);
    }
  });

  it("assembles every page as a full document with the shared shell", async () => {
    const { setup, env, session } = await pageFixture();
    for (const path of AUTHENTICATED_PAGES) {
      const page = await readPage(setup, env, session, path);
      expect(page.status, `${path} status`).toBe(200);
      // A page rendered as a bare fragment has no document head, so the browser
      // falls back to a ~980px layout viewport and none of the styles apply.
      expect(page.text.startsWith("<!doctype html>"), `${path} must be a full document`).toBe(true);
      expect(page.text.includes('<meta name="viewport"'), `${path} needs a viewport meta`).toBe(true);
      expect(page.text.includes("--accent:#096f67"), `${path} is missing the shell stylesheet`).toBe(true);
      expect(page.text.includes('<a class="skip" href="#main-content">Skip to main content</a>'), `${path} needs the skip link`).toBe(true);
      expect(page.text.includes('<main id="main-content">'), `${path} needs the main landmark`).toBe(true);
      expect(page.text.includes('<ul class="crumbs">'), `${path} needs the section nav`).toBe(true);
      expect(page.text.includes('<nav class="admin-nav" aria-label="Admin sections">'), `${path} needs a named section nav`).toBe(true);
      expect(page.text.includes('>Workspace</span>') && page.text.includes('>Manage</span>'), `${path} needs navigation groups`).toBe(true);
      expect(page.text.includes('action="/admin/logout"'), `${path} needs a reachable sign-out form`).toBe(true);
      expect(page.text.includes('aria-current="page"'), `${path} needs to mark the current section`).toBe(true);
    }
  });

  it("has the shell affordances a keyboard and narrow screen need", async () => {
    const { setup, env, session } = await pageFixture();
    const { text } = await readPage(setup, env, session, "/admin/users");
    expect(text).toContain('<meta name="viewport" content="width=device-width, initial-scale=1">');
    expect(text).toContain('<a class="skip" href="#main-content">Skip to main content</a>');
    expect(text).toContain(":focus-visible");
    expect(text).toContain("overflow-wrap:anywhere");
    expect(text).toContain("@media(max-width:640px)");
    // Never a fixed pixel width that could overflow a 360px viewport. The
    // lookbehind keeps max-width/min-width out of the check.
    expect(/(?<![-a-z])width:\s*\d{3,}px/.test(text)).toBe(false);
    expect(/style="width:/.test(text)).toBe(false);
  });

  it("does not confuse gateway jobs with device RSS health", async () => {
    const { setup, env, session } = await pageFixture();
    const { text } = await readPage(setup, env, session, "/admin");
    expect(text).toContain("RSS and Atom subscriptions are fetched on each reader’s Android device");
    expect(text).toContain("job counts above do not indicate RSS health");
  });

  it("exposes each critical action as a submittable form", async () => {
    const { setup, env, session, readers } = await pageFixture();
    const expectations = [
      ["/admin/users", [`<form method="post" action="/admin/users/${readers[0].id}`, "Save changes</button>"]],
      ["/admin/smtp", ['<form method="post" action="/admin/smtp">', '<form method="post" action="/admin/smtp/test">', "Save SMTP settings</button>", "Send test message</button>"]],
      ["/admin/provider-keys", ['<form method="post" action="/admin/provider-keys">', "Save key</button>"]],
      ["/admin/audio", ['<form method="post" action="/admin/audio/mimo-tts">', '<form method="post" action="/admin/audio/mimo-tts/check">', "Save preset</button>", "Check connectivity</button>"]],
      ["/admin/settings", ['<form method="post" action="/admin/settings">', "Save settings</button>"]],
      ["/admin/providers", ['<form method="post" action="/admin/providers">', "Create provider</button>"]],
      ["/admin", ['<form method="post" action="/admin/logout">', "Sign out</button>"]],
      ["/admin/overview", ['<form class="search" method="get" action="/admin/overview">', "Show period</button>"]]
    ];
    for (const [path, fragments] of expectations) {
      const { text } = await readPage(setup, env, session, path);
      for (const fragment of fragments) {
        expect(text.includes(fragment), `${path} is missing ${fragment}`).toBe(true);
      }
    }
  });

  it("keeps the sign-in page script-free and the password change page in tab order", async () => {
    const { setup, env, session, adminId } = await pageFixture();

    // Anonymous view of the sign-in page.
    const login = await callAdmin(jsonRequest("/admin/login"), env);
    expect(login.status).toBe(200);
    expect(login.text.includes("<script")).toBe(false);
    expect(login.text).toContain('<form method="post" action="/admin/login">');
    const loginInputs = tags(login.text, "input").filter((tag) => (attr(tag, "type") ?? "text") !== "hidden");
    expect(loginInputs.map((tag) => attr(tag, "id"))).toEqual(["login-email", "login-password"]);
    expect(loginInputs.every((tag) => hasAttr(tag, "required"))).toBe(true);
    // Both are labelled and neither hides its value type from the browser.
    expect(loginInputs[0]).toContain('type="email"');
    expect(loginInputs[1]).toContain('type="password"');
    expect(login.text).toContain('<button type="submit">Sign in</button>');

    // The forced password change, as it is presented before the password is set.
    await setup.d1
      .prepare("INSERT INTO admin_security (user_id, must_change_password) VALUES (?, 1)")
      .bind(adminId)
      .run();
    const change = await readPage(setup, env, session, "/admin/change-password");
    expect(change.status).toBe(200);
    expect(change.text.includes("<script")).toBe(false);
    const passwordInputs = tags(change.text, "input")
      .filter((tag) => (attr(tag, "type") ?? "text") !== "hidden")
      .map((tag) => attr(tag, "id"));
    expect(passwordInputs).toEqual(["currentPassword", "newPassword", "confirmPassword"]);
    expect(change.text).toContain('<button type="submit">Change password</button>');
    expect(change.text.indexOf('id="currentPassword"')).toBeLessThan(change.text.indexOf('id="newPassword"'));
    expect(change.text.indexOf('id="newPassword"')).toBeLessThan(change.text.indexOf('id="confirmPassword"'));
  });
});

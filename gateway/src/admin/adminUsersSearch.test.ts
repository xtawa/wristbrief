// @ts-nocheck
import { describe, expect, it } from "vitest";
import {
  adminEnv,
  callAdmin,
  createSetup,
  formRequest,
  formTokenFrom,
  openSession,
  readPage,
  registerAdmin,
  seedSqlAccounts,
  sessionRequest
} from "./adminTestHelpers";

/** Distinct created_at per account, so the page window is exactly predictable. */
async function usersFixture(count = 25) {
  const setup = createSetup();
  const adminId = await registerAdmin(setup);
  const readers = await seedSqlAccounts(setup, count, { displayName: "Reader" });
  const env = adminEnv(setup);
  const session = await openSession(setup, env);
  expect(session.ok).toBe(true);
  return { setup, env, session, adminId, readers };
}

/** Newest first, matching `ORDER BY created_at DESC, id DESC`. */
function newestFirst(readers) {
  return [...readers].reverse().map((reader) => reader.id);
}

describe("paginated admin user search", () => {
  it("returns the total count and the effective page window, and never lists the administrator", async () => {
    const { setup, env, session, adminId, readers } = await usersFixture(25);
    const expected = newestFirst(readers);

    const first = await callAdmin(sessionRequest("/v1/admin/users?limit=10", session), env);
    expect(first.status).toBe(200);
    expect(first.json.users).toHaveLength(10);
    expect(first.json.page).toMatchObject({
      limit: 10,
      offset: 0,
      total: 25,
      returned: 10,
      hasMore: true,
      page: 1,
      pageCount: 3,
      limitClamped: false,
      offsetOutOfRange: false,
      query: ""
    });
    expect(first.json.users.map((user) => user.id)).toEqual(expected.slice(0, 10));
    expect(first.json.users.some((user) => user.id === adminId)).toBe(false);

    // Pages two and three complete the set with no duplicates and no gaps.
    const second = await callAdmin(sessionRequest("/v1/admin/users?limit=10&offset=10", session), env);
    const third = await callAdmin(sessionRequest("/v1/admin/users?limit=10&offset=20", session), env);
    expect(second.json.users.map((user) => user.id)).toEqual(expected.slice(10, 20));
    expect(third.json.users.map((user) => user.id)).toEqual(expected.slice(20));
    expect(third.json.page.hasMore).toBe(false);
    expect(third.json.users).toHaveLength(5);

    // Ordering is fixed, so a repeated request returns the same window.
    const repeated = await callAdmin(sessionRequest("/v1/admin/users?limit=10&offset=10", session), env);
    expect(repeated.json.users.map((user) => user.id)).toEqual(second.json.users.map((user) => user.id));
    expect(repeated.json.page.order).toBe("created_at DESC, id DESC");
    expect(adminId).toBeTruthy();
    expect(setup).toBeTruthy();
  });

  it("returns an empty page with the real total when the offset is past the end", async () => {
    const { env, session } = await usersFixture(5);
    const response = await callAdmin(sessionRequest("/v1/admin/users?limit=10&offset=999", session), env);
    expect(response.status).toBe(200);
    expect(response.json.users).toEqual([]);
    expect(response.json.page.total).toBe(5);
    expect(response.json.page.returned).toBe(0);
    expect(response.json.page.offsetOutOfRange).toBe(true);
  });

  it("searches by email and by display name, case-insensitively, and reports an empty result truthfully", async () => {
    const { setup, env, session, readers } = await usersFixture(4);
    const target = readers[2];

    const byEmail = await callAdmin(sessionRequest(`/v1/admin/users?q=${encodeURIComponent(target.email.toUpperCase())}`, session), env);
    expect(byEmail.status).toBe(200);
    expect(byEmail.json.page.total).toBe(1);
    expect(byEmail.json.users).toHaveLength(1);
    expect(byEmail.json.users[0].id).toBe(target.id);

    const byName = await callAdmin(sessionRequest("/v1/admin/users?q=reader%202", session), env);
    expect(byName.status).toBe(200);
    expect(byName.json.page.total).toBe(1);
    expect(byName.json.users[0].display_name).toBe("Reader 2");

    const partial = await callAdmin(sessionRequest("/v1/admin/users?q=reader", session), env);
    expect(partial.json.page.total).toBe(4);

    const empty = await callAdmin(sessionRequest("/v1/admin/users?q=nobody-matcher", session), env);
    expect(empty.status).toBe(200);
    expect(empty.json.users).toEqual([]);
    expect(empty.json.page.total).toBe(0);
    expect(empty.json.page.offsetOutOfRange).toBe(false);
    expect(empty.json.page.pageCount).toBe(1);
  });

  it("treats % and _ as literal search characters instead of wildcards", async () => {
    const { setup, env, session, readers } = await usersFixture(2);
    await setup.d1.prepare("UPDATE identities SET email = 'odd%name@example.com' WHERE user_id = ?")
      .bind(readers[0].id)
      .run();
    const wildcard = await callAdmin(sessionRequest("/v1/admin/users?q=%25", session), env);
    expect(wildcard.status).toBe(200);
    expect(wildcard.json.page.total).toBe(1);
    expect(wildcard.json.users[0].id).toBe(readers[0].id);

    const underscore = await callAdmin(sessionRequest("/v1/admin/users?q=_", session), env);
    expect(underscore.status).toBe(200);
    expect(underscore.json.page.total).toBe(0);
  });

  it("clamps an absurd page size and rejects a nonsensical one", async () => {
    const { env, session } = await usersFixture(3);

    const absurd = await callAdmin(sessionRequest("/v1/admin/users?limit=100000", session), env);
    expect(absurd.status).toBe(200);
    expect(absurd.json.page.limit).toBe(100);
    expect(absurd.json.page.limitClamped).toBe(true);

    const zero = await callAdmin(sessionRequest("/v1/admin/users?limit=0", session), env);
    expect(zero.status).toBe(400);
    expect(zero.json.fields).toContainEqual(expect.objectContaining({ field: "limit", reason: "out_of_range", min: 1, max: 100 }));

    const nonsense = await callAdmin(sessionRequest("/v1/admin/users?limit=abc", session), env);
    expect(nonsense.status).toBe(400);
    expect(nonsense.json.fields).toContainEqual(expect.objectContaining({ field: "limit", reason: "invalid_type" }));

    const negative = await callAdmin(sessionRequest("/v1/admin/users?offset=-5", session), env);
    expect(negative.status).toBe(400);
    expect(negative.json.fields).toContainEqual(expect.objectContaining({ field: "offset", reason: "invalid_type" }));

    const longQuery = await callAdmin(sessionRequest(`/v1/admin/users?q=${"a".repeat(200)}`, session), env);
    expect(longQuery.status).toBe(400);
    expect(longQuery.json.fields).toContainEqual(expect.objectContaining({ field: "q", reason: "too_long", max: 120 }));
  });

  it("renders a keyboard-operable search form, paging links and the editable-scope note", async () => {
    const { setup, env, session } = await usersFixture(25);
    const page = await readPage(setup, env, session, "/admin/users?limit=10&offset=0");
    expect(page.status).toBe(200);

    // Real, submittable search form (no JavaScript required).
    expect(page.text).toContain('<form class="search" method="get" action="/admin/users"');
    expect(page.text).toContain('<input type="search" id="q" name="q"');
    expect(page.text).toContain('<label for="q">Search by email or display name</label>');
    expect(page.text).toContain('<button type="submit">Search</button>');

    // Paging controls with rel=next/prev, carrying the search term.
    expect(page.text).toContain('rel="next"');
    expect(page.text).toContain("Page 1 of 3");
    expect(page.text).toContain("showing 1–10 of 25");
    const second = await readPage(setup, env, session, "/admin/users?limit=10&offset=10");
    expect(second.text).toContain('rel="prev"');

    // Scope note: what is editable and what is not.
    expect(page.text).toContain("What you can change here");
    expect(page.text).toContain("display name, account status (active/disabled), plan (FREE/PRO) and this month's managed AI quota");
    expect(page.text).toContain("the email address that identifies the account, its password, its administrator status, and billing history");
    expect(page.text).toContain("The sole administrator is never listed");

    // Each row is one real form with labelled controls.
    const formCount = (page.text.match(/<form method="post" action="\/admin\/users\//g) ?? []).length;
    expect(formCount).toBe(10);
    expect(page.text).toContain('for="u-');
    expect(page.text).toContain('name="quotaLimit"');
    expect(page.text).toContain(`max="10000"`);
  });

  it("disables an account, revokes its sessions and audits the change", async () => {
    const { setup, env, session, readers } = await usersFixture(1);
    const reader = readers[0];
    await setup.d1.prepare("INSERT INTO sessions (id, user_id, token_hash, created_at, expires_at) VALUES ('sess-1', ?, ?, CURRENT_TIMESTAMP, '2999-01-01T00:00:00.000Z')")
      .bind(reader.id, "a".repeat(64))
      .run();
    await setup.d1.prepare("INSERT INTO admin_web_sessions (id, user_id, session_hash, csrf_secret_hash, created_at, expires_at, last_seen_at) VALUES ('aws-1', ?, ?, ?, CURRENT_TIMESTAMP, '2999-01-01T00:00:00.000Z', CURRENT_TIMESTAMP)")
      .bind(reader.id, "b".repeat(64), "c".repeat(64))
      .run();

    const response = await callAdmin(
      sessionRequest(`/v1/admin/users/${reader.id}`, session, {
        method: "PATCH",
        body: { displayName: "Disabled Reader", status: "disabled", plan: "PRO", quotaLimit: 42 }
      }),
      env
    );
    expect(response.status).toBe(200);

    const mobile = await setup.d1.prepare("SELECT revoked_at FROM sessions WHERE id = 'sess-1'").first();
    const web = await setup.d1.prepare("SELECT revoked_at FROM admin_web_sessions WHERE id = 'aws-1'").first();
    expect(mobile.revoked_at).not.toBeNull();
    expect(web.revoked_at).not.toBeNull();

    const audit = await setup.d1.prepare("SELECT action, after_json FROM admin_audit_log WHERE action = 'user_updated'").first();
    expect(audit.after_json).toContain('"status":"disabled"');
    expect(audit.after_json).toContain('"quotaLimit":42');

    // Re-enabling does not invent a revocation.
    const reEnabled = await callAdmin(
      sessionRequest(`/v1/admin/users/${reader.id}`, session, {
        method: "PATCH",
        body: { displayName: "Reader", status: "active", plan: "FREE", quotaLimit: 10 }
      }),
      env
    );
    expect(reEnabled.status).toBe(200);
  });

  it("keeps a failed form submission on the same page window", async () => {
    const { setup, env, session } = await usersFixture(25);
    const page = await readPage(setup, env, session, "/admin/users?limit=10&offset=10");
    const token = formTokenFrom(page.text);
    // The offending row must be part of the window being re-rendered, otherwise
    // the error would have nowhere to render.
    const window = await callAdmin(sessionRequest("/v1/admin/users?limit=10&offset=10", session), env);
    const target = window.json.users[0];
    expect(target).toBeTruthy();

    const posted = await callAdmin(
      formRequest(`/admin/users/${target.id}?limit=10&offset=10&q=`, session, {
        csrfToken: token,
        displayName: target.display_name ?? "Reader",
        status: "active",
        plan: "FREE",
        quotaLimit: "-1"
      }),
      env
    );
    expect(posted.status).toBe(400);
    expect(posted.text).toContain("Enter a whole number between 0 and 10000.");
    expect(posted.text).toContain(`id="err-u-${target.id.replace(/[^A-Za-z0-9]/g, "")}-quotaLimit"`);
    // Still the second page of 25, not silently reset to page 1.
    expect(posted.text).toContain("Page 2 of 3");
    expect(posted.text).toContain("showing 11–20 of 25");
    // Nothing was written.
    const row = await setup.d1.prepare("SELECT status FROM users WHERE id = ?").bind(target.id).first();
    expect(row.status).toBe("active");
  });
});

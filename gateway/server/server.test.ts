import { afterEach, expect, it } from "vitest";
import { mkdtemp, readFile, rm } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { startServer } from "./index";

const active: Array<{ server: Awaited<ReturnType<typeof startServer>>; dir: string }> = [];
afterEach(async () => {
  for (const { server, dir } of active.splice(0)) {
    await new Promise<void>((resolve) => server.server.close(() => resolve()));
    server.sqlite.close();
    await rm(dir, { recursive: true, force: true });
  }
});

it("requires the sole administrator to rotate the bootstrap password and encrypts provider keys", async () => {
  const dir = await mkdtemp(join(tmpdir(), "wristbrief-server-test-"));
  const initial = "one-time-long-admin-password";
  const started = await startServer({
    WRISTBRIEF_DATA_DIR: dir,
    WRISTBRIEF_MASTER_KEY: Buffer.alloc(32, 7).toString("base64"),
    WRISTBRIEF_ADMIN_INITIAL_PASSWORD: initial,
    PORT: "0"
  });
  active.push({ server: started, dir });
  const origin = `http://127.0.0.1:${(started.server.address() as { port: number }).port}`;
  expect(await (await fetch(`${origin}/v1/admin/bootstrap-status`)).json()).toMatchObject({ registrationOpen: false, mobileRegistrationOpen: false });
  const loginPage = await (await fetch(`${origin}/admin/login`)).text();
  expect(loginPage).not.toContain("Create the first admin account");
  const login = await fetch(`${origin}/v1/admin/session`, {
    method: "POST", headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ email: "zeromostia@gmail.com", password: initial })
  });
  expect(login.status).toBe(200);
  const cookies = login.headers.getSetCookie();
  const cookieHeader = cookies.map((value) => value.split(";")[0]).join("; ");
  const csrf = /wristbrief_admin_csrf=([^;]+)/.exec(cookieHeader)?.[1];
  expect(csrf).toBeTruthy();
  expect((await fetch(`${origin}/admin`, { headers: { Cookie: cookieHeader }, redirect: "manual" })).headers.get("location"))
    .toBe("/admin/change-password");
  const blocked = await fetch(`${origin}/v1/admin/users`, { headers: { Cookie: cookieHeader } });
  expect(blocked.status).toBe(403);
  const change = await fetch(`${origin}/v1/admin/change-password`, {
    method: "POST", headers: { "Content-Type": "application/json", Cookie: cookieHeader, "X-CSRF-Token": csrf!, Origin: "http://localhost:8787" },
    body: JSON.stringify({ currentPassword: initial, newPassword: "new-long-and-private-password" })
  });
  expect(change.status).toBe(200);
  const relogin = await fetch(`${origin}/v1/admin/session`, {
    method: "POST", headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ email: "zeromostia@gmail.com", password: "new-long-and-private-password" })
  });
  expect(relogin.status).toBe(200);
  const nextCookies = relogin.headers.getSetCookie().map((value) => value.split(";")[0]).join("; ");
  const nextCsrf = /wristbrief_admin_csrf=([^;]+)/.exec(nextCookies)?.[1];
  const earlyRegistration = await fetch(`${origin}/v1/admin/settings`, {
    method: "PATCH", headers: { "Content-Type": "application/json", Cookie: nextCookies, "X-CSRF-Token": nextCsrf!, Origin: "http://localhost:8787" },
    body: JSON.stringify({ registrationMode: "OPEN" })
  });
  expect(earlyRegistration.status).toBe(409);
  const save = await fetch(`${origin}/v1/admin/provider-keys`, {
    method: "PUT", headers: { "Content-Type": "application/json", Cookie: nextCookies, "X-CSRF-Token": nextCsrf!, Origin: "http://localhost:8787" },
    body: JSON.stringify({ slot: "AI_PROVIDER_SECRET_1", secret: "sensitive-provider-key-123" })
  });
  expect(save.status).toBe(200);
  const disk = await readFile(join(dir, "wristbrief.sqlite"));
  expect(disk.includes(Buffer.from("sensitive-provider-key-123"))).toBe(false);
  expect((await fetch(`${origin}/v1/admin/users`, { headers: { Cookie: nextCookies } })).status).toBe(200);
  started.sqlite.prepare("INSERT INTO users (id) VALUES ('test-user')").run();
  started.sqlite.prepare("INSERT INTO identities (provider, provider_subject, user_id, email) VALUES ('google', 'google-test', 'test-user', 'reader@example.com')").run();
  const update = await fetch(`${origin}/v1/admin/users/test-user`, {
    method: "PATCH", headers: { "Content-Type": "application/json", Cookie: nextCookies, "X-CSRF-Token": nextCsrf!, Origin: "http://localhost:8787" },
    body: JSON.stringify({ displayName: "Reader", status: "disabled", plan: "PRO", quotaLimit: 25 })
  });
  expect(update.status).toBe(200);
  const users = await (await fetch(`${origin}/v1/admin/users`, { headers: { Cookie: nextCookies } })).json() as { users: Array<Record<string, unknown>> };
  expect(users.users.find((u) => u.id === "test-user")).toMatchObject({ display_name: "Reader", status: "disabled", plan: "PRO", quota: 25 });
  const smtp = await fetch(`${origin}/v1/admin/smtp`, {
    method: "PUT", headers: { "Content-Type": "application/json", Cookie: nextCookies, "X-CSRF-Token": nextCsrf!, Origin: "http://localhost:8787" },
    body: JSON.stringify({ host: "mail.example.com", port: 587, secure: false, username: "sender", password: "smtp-private-password", from: "sender@example.com" })
  });
  expect(smtp.status).toBe(200);
  expect((await readFile(join(dir, "wristbrief.sqlite"))).includes(Buffer.from("smtp-private-password"))).toBe(false);
  const registration = await fetch(`${origin}/v1/admin/settings`, {
    method: "PATCH", headers: { "Content-Type": "application/json", Cookie: nextCookies, "X-CSRF-Token": nextCsrf!, Origin: "http://localhost:8787" },
    body: JSON.stringify({ registrationMode: "OPEN" })
  });
  expect(registration.status).toBe(200);
});

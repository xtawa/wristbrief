// @ts-nocheck
import { describe, expect, it } from "vitest";
import { adminEnv, callAdmin, createSetup, openSession, readPage, registerAdmin, seedAccounts, sessionRequest } from "./adminTestHelpers";

const CURRENT_PERIOD = new Date().toISOString().slice(0, 7);
const HOUR = 3600 * 1000;

async function overviewFixture() {
  const setup = createSetup();
  const adminId = await registerAdmin(setup);
  const readers = await seedAccounts(setup, 2);
  const env = adminEnv(setup, { FREE_AI_MONTHLY_LIMIT: "10", PRO_AI_MONTHLY_LIMIT: "100" });
  const session = await openSession(setup, env);
  expect(session.ok).toBe(true);
  return { setup, env, session, readers, adminId };
}

async function insertJob(setup, { id, status, errorCode = null, attempts = 1, userId, ageMs = 0 }) {
  await setup.d1
    .prepare(
      `INSERT INTO artifact_jobs (id, dedupe_key, user_id, content_id, artifact_type, language, status, attempt_count, error_code, created_at, updated_at)
       VALUES (?, ?, ?, ?, 'transcript', 'en', ?, ?, ?, ?, ?)`
    )
    .bind(id, `dedupe-${id}`, userId, `content-${id}`, status, attempts, errorCode, Date.now() - ageMs, Date.now() - ageMs)
    .run();
}

describe("operational overview", () => {
  it("reports an explicitly empty database instead of a zero-filled dashboard", async () => {
    const { env, session, adminId } = await overviewFixture();
    const response = await callAdmin(sessionRequest("/v1/admin/overview", session), env);
    expect(response.status).toBe(200);
    expect(response.json.period).toBe(CURRENT_PERIOD);
    expect(response.json.jobs).toMatchObject({ total: 0, active: 0, failed: 0, byStatus: [] });
    expect(response.json.recentFailures).toEqual([]);
    expect(response.json.empty).toEqual({ noJobs: true, noFailures: true, noQuotaRows: true });
    // Only editable accounts appear; the sole administrator is not one of them.
    expect(response.json.quota.totalUsers).toBe(2);
    expect(response.json.quota.rows.some((row) => row.userId === adminId)).toBe(false);
    expect(response.json.quota.rows.every((row) => row.hasUsageRow === false)).toBe(true);
    expect(response.json.quota.totalUsed).toBe(0);
  });

  it("counts real job rows by status and lists recent failures with codes and timestamps", async () => {
    const { setup, env, session, readers } = await overviewFixture();
    const [first, second] = readers;
    await insertJob(setup, { id: "job-queued-1", status: "queued", userId: first.id, ageMs: 5 * HOUR });
    await insertJob(setup, { id: "job-queued-2", status: "queued", userId: second.id, ageMs: 4 * HOUR });
    await insertJob(setup, { id: "job-running", status: "running", userId: first.id, ageMs: 3 * HOUR });
    await insertJob(setup, { id: "job-completed", status: "completed", userId: second.id, ageMs: 2 * HOUR });
    await insertJob(setup, { id: "job-failed", status: "failed", errorCode: "stt_upstream_429", attempts: 3, userId: first.id, ageMs: HOUR });
    await insertJob(setup, { id: "job-retried-ok", status: "completed", errorCode: "transcode_retry", attempts: 2, userId: second.id });

    const response = await callAdmin(sessionRequest("/v1/admin/overview", session), env);
    expect(response.status).toBe(200);
    expect(response.json.jobs.total).toBe(6);
    expect(response.json.jobs.active).toBe(3);
    expect(response.json.jobs.failed).toBe(1);
    expect(response.json.jobs.byStatus).toEqual([
      { status: "completed", count: 2 },
      { status: "failed", count: 1 },
      { status: "queued", count: 2 },
      { status: "running", count: 1 }
    ]);
    expect(response.json.empty.noJobs).toBe(false);

    // Recent failures: anything failed, or anything that ever recorded an error.
    expect(response.json.recentFailures.map((failure) => failure.jobId)).toEqual([
      "job-retried-ok",
      "job-failed"
    ]);
    const failed = response.json.recentFailures.find((failure) => failure.jobId === "job-failed");
    expect(failed).toMatchObject({ errorCode: "stt_upstream_429", attempts: 3, status: "failed", language: "en", artifactType: "transcript" });
    const occurredMs = Date.parse(failed.occurredAt);
    expect(Number.isFinite(occurredMs)).toBe(true);
    // The row was seeded an hour ago; the timestamp is the real updated_at.
    expect(Math.abs(Date.now() - occurredMs - HOUR)).toBeLessThan(5 * 60 * 1000);
    expect(response.json.empty.noFailures).toBe(false);
  });

  it("reports current-period managed AI quota per editable account", async () => {
    const { setup, env, session, readers } = await overviewFixture();
    const [first, second] = readers;
    await setup.d1
      .prepare("INSERT INTO managed_ai_usage (user_id, period_key, used, quota_limit) VALUES (?, ?, 7, 50)")
      .bind(first.id, CURRENT_PERIOD)
      .run();
    await setup.d1
      .prepare("INSERT INTO managed_ai_usage (user_id, period_key, used, quota_limit) VALUES (?, '2020-01', 99, 999)")
      .bind(second.id)
      .run();
    await setup.d1
      .prepare("INSERT INTO membership_entitlements (user_id, plan, source) VALUES (?, 'PRO', 'play')")
      .bind(second.id)
      .run();

    const response = await callAdmin(sessionRequest("/v1/admin/overview", session), env);
    expect(response.status).toBe(200);
    const firstRow = response.json.quota.rows.find((row) => row.userId === first.id);
    expect(firstRow).toMatchObject({ used: 7, quotaLimit: 50, remaining: 43, percentUsed: 14, hasUsageRow: true, plan: "FREE" });
    const secondRow = response.json.quota.rows.find((row) => row.userId === second.id);
    // A PRO account with no row for this period uses the deployment PRO default.
    expect(secondRow).toMatchObject({ used: 0, quotaLimit: 100, remaining: 100, hasUsageRow: false, plan: "PRO" });
    expect(response.json.quota).toMatchObject({ totalUsers: 2, usersWithUsage: 1, usersWithoutUsage: 1, totalUsed: 7 });
    expect(response.json.empty.noQuotaRows).toBe(false);

    // A historical period shows no usage at all, because none was recorded then.
    const historic = await callAdmin(sessionRequest("/v1/admin/overview?period=2020-01", session), env);
    expect(historic.status).toBe(200);
    expect(historic.json.quota.period).toBe("2020-01");
    expect(historic.json.quota.rows.find((row) => row.userId === first.id)).toMatchObject({ used: 0, hasUsageRow: false });
    // managed_ai_usage in 2020-01 has no effect on the seeded target period.
    expect(response.json.quota.totalUsed).toBe(7);
  });

  it("rejects a malformed period and accepts a well-formed one", async () => {
    const { env, session } = await overviewFixture();
    const bad = await callAdmin(sessionRequest("/v1/admin/overview?period=2020-13", session), env);
    expect(bad.status).toBe(400);
    expect(bad.json.fields).toContainEqual(expect.objectContaining({ field: "period", reason: "invalid_format" }));

    const good = await callAdmin(sessionRequest("/v1/admin/overview?period=2020-02", session), env);
    expect(good.status).toBe(200);
    expect(good.json.period).toBe("2020-02");
  });

  it("renders the empty state, the failure table and the quota table on the page", async () => {
    const { setup, env, session, readers, adminId } = await overviewFixture();
    const empty = await readPage(setup, env, session, "/admin/overview");
    expect(empty.status).toBe(200);
    expect(empty.text).toContain("No transcript or artifact jobs have been recorded yet");
    expect(empty.text).toContain("No failures recorded. This is a real empty result, not a placeholder.");
    expect(empty.text).toContain("No account has consumed quota");
    expect(empty.text).not.toContain(adminId);
    expect(empty.text).toContain('<form class="search" method="get" action="/admin/overview">');

    await insertJob(setup, { id: "job-failed", status: "failed", errorCode: "tts_upstream_500", attempts: 2, userId: readers[0].id });
    await setup.d1
      .prepare("INSERT INTO managed_ai_usage (user_id, period_key, used, quota_limit) VALUES (?, ?, 3, 10)")
      .bind(readers[0].id, CURRENT_PERIOD)
      .run();

    const populated = await readPage(setup, env, session, "/admin/overview");
    expect(populated.text).toContain("tts_upstream_500");
    expect(populated.text).toContain("job-failed");
    expect(populated.text).toMatch(/<time datetime="\d{4}-\d{2}-\d{2}T/);
    expect(populated.text).toContain(CURRENT_PERIOD);
    expect(populated.text).not.toContain("No failures recorded");
  });

  it("requires admin authentication for both the API and the page", async () => {
    const { env } = await overviewFixture();
    const api = await callAdmin(sessionRequest("/v1/admin/overview", { cookie: "", csrfToken: "" }), env);
    expect(api.status).toBe(401);
    const page = await callAdmin(sessionRequest("/admin/overview", { cookie: "", csrfToken: "" }), env);
    expect(page.status).toBe(302);
    expect(page.response.headers.get("Location")).toBe("/admin/login");
  });
});

// @ts-nocheck
import { describe, expect, it } from "vitest";
import { ProviderError } from "../provider";
import { classifyProviderFailure } from "./adminConnectivity";
import {
  adminEnv,
  callAdmin,
  createSetup,
  openSession,
  readPage,
  registerAdmin,
  sessionRequest
} from "./adminTestHelpers";

/** A fetch double that records the request and returns a fixed response. */
function fakeFetch(handler) {
  const calls = [];
  const fn = async (url, init) => {
    calls.push({ url, init });
    const outcome = handler(url, init);
    if (outcome instanceof Error) throw outcome;
    return new Response(outcome.body ?? "", { status: outcome.status ?? 200, headers: outcome.headers ?? {} });
  };
  return { fn, calls };
}

async function audioFixture(handler, extra = {}, options = {}) {
  const setup = createSetup();
  await registerAdmin(setup);
  const probe = fakeFetch(handler);
  const env = adminEnv(setup, { ADMIN_FETCH: probe.fn, ...extra });
  const session = await openSession(setup, env);
  expect(session.ok).toBe(true);
  // Every preset ships disabled (migration 0014), and a disabled preset is
  // reported as disabled rather than probed, so enable the one under test.
  if (options.enable !== false) {
    const enabled = await callAdmin(
      sessionRequest("/v1/admin/audio/mimo-tts", session, {
        method: "PATCH",
        body: { model: "mimo-v2.5-tts", voice: "mimo_default", secretRef: "AI_PROVIDER_SECRET_1", enabled: true, priority: 10 }
      }),
      env
    );
    expect(enabled.status).toBe(200);
  }
  probe.calls.length = 0;
  return { setup, env, session, probe };
}

describe("audio preset connectivity checks", () => {
  it("reports ok, auth_rejected, upstream_error and unreachable from real request outcomes", async () => {
    const cases = [
      [{ status: 200 }, "ok", 200],
      [{ status: 401 }, "auth_rejected", 502],
      [{ status: 403 }, "auth_rejected", 502],
      [{ status: 500 }, "upstream_error", 502],
      [{ status: 404 }, "upstream_error", 502]
    ];
    for (const [outcome, expectedStatus, expectedHttp] of cases) {
      const { env, session, probe } = await audioFixture(() => outcome, { AI_PROVIDER_SECRET_1: "audio-secret-key" });
      const response = await callAdmin(
        sessionRequest("/v1/admin/audio/mimo-tts/check", session, { method: "POST", body: {} }),
        env
      );
      expect(response.status).toBe(expectedHttp);
      expect(response.json.status).toBe(expectedStatus);
      expect(response.json.stored).toBe(true);
      expect(response.json.checkedAt).toBeTruthy();
      expect(probe.calls).toHaveLength(1);
      // The key is sent to the provider but never returned.
      expect(JSON.stringify(response.json)).not.toContain("audio-secret-key");
    }

    const { env, session } = await audioFixture(() => new Error("dns failure"), { AI_PROVIDER_SECRET_1: "audio-secret-key" });
    const unreachable = await callAdmin(sessionRequest("/v1/admin/audio/mimo-tts/check", session, { method: "POST", body: {} }), env);
    expect(unreachable.status).toBe(502);
    expect(unreachable.json.status).toBe("unreachable");
    expect(unreachable.json.reachable).toBe(false);
  });

  it("distinguishes an unset key slot and a disabled preset without calling out", async () => {
    const { env, session, probe } = await audioFixture(() => ({ status: 200 }));
    const unset = await callAdmin(sessionRequest("/v1/admin/audio/mimo-tts/check", session, { method: "POST", body: {} }), env);
    expect(unset.status).toBe(409);
    expect(unset.json.status).toBe("not_configured");
    expect(unset.json.detail).toBe("secret_slot_empty");
    expect(probe.calls).toHaveLength(0);

    const { env: env2, session: session2, probe: probe2 } = await audioFixture(() => ({ status: 200 }), { AI_PROVIDER_SECRET_1: "k" }, { enable: false });
    const disabled = await callAdmin(sessionRequest("/v1/admin/audio/deepgram-stt/check", session2, { method: "POST", body: {} }), env2);
    expect(disabled.status).toBe(409);
    expect(disabled.json.status).toBe("disabled");
    expect(probe2.calls).toHaveLength(0);
  });

  it("stores the last result and surfaces it as evidence, not as an inference", async () => {
    const { setup, env, session } = await audioFixture(() => ({ status: 401 }), { AI_PROVIDER_SECRET_1: "audio-secret-key" });
    await callAdmin(sessionRequest("/v1/admin/audio/mimo-tts/check", session, { method: "POST", body: {} }), env);

    const stored = await setup.d1
      .prepare("SELECT target_type, target_id, status, http_status FROM admin_connectivity_checks")
      .all();
    expect(stored.results).toEqual([
      expect.objectContaining({ target_type: "audio_provider", target_id: "mimo-tts", status: "auth_rejected", http_status: 401 })
    ]);

    const list = await callAdmin(sessionRequest("/v1/admin/audio", session), env);
    expect(list.status).toBe(200);
    expect(list.json.providers.find((row) => row.id === "mimo-tts").enabled).toBe(1);
    expect(list.json.lastChecks["mimo-tts"].status).toBe("auth_rejected");
    expect(list.json.lastChecks["mimo-tts"].authenticated).toBe(false);
    expect(JSON.stringify(list.json)).not.toContain("audio-secret-key");

    // A later successful check replaces the stored verdict.
    const { env: env2, session: session2 } = await audioFixture(() => ({ status: 200 }), { AI_PROVIDER_SECRET_1: "audio-secret-key" });
    await callAdmin(sessionRequest("/v1/admin/audio/mimo-tts", session2, {
      method: "PATCH",
      body: { model: "mimo-v2.5-tts", voice: "mimo_default", secretRef: "AI_PROVIDER_SECRET_1", enabled: true, priority: 10 }
    }), env2);
    await callAdmin(sessionRequest("/v1/admin/audio/mimo-tts/check", session2, { method: "POST", body: {} }), env2);
    const after = await callAdmin(sessionRequest("/v1/admin/audio", session2), env2);
    expect(after.json.lastChecks["mimo-tts"].status).toBe("ok");
    expect(after.json.lastChecks["mimo-tts"].authenticated).toBe(true);
  });

  it("shows the last check and a keyboard-operable check button on the page", async () => {
    const { setup, env, session } = await audioFixture(() => ({ status: 200 }), { AI_PROVIDER_SECRET_1: "audio-secret-key" });
    const before = await readPage(setup, env, session, "/admin/audio");
    expect(before.status).toBe(200);
    expect(before.text).toContain("Never checked.");
    expect(before.text).toContain('<form method="post" action="/admin/audio/mimo-tts/check">');
    expect(before.text).toContain("Check connectivity</button>");

    const posted = await callAdmin(
      sessionRequest("/v1/admin/audio/mimo-tts/check", session, { method: "POST", body: {} }),
      env
    );
    expect(posted.status).toBe(200);
    const after = await readPage(setup, env, session, "/admin/audio");
    expect(after.text).toContain("Last check:");
    expect(after.text).toContain("Reachable and authenticated.");
  });
});

describe("AI provider connectivity classification", () => {
  it("maps provider errors to reachable / authenticated / unreachable verdicts", () => {
    expect(classifyProviderFailure(new ProviderError("provider_error", 401))).toMatchObject({
      status: "auth_rejected",
      httpStatus: 401,
      reachable: true,
      authenticated: false
    });
    expect(classifyProviderFailure(new ProviderError("provider_error", 403))).toMatchObject({ status: "auth_rejected" });
    expect(classifyProviderFailure(new ProviderError("provider_timeout"))).toMatchObject({
      status: "unreachable",
      detail: "provider_timeout",
      reachable: false
    });
    expect(classifyProviderFailure(new ProviderError("provider_not_configured"))).toMatchObject({ status: "not_configured" });
    expect(classifyProviderFailure(new ProviderError("provider_error", 503))).toMatchObject({
      status: "upstream_error",
      httpStatus: 503,
      reachable: true
    });
    expect(classifyProviderFailure(new TypeError("fetch failed"))).toMatchObject({ status: "unreachable" });
  });

  it("refuses to call out when the secret slot is empty and reports it as not_configured", async () => {
    const setup = createSetup();
    await registerAdmin(setup);
    const env = adminEnv(setup, { AI_ALLOWED_HOSTS: "api.openai.com" });
    const session = await openSession(setup, env);

    const created = await callAdmin(
      sessionRequest("/v1/admin/providers", session, {
        method: "POST",
        body: {
          id: "primary-llm",
          adapterType: "openai-compatible",
          displayName: "Primary",
          baseUrl: "https://api.openai.com/v1",
          model: "gpt-5-mini",
          secretRef: "AI_PROVIDER_SECRET_1"
        }
      }),
      env
    );
    expect(created.status).toBe(201);

    const notConfigured = await callAdmin(
      sessionRequest("/v1/admin/providers/primary-llm/health-check", session, { method: "POST", body: {} }),
      env
    );
    expect(notConfigured.status).toBe(409);
    expect(notConfigured.json.status).toBe("not_configured");
    expect(notConfigured.json.healthy).toBe(false);
    expect(notConfigured.json.authenticated).toBe(false);

    const stored = await setup.d1.prepare("SELECT target_type, target_id, status FROM admin_connectivity_checks").all();
    expect(stored.results).toEqual([
      expect.objectContaining({ target_type: "ai_provider", target_id: "primary-llm", status: "not_configured" })
    ]);

    const missing = await callAdmin(
      sessionRequest("/v1/admin/providers/nope/health-check", session, { method: "POST", body: {} }),
      env
    );
    expect(missing.status).toBe(404);
  });

  it("reports a disabled provider as disabled instead of pretending it is healthy", async () => {
    const setup = createSetup();
    await registerAdmin(setup);
    const env = adminEnv(setup, { AI_ALLOWED_HOSTS: "api.openai.com", AI_PROVIDER_SECRET_1: "sk-live-1" });
    const session = await openSession(setup, env);
    await callAdmin(
      sessionRequest("/v1/admin/providers", session, {
        method: "POST",
        body: {
          id: "primary-llm",
          adapterType: "openai-compatible",
          displayName: "Primary",
          baseUrl: "https://api.openai.com/v1",
          model: "gpt-5-mini",
          secretRef: "AI_PROVIDER_SECRET_1",
          enabled: false
        }
      }),
      env
    );
    const response = await callAdmin(
      sessionRequest("/v1/admin/providers/primary-llm/health-check", session, { method: "POST", body: {} }),
      env
    );
    expect(response.status).toBe(409);
    expect(response.json.status).toBe("disabled");
  });

  it("surfaces a stored verdict on the providers API and page without the key", async () => {
    const setup = createSetup();
    await registerAdmin(setup);
    const env = adminEnv(setup, { AI_ALLOWED_HOSTS: "api.openai.com", AI_PROVIDER_SECRET_1: "sk-live-sentinel" });
    const session = await openSession(setup, env);
    await callAdmin(
      sessionRequest("/v1/admin/providers", session, {
        method: "POST",
        body: {
          id: "primary-llm",
          adapterType: "openai-compatible",
          displayName: "Primary",
          baseUrl: "https://api.openai.com/v1",
          model: "gpt-5-mini",
          secretRef: "AI_PROVIDER_SECRET_1"
        }
      }),
      env
    );
    await setup.d1
      .prepare("INSERT INTO admin_connectivity_checks (target_type, target_id, status, http_status, latency_ms, detail, checked_at) VALUES ('ai_provider', 'primary-llm', 'auth_rejected', 401, 42, NULL, '2026-01-01T00:00:00.000Z')")
      .run();

    const list = await callAdmin(sessionRequest("/v1/admin/providers", session), env);
    expect(list.status).toBe(200);
    const provider = list.json.providers.find((row) => row.id === "primary-llm");
    expect(provider.secretConfigured).toBe(true);
    expect(provider.lastCheck).toMatchObject({ status: "auth_rejected", httpStatus: 401, latencyMs: 42 });
    expect(JSON.stringify(list.json)).not.toContain("sk-live-sentinel");

    const page = await readPage(setup, env, session, "/admin/providers");
    expect(page.status).toBe(200);
    expect(page.text).toContain("Reachable, but the provider rejected the credential.");
    expect(page.text).toContain('<form method="post" action="/admin/providers/primary-llm/check">');
    expect(page.text).not.toContain("sk-live-sentinel");
  });
});

import { ProviderError, createProviderRegistry, type ProviderEnv } from "./provider";

interface Env extends ProviderEnv { GATEWAY_TOKEN: string }
type SummaryRequest = { title?: string; content?: string };
const DEFAULT_PROVIDER_ID = "openai-compatible";

export default {
  async fetch(request: Request, env: Env): Promise<Response> {
    const url = new URL(request.url);
    if (request.method === "GET" && url.pathname === "/health") return json({ ok: true });

    if (request.method === "GET" && url.pathname === "/v1/providers") {
      if (!authorized(request, env)) return json({ error: "unauthorized" }, 401);
      return json({ selected: env.AI_PROVIDER?.trim() || DEFAULT_PROVIDER_ID, providers: createProviderRegistry(env).list() });
    }

    if (request.method !== "POST" || url.pathname !== "/v1/summary") return json({ error: "not_found" }, 404);
    if (!authorized(request, env)) return json({ error: "unauthorized" }, 401);

    let body: SummaryRequest;
    try { body = await request.json<SummaryRequest>(); } catch { return json({ error: "invalid_json" }, 400); }
    const content = body.content?.trim();
    if (!content) return json({ error: "content_required" }, 400);
    if (content.length > 50000) return json({ error: "content_too_large" }, 413);

    try {
      const provider = createProviderRegistry(env).require(env.AI_PROVIDER?.trim() || DEFAULT_PROVIDER_ID);
      return json(await provider.summarize({ title: body.title, content }));
    } catch (error) {
      if (error instanceof ProviderError) {
        if (error.code === "invalid_provider_url" || error.code === "provider_not_configured") return json({ error: error.code }, 500);
        if (error.code === "provider_error") return json({ error: error.code, status: error.upstreamStatus }, 502);
        return json({ error: error.code }, 502);
      }
      return json({ error: "provider_error" }, 502);
    }
  }
};

function authorized(request: Request, env: Env): boolean {
  return request.headers.get("Authorization") === `Bearer ${env.GATEWAY_TOKEN}`;
}

function json(value: unknown, status = 200): Response {
  return new Response(JSON.stringify(value), { status, headers: { "Content-Type": "application/json; charset=utf-8", "Cache-Control": "no-store" } });
}

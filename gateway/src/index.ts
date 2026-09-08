import { ProviderError, createProviderRegistry } from "./provider";

interface Env {
  AI_API_KEY: string;
  GATEWAY_TOKEN: string;
  AI_BASE_URL: string;
  AI_MODEL: string;
}

type SummaryRequest = {
  title?: string;
  content?: string;
};

const DEFAULT_PROVIDER_ID = "openai-compatible";

export default {
  async fetch(request: Request, env: Env): Promise<Response> {
    const url = new URL(request.url);

    if (request.method === "GET" && url.pathname === "/health") {
      return json({ ok: true });
    }

    if (request.method !== "POST" || url.pathname !== "/v1/summary") {
      return json({ error: "not_found" }, 404);
    }

    const auth = request.headers.get("Authorization");
    if (!auth || auth !== `Bearer ${env.GATEWAY_TOKEN}`) {
      return json({ error: "unauthorized" }, 401);
    }

    let body: SummaryRequest;
    try {
      body = await request.json<SummaryRequest>();
    } catch {
      return json({ error: "invalid_json" }, 400);
    }

    const content = body.content?.trim();
    if (!content) return json({ error: "content_required" }, 400);
    if (content.length > 50000) return json({ error: "content_too_large" }, 413);

    const provider = createProviderRegistry(env).require(DEFAULT_PROVIDER_ID);
    try {
      const result = await provider.summarize({ title: body.title, content });
      return json(result);
    } catch (error) {
      if (error instanceof ProviderError) {
        if (error.code === "invalid_provider_url") {
          return json({ error: error.code }, 500);
        }
        if (error.code === "provider_error") {
          return json({ error: error.code, status: error.upstreamStatus }, 502);
        }
        return json({ error: error.code }, 502);
      }
      return json({ error: "provider_error" }, 502);
    }
  }
};

function json(value: unknown, status = 200): Response {
  return new Response(JSON.stringify(value), {
    status,
    headers: {
      "Content-Type": "application/json; charset=utf-8",
      "Cache-Control": "no-store"
    }
  });
}

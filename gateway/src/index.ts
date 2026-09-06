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

    const base = env.AI_BASE_URL.replace(/\/$/, "");
    if (!base.startsWith("https://")) {
      return json({ error: "invalid_provider_url" }, 500);
    }

    const upstream = await fetch(`${base}/chat/completions`, {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        "Authorization": `Bearer ${env.AI_API_KEY}`
      },
      body: JSON.stringify({
        model: env.AI_MODEL,
        temperature: 0.2,
        messages: [
          {
            role: "system",
            content: "Summarize RSS or podcast content for a Wear OS display. Be concise, factual, and preserve important names, numbers, and dates."
          },
          {
            role: "user",
            content: `${body.title ? `Title: ${body.title}\n\n` : ""}${content}`
          }
        ]
      })
    });

    if (!upstream.ok) {
      return json({ error: "provider_error", status: upstream.status }, 502);
    }

    const data = await upstream.json<any>();
    const summary = data?.choices?.[0]?.message?.content;
    if (typeof summary !== "string" || !summary.trim()) {
      return json({ error: "invalid_provider_response" }, 502);
    }

    return json({ summary: summary.trim(), model: env.AI_MODEL });
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

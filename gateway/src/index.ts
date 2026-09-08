import {
  ProviderError,
  createProviderRegistry,
  isRetryableProviderError,
  type AiProviderRegistry,
  type ProviderEnv,
  type SummaryInput,
  type SummaryOutput
} from "./provider";
import {
  buildSummaryCacheKey,
  createSummaryCache,
  summarizeWithCache,
  summaryCacheTtlSeconds,
  type SummaryCacheEnv
} from "./summaryCache";

interface Env extends ProviderEnv, SummaryCacheEnv { GATEWAY_TOKEN: string }
type SummaryRequest = { title?: string; content?: string };
const DEFAULT_PROVIDER_ID = "openai-compatible";
const MAX_REQUEST_BYTES = 64 * 1024;
const MAX_CONTENT_CHARS = 50_000;

export default {
  async fetch(request: Request, env: Env): Promise<Response> {
    const requestId = crypto.randomUUID();
    const respond = (value: unknown, status = 200) => json(value, status, requestId);
    const url = new URL(request.url);

    if (request.method === "GET" && url.pathname === "/health") return respond({ ok: true });

    if (request.method === "GET" && url.pathname === "/v1/providers") {
      if (!authorized(request, env)) return respond({ error: "unauthorized" }, 401);
      try {
        return respond({
          selected: env.AI_PROVIDER?.trim() || DEFAULT_PROVIDER_ID,
          providers: createProviderRegistry(env).list()
        });
      } catch (error) {
        return providerFailure(error, respond);
      }
    }

    if (request.method !== "POST" || url.pathname !== "/v1/summary") return respond({ error: "not_found" }, 404);
    if (!authorized(request, env)) return respond({ error: "unauthorized" }, 401);

    const parsedBody = await readJsonBodyLimited<SummaryRequest>(request, MAX_REQUEST_BYTES);
    if (parsedBody.error) return respond({ error: parsedBody.error }, parsedBody.error === "request_too_large" ? 413 : 400);

    const body = parsedBody.value;
    const content = body.content?.trim();
    if (!content) return respond({ error: "content_required" }, 400);
    if (content.length > MAX_CONTENT_CHARS) return respond({ error: "content_too_large" }, 413);

    try {
      const registry = createProviderRegistry(env);
      const primaryId = env.AI_PROVIDER?.trim() || DEFAULT_PROVIDER_ID;
      const input = { title: body.title, content };
      const cache = createSummaryCache(env);
      const cacheKey = await buildSummaryCacheKey({
        title: input.title,
        content: input.content,
        language: "auto"
      });
      const output = await summarizeWithCache(cache, cacheKey, summaryCacheTtlSeconds(env), () =>
        summarizeWithFallback(registry, primaryId, env.AI_FALLBACK_PROVIDER?.trim(), input)
      );
      return respond(output);
    } catch (error) {
      return providerFailure(error, respond);
    }
  }
};

async function summarizeWithFallback(
  registry: AiProviderRegistry,
  primaryId: string,
  fallbackId: string | undefined,
  input: SummaryInput
): Promise<SummaryOutput> {
  try {
    return await registry.require(primaryId).summarize(input);
  } catch (error) {
    if (!fallbackId || fallbackId === primaryId || !isRetryableProviderError(error)) throw error;
    return registry.require(fallbackId).summarize(input);
  }
}

async function readJsonBodyLimited<T>(
  request: Request,
  maxBytes: number
): Promise<{ value: T; error?: undefined } | { value?: undefined; error: "invalid_json" | "request_too_large" }> {
  const declaredLength = Number(request.headers.get("Content-Length"));
  if (Number.isFinite(declaredLength) && declaredLength > maxBytes) {
    return { error: "request_too_large" };
  }

  if (!request.body) return { error: "invalid_json" };
  const reader = request.body.getReader();
  const chunks: Uint8Array[] = [];
  let total = 0;

  try {
    while (true) {
      const { done, value } = await reader.read();
      if (done) break;
      total += value.byteLength;
      if (total > maxBytes) {
        await reader.cancel();
        return { error: "request_too_large" };
      }
      chunks.push(value);
    }
  } catch {
    return { error: "invalid_json" };
  }

  const bytes = new Uint8Array(total);
  let offset = 0;
  for (const chunk of chunks) {
    bytes.set(chunk, offset);
    offset += chunk.byteLength;
  }

  try {
    return { value: JSON.parse(new TextDecoder().decode(bytes)) as T };
  } catch {
    return { error: "invalid_json" };
  }
}

function providerFailure(
  error: unknown,
  respond: (value: unknown, status?: number) => Response
): Response {
  if (error instanceof ProviderError) {
    if (error.code === "invalid_provider_url" || error.code === "provider_not_configured") {
      return respond({ error: error.code }, 500);
    }
    if (error.code === "provider_timeout") return respond({ error: error.code }, 504);
    if (error.code === "provider_error") return respond({ error: error.code, status: error.upstreamStatus }, 502);
    return respond({ error: error.code }, 502);
  }
  return respond({ error: "provider_error" }, 502);
}

function authorized(request: Request, env: Env): boolean {
  return request.headers.get("Authorization") === `Bearer ${env.GATEWAY_TOKEN}`;
}

function json(value: unknown, status: number, requestId: string): Response {
  return new Response(JSON.stringify(value), {
    status,
    headers: {
      "Content-Type": "application/json; charset=utf-8",
      "Cache-Control": "no-store",
      "X-Request-ID": requestId
    }
  });
}

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
  BYOK_PROVIDER_IDS,
  ByokConfigError,
  createByokProvider
} from "./byok";
import {
  buildSummaryCacheKey,
  createSummaryCache,
  summarizeWithCache,
  summaryCacheTtlSeconds,
  type SummaryCacheEnv
} from "./summaryCache";
import {
  authenticateGatewayUser,
  createMembershipService,
  type MembershipEnv
} from "./membership";
import {
  processPlayRtdn,
  restorePlayPurchase,
  type BillingResult,
  type BillingServerEnv
} from "./billingServer";
import {
  exchangeGoogleIdToken,
  type AuthResult,
  type AuthServerEnv
} from "./authServer";
import { AccountSessionService } from "./accountSession";
import { createConfiguredD1AccountStores } from "./d1AccountStore";
import { authenticateRequestUser } from "./requestAuth";

interface Env extends ProviderEnv, SummaryCacheEnv, MembershipEnv, BillingServerEnv, AuthServerEnv {}
type SummaryRequest = { title?: string; content?: string };
type ByokSummaryRequest = SummaryRequest & { provider?: string; model?: string };
const DEFAULT_PROVIDER_ID = "openai-compatible";
const MAX_REQUEST_BYTES = 64 * 1024;
const MAX_BILLING_REQUEST_BYTES = 16 * 1024;
const MAX_AUTH_REQUEST_BYTES = 20 * 1024;
const MAX_CONTENT_CHARS = 50_000;
const BYOK_API_KEY_HEADER = "X-WristBrief-BYOK-Key";

export default {
  async fetch(request: Request, env: Env): Promise<Response> {
    const requestId = crypto.randomUUID();
    const respond = (value: unknown, status = 200) => json(value, status, requestId);
    const respondBilling = (result: BillingResult) =>
      result.status === 204 ? empty(204, requestId) : respond(result.body, result.status);
    const respondAuth = (result: AuthResult) => respond(result.body, result.status);
    const url = new URL(request.url);

    if (request.method === "GET" && url.pathname === "/health") return respond({ ok: true });

    if (request.method === "POST" && url.pathname === "/v1/auth/google") {
      const parsedBody = await readJsonBodyLimited<unknown>(request, MAX_AUTH_REQUEST_BYTES);
      if (parsedBody.error) {
        return respond(
          { error: parsedBody.error },
          parsedBody.error === "request_too_large" ? 413 : 400
        );
      }
      const linkLegacy = requestsLegacyLink(parsedBody.value);
      const legacyUser = linkLegacy ? authenticateGatewayUser(request, env) : null;
      if (linkLegacy && !legacyUser) return respond({ error: "legacy_auth_required" }, 401);
      try {
        return respondAuth(await exchangeGoogleIdToken(
          parsedBody.value,
          env,
          legacyUser ? { existingUserId: legacyUser.id } : {}
        ));
      } catch {
        return respond({ error: "auth_unavailable" }, 503);
      }
    }

    if (request.method === "POST" && url.pathname === "/v1/auth/logout") {
      const sessionStore = env.ACCOUNT_SESSION_STORE ?? createConfiguredD1AccountStores(env)?.sessionStore;
      if (!sessionStore) return respond({ error: "auth_not_configured" }, 503);
      try {
        const revoked = await new AccountSessionService(sessionStore)
          .revokeAuthorizationHeader(request.headers.get("Authorization"));
        return revoked ? empty(204, requestId) : respond({ error: "unauthorized" }, 401);
      } catch {
        return respond({ error: "auth_unavailable" }, 503);
      }
    }

    const user = await authenticateRequestUser(request, env);

    if (request.method === "POST" && url.pathname === "/v1/billing/rtdn") {
      try {
        return respondBilling(await processPlayRtdn(request, env));
      } catch {
        return respond({ error: "billing_unavailable" }, 503);
      }
    }

    if (request.method === "POST" && url.pathname === "/v1/billing/restore") {
      if (!user) return respond({ error: "unauthorized" }, 401);
      const parsedBody = await readJsonBodyLimited<unknown>(request, MAX_BILLING_REQUEST_BYTES);
      if (parsedBody.error) {
        return respond(
          { error: parsedBody.error },
          parsedBody.error === "request_too_large" ? 413 : 400
        );
      }
      try {
        return respondBilling(await restorePlayPurchase(user, parsedBody.value, env));
      } catch {
        return respond({ error: "billing_unavailable" }, 503);
      }
    }

    if (request.method === "GET" && url.pathname === "/v1/me") {
      if (!user) return respond({ error: "unauthorized" }, 401);
      try {
        return respond(await createMembershipService(env).snapshot(user));
      } catch {
        return respond({ error: "membership_unavailable" }, 503);
      }
    }

    if (request.method === "GET" && url.pathname === "/v1/providers") {
      if (!user) return respond({ error: "unauthorized" }, 401);
      try {
        return respond({
          selected: env.AI_PROVIDER?.trim() || DEFAULT_PROVIDER_ID,
          providers: createProviderRegistry(env).list(),
          byokProviders: BYOK_PROVIDER_IDS
        });
      } catch (error) {
        return providerFailure(error, respond);
      }
    }

    if (request.method === "POST" && url.pathname === "/v1/byok/summary") {
      if (!user) return respond({ error: "unauthorized" }, 401);

      const parsedBody = await readJsonBodyLimited<ByokSummaryRequest>(request, MAX_REQUEST_BYTES);
      if (parsedBody.error) {
        return respond(
          { error: parsedBody.error },
          parsedBody.error === "request_too_large" ? 413 : 400
        );
      }

      const body = parsedBody.value;
      const content = body.content?.trim();
      if (!content) return respond({ error: "content_required" }, 400);
      if (content.length > MAX_CONTENT_CHARS) return respond({ error: "content_too_large" }, 413);

      const apiKey = request.headers.get(BYOK_API_KEY_HEADER) ?? undefined;
      let provider;
      try {
        provider = createByokProvider(
          { provider: body.provider, model: body.model, apiKey },
          env
        );
      } catch (error) {
        if (error instanceof ByokConfigError) return respond({ error: error.code }, 400);
        return providerFailure(error, respond);
      }

      const memberships = createMembershipService(env);
      try {
        const access = await memberships.canUseAi(user.id, "byok");
        if (!access.allowed) return respond({ error: "quota_exceeded", quota: access.quota }, 429);
        const output = await provider.summarize({ title: body.title, content });
        await memberships.recordAiUsage(user.id, "byok");
        return respond(output);
      } catch (error) {
        if (error instanceof ProviderError) return byokProviderFailure(error, respond);
        return respond({ error: "membership_unavailable" }, 503);
      }
    }

    if (request.method !== "POST" || url.pathname !== "/v1/summary") return respond({ error: "not_found" }, 404);
    if (!user) return respond({ error: "unauthorized" }, 401);

    const memberships = createMembershipService(env);
    try {
      const access = await memberships.canUseAi(user.id, "managed");
      if (!access.allowed) return respond({ error: "quota_exceeded", quota: access.quota }, 429);
    } catch {
      return respond({ error: "membership_unavailable" }, 503);
    }

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
      await memberships.recordAiUsage(user.id, "managed");
      return respond(output);
    } catch (error) {
      if (error instanceof ProviderError) return providerFailure(error, respond);
      return respond({ error: "membership_unavailable" }, 503);
    }
  }
};

function requestsLegacyLink(value: unknown): boolean {
  return Boolean(value && typeof value === "object" && (value as Record<string, unknown>).linkLegacy === true);
}

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

function byokProviderFailure(
  error: ProviderError,
  respond: (value: unknown, status?: number) => Response
): Response {
  if (error.code === "provider_error" && (error.upstreamStatus === 401 || error.upstreamStatus === 403)) {
    return respond({ error: "byok_provider_auth_failed" }, 422);
  }
  return providerFailure(error, respond);
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

function empty(status: number, requestId: string): Response {
  return new Response(null, {
    status,
    headers: {
      "Cache-Control": "no-store",
      "X-Request-ID": requestId
    }
  });
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

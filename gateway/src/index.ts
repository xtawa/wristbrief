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
  summarizeWithCacheAndLock,
  summaryCacheTtlSeconds,
  type SummaryCacheEnv
} from "./summaryCache";
import {
  authenticateActiveGatewayUser,
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
  deleteAccount,
  exchangeGoogleIdToken,
  issueLegacyMigrationGrant,
  type AuthResult,
  type AuthServerEnv
} from "./authServer";
import { AccountSessionService } from "./accountSession";
import { createConfiguredD1AccountStores } from "./d1AccountStore";
import { authenticateRequestUser } from "./requestAuth";
import { handleSyncPush, handleSyncPull, type SyncRouteEnv } from "./sync/syncRoutes";
import { handleContentResolve, handleContentGet, type ContentRouteEnv } from "./content/contentRoutes";
import {
  handleTranscriptRequest,
  handleTranscriptGet,
  handleTranscriptStatus,
  type TranscriptRouteEnv
} from "./artifacts/transcriptRoutes";
import {
  handleEmailAuthRoute,
  handleEmailVerifyConfirm,
  handleLinkEmailIdentity,
  handleLinkGoogleIdentity,
  handleListIdentities,
  handleUnlinkIdentity,
  MAX_EMAIL_BODY_BYTES,
  type EmailAuthEnv
} from "./emailAuth/emailAuthRoutes";
import { handleAdminRoute, type AdminRoutesEnv } from "./admin/adminRoutes";

interface Env
  extends ProviderEnv,
    SummaryCacheEnv,
    MembershipEnv,
    BillingServerEnv,
    AuthServerEnv,
    SyncRouteEnv,
    ContentRouteEnv,
    TranscriptRouteEnv,
    EmailAuthEnv,
    AdminRoutesEnv {}
type SummaryRequest = { title?: string; content?: string };
const DEFAULT_PROVIDER_ID = "openai-compatible";
const MAX_REQUEST_BYTES = 64 * 1024;
const MAX_BILLING_REQUEST_BYTES = 16 * 1024;
const MAX_AUTH_REQUEST_BYTES = 20 * 1024;
const MAX_CONTENT_CHARS = 50_000;

export default {
  async fetch(request: Request, env: Env): Promise<Response> {
    const requestId = crypto.randomUUID();
    const respond = (value: unknown, status = 200) => json(value, status, requestId);
    const respondBilling = (result: BillingResult) =>
      result.status === 204 ? empty(204, requestId) : respond(result.body, result.status);
    const respondAuth = (result: AuthResult) => respond(result.body, result.status);
    const url = new URL(request.url);

    if (request.method === "GET" && url.pathname === "/health") return respond({ ok: true });

    if (request.method === "POST" && url.pathname === "/v1/auth/legacy-migration-grant") {
      const legacyUser = await authenticateActiveGatewayUser(request, env);
      if (!legacyUser) return respond({ error: "legacy_auth_required" }, 401);
      try {
        return respondAuth(await issueLegacyMigrationGrant(legacyUser.id, env));
      } catch {
        return respond({ error: "auth_unavailable" }, 503);
      }
    }

    if (request.method === "POST" && url.pathname === "/v1/auth/google") {
      const parsedBody = await readJsonBodyLimited<unknown>(request, MAX_AUTH_REQUEST_BYTES);
      if (parsedBody.error) {
        return respond(
          { error: parsedBody.error },
          parsedBody.error === "request_too_large" ? 413 : 400
        );
      }
      const linkLegacy = requestsLegacyLink(parsedBody.value);
      const legacyUser = linkLegacy ? await authenticateActiveGatewayUser(request, env) : null;
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

    // Email/password authentication. These routes apply their own rate limits
    // (route x IP prefix x normalized-email hash) inside the handlers.
    if (request.method === "POST" && (url.pathname === "/v1/auth/email/register" || url.pathname === "/v1/auth/email/login" || url.pathname === "/v1/auth/email/verify/request" || url.pathname === "/v1/auth/email/verify/confirm" || url.pathname === "/v1/auth/email/password/forgot" || url.pathname === "/v1/auth/email/password/reset")) {
      const parsedBody = await readJsonBodyLimited<unknown>(request, MAX_EMAIL_BODY_BYTES);
      if (parsedBody.error) {
        return respond({ error: parsedBody.error }, parsedBody.error === "request_too_large" ? 413 : 400);
      }
      const auth = await handleEmailAuthRoute(url.pathname, request, env, parsedBody.value);
      return respondAuth(auth);
    }

    // Email verification links arrive as GET (from the email body).
    if (request.method === "GET" && url.pathname === "/v1/auth/email/verify/confirm") {
      return respondAuth(await handleEmailVerifyConfirm(request, env, null, url));
    }

    // Server-rendered /admin pages and /v1/admin API. Self-contained
    // authorization (admin cookie session + CSRF); returns null for other paths.
    const adminResponse = await handleAdminRoute(request, env, requestId);
    if (adminResponse) return adminResponse;

    const user = await authenticateRequestUser(request, env);

    // Identity linking: explicit, session-authenticated, and never auto-merged
    // by matching email addresses between Google and email credentials.
    if (request.method === "GET" && url.pathname === "/v1/account/identities") {
      if (!user) return respond({ error: "unauthorized" }, 401);
      return respondAuth(await handleListIdentities(user.id, env));
    }

    if (request.method === "POST" && url.pathname === "/v1/account/identities/email") {
      if (!user) return respond({ error: "unauthorized" }, 401);
      const parsedBody = await readJsonBodyLimited<unknown>(request, MAX_EMAIL_BODY_BYTES);
      if (parsedBody.error) {
        return respond({ error: parsedBody.error }, parsedBody.error === "request_too_large" ? 413 : 400);
      }
      return respondAuth(await handleLinkEmailIdentity(user.id, env, parsedBody.value));
    }

    if (request.method === "POST" && url.pathname === "/v1/account/identities/google") {
      if (!user) return respond({ error: "unauthorized" }, 401);
      const parsedBody = await readJsonBodyLimited<unknown>(request, MAX_EMAIL_BODY_BYTES);
      if (parsedBody.error) {
        return respond({ error: parsedBody.error }, parsedBody.error === "request_too_large" ? 413 : 400);
      }
      return respondAuth(await handleLinkGoogleIdentity(user.id, env, parsedBody.value));
    }

    if (request.method === "DELETE" && url.pathname.startsWith("/v1/account/identities/")) {
      if (!user) return respond({ error: "unauthorized" }, 401);
      const rest = url.pathname.slice("/v1/account/identities/".length);
      const separator = rest.indexOf("/");
      if (separator <= 0) return respond({ error: "not_found" }, 404);
      const provider = rest.slice(0, separator);
      const subject = decodeURIComponent(rest.slice(separator + 1));
      return respondAuth(await handleUnlinkIdentity(user.id, env, provider, subject));
    }

    if (
      (request.method === "POST" || request.method === "DELETE") &&
      (url.pathname === "/v1/auth/delete" || url.pathname === "/v1/account/delete")
    ) {
      if (!user) return respond({ error: "unauthorized" }, 401);
      try {
        const res = await deleteAccount(user, env);
        if (res.status === 204) return empty(204, requestId);
        return respond(res.body, res.status);
      } catch {
        return respond({ error: "auth_unavailable" }, 503);
      }
    }

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
          providers: createProviderRegistry(env).list()
        });
      } catch (error) {
        return providerFailure(error, respond);
      }
    }

    if (request.method === "POST" && url.pathname === "/v1/content/resolve") {
      const parsedBody = await readJsonBodyLimited<any>(request, MAX_REQUEST_BYTES);
      if (parsedBody.error) return respond({ error: parsedBody.error }, parsedBody.error === "request_too_large" ? 413 : 400);
      try {
        const res = await handleContentResolve(request, env, parsedBody.value);
        return respond(res.body, res.status);
      } catch {
        return respond({ error: "content_unavailable" }, 503);
      }
    }

    if (request.method === "GET" && url.pathname.startsWith("/v1/content/")) {
      const contentCode = url.pathname.slice("/v1/content/".length);
      try {
        const res = await handleContentGet(contentCode, env);
        return respond(res.body, res.status);
      } catch {
        return respond({ error: "content_unavailable" }, 503);
      }
    }

    if (request.method === "POST" && url.pathname === "/v1/sync/push") {
      if (!user) return respond({ error: "unauthorized" }, 401);
      const parsedBody = await readJsonBodyLimited<any>(request, MAX_REQUEST_BYTES);
      if (parsedBody.error) return respond({ error: parsedBody.error }, parsedBody.error === "request_too_large" ? 413 : 400);
      try {
        const res = await handleSyncPush(request, env, user.id, parsedBody.value);
        return respond(res.body, res.status);
      } catch {
        return respond({ error: "sync_unavailable" }, 503);
      }
    }

    if (request.method === "GET" && url.pathname === "/v1/sync/pull") {
      if (!user) return respond({ error: "unauthorized" }, 401);
      try {
        const res = await handleSyncPull(request, env, user.id);
        return respond(res.body, res.status);
      } catch {
        return respond({ error: "sync_unavailable" }, 503);
      }
    }

    if (request.method === "POST" && url.pathname === "/v1/transcripts/request") {
      if (!user) return respond({ error: "unauthorized" }, 401);
      const parsedBody = await readJsonBodyLimited<any>(request, MAX_REQUEST_BYTES);
      if (parsedBody.error) return respond({ error: parsedBody.error }, parsedBody.error === "request_too_large" ? 413 : 400);
      try {
        const res = await handleTranscriptRequest(request, env, user.id, parsedBody.value);
        return respond(res.body, res.status);
      } catch {
        return respond({ error: "transcripts_unavailable" }, 503);
      }
    }

    if (request.method === "GET" && url.pathname.startsWith("/v1/transcripts/jobs/")) {
      if (!user) return respond({ error: "unauthorized" }, 401);
      const jobId = url.pathname.slice("/v1/transcripts/jobs/".length);
      try {
        const res = await handleTranscriptStatus(jobId, env, user.id);
        return respond(res.body, res.status);
      } catch {
        return respond({ error: "transcripts_unavailable" }, 503);
      }
    }

    if (request.method === "GET" && url.pathname.startsWith("/v1/transcripts/")) {
      if (!user) return respond({ error: "unauthorized" }, 401);
      const contentCode = url.pathname.slice("/v1/transcripts/".length);
      try {
        const res = await handleTranscriptGet(contentCode, env, user.id);
        return respond(res.body, res.status);
      } catch {
        return respond({ error: "transcripts_unavailable" }, 503);
      }
    }

    if (request.method !== "POST" || url.pathname !== "/v1/summary") return respond({ error: "not_found" }, 404);
    if (!user) return respond({ error: "unauthorized" }, 401);

    const parsedBody = await readJsonBodyLimited<SummaryRequest>(request, MAX_REQUEST_BYTES);
    if (parsedBody.error) return respond({ error: parsedBody.error }, parsedBody.error === "request_too_large" ? 413 : 400);

    const body = parsedBody.value;
    const content = body.content?.trim();
    if (!content) return respond({ error: "content_required" }, 400);
    if (content.length > MAX_CONTENT_CHARS) return respond({ error: "content_too_large" }, 413);

    let registry: AiProviderRegistry;
    let primaryId: string;
    let model: string;
    try {
      registry = createProviderRegistry(env);
      primaryId = env.AI_PROVIDER?.trim() || DEFAULT_PROVIDER_ID;
      model = registry.require(primaryId).metadata.model;
    } catch (error) {
      if (error instanceof ProviderError) return providerFailure(error, respond);
      return respond({ error: "provider_error" }, 502);
    }

    const memberships = createMembershipService(env);
    const input = { title: body.title, content };
    const cache = createSummaryCache(env);
    const cacheKey = await buildSummaryCacheKey({
      provider: primaryId,
      model,
      title: input.title,
      content: input.content,
      language: "auto"
    });

    if (cache) {
      const cached = await cache.get(cacheKey);
      if (cached) {
        return respond(cached);
      }
    }

    let quotaReserved = false;
    try {
      const reservation = await memberships.reserveAiQuota(user.id);
      if (!reservation.allowed) {
        return respond({ error: "quota_exceeded", quota: reservation.quota }, 429);
      }
      quotaReserved = true;
    } catch {
      return respond({ error: "membership_unavailable" }, 503);
    }

    try {
      const result = await summarizeWithCacheAndLock(cache, cacheKey, summaryCacheTtlSeconds(env), () =>
        summarizeWithFallback(registry, primaryId, env.AI_FALLBACK_PROVIDER?.trim(), input)
      );

      if (result.cached) {
        await memberships.releaseAiQuota(user.id);
      } else {
        await memberships.commitAiQuota(user.id);
      }

      return respond(result.output);
    } catch (error) {
      if (quotaReserved) {
        try {
          await memberships.releaseAiQuota(user.id);
        } catch {
          // ignore cleanup errors so primary error is returned
        }
      }
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

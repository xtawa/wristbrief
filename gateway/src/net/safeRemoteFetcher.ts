import { lookup as lookupHost } from "node:dns/promises";
import * as http from "node:http";
import * as https from "node:https";
import { isIP, type LookupFunction } from "node:net";
import { Readable } from "node:stream";
import { createBrotliDecompress, createGunzip, createUnzip } from "node:zlib";
import { checkRemoteAddress, checkRemoteHost } from "./ipPolicy";
import { Semaphore } from "./semaphore";

export type SafeFetchErrorCode =
  | "blocked_scheme"
  | "blocked_host"
  | "credentials_in_url"
  | "invalid_url"
  | "redirect_blocked"
  | "too_many_redirects"
  | "too_large"
  | "timeout"
  | "http_error"
  | "network_error";

export type SafeFetchOptions = {
  /** Hard cap on the decoded response body; enforced while streaming. */
  maxBytes: number;
  /** ONE deadline covering DNS + connect + headers + the entire body read. */
  timeoutMs: number;
  /** Max time without any response progress (default min(30s, timeoutMs)). */
  idleTimeoutMs?: number;
  maxRedirects?: number;
  allowedContentTypes?: RegExp[];
  /** Test seam: replaces the DNS resolver used for every hop. */
  resolver?: AddressResolver;
  /** Test seam: replaces the HTTP transport used for every hop. */
  transport?: SafeHttpTransport;
};

export type SafeFetchResult =
  | { ok: true; contentType: string; bytes: Uint8Array }
  | { ok: false; error: SafeFetchErrorCode; status?: number };

/** Resolves a hostname to the addresses a connection could be opened to. */
export type AddressResolver = (hostname: string) => Promise<string[]>;

/** One request for one hop; `address` is already validated by the policy. */
export type SafeHttpRequest = {
  url: URL;
  /** Exact `Host` header value (hostname plus a non-default port). */
  hostHeader: string;
  /** Bare hostname, used for TLS SNI and certificate validation. */
  hostname: string;
  /** The validated public IP the socket MUST be opened to. */
  address: string;
  family: 4 | 6;
  signal: AbortSignal;
  /** Defaults to GET. */
  method?: string;
  /** Extra request headers; the pinned `Host` cannot be overridden by these. */
  headers?: Record<string, string>;
  body?: string | Uint8Array;
};

export type SafeHttpResponse = {
  status: number;
  headers: { get(name: string): string | null };
  body: AsyncIterable<Uint8Array>;
  /** Releases the underlying socket; safe to call more than once. */
  destroy(): void;
};

export type SafeHttpTransport = {
  request(request: SafeHttpRequest): Promise<SafeHttpResponse>;
};

const DEFAULT_MAX_REDIRECTS = 5;
const DEFAULT_MAX_CONCURRENCY = 8;
const DEFAULT_IDLE_TIMEOUT_MS = 30_000;
const USER_AGENT = "WristBriefGateway/1.0";

/** Thrown only by the deadline/idle timer; mapped to the `timeout` result. */
class FetchTimeoutError extends Error {}

type ReadResult = { ok: true; bytes: Uint8Array } | { ok: false; error: "too_large" | "network_error" };

type HopPlan = {
  transport: SafeHttpTransport;
  deadlineAt: number;
  idleTimeoutMs: number;
  maxBytes: number;
  allowedContentTypes?: RegExp[];
  method?: string;
  headers?: Record<string, string>;
  body?: string | Uint8Array;
};

type HopOutcome =
  | { kind: "redirect"; location: string | null; status: number }
  | { kind: "response"; status: number; contentType: string; bytes: Uint8Array }
  | { kind: "error"; error: SafeFetchErrorCode; status?: number };

/**
 * The single SSRF-safe remote fetch used by OPML URL preview, article
 * extraction, the image proxy, and podcast audio download:
 *
 *  - http/https only, no credentials in the URL;
 *  - every hop (the request itself AND every redirect target) is resolved with
 *    DNS, EVERY returned address is screened by ipPolicy.checkRemoteAddress, and
 *    the connection is pinned to the screened address through
 *    `http(s).request({ lookup })`. The validated address and the connected
 *    address therefore cannot diverge, which closes DNS rebinding (a second
 *    resolution returning a private address is never consulted — the pinned
 *    lookup ignores the hostname entirely);
 *  - a mixed answer set containing any blocked address refuses the hostname
 *    outright, because a public/private mix is the classic rebinding shape;
 *  - the `Host` header and the TLS SNI/certificate name stay the original
 *    hostname, so HTTPS still validates the real certificate;
 *  - redirects are never followed automatically (`redirect: manual`); each
 *    Location is resolved and re-validated, bounded by maxRedirects;
 *  - ONE deadline covers DNS + connect + headers + the whole streamed body, with
 *    an additional idle/stall timeout, so a server that sends headers and then
 *    dribbles the body cannot hang the caller;
 *  - bodies are capped while streaming (never buffered first and checked after);
 *  - a bounded semaphore caps simultaneous outbound fetches.
 */
export async function safeFetch(rawUrl: string, options: SafeFetchOptions): Promise<SafeFetchResult> {
  return fetchSemaphore.run(() => safeFetchWithinLimit(rawUrl, options));
}

/* ------------------------------------------------------------------ */
/* Concurrency limit                                                   */
/* ------------------------------------------------------------------ */

function configuredConcurrency(): number {
  const raw = typeof process !== "undefined" ? process.env?.WRISTBRIEF_FETCH_CONCURRENCY : undefined;
  const parsed = Number(raw);
  return Number.isFinite(parsed) && parsed >= 1 ? Math.floor(parsed) : DEFAULT_MAX_CONCURRENCY;
}

const fetchSemaphore = new Semaphore(configuredConcurrency());

/** Changes the outbound fetch bound; in-flight requests are not interrupted. */
export function configureFetchConcurrency(limit: number): void {
  fetchSemaphore.setLimit(limit);
}

export function fetchConcurrencyState(): { limit: number; active: number; queued: number } {
  return { limit: fetchSemaphore.limit, active: fetchSemaphore.active, queued: fetchSemaphore.queued };
}

/* ------------------------------------------------------------------ */
/* Transport seam                                                      */
/* ------------------------------------------------------------------ */

let transportOverride: SafeHttpTransport | null = null;
let resolverOverride: AddressResolver | null = null;

/**
 * Replaces the transport used when `SafeFetchOptions.transport` is omitted.
 * Tests use this to drive routes that call safeFetch without an options bag;
 * production code never sets it (the node transport is the real one, and it is
 * the only transport that pins the connection to the validated address).
 */
export function setSafeFetchTransportOverride(transport: SafeHttpTransport | null): void {
  transportOverride = transport;
}

/**
 * Replaces the resolver used when `SafeFetchOptions.resolver` is omitted, so a
 * test double can drive safeFetch without touching real DNS.
 */
export function setSafeFetchResolverOverride(resolver: AddressResolver | null): void {
  resolverOverride = resolver;
}

/* ------------------------------------------------------------------ */
/* Policy                                                              */
/* ------------------------------------------------------------------ */

function stripBrackets(hostname: string): string {
  return hostname.startsWith("[") && hostname.endsWith("]") ? hostname.slice(1, -1) : hostname;
}

function validateFetchUrl(rawUrl: string): { ok: true } | { ok: false; error: SafeFetchErrorCode } {
  let parsed: URL;
  try {
    parsed = new URL(rawUrl);
  } catch {
    return { ok: false, error: "invalid_url" };
  }
  if (parsed.protocol !== "http:" && parsed.protocol !== "https:") return { ok: false, error: "blocked_scheme" };
  if (parsed.username || parsed.password) return { ok: false, error: "credentials_in_url" };
  if (!parsed.hostname) return { ok: false, error: "invalid_url" };
  const hostVerdict = checkRemoteHost(stripBrackets(parsed.hostname));
  if (!hostVerdict.allowed) return { ok: false, error: "blocked_host" };
  return { ok: true };
}

const defaultResolver: AddressResolver = async (hostname) => {
  // `dns.lookup` (getaddrinfo) is what a default Node connection would use, so
  // screening its answers is exactly the address set the socket could reach.
  const addresses = await lookupHost(hostname, { all: true, verbatim: true });
  return addresses.map((entry) => entry.address);
};

type PinnedAddress = { address: string; family: 4 | 6 };

async function pinHostAddress(
  hostname: string,
  resolver: AddressResolver
): Promise<{ ok: true; pinned: PinnedAddress } | { ok: false; reason: "blocked" | "unresolved" }> {
  const literalFamily = isIP(hostname);
  let addresses: string[];
  if (literalFamily !== 0) {
    addresses = [hostname];
  } else {
    try {
      addresses = await resolver(hostname);
    } catch {
      return { ok: false, reason: "unresolved" };
    }
  }
  if (!Array.isArray(addresses) || addresses.length === 0) return { ok: false, reason: "unresolved" };

  // ANY blocked address refuses the whole hostname. Picking the public one out
  // of a mixed answer set is exactly how a rebinding attacker wins a race.
  for (const address of addresses) {
    if (!checkRemoteAddress(address).allowed) return { ok: false, reason: "blocked" };
  }

  // Prefer IPv4 for reachability, otherwise the first IPv6 answer. The chosen
  // address is the one the connection is pinned to.
  const chosen = addresses.find((address) => isIP(address) === 4) ?? addresses[0];
  const family = isIP(chosen);
  if (family !== 4 && family !== 6) return { ok: false, reason: "blocked" };
  return { ok: true, pinned: { address: chosen, family } };
}

/* ------------------------------------------------------------------ */
/* Hop execution                                                       */
/* ------------------------------------------------------------------ */

async function safeFetchWithinLimit(rawUrl: string, options: SafeFetchOptions): Promise<SafeFetchResult> {
  const maxRedirects = options.maxRedirects ?? DEFAULT_MAX_REDIRECTS;
  const transport = options.transport ?? transportOverride ?? nodeTransport;
  const resolver = options.resolver ?? resolverOverride ?? defaultResolver;
  const idleTimeoutMs = options.idleTimeoutMs ?? Math.min(DEFAULT_IDLE_TIMEOUT_MS, options.timeoutMs);
  // One deadline for the whole operation: DNS, connect, headers, body, and every
  // redirect hop share the same budget.
  const deadlineAt = Date.now() + options.timeoutMs;
  const plan: HopPlan = { transport, deadlineAt, idleTimeoutMs, maxBytes: options.maxBytes, allowedContentTypes: options.allowedContentTypes };

  let currentUrl = rawUrl;
  for (let hop = 0; hop <= maxRedirects; hop++) {
    const validated = validateFetchUrl(currentUrl);
    if (!validated.ok) {
      // A blocked hop-0 URL is a blocked request; a blocked redirect target is
      // a refused redirect (public -> private redirects are the classic SSRF).
      return hop === 0 ? validated : { ok: false, error: "redirect_blocked" };
    }

    const url = new URL(currentUrl);
    const hostname = stripBrackets(url.hostname);
    const pinned = await pinHostAddress(hostname, resolver);
    if (!pinned.ok) {
      if (hop > 0) return { ok: false, error: "redirect_blocked" };
      return { ok: false, error: pinned.reason === "blocked" ? "blocked_host" : "network_error" };
    }

    const outcome = await fetchHop(url, hostname, pinned.pinned, plan);
    if (outcome.kind === "error") return { ok: false, error: outcome.error, status: outcome.status };
    if (outcome.kind === "response") return { ok: true, contentType: outcome.contentType, bytes: outcome.bytes };

    if (!outcome.location) return { ok: false, error: "redirect_blocked" };
    if (hop === maxRedirects) return { ok: false, error: "too_many_redirects" };
    const resolved = resolveRedirect(currentUrl, outcome.location);
    if (!resolved) return { ok: false, error: "redirect_blocked" };
    currentUrl = resolved;
  }

  return { ok: false, error: "too_many_redirects" };
}

async function fetchHop(url: URL, hostname: string, pinned: PinnedAddress, plan: HopPlan): Promise<HopOutcome> {
  // The hop is always attempted: an exhausted (or sub-millisecond) budget is
  // enforced by the deadline timer, so a 1 ms timeout still produces a real
  // request that is aborted immediately instead of a pre-flight refusal that
  // would depend on clock granularity.
  const budget = Math.max(1, plan.deadlineAt - Date.now());

  const controller = new AbortController();
  let fireExpired: () => void = () => {};
  // Rejects only when the deadline/idle timer fires. Racing a `Promise<never>`
  // keeps the raced value statically typed as the response / read result, so a
  // hung transport can never leave the caller waiting.
  const expired = new Promise<never>((_resolve, reject) => { fireExpired = () => reject(new FetchTimeoutError()); });
  let didExpire = false;
  const onExpire = () => {
    if (didExpire) return;
    didExpire = true;
    controller.abort();
    fireExpired();
  };

  const overallTimer = setTimeout(onExpire, budget);
  let idleTimer: ReturnType<typeof setTimeout> | undefined;
  let finished = false;
  const armIdle = () => {
    // A late chunk from an abandoned stream must not re-arm a timer after the
    // hop has already returned.
    if (finished) return;
    if (idleTimer) clearTimeout(idleTimer);
    idleTimer = setTimeout(onExpire, Math.max(1, plan.idleTimeoutMs));
  };

  try {
    // The idle timer covers connect + waiting for response headers.
    armIdle();
    let pending: Promise<SafeHttpResponse>;
    try {
      pending = plan.transport.request({
        url,
        hostHeader: url.host,
        hostname,
        address: pinned.address,
        family: pinned.family,
        signal: controller.signal,
        method: plan.method,
        headers: plan.headers,
        body: plan.body
      });
    } catch {
      return { kind: "error", error: "network_error" };
    }
    const opened = await Promise.race([pending, expired]);

    const status = opened.status;
    if (status >= 300 && status < 400) {
      const location = opened.headers.get("location");
      opened.destroy();
      return { kind: "redirect", location, status };
    }
    if (status < 200 || status >= 300) {
      opened.destroy();
      return { kind: "error", error: "http_error", status };
    }

    const contentType = opened.headers.get("content-type") ?? "";
    if (plan.allowedContentTypes && !plan.allowedContentTypes.some((pattern) => pattern.test(contentType))) {
      opened.destroy();
      return { kind: "error", error: "http_error", status: 415 };
    }

    const declaredLength = Number(opened.headers.get("content-length"));
    if (Number.isFinite(declaredLength) && declaredLength > plan.maxBytes) {
      opened.destroy();
      return { kind: "error", error: "too_large" };
    }

    let body: AsyncIterable<Uint8Array>;
    try {
      body = decodeBody(opened.body, opened.headers.get("content-encoding") ?? "");
    } catch {
      opened.destroy();
      return { kind: "error", error: "network_error" };
    }

    // The same deadline and the same idle timer keep covering the body read.
    const read = readBodyLimited(body, plan.maxBytes, armIdle);
    const outcome = await Promise.race([read, expired]);
    opened.destroy();
    if (!outcome.ok) return { kind: "error", error: outcome.error };
    return { kind: "response", status, contentType, bytes: outcome.bytes };
  } catch (error) {
    return { kind: "error", error: didExpire || error instanceof FetchTimeoutError ? "timeout" : "network_error" };
  } finally {
    finished = true;
    clearTimeout(overallTimer);
    if (idleTimer) clearTimeout(idleTimer);
  }
}

/**
 * Streams the body and stops the moment it exceeds the cap — the transfer is
 * never buffered first and checked afterwards. `onProgress` re-arms the
 * idle/stall timeout on every chunk.
 */
async function readBodyLimited(
  body: AsyncIterable<Uint8Array>,
  maxBytes: number,
  onProgress: () => void
): Promise<ReadResult> {
  const chunks: Uint8Array[] = [];
  let total = 0;
  try {
    for await (const chunk of body) {
      onProgress();
      total += chunk.byteLength;
      if (total > maxBytes) return { ok: false, error: "too_large" };
      chunks.push(chunk);
    }
  } catch {
    return { ok: false, error: "network_error" };
  }

  const bytes = new Uint8Array(total);
  let offset = 0;
  for (const chunk of chunks) {
    bytes.set(chunk, offset);
    offset += chunk.byteLength;
  }
  return { ok: true, bytes };
}

function decodeBody(body: AsyncIterable<Uint8Array>, rawEncoding: string): AsyncIterable<Uint8Array> {
  const encoding = rawEncoding.split(",")[0].trim().toLowerCase();
  if (!encoding || encoding === "identity") return body;
  const decoder = encoding === "gzip" || encoding === "x-gzip"
    ? createGunzip()
    : encoding === "deflate" || encoding === "x-deflate"
      ? createUnzip()
      : encoding === "br"
        ? createBrotliDecompress()
        : null;
  if (!decoder) throw new Error("unsupported_content_encoding");
  const source = Readable.from(body as AsyncIterable<Buffer>);
  source.on("error", (error) => decoder.destroy(error as Error));
  source.pipe(decoder);
  return decoder as unknown as AsyncIterable<Uint8Array>;
}

function resolveRedirect(baseUrl: string, location: string): string | null {
  let resolved: URL;
  try {
    resolved = new URL(location, baseUrl);
  } catch {
    return null;
  }
  // Only http(s) redirects; everything else (including protocol-relative to
  // other schemes) is refused.
  if (resolved.protocol !== "http:" && resolved.protocol !== "https:") return null;
  return resolved.toString();
}

/* ------------------------------------------------------------------ */
/* Node transport: resolve -> screen -> pin                            */
/* ------------------------------------------------------------------ */

/**
 * A lookup function that ignores the hostname it is asked about and always
 * returns the address that was already validated. This is the pin: even if the
 * name is re-resolved between validation and connect, the socket cannot reach a
 * different address.
 */
function pinnedLookup(address: string, family: 4 | 6): LookupFunction {
  return (_hostname, _options, callback) => callback(null, address, family);
}

export function createNodeSafeHttpTransport(): SafeHttpTransport {
  return {
    request({ url, hostHeader, hostname, address, family, signal, method, headers, body }) {
      return new Promise<SafeHttpResponse>((resolve, reject) => {
        const secure = url.protocol === "https:";
        const payload = body === undefined
          ? null
          : typeof body === "string"
            ? Buffer.from(body, "utf8")
            : Buffer.from(body);
        const options: http.RequestOptions = {
          method: method ?? "GET",
          // Connect target is the validated IP, never the hostname.
          host: address,
          family,
          lookup: pinnedLookup(address, family),
          port: url.port ? Number(url.port) : secure ? 443 : 80,
          path: `${url.pathname}${url.search}`,
          headers: {
            "User-Agent": USER_AGENT,
            Accept: "*/*",
            "Accept-Encoding": "gzip, deflate, br, identity",
            Connection: "close",
            ...(headers ?? {}),
            // Preserve virtual-host routing while the socket goes to the IP.
            // Set last so a caller cannot redirect the vhost choice.
            Host: hostHeader,
            ...(payload ? { "Content-Length": String(payload.byteLength) } : {})
          },
          // `Host` is set explicitly, so Node must not derive it from `host`.
          setHost: false,
          signal,
          // No connection reuse: a pooled socket is a connection that was
          // validated against an older resolution.
          agent: false
        };

        let settled = false;
        const onResponse = (response: http.IncomingMessage) => {
          settled = true;
          resolve({
            status: response.statusCode ?? 0,
            headers: {
              get(name: string) {
                const value = response.headers[name.toLowerCase()];
                if (value === undefined) return null;
                return Array.isArray(value) ? value[0] ?? null : value;
              }
            },
            body: response as unknown as AsyncIterable<Uint8Array>,
            destroy: () => { response.destroy(); request.destroy(); }
          });
        };

        const request = secure
          ? https.request(
              { ...options, servername: isIP(hostname) ? undefined : hostname, rejectUnauthorized: true },
              onResponse
            )
          : http.request(options, onResponse);
        request.on("error", (error) => { if (!settled) reject(error); });
        if (payload) request.write(payload);
        request.end();
      });
    }
  };
}

const nodeTransport = createNodeSafeHttpTransport();

/* ------------------------------------------------------------------ */
/* Pinned POST: the same policy for provider APIs                      */
/* ------------------------------------------------------------------ */

const DEFAULT_POST_MAX_BYTES = 8 * 1024 * 1024;

export type SafePinnedPostOptions = {
  method?: string;
  headers?: Record<string, string>;
  body?: string | Uint8Array;
  /** ONE deadline covering DNS + connect + headers + the whole body read. */
  timeoutMs: number;
  idleTimeoutMs?: number;
  /** Hard cap on the decoded response body (default 8 MiB). */
  maxBytes?: number;
  resolver?: AddressResolver;
  transport?: SafeHttpTransport;
};

export type SafePinnedPostResult =
  | { ok: true; status: number; contentType: string; bytes: Uint8Array }
  | { ok: false; error: SafeFetchErrorCode; status?: number };

/**
 * The pinned request path for server-side POSTs to operator-configured provider
 * APIs (the AI text provider in provider.ts). It is NOT a general HTTP client
 * and it never follows redirects: a 3xx comes back as `http_error` with its
 * status, so a provider can never move a request — with its credentials and the
 * user's content — to a host the gateway did not choose.
 *
 * It runs the exact same resolve -> screen-every-answer -> pin sequence and the
 * same bounded semaphore as safeFetch, so there is still one SSRF policy and one
 * pinning implementation in the codebase.
 */
export async function safePinnedPost(rawUrl: string, options: SafePinnedPostOptions): Promise<SafePinnedPostResult> {
  return fetchSemaphore.run(async () => {
    const validated = validateFetchUrl(rawUrl);
    if (!validated.ok) return validated;

    const url = new URL(rawUrl);
    // A POST carries credentials and user content, so it is https-only: the
    // caller's own scheme guard is not the only thing enforcing it.
    if (url.protocol !== "https:") return { ok: false, error: "blocked_scheme" };
    const hostname = stripBrackets(url.hostname);
    const pinned = await pinHostAddress(hostname, options.resolver ?? resolverOverride ?? defaultResolver);
    if (!pinned.ok) return { ok: false, error: pinned.reason === "blocked" ? "blocked_host" : "network_error" };

    const plan: HopPlan = {
      transport: options.transport ?? transportOverride ?? nodeTransport,
      deadlineAt: Date.now() + options.timeoutMs,
      idleTimeoutMs: options.idleTimeoutMs ?? Math.min(DEFAULT_IDLE_TIMEOUT_MS, options.timeoutMs),
      maxBytes: options.maxBytes ?? DEFAULT_POST_MAX_BYTES,
      method: options.method ?? "POST",
      headers: options.headers,
      body: options.body
    };

    const outcome = await fetchHop(url, hostname, pinned.pinned, plan);
    if (outcome.kind === "response") {
      return { ok: true, status: outcome.status, contentType: outcome.contentType, bytes: outcome.bytes };
    }
    // Redirects are refused, never followed.
    if (outcome.kind === "redirect") return { ok: false, error: "http_error", status: outcome.status };
    return { ok: false, error: outcome.error, status: outcome.status };
  });
}

export function decodeUtf8(bytes: Uint8Array): string {
  return new TextDecoder("utf-8").decode(bytes);
}

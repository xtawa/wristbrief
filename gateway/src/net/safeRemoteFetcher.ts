import { checkRemoteHost } from "./ipPolicy";

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
  maxBytes: number;
  timeoutMs: number;
  maxRedirects?: number;
  allowedContentTypes?: RegExp[];
};

export type SafeFetchResult =
  | { ok: true; contentType: string; bytes: Uint8Array }
  | { ok: false; error: SafeFetchErrorCode; status?: number };

const DEFAULT_MAX_REDIRECTS = 5;

/**
 * The single SSRF-safe remote fetch used by OPML URL preview, article
 * extraction, and the media proxy:
 *  - http/https only, no credentials in URL
 *  - hostname + literal-IP policy (see ipPolicy.ts) re-checked on EVERY hop
 *  - redirects never followed automatically; each Location is resolved and
 *    re-validated, up to maxRedirects
 *  - response bodies are read as a stream and cut off at maxBytes — never
 *    buffered first and checked afterwards
 */
export async function safeFetch(rawUrl: string, options: SafeFetchOptions): Promise<SafeFetchResult> {
  const maxRedirects = options.maxRedirects ?? DEFAULT_MAX_REDIRECTS;
  let currentUrl = rawUrl;

  for (let hop = 0; hop <= maxRedirects; hop++) {
    const validated = validateFetchUrl(currentUrl);
    if (!validated.ok) {
      // A blocked hop-0 URL is a blocked request; a blocked redirect target is
      // a refused redirect (public -> private redirects are the classic SSRF).
      return hop === 0 ? validated : { ok: false, error: "redirect_blocked" };
    }

    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort("timeout"), options.timeoutMs);
    let response: Response;
    try {
      response = await fetch(currentUrl, {
        method: "GET",
        redirect: "manual",
        signal: controller.signal
      });
    } catch (error) {
      clearTimeout(timer);
      return controller.signal.aborted ? { ok: false, error: "timeout" } : { ok: false, error: "network_error" };
    }
    clearTimeout(timer);

    if (response.status >= 300 && response.status < 400) {
      const location = response.headers.get("Location");
      if (!location) return { ok: false, error: "redirect_blocked" };
      if (hop === maxRedirects) return { ok: false, error: "too_many_redirects" };
      const resolved = resolveRedirect(currentUrl, location);
      if (!resolved) return { ok: false, error: "redirect_blocked" };
      currentUrl = resolved;
      continue;
    }

    if (!response.ok) {
      return { ok: false, error: "http_error", status: response.status };
    }

    const contentType = response.headers.get("Content-Type") ?? "";
    if (options.allowedContentTypes && !options.allowedContentTypes.some((pattern) => pattern.test(contentType))) {
      return { ok: false, error: "http_error", status: 415 };
    }

    const read = await readBodyLimited(response, options.maxBytes);
    if (!read.ok) return read;
    return { ok: true, contentType, bytes: read.bytes };
  }

  return { ok: false, error: "too_many_redirects" };
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
  const hostVerdict = checkRemoteHost(parsed.hostname);
  if (!hostVerdict.allowed) return { ok: false, error: "blocked_host" };
  return { ok: true };
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

async function readBodyLimited(response: Response, maxBytes: number): Promise<{ ok: true; bytes: Uint8Array } | { ok: false; error: "too_large" | "network_error" }> {
  if (!response.body) {
    const buffer = await response.arrayBuffer();
    if (buffer.byteLength > maxBytes) return { ok: false, error: "too_large" };
    return { ok: true, bytes: new Uint8Array(buffer) };
  }

  const reader = response.body.getReader();
  const chunks: Uint8Array[] = [];
  let total = 0;
  try {
    while (true) {
      const { done, value } = await reader.read();
      if (done) break;
      total += value.byteLength;
      if (total > maxBytes) {
        await reader.cancel();
        return { ok: false, error: "too_large" };
      }
      chunks.push(value);
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

export function decodeUtf8(bytes: Uint8Array): string {
  return new TextDecoder("utf-8").decode(bytes);
}

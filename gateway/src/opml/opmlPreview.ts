import { decodeUtf8, safeFetch, type SafeFetchErrorCode } from "../net/safeRemoteFetcher";
import { parseOpmlDocument } from "./opmlParser";

export type OpmlPreviewErrorCode =
  | "MISSING_ROOT"
  | "TOO_LARGE"
  | "TOO_MANY_FEEDS"
  | "UNSUPPORTED_ENTITY"
  | "MALFORMED_OUTLINE"
  | "INVALID_FEED_URL"
  | "DUPLICATE_FEED"
  | "FETCH_TIMEOUT"
  | "FETCH_BLOCKED_HOST"
  | "REDIRECT_BLOCKED"
  | "HTTP_ERROR"
  | "INVALID_URL";

export type OpmlPreviewOk = {
  ok: true;
  body: Record<string, unknown>;
};

export type OpmlPreviewError = { ok: false; status: number; code: OpmlPreviewErrorCode };

const MAX_OPML_BYTES = 8_000_000; // byte-level cap, mirrored by the 2M-char parse cap
const FETCH_TIMEOUT_MS = 15_000;

const FETCH_ERROR_MAP: Partial<Record<SafeFetchErrorCode, OpmlPreviewErrorCode>> = {
  blocked_scheme: "FETCH_BLOCKED_HOST",
  blocked_host: "FETCH_BLOCKED_HOST",
  credentials_in_url: "FETCH_BLOCKED_HOST",
  invalid_url: "INVALID_URL",
  redirect_blocked: "REDIRECT_BLOCKED",
  too_many_redirects: "REDIRECT_BLOCKED",
  too_large: "TOO_LARGE",
  timeout: "FETCH_TIMEOUT",
  http_error: "HTTP_ERROR",
  network_error: "FETCH_TIMEOUT"
};

/**
 * POST /v1/opml/preview-url: fetches an OPML document server-side (SSRF-safe,
 * never on the client) and returns a preview the user confirms before apply.
 */
export async function previewOpmlUrl(rawUrl: unknown): Promise<OpmlPreviewOk | OpmlPreviewError> {
  if (typeof rawUrl !== "string" || rawUrl.trim().length === 0 || rawUrl.length > 2048) {
    return { ok: false, status: 400, code: "INVALID_URL" };
  }

  const fetched = await safeFetch(rawUrl.trim(), {
    maxBytes: MAX_OPML_BYTES,
    timeoutMs: FETCH_TIMEOUT_MS
  });
  if (!fetched.ok) {
    const code = FETCH_ERROR_MAP[fetched.error] ?? "HTTP_ERROR";
    const status = code === "TOO_LARGE" ? 413 : code === "FETCH_TIMEOUT" ? 504 : code === "HTTP_ERROR" ? 502 : 400;
    return { ok: false, status, code };
  }

  const parsed = parseOpmlDocument(decodeUtf8(fetched.bytes));
  if (!parsed.ok) {
    const status = parsed.code === "TOO_LARGE" ? 413 : 400;
    return { ok: false, status, code: parsed.code };
  }

  const rejected = parsed.invalidFeedUrls.map((url) => ({ url, reason: "INVALID_FEED_URL" }));
  const total = parsed.feeds.length + parsed.duplicatesWithinImport + rejected.length;

  return {
    ok: true,
    body: {
      source: { type: "url", displayUrl: rawUrl.trim() },
      feeds: parsed.feeds,
      rejected,
      warnings: parsed.duplicatesWithinImport > 0
        ? [`${parsed.duplicatesWithinImport} duplicate feed URL(s) inside the document were merged`]
        : [],
      summary: {
        total,
        valid: parsed.feeds.length,
        duplicate: parsed.duplicatesWithinImport,
        rejected: rejected.length
      }
    }
  };
}

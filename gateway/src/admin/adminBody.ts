/**
 * One body-reading path for every /admin and /v1/admin write endpoint.
 *
 * Before this module each route called `request.json()` inside a try/catch, so an
 * oversized body was buffered in full before anyone noticed, a JSON parse failure
 * was indistinguishable from a wrong content type, and no route could tell the
 * difference between "no body" and "body that is not an object".
 *
 * `readAdminBody` enforces a caller-supplied byte cap, streams the body so the cap
 * is respected even when Content-Length is absent or lies, and returns a
 * machine-readable failure the route can hand straight to `jsonResponse`. It reads
 * both JSON (the JSON API and the page scripts) and
 * application/x-www-form-urlencoded (the no-JavaScript form path) so the two
 * front ends share one parser.
 */

export type AdminBodyContentType = "json" | "form" | "empty";

export type AdminBodySuccess = {
  ok: true;
  value: Record<string, unknown>;
  contentType: AdminBodyContentType;
  bytes: number;
  /** Form field values by name, first value wins; empty for JSON bodies. */
  fields: Record<string, string>;
  /** The CSRF token carried as a form field, when present. Never logged. */
  formCsrfToken: string | null;
};

export type AdminBodyFailure = {
  ok: false;
  status: 400 | 413;
  body: Record<string, unknown>;
};

export type AdminBodyResult = AdminBodySuccess | AdminBodyFailure;

/** Per-route caps. Every admin write endpoint must name one of these. */
export const ADMIN_BODY_LIMITS = {
  /** POST /v1/admin/session, /v1/admin/logout */
  session: 4 * 1024,
  /** PATCH /v1/admin/settings, POST /admin/settings */
  settings: 4 * 1024,
  /** POST /v1/admin/change-password */
  password: 4 * 1024,
  /** POST /admin/login */
  login: 4 * 1024,
  /** PATCH /v1/admin/users/:id */
  user: 4 * 1024,
  /** PUT /v1/admin/smtp (password + host + from), POST /admin/smtp */
  smtp: 16 * 1024,
  /** POST /v1/admin/smtp/test (recipient only) */
  smtpTest: 2 * 1024,
  /** PUT /v1/admin/provider-keys (a single API key) */
  providerKey: 16 * 1024,
  /** PATCH /v1/admin/audio/:id, POST /admin/audio/:id */
  audio: 4 * 1024,
  /** POST/PATCH /v1/admin/providers */
  provider: 16 * 1024,
  /** POST /v1/admin/providers/:id/health-check, POST /admin/audio/:id/check */
  connectivity: 1024,
  /** POST /v1/admin/recovery */
  recovery: 2 * 1024
} as const;

export type AdminBodyLimitName = keyof typeof ADMIN_BODY_LIMITS;

const JSON_TYPES = new Set(["application/json"]);
const FORM_TYPES = new Set(["application/x-www-form-urlencoded"]);

function mediaTypeOf(request: Request): string {
  const header = request.headers.get("Content-Type") ?? "";
  return header.split(";")[0]!.trim().toLowerCase();
}

export async function readAdminBody(request: Request, maxBytes: number): Promise<AdminBodyResult> {
  const declared = Number(request.headers.get("Content-Length"));
  if (Number.isFinite(declared) && declared > maxBytes) {
    return tooLarge(maxBytes, declared);
  }

  let bytes: Uint8Array;
  try {
    bytes = await readBounded(request, maxBytes);
  } catch (error) {
    if (error instanceof BodyTooLargeError) return tooLarge(maxBytes, error.observed);
    return { ok: false, status: 400, body: { error: "unreadable_body" } };
  }

  if (bytes.byteLength === 0) {
    // No body at all: valid for logout, connectivity checks, and any route whose
    // parameters live in the path. The content type is irrelevant here, which
    // also keeps older clients that send no Content-Type working.
    return { ok: true, value: {}, contentType: "empty", bytes: 0, fields: {}, formCsrfToken: null };
  }

  const mediaType = mediaTypeOf(request);
  const text = new TextDecoder().decode(bytes);

  if (JSON_TYPES.has(mediaType) || mediaType.endsWith("+json")) {
    let parsed: unknown;
    try {
      parsed = JSON.parse(text);
    } catch {
      return { ok: false, status: 400, body: { error: "malformed_json", message: "The request body is not valid JSON." } };
    }
    if (parsed === null || typeof parsed !== "object" || Array.isArray(parsed)) {
      return { ok: false, status: 400, body: { error: "malformed_json", message: "The request body must be a JSON object.", expected: "object" } };
    }
    const value = parsed as Record<string, unknown>;
    return { ok: true, value, contentType: "json", bytes: bytes.byteLength, fields: {}, formCsrfToken: null };
  }

  if (FORM_TYPES.has(mediaType)) {
    const params = new URLSearchParams(text);
    const fields: Record<string, string> = {};
    const value: Record<string, unknown> = {};
    for (const [key, entryValue] of params) {
      // First value wins: repeated keys are ambiguous, and picking the first keeps
      // the HTML form path deterministic and identical to a JSON object.
      if (!(key in fields)) {
        fields[key] = entryValue;
        value[key] = entryValue;
      }
    }
    return {
      ok: true,
      value,
      contentType: "form",
      bytes: bytes.byteLength,
      fields,
      formCsrfToken: fields.csrfToken ?? null
    };
  }

  return {
    ok: false,
    status: 400,
    body: {
      error: "unsupported_media_type",
      message: `Send application/json or application/x-www-form-urlencoded (received ${mediaType || "none"}).`,
      received: mediaType || null,
      supported: ["application/json", "application/x-www-form-urlencoded"]
    }
  };
}

class BodyTooLargeError extends Error {
  constructor(readonly observed: number) {
    super("request_too_large");
    this.name = "BodyTooLargeError";
  }
}

async function readBounded(request: Request, maxBytes: number): Promise<Uint8Array> {
  const body = request.body;
  if (!body) return new Uint8Array(await request.arrayBuffer());
  const reader = body.getReader();
  const chunks: Uint8Array[] = [];
  let total = 0;
  try {
    for (;;) {
      const { value, done } = await reader.read();
      if (done) break;
      if (!value) continue;
      total += value.byteLength;
      if (total > maxBytes) {
        await reader.cancel().catch(() => undefined);
        throw new BodyTooLargeError(total);
      }
      chunks.push(value);
    }
  } finally {
    reader.releaseLock?.();
  }
  const merged = new Uint8Array(total);
  let offset = 0;
  for (const chunk of chunks) {
    merged.set(chunk, offset);
    offset += chunk.byteLength;
  }
  return merged;
}

function tooLarge(maxBytes: number, observed: number): AdminBodyFailure {
  return {
    ok: false,
    status: 413,
    body: {
      error: "request_too_large",
      // Names the limit so an operator can act on it, and says plainly that the
      // request was too large rather than reporting a downstream failure.
      message: `That request is too large: the body exceeds the ${maxBytes} byte limit for this endpoint.`,
      maxBytes,
      observedBytes: observed > 0 ? observed : null
    }
  };
}

/**
 * Shared shape for a body failure so HTML and JSON paths report the same thing.
 * The HTML path turns this into an inline message; the JSON path returns it as is.
 */
export function bodyFailureMessage(body: Record<string, unknown>): string {
  const message = body.message;
  if (typeof message === "string" && message) return message;
  switch (body.error) {
    case "request_too_large": {
      const maxBytes = typeof body.maxBytes === "number" ? body.maxBytes : null;
      return maxBytes
        ? `That request is too large: the body exceeds the ${maxBytes} byte limit for this endpoint.`
        : "That request was too large. Shorten the values and try again.";
    }
    case "malformed_json":
      return "The request body could not be read.";
    case "unsupported_media_type":
      return "That content type is not supported.";
    default:
      return "The request could not be read.";
  }
}

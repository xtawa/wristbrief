import { safeFetch } from "../net/safeRemoteFetcher";
import { urlForMediaId } from "./articleTypes";

export type MediaProxyEnv = {
  ACCOUNT_DB?: D1Database;
  TRANSCRIPTS_BUCKET?: R2Bucket;
};

const MAX_IMAGE_BYTES = 10 * 1024 * 1024;
const FETCH_TIMEOUT_MS = 15_000;
const MEDIA_TTL_MS = 24 * 60 * 60 * 1000;

/**
 * GET /v1/media/:mediaId — the only way article images reach clients. The
 * client never sees the upstream URL; the proxy decodes the opaque id, runs the
 * full SSRF policy (hostname/IP, redirects, size, timeout), sniffs the content
 * type, and serves from R2 when cached.
 */
export async function handleMediaGet(request: Request, env: MediaProxyEnv, mediaId: string): Promise<Response> {
  const url = urlForMediaId(mediaId);
  if (!url || !(url.startsWith("http://") || url.startsWith("https://"))) {
    return mediaError(400, "invalid_media_id");
  }

  const objectKey = `articles/media/${mediaId}`;
  const bucket = env.TRANSCRIPTS_BUCKET;
  if (bucket) {
    const cached = await bucket.get(objectKey);
    if (cached) {
      return new Response(cached.body, {
        status: 200,
        headers: mediaHeaders(cached.httpMetadata?.contentType ?? "application/octet-stream", cached.size)
      });
    }
  }

  const fetched = await safeFetch(url, {
    maxBytes: MAX_IMAGE_BYTES,
    timeoutMs: FETCH_TIMEOUT_MS,
    allowedContentTypes: [/^image\//i]
  });
  if (!fetched.ok) {
    return mediaError(fetched.error === "http_error" && fetched.status === 415 ? 415 : 404, "media_unavailable");
  }

  // Trust only the sniffed prefix; the content-type allowlist above already
  // restricted it to image/*.
  const contentType = fetched.contentType.split(";")[0].trim().toLowerCase();
  if (bucket) {
    await bucket.put(objectKey, fetched.bytes, {
      httpMetadata: { contentType }
    });
    if (env.ACCOUNT_DB) {
      const now = Date.now();
      await env.ACCOUNT_DB
        .prepare(
          `INSERT INTO article_media_cache (media_id, content_type, byte_length, r2_object_key, fetched_at, expires_at)
           VALUES (?, ?, ?, ?, ?, ?)
           ON CONFLICT(media_id) DO UPDATE SET fetched_at = excluded.fetched_at, expires_at = excluded.expires_at`
        )
        .bind(mediaId, contentType, fetched.bytes.byteLength, objectKey, now, now + MEDIA_TTL_MS)
        .run();
    }
  }

  return new Response(fetched.bytes, { status: 200, headers: mediaHeaders(contentType, fetched.bytes.byteLength) });
}

function mediaHeaders(contentType: string, byteLength: number): Record<string, string> {
  return {
    "Content-Type": contentType,
    "Content-Length": String(byteLength),
    "Cache-Control": `public, max-age=${Math.floor(MEDIA_TTL_MS / 1000)}`,
    "Content-Security-Policy": "default-src 'none'; sandbox",
    "X-Content-Type-Options": "nosniff"
  };
}

function mediaError(status: number, code: string): Response {
  return new Response(JSON.stringify({ error: code }), {
    status,
    headers: { "Content-Type": "application/json; charset=utf-8", "Cache-Control": "no-store" }
  });
}

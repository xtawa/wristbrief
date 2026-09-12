import { ContentResolver, validateAudioUrl, type EpisodeMetadataInput } from "./contentResolver";
import { D1ContentStore } from "./contentStore";

export interface ContentRouteEnv {
  ACCOUNT_DB?: D1Database;
}

export async function handleContentResolve(
  request: Request,
  env: ContentRouteEnv,
  preParsedBody?: EpisodeMetadataInput
): Promise<{ status: number; body: unknown }> {
  if (!env.ACCOUNT_DB) {
    return { status: 503, body: { error: "database_unavailable" } };
  }

  let body: EpisodeMetadataInput;
  if (preParsedBody) {
    body = preParsedBody;
  } else {
    try {
      body = (await request.json()) as EpisodeMetadataInput;
    } catch {
      return { status: 400, body: { error: "invalid_json" } };
    }
  }

  if (!body || !body.audioUrl) {
    return { status: 400, body: { error: "invalid_request", message: "audioUrl is required" } };
  }

  const urlCheck = validateAudioUrl(body.audioUrl);
  if (!urlCheck.ok) {
    return { status: 400, body: { error: "invalid_audio_url", message: urlCheck.error } };
  }

  const store = new D1ContentStore(env.ACCOUNT_DB);
  const resolver = new ContentResolver(store);
  const result = await resolver.resolve(body);

  return {
    status: 200,
    body: {
      contentId: result.contentId,
      contentCode: result.contentCode,
      isNew: result.isNew,
      sharePolicy: result.content.sharePolicy,
      canonicalTitle: result.content.canonicalTitle,
      canonicalDurationMs: result.content.canonicalDurationMs
    }
  };
}

export async function handleContentGet(
  contentCode: string,
  env: ContentRouteEnv
): Promise<{ status: number; body: unknown }> {
  if (!env.ACCOUNT_DB) {
    return { status: 503, body: { error: "database_unavailable" } };
  }

  const store = new D1ContentStore(env.ACCOUNT_DB);
  const content = await store.findByContentCode(contentCode);
  if (!content) {
    return { status: 404, body: { error: "content_not_found" } };
  }

  return {
    status: 200,
    body: content
  };
}

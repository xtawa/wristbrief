import { D1SyncStore } from "./d1SyncStore";
import { type SyncPushRequest } from "./syncTypes";

export interface SyncRouteEnv {
  ACCOUNT_DB?: D1Database;
}

const MAX_SYNC_IDENTIFIER_LENGTH = 128;
const CONTROL_CHARS = /[\u0000-\u001F\u007F]/;

function isValidIdentifier(value: unknown): value is string {
  return typeof value === "string" &&
    value.length > 0 &&
    value.length <= MAX_SYNC_IDENTIFIER_LENGTH &&
    value === value.trim() &&
    !CONTROL_CHARS.test(value);
}

export async function handleSyncPush(
  request: Request,
  env: SyncRouteEnv,
  userId: string,
  preParsedBody?: SyncPushRequest
): Promise<{ status: number; body: unknown }> {
  if (!env.ACCOUNT_DB) {
    return { status: 503, body: { error: "database_unavailable" } };
  }

  let body: SyncPushRequest;
  if (preParsedBody) {
    body = preParsedBody;
  } else {
    try {
      body = (await request.json()) as SyncPushRequest;
    } catch {
      return { status: 400, body: { error: "invalid_json" } };
    }
  }

  if (!isValidIdentifier(body.deviceId) || !Array.isArray(body.mutations)) {
    return { status: 400, body: { error: "invalid_request", message: "deviceId and mutations array required" } };
  }

  if (body.mutations.length > 100) {
    return { status: 400, body: { error: "too_many_mutations", message: "Maximum 100 mutations allowed per push" } };
  }

  const idempotencyKeys = new Set<string>();
  for (const mutation of body.mutations) {
    if (!mutation || typeof mutation !== "object") {
      return { status: 400, body: { error: "invalid_mutation" } };
    }
    if (!isValidIdentifier(mutation.idempotencyKey)) {
      return { status: 400, body: { error: "invalid_idempotency_key" } };
    }
    if (!isValidIdentifier(mutation.entityId)) {
      return { status: 400, body: { error: "invalid_entity_id" } };
    }
    if (!Number.isFinite(mutation.updatedAt) || mutation.updatedAt < 0) {
      return { status: 400, body: { error: "invalid_updated_at" } };
    }
    if (!mutation.payload || typeof mutation.payload !== "object" || Array.isArray(mutation.payload)) {
      return { status: 400, body: { error: "invalid_payload" } };
    }
    if (idempotencyKeys.has(mutation.idempotencyKey)) {
      return { status: 400, body: { error: "duplicate_idempotency_key" } };
    }
    idempotencyKeys.add(mutation.idempotencyKey);
  }

  const syncStore = new D1SyncStore(env.ACCOUNT_DB);
  const result = await syncStore.pushMutations(userId, body);

  return {
    status: 200,
    body: result
  };
}

export async function handleSyncPull(
  request: Request,
  env: SyncRouteEnv,
  userId: string
): Promise<{ status: number; body: unknown }> {
  if (!env.ACCOUNT_DB) {
    return { status: 503, body: { error: "database_unavailable" } };
  }

  const url = new URL(request.url);
  const cursor = Number(url.searchParams.get("cursor") || 0);
  const deviceId = url.searchParams.get("deviceId");
  const requestedLimit = Number(url.searchParams.get("limit") || 100);
  if (!isValidIdentifier(deviceId) || !Number.isFinite(cursor) || cursor < 0 || !Number.isFinite(requestedLimit) || requestedLimit < 0) {
    return { status: 400, body: { error: "invalid_sync_parameters" } };
  }
  const limit = Math.min(200, Math.max(1, Math.floor(requestedLimit)));

  const syncStore = new D1SyncStore(env.ACCOUNT_DB);
  const result = await syncStore.pullDeltas(userId, deviceId, cursor, limit);

  return {
    status: 200,
    body: result
  };
}

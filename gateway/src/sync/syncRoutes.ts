import { D1SyncStore } from "./d1SyncStore";
import { type SyncPushRequest } from "./syncTypes";

export interface SyncRouteEnv {
  ACCOUNT_DB?: D1Database;
}

export async function handleSyncPush(
  request: Request,
  env: SyncRouteEnv,
  userId: string
): Promise<{ status: number; body: unknown }> {
  if (!env.ACCOUNT_DB) {
    return { status: 503, body: { error: "database_unavailable" } };
  }

  let body: SyncPushRequest;
  try {
    body = (await request.json()) as SyncPushRequest;
  } catch {
    return { status: 400, body: { error: "invalid_json" } };
  }

  if (!body.deviceId || !Array.isArray(body.mutations)) {
    return { status: 400, body: { error: "invalid_request", message: "deviceId and mutations array required" } };
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
  const deviceId = url.searchParams.get("deviceId") || "unknown";
  const limit = Math.min(200, Number(url.searchParams.get("limit") || 100));

  const syncStore = new D1SyncStore(env.ACCOUNT_DB);
  const result = await syncStore.pullDeltas(userId, deviceId, cursor, limit);

  return {
    status: 200,
    body: result
  };
}

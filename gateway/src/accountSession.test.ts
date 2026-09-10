import { describe, expect, it } from "vitest";
import {
  AccountSessionService,
  InMemoryAccountSessionStore,
  parseBearerSessionToken
} from "./accountSession";

const startMs = Date.parse("2026-09-10T15:00:00Z");
const entropy = Uint8Array.from({ length: 32 }, (_, index) => index + 1);

function service(store: InMemoryAccountSessionStore, now: () => number = () => startMs) {
  return new AccountSessionService(store, {
    now,
    newSessionId: () => "session-id-1",
    randomBytes: () => entropy,
    ttlSeconds: 3600
  });
}

describe("WristBrief account sessions", () => {
  it("stores only a one-way token hash and authenticates the issued bearer", async () => {
    const store = new InMemoryAccountSessionStore();
    const sessions = service(store);

    const issued = await sessions.issue("user-123");
    expect(issued.token).toMatch(/^wbs_[A-Za-z0-9_-]{43}$/);
    expect(issued.expiresAt).toBe("2026-09-10T16:00:00.000Z");

    const records = store.records();
    expect(records).toHaveLength(1);
    expect(records[0]).toMatchObject({
      id: "session-id-1",
      userId: "user-123",
      createdAt: "2026-09-10T15:00:00.000Z",
      expiresAt: "2026-09-10T16:00:00.000Z"
    });
    expect(records[0]!.tokenHash).toMatch(/^[0-9a-f]{64}$/);
    expect(JSON.stringify(records)).not.toContain(issued.token);
    await expect(sessions.authenticateAuthorizationHeader(`Bearer ${issued.token}`)).resolves.toBe("user-123");
  });

  it("fails closed for expired or revoked sessions", async () => {
    const store = new InMemoryAccountSessionStore();
    let clock = startMs;
    const sessions = service(store, () => clock);
    const issued = await sessions.issue("user-123");

    expect(await sessions.revokeAuthorizationHeader(`Bearer ${issued.token}`)).toBe(true);
    await expect(sessions.authenticateAuthorizationHeader(`Bearer ${issued.token}`)).resolves.toBeNull();

    const freshStore = new InMemoryAccountSessionStore();
    clock = startMs;
    const expiring = service(freshStore, () => clock);
    const expiringIssued = await expiring.issue("user-456");
    clock = startMs + 3600_000;
    await expect(expiring.authenticateAuthorizationHeader(`Bearer ${expiringIssued.token}`)).resolves.toBeNull();
  });

  it("rejects malformed bearer values without touching persisted state", async () => {
    const store = new InMemoryAccountSessionStore();
    const sessions = service(store);
    await expect(sessions.authenticateAuthorizationHeader(null)).resolves.toBeNull();
    await expect(sessions.authenticateAuthorizationHeader("Bearer wrong" )).resolves.toBeNull();
    await expect(sessions.authenticateAuthorizationHeader("Basic wbs_AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA")).resolves.toBeNull();
    expect(store.records()).toHaveLength(0);
  });

  it("does not authenticate a different random token", async () => {
    const store = new InMemoryAccountSessionStore();
    const sessions = service(store);
    const issued = await sessions.issue("user-123");
    const wrongToken = issued.token.slice(0, -1) + (issued.token.endsWith("A") ? "B" : "A");
    await expect(sessions.authenticateAuthorizationHeader(`Bearer ${wrongToken}`)).resolves.toBeNull();
  });

  it("bounds session lifetime and validates server-side identifiers", async () => {
    expect(() => new AccountSessionService(new InMemoryAccountSessionStore(), { ttlSeconds: 59 })).toThrow("invalid_session_ttl");
    expect(() => new AccountSessionService(new InMemoryAccountSessionStore(), { ttlSeconds: 91 * 24 * 60 * 60 })).toThrow("invalid_session_ttl");

    const badUser = service(new InMemoryAccountSessionStore());
    await expect(badUser.issue("bad\nuser")).rejects.toThrow("invalid_user_id");
    const badSessionId = new AccountSessionService(new InMemoryAccountSessionStore(), {
      now: () => startMs,
      newSessionId: () => "bad\nsession",
      randomBytes: () => entropy,
      ttlSeconds: 3600
    });
    await expect(badSessionId.issue("user-123")).rejects.toThrow("invalid_session_id");
  });
});

describe("parseBearerSessionToken", () => {
  it("accepts only exact WristBrief session bearer format", () => {
    const valid = `wbs_${"A".repeat(43)}`;
    expect(parseBearerSessionToken(`Bearer ${valid}`)).toBe(valid);
    expect(parseBearerSessionToken(`bearer ${valid}`)).toBeNull();
    expect(parseBearerSessionToken(`Bearer  ${valid}`)).toBeNull();
    expect(parseBearerSessionToken(`Bearer ${valid}.extra`)).toBeNull();
  });
});

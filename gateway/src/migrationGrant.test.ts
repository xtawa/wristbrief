import { describe, expect, it } from "vitest";
import {
  InMemoryLegacyMigrationGrantStore,
  LegacyMigrationGrantService,
  migrationGrantTtlSeconds
} from "./migrationGrant";

describe("legacy migration grants", () => {
  it("stores only a hash and consumes a valid grant once", async () => {
    const store = new InMemoryLegacyMigrationGrantStore();
    const service = new LegacyMigrationGrantService(store, 600, () => new Date("2026-09-11T00:00:00Z"));
    const issued = await service.issue("legacy-user-1");
    expect(issued.token).toMatch(/^wbm_[A-Za-z0-9_-]{43}$/);
    expect(issued.expiresAt).toBe("2026-09-11T00:10:00.000Z");
    await expect(service.consume(issued.token)).resolves.toBe("legacy-user-1");
    await expect(service.consume(issued.token)).resolves.toBeNull();
  });

  it("rejects malformed and expired grants", async () => {
    const store = new InMemoryLegacyMigrationGrantStore();
    let now = new Date("2026-09-11T00:00:00Z");
    const service = new LegacyMigrationGrantService(store, 60, () => now);
    const issued = await service.issue("legacy-user-2");
    await expect(service.consume("wbm_bad")).resolves.toBeNull();
    now = new Date("2026-09-11T00:01:01Z");
    await expect(service.consume(issued.token)).resolves.toBeNull();
  });

  it("bounds configurable TTL", () => {
    expect(migrationGrantTtlSeconds(undefined)).toBe(600);
    expect(migrationGrantTtlSeconds("120")).toBe(120);
    expect(() => migrationGrantTtlSeconds("59")).toThrow("invalid_migration_grant_ttl");
    expect(() => migrationGrantTtlSeconds("1801")).toThrow("invalid_migration_grant_ttl");
  });
});

import { describe, expect, it } from "vitest";
import { createMigratedTestDb } from "../testDbHelper";
import { calculateNormalTranscriptUnits, D1QuotaLedgerStore } from "./quotaLedger";

describe("QuotaLedger & Two-Phase Credit Transactions", () => {
  it("calculates normal units based on duration minutes with minimum 1", () => {
    expect(calculateNormalTranscriptUnits(undefined)).toBe(1);
    expect(calculateNormalTranscriptUnits(0)).toBe(1);
    expect(calculateNormalTranscriptUnits(30000)).toBe(1); // 0.5 min -> 1 unit
    expect(calculateNormalTranscriptUnits(60000)).toBe(1); // 1.0 min -> 1 unit
    expect(calculateNormalTranscriptUnits(61000)).toBe(2); // 1.016 min -> 2 units
    expect(calculateNormalTranscriptUnits(6821000)).toBe(114); // 113.68 min -> 114 units
  });

  it("handles two-phase credit reservation, commitment, and release", async () => {
    const { d1 } = createMigratedTestDb();
    const ledger = new D1QuotaLedgerStore(d1);
    const userId = "usr_quota_1";

    // 1. Reserve 10 units at 0.2x multiplier = 2 units
    const tx = await ledger.reserve(userId, "transcript", "art_123", 10, 0.2);
    expect(tx.status).toBe("RESERVED");
    expect(tx.units).toBe(2);
    expect(tx.multiplier).toBe(0.2);
    expect(tx.committedAt).toBeNull();

    // Repeated reservation with same reference returns existing tx (idempotent)
    const txDup = await ledger.reserve(userId, "transcript", "art_123", 10, 0.2);
    expect(txDup.id).toBe(tx.id);

    // 2. Commit transaction
    await ledger.commit(tx.id);
    const committed = await ledger.findByReference(userId, "transcript", "art_123");
    expect(committed?.status).toBe("COMMITTED");
    expect(committed?.committedAt).toBeGreaterThan(0);
  });

  it("releases reserved quota when job fails", async () => {
    const { d1 } = createMigratedTestDb();
    const ledger = new D1QuotaLedgerStore(d1);
    const userId = "usr_quota_2";

    const tx = await ledger.reserve(userId, "transcript", "job_fail", 60, 1.0);
    expect(tx.status).toBe("RESERVED");
    expect(tx.units).toBe(60);

    await ledger.release(tx.id);
    const released = await ledger.findByReference(userId, "transcript", "job_fail");
    expect(released?.status).toBe("RELEASED");
  });
});

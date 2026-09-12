export interface CreditTransaction {
  id: string;
  userId: string;
  operationType: string;
  referenceId: string;
  units: number;
  multiplier: number;
  status: "RESERVED" | "COMMITTED" | "RELEASED";
  createdAt: number;
  committedAt: number | null;
}

export interface QuotaLedgerStore {
  reserve(
    userId: string,
    operationType: string,
    referenceId: string,
    normalUnits: number,
    multiplier: number
  ): Promise<CreditTransaction>;
  commit(transactionId: string): Promise<void>;
  release(transactionId: string): Promise<void>;
  findByReference(userId: string, operationType: string, referenceId: string): Promise<CreditTransaction | null>;
}

export class D1QuotaLedgerStore implements QuotaLedgerStore {
  constructor(private readonly db: D1Database) {}

  async reserve(
    userId: string,
    operationType: string,
    referenceId: string,
    normalUnits: number,
    multiplier: number
  ): Promise<CreditTransaction> {
    const existing = await this.findByReference(userId, operationType, referenceId);
    if (existing) {
      if (existing.status === "RELEASED") {
        const now = Date.now();
        await this.db
          .prepare("UPDATE credit_transactions SET status = 'RESERVED', committed_at = NULL, created_at = ? WHERE id = ? AND status = 'RELEASED'")
          .bind(now, existing.id)
          .run();
        const reactivated = await this.findByReference(userId, operationType, referenceId);
        if (reactivated?.status === "RESERVED") return reactivated;
      }
      return existing;
    }

    const id = `tx_${crypto.randomUUID()}`;
    const now = Date.now();
    const chargedUnits = Math.round(normalUnits * multiplier * 100) / 100;

    await this.db
      .prepare(`
        INSERT INTO credit_transactions (
          id, user_id, operation_type, reference_id, units, multiplier, status, created_at
        ) VALUES (?, ?, ?, ?, ?, ?, 'RESERVED', ?)
      `)
      .bind(id, userId, operationType, referenceId, chargedUnits, multiplier, now)
      .run();

    return {
      id,
      userId,
      operationType,
      referenceId,
      units: chargedUnits,
      multiplier,
      status: "RESERVED",
      createdAt: now,
      committedAt: null
    };
  }

  async commit(transactionId: string): Promise<void> {
    const now = Date.now();
    await this.db
      .prepare("UPDATE credit_transactions SET status = 'COMMITTED', committed_at = ? WHERE id = ? AND status = 'RESERVED'")
      .bind(now, transactionId)
      .run();
  }

  async release(transactionId: string): Promise<void> {
    await this.db
      .prepare("UPDATE credit_transactions SET status = 'RELEASED' WHERE id = ? AND status = 'RESERVED'")
      .bind(transactionId)
      .run();
  }

  async findByReference(userId: string, operationType: string, referenceId: string): Promise<CreditTransaction | null> {
    const row = await this.db
      .prepare("SELECT * FROM credit_transactions WHERE user_id = ? AND operation_type = ? AND reference_id = ?")
      .bind(userId, operationType, referenceId)
      .first<Record<string, unknown>>();

    if (!row) return null;

    return {
      id: String(row.id),
      userId: String(row.user_id),
      operationType: String(row.operation_type),
      referenceId: String(row.reference_id),
      units: Number(row.units),
      multiplier: Number(row.multiplier),
      status: row.status as "RESERVED" | "COMMITTED" | "RELEASED",
      createdAt: Number(row.created_at),
      committedAt: typeof row.committed_at === "number" ? row.committed_at : null
    };
  }
}

/**
 * Calculates normal units based on duration.
 * Formula: ceil(durationMinutes), minimum 1.
 */
export function calculateNormalTranscriptUnits(durationMs?: number): number {
  if (!durationMs || durationMs <= 0) return 1;
  const minutes = durationMs / 60000;
  return Math.max(1, Math.ceil(minutes));
}

import type { DurableMembershipEnv } from "./d1MembershipStore";

export interface RtdnDedupStore {
  /** Atomically claims a Pub/Sub message id. False means another delivery already claimed it. */
  claim(messageId: string): Promise<boolean>;
  /** Releases a claim only when processing failed transiently so Pub/Sub retry can re-attempt it. */
  release(messageId: string): Promise<void>;
}

export class D1RtdnDedupStore implements RtdnDedupStore {
  constructor(private readonly db: D1Database) {}

  async claim(messageId: string): Promise<boolean> {
    const result = await this.db.prepare(
      "INSERT OR IGNORE INTO play_rtdn_messages (message_id, processed_at) VALUES (?, CURRENT_TIMESTAMP)"
    ).bind(messageId).run();
    return (result.meta.changes ?? 0) === 1;
  }

  async release(messageId: string): Promise<void> {
    await this.db.prepare(
      "DELETE FROM play_rtdn_messages WHERE message_id = ?"
    ).bind(messageId).run();
  }
}

export class InMemoryRtdnDedupStore implements RtdnDedupStore {
  private readonly claimed = new Set<string>();

  async claim(messageId: string): Promise<boolean> {
    if (this.claimed.has(messageId)) return false;
    this.claimed.add(messageId);
    return true;
  }

  async release(messageId: string): Promise<void> {
    this.claimed.delete(messageId);
  }
}

export function createConfiguredD1RtdnDedupStore(env: DurableMembershipEnv): RtdnDedupStore | undefined {
  return env.ACCOUNT_DB ? new D1RtdnDedupStore(env.ACCOUNT_DB) : undefined;
}

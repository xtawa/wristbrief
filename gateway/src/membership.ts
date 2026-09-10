import { createConfiguredD1MembershipStore, type DurableMembershipEnv } from "./d1MembershipStore";

export type Plan = "FREE" | "PRO";
export type AiUsageKind = "managed" | "byok";

export type AuthenticatedUser = {
  id: string;
};

export type Entitlement = {
  plan: Plan;
  source: "legacy" | "billing" | "admin" | "test" | "default";
  expiresAt?: string;
};

export type ManagedAiQuota = {
  limit: number | null;
  used: number;
  remaining: number | null;
};

export type MembershipSnapshot = {
  user: AuthenticatedUser;
  entitlement: Entitlement;
  managedAiQuota: ManagedAiQuota;
};

export interface MembershipStore {
  getEntitlement(userId: string): Promise<Entitlement>;
  getManagedAiQuota(userId: string): Promise<{ limit: number | null; used: number }>;
  incrementManagedAiUsage(userId: string): Promise<void>;
}

export interface BillingVerifier {
  verify(input: { userId: string; productId: string; purchaseToken: string }): Promise<Entitlement | null>;
}

export class FakeBillingVerifier implements BillingVerifier {
  constructor(private readonly result: Entitlement | null = null) {}

  async verify(_input: { userId: string; productId: string; purchaseToken: string }): Promise<Entitlement | null> {
    return this.result;
  }
}

export type MembershipEnv = DurableMembershipEnv & {
  GATEWAY_TOKEN?: string;
  GATEWAY_USER_ID?: string;
  MEMBERSHIP_STORE?: MembershipStore;
};

export function authenticateGatewayUser(request: Request, env: MembershipEnv): AuthenticatedUser | null {
  const configuredToken = env.GATEWAY_TOKEN;
  if (!configuredToken) return null;
  const presented = request.headers.get("Authorization");
  if (presented !== `Bearer ${configuredToken}`) return null;
  return { id: env.GATEWAY_USER_ID?.trim() || "legacy-user" };
}

export class MembershipService {
  constructor(private readonly store: MembershipStore) {}

  async snapshot(user: AuthenticatedUser): Promise<MembershipSnapshot> {
    const [entitlement, quota] = await Promise.all([
      this.store.getEntitlement(user.id),
      this.store.getManagedAiQuota(user.id)
    ]);
    return {
      user,
      entitlement,
      managedAiQuota: normalizeQuota(quota)
    };
  }

  async canUseAi(userId: string, kind: AiUsageKind): Promise<{ allowed: boolean; quota: ManagedAiQuota }> {
    if (kind === "byok") {
      return { allowed: true, quota: normalizeQuota(await this.store.getManagedAiQuota(userId)) };
    }

    // Managed-AI admission reserves quota atomically before any upstream provider work.
    // Durable stores must enforce the limit inside incrementManagedAiUsage rather than via a prior read.
    try {
      await this.store.incrementManagedAiUsage(userId);
      return { allowed: true, quota: normalizeQuota(await this.store.getManagedAiQuota(userId)) };
    } catch (error) {
      if (!(error instanceof Error) || error.message !== "managed_ai_quota_unavailable") throw error;
      return { allowed: false, quota: normalizeQuota(await this.store.getManagedAiQuota(userId)) };
    }
  }

  async recordAiUsage(_userId: string, _kind: AiUsageKind): Promise<void> {
    // Managed usage is reserved during canUseAi so concurrent requests cannot all pass a stale quota read.
    // BYOK never consumes managed quota.
  }
}

export function createMembershipService(env: MembershipEnv): MembershipService {
  const legacyUserId = env.GATEWAY_USER_ID?.trim() || "legacy-user";
  const store = env.MEMBERSHIP_STORE ?? createConfiguredD1MembershipStore(env) ?? new LegacyScopedMembershipStore(legacyUserId);
  return new MembershipService(store);
}

class LegacyScopedMembershipStore implements MembershipStore {
  constructor(private readonly legacyUserId: string) {}

  async getEntitlement(userId: string): Promise<Entitlement> {
    return userId === this.legacyUserId
      ? { plan: "PRO", source: "legacy" }
      : { plan: "FREE", source: "default" };
  }

  async getManagedAiQuota(userId: string): Promise<{ limit: number | null; used: number }> {
    return userId === this.legacyUserId
      ? { limit: null, used: 0 }
      : { limit: 0, used: 0 };
  }

  async incrementManagedAiUsage(userId: string): Promise<void> {
    if (userId !== this.legacyUserId) throw new Error("managed_ai_quota_unavailable");
    // Compatibility mode for the configured legacy principal only; its quota is unlimited.
  }
}

type InMemoryMembershipRecord = {
  userId: string;
  plan: Plan;
  managedAiLimit: number | null;
  managedAiUsed?: number;
  source?: Entitlement["source"];
  expiresAt?: string;
};

export class InMemoryMembershipStore implements MembershipStore {
  private readonly records = new Map<string, InMemoryMembershipRecord>();

  constructor(records: InMemoryMembershipRecord[] = []) {
    for (const record of records) this.records.set(record.userId, { ...record });
  }

  async getEntitlement(userId: string): Promise<Entitlement> {
    const record = this.require(userId);
    return {
      plan: record.plan,
      source: record.source ?? "test",
      expiresAt: record.expiresAt
    };
  }

  async getManagedAiQuota(userId: string): Promise<{ limit: number | null; used: number }> {
    const record = this.require(userId);
    return { limit: record.managedAiLimit, used: record.managedAiUsed ?? 0 };
  }

  async incrementManagedAiUsage(userId: string): Promise<void> {
    const record = this.require(userId);
    const used = record.managedAiUsed ?? 0;
    if (record.managedAiLimit !== null && used >= record.managedAiLimit) {
      throw new Error("managed_ai_quota_unavailable");
    }
    record.managedAiUsed = used + 1;
  }

  private require(userId: string): InMemoryMembershipRecord {
    const record = this.records.get(userId);
    if (!record) throw new Error("membership_not_found");
    return record;
  }
}

function normalizeQuota(value: { limit: number | null; used: number }): ManagedAiQuota {
  const used = Math.max(0, Math.floor(value.used));
  const limit = value.limit === null ? null : Math.max(0, Math.floor(value.limit));
  return {
    limit,
    used,
    remaining: limit === null ? null : Math.max(0, limit - used)
  };
}

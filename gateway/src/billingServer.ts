import type { AuthenticatedUser, Entitlement } from "./membership";
import { createConfiguredGooglePlayPurchaseVerifier, type GooglePlayVerifierEnv } from "./googlePlayVerifier";
import { createConfiguredPubSubPushAuthenticator, type PubSubPushAuthEnv } from "./pubSubPushAuthenticator";
import { createConfiguredD1BillingStateStore, type DurableMembershipEnv } from "./d1MembershipStore";
import { createConfiguredD1RtdnDedupStore, type RtdnDedupStore } from "./rtdnDedupStore";

export type PlaySubscriptionStatus = "active" | "canceled" | "expired" | "grace" | "on_hold" | "revoked";

export type VerifiedPlaySubscription = {
  packageName: string;
  productId: string;
  status: PlaySubscriptionStatus;
  expiresAt?: string;
};

export interface GooglePlayPurchaseVerifier {
  verifySubscription(input: { packageName: string; purchaseToken: string }): Promise<VerifiedPlaySubscription>;
}

export interface PubSubPushAuthenticator {
  verify(request: Request): Promise<boolean>;
}

export interface BillingStateStore {
  userForTokenHash(tokenHash: string): Promise<string | null>;
  bindTokenHash(userId: string, tokenHash: string): Promise<"bound" | "already_bound" | "conflict">;
  setBillingEntitlement(userId: string, entitlement: Entitlement): Promise<void>;
}

export type BillingServerEnv = GooglePlayVerifierEnv & PubSubPushAuthEnv & DurableMembershipEnv & {
  PLAY_PACKAGE_NAME?: string;
  PLAY_SUBSCRIPTION_PRODUCT_IDS?: string;
  PLAY_PURCHASE_VERIFIER?: GooglePlayPurchaseVerifier;
  PLAY_BILLING_STATE_STORE?: BillingStateStore;
  PUBSUB_PUSH_AUTHENTICATOR?: PubSubPushAuthenticator;
  RTDN_DEDUP_STORE?: RtdnDedupStore;
};

export type BillingResult = { status: number; body: Record<string, unknown> };

type ParsedPlayRtdn =
  | { kind: "test"; packageName: string; messageId: string }
  | { kind: "subscription"; packageName: string; purchaseToken: string; messageId: string };

export async function restorePlayPurchase(user: AuthenticatedUser, body: unknown, env: BillingServerEnv): Promise<BillingResult> {
  const config = configuredBilling(env);
  if (!config) return result(503, "billing_not_configured");
  const parsed = parseRestoreBody(body);
  if (!parsed) return result(400, "invalid_purchase");
  if (parsed.packageName !== config.packageName || !config.productIds.has(parsed.productId)) return result(400, "purchase_not_allowed");

  const tokenHash = await sha256(parsed.purchaseToken);
  const existingUser = await config.store.userForTokenHash(tokenHash);
  if (existingUser && existingUser !== user.id) return result(409, "purchase_already_linked");

  const verified = await config.verifier.verifySubscription({ packageName: config.packageName, purchaseToken: parsed.purchaseToken });
  if (verified.packageName !== config.packageName || verified.productId !== parsed.productId || !config.productIds.has(verified.productId)) return result(400, "purchase_mismatch");

  const binding = await config.store.bindTokenHash(user.id, tokenHash);
  if (binding === "conflict") return result(409, "purchase_already_linked");
  const entitlement = entitlementFor(verified);
  await config.store.setBillingEntitlement(user.id, entitlement);
  return { status: 200, body: { entitlement, subscription: publicSubscription(verified) } };
}

export async function processPlayRtdn(request: Request, env: BillingServerEnv): Promise<BillingResult> {
  const config = configuredBilling(env);
  const authenticator = env.PUBSUB_PUSH_AUTHENTICATOR ?? createConfiguredPubSubPushAuthenticator(env);
  const dedup = env.RTDN_DEDUP_STORE ?? createConfiguredD1RtdnDedupStore(env);
  if (!config || !authenticator || !dedup) return result(503, "billing_not_configured");
  if (!(await authenticator.verify(request))) return result(401, "unauthorized");
  const notification = await parseRtdn(request);
  if (!notification) return result(400, "invalid_rtdn");
  if (notification.packageName !== config.packageName) return result(400, "package_mismatch");
  if (notification.kind === "test") return { status: 204, body: {} };

  const tokenHash = await sha256(notification.purchaseToken);
  const userId = await config.store.userForTokenHash(tokenHash);
  if (!userId) return { status: 204, body: {} };

  const claimed = await dedup.claim(notification.messageId);
  if (!claimed) return { status: 204, body: {} };

  try {
    const verified = await config.verifier.verifySubscription({ packageName: config.packageName, purchaseToken: notification.purchaseToken });
    if (verified.packageName !== config.packageName || !config.productIds.has(verified.productId)) return result(400, "purchase_mismatch");
    await config.store.setBillingEntitlement(userId, entitlementFor(verified));
    return { status: 204, body: {} };
  } catch (error) {
    // Do not convert a transient verifier/storage failure into a permanently acknowledged duplicate.
    await dedup.release(notification.messageId);
    throw error;
  }
}

export function entitlementFor(subscription: VerifiedPlaySubscription): Entitlement {
  const pro = subscription.status === "active" || subscription.status === "grace" || subscription.status === "canceled";
  return { plan: pro ? "PRO" : "FREE", source: "billing", expiresAt: subscription.expiresAt };
}

export class InMemoryBillingStateStore implements BillingStateStore {
  readonly entitlements = new Map<string, Entitlement>();
  private readonly bindings = new Map<string, string>();
  async userForTokenHash(tokenHash: string): Promise<string | null> { return this.bindings.get(tokenHash) ?? null; }
  async bindTokenHash(userId: string, tokenHash: string): Promise<"bound" | "already_bound" | "conflict"> {
    const existing = this.bindings.get(tokenHash);
    if (existing === userId) return "already_bound";
    if (existing) return "conflict";
    this.bindings.set(tokenHash, userId);
    return "bound";
  }
  async setBillingEntitlement(userId: string, entitlement: Entitlement): Promise<void> { this.entitlements.set(userId, entitlement); }
}

export class FakeGooglePlayPurchaseVerifier implements GooglePlayPurchaseVerifier {
  calls = 0;
  constructor(private readonly response: VerifiedPlaySubscription) {}
  async verifySubscription(): Promise<VerifiedPlaySubscription> { this.calls += 1; return this.response; }
}

export class FakePubSubPushAuthenticator implements PubSubPushAuthenticator {
  constructor(private readonly allowed = true) {}
  async verify(_request: Request): Promise<boolean> { return this.allowed; }
}

function configuredBilling(env: BillingServerEnv) {
  const packageName = env.PLAY_PACKAGE_NAME?.trim();
  const productIds = new Set((env.PLAY_SUBSCRIPTION_PRODUCT_IDS ?? "").split(",").map((v) => v.trim()).filter(Boolean));
  const verifier = env.PLAY_PURCHASE_VERIFIER ?? createConfiguredGooglePlayPurchaseVerifier(env);
  const store = env.PLAY_BILLING_STATE_STORE ?? createConfiguredD1BillingStateStore(env);
  return packageName && productIds.size && verifier && store ? { packageName, productIds, verifier, store } : null;
}

function parseRestoreBody(value: unknown): { packageName: string; productId: string; purchaseToken: string } | null {
  if (!value || typeof value !== "object") return null;
  const body = value as Record<string, unknown>;
  const packageName = typeof body.packageName === "string" ? body.packageName.trim() : "";
  const productId = typeof body.productId === "string" ? body.productId.trim() : "";
  const purchaseToken = typeof body.purchaseToken === "string" ? body.purchaseToken.trim() : "";
  if (!packageName || !productId || !purchaseToken || packageName.length > 200 || productId.length > 200 || purchaseToken.length > 4096) return null;
  return { packageName, productId, purchaseToken };
}

async function parseRtdn(request: Request): Promise<ParsedPlayRtdn | null> {
  try {
    const envelope = await request.json() as { message?: { data?: string; messageId?: string } };
    const data = envelope.message?.data;
    const messageId = envelope.message?.messageId?.trim();
    if (!data || !messageId || messageId.length > 512) return null;
    const decoded = JSON.parse(decodeBase64Utf8(data)) as {
      packageName?: string;
      subscriptionNotification?: { purchaseToken?: string } | null;
      testNotification?: unknown;
    };
    const packageName = decoded.packageName?.trim();
    if (!packageName || packageName.length > 200) return null;

    const purchaseToken = decoded.subscriptionNotification?.purchaseToken?.trim();
    const isTest = decoded.testNotification !== undefined && decoded.testNotification !== null;
    if (isTest && purchaseToken) return null;
    if (isTest) return { kind: "test", packageName, messageId };
    if (!purchaseToken || purchaseToken.length > 4096) return null;
    return { kind: "subscription", packageName, purchaseToken, messageId };
  } catch { return null; }
}

function decodeBase64Utf8(value: string): string {
  const binary = atob(value);
  const bytes = Uint8Array.from(binary, (ch) => ch.charCodeAt(0));
  return new TextDecoder().decode(bytes);
}

async function sha256(value: string): Promise<string> {
  const digest = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(value));
  return Array.from(new Uint8Array(digest), (byte) => byte.toString(16).padStart(2, "0")).join("");
}

function publicSubscription(value: VerifiedPlaySubscription) { return { productId: value.productId, status: value.status, expiresAt: value.expiresAt }; }
function result(status: number, error: string): BillingResult { return { status, body: { error } }; }

import type { VerifiedGoogleIdentity } from "./googleIdentity";

export type GoogleIdentityResolution = {
  userId: string;
  created: boolean;
};

export type GoogleIdentityRecord = {
  provider: "google";
  providerSubject: string;
  userId: string;
  email: string;
  displayName?: string;
  pictureUrl?: string;
};

export interface AccountIdentityStore {
  resolveOrCreateGoogleIdentity(input: GoogleIdentityRecord): Promise<GoogleIdentityResolution>;
  linkGoogleIdentityToUser(input: GoogleIdentityRecord): Promise<GoogleIdentityResolution>;
}

export class AccountIdentityService {
  constructor(
    private readonly store: AccountIdentityStore,
    private readonly newUserId: () => string = () => crypto.randomUUID()
  ) {}

  async resolveGoogle(identity: VerifiedGoogleIdentity): Promise<GoogleIdentityResolution> {
    const candidateUserId = this.newUserId();
    validateInternalUserId(candidateUserId, "invalid_generated_user_id");
    return this.store.resolveOrCreateGoogleIdentity(recordFor(candidateUserId, identity));
  }

  async linkGoogleToExistingUser(
    userId: string,
    identity: VerifiedGoogleIdentity
  ): Promise<GoogleIdentityResolution> {
    validateInternalUserId(userId, "invalid_existing_user_id");
    return this.store.linkGoogleIdentityToUser(recordFor(userId, identity));
  }
}

export class InMemoryAccountIdentityStore implements AccountIdentityStore {
  private readonly identities = new Map<string, GoogleIdentityRecord>();

  async resolveOrCreateGoogleIdentity(input: GoogleIdentityRecord): Promise<GoogleIdentityResolution> {
    const key = identityKey(input.provider, input.providerSubject);
    const existing = this.identities.get(key);
    if (existing) {
      this.identities.set(key, refreshedRecord(existing, input));
      return { userId: existing.userId, created: false };
    }

    this.identities.set(key, { ...input });
    return { userId: input.userId, created: true };
  }

  async linkGoogleIdentityToUser(input: GoogleIdentityRecord): Promise<GoogleIdentityResolution> {
    const key = identityKey(input.provider, input.providerSubject);
    const existing = this.identities.get(key);
    if (existing && existing.userId !== input.userId) throw new Error("identity_conflict");
    if (existing) {
      this.identities.set(key, refreshedRecord(existing, input));
      return { userId: existing.userId, created: false };
    }

    this.identities.set(key, { ...input });
    return { userId: input.userId, created: true };
  }

  identityForGoogleSubject(providerSubject: string): GoogleIdentityRecord | undefined {
    const record = this.identities.get(identityKey("google", providerSubject));
    return record ? { ...record } : undefined;
  }
}

function recordFor(userId: string, identity: VerifiedGoogleIdentity): GoogleIdentityRecord {
  return {
    provider: "google",
    providerSubject: identity.subject,
    userId,
    email: identity.email,
    ...(identity.displayName ? { displayName: identity.displayName } : {}),
    ...(identity.pictureUrl ? { pictureUrl: identity.pictureUrl } : {})
  };
}

function refreshedRecord(existing: GoogleIdentityRecord, input: GoogleIdentityRecord): GoogleIdentityRecord {
  return {
    ...existing,
    email: input.email,
    displayName: input.displayName,
    pictureUrl: input.pictureUrl
  };
}

function validateInternalUserId(userId: string, errorCode: string): void {
  if (!userId || userId.length > 128 || /[\u0000-\u001F\u007F]/.test(userId)) {
    throw new Error(errorCode);
  }
}

function identityKey(provider: "google", providerSubject: string): string {
  return `${provider}\u0000${providerSubject}`;
}

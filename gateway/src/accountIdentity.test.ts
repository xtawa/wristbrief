import { describe, expect, it } from "vitest";
import { AccountIdentityService, InMemoryAccountIdentityStore } from "./accountIdentity";
import type { VerifiedGoogleIdentity } from "./googleIdentity";

function googleIdentity(subject: string, email: string): VerifiedGoogleIdentity {
  return { subject, email, emailVerified: true };
}

describe("Google account identity resolution", () => {
  it("uses Google sub rather than email as the durable identity key", async () => {
    const store = new InMemoryAccountIdentityStore();
    const ids = ["internal-user-a", "internal-user-b", "internal-user-c"];
    let index = 0;
    const service = new AccountIdentityService(store, () => ids[index++]!);

    const first = await service.resolveGoogle(googleIdentity("google-sub-a", "first@example.com"));
    const sameGoogleAccount = await service.resolveGoogle(googleIdentity("google-sub-a", "renamed@example.com"));
    const differentGoogleAccountSameEmail = await service.resolveGoogle(googleIdentity("google-sub-b", "renamed@example.com"));

    expect(first).toEqual({ userId: "internal-user-a", created: true });
    expect(sameGoogleAccount).toEqual({ userId: "internal-user-a", created: false });
    expect(differentGoogleAccountSameEmail).toEqual({ userId: "internal-user-c", created: true });
    expect(store.identityForGoogleSubject("google-sub-a")?.email).toBe("renamed@example.com");
  });

  it("generates the internal user id on the server-side boundary", async () => {
    const store = new InMemoryAccountIdentityStore();
    const service = new AccountIdentityService(store, () => "server-generated-user-id");

    const resolved = await service.resolveGoogle(googleIdentity("1234567890", "person@example.com"));
    expect(resolved.userId).toBe("server-generated-user-id");
    expect(resolved.userId).not.toBe("1234567890");
    expect(resolved.userId).not.toBe("person@example.com");
  });

  it("rejects an invalid generated user id instead of persisting it", async () => {
    const service = new AccountIdentityService(new InMemoryAccountIdentityStore(), () => "bad\nuser");
    await expect(service.resolveGoogle(googleIdentity("123", "person@example.com"))).rejects.toThrow("invalid_generated_user_id");
  });

  it("links a first Google identity to the authenticated legacy user id without moving account state", async () => {
    const store = new InMemoryAccountIdentityStore();
    const service = new AccountIdentityService(store, () => "unused-new-user");

    const linked = await service.linkGoogleToExistingUser(
      "legacy-user-1",
      googleIdentity("google-sub-link", "person@example.com")
    );

    expect(linked).toEqual({ userId: "legacy-user-1", created: true });
    expect(store.identityForGoogleSubject("google-sub-link")?.userId).toBe("legacy-user-1");
  });

  it("makes repeated legacy linking idempotent and rejects a Google sub owned by another user", async () => {
    const store = new InMemoryAccountIdentityStore();
    const service = new AccountIdentityService(store, () => "fresh-google-user");
    const verified = googleIdentity("google-sub-conflict", "person@example.com");

    await expect(service.linkGoogleToExistingUser("legacy-user-1", verified)).resolves.toEqual({
      userId: "legacy-user-1",
      created: true
    });
    await expect(service.linkGoogleToExistingUser("legacy-user-1", verified)).resolves.toEqual({
      userId: "legacy-user-1",
      created: false
    });
    await expect(service.linkGoogleToExistingUser("legacy-user-2", verified)).rejects.toThrow("identity_conflict");
  });
});

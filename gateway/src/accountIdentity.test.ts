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
});

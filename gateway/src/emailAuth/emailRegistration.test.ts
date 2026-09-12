// @ts-nocheck
import { describe, expect, it } from "vitest";
import { createEmailAuthTestSetup } from "./emailAuthTestSetup";
import { normalizeEmail, passwordPolicyError } from "./emailAuthService";

describe("email registration", () => {
  it("creates the first admin with an email identity keyed by credential id", async () => {
    const setup = createEmailAuthTestSetup();
    const result = await setup.service.register("First@Example.COM ", "Password12345");
    expect(result.status).toBe(200);
    expect(result.body.firstAdmin).toBe(true);
    expect(result.body.sessionToken).toMatch(/^wbs_[A-Za-z0-9_-]{43}$/);

    const identity = await setup.d1
      .prepare("SELECT provider, provider_subject, user_id, email FROM identities WHERE provider = 'email'")
      .first();
    const credential = await setup.d1
      .prepare("SELECT id, user_id, normalized_email, password_algo FROM email_credentials")
      .first();
    expect(identity.provider).toBe("email");
    expect(identity.email).toBe("first@example.com");
    // provider_subject is the credential id, never the email string.
    expect(identity.provider_subject).toBe(credential.id);
    expect(credential.normalized_email).toBe("first@example.com");
    expect(credential.password_algo).toBe("argon2id");
    expect(await setup.adminStore.rolesForUser(identity.user_id)).toEqual(["admin"]);
  });

  it("closes registration after bootstrap until an admin reopens it", async () => {
    const setup = createEmailAuthTestSetup();
    await setup.service.register("admin@example.com", "Password12345");

    const blocked = await setup.service.register("other@example.com", "Password123456");
    expect(blocked.status).toBe(403);
    expect(blocked.body.error).toBe("registration_closed");

    await setup.adminStore.writeRegistrationMode("OPEN", null);
    const allowed = await setup.service.register("other@example.com", "Password123456");
    expect(allowed.status).toBe(200);
    expect(allowed.body.firstAdmin).toBe(false);
    const identity = await setup.d1
      .prepare("SELECT user_id FROM identities WHERE provider = 'email' AND email = 'other@example.com'")
      .first();
    expect(await setup.adminStore.rolesForUser(identity.user_id)).toEqual([]);
  });

  it("rejects duplicate emails, weak passwords, and invalid emails", async () => {
    const setup = createEmailAuthTestSetup();
    await setup.service.register("admin@example.com", "Password12345");

    expect((await setup.service.register("admin@example.com", "Password123456")).body.error).toBe("email_already_registered");
    expect((await setup.service.register("new@example.com", "short1")).body.error).toBe("weak_password");
    expect((await setup.service.register("new@example.com", "no-digit-password")).body.error).toBe("weak_password");
    // Policy passes but registration is closed after bootstrap.
    expect((await setup.service.register("new@example.com", "Password12345")).body.error).toBe("registration_closed");
    expect((await setup.service.register("not-an-email", "Password12345")).body.error).toBe("invalid_email");
    expect((await setup.service.register(null, "Password12345")).body.error).toBe("invalid_email");
  });
});

describe("email normalization and password policy", () => {
  it("normalizes conservatively without alias guessing", () => {
    expect(normalizeEmail(" User@Example.COM ")).toBe("user@example.com");
    expect(normalizeEmail("first.last+news@gmail.com")).toBe("first.last+news@gmail.com");
    expect(normalizeEmail("a@b")).toBeNull();
    expect(normalizeEmail("a b@c.com")).toBeNull();
    expect(normalizeEmail(42)).toBeNull();
  });

  it("enforces length, character class, and email equality", () => {
    expect(passwordPolicyError("Password12345", "user@example.com")).toBeNull();
    expect(passwordPolicyError("short1a", "user@example.com")).toBe("weak_password");
    expect(passwordPolicyError("a".repeat(300) + "1", "user@example.com")).toBe("weak_password");
    expect(passwordPolicyError("nodigits", "user@example.com")).toBe("weak_password");
    expect(passwordPolicyError("user@example.com", "user@example.com")).toBe("weak_password");
    expect(passwordPolicyError(undefined, "user@example.com")).toBe("weak_password");
  });
});

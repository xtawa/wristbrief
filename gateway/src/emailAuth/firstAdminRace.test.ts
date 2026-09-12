// @ts-nocheck
import { describe, expect, it } from "vitest";
import { createEmailAuthTestSetup } from "./emailAuthTestSetup";

describe("first-admin bootstrap race", () => {
  it("produces exactly one admin from 25 concurrent registrations", async () => {
    const setup = createEmailAuthTestSetup();
    const results = await Promise.all(
      Array.from({ length: 25 }, (_, i) => setup.service.register(`racer${i}@example.com`, "Password12345"))
    );

    const winners = results.filter((r) => r.status === 200);
    expect(winners).toHaveLength(1);
    expect(winners[0].body.firstAdmin).toBe(true);
    expect(typeof winners[0].body.sessionToken).toBe("string");

    for (const loser of results.filter((r) => r.status !== 200)) {
      expect(loser.status).toBe(403);
      expect(loser.body.error).toBe("registration_closed");
    }

    // Exactly one admin, one user, one credential, one email identity.
    expect(await setup.d1.prepare("SELECT COUNT(*) AS c FROM user_roles WHERE role = 'admin'").first()).toEqual({ c: 1 });
    expect(await setup.d1.prepare("SELECT COUNT(*) AS c FROM users").first()).toEqual({ c: 1 });
    expect(await setup.d1.prepare("SELECT COUNT(*) AS c FROM email_credentials").first()).toEqual({ c: 1 });
    expect(await setup.d1.prepare("SELECT COUNT(*) AS c FROM identities WHERE provider = 'email'").first()).toEqual({ c: 1 });

    const bootstrap = await setup.adminStore.readBootstrapState();
    expect(bootstrap.firstAdminUserId).not.toBeNull();
    expect(bootstrap.webRegistrationEnabled).toBe(false);
    expect(bootstrap.bootstrapCompletedAt).not.toBeNull();
  });

  it("same-email concurrent registrations still yield exactly one account", async () => {
    const setup = createEmailAuthTestSetup();
    const results = await Promise.all(
      Array.from({ length: 12 }, () => setup.service.register("same@example.com", "Password12345"))
    );
    expect(results.filter((r) => r.status === 200)).toHaveLength(1);
    expect(await setup.d1.prepare("SELECT COUNT(*) AS c FROM email_credentials").first()).toEqual({ c: 1 });
  });

  it("cannot re-enter the bootstrap branch after completion", async () => {
    const setup = createEmailAuthTestSetup();
    const first = await setup.service.register("first@example.com", "Password12345");
    expect(first.status).toBe(200);

    // Even though registration mode is flipped OPEN by an admin, the next
    // registrant is an ordinary user and never becomes first admin.
    await setup.adminStore.writeRegistrationMode("OPEN", null);
    const second = await setup.service.register("second@example.com", "Password123456");
    expect(second.status).toBe(200);
    expect(second.body.firstAdmin).toBe(false);
    expect(await setup.d1.prepare("SELECT COUNT(*) AS c FROM user_roles WHERE role = 'admin'").first()).toEqual({ c: 1 });

    const bootstrap = await setup.adminStore.readBootstrapState();
    expect(bootstrap.firstAdminUserId).not.toBeNull();
  });
});

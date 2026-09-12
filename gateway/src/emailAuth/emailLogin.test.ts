// @ts-nocheck
import { describe, expect, it } from "vitest";
import { createEmailAuthTestSetup, tokenFromEmail } from "./emailAuthTestSetup";

describe("email login and verification", () => {
  it("logs in with correct credentials and reports verification state", async () => {
    const setup = createEmailAuthTestSetup();
    await setup.service.register("user@example.com", "Password12345");

    const badPassword = await setup.service.login("user@example.com", "WrongPassword1");
    expect(badPassword.status).toBe(401);
    expect(badPassword.body.error).toBe("invalid_credentials");

    const unknownEmail = await setup.service.login("nobody@example.com", "Password12345");
    expect(unknownEmail.status).toBe(401);
    expect(unknownEmail.body.error).toBe("invalid_credentials");

    const login = await setup.service.login("USER@example.com ", "Password12345");
    expect(login.status).toBe(200);
    expect(login.body.sessionToken).toMatch(/^wbs_[A-Za-z0-9_-]{43}$/);
    expect(login.body.emailVerified).toBe(false);
  });

  it("completes the verification round trip exactly once", async () => {
    const setup = createEmailAuthTestSetup();
    await setup.service.register("user@example.com", "Password12345");

    const request = await setup.service.requestVerification("user@example.com");
    expect(request.status).toBe(202);
    expect(setup.emailSender.sent).toHaveLength(1);
    expect(setup.emailSender.sent[0].text).toContain("verify/confirm?token=");

    const token = tokenFromEmail(setup.emailSender, "verify/confirm");
    expect(token).toBeTruthy();

    const confirm = await setup.service.confirmVerification(token);
    expect(confirm.status).toBe(200);
    expect(confirm.body.verified).toBe(true);

    const replay = await setup.service.confirmVerification(token);
    expect(replay.status).toBe(400);
    expect(replay.body.error).toBe("invalid_or_expired_token");

    const login = await setup.service.login("user@example.com", "Password12345");
    expect(login.body.emailVerified).toBe(true);

    // Already verified: no further verification emails.
    const again = await setup.service.requestVerification("user@example.com");
    expect(again.status).toBe(202);
    expect(setup.emailSender.sent).toHaveLength(1);
  });

  it("returns uniform 202 for unknown emails without sending", async () => {
    const setup = createEmailAuthTestSetup();
    const result = await setup.service.requestVerification("ghost@example.com");
    expect(result.status).toBe(202);
    expect(setup.emailSender.sent).toHaveLength(0);
  });
});

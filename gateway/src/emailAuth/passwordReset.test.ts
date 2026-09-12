// @ts-nocheck
import { describe, expect, it } from "vitest";
import { createEmailAuthTestSetup, tokenFromEmail } from "./emailAuthTestSetup";

describe("password reset", () => {
  it("returns uniform 202 for forgot regardless of email existence", async () => {
    const setup = createEmailAuthTestSetup();
    await setup.service.register("user@example.com", "Password12345");

    const known = await setup.service.forgotPassword("user@example.com");
    const unknown = await setup.service.forgotPassword("ghost@example.com");
    expect(known.status).toBe(202);
    expect(unknown.status).toBe(202);
    expect(setup.emailSender.sent).toHaveLength(1);
  });

  it("resets the password, revokes all sessions, and invalidates old tokens", async () => {
    const setup = createEmailAuthTestSetup();
    await setup.service.register("user@example.com", "Password12345");
    const oldSession = await setup.service.login("user@example.com", "Password12345");
    const userId = oldSession.body.user.id;

    await setup.service.forgotPassword("user@example.com");
    const token = tokenFromEmail(setup.emailSender, "password/reset");
    expect(token).toBeTruthy();

    const weak = await setup.service.resetPassword(token, "short1");
    expect(weak.body.error).toBe("weak_password");
    // A failed policy check does not consume the token.
    const reset = await setup.service.resetPassword(token, "NewPassword678");
    expect(reset.status).toBe(200);
    expect(reset.body.reset).toBe(true);

    // Old sessions revoked by the completed reset (sessions live in D1).
    const sessions = await setup.d1
      .prepare("SELECT revoked_at FROM sessions WHERE user_id = ?")
      .bind(userId)
      .all();
    expect(sessions.results.length).toBeGreaterThan(0);
    expect(sessions.results.every((row) => row.revoked_at)).toBe(true);

    // Old password no longer works, new one does.
    expect((await setup.service.login("user@example.com", "Password12345")).status).toBe(401);
    const fresh = await setup.service.login("user@example.com", "NewPassword678");
    expect(fresh.status).toBe(200);

    // Token cannot be reused.
    expect((await setup.service.resetPassword(token, "AnotherPassword9")).body.error).toBe("invalid_or_expired_token");

    // Security notification sent.
    expect(setup.emailSender.sent.some((sent) => sent.subject.includes("was changed"))).toBe(true);
    // Security event audited.
    const audit = await setup.d1.prepare("SELECT action FROM admin_audit_log ORDER BY created_at DESC").all();
    expect(audit.results.map((row) => row.action)).toContain("password_reset_completed");
  });

  it("only the newest reset token works", async () => {
    const setup = createEmailAuthTestSetup();
    await setup.service.register("user@example.com", "Password12345");

    await setup.service.forgotPassword("user@example.com");
    const firstToken = tokenFromEmail(setup.emailSender, "password/reset");
    await setup.service.forgotPassword("user@example.com");
    const secondToken = tokenFromEmail(setup.emailSender, "password/reset");
    expect(firstToken).not.toBe(secondToken);

    expect((await setup.service.resetPassword(firstToken, "NewPassword678")).body.error).toBe("invalid_or_expired_token");
    expect((await setup.service.resetPassword(secondToken, "NewPassword678")).status).toBe(200);
  });
});

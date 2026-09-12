// @ts-nocheck
import { describe, expect, it } from "vitest";
import { createMigratedTestDb } from "../testDbHelper";
import { AdminAuditLog } from "./adminAudit";

describe("admin audit log", () => {
  it("records and lists entries newest-first", async () => {
    const { d1 } = createMigratedTestDb();
    const audit = new AdminAuditLog(d1);
    await audit.record({ actorUserId: "u-1", action: "registration_mode_changed", targetType: "system_setting", targetId: "registration_mode", before: { registrationMode: "CLOSED" }, after: { registrationMode: "OPEN" }, requestId: "req-1" });
    await audit.record({ actorUserId: "u-2", action: "admin_session_created", targetType: "admin_web_session" });

    const entries = await audit.list(10);
    expect(entries).toHaveLength(2);
    expect(entries[0].action).toBe("admin_session_created");
    expect(entries[1].action).toBe("registration_mode_changed");
    expect(entries[1].beforeJson).toBe('{"registrationMode":"CLOSED"}');
    expect(entries[1].afterJson).toBe('{"registrationMode":"OPEN"}');
    expect(entries[1].requestId).toBe("req-1");
  });

  it("refuses to serialize credential-shaped keys", async () => {
    const { d1 } = createMigratedTestDb();
    const audit = new AdminAuditLog(d1);
    await expect(
      audit.record({ action: "provider_updated", after: { api_key: "sk-live-123" } })
    ).rejects.toThrow(/audit_payload_refused/);
    await expect(
      audit.record({ action: "provider_updated", after: { secret_value: "x" } })
    ).rejects.toThrow(/audit_payload_refused/);
    await expect(
      audit.record({ action: "provider_updated", after: { password_hash: "x" } })
    ).rejects.toThrow(/audit_payload_refused/);

    // Secret-free metadata like a secret version reference is accepted.
    await expect(
      audit.record({ action: "provider_updated", after: { secret_version: "V2" } })
    ).resolves.toBeUndefined();
  });
});

// @ts-nocheck
import { createMigratedTestDb } from "../testDbHelper";
import { AccountSessionService } from "../accountSession";
import { D1AccountSessionStore } from "../d1AccountStore";
import { D1AdminStore } from "../admin/adminStore";
import { AdminAuditLog } from "../admin/adminAudit";
import { EmailAuthService } from "./emailAuthService";
import { D1EmailCredentialStore, D1EmailTokenStore } from "./emailCredentialStore";
import { InMemoryEmailSender } from "./emailSender";

export function createEmailAuthTestSetup() {
  const { db, d1 } = createMigratedTestDb();
  const emailSender = new InMemoryEmailSender();
  // Sessions go through the real D1 store so revocations issued via SQL
  // (password reset revokes all sessions) are observable in the same db.
  const sessionStore = new D1AccountSessionStore(d1);
  const adminStore = new D1AdminStore(d1);
  const service = new EmailAuthService({
    db: d1,
    credentialStore: new D1EmailCredentialStore(d1),
    tokenStore: new D1EmailTokenStore(d1),
    sessions: new AccountSessionService(sessionStore),
    emailSender,
    adminStore,
    audit: new AdminAuditLog(d1)
  });
  return { db, d1, service, emailSender, sessionStore, adminStore };
}

export function tokenFromEmail(emailSender: InMemoryEmailSender, marker: string): string | null {
  const email = [...emailSender.sent].reverse().find((sent) => sent.text.includes(marker));
  if (!email) return null;
  const match = new RegExp(`[?&]token=([A-Za-z0-9_-]+)`).exec(email.text);
  return match?.[1] ?? null;
}

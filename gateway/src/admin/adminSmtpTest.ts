/**
 * Real SMTP test send for the /admin/smtp page.
 *
 * This is the one control that can prove email delivery works, so it must never
 * report a success it did not observe. Three outcomes are distinguished:
 *
 *  - `not_configured`: no SMTP settings are saved, so nothing was attempted;
 *  - `sent`: the transport accepted the message for the recipient;
 *  - `send_failed`: the transport rejected it, reported with the transport's
 *    error *class* (e.g. `EAUTH`, `ECONNECTION`) and never the driver message,
 *    which can echo the account name.
 *
 * Abuse limits: the recipient must be the administrator's own address or an
 * address the deployment explicitly allowlists (`ADMIN_SMTP_TEST_RECIPIENTS`),
 * and each admin is limited to a few attempts per window. The stored history and
 * the audit entry carry the recipient, the outcome and the error class — never
 * the SMTP password, never the username, never the message body.
 */
import type { AdminAuditLog } from "./adminAudit";
import { EMAIL_FORMAT, invalidRequest, type FieldError } from "./adminValidation";
import { createConfiguredRateLimiter, type RateLimitRule } from "../rateLimit";

export type SmtpTestStatus = "sent" | "not_configured" | "send_failed";

export const ADMIN_SMTP_TEST_RATE_LIMIT: RateLimitRule = {
  name: "admin-smtp-test",
  max: 3,
  windowSeconds: 900
};

export type SmtpTestSettings = {
  smtpStatus(): Record<string, unknown>;
  send?(email: { to: string; subject: string; text: string }): Promise<void>;
};

export type SmtpTestEnv = {
  ACCOUNT_DB?: D1Database;
  /** Extra addresses an operator may test against, comma separated. */
  ADMIN_SMTP_TEST_RECIPIENTS?: string;
};

export type SmtpTestDeps = {
  env: SmtpTestEnv;
  settings: SmtpTestSettings | undefined;
  actorUserId: string;
  actorEmail: string;
  requestedRecipient: unknown;
  audit: AdminAuditLog;
  requestId: string;
};

export type SmtpTestOutcome = {
  status: number;
  body: Record<string, unknown>;
  /** Present when a row was written to admin_smtp_tests. */
  record?: { status: SmtpTestStatus; recipient: string; errorClass: string | null };
};

export function smtpTestAllowlist(env: SmtpTestEnv, actorEmail: string): string[] {
  const entries = [actorEmail, ...(env.ADMIN_SMTP_TEST_RECIPIENTS ?? "").split(",")]
    .map((entry) => entry.trim().toLowerCase())
    .filter((entry) => EMAIL_FORMAT.test(entry));
  return [...new Set(entries)];
}

export async function runSmtpTest(deps: SmtpTestDeps): Promise<SmtpTestOutcome> {
  const { env, settings, actorUserId, requestId, audit } = deps;
  if (!settings) {
    return { status: 503, body: { error: "settings_unavailable" } };
  }

  const allowlist = smtpTestAllowlist(env, deps.actorEmail);
  if (!allowlist.length) {
    // Without a resolvable administrator address there is no safe default target.
    return { status: 503, body: { error: "admin_email_unknown" } };
  }

  const requested = typeof deps.requestedRecipient === "string" ? deps.requestedRecipient.trim().toLowerCase() : "";
  const recipient = requested || allowlist[0]!;
  const fields: FieldError[] = [];
  if (!EMAIL_FORMAT.test(recipient)) {
    fields.push({ field: "recipient", reason: "invalid_format", format: "email address" });
  } else if (!allowlist.includes(recipient)) {
    fields.push({
      field: "recipient",
      reason: "not_allowed",
      hint: "Test sends are limited to the administrator's own address unless the deployment allowlists more."
    });
  }
  if (fields.length) return { status: 400, body: { ...invalidRequest(fields) } };

  const status = settings.smtpStatus();
  const configured = status?.configured === true;
  const checkedAt = new Date().toISOString();

  if (!configured) {
    await writeRecord(env, { status: "not_configured", recipient, errorClass: null, actorUserId });
    await audit.record({
      actorUserId,
      action: "smtp_test_not_configured",
      targetType: "system_setting",
      targetId: "smtp",
      after: { result: "not_configured", recipient },
      requestId
    });
    return {
      status: 409,
      body: {
        error: "smtp_not_configured",
        result: "not_configured",
        message: "No SMTP settings are saved yet; save them before sending a test message.",
        recipient,
        checkedAt
      },
      record: { status: "not_configured", recipient, errorClass: null }
    };
  }

  if (typeof settings.send !== "function") {
    return { status: 503, body: { error: "settings_unavailable", message: "This deployment cannot send mail." } };
  }

  const limiter = createConfiguredRateLimiter(env, ADMIN_SMTP_TEST_RATE_LIMIT);
  const allowed = await limiter.check(`${ADMIN_SMTP_TEST_RATE_LIMIT.name}:user:${actorUserId}`);
  if (!allowed.allowed) {
    // No audit row: the attempt changed no state, and auditing it would let an
    // authenticated operator flood the trail.
    return {
      status: 429,
      body: {
        error: "rate_limited",
        message: `Limit of ${ADMIN_SMTP_TEST_RATE_LIMIT.max} test sends per ${Math.round(
          ADMIN_SMTP_TEST_RATE_LIMIT.windowSeconds / 60
        )} minutes reached. Try again later.`,
        retryAfterSeconds: ADMIN_SMTP_TEST_RATE_LIMIT.windowSeconds
      }
    };
  }

  try {
    await settings.send({
      to: recipient,
      subject: "WristBrief admin email test",
      // Deliberately fixed and credential-free: nothing from the saved SMTP
      // configuration is echoed into the delivered message.
      text: `This is a test message sent from the WristBrief admin console at ${checkedAt}.\nNo action is needed.`
    });
  } catch (error) {
    const errorClass = transportErrorClass(error);
    await writeRecord(env, { status: "send_failed", recipient, errorClass, actorUserId });
    await audit.record({
      actorUserId,
      action: "smtp_test_failed",
      targetType: "system_setting",
      targetId: "smtp",
      after: { result: "send_failed", errorClass },
      requestId
    });
    return {
      status: 502,
      body: {
        error: "smtp_send_failed",
        result: "send_failed",
        errorClass,
        message: `The mail server refused the test message (${errorClass}).`,
        recipient,
        checkedAt
      },
      record: { status: "send_failed", recipient, errorClass }
    };
  }

  await writeRecord(env, { status: "sent", recipient, errorClass: null, actorUserId });
  await audit.record({
    actorUserId,
    action: "smtp_test_sent",
    targetType: "system_setting",
    targetId: "smtp",
    after: { result: "sent", recipient },
    requestId
  });
  return {
    status: 200,
    body: {
      result: "sent",
      message: `The mail server accepted a test message for ${recipient}.`,
      recipient,
      checkedAt
    },
    record: { status: "sent", recipient, errorClass: null }
  };
}

/**
 * Transport error class only. Node SMTP drivers expose a stable uppercase code
 * (`EAUTH`, `ECONNECTION`, `ETIMEDOUT`, ...); anything else is reported as a
 * generic class so a driver message (which may name the account) never travels.
 */
export function transportErrorClass(error: unknown): string {
  const code = (error as { code?: unknown } | null)?.code;
  if (typeof code === "string" && /^[A-Z][A-Z0-9_]{1,39}$/.test(code)) return code;
  const name = error instanceof Error ? error.name : "";
  if (/^[A-Za-z][A-Za-z0-9]{1,39}$/.test(name)) return name.replace(/([a-z0-9])([A-Z])/g, "$1_$2").toUpperCase();
  return "SEND_FAILED";
}

async function writeRecord(
  env: SmtpTestEnv,
  input: { status: SmtpTestStatus; recipient: string; errorClass: string | null; actorUserId: string }
): Promise<void> {
  if (!env.ACCOUNT_DB) return;
  await env.ACCOUNT_DB.prepare(
    "INSERT INTO admin_smtp_tests (id, status, recipient, error_class, actor_user_id) VALUES (?, ?, ?, ?, ?)"
  )
    .bind(crypto.randomUUID(), input.status, input.recipient, input.errorClass, input.actorUserId)
    .run();
}

export type SmtpTestHistoryRow = {
  id: string;
  status: string;
  recipient: string;
  errorClass: string | null;
  createdAt: string;
};

export async function readSmtpTestHistory(env: SmtpTestEnv, limit = 5): Promise<SmtpTestHistoryRow[]> {
  if (!env.ACCOUNT_DB) return [];
  const rows = await env.ACCOUNT_DB.prepare(
    "SELECT id, status, recipient, error_class, created_at FROM admin_smtp_tests ORDER BY created_at DESC, id DESC LIMIT ?"
  )
    .bind(Math.min(Math.max(Math.floor(limit), 1), 50))
    .all<{ id: string; status: string; recipient: string; error_class: string | null; created_at: string }>();
  return (rows.results ?? []).map((row) => ({
    id: row.id,
    status: row.status,
    recipient: row.recipient,
    errorClass: row.error_class,
    createdAt: row.created_at
  }));
}

export type OutboundEmail = {
  to: string;
  subject: string;
  text: string;
};

export interface EmailSender {
  send(email: OutboundEmail): Promise<void>;
}

/**
 * Phase A sender: records outbound emails in memory so tests (and the local
 * dev loop) can read verification/reset links. Not for production delivery.
 */
export class InMemoryEmailSender implements EmailSender {
  readonly sent: OutboundEmail[] = [];

  async send(email: OutboundEmail): Promise<void> {
    this.sent.push({ ...email });
  }
}

/**
 * Default sender when no production email provider is configured (none exists in
 * this repo today): outbound mail is dropped. Raw verification/reset tokens are
 * never returned to clients regardless of sender, so unconfigured delivery only
 * means the user cannot receive the link — it never leaks the token.
 */
export class NoopEmailSender implements EmailSender {
  async send(_email?: OutboundEmail): Promise<void> {}
}

export const DEFAULT_RESEND_FROM = "WristBrief <onboarding@resend.dev>";

/**
 * Production email sender using Resend (https://resend.com) HTTP API.
 * Uses native fetch in Cloudflare Workers with zero external dependencies.
 */
export class ResendEmailSender implements EmailSender {
  private readonly apiKey: string;
  private readonly fromEmail: string;
  private readonly fetchFn: typeof fetch;

  constructor(apiKey: string, fromEmail?: string, fetchFn: typeof fetch = fetch) {
    this.apiKey = apiKey;
    this.fromEmail = fromEmail?.trim() ? fromEmail.trim() : DEFAULT_RESEND_FROM;
    this.fetchFn = fetchFn;
  }

  async send(email: OutboundEmail): Promise<void> {
    try {
      const response = await this.fetchFn("https://api.resend.com/emails", {
        method: "POST",
        headers: {
          "Authorization": `Bearer ${this.apiKey}`,
          "Content-Type": "application/json"
        },
        body: JSON.stringify({
          from: this.fromEmail,
          to: [email.to],
          subject: email.subject,
          text: email.text
        })
      });

      if (!response.ok) {
        const errorText = await response.text().catch(() => "");
        console.error(`Resend email delivery failed (${response.status}): ${errorText}`);
      }
    } catch (err) {
      console.error("Resend email delivery network error:", err);
    }
  }
}

export type EmailSenderEnv = {
  EMAIL_SENDER?: EmailSender;
  EMAIL_SENDER_MODE?: string;
  RESEND_API_KEY?: string;
  RESEND_FROM_EMAIL?: string;
};

export function createConfiguredEmailSender(env: EmailSenderEnv): EmailSender {
  if (env.EMAIL_SENDER) return env.EMAIL_SENDER;
  if (env.EMAIL_SENDER_MODE === "test") return new InMemoryEmailSender();
  if (env.RESEND_API_KEY) {
    return new ResendEmailSender(env.RESEND_API_KEY, env.RESEND_FROM_EMAIL);
  }
  return new NoopEmailSender();
}

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
  async send(): Promise<void> {}
}

export type EmailSenderEnv = {
  EMAIL_SENDER?: EmailSender;
  EMAIL_SENDER_MODE?: string;
};

export function createConfiguredEmailSender(env: EmailSenderEnv): EmailSender {
  if (env.EMAIL_SENDER) return env.EMAIL_SENDER;
  if (env.EMAIL_SENDER_MODE === "test") return new InMemoryEmailSender();
  return new NoopEmailSender();
}

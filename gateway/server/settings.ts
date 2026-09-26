import { createCipheriv, createDecipheriv, randomBytes } from "node:crypto";
import nodemailer from "nodemailer";
import type { EmailSender, OutboundEmail } from "../src/emailAuth/emailSender";

export type SmtpConfig = { host: string; port: number; secure: boolean; username: string; password: string; from: string };

export class ServerSettings implements EmailSender {
  private readonly key: Buffer;
  private smtp: SmtpConfig | null = null;

  constructor(private readonly db: D1Database, private readonly runtime: Record<string, unknown>, encodedKey: string) {
    const key = Buffer.from(encodedKey, "base64");
    if (key.length !== 32) throw new Error("WRISTBRIEF_MASTER_KEY must be a base64-encoded 32-byte key");
    this.key = key;
  }

  async load(): Promise<void> {
    const rows = await this.db.prepare("SELECT key, encrypted_value FROM encrypted_settings").all<{ key: string; encrypted_value: string }>();
    for (const row of rows.results) {
      const plain = this.decrypt(row.encrypted_value);
      if (row.key === "smtp") this.smtp = JSON.parse(plain) as SmtpConfig;
      else if (/^AI_PROVIDER_SECRET_([1-9]|10)$/.test(row.key)) this.runtime[row.key] = plain;
    }
  }

  smtpStatus(): Record<string, unknown> {
    if (!this.smtp) return { configured: false };
    const { host, port, secure, username, from } = this.smtp;
    return { configured: true, host, port, secure, username, from, passwordConfigured: !!this.smtp.password };
  }

  async saveSmtp(value: unknown): Promise<boolean> {
    if (!value || typeof value !== "object") return false;
    const input = value as Record<string, unknown>;
    const host = typeof input.host === "string" ? input.host.trim() : "";
    const username = typeof input.username === "string" ? input.username.trim() : "";
    const from = typeof input.from === "string" ? input.from.trim() : "";
    const port = Number(input.port);
    if (!/^[a-z0-9.-]{3,253}$/i.test(host) || !Number.isInteger(port) || port < 1 || port > 65535 ||
        !username || !from.includes("@") || (input.password !== undefined && typeof input.password !== "string")) return false;
    const password = typeof input.password === "string" && input.password ? input.password : this.smtp?.password;
    if (!password) return false;
    const next: SmtpConfig = { host, port, secure: input.secure === true, username, from, password };
    await this.persist("smtp", JSON.stringify(next));
    this.smtp = next;
    return true;
  }

  async setProviderSecret(slot: string, secret: string): Promise<boolean> {
    if (!/^AI_PROVIDER_SECRET_([1-9]|10)$/.test(slot) || !secret || secret.length > 8192) return false;
    await this.persist(slot, secret);
    this.runtime[slot] = secret;
    return true;
  }

  async send(email: OutboundEmail): Promise<void> {
    if (!this.smtp) throw new Error("smtp_not_configured");
    const transport = nodemailer.createTransport({
      host: this.smtp.host, port: this.smtp.port, secure: this.smtp.secure,
      auth: { user: this.smtp.username, pass: this.smtp.password },
      connectionTimeout: 10000, socketTimeout: 15000
    });
    await transport.sendMail({ from: this.smtp.from, to: email.to, subject: email.subject, text: email.text });
    transport.close();
  }

  private async persist(key: string, plain: string): Promise<void> {
    await this.db.prepare("INSERT INTO encrypted_settings (key, encrypted_value) VALUES (?, ?) ON CONFLICT(key) DO UPDATE SET encrypted_value = excluded.encrypted_value, updated_at = CURRENT_TIMESTAMP")
      .bind(key, this.encrypt(plain)).run();
  }

  private encrypt(plain: string): string {
    const iv = randomBytes(12);
    const cipher = createCipheriv("aes-256-gcm", this.key, iv);
    const encrypted = Buffer.concat([cipher.update(plain, "utf8"), cipher.final()]);
    return Buffer.concat([iv, cipher.getAuthTag(), encrypted]).toString("base64");
  }

  private decrypt(encoded: string): string {
    const bytes = Buffer.from(encoded, "base64");
    const decipher = createDecipheriv("aes-256-gcm", this.key, bytes.subarray(0, 12));
    decipher.setAuthTag(bytes.subarray(12, 28));
    return Buffer.concat([decipher.update(bytes.subarray(28)), decipher.final()]).toString("utf8");
  }
}

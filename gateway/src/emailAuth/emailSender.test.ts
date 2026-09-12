import { describe, expect, it, vi } from "vitest";
import {
  createConfiguredEmailSender,
  DEFAULT_RESEND_FROM,
  InMemoryEmailSender,
  NoopEmailSender,
  ResendEmailSender
} from "./emailSender";

describe("emailSender", () => {
  it("InMemoryEmailSender records sent emails", async () => {
    const sender = new InMemoryEmailSender();
    await sender.send({ to: "test@example.com", subject: "Sub", text: "Body" });
    expect(sender.sent).toEqual([{ to: "test@example.com", subject: "Sub", text: "Body" }]);
  });

  it("NoopEmailSender silently accepts emails", async () => {
    const sender = new NoopEmailSender();
    await expect(sender.send({ to: "test@example.com", subject: "Sub", text: "Body" })).resolves.toBeUndefined();
  });

  it("ResendEmailSender posts email payload to api.resend.com with auth headers", async () => {
    let capturedUrl = "";
    let capturedOptions: RequestInit | undefined;
    const mockFetch = (async (url: string | URL | Request, init?: RequestInit) => {
      capturedUrl = String(url);
      capturedOptions = init;
      return new Response(JSON.stringify({ id: "resend_123" }), { status: 200 });
    }) as typeof fetch;

    const sender = new ResendEmailSender("re_test_key_123", "Sender <noreply@wristbrief.com>", mockFetch);
    await sender.send({ to: "user@example.com", subject: "Hello", text: "Test email body" });

    expect(capturedUrl).toBe("https://api.resend.com/emails");
    expect(capturedOptions?.method).toBe("POST");
    expect((capturedOptions?.headers as Record<string, string>)["Authorization"]).toBe("Bearer re_test_key_123");
    expect((capturedOptions?.headers as Record<string, string>)["Content-Type"]).toBe("application/json");
    const body = JSON.parse(capturedOptions?.body as string);
    expect(body).toEqual({
      from: "Sender <noreply@wristbrief.com>",
      to: ["user@example.com"],
      subject: "Hello",
      text: "Test email body"
    });
  });

  it("ResendEmailSender falls back to DEFAULT_RESEND_FROM when from is empty or omitted", async () => {
    let capturedBody: any;
    const mockFetch = (async (_url: string | URL | Request, init?: RequestInit) => {
      capturedBody = JSON.parse(init?.body as string);
      return new Response(JSON.stringify({ id: "resend_123" }), { status: 200 });
    }) as typeof fetch;

    const sender = new ResendEmailSender("re_key", undefined, mockFetch);
    await sender.send({ to: "user@example.com", subject: "Hello", text: "Test" });
    expect(capturedBody.from).toBe(DEFAULT_RESEND_FROM);
  });

  it("ResendEmailSender handles HTTP error without throwing", async () => {
    const mockFetch = (async () => {
      return new Response(JSON.stringify({ statusCode: 422, message: "Validation error" }), { status: 422 });
    }) as typeof fetch;

    const errorSpy = vi.spyOn(console, "error").mockImplementation(() => {});
    const sender = new ResendEmailSender("re_key", undefined, mockFetch);
    await expect(sender.send({ to: "user@example.com", subject: "Hello", text: "Test" })).resolves.toBeUndefined();
    expect(errorSpy).toHaveBeenCalled();
    errorSpy.mockRestore();
  });

  it("ResendEmailSender handles network exception without crashing", async () => {
    const mockFetch = (async () => {
      throw new Error("Network timeout");
    }) as typeof fetch;

    const errorSpy = vi.spyOn(console, "error").mockImplementation(() => {});
    const sender = new ResendEmailSender("re_key", undefined, mockFetch);
    await expect(sender.send({ to: "user@example.com", subject: "Hello", text: "Test" })).resolves.toBeUndefined();
    expect(errorSpy).toHaveBeenCalled();
    errorSpy.mockRestore();
  });

  it("createConfiguredEmailSender resolves the proper sender based on env", () => {
    const customSender = new InMemoryEmailSender();
    expect(createConfiguredEmailSender({ EMAIL_SENDER: customSender })).toBe(customSender);

    expect(createConfiguredEmailSender({ EMAIL_SENDER_MODE: "test" })).toBeInstanceOf(InMemoryEmailSender);

    expect(createConfiguredEmailSender({ RESEND_API_KEY: "re_123" })).toBeInstanceOf(ResendEmailSender);

    expect(createConfiguredEmailSender({})).toBeInstanceOf(NoopEmailSender);
  });
});

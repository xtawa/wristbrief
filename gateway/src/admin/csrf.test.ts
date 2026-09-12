// @ts-nocheck
import { describe, expect, it } from "vitest";
import { verifyCsrfTokenAsync, verifySameOrigin, verifySecFetchSite } from "../admin/adminCsrf";
import { sha256Hex } from "../emailAuth/emailTokens";

describe("admin CSRF helpers", () => {
  it("accepts only the token whose hash matches the session secret", async () => {
    const csrfToken = "sometokenvalue1234567890";
    const storedHash = await sha256Hex(csrfToken);
    expect(await verifyCsrfTokenAsync(storedHash, csrfToken)).toBe(true);
    expect(await verifyCsrfTokenAsync(storedHash, "wrongtokenvalue123456")).toBe(false);
    expect(await verifyCsrfTokenAsync(storedHash, null)).toBe(false);
    expect(await verifyCsrfTokenAsync(storedHash, "")).toBe(false);
    expect(await verifyCsrfTokenAsync(storedHash, csrfToken + "x".repeat(300))).toBe(false);
  });

  it("requires same-origin Origin headers", () => {
    const request = (origin: string | null) =>
      new Request("https://gateway.example.com/v1/admin/settings", {
        method: "PATCH",
        headers: origin ? { Origin: origin } : {}
      });
    expect(verifySameOrigin(request("https://gateway.example.com"))).toBe(true);
    expect(verifySameOrigin(request("https://evil.example"))).toBe(false);
    expect(verifySameOrigin(request(null))).toBe(false);
    expect(verifySameOrigin(request("not a url"))).toBe(false);
  });

  it("rejects browser-reported cross-site fetches but tolerates missing Sec-Fetch-Site", () => {
    const withHeader = (site: string | null) =>
      new Request("https://gateway.example.com/", { headers: site ? { "Sec-Fetch-Site": site } : {} });
    expect(verifySecFetchSite(withHeader("same-origin"))).toBe(true);
    expect(verifySecFetchSite(withHeader("same-site"))).toBe(true);
    expect(verifySecFetchSite(withHeader("none"))).toBe(true);
    expect(verifySecFetchSite(withHeader("cross-site"))).toBe(false);
    expect(verifySecFetchSite(withHeader(null))).toBe(true);
  });
});

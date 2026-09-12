// @ts-nocheck
import { afterEach, describe, expect, it, vi } from "vitest";
import { checkRemoteHost } from "./ipPolicy";
import { decodeUtf8, safeFetch } from "./safeRemoteFetcher";

function jsonResponse(body: string, headers: Record<string, string> = {}) {
  return new Response(body, { status: 200, headers: { "Content-Type": "text/plain", ...headers } });
}

function redirectResponse(location: string) {
  return new Response(null, { status: 302, headers: { Location: location } });
}

const OPTIONS = { maxBytes: 1024, timeoutMs: 2000 };

afterEach(() => {
  vi.unstubAllGlobals();
});

describe("ipPolicy hostname and literal-IP checks", () => {
  it("allows public hostnames and public literal IPs", () => {
    expect(checkRemoteHost("example.com").allowed).toBe(true);
    expect(checkRemoteHost("cdn.example.co.uk").allowed).toBe(true);
    expect(checkRemoteHost("8.8.8.8").allowed).toBe(true);
    expect(checkRemoteHost("[2001:4860:4860::8888]").allowed).toBe(true);
  });

  it("rejects local hostnames and metadata endpoints", () => {
    for (const host of ["localhost", "localhost.localdomain", "foo.local", "bar.internal", "metadata.google.internal", "169.254.169.254"]) {
      expect(checkRemoteHost(host).allowed).toBe(false);
    }
  });

  it("rejects private, loopback, link-local, and reserved IPv4 ranges", () => {
    for (const host of ["127.0.0.1", "10.1.2.3", "172.16.0.1", "172.31.255.255", "192.168.1.10", "169.254.169.254", "0.0.0.0", "224.0.0.1", "240.0.0.1", "100.64.0.1", "2130706433", "127.1"]) {
      expect(checkRemoteHost(host).allowed).toBe(false);
    }
    expect(checkRemoteHost("172.32.0.1").allowed).toBe(true);
  });

  it("rejects loopback, ULA, link-local, and IPv4-mapped IPv6 addresses", () => {
    for (const host of ["::1", "::", "fc00::1", "fd12:3456::1", "fe80::1", "ff02::1", "::ffff:127.0.0.1", "::ffff:10.0.0.1", "[::ffff:192.168.0.1]", "64:ff9b::7f00:1"]) {
      expect(checkRemoteHost(host).allowed).toBe(false);
    }
    expect(checkRemoteHost("2606:4700::1111").allowed).toBe(true);
  });
});

describe("safeFetch", () => {
  it("allows http and https to public hosts", async () => {
    vi.stubGlobal("fetch", vi.fn(async () => jsonResponse("hello")));
    const https = await safeFetch("https://example.com/opml", OPTIONS);
    expect(https.ok).toBe(true);
    expect(decodeUtf8(https.bytes)).toBe("hello");

    const http = await safeFetch("http://example.com/opml", OPTIONS);
    expect(http.ok).toBe(true);
  });

  it("rejects non-http schemes and credentials in URL", async () => {
    const fetchSpy = vi.fn();
    vi.stubGlobal("fetch", fetchSpy);
    expect((await safeFetch("file:///etc/passwd", OPTIONS)).error).toBe("blocked_scheme");
    expect((await safeFetch("ftp://example.com/x", OPTIONS)).error).toBe("blocked_scheme");
    expect((await safeFetch("https://user:pass@example.com/x", OPTIONS)).error).toBe("credentials_in_url");
    expect(fetchSpy).not.toHaveBeenCalled();
  });

  it("rejects private hosts without fetching", async () => {
    const fetchSpy = vi.fn();
    vi.stubGlobal("fetch", fetchSpy);
    for (const url of [
      "https://localhost/x",
      "https://127.0.0.1/x",
      "https://10.0.0.5/x",
      "https://169.254.169.254/latest/meta-data",
      "https://[::1]/x",
      "https://service.internal/x",
      "https://printer.local/x"
    ]) {
      expect((await safeFetch(url, OPTIONS)).error).toBe("blocked_host");
    }
    expect(fetchSpy).not.toHaveBeenCalled();
  });

  it("validates every redirect hop and refuses public-to-private redirects", async () => {
    const fetchSpy = vi.fn(async (_url: string, init: RequestInit) => {
      if ((init.redirect === "manual") && fetchSpy.mock.calls.length === 1) return redirectResponse("https://evil.example/next");
      if (fetchSpy.mock.calls.length === 2) return redirectResponse("https://127.0.0.1/admin");
      return jsonResponse("never");
    });
    vi.stubGlobal("fetch", fetchSpy);

    const result = await safeFetch("https://public.example/opml", OPTIONS);
    expect(result.ok).toBe(false);
    expect(result.error).toBe("redirect_blocked");
    // The third hop (to the private host) must never be attempted.
    expect(fetchSpy).toHaveBeenCalledTimes(2);
  });

  it("enforces the redirect limit", async () => {
    vi.stubGlobal("fetch", vi.fn(async () => redirectResponse("https://example.com/loop")));
    const result = await safeFetch("https://example.com/start", { ...OPTIONS, maxRedirects: 3 });
    expect(result.ok).toBe(false);
    expect(result.error).toBe("too_many_redirects");
  });

  it("rejects non-http redirect targets", async () => {
    vi.stubGlobal("fetch", vi.fn(async () => redirectResponse("file:///etc/passwd")));
    const result = await safeFetch("https://example.com/start", OPTIONS);
    expect(result.error).toBe("redirect_blocked");
  });

  it("streams the body and refuses documents above maxBytes", async () => {
    const bigBody = "x".repeat(4096);
    vi.stubGlobal("fetch", vi.fn(async () => new Response(bigBody, { status: 200 })));
    const result = await safeFetch("https://example.com/big", OPTIONS);
    expect(result.error).toBe("too_large");
  });

  it("surfaces upstream HTTP errors with status", async () => {
    vi.stubGlobal("fetch", vi.fn(async () => new Response("nope", { status: 404 })));
    const result = await safeFetch("https://example.com/missing", OPTIONS);
    expect(result.error).toBe("http_error");
    expect(result.status).toBe(404);
  });

  it("reports timeouts", async () => {
    vi.stubGlobal("fetch", (_url: string, init: RequestInit) =>
      new Promise((_resolve, reject) => {
        init.signal?.addEventListener("abort", () => reject(new Error("aborted")));
      })
    );
    const result = await safeFetch("https://example.com/slow", { maxBytes: 1024, timeoutMs: 50 });
    expect(result.error).toBe("timeout");
  });
});

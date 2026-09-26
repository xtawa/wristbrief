import { afterEach, describe, expect, it } from "vitest";
import { createServer } from "node:http";
import { gzipSync } from "node:zlib";
import { checkRemoteAddress, checkRemoteHost } from "./ipPolicy";
import {
  configureFetchConcurrency,
  createNodeSafeHttpTransport,
  decodeUtf8,
  fetchConcurrencyState,
  safeFetch,
  setSafeFetchResolverOverride,
  setSafeFetchTransportOverride,
  type AddressResolver,
  type SafeFetchOptions,
  type SafeFetchResult,
  type SafeHttpRequest,
  type SafeHttpResponse,
  type SafeHttpTransport
} from "./safeRemoteFetcher";

const PUBLIC_IPV4 = "93.184.216.34";
const DEFAULT_CONCURRENCY = 8;

afterEach(() => {
  setSafeFetchTransportOverride(null);
  setSafeFetchResolverOverride(null);
  configureFetchConcurrency(DEFAULT_CONCURRENCY);
});

/* ------------------------------------------------------------------ */
/* Fakes: a scripted transport + resolver replace the network boundary  */
/* ------------------------------------------------------------------ */

function headerBag(map: Record<string, string> = {}): { get(name: string): string | null } {
  const lower = new Map(Object.entries(map).map(([key, value]) => [key.toLowerCase(), String(value)]));
  return { get: (name: string) => lower.get(name.toLowerCase()) ?? null };
}

function fromChunks(chunks: Uint8Array[]): AsyncIterable<Uint8Array> {
  return {
    async *[Symbol.asyncIterator]() {
      for (const chunk of chunks) yield chunk;
    }
  };
}

/** Yields until the cap trips; an unbounded body proves the cap is streaming. */
function endlessBody(size = 1024, counter = { pulled: 0 }): AsyncIterable<Uint8Array> {
  return {
    async *[Symbol.asyncIterator]() {
      while (true) {
        counter.pulled += 1;
        yield new Uint8Array(size);
      }
    }
  };
}

/** Headers arrive, the body never produces a byte. */
function stalledBody(): AsyncIterable<Uint8Array> {
  return {
    [Symbol.asyncIterator](): AsyncIterator<Uint8Array> {
      return { next: () => new Promise<IteratorResult<Uint8Array>>(() => {}) };
    }
  };
}

/** One chunk arrives, then the body stalls until the deadline. */
function dribblingBody(first: Uint8Array): AsyncIterable<Uint8Array> {
  let sent = false;
  return {
    [Symbol.asyncIterator](): AsyncIterator<Uint8Array> {
      return {
        next: async (): Promise<IteratorResult<Uint8Array>> => {
          if (sent) return new Promise<IteratorResult<Uint8Array>>(() => {});
          sent = true;
          return { done: false, value: first };
        }
      };
    }
  };
}

function utf8(text: string): Uint8Array {
  return new TextEncoder().encode(text);
}

type FakeResponseInput = {
  status?: number;
  headers?: Record<string, string>;
  chunks?: Uint8Array[];
  body?: AsyncIterable<Uint8Array>;
};

function fakeResponse(input: FakeResponseInput = {}): { state: { destroyed: boolean }; response: SafeHttpResponse } {
  const state = { destroyed: false };
  const response: SafeHttpResponse = {
    status: input.status ?? 200,
    headers: headerBag(input.headers ?? {}),
    body: input.body ?? fromChunks(input.chunks ?? [utf8("ok")]),
    destroy: () => { state.destroyed = true; }
  };
  return { state, response };
}

function scriptedTransport(handler: (request: SafeHttpRequest, index: number) => SafeHttpResponse | Promise<SafeHttpResponse>) {
  const calls: SafeHttpRequest[] = [];
  const transport: SafeHttpTransport = {
    async request(request) {
      calls.push(request);
      return handler(request, calls.length - 1);
    }
  };
  return { calls, transport };
}

const publicResolver: AddressResolver = async () => [PUBLIC_IPV4];

function options(extra: Partial<SafeFetchOptions> = {}): SafeFetchOptions {
  return { maxBytes: 1024, timeoutMs: 2000, resolver: publicResolver, ...extra };
}

/** Unwraps a successful fetch, failing loudly instead of reading a union. */
function okBytes(result: SafeFetchResult): Uint8Array {
  if (!result.ok) throw new Error(`expected a successful fetch, got ${result.error}`);
  return result.bytes;
}

function failure(result: SafeFetchResult): { error: string; status?: number } {
  if (result.ok) throw new Error("expected a failed fetch, got a response");
  return { error: result.error, status: result.status };
}

/* ------------------------------------------------------------------ */
/* ipPolicy                                                            */
/* ------------------------------------------------------------------ */

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

  it("screens the address a socket would actually connect to", () => {
    for (const address of [
      "127.0.0.1", "10.0.0.5", "172.16.9.9", "192.168.1.1", "169.254.169.254", "100.64.0.1",
      "0.0.0.0", "224.0.0.1", "240.0.0.1", "192.0.2.5", "198.18.0.1", "999.1.1.1", "8.8.8.8.8",
      "not-an-address", "example.com", ""
    ]) {
      expect(checkRemoteAddress(address).allowed, address).toBe(false);
    }
    for (const address of ["8.8.8.8", "1.1.1.1", "172.32.0.1", "2606:4700::1111", "[2001:4860:4860::8888]"]) {
      expect(checkRemoteAddress(address).allowed, address).toBe(true);
    }
  });

  it("unwraps IPv4-mapped, NAT64, 6to4 and Teredo addresses before screening", () => {
    expect(checkRemoteAddress("::ffff:169.254.169.254").allowed).toBe(false);
    expect(checkRemoteAddress("::ffff:8.8.8.8").allowed).toBe(true);
    expect(checkRemoteAddress("64:ff9b::a00:1").allowed).toBe(false);      // NAT64 -> 10.0.0.1
    expect(checkRemoteAddress("64:ff9b::808:808").allowed).toBe(true);     // NAT64 -> 8.8.8.8
    expect(checkRemoteAddress("2002:7f00:1::1").allowed).toBe(false);      // 6to4 -> 127.0.0.1
    expect(checkRemoteAddress("2001:0:1::1").allowed).toBe(false);         // Teredo
  });
});

/* ------------------------------------------------------------------ */
/* safeFetch: URL policy                                               */
/* ------------------------------------------------------------------ */

describe("safeFetch URL policy", () => {
  it("rejects non-http schemes and credentials in the URL without connecting", async () => {
    const scripted = scriptedTransport(() => fakeResponse().response);
    for (const url of ["file:///etc/passwd", "ftp://example.com/x", "https://user:pass@example.com/x"]) {
      const result = await safeFetch(url, options({ transport: scripted.transport }));
      expect(result.ok).toBe(false);
      expect(["blocked_scheme", "credentials_in_url"]).toContain(failure(result).error);
    }
    expect(scripted.calls).toHaveLength(0);
  });

  it("rejects private hosts (literal and name based) without connecting", async () => {
    const scripted = scriptedTransport(() => fakeResponse().response);
    for (const url of [
      "https://localhost/x",
      "https://127.0.0.1/x",
      "https://10.0.0.5/x",
      "https://169.254.169.254/latest/meta-data",
      "https://[::1]/x",
      "https://service.internal/x",
      "https://printer.local/x",
      "https://[::ffff:10.0.0.1]/x"
    ]) {
      const result = await safeFetch(url, options({ transport: scripted.transport }));
      expect(failure(result).error).toBe("blocked_host");
    }
    expect(scripted.calls).toHaveLength(0);
  });
});

/* ------------------------------------------------------------------ */
/* safeFetch: DNS screening + pinning                                  */
/* ------------------------------------------------------------------ */

describe("safeFetch DNS screening", () => {
  it("refuses a hostname that resolves to a private, loopback, link-local or metadata address", async () => {
    const scripted = scriptedTransport(() => fakeResponse().response);
    const malicious = [
      "10.0.0.5",                 // private
      "127.0.0.1",                // loopback
      "169.254.169.254",          // cloud metadata
      "100.64.0.1",               // CGNAT
      "::1",                      // IPv6 loopback
      "fd00::1",                  // unique local
      "fe80::1",                  // link local
      "::ffff:169.254.169.254",   // IPv4-mapped metadata
      "64:ff9b::a9fe:a9fe"        // NAT64 -> 169.254.169.254
    ];
    for (const address of malicious) {
      const result = await safeFetch("https://rebind.example/secret", options({
        transport: scripted.transport,
        resolver: async () => [address]
      }));
      expect(failure(result).error, address).toBe("blocked_host");
    }
    // The refused addresses were never handed to the transport at all.
    expect(scripted.calls).toHaveLength(0);
  });

  it("blocks a trailing-dot loopback name through its resolved address, not through the suffix list", async () => {
    // "localhost." does not match the suffix list ("localhost"), so the hostname
    // check alone lets it through — the resolved address is what refuses it.
    const scripted = scriptedTransport(() => fakeResponse().response);
    const result = await safeFetch("http://localhost./admin", options({
      transport: scripted.transport,
      resolver: async () => ["127.0.0.1"]
    }));
    expect(failure(result).error).toBe("blocked_host");
    expect(scripted.calls).toHaveLength(0);
  });

  it("refuses a mixed answer set that contains any blocked address", async () => {
    const scripted = scriptedTransport(() => fakeResponse().response);
    const result = await safeFetch("https://mixed.example/x", options({
      transport: scripted.transport,
      resolver: async () => [PUBLIC_IPV4, "127.0.0.1"]
    }));
    expect(failure(result).error).toBe("blocked_host");
    expect(scripted.calls).toHaveLength(0);
  });

  it("refuses a hostname whose resolution fails instead of falling back to a connection", async () => {
    const scripted = scriptedTransport(() => fakeResponse().response);
    const result = await safeFetch("https://gone.example/x", options({
      transport: scripted.transport,
      resolver: async () => { throw Object.assign(new Error("ENOTFOUND"), { code: "ENOTFOUND" }); }
    }));
    expect(failure(result).error).toBe("network_error");
    expect(scripted.calls).toHaveLength(0);
  });

  it("resolves once, screens the answer, and connects to exactly that address", async () => {
    const scripted = scriptedTransport(() => fakeResponse({ chunks: [utf8("pinned")], headers: { "content-type": "text/plain" } }).response);
    let resolutions = 0;
    const result = await safeFetch("https://public.example/opml?a=1", options({
      transport: scripted.transport,
      resolver: async () => { resolutions += 1; return resolutions === 1 ? [PUBLIC_IPV4] : ["127.0.0.1"]; }
    }));

    expect(decodeUtf8(okBytes(result))).toBe("pinned");
    // The validated address and the connected address cannot diverge: the
    // transport is handed the screened IP, not the hostname, and DNS is not
    // consulted a second time inside the connect path.
    expect(scripted.calls).toHaveLength(1);
    expect(scripted.calls[0].address).toBe(PUBLIC_IPV4);
    expect(scripted.calls[0].hostname).toBe("public.example");
    expect(scripted.calls[0].hostHeader).toBe("public.example");
    expect(scripted.calls[0].url.pathname).toBe("/opml");
    expect(scripted.calls[0].url.search).toBe("?a=1");
    expect(scripted.calls[0].family).toBe(4);
    expect(resolutions).toBe(1);
  });

  it("survives a rebinding second answer because the pinned socket never re-resolves", async () => {
    // A real socket is opened here: `rebind.invalid` cannot resolve at all, so a
    // connection to it can only succeed if the socket went to the pinned IP.
    const seen: Array<{ host?: string; url?: string }> = [];
    const server = createServer((request, response) => {
      seen.push({ host: request.headers.host, url: request.url });
      response.writeHead(200, { "Content-Type": "text/plain" });
      response.end("pinned-socket");
    });
    await new Promise<void>((resolve) => server.listen(0, "127.0.0.1", () => resolve()));
    const address = server.address();
    if (address === null || typeof address === "string") throw new Error("unexpected server address");
    try {
      const transport = createNodeSafeHttpTransport();
      const response = await transport.request({
        url: new URL(`http://rebind.invalid:${address.port}/pinned?x=1`),
        hostHeader: `rebind.invalid:${address.port}`,
        hostname: "rebind.invalid",
        address: "127.0.0.1",
        family: 4,
        signal: new AbortController().signal
      });
      expect(response.status).toBe(200);
      let text = "";
      for await (const chunk of response.body) text += Buffer.from(chunk).toString("utf8");
      response.destroy();

      expect(text).toBe("pinned-socket");
      // Virtual-host routing is preserved even though the socket went to the IP.
      expect(seen).toEqual([{ host: `rebind.invalid:${address.port}`, url: "/pinned?x=1" }]);
    } finally {
      server.closeAllConnections();
      await new Promise<void>((resolve) => server.close(() => resolve()));
    }
  });

  it("honours the module level test doubles for routes that pass no transport or resolver", async () => {
    setSafeFetchTransportOverride(scriptedTransport(() => fakeResponse({ chunks: [utf8("override")] }).response).transport);
    setSafeFetchResolverOverride(async () => [PUBLIC_IPV4]);
    const result = await safeFetch("https://public.example/x", { maxBytes: 1024, timeoutMs: 2000 });
    expect(decodeUtf8(okBytes(result))).toBe("override");
  });
});

/* ------------------------------------------------------------------ */
/* safeFetch: redirects                                                */
/* ------------------------------------------------------------------ */

describe("safeFetch redirects", () => {
  it("re-validates DNS on every hop and refuses a public-to-private redirect", async () => {
    const scripted = scriptedTransport((_, index) => index === 0
      ? fakeResponse({ status: 302, headers: { location: "https://evil.example/next" } }).response
      : fakeResponse({ chunks: [utf8("never")] }).response);
    const result = await safeFetch("https://public.example/opml", options({
      transport: scripted.transport,
      resolver: async (hostname) => hostname === "evil.example" ? ["10.0.0.5"] : [PUBLIC_IPV4]
    }));

    expect(failure(result).error).toBe("redirect_blocked");
    // Only the first hop was attempted; the private redirect target was neither
    // resolved-and-pinned nor connected.
    expect(scripted.calls).toHaveLength(1);
  });

  it("follows a safe redirect and screens the second hop", async () => {
    const scripted = scriptedTransport((_, index) => index === 0
      ? fakeResponse({ status: 301, headers: { location: "https://cdn.example/final" } }).response
      : fakeResponse({ chunks: [utf8("hop2")] }).response);
    const result = await safeFetch("https://public.example/start", options({ transport: scripted.transport }));
    expect(decodeUtf8(okBytes(result))).toBe("hop2");
    expect(scripted.calls.map((call) => call.hostname)).toEqual(["public.example", "cdn.example"]);
    expect(scripted.calls[1].address).toBe(PUBLIC_IPV4);
  });

  it("bounds the redirect chain and refuses non-http targets", async () => {
    const loop = scriptedTransport(() => fakeResponse({ status: 302, headers: { location: "https://example.com/loop" } }).response);
    const limited = await safeFetch("https://example.com/start", options({ transport: loop.transport, maxRedirects: 3 }));
    expect(failure(limited).error).toBe("too_many_redirects");

    const scheme = scriptedTransport(() => fakeResponse({ status: 302, headers: { location: "file:///etc/passwd" } }).response);
    const blocked = await safeFetch("https://example.com/start", options({ transport: scheme.transport }));
    expect(failure(blocked).error).toBe("redirect_blocked");

    const missing = scriptedTransport(() => fakeResponse({ status: 302 }).response);
    const noLocation = await safeFetch("https://example.com/start", options({ transport: missing.transport }));
    expect(failure(noLocation).error).toBe("redirect_blocked");
  });
});

/* ------------------------------------------------------------------ */
/* safeFetch: deadlines                                                */
/* ------------------------------------------------------------------ */

describe("safeFetch deadlines and limits", () => {
  it("fails with timeout when headers arrive fast and the body then stalls", async () => {
    const scripted = scriptedTransport(() => fakeResponse({ headers: { "content-type": "text/plain" }, body: stalledBody() }).response);
    const started = Date.now();
    const result = await safeFetch("https://slow.example/drip", {
      maxBytes: 1024,
      timeoutMs: 150,
      resolver: publicResolver,
      transport: scripted.transport
    });
    expect(failure(result).error).toBe("timeout");
    // The deadline covers the body read, so this returns instead of hanging.
    expect(Date.now() - started).toBeLessThan(2000);
    expect(scripted.calls).toHaveLength(1);
  });

  it("kills a dribbling body with the idle/stall timeout before the overall deadline", async () => {
    const scripted = scriptedTransport(() => fakeResponse({ body: dribblingBody(utf8("first")) }).response);
    const started = Date.now();
    const result = await safeFetch("https://dribble.example/x", {
      maxBytes: 1024,
      timeoutMs: 5000,
      idleTimeoutMs: 100,
      resolver: publicResolver,
      transport: scripted.transport
    });
    expect(failure(result).error).toBe("timeout");
    expect(Date.now() - started).toBeLessThan(2000);
  });

  it("times out a stalled connect without waiting for the transport", async () => {
    const hanging = scriptedTransport(() => new Promise<SafeHttpResponse>(() => {}));
    const started = Date.now();
    const result = await safeFetch("https://hang.example/x", {
      maxBytes: 1024,
      timeoutMs: 120,
      resolver: publicResolver,
      transport: hanging.transport
    });
    expect(failure(result).error).toBe("timeout");
    expect(Date.now() - started).toBeLessThan(2000);
  });

  it("stops an oversized stream as soon as the cap is crossed and never buffers it whole", async () => {
    const counter = { pulled: 0 };
    const scripted = scriptedTransport(() => fakeResponse({ body: endlessBody(1024, counter) }).response);
    const result = await safeFetch("https://big.example/file", {
      maxBytes: 4096,
      timeoutMs: 2000,
      resolver: publicResolver,
      transport: scripted.transport
    });
    expect(failure(result).error).toBe("too_large");
    expect(counter.pulled).toBeLessThan(10);
    expect(scripted.calls).toHaveLength(1);
  });

  it("refuses an oversized declared body before reading a single byte", async () => {
    const counter = { pulled: 0 };
    const scripted = scriptedTransport(() => fakeResponse({
      headers: { "content-length": "1048576" },
      body: endlessBody(1024, counter)
    }).response);
    const result = await safeFetch("https://big.example/declared", options({ transport: scripted.transport }));
    expect(failure(result).error).toBe("too_large");
    expect(counter.pulled).toBe(0);
  });

  it("surfaces upstream status errors and content-type refusals", async () => {
    const notFound = scriptedTransport(() => fakeResponse({ status: 404, chunks: [utf8("nope")] }).response);
    const missing = await safeFetch("https://example.com/missing", options({ transport: notFound.transport }));
    expect(failure(missing).error).toBe("http_error");
    expect(failure(missing).status).toBe(404);

    const wrongType = scriptedTransport(() => fakeResponse({ headers: { "content-type": "text/html" } }).response);
    const refused = await safeFetch("https://example.com/image.png", options({
      transport: wrongType.transport,
      allowedContentTypes: [/^image\//i]
    }));
    expect(failure(refused).error).toBe("http_error");
    expect(failure(refused).status).toBe(415);
  });

  it("maps transport and body failures to network_error", async () => {
    const refusing = scriptedTransport(() => { throw new Error("ECONNREFUSED"); });
    const refused = await safeFetch("https://down.example/x", options({ transport: refusing.transport }));
    expect(failure(refused).error).toBe("network_error");

    const broken: AsyncIterable<Uint8Array> = {
      [Symbol.asyncIterator](): AsyncIterator<Uint8Array> {
        return { next: async () => { throw new Error("ECONNRESET"); } };
      }
    };
    const midStream = scriptedTransport(() => fakeResponse({ body: broken }).response);
    const failed = await safeFetch("https://reset.example/x", options({ transport: midStream.transport }));
    expect(failure(failed).error).toBe("network_error");
  });

  it("decodes a gzip encoded body inside the size cap", async () => {
    const payload = "x".repeat(4096);
    const scripted = scriptedTransport(() => fakeResponse({
      headers: { "content-encoding": "gzip", "content-type": "text/plain" },
      body: fromChunks([gzipSync(Buffer.from(payload))])
    }).response);
    const result = await safeFetch("https://gzip.example/x", {
      maxBytes: 8192,
      timeoutMs: 2000,
      resolver: publicResolver,
      transport: scripted.transport
    });
    expect(decodeUtf8(okBytes(result))).toBe(payload);

    const bomb = scriptedTransport(() => fakeResponse({
      headers: { "content-encoding": "gzip" },
      body: fromChunks([gzipSync(Buffer.from("y".repeat(64 * 1024)))])
    }).response);
    const stopped = await safeFetch("https://gzip.example/bomb", options({ transport: bomb.transport }));
    expect(failure(stopped).error).toBe("too_large");
  });
});

/* ------------------------------------------------------------------ */
/* safeFetch: concurrency                                              */
/* ------------------------------------------------------------------ */

describe("safeFetch concurrency limit", () => {
  it("bounds simultaneous outbound fetches and releases every slot", async () => {
    configureFetchConcurrency(2);
    let active = 0;
    let peak = 0;
    const gates: Array<() => void> = [];
    const transport: SafeHttpTransport = {
      async request() {
        active += 1;
        peak = Math.max(peak, active);
        await new Promise<void>((resolve) => { const release = () => resolve(); gates.push(release); });
        active -= 1;
        return fakeResponse({ chunks: [utf8("ok")] }).response;
      }
    };

    let settled = 0;
    const all = Promise.all(Array.from({ length: 6 }, () => safeFetch("https://public.example/x", options({ transport }))
      .then((result) => { settled += 1; return result; })));

    for (let tick = 0; tick < 400 && settled < 6; tick++) {
      await new Promise<void>((resolve) => setTimeout(resolve, 5));
      expect(fetchConcurrencyState().active).toBeLessThanOrEqual(2);
      for (const release of gates.splice(0, gates.length)) release();
    }

    const results = await all;
    expect(results.every((result) => result.ok)).toBe(true);
    expect(peak).toBeLessThanOrEqual(2);
    expect(peak).toBe(2);
    expect(fetchConcurrencyState()).toMatchObject({ limit: 2, active: 0, queued: 0 });
  });

  it("frees the slot after a failure, a timeout, and a redirect abort", async () => {
    configureFetchConcurrency(1);
    const failing = scriptedTransport(() => { throw new Error("ECONNREFUSED"); });
    expect(failure(await safeFetch("https://down.example/x", options({ transport: failing.transport }))).error).toBe("network_error");
    expect(fetchConcurrencyState().active).toBe(0);

    const stalling = scriptedTransport(() => fakeResponse({ body: stalledBody() }).response);
    expect(failure(await safeFetch("https://slow.example/x", {
      timeoutMs: 80, maxBytes: 1024, resolver: publicResolver, transport: stalling.transport
    })).error).toBe("timeout");
    expect(fetchConcurrencyState().active).toBe(0);

    const redirecting = scriptedTransport(() => fakeResponse({ status: 302, headers: { location: "https://127.0.0.1/x" } }).response);
    expect(failure(await safeFetch("https://public.example/x", options({ transport: redirecting.transport }))).error).toBe("redirect_blocked");
    expect(fetchConcurrencyState().active).toBe(0);

    const working = scriptedTransport(() => fakeResponse({ chunks: [utf8("after")] }).response);
    const result = await safeFetch("https://public.example/x", options({ transport: working.transport }));
    expect(decodeUtf8(okBytes(result))).toBe("after");
    expect(fetchConcurrencyState().active).toBe(0);
  });
});

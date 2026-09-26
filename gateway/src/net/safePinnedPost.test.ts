import { afterEach, describe, expect, it } from "vitest";
import { createServer } from "node:http";
import {
  configureFetchConcurrency,
  createNodeSafeHttpTransport,
  decodeUtf8,
  fetchConcurrencyState,
  safeFetch,
  safePinnedPost,
  setSafeFetchResolverOverride,
  setSafeFetchTransportOverride,
  type AddressResolver,
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
/* Fakes                                                               */
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

function stalledBody(): AsyncIterable<Uint8Array> {
  return {
    [Symbol.asyncIterator](): AsyncIterator<Uint8Array> {
      return { next: () => new Promise<IteratorResult<Uint8Array>>(() => {}) };
    }
  };
}

function utf8(text: string): Uint8Array {
  return new TextEncoder().encode(text);
}

function fakeResponse(input: { status?: number; headers?: Record<string, string>; chunks?: Uint8Array[]; body?: AsyncIterable<Uint8Array> } = {}): SafeHttpResponse {
  return {
    status: input.status ?? 200,
    headers: headerBag(input.headers ?? { "content-type": "application/json" }),
    body: input.body ?? fromChunks(input.chunks ?? [utf8('{"ok":true}')]),
    destroy: () => {}
  };
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

function failure(result: { ok: true } | { ok: false; error: string; status?: number }): { error: string; status?: number } {
  if (result.ok) throw new Error("expected a failed request");
  return { error: result.error, status: result.status };
}

async function waitUntil(predicate: () => boolean, timeoutMs = 5000) {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    if (predicate()) return;
    await new Promise((resolve) => setTimeout(resolve, 5));
  }
  throw new Error("condition was not reached in time");
}

const POST_OPTIONS = { timeoutMs: 2000, resolver: publicResolver };

/* ------------------------------------------------------------------ */
/* Request shape + pinning                                             */
/* ------------------------------------------------------------------ */

describe("safePinnedPost request shape", () => {
  it("sends the caller's method, headers and body from the pinned address", async () => {
    const scripted = scriptedTransport(() => fakeResponse({ chunks: [utf8('{"choices":[{"message":{"content":"hi"}}]}')] }));
    const result = await safePinnedPost("https://provider.example/v1/chat/completions", {
      ...POST_OPTIONS,
      transport: scripted.transport,
      method: "POST",
      headers: { "Content-Type": "application/json", Authorization: "Bearer provider-secret" },
      body: JSON.stringify({ model: "test-model" })
    });

    expect(result.ok).toBe(true);
    if (!result.ok) return;
    expect(result.status).toBe(200);
    expect(decodeUtf8(result.bytes)).toContain("\"content\":\"hi\"");
    expect(scripted.calls).toHaveLength(1);
    expect(scripted.calls[0].method).toBe("POST");
    expect(scripted.calls[0].address).toBe(PUBLIC_IPV4);
    expect(scripted.calls[0].hostname).toBe("provider.example");
    expect(scripted.calls[0].hostHeader).toBe("provider.example");
    expect(scripted.calls[0].url.pathname).toBe("/v1/chat/completions");
    expect(scripted.calls[0].headers).toMatchObject({ Authorization: "Bearer provider-secret" });
    expect(JSON.parse(String(scripted.calls[0].body))).toMatchObject({ model: "test-model" });
  });

  it("refuses a hostname that resolves to a blocked address and never connects", async () => {
    const scripted = scriptedTransport(() => fakeResponse());
    for (const address of ["127.0.0.1", "10.0.0.5", "169.254.169.254", "100.64.0.1", "::1", "fc00::1", "::ffff:169.254.169.254", "64:ff9b::a9fe:a9fe"]) {
      const result = await safePinnedPost("https://provider.example/v1/chat/completions", {
        ...POST_OPTIONS,
        transport: scripted.transport,
        resolver: async () => [address],
        body: "{}"
      });
      expect(failure(result).error, address).toBe("blocked_host");
    }
    expect(scripted.calls).toHaveLength(0);
  });

  it("refuses a mixed answer set that contains any blocked address", async () => {
    const scripted = scriptedTransport(() => fakeResponse());
    const result = await safePinnedPost("https://provider.example/v1/chat/completions", {
      ...POST_OPTIONS,
      transport: scripted.transport,
      resolver: async () => [PUBLIC_IPV4, "127.0.0.1"],
      body: "{}"
    });
    expect(failure(result).error).toBe("blocked_host");
    expect(scripted.calls).toHaveLength(0);
  });

  it("validates once and connects to exactly the screened address", async () => {
    const scripted = scriptedTransport(() => fakeResponse());
    let resolutions = 0;
    const result = await safePinnedPost("https://provider.example/v1/messages", {
      ...POST_OPTIONS,
      transport: scripted.transport,
      resolver: async () => { resolutions += 1; return resolutions === 1 ? [PUBLIC_IPV4] : ["127.0.0.1"]; },
      body: "{}"
    });
    expect(result.ok).toBe(true);
    expect(scripted.calls[0].address).toBe(PUBLIC_IPV4);
    expect(scripted.calls[0].family).toBe(4);
    expect(resolutions).toBe(1);
  });

  it("refuses a redirect instead of following it", async () => {
    const scripted = scriptedTransport(() => fakeResponse({ status: 302, headers: { location: "http://169.254.169.254/latest/meta-data" } }));
    const result = await safePinnedPost("https://provider.example/v1/chat/completions", {
      ...POST_OPTIONS,
      transport: scripted.transport,
      body: "{}"
    });
    expect(failure(result)).toMatchObject({ error: "http_error", status: 302 });
    // Nothing followed the Location.
    expect(scripted.calls).toHaveLength(1);
  });

  it("fails with timeout when the body stalls after the headers", async () => {
    const scripted = scriptedTransport(() => fakeResponse({ body: stalledBody() }));
    const started = Date.now();
    const result = await safePinnedPost("https://provider.example/v1/chat/completions", {
      timeoutMs: 120,
      resolver: publicResolver,
      transport: scripted.transport,
      body: "{}"
    });
    expect(failure(result).error).toBe("timeout");
    expect(Date.now() - started).toBeLessThan(2000);
  });

  it("caps the response body while streaming", async () => {
    const counter = { pulled: 0 };
    const endless: AsyncIterable<Uint8Array> = {
      async *[Symbol.asyncIterator]() {
        while (true) { counter.pulled += 1; yield new Uint8Array(1024); }
      }
    };
    const scripted = scriptedTransport(() => fakeResponse({ body: endless }));
    const result = await safePinnedPost("https://provider.example/v1/chat/completions", {
      ...POST_OPTIONS,
      maxBytes: 4096,
      transport: scripted.transport,
      body: "{}"
    });
    expect(failure(result).error).toBe("too_large");
    expect(counter.pulled).toBeLessThan(10);
  });
});

/* ------------------------------------------------------------------ */
/* The real node transport (pinning, Host, body, SNI-equivalent name)   */
/* ------------------------------------------------------------------ */

describe("pinned POST transport", () => {
  it("posts to the pinned address while preserving the Host header and payload", async () => {
    const seen: Array<{ method?: string; host?: string; url?: string; body: string; contentType?: string }> = [];
    const server = createServer((request, response) => {
      let body = "";
      request.on("data", (chunk) => { body += chunk.toString("utf8"); });
      request.on("end", () => {
        seen.push({
          method: request.method,
          host: request.headers.host,
          url: request.url,
          contentType: request.headers["content-type"] as string | undefined,
          body
        });
        response.writeHead(200, { "Content-Type": "application/json" });
        response.end('{"ok":true}');
      });
    });
    await new Promise<void>((resolve) => server.listen(0, "127.0.0.1", () => resolve()));
    const address = server.address();
    if (address === null || typeof address === "string") throw new Error("unexpected server address");
    try {
      const transport = createNodeSafeHttpTransport();
      const response = await transport.request({
        url: new URL(`http://provider.invalid:${address.port}/v1/chat/completions`),
        hostHeader: `provider.invalid:${address.port}`,
        hostname: "provider.invalid",
        address: "127.0.0.1",
        family: 4,
        signal: new AbortController().signal,
        method: "POST",
        // A caller cannot move the request to another virtual host.
        headers: { "Content-Type": "application/json", Authorization: "Bearer provider-secret", Host: "evil.example" },
        body: JSON.stringify({ model: "test-model" })
      });
      expect(response.status).toBe(200);
      let text = "";
      for await (const chunk of response.body) text += Buffer.from(chunk).toString("utf8");
      response.destroy();
      expect(text).toBe('{"ok":true}');

      // `provider.invalid` cannot resolve, so reaching the origin proves the
      // socket went to the pinned address; the Host header is still the
      // intended virtual host, never the IP and never the caller's spoof.
      expect(seen).toEqual([{
        method: "POST",
        host: `provider.invalid:${address.port}`,
        url: "/v1/chat/completions",
        contentType: "application/json",
        body: JSON.stringify({ model: "test-model" })
      }]);
    } finally {
      server.closeAllConnections();
      await new Promise<void>((resolve) => server.close(() => resolve()));
    }
  });

  it("does not reuse an agent connection across requests", async () => {
    // Two POSTs to the same pinned origin must open two sockets, so a socket
    // validated against an older resolution can never be reused.
    const sockets = new Set<unknown>();
    const server = createServer((_request, response) => {
      response.writeHead(200, { "Content-Type": "application/json" });
      response.end("{}");
    });
    server.on("connection", (socket) => sockets.add(socket));
    await new Promise<void>((resolve) => server.listen(0, "127.0.0.1", () => resolve()));
    const address = server.address();
    if (address === null || typeof address === "string") throw new Error("unexpected server address");
    try {
      const transport = createNodeSafeHttpTransport();
      for (let index = 0; index < 2; index++) {
        const response = await transport.request({
          url: new URL(`http://provider.invalid:${address.port}/v1/messages`),
          hostHeader: `provider.invalid:${address.port}`,
          hostname: "provider.invalid",
          address: "127.0.0.1",
          family: 4,
          signal: new AbortController().signal,
          method: "POST",
          body: "{}"
        });
        for await (const _chunk of response.body) { /* drain */ }
        response.destroy();
      }
      expect(sockets.size).toBe(2);
    } finally {
      server.closeAllConnections();
      await new Promise<void>((resolve) => server.close(() => resolve()));
    }
  });
});

/* ------------------------------------------------------------------ */
/* One bound for every outbound request                                */
/* ------------------------------------------------------------------ */

describe("pinned POST concurrency", () => {
  it("shares the single outbound fetch bound with safeFetch", async () => {
    configureFetchConcurrency(1);
    const stalling = scriptedTransport(() => fakeResponse({ body: stalledBody() }));
    const download = safeFetch("https://slow.example/audio", {
      maxBytes: 1024, timeoutMs: 200, resolver: publicResolver, transport: stalling.transport
    });
    await waitUntil(() => fetchConcurrencyState().active === 1);

    const scripted = scriptedTransport(() => fakeResponse({ chunks: [utf8('{"ok":true}')] }));
    const request = safePinnedPost("https://provider.example/v1/chat/completions", {
      ...POST_OPTIONS, transport: scripted.transport, body: "{}"
    });
    await waitUntil(() => fetchConcurrencyState().queued === 1);
    // The provider POST waits for the download's slot instead of opening a new socket.
    expect(scripted.calls).toHaveLength(0);

    expect(failure(await download).error).toBe("timeout");
    const result = await request;
    expect(result.ok).toBe(true);
    expect(scripted.calls).toHaveLength(1);
    expect(fetchConcurrencyState()).toMatchObject({ active: 0, queued: 0 });
  });
});

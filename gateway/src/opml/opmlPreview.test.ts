// @ts-nocheck
import { afterEach, describe, expect, it, vi } from "vitest";
import { previewOpmlUrl } from "./opmlPreview";
import { parseOpmlDocument } from "./opmlParser";

const OPML = (feeds: Array<{ url: string; title?: string }>, folder?: string) => `<?xml version="1.0"?>
<opml version="2.0"><head><title>Subs</title></head><body>
${folder ? `<outline text="${folder}" title="${folder}">` : ""}
${feeds.map((feed) => `<outline type="rss" xmlUrl="${feed.url}" text="${feed.title ?? "Feed"}" />`).join("\n")}
${folder ? "</outline>" : ""}
</body></opml>`;

afterEach(() => {
  vi.unstubAllGlobals();
});

describe("opmlParser", () => {
  it("parses feeds, folders, custom fields, and dedupes within the document", () => {
    const raw = `<?xml version="1.0"?>
<opml version="2.0"><body>
<outline text="Tech" title="Tech">
<outline type="rss" xmlUrl="HTTPS://Example.COM/Feed.xml" text="Tech Feed" wristbriefEnabled="false" wristbriefSendToWatch="false" wristbriefWatchKeywords="kotlin, android" />
</outline>
<outline type="rss" xmlUrl="https://example.com/Feed.xml" text="Duplicate" />
<outline type="rss" xmlUrl="http://insecure.example/rss" text="Not HTTPS" />
</body></opml>`;
    const parsed = parseOpmlDocument(raw);
    expect(parsed.ok).toBe(true);
    expect(parsed.feeds).toHaveLength(1);
    // Scheme and host are normalized (lowercased); the path stays case-sensitive,
    // matching the mobile normalizeFeedUrl semantics.
    expect(parsed.feeds[0].url).toBe("https://example.com/Feed.xml");
    expect(parsed.feeds[0].category).toBe("Tech");
    expect(parsed.feeds[0].enabled).toBe(false);
    expect(parsed.feeds[0].sendToWatch).toBe(false);
    expect(parsed.feeds[0].watchKeywords).toEqual(["kotlin", "android"]);
    expect(parsed.duplicatesWithinImport).toBe(1);
    expect(parsed.invalidFeedUrls).toEqual(["http://insecure.example/rss"]);
  });

  it("rejects missing root, DOCTYPE/ENTITY, oversize, and too many feeds", () => {
    expect(parseOpmlDocument("<html><body>nope</body></html>").code).toBe("MISSING_ROOT");
    expect(parseOpmlDocument(`<!DOCTYPE opml SYSTEM "evil.dtd"><opml><body/></opml>`).code).toBe("UNSUPPORTED_ENTITY");
    expect(parseOpmlDocument("<!ENTITY x " + "y".repeat(100) + "><opml><body/></opml>").code).toBe("UNSUPPORTED_ENTITY");
    expect(parseOpmlDocument("x".repeat(2_000_001)).code).toBe("TOO_LARGE");
    const many = Array.from({ length: 1001 }, (_, i) => `<outline xmlUrl="https://example.com/${i}/rss" />`).join("");
    expect(parseOpmlDocument(`<opml><body>${many}</body></opml>`).code).toBe("TOO_MANY_FEEDS");
    expect(parseOpmlDocument(`<opml><body><outline xmlUrl="https://example.com/rss"`).code).toBe("MALFORMED_OUTLINE");
  });
});

describe("previewOpmlUrl", () => {
  it("returns a preview with source, feeds, and summary", async () => {
    vi.stubGlobal("fetch", vi.fn(async () => new Response(OPML([{ url: "https://a.example/rss", title: "A" }, { url: "https://b.example/rss", title: "B" }], "News"), { status: 200 })));
    const result = await previewOpmlUrl("https://example.com/subscriptions.opml");
    expect(result.ok).toBe(true);
    expect(result.body.source).toEqual({ type: "url", displayUrl: "https://example.com/subscriptions.opml" });
    expect(result.body.feeds).toHaveLength(2);
    expect(result.body.feeds[0].category).toBe("News");
    expect(result.body.summary).toEqual({ total: 2, valid: 2, duplicate: 0, rejected: 0 });
  });

  it("reports invalid feed URLs and duplicates in the summary", async () => {
    vi.stubGlobal("fetch", vi.fn(async () => new Response(OPML([{ url: "https://a.example/rss" }, { url: "https://a.example/rss" }, { url: "http://insecure.example/rss" }]), { status: 200 })));
    const result = await previewOpmlUrl("https://example.com/sub.opml");
    expect(result.ok).toBe(true);
    expect(result.body.summary.valid).toBe(1);
    expect(result.body.summary.duplicate).toBe(1);
    expect(result.body.summary.rejected).toBe(1);
    expect(result.body.rejected[0].reason).toBe("INVALID_FEED_URL");
  });

  it("maps fetch failures to the OPML error codes", async () => {
    vi.stubGlobal("fetch", vi.fn(async () => new Response(null, { status: 302, headers: { Location: "https://127.0.0.1/x" } })));
    const redirected = await previewOpmlUrl("https://example.com/sub.opml");
    expect(redirected.ok).toBe(false);
    expect(redirected.code).toBe("REDIRECT_BLOCKED");

    vi.stubGlobal("fetch", vi.fn(async () => new Response("server err", { status: 500 })));
    const httpError = await previewOpmlUrl("https://example.com/sub.opml");
    expect(httpError.code).toBe("HTTP_ERROR");
    expect(httpError.status).toBe(502);

    const blocked = await previewOpmlUrl("https://192.168.0.10/sub.opml");
    expect(blocked.code).toBe("FETCH_BLOCKED_HOST");

    const badRequest = await previewOpmlUrl(42);
    expect(badRequest.code).toBe("INVALID_URL");
    expect(badRequest.status).toBe(400);
  });

  it("maps parse failures to the OPML error codes", async () => {
    vi.stubGlobal("fetch", vi.fn(async () => new Response("<html>not opml</html>", { status: 200 })));
    const result = await previewOpmlUrl("https://example.com/sub.opml");
    expect(result.ok).toBe(false);
    expect(result.code).toBe("MISSING_ROOT");
    expect(result.status).toBe(400);
  });
});

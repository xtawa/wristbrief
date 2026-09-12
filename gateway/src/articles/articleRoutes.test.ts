// @ts-nocheck
import { afterEach, describe, expect, it, vi } from "vitest";
import { htmlToArticleDocument } from "./articleExtractor";
import { handleArticleResolve, handleArticleGet } from "./articleRoutes";
import { handleMediaGet } from "./mediaProxy";
import { mediaIdForUrl, urlForMediaId, articleKeyFor } from "./articleTypes";
import { createMigratedTestDb } from "../testDbHelper";

function fakeR2() {
  const objects = new Map();
  return {
    objects,
    async get(key) {
      const entry = objects.get(key);
      if (!entry) return null;
      return {
        body: entry.body,
        size: entry.body.length,
        httpMetadata: { contentType: entry.contentType },
        async text() {
          return entry.body;
        }
      };
    },
    async put(key, body, options) {
      objects.set(key, { body: typeof body === "string" ? body : await new Response(body).text(), contentType: options?.httpMetadata?.contentType ?? "application/octet-stream" });
    }
  };
}

function d1() {
  return createMigratedTestDb().d1;
}

const LONG_CONTENT = "<p>" + "A".repeat(600) + "</p><p>Second paragraph with <b>bold</b> text.</p>";

afterEach(() => {
  vi.unstubAllGlobals();
});

describe("htmlToArticleDocument", () => {
  it("produces structured blocks from allowlisted HTML", () => {
    const html = `
      <html><body><article>
        <h1>Title stays in meta</h1>
        <script>alert("evil")</script>
        <p>First <strong>bold</strong> and <em>italic</em> and <code>inline code</code>.</p>
        <h2>Section</h2>
        <ul><li>One</li><li>Two</li></ul>
        <ol><li>First</li></ol>
        <blockquote>Quoted wisdom</blockquote>
        <pre><code>val x = 1</code></pre>
        <img src="/images/pic.png" alt="A picture">
        <hr>
        <p>Visit <a href="https://example.com/page">our site</a> or <a href="javascript:alert(1)">bad link</a>.</p>
        <iframe src="https://evil.example"></iframe>
      </article></body></html>
    `;
    const doc = htmlToArticleDocument(html, { title: "The Title" }, "https://blog.example.com/post/1");
    const types = doc.blocks.map((block) => block.type);
    expect(types).toContain("paragraph");
    expect(types).toContain("heading");
    expect(types).toContain("unordered_list");
    expect(types).toContain("ordered_list");
    expect(types).toContain("quote");
    expect(types).toContain("code_block");
    expect(types).toContain("image");
    expect(types).toContain("divider");
    expect(types).not.toContain("script" as any);
    // Script/iframe content never survives.
    expect(JSON.stringify(doc)).not.toContain("alert");
    expect(JSON.stringify(doc)).not.toContain("evil.example");

    // Inline: bold/italic/code spans, http links only.
    const firstParagraph = doc.blocks.find((block) => block.type === "paragraph" && block.spans.some((span) => span.type === "bold"));
    expect(firstParagraph.spans.find((span) => span.type === "bold").text).toBe("bold");
    const linkParagraph = doc.blocks.find((block) => block.type === "paragraph" && block.spans.some((span) => span.type === "link"));
    const link = linkParagraph.spans.find((span) => span.type === "link");
    expect(link.url).toBe("https://example.com/page");
    expect(JSON.stringify(doc)).not.toContain("javascript:");

    // Images resolve relative to the base URL and become opaque media ids.
    const image = doc.blocks.find((block) => block.type === "image");
    expect(urlForMediaId(image.mediaId)).toBe("https://blog.example.com/images/pic.png");
    expect(image.alt).toBe("A picture");

    // Metadata is carried through.
    expect(doc.title).toBe("The Title");
    expect(doc.canonicalUrl).toBe("https://blog.example.com/post/1");
  });
});

describe("article resolve routes", () => {
  it("uses long RSS content directly (Level 1) and caches it", async () => {
    const env = { ACCOUNT_DB: d1(), TRANSCRIPTS_BUCKET: fakeR2() };
    const first = await handleArticleResolve(env, { url: "https://blog.example.com/post/1", content: LONG_CONTENT, title: "Post" });
    expect(first.status).toBe(200);
    expect(first.body.source).toBe("rss");
    expect(first.body.document.blocks.length).toBeGreaterThan(0);
    const key = first.body.articleKey;

    const second = await handleArticleGet(env, key);
    expect(second.status).toBe(200);
    expect(second.body.document.title).toBe("Post");

    // The original URL never leaks as a fetch target — nothing was fetched.
  });

  it("falls back to server-side extraction when RSS content is short", async () => {
    const env = { ACCOUNT_DB: d1(), TRANSCRIPTS_BUCKET: fakeR2() };
    const pageHtml = `<html><body><article>${"<p>" + "B".repeat(800) + "</p>"}</article></body></html>`;
    vi.stubGlobal("fetch", vi.fn(async () => new Response(pageHtml, { status: 200, headers: { "Content-Type": "text/html" } })));
    const result = await handleArticleResolve(env, { url: "https://news.example.com/story", content: "<p>too short</p>", title: "Story" });
    expect(result.status).toBe(200);
    expect(result.body.source).toBe("extraction");
    expect(result.body.document.blocks[0].spans[0].text).toContain("B".repeat(10));
  });

  it("extracts when no RSS content is provided, refusing blocked hosts", async () => {
    const env = { ACCOUNT_DB: d1(), TRANSCRIPTS_BUCKET: fakeR2() };
    const pageHtml = `<html><body><main>${"<p>" + "C".repeat(700) + "</p>"}</main></body></html>`;
    vi.stubGlobal("fetch", vi.fn(async () => new Response(pageHtml, { status: 200, headers: { "Content-Type": "text/html" } })));
    const ok = await handleArticleResolve(env, { url: "https://news.example.com/only-page", title: "Only" });
    expect(ok.status).toBe(200);
    expect(ok.body.source).toBe("extraction");

    vi.stubGlobal("fetch", vi.fn());
    const blocked = await handleArticleResolve(env, { url: "https://192.168.0.5/private", title: "Nope" });
    expect(blocked.status).toBe(422);
    expect(blocked.body.error).toBe("content_unavailable");
  });
});

describe("media proxy", () => {
  it("proxies validated images and caches them in R2", async () => {
    const bucket = fakeR2();
    const env = { ACCOUNT_DB: d1(), TRANSCRIPTS_BUCKET: bucket };
    const png = new Uint8Array([0x89, 0x50, 0x4e, 0x47]);
    vi.stubGlobal("fetch", vi.fn(async () => new Response(png, { status: 200, headers: { "Content-Type": "image/png" } })));

    const mediaId = mediaIdForUrl("https://cdn.example.com/pic.png");
    const response = await handleMediaGet(new Request("https://gateway.test/v1/media/" + mediaId), env, mediaId);
    expect(response.status).toBe(200);
    expect(response.headers.get("Content-Type")).toBe("image/png");
    expect(bucket.objects.size).toBe(1);

    // Second call is served from R2 without a fetch.
    vi.stubGlobal("fetch", vi.fn(async () => {
      throw new Error("should not fetch");
    }));
    const cached = await handleMediaGet(new Request("https://gateway.test/v1/media/" + mediaId), env, mediaId);
    expect(cached.status).toBe(200);
  });

  it("refuses invalid media ids, non-image content, and blocked hosts", async () => {
    const env = { ACCOUNT_DB: d1(), TRANSCRIPTS_BUCKET: fakeR2() };
    const bad = await handleMediaGet(new Request("https://gateway.test/v1/media/%%%"), env, "%%%");
    expect(bad.status).toBe(400);

    vi.stubGlobal("fetch", vi.fn(async () => new Response("<html>not an image</html>", { status: 200, headers: { "Content-Type": "text/html" } })));
    const wrongType = await handleMediaGet(new Request("https://gateway.test/v1/media/" + mediaIdForUrl("https://cdn.example.com/doc.html")), env, mediaIdForUrl("https://cdn.example.com/doc.html"));
    expect(wrongType.status).toBe(415);

    const blocked = await handleMediaGet(new Request("https://gateway.test/v1/media/" + mediaIdForUrl("https://127.0.0.1/x.png")), env, mediaIdForUrl("https://127.0.0.1/x.png"));
    expect(blocked.status).toBe(404);
  });
});

describe("media id round trip", () => {
  it("is url-safe and reversible", async () => {
    const url = "https://cdn.example.com/a/b/c.png?w=100&h=50";
    const mediaId = mediaIdForUrl(url);
    expect(mediaId).toMatch(/^[A-Za-z0-9_-]+$/);
    expect(urlForMediaId(mediaId)).toBe(url);
    expect(await articleKeyFor(url)).toMatch(/^[a-f0-9]{64}$/);
  });
});

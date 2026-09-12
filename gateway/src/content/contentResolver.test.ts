import { describe, expect, it } from "vitest";
import { createMigratedTestDb } from "../testDbHelper";
import { ContentResolver, normalizeAudioUrl } from "./contentResolver";
import { D1ContentStore } from "./contentStore";
import { isValidContentCode } from "./contentCode";

describe("ContentResolver & ContentStore", () => {
  it("normalizes audio URL by stripping tracking parameters", () => {
    const raw = "https://cdn.example.com/audio.mp3?utm_source=rss&utm_medium=feed&token=123#t=10";
    const normalized = normalizeAudioUrl(raw);
    expect(normalized).toBe("https://cdn.example.com/audio.mp3?token=123");
  });

  it("resolves a new episode into a canonical content row with aliases", async () => {
    const { d1 } = createMigratedTestDb();
    const store = new D1ContentStore(d1);
    const resolver = new ContentResolver(store, { publicReuseHosts: ["example.com"] });

    const result = await resolver.resolve({
      sharePolicy: "PUBLIC_REUSE",
      feedUrl: "https://example.com/feed.xml",
      guid: "ep-001",
      audioUrl: "https://cdn.example.com/ep001.mp3?utm_source=test",
      title: "Episode 1: The Beginning",
      durationMs: 3600000
    });

    expect(result.isNew).toBe(true);
    expect(result.contentId).toMatch(/^cnt_/);
    expect(isValidContentCode(result.contentCode)).toBe(true);
    expect(result.content.canonicalTitle).toBe("Episode 1: The Beginning");
    expect(result.content.canonicalDurationMs).toBe(3600000);
    expect(result.content.sharePolicy).toBe("PUBLIC_REUSE");

    // Second resolution with the same audio URL returns existing content
    const second = await resolver.resolve({
      audioUrl: "https://cdn.example.com/ep001.mp3",
      title: "Different Title"
    });

    expect(second.isNew).toBe(false);
    expect(second.contentId).toBe(result.contentId);
    expect(second.contentCode).toBe(result.contentCode);
  });

  it("resolves via feedUrl + guid alias", async () => {
    const { d1 } = createMigratedTestDb();
    const store = new D1ContentStore(d1);
    const resolver = new ContentResolver(store);

    const first = await resolver.resolve({
      feedUrl: "https://example.com/feed.xml",
      guid: "ep-002",
      audioUrl: "https://cdn.example.com/ep002-v1.mp3"
    });

    // Same feed + guid with different audio URL
    const second = await resolver.resolve({
      feedUrl: "https://example.com/feed.xml",
      guid: "ep-002",
      audioUrl: "https://cdn.example.com/ep002-v2.mp3"
    });

    expect(second.isNew).toBe(false);
    expect(second.contentId).toBe(first.contentId);
  });

  it("resolves via verified audio SHA-256 fingerprint", async () => {
    const { d1 } = createMigratedTestDb();
    const store = new D1ContentStore(d1);
    const resolver = new ContentResolver(store);

    const sha = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";
    const first = await resolver.resolve({
      audioUrl: "https://cdn.example.com/first.mp3",
      audioSha256: sha
    });

    const second = await resolver.resolve({
      audioUrl: "https://cdn.different.com/second.mp3",
      audioSha256: sha
    });

    expect(second.isNew).toBe(false);
    expect(second.contentId).toBe(first.contentId);
  });

  it("fails closed to private content when public reuse host is not allowlisted", async () => {
    const { d1 } = createMigratedTestDb();
    const resolver = new ContentResolver(new D1ContentStore(d1), { publicReuseHosts: ["trusted.example"] });
    const result = await resolver.resolve({
      sharePolicy: "PUBLIC_REUSE",
      feedUrl: "https://untrusted.example/feed.xml",
      audioUrl: "https://cdn.example.com/episode.mp3"
    });
    expect(result.content.sharePolicy).toBe("PRIVATE_ACCOUNT");
  });
});

import { describe, expect, it } from "vitest";
import type { SummaryOutput } from "./provider";
import {
  InMemorySummaryCache,
  buildSummaryCacheKey,
  summarizeWithCache,
  summaryCacheTtlSeconds
} from "./summaryCache";

const output: SummaryOutput = {
  summary: "A concise brief.",
  model: "test-model",
  structured: {
    tiny: "Tiny brief.",
    brief: "A concise brief.",
    bullets: ["Fact one"],
    topics: ["test"],
    sourceLanguage: "en",
    outputLanguage: "en",
    schemaVersion: "1",
    promptVersion: "1"
  }
};

describe("summary cache", () => {
  it("normalizes equivalent content into the same key", async () => {
    const first = await buildSummaryCacheKey({ title: "  Title ", content: "line 1\r\nline   2", language: "AUTO" });
    const second = await buildSummaryCacheKey({ title: "Title", content: "line 1\nline 2", language: "auto" });
    expect(first).toBe(second);
    expect(first).toMatch(/^summary:v1:[0-9a-f]{64}$/);
    expect(first).not.toContain("line 1");
  });

  it("invalidates when language, prompt version, or schema version changes", async () => {
    const base = { title: "T", content: "C", language: "auto" };
    const key = await buildSummaryCacheKey(base);
    expect(await buildSummaryCacheKey({ ...base, language: "zh-CN" })).not.toBe(key);
    expect(await buildSummaryCacheKey({ ...base, promptVersion: "2" })).not.toBe(key);
    expect(await buildSummaryCacheKey({ ...base, schemaVersion: "2" })).not.toBe(key);
  });

  it("bypasses the producer on a cache hit", async () => {
    const cache = new InMemorySummaryCache();
    let calls = 0;
    const producer = async () => {
      calls += 1;
      return output;
    };
    const first = await summarizeWithCache(cache, "key", 60, producer);
    const second = await summarizeWithCache(cache, "key", 60, producer);
    expect(first).toEqual(output);
    expect(second).toEqual(output);
    expect(calls).toBe(1);
  });

  it("expires in-memory entries after TTL", async () => {
    let now = 1_000;
    const cache = new InMemorySummaryCache(() => now);
    await cache.put("key", output, 60);
    expect(await cache.get("key")).toEqual(output);
    now += 60_000;
    expect(await cache.get("key")).toBeNull();
  });

  it("uses a bounded configurable TTL", () => {
    expect(summaryCacheTtlSeconds({})).toBe(86_400);
    expect(summaryCacheTtlSeconds({ SUMMARY_CACHE_TTL_SECONDS: "10" })).toBe(60);
    expect(summaryCacheTtlSeconds({ SUMMARY_CACHE_TTL_SECONDS: "99999999" })).toBe(2_592_000);
  });
});

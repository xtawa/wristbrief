import { parseStructuredBrief, BRIEF_PROMPT_VERSION, BRIEF_SCHEMA_VERSION } from "./structuredBrief";
import type { SummaryOutput } from "./provider";

export const DEFAULT_SUMMARY_CACHE_TTL_SECONDS = 24 * 60 * 60;
const MIN_KV_TTL_SECONDS = 60;
const MAX_CACHE_TTL_SECONDS = 30 * 24 * 60 * 60;

export type SummaryCacheEnv = {
  SUMMARY_CACHE?: Pick<KVNamespace, "get" | "put">;
  SUMMARY_CACHE_TTL_SECONDS?: string;
};

export interface SummaryCache {
  get(key: string): Promise<SummaryOutput | null>;
  put(key: string, value: SummaryOutput, ttlSeconds: number): Promise<void>;
}

export type SummaryCacheKeyInput = {
  title?: string;
  content: string;
  language: string;
  promptVersion?: string;
  schemaVersion?: string;
};

export async function buildSummaryCacheKey(input: SummaryCacheKeyInput): Promise<string> {
  const normalized = JSON.stringify({
    title: normalizeText(input.title ?? ""),
    content: normalizeText(input.content),
    language: normalizeLanguage(input.language),
    promptVersion: input.promptVersion ?? BRIEF_PROMPT_VERSION,
    schemaVersion: input.schemaVersion ?? BRIEF_SCHEMA_VERSION
  });
  const digest = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(normalized));
  return `summary:v1:${toHex(new Uint8Array(digest))}`;
}

export function summaryCacheTtlSeconds(env: SummaryCacheEnv): number {
  const parsed = Number(env.SUMMARY_CACHE_TTL_SECONDS);
  if (!Number.isFinite(parsed)) return DEFAULT_SUMMARY_CACHE_TTL_SECONDS;
  return Math.min(MAX_CACHE_TTL_SECONDS, Math.max(MIN_KV_TTL_SECONDS, Math.trunc(parsed)));
}

export function createSummaryCache(env: SummaryCacheEnv): SummaryCache | null {
  return env.SUMMARY_CACHE ? new KvSummaryCache(env.SUMMARY_CACHE) : null;
}

export async function summarizeWithCache(
  cache: SummaryCache | null,
  key: string,
  ttlSeconds: number,
  producer: () => Promise<SummaryOutput>
): Promise<SummaryOutput> {
  if (!cache) return producer();
  const cached = await cache.get(key);
  if (cached) return cached;
  const produced = await producer();
  await cache.put(key, produced, ttlSeconds);
  return produced;
}

export class KvSummaryCache implements SummaryCache {
  constructor(private readonly namespace: Pick<KVNamespace, "get" | "put">) {}

  async get(key: string): Promise<SummaryOutput | null> {
    const raw = await this.namespace.get(key, "text");
    return raw ? decodeSummaryOutput(raw) : null;
  }

  async put(key: string, value: SummaryOutput, ttlSeconds: number): Promise<void> {
    await this.namespace.put(key, JSON.stringify(value), { expirationTtl: ttlSeconds });
  }
}

export class InMemorySummaryCache implements SummaryCache {
  private readonly entries = new Map<string, { value: SummaryOutput; expiresAt: number }>();

  constructor(private readonly now: () => number = Date.now) {}

  async get(key: string): Promise<SummaryOutput | null> {
    const entry = this.entries.get(key);
    if (!entry) return null;
    if (entry.expiresAt <= this.now()) {
      this.entries.delete(key);
      return null;
    }
    return entry.value;
  }

  async put(key: string, value: SummaryOutput, ttlSeconds: number): Promise<void> {
    this.entries.set(key, { value, expiresAt: this.now() + ttlSeconds * 1000 });
  }
}

function decodeSummaryOutput(raw: string): SummaryOutput | null {
  try {
    const parsed = JSON.parse(raw) as { summary?: unknown; model?: unknown; structured?: unknown };
    if (typeof parsed.summary !== "string" || !parsed.summary.trim()) return null;
    if (typeof parsed.model !== "string" || !parsed.model.trim()) return null;
    const structured = parseStructuredBrief(JSON.stringify(parsed.structured));
    if (!structured) return null;
    return { summary: parsed.summary, model: parsed.model, structured };
  } catch {
    return null;
  }
}

function normalizeText(value: string): string {
  return value.replace(/\r\n?/g, "\n").replace(/[\t ]+/g, " ").replace(/\n{3,}/g, "\n\n").trim();
}

function normalizeLanguage(value: string): string {
  const normalized = value.trim().toLowerCase();
  return normalized || "auto";
}

function toHex(bytes: Uint8Array): string {
  return Array.from(bytes, byte => byte.toString(16).padStart(2, "0")).join("");
}

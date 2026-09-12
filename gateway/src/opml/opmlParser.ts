/**
 * Gateway-side OPML parser mirroring the mobile Opml.kt rules exactly:
 * 2,000,000 chars / 1,000 feeds / DOCTYPE+ENTITY rejection / HTTPS feed URL
 * normalization / in-import dedupe / category folders + WristBrief custom
 * fields. The mobile parser stays the source for local files; this one exists
 * so /v1/opml/preview-url can preview a remote document server-side without
 * the client ever fetching the URL itself.
 */
const MAX_OPML_CHARS = 2_000_000;
const MAX_OPML_FEEDS = 1_000;

export type OpmlFeedEntry = {
  title: string;
  url: string;
  enabled: boolean;
  sendToWatch: boolean;
  category: string | null;
  watchKeywords: string[];
};

export type OpmlParseOk = {
  ok: true;
  feeds: OpmlFeedEntry[];
  duplicatesWithinImport: number;
  invalidFeedUrls: string[];
};

export type OpmlParseErrorCode = "TOO_LARGE" | "UNSUPPORTED_ENTITY" | "MISSING_ROOT" | "MALFORMED_OUTLINE" | "TOO_MANY_FEEDS";

export type OpmlParseError = { ok: false; code: OpmlParseErrorCode };

export function parseOpmlDocument(raw: string): OpmlParseOk | OpmlParseError {
  if (raw.length > MAX_OPML_CHARS) return { ok: false, code: "TOO_LARGE" };
  if (!/<\s*opml\b/i.test(raw)) return { ok: false, code: "MISSING_ROOT" };
  if (/<!\s*(DOCTYPE|ENTITY)\b/i.test(raw)) return { ok: false, code: "UNSUPPORTED_ENTITY" };

  const feeds: OpmlFeedEntry[] = [];
  const seenUrls = new Set<string>();
  const invalidFeedUrls: string[] = [];
  let duplicates = 0;
  const folderStack: (string | null)[] = [];

  const outlineTag = /<\s*\/?\s*outline\b/gi;
  outlineTag.lastIndex = 0;
  let match: RegExpExecArray | null;
  while ((match = outlineTag.exec(raw)) !== null) {
    const tagEnd = findTagEnd(raw, match.index + match[0].length);
    if (tagEnd < 0) return { ok: false, code: "MALFORMED_OUTLINE" };
    const tag = raw.slice(match.index, tagEnd + 1);

    if (/^<\s*\//i.test(tag)) {
      folderStack.pop();
      continue;
    }

    const attributes = parseAttributes(tag);
    const xmlUrl = attributes.get("xmlurl");
    const selfClosing = /\/\s*>$/.test(tag);
    if (xmlUrl !== undefined) {
      const normalized = normalizeFeedUrl(xmlUrl);
      if (normalized === null) {
        invalidFeedUrls.push(xmlUrl.slice(0, 256));
      } else if (seenUrls.has(normalized)) {
        duplicates += 1;
      } else {
        if (feeds.length >= MAX_OPML_FEEDS) return { ok: false, code: "TOO_MANY_FEEDS" };
        seenUrls.add(normalized);
        const title = (attributes.get("title") ?? attributes.get("text") ?? "").trim().slice(0, 160);
        const explicitCategory = attributes.get("wristbriefcategory");
        const folder = folderStack.length > 0 ? folderStack[folderStack.length - 1] : null;
        feeds.push({
          title,
          url: normalized,
          enabled: attributes.get("wristbriefenabled")?.toLowerCase() !== "false",
          sendToWatch: attributes.get("wristbriefsendtowatch")?.toLowerCase() !== "false",
          category: normalizeFeedCategory(explicitCategory ?? folder ?? undefined),
          watchKeywords: normalizeWatchKeywords(attributes.get("wristbriefwatchkeywords") ?? "")
        });
      }
    } else if (!selfClosing) {
      const folder = attributes.get("title") ?? attributes.get("text") ?? "";
      const normalizedFolder = normalizeFeedCategory(folder);
      folderStack.push(normalizedFolder ?? null);
    }
  }

  return { ok: true, feeds, duplicatesWithinImport: duplicates, invalidFeedUrls };
}

/** Mirrors mobile normalizeFeedUrl: HTTPS only, lowercase host, no userinfo. */
export function normalizeFeedUrl(raw: string): string | null {
  try {
    const parsed = new URL(raw.trim());
    if (parsed.protocol !== "https:") return null;
    if (!parsed.hostname || parsed.username || parsed.password) return null;
    parsed.protocol = "https:";
    parsed.hostname = parsed.hostname.toLowerCase();
    if (parsed.pathname === "") parsed.pathname = "/";
    parsed.hash = "";
    return parsed.toString();
  } catch {
    return null;
  }
}

function normalizeFeedCategory(raw: string | undefined | null): string | null {
  if (!raw) return null;
  const normalized = raw.trim().replace(/\s+/g, " ").slice(0, 80);
  return normalized.length > 0 ? normalized : null;
}

function normalizeWatchKeywords(raw: string): string[] {
  const seen = new Set<string>();
  return raw
    .split(/[,\n]/)
    .map((value) => value.trim().replace(/\s+/g, " ").slice(0, 48))
    .filter((value) => value.length > 0)
    .filter((value) => {
      const key = value.toLowerCase();
      if (seen.has(key)) return false;
      seen.add(key);
      return true;
    })
    .slice(0, 12);
}

const ATTRIBUTE_PATTERN = /([A-Za-z_:][A-Za-z0-9_.:-]*)\s*=\s*(?:"([^"]*)"|'([^']*)')/g;

function parseAttributes(tag: string): Map<string, string> {
  const attributes = new Map<string, string>();
  ATTRIBUTE_PATTERN.lastIndex = 0;
  let match: RegExpExecArray | null;
  while ((match = ATTRIBUTE_PATTERN.exec(tag)) !== null) {
    attributes.set(match[1].toLowerCase(), decodeXmlAttribute(match[2] ?? match[3] ?? ""));
  }
  return attributes;
}

const ENTITY_PATTERN = /&(#x[0-9A-Fa-f]+|#\d+|amp|quot|apos|lt|gt);/gi;

function decodeXmlAttribute(value: string): string {
  return value.replace(ENTITY_PATTERN, (entity, name: string) => {
    switch (name.toLowerCase()) {
      case "amp": return "&";
      case "quot": return "\"";
      case "apos": return "'";
      case "lt": return "<";
      case "gt": return ">";
      default: {
        const codePoint = name.toLowerCase().startsWith("#x")
          ? Number.parseInt(name.slice(2), 16)
          : Number.parseInt(name.slice(1), 10);
        return Number.isInteger(codePoint) && codePoint >= 0 && codePoint <= 0x10ffff
          ? String.fromCodePoint(codePoint)
          : entity;
      }
    }
  });
}

function findTagEnd(raw: string, start: number): number {
  let quote: string | null = null;
  for (let index = start; index < raw.length; index++) {
    const ch = raw[index];
    if (quote !== null) {
      if (ch === quote) quote = null;
    } else if (ch === "'" || ch === '"') {
      quote = ch;
    } else if (ch === ">") {
      return index;
    }
  }
  return -1;
}

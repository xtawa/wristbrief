import { ARTICLE_EXTRACTION_VERSION, type ArticleBlock, type ArticleDocument, type ArticleSourceMeta, type InlineSpan, mediaIdForUrl } from "./articleTypes";

/**
 * Allowlist-based HTML → ArticleDocument extractor/sanitizer. Workers has no
 * DOM parser, so this walks the tag stream directly:
 *
 *  - dropped with content: script, style, iframe, object, embed, svg, math, noscript, form, button, video, audio
 *  - dropped (transparent): every other non-allowlisted tag — its text still flows
 *  - allowed blocks: p, h1-h6, ul, ol, li, blockquote, pre, code, img, hr
 *  - allowed inline: a (http/https only), strong/b, em/i, code
 *  - all on* attributes and every other attribute are ignored by construction
 *
 * Images never expose their URL to clients: each becomes an opaque media id
 * served (and re-validated) by the server-side media proxy.
 */
const DROP_WITH_CONTENT = /<\s*(script|style|iframe|object|embed|svg|math|noscript|form|button|video|audio|template)\b[^>]*>[\s\S]*?<\s*\/\s*\1\s*>/gi;
const DROP_WITH_CONTENT_UNPAIRED = /<\s*(script|style|iframe|object|embed|svg|math|noscript|form|button|video|audio|template)\b[^>]*\/?\s*>/gi;
const COMMENTS = /<!--[\s\S]*?-->/g;

const REGION_ARTICLE = /<\s*article\b[^>]*>([\s\S]*?)<\s*\/\s*article\s*>/i;
const REGION_MAIN = /<\s*main\b[^>]*>([\s\S]*?)<\s*\/\s*main\s*>/i;
const REGION_BODY = /<\s*body\b[^>]*>([\s\S]*?)<\s*\/\s*body\s*>/i;

const TAG_PATTERN = /<\s*(\/?)\s*([a-zA-Z][a-zA-Z0-9]*)((?:[^>"']|"[^"]*"|'[^']*')*)>/g;
const HREF_PATTERN = /href\s*=\s*(?:"([^"]*)"|'([^']*)')/i;
const SRC_PATTERN = /src\s*=\s*(?:"([^"]*)"|'([^']*)')/i;
const ALT_PATTERN = /alt\s*=\s*(?:"([^"]*)"|'([^']*)')/i;

const MAX_BLOCKS = 500;
const MAX_SPANS_PER_BLOCK = 200;
const MAX_TEXT_LENGTH = 200_000;

export function htmlToArticleDocument(html: string, meta: ArticleSourceMeta, baseUrl: string): ArticleDocument {
  const cleaned = html
    .replace(COMMENTS, "")
    .replace(DROP_WITH_CONTENT, "")
    .replace(DROP_WITH_CONTENT_UNPAIRED, "");

  const region = REGION_ARTICLE.exec(cleaned)?.[1] ?? REGION_MAIN.exec(cleaned)?.[1] ?? REGION_BODY.exec(cleaned)?.[1] ?? cleaned;

  const blocks: ArticleBlock[] = [];
  let spans: InlineSpan[] = [];
  let boldDepth = 0;
  let italicDepth = 0;
  let codeDepth = 0;
  const linkStack: (string | null)[] = [];
  let listMode: "ul" | "ol" | null = null;
  let listItems: InlineSpan[][] = [];

  const flushParagraph = (as: "paragraph" | "quote" = "paragraph") => {
    if (spans.length === 0) return;
    blocks.push({ type: as, spans: finalizeSpans(spans).slice(0, MAX_SPANS_PER_BLOCK) });
    spans = [];
  };

  const flushList = () => {
    if (listItems.length > 0) {
      blocks.push({ type: listMode === "ol" ? "ordered_list" : "unordered_list", items: listItems.slice(0, 100) });
    }
    listItems = [];
    listMode = null;
  };

  let textLength = 0;
  const budgetExceeded = () => blocks.length >= MAX_BLOCKS || textLength >= MAX_TEXT_LENGTH;

  TAG_PATTERN.lastIndex = 0;
  let match: RegExpExecArray | null;
  let cursor = 0;
  while ((match = TAG_PATTERN.exec(region)) !== null) {
    if (budgetExceeded()) break;
    const text = decodeEntities(region.slice(cursor, match.index));
    if (text.trim().length > 0 || (spans.length > 0 && text.length > 0)) {
      pushText(spans, text, () => ({ bold: boldDepth > 0, italic: italicDepth > 0, code: codeDepth > 0, link: linkStack.length > 0 ? linkStack[linkStack.length - 1] : null }));
      textLength += text.length;
    }
    cursor = match.index + match[0].length;

    const closing = match[1] === "/";
    const tag = match[2].toLowerCase();
    const attributes = match[3] ?? "";

    switch (tag) {
      case "p":
      case "article":
      case "main":
      case "body":
      case "div":
      case "section":
      case "header":
      case "footer":
      case "aside":
      case "figure":
      case "figcaption":
      case "table":
      case "tbody":
      case "tr":
      case "td":
      case "th":
      case "span":
      case "font":
        // Containers are transparent; a close simply ends the current paragraph.
        if (closing) flushParagraph();
        break;
      case "h1":
      case "h2":
      case "h3":
      case "h4":
      case "h5":
      case "h6":
        if (closing) {
          const level = Number(tag[1]) as 1 | 2 | 3 | 4 | 5 | 6;
          if (spans.length > 0) {
            blocks.push({ type: "heading", level, spans: finalizeSpans(spans).slice(0, MAX_SPANS_PER_BLOCK) });
            spans = [];
          }
        }
        break;
      case "blockquote":
        if (closing) flushParagraph("quote");
        break;
      case "ul":
      case "ol":
        if (closing) {
          flushList();
        } else {
          flushParagraph();
          listMode = tag === "ol" ? "ol" : "ul";
          listItems = [];
        }
        break;
      case "li":
        if (closing) {
          listItems.push(finalizeSpans(spans).slice(0, MAX_SPANS_PER_BLOCK));
          spans = [];
        } else if (listMode !== null) {
          spans = [];
        }
        break;
      case "pre":
        // <pre> content is captured verbatim until </pre>.
        {
          const remainder = region.slice(cursor);
          const end = /<\s*\/\s*pre\s*>/i.exec(remainder);
          const raw = end ? remainder.slice(0, end.index) : remainder;
          const codeText = decodeEntities(raw.replace(/<[^>]+>/g, "")).trim().slice(0, 20_000);
          if (codeText.length > 0) blocks.push({ type: "code_block", text: codeText });
          cursor = end ? cursor + end.index + end[0].length : region.length;
          TAG_PATTERN.lastIndex = cursor;
        }
        break;
      case "hr":
        blocks.push({ type: "divider" });
        break;
      case "img": {
        if (closing) break;
        const src = SRC_PATTERN.exec(attributes)?.[1] ?? SRC_PATTERN.exec(attributes)?.[2];
        if (!src) break;
        const absolute = resolveUrl(baseUrl, decodeEntities(src));
        if (!absolute || !(absolute.startsWith("http://") || absolute.startsWith("https://"))) break;
        const alt = decodeEntities(ALT_PATTERN.exec(attributes)?.[1] ?? "").slice(0, 500);
        flushParagraph();
        blocks.push({ type: "image", mediaId: mediaIdForUrl(absolute), alt });
        break;
      }
      case "strong":
      case "b":
        boldDepth = closing ? Math.max(0, boldDepth - 1) : boldDepth + 1;
        break;
      case "em":
      case "i":
        italicDepth = closing ? Math.max(0, italicDepth - 1) : italicDepth + 1;
        break;
      case "code":
        codeDepth = closing ? Math.max(0, codeDepth - 1) : codeDepth + 1;
        break;
      case "a": {
        if (closing) {
          linkStack.pop();
        } else {
          const href = HREF_PATTERN.exec(attributes);
          const url = href ? resolveUrl(baseUrl, decodeEntities(href[1] ?? href[2] ?? "")) : null;
          // Only http/https links survive; everything else renders as plain text.
          linkStack.push(url && (url.startsWith("http://") || url.startsWith("https://")) ? url : null);
        }
        break;
      }
      case "br":
        pushText(spans, "\n", () => ({ bold: boldDepth > 0, italic: italicDepth > 0, code: codeDepth > 0, link: linkStack.length > 0 ? linkStack[linkStack.length - 1] : null }));
        break;
      default:
        break;
    }
  }

  if (!budgetExceeded()) {
    const tail = decodeEntities(region.slice(cursor));
    if (tail.trim().length > 0) {
      pushText(spans, tail, () => ({ bold: boldDepth > 0, italic: italicDepth > 0, code: codeDepth > 0, link: linkStack.length > 0 ? linkStack[linkStack.length - 1] : null }));
    }
  }
  flushParagraph();
  flushList();

  return {
    title: meta.title.slice(0, 500),
    author: meta.author?.slice(0, 200) ?? null,
    publishedAt: meta.publishedAt ?? null,
    canonicalUrl: baseUrl,
    sourceName: meta.sourceName?.slice(0, 200) ?? null,
    blocks,
    extractionVersion: ARTICLE_EXTRACTION_VERSION
  };
}

const ENTITY_PATTERN = /&(#x[0-9A-Fa-f]+|#\d+|amp|quot|apos|lt|gt|nbsp);/gi;

function decodeEntities(value: string): string {
  return value
    .replace(ENTITY_PATTERN, (entity, name: string) => {
      switch (name.toLowerCase()) {
        case "amp": return "&";
        case "quot": return "\"";
        case "apos": return "'";
        case "lt": return "<";
        case "gt": return ">";
        case "nbsp": return " ";
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

type FormatSnapshot = { bold: boolean; italic: boolean; code: boolean; link: string | null };

function pushText(spans: InlineSpan[], rawText: string, format: () => FormatSnapshot): void {
  const text = rawText.replace(/[ \t\r\n]+/g, " ");
  if (text.length === 0) return;
  const snapshot = format();
  if (snapshot.link) {
    spans.push({ type: "link", text, url: snapshot.link });
  } else if (snapshot.code) {
    spans.push({ type: "code", text });
  } else if (snapshot.bold && snapshot.italic) {
    spans.push({ type: "bold", text });
    spans.push({ type: "italic", text });
  } else if (snapshot.bold) {
    spans.push({ type: "bold", text });
  } else if (snapshot.italic) {
    spans.push({ type: "italic", text });
  } else {
    spans.push({ type: "text", text });
  }
}

function finalizeSpans(spans: InlineSpan[]): InlineSpan[] {
  const trimmed = [...spans];
  while (trimmed.length > 0) {
    const first = trimmed[0];
    if (first.type === "text" && first.text.trim().length === 0) {
      trimmed.shift();
      continue;
    }
    trimmed[0] = withText(first, first.text.replace(/^\s+/, ""));
    break;
  }
  while (trimmed.length > 0) {
    const last = trimmed[trimmed.length - 1];
    if (last.type === "text" && last.text.trim().length === 0) {
      trimmed.pop();
      continue;
    }
    trimmed[trimmed.length - 1] = withText(last, last.text.replace(/\s+$/, ""));
    break;
  }
  return trimmed;
}

function withText(span: InlineSpan, text: string): InlineSpan {
  return { ...span, text } as InlineSpan;
}

function resolveUrl(baseUrl: string, raw: string): string | null {
  if (!raw) return null;
  try {
    const resolved = new URL(raw, baseUrl);
    return resolved.toString();
  } catch {
    return null;
  }
}

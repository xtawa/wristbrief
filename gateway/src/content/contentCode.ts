const CROCKFORD_ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ";
const CODE_PREFIX = "WBEP-";

/**
 * Encodes a buffer or random bytes into Crockford Base32 string.
 */
export function encodeCrockfordBase32(bytes: Uint8Array): string {
  let bits = 0;
  let value = 0;
  let output = "";

  for (let i = 0; i < bytes.length; i++) {
    value = (value << 8) | bytes[i];
    bits += 8;
    while (bits >= 5) {
      output += CROCKFORD_ALPHABET[(value >>> (bits - 5)) & 31];
      bits -= 5;
    }
  }

  if (bits > 0) {
    output += CROCKFORD_ALPHABET[(value << (5 - bits)) & 31];
  }

  return output;
}

/**
 * Generates a stable public Content Code formatted as WBEP-XXXX-XXXX-XXXX.
 */
export function generateContentCode(customEntropy?: Uint8Array): string {
  const entropy = customEntropy ?? crypto.getRandomValues(new Uint8Array(10));
  const encoded = encodeCrockfordBase32(entropy).toUpperCase();
  const trimmed = encoded.slice(0, 16);
  // Format as WBEP-XXXX-XXXX-XXXX-XXXX
  return `${CODE_PREFIX}${trimmed.slice(0, 4)}-${trimmed.slice(4, 8)}-${trimmed.slice(8, 12)}-${trimmed.slice(12, 16)}`;
}

/**
 * Validates whether a given string is a syntactically valid Content Code.
 */
export function isValidContentCode(code: string): boolean {
  if (!code.startsWith(CODE_PREFIX)) return false;
  const raw = code.slice(CODE_PREFIX.length).replace(/-/g, "").toUpperCase();
  if (raw.length !== 16) return false;
  for (let i = 0; i < raw.length; i++) {
    if (!CROCKFORD_ALPHABET.includes(raw[i])) return false;
  }
  return true;
}

/**
 * Normalizes user-input or URL-provided Content Code by removing whitespace, uppercasing, and standardizing hyphens.
 */
export function normalizeContentCode(code: string): string | null {
  const cleaned = code.trim().toUpperCase().replace(/[^0-9A-Z]/g, "");
  let body = cleaned;
  if (body.startsWith("WBEP")) {
    body = body.slice(4);
  }
  // Crockford substitutions for readability: I/L -> 1, O -> 0
  body = body.replace(/[IL]/g, "1").replace(/O/g, "0");
  if (body.length !== 16) return null;
  for (let i = 0; i < body.length; i++) {
    if (!CROCKFORD_ALPHABET.includes(body[i])) return null;
  }
  return `${CODE_PREFIX}${body.slice(0, 4)}-${body.slice(4, 8)}-${body.slice(8, 12)}-${body.slice(12, 16)}`;
}

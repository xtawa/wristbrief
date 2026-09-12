import { describe, expect, it } from "vitest";
import {
  encodeCrockfordBase32,
  generateContentCode,
  isValidContentCode,
  normalizeContentCode
} from "./contentCode";

describe("Content Code & Crockford Base32", () => {
  it("encodes bytes to uppercase Crockford Base32 string", () => {
    const bytes = new Uint8Array([0, 1, 2, 3, 4]);
    const encoded = encodeCrockfordBase32(bytes);
    expect(encoded).toBeDefined();
    expect(typeof encoded).toBe("string");
    expect(encoded.length).toBeGreaterThan(0);
    // Does not include ambiguous characters (I, L, O, U)
    expect(encoded).not.toMatch(/[ILOU]/);
  });

  it("generates Content Code with WBEP- prefix and 4x4 blocks", () => {
    const code = generateContentCode();
    expect(code).toMatch(/^WBEP-[0-9A-Z]{4}-[0-9A-Z]{4}-[0-9A-Z]{4}-[0-9A-Z]{4}$/);
    expect(isValidContentCode(code)).toBe(true);
  });

  it("validates valid and invalid Content Codes", () => {
    expect(isValidContentCode("WBEP-7Q2M-4H9D-K8XR-1234")).toBe(true);
    expect(isValidContentCode("INVALID-CODE")).toBe(false);
    expect(isValidContentCode("WBEP-SHORT")).toBe(false);
    // Invalid character 'U'
    expect(isValidContentCode("WBEP-7Q2U-4H9D-K8XR-1234")).toBe(false);
  });

  it("normalizes user-input Content Code handling case, spaces, and substitutions", () => {
    // I -> 1, O -> 0
    const normalized = normalizeContentCode("wbep 7q2m 4h9d k8xr 1234");
    expect(normalized).toBe("WBEP-7Q2M-4H9D-K8XR-1234");

    const withSubstitutions = normalizeContentCode("wbep-7i2o-4l9d-k8xr-1234");
    expect(withSubstitutions).toBe("WBEP-7120-419D-K8XR-1234");

    expect(normalizeContentCode("too-short")).toBeNull();
  });
});

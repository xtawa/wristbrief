/**
 * Field-level validation shared by the JSON API and the server-rendered HTML
 * pages. There is exactly one validator per field, called from one place, so a
 * rule can never drift between the two front ends: the JSON route returns the
 * structured `fields` array and the HTML route renders the very same errors next
 * to the offending input.
 *
 * Every failure names the field and a stable machine reason. The route never
 * returns a bare "invalid request": an operator who mistypes a quota gets
 * `{field:"quotaLimit", reason:"out_of_range", min:0, max:10000}` and the page
 * shows it under that input.
 */

export type FieldError = { field: string; reason: string } & Record<string, unknown>;

export type InvalidRequest = { error: "invalid_request"; message: string; fields: FieldError[] };

export function invalidRequest(fields: FieldError[]): InvalidRequest {
  return {
    error: "invalid_request",
    message: fields.length === 1 ? describeFieldError(fields[0]!) : `Fix ${fields.length} fields and try again.`,
    fields
  };
}

export type StringFieldOptions = {
  required?: boolean;
  min?: number;
  max?: number;
  /** Trim before validating; the returned value is trimmed. Default true. */
  trim?: boolean;
  /** Reject C0/C1 control characters. Default true. */
  noControlCharacters?: boolean;
  /** Extra format rule; `format` names it in the error. */
  format?: { name: string; test: (value: string) => boolean };
  /** Allow the empty string even when `required` is false (default: allowed). */
  allowEmpty?: boolean;
};

export type IntegerFieldOptions = {
  min: number;
  max: number;
  required?: boolean;
  /** Value substituted when the field is absent and not required. */
  fallback?: number;
};

/**
 * Accumulates the field errors for one request body. Validators return
 * `undefined` when the input is unusable, so a route must consult `ok` before
 * writing anything.
 */
export class AdminInput {
  private readonly errors: FieldError[] = [];

  constructor(readonly source: Record<string, unknown>) {}

  get fieldErrors(): FieldError[] {
    return [...this.errors];
  }

  get ok(): boolean {
    return this.errors.length === 0;
  }

  get failure(): InvalidRequest {
    return invalidRequest(this.errors);
  }

  fail(field: string, reason: string, detail: Record<string, unknown> = {}): void {
    this.errors.push({ field, reason, ...detail });
  }

  has(field: string): boolean {
    return this.source[field] !== undefined && this.source[field] !== null;
  }

  raw(field: string): unknown {
    return this.source[field];
  }

  string(field: string, options: StringFieldOptions = {}): string | undefined {
    const { required = true, min = 0, max = 4096, trim = true, noControlCharacters = true } = options;
    const raw = this.source[field];
    if (raw === undefined || raw === null || raw === "") {
      if (required) {
        this.fail(field, "required");
        return undefined;
      }
      return "";
    }
    if (typeof raw !== "string") {
      this.fail(field, "invalid_type", { expected: "string" });
      return undefined;
    }
    const value = trim ? raw.trim() : raw;
    if (!value.length) {
      if (required) {
        this.fail(field, "required");
        return undefined;
      }
      return "";
    }
    if (noControlCharacters && /[\u0000-\u001f\u007f-\u009f]/.test(value)) {
      this.fail(field, "control_characters");
      return undefined;
    }
    if (value.length < min) {
      this.fail(field, "too_short", { min, length: value.length });
      return undefined;
    }
    if (value.length > max) {
      this.fail(field, "too_long", { max, length: value.length });
      return undefined;
    }
    if (options.format && !options.format.test(value)) {
      this.fail(field, "invalid_format", { format: options.format.name });
      return undefined;
    }
    return value;
  }

  /**
   * Accepts a JSON number or a form-encoded numeric string. Rejects floats,
   * exponents, booleans and anything outside [min, max] with a named reason.
   */
  integer(field: string, options: IntegerFieldOptions): number | undefined {
    const raw = this.source[field];
    if (raw === undefined || raw === null || raw === "") {
      if (options.required === false) return options.fallback;
      this.fail(field, "required");
      return undefined;
    }
    let parsed: number;
    if (typeof raw === "number") {
      parsed = raw;
    } else if (typeof raw === "string" && /^-?\d{1,12}$/.test(raw.trim())) {
      parsed = Number(raw.trim());
    } else {
      this.fail(field, "invalid_type", { expected: "integer" });
      return undefined;
    }
    if (!Number.isInteger(parsed)) {
      this.fail(field, "invalid_type", { expected: "integer" });
      return undefined;
    }
    if (parsed < options.min || parsed > options.max) {
      this.fail(field, "out_of_range", { min: options.min, max: options.max, value: parsed });
      return undefined;
    }
    return parsed;
  }

  enum<T extends string>(field: string, allowed: readonly T[], options: { required?: boolean; fallback?: T } = {}): T | undefined {
    const raw = this.source[field];
    if (raw === undefined || raw === null || raw === "") {
      if (options.required === false) return options.fallback;
      this.fail(field, "required");
      return undefined;
    }
    if (typeof raw !== "string" || !(allowed as readonly string[]).includes(raw)) {
      this.fail(field, "invalid_enum", { allowed: [...allowed] });
      return undefined;
    }
    return raw as T;
  }

  /** HTML checkboxes omit the field entirely when unchecked. */
  boolean(field: string, options: { fallback?: boolean } = {}): boolean | undefined {
    const raw = this.source[field];
    if (raw === undefined || raw === null) return options.fallback ?? false;
    if (typeof raw === "boolean") return raw;
    if (raw === 1 || raw === 0) return raw === 1;
    if (raw === "true" || raw === "on" || raw === "1") return true;
    if (raw === "false" || raw === "off" || raw === "0" || raw === "") return false;
    this.fail(field, "invalid_type", { expected: "boolean" });
    return undefined;
  }

  /**
   * A checkbox that must be present. An unchecked box means the operator did not
   * confirm, which is an error rather than "false".
   */
  confirmation(field: string): boolean | undefined {
    const raw = this.source[field];
    if (raw === undefined || raw === null || raw === "" || raw === "false" || raw === "off" || raw === "0") {
      this.fail(field, "not_confirmed");
      return undefined;
    }
    return true;
  }
}

const EMAIL_PATTERN = /^[^\s@]{1,64}@[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?(?:\.[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?)+$/i;

export const EMAIL_FORMAT = { name: "email address", test: (value: string) => EMAIL_PATTERN.test(value) };

export function fieldErrorIndex(errors: FieldError[]): Map<string, FieldError> {
  const index = new Map<string, FieldError>();
  for (const error of errors) if (!index.has(error.field)) index.set(error.field, error);
  return index;
}

/** Operator-facing sentence for one field error. Never includes request values. */
export function describeFieldError(error: FieldError): string {
  const reason = error.reason;
  if (reason === "required") return "This field is required.";
  if (reason === "not_confirmed") return "Tick the box to confirm.";
  if (reason === "too_short") return `Use at least ${String(error.min)} characters.`;
  if (reason === "too_long") return `Use at most ${String(error.max)} characters.`;
  if (reason === "out_of_range") return `Enter a whole number between ${String(error.min)} and ${String(error.max)}.`;
  if (reason === "invalid_type") return error.expected === "integer" ? "Enter a whole number." : "Use a text value.";
  if (reason === "invalid_enum") return `Choose one of: ${(error.allowed as string[] | undefined)?.join(", ") ?? "the listed values"}.`;
  if (reason === "invalid_format") return `Enter a valid ${String(error.format ?? "value")}.`;
  if (reason === "control_characters") return "Remove control characters.";
  if (reason === "unknown_field") return "This field cannot be changed here.";
  if (reason === "not_allowed") return typeof error.hint === "string" && error.hint ? error.hint : "That value is not allowed.";
  if (typeof error.message === "string" && error.message) return error.message;
  return "Not accepted.";
}

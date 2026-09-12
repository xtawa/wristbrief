/**
 * Fixed-window rate limiting for auth-sensitive routes. Buckets combine route ×
 * IP prefix × normalized-email hash × user id so NAT'd networks are not blocked as
 * a whole and a single email cannot be hammered from rotating IPs.
 *
 * The D1 counter is shared across isolates (authoritative); the in-memory limiter
 * is a per-isolate fast path for tests and unconfigured deployments.
 */
export interface RateLimiter {
  check(key: string): Promise<{ allowed: boolean }>;
}

export interface RateLimitRule {
  name: string;
  max: number;
  windowSeconds: number;
}

export const AUTH_RATE_LIMIT_RULES = {
  emailRegister: { name: "email-register", max: 5, windowSeconds: 3600 },
  emailLogin: { name: "email-login", max: 10, windowSeconds: 900 },
  emailVerifyRequest: { name: "email-verify-request", max: 5, windowSeconds: 3600 },
  passwordForgot: { name: "password-forgot", max: 5, windowSeconds: 3600 },
  passwordReset: { name: "password-reset", max: 10, windowSeconds: 900 },
  adminLogin: { name: "admin-login", max: 10, windowSeconds: 900 },
  adminRecovery: { name: "admin-recovery", max: 3, windowSeconds: 3600 }
} satisfies Record<string, RateLimitRule>;

export class InMemoryRateLimiter implements RateLimiter {
  private readonly windows = new Map<string, { windowStart: number; count: number }>();

  constructor(private readonly max: number, private readonly windowSeconds: number) {}

  async check(key: string): Promise<{ allowed: boolean }> {
    const now = Date.now();
    const windowStart = Math.floor(now / (this.windowSeconds * 1000));
    const current = this.windows.get(key);
    if (!current || current.windowStart !== windowStart) {
      this.windows.set(key, { windowStart, count: 1 });
      return { allowed: true };
    }
    current.count += 1;
    return { allowed: current.count <= this.max };
  }
}

export class D1FixedWindowRateLimiter implements RateLimiter {
  constructor(
    private readonly db: D1Database,
    private readonly max: number,
    private readonly windowSeconds: number
  ) {}

  async check(key: string): Promise<{ allowed: boolean }> {
    const windowStart = Math.floor(Date.now() / (this.windowSeconds * 1000));
    const result = await this.db
      .prepare(
        `INSERT INTO auth_rate_limits (bucket, window_start, count) VALUES (?, ?, 1)
         ON CONFLICT(bucket, window_start) DO UPDATE SET count = count + 1
         RETURNING count`
      )
      .bind(key, windowStart)
      .first<{ count: number }>();
    const count = result?.count ?? 1;
    return { allowed: count <= this.max };
  }
}

export type RateLimitEnv = {
  ACCOUNT_DB?: D1Database;
};

export function createConfiguredRateLimiter(env: RateLimitEnv, rule: RateLimitRule): RateLimiter {
  if (env.ACCOUNT_DB) return new D1FixedWindowRateLimiter(env.ACCOUNT_DB, rule.max, rule.windowSeconds);
  return new InMemoryRateLimiter(rule.max, rule.windowSeconds);
}

export function ipPrefixOf(request: Request): string {
  const ip = request.headers.get("CF-Connecting-IP") ?? request.headers.get("X-Forwarded-For")?.split(",")[0]?.trim() ?? "unknown";
  if (ip.includes(":")) {
    // IPv6: /64 prefix (first four groups) is the common allocation unit.
    return ip.split(":").slice(0, 4).join(":");
  }
  // IPv4: /24 prefix.
  return ip.split(".").slice(0, 3).join(".");
}

/**
 * In-memory circuit breaker keyed by provider id (CLOSED -> OPEN -> HALF_OPEN).
 * Per-isolate only: Workers run many isolates, so this is a fast local gate on
 * top of the best-effort D1 health snapshot — not a strict global breaker.
 * Only circuit-worthy failures trip it: timeouts, 429s, and 5xx (the same
 * classification as retryable provider errors). 400s and config errors never do.
 */
export type CircuitState = "CLOSED" | "OPEN" | "HALF_OPEN";

type BreakerEntry = {
  state: CircuitState;
  consecutiveFailures: number;
  circuitOpenUntil: number;
};

export class ProviderCircuitBreaker {
  private readonly entries = new Map<string, BreakerEntry>();

  constructor(
    private readonly defaultFailureThreshold = 3,
    private readonly defaultOpenSeconds = 30
  ) {}

  canAttempt(providerId: string, now: number, failureThreshold?: number | null, openSeconds?: number | null): boolean {
    const entry = this.entries.get(providerId);
    if (!entry || entry.state === "CLOSED") return true;
    if (entry.state === "OPEN") {
      if (now >= entry.circuitOpenUntil) {
        entry.state = "HALF_OPEN";
        return true; // one probe attempt
      }
      return false;
    }
    return true; // HALF_OPEN: probes allowed; a failure re-opens immediately
  }

  recordSuccess(providerId: string): void {
    this.entries.delete(providerId);
  }

  recordFailure(providerId: string, now: number, failureThreshold?: number | null, openSeconds?: number | null): CircuitState {
    const threshold = failureThreshold ?? this.defaultFailureThreshold;
    const seconds = openSeconds ?? this.defaultOpenSeconds;
    const entry = this.entries.get(providerId) ?? { state: "CLOSED" as CircuitState, consecutiveFailures: 0, circuitOpenUntil: 0 };
    entry.consecutiveFailures += 1;
    if (entry.state === "HALF_OPEN" || entry.consecutiveFailures >= threshold) {
      entry.state = "OPEN";
      entry.circuitOpenUntil = now + seconds * 1000;
    }
    this.entries.set(providerId, entry);
    return entry.state;
  }

  state(providerId: string): CircuitState {
    return this.entries.get(providerId)?.state ?? "CLOSED";
  }
}

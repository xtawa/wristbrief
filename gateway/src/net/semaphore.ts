/**
 * Small bounded semaphore used to cap simultaneous outbound fetches. Node's
 * default HTTP agent has no socket limit, so without this a burst of requests
 * (or a redirect fan-out) can open an unbounded number of sockets.
 */
export class Semaphore {
  private inFlight = 0;
  private readonly waiters: Array<() => void> = [];

  constructor(private capacity: number) {
    this.capacity = Math.max(1, Math.floor(capacity));
  }

  get limit(): number {
    return this.capacity;
  }

  get active(): number {
    return this.inFlight;
  }

  get queued(): number {
    return this.waiters.length;
  }

  /**
   * Lowering the limit never grants new slots until in-flight work drains below
   * the new capacity, so the bound always holds.
   */
  setLimit(limit: number): void {
    this.capacity = Math.max(1, Math.floor(limit));
    this.drain();
  }

  /** Resolves with an idempotent release function once a slot is held. */
  async acquire(): Promise<() => void> {
    if (this.inFlight < this.capacity) this.inFlight += 1;
    else await new Promise<void>((resolve) => this.waiters.push(resolve));
    let released = false;
    return () => {
      if (released) return;
      released = true;
      this.inFlight -= 1;
      this.drain();
    };
  }

  /** Runs `task` inside a slot; the slot is released on every exit path. */
  async run<T>(task: () => Promise<T>): Promise<T> {
    const release = await this.acquire();
    try {
      return await task();
    } finally {
      release();
    }
  }

  private drain(): void {
    while (this.inFlight < this.capacity && this.waiters.length > 0) {
      this.inFlight += 1;
      const next = this.waiters.shift();
      if (next) next();
    }
  }
}

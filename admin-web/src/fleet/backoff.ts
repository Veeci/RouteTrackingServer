/**
 * Waits between reconnects, with "full jitter": a random wait between 0 and a ceiling that doubles after every
 * failure, from `baseMs` up to `capMs`. Without the randomness, every dashboard that a deploy disconnected would retry
 * at the same seconds and reach the new instance together. The simulator's `Backoff.kt` does the same.
 */
export class Backoff {
  private failures = 0;

  constructor(
    private readonly baseMs = 1_000,
    private readonly capMs = 30_000,
    /** A number in [0, 1), as Math.random gives. Tests pass a fixed one. */
    private readonly random: () => number = Math.random,
  ) {}

  /** The ceiling for the next wait: `baseMs` times 2 to the number of failures, at most `capMs`. */
  get ceilingMs(): number {
    // The shift stops far above any cap, so the power never overflows.
    return Math.min(this.capMs, this.baseMs * 2 ** Math.min(this.failures, 20));
  }

  /** A random wait from 0 to the ceiling, both included. The next ceiling doubles. */
  next(): number {
    const wait = Math.floor(this.random() * (this.ceilingMs + 1));
    this.failures += 1;
    return wait;
  }

  /** After a connection that worked: start again from `baseMs`. */
  reset(): void {
    this.failures = 0;
  }
}

import { describe, expect, it } from 'vitest';
import { Backoff } from './backoff';

describe('Backoff', () => {
  it('doubles the ceiling after each failure, up to the cap', () => {
    const backoff = new Backoff(1_000, 30_000, () => 0);
    const ceilings: number[] = [];
    for (let i = 0; i < 7; i += 1) {
      ceilings.push(backoff.ceilingMs);
      backoff.next();
    }
    expect(ceilings).toEqual([1_000, 2_000, 4_000, 8_000, 16_000, 30_000, 30_000]);
  });

  it('waits from 0 to the ceiling, both included', () => {
    expect(new Backoff(1_000, 30_000, () => 0).next()).toBe(0);
    expect(new Backoff(1_000, 30_000, () => 0.999_999_9).next()).toBe(1_000);
    expect(new Backoff(1_000, 30_000, () => 0.5).next()).toBe(500);
  });

  it('starts again from the base after a reset', () => {
    const backoff = new Backoff(1_000, 30_000, () => 0);
    backoff.next();
    backoff.next();
    expect(backoff.ceilingMs).toBe(4_000);
    backoff.reset();
    expect(backoff.ceilingMs).toBe(1_000);
  });

  it('never overflows after many failures', () => {
    const backoff = new Backoff(1_000, 30_000, () => 0);
    for (let i = 0; i < 2_000; i += 1) backoff.next();
    expect(backoff.ceilingMs).toBe(30_000);
  });
});

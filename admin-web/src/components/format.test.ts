import { describe, expect, it } from 'vitest';
import { formatAge } from './format';

const NOW = '2026-11-20T08:30:00Z';

describe('formatAge', () => {
  it('gives the age in its largest whole unit', () => {
    expect(formatAge('2026-11-20T08:29:48Z', NOW)).toBe('12 s');
    expect(formatAge('2026-11-20T08:29:00Z', NOW)).toBe('1 min');
    expect(formatAge('2026-11-20T08:26:59Z', NOW)).toBe('3 min');
    expect(formatAge('2026-11-20T06:00:00Z', NOW)).toBe('2 h');
    expect(formatAge('2026-11-16T08:30:00Z', NOW)).toBe('4 d');
  });

  it('reads a time after now, such as from a phone clock that runs fast, as 0 s', () => {
    expect(formatAge('2026-11-20T08:30:20Z', NOW)).toBe('0 s');
  });
});

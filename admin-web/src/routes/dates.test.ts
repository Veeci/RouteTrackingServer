import { describe, expect, it } from 'vitest';
import { addDays, dayEnd, dayStart, formatDay, lastDays, parseDay, reportRangeProblem } from './dates';

describe('days', () => {
  it('parses only real days', () => {
    expect(parseDay('2026-11-20')).toEqual(new Date(2026, 10, 20));
    expect(parseDay('2026-02-30')).toBeUndefined();
    expect(parseDay('2026-11-20T00:00')).toBeUndefined();
    expect(parseDay('20-11-2026')).toBeUndefined();
  });

  it('formats and adds days in local time', () => {
    expect(formatDay(new Date(2026, 0, 5))).toBe('2026-01-05');
    expect(formatDay(addDays(new Date(2026, 10, 30), 1))).toBe('2026-12-01');
  });

  it('turns a day into its first instant and the instant after it', () => {
    expect(dayStart('2026-11-20')).toBe(new Date(2026, 10, 20).toISOString());
    expect(dayEnd('2026-11-20')).toBe(new Date(2026, 10, 21).toISOString());
    expect(dayStart('nope')).toBeUndefined();
    expect(dayEnd('nope')).toBeUndefined();
  });

  it('gives the last 7 days up to and including today', () => {
    expect(lastDays(7, new Date(2026, 10, 20, 15, 30))).toEqual({ from: '2026-11-14', to: '2026-11-20' });
  });
});

describe('reportRangeProblem', () => {
  it('accepts up to 31 whole days', () => {
    expect(reportRangeProblem({ from: '2026-11-20', to: '2026-11-20' })).toBeUndefined();
    expect(reportRangeProblem({ from: '2026-10-01', to: '2026-10-31' })).toBeUndefined();
  });

  it('refuses 32 days, a reversed range and a missing day', () => {
    expect(reportRangeProblem({ from: '2026-10-01', to: '2026-11-01' })).toBe('A report covers at most 31 days.');
    expect(reportRangeProblem({ from: '2026-11-20', to: '2026-11-19' })).toBe('The last day is before the first day.');
    expect(reportRangeProblem({ from: '', to: '2026-11-19' })).toBe('Pick a first and a last day.');
  });
});

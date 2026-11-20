// Whole days in the browser's time zone, for the date filters of the Audit and Reports pages. The URL holds a day as
// `YYYY-MM-DD`, which is short and readable. The API gets instants: a range of days runs from the first day's
// midnight to the midnight after the last day.

const DAY = /^(\d{4})-(\d{2})-(\d{2})$/;
const DAY_MS = 86_400_000;

/** The server's limit for one provider report (`SessionHealthService.MAX_REPORT_RANGE`). */
export const MAX_REPORT_DAYS = 31;

/** Local midnight of a `YYYY-MM-DD` day, or undefined for text that is no real day, such as `2026-02-30`. */
export function parseDay(text: string): Date | undefined {
  const match = DAY.exec(text);
  if (match === null) return undefined;
  const [year, month, day] = [Number(match[1]), Number(match[2]), Number(match[3])];
  const date = new Date(year, month - 1, day);
  const real = date.getFullYear() === year && date.getMonth() === month - 1 && date.getDate() === day;
  return real ? date : undefined;
}

export function isDay(text: string): boolean {
  return parseDay(text) !== undefined;
}

export function formatDay(date: Date): string {
  const pad = (value: number) => String(value).padStart(2, '0');
  return `${String(date.getFullYear())}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}`;
}

/** The same local time of day, `days` later. Calendar arithmetic, so a daylight saving change stays out of it. */
export function addDays(date: Date, days: number): Date {
  return new Date(date.getFullYear(), date.getMonth(), date.getDate() + days);
}

/** The instant a day starts. */
export function dayStart(day: string): string | undefined {
  return parseDay(day)?.toISOString();
}

/** The instant after a day ends: the next day's midnight. */
export function dayEnd(day: string): string | undefined {
  const date = parseDay(day);
  return date === undefined ? undefined : addDays(date, 1).toISOString();
}

/** A range of whole days, both included. */
export interface DayRange {
  from: string;
  to: string;
}

/** The last `count` days up to and including today. */
export function lastDays(count: number, now: Date = new Date()): DayRange {
  const today = new Date(now.getFullYear(), now.getMonth(), now.getDate());
  return { from: formatDay(addDays(today, 1 - count)), to: formatDay(today) };
}

/**
 * What is wrong with a report range, or undefined when the server will take it. The server allows `to - from` up to
 * 31 days, on instants, so the check uses the same instants the request sends.
 */
export function reportRangeProblem({ from, to }: DayRange): string | undefined {
  const start = parseDay(from);
  const end = parseDay(to);
  if (start === undefined || end === undefined) return 'Pick a first and a last day.';
  const after = addDays(end, 1);
  if (end < start) return 'The last day is before the first day.';
  if (after.getTime() - start.getTime() > MAX_REPORT_DAYS * DAY_MS) {
    return `A report covers at most ${MAX_REPORT_DAYS} days.`;
  }
  return undefined;
}

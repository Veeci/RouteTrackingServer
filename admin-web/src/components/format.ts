// Formats shared by the pages. Every time is shown in the browser's time zone and locale.

const dateTime = new Intl.DateTimeFormat(undefined, { dateStyle: 'short', timeStyle: 'medium' });
const timeOnly = new Intl.DateTimeFormat(undefined, { timeStyle: 'medium' });
const integer = new Intl.NumberFormat(undefined, { maximumFractionDigits: 0 });
const decimal = new Intl.NumberFormat(undefined, { maximumFractionDigits: 1 });
const percent = new Intl.NumberFormat(undefined, { style: 'percent', maximumFractionDigits: 1 });

/** Shown for a value the server did not send. */
export const NONE = 'None';

export function formatDateTime(iso: string | undefined): string {
  return iso === undefined ? NONE : dateTime.format(new Date(iso));
}

export function formatTime(iso: string): string {
  return timeOnly.format(new Date(iso));
}

export function formatInteger(value: number | undefined): string {
  return value === undefined ? NONE : integer.format(value);
}

export function formatDecimal(value: number | undefined, unit = ''): string {
  return value === undefined ? NONE : `${decimal.format(value)}${unit === '' ? '' : ` ${unit}`}`;
}

/** A ratio from 0 to 1 as a percentage. */
export function formatRatio(value: number | undefined): string {
  return value === undefined ? NONE : percent.format(value);
}

const AGE_UNITS: readonly (readonly [seconds: number, unit: string])[] = [
  [86_400, 'd'],
  [3_600, 'h'],
  [60, 'min'],
  [1, 's'],
];

/**
 * How long before `now` the time `iso` was, in its largest whole unit: "12 s", "3 min", "2 h", "4 d". Pass the server's
 * `at` as `now`, so a wrong clock in the browser does not change the age. A time after `now` reads "0 s".
 */
export function formatAge(iso: string, now: string): string {
  const seconds = Math.max(0, Math.floor((Date.parse(now) - Date.parse(iso)) / 1000));
  const [size, unit] = AGE_UNITS.find(([unitSeconds]) => seconds >= unitSeconds) ?? [1, 's'];
  return `${String(Math.floor(seconds / size))} ${unit}`;
}

/** The first 8 characters of a UUID: enough to tell staff apart in a list, short enough for a column. */
export function shortId(id: string): string {
  return id.slice(0, 8);
}

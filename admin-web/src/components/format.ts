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

/** The first 8 characters of a UUID: enough to tell staff apart in a list, short enough for a column. */
export function shortId(id: string): string {
  return id.slice(0, 8);
}

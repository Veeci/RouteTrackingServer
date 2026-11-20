import { z } from 'zod';
import { isDay } from './dates';

// Pieces of the search param schemas. Each one drops a bad value instead of failing the page, so a hand-edited or
// stale link opens the list with that filter off. The router parses values as JSON, so `?q=42` arrives as a number.

/** `true`, `false`, or absent. */
export const flagParam = z.boolean().optional().catch(undefined);

/** Text of 1 to `max` characters, trimmed. Blank means absent. */
export function textParam(max: number) {
  return z
    .union([z.string(), z.number()])
    .transform((value) => String(value).trim())
    .pipe(z.string().min(1).max(max))
    .optional()
    .catch(undefined);
}

/** An opaque `nextCursor` from the API. */
export const cursorParam = textParam(1_000);

export const uuidParam = z.uuid().optional().catch(undefined);

/** A day as `YYYY-MM-DD`. */
export const dayParam = z.string().refine(isDay).optional().catch(undefined);

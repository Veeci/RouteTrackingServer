import { z } from 'zod';
import { dayParam } from '../search';

/** The report's first and last day, both included. Absent means the last 7 days. */
export const reportsSearch = z.object({
  from: dayParam,
  to: dayParam,
});

export type ReportsSearch = z.infer<typeof reportsSearch>;

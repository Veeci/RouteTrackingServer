import { z } from 'zod';
import { cursorParam, dayParam, textParam, uuidParam } from '../search';

/** The Audit page's filters and cursor, in the URL (C1). `from` and `to` are whole days, both included. */
export const auditSearch = z.object({
  actorId: uuidParam,
  action: textParam(100),
  targetType: textParam(50),
  targetId: textParam(200),
  from: dayParam,
  to: dayParam,
  cursor: cursorParam,
});

export type AuditSearch = z.infer<typeof auditSearch>;

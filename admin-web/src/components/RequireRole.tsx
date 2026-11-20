import type { ReactNode } from 'react';
import type { Role } from '../api/types';
import { hasAnyRole } from '../auth/roles';
import { useSession } from '../auth/session';

export interface RequireRoleProps {
  /** The roles that may see the children. */
  roles: readonly Role[];
  children: ReactNode;
  /** Shown instead, when the role may not see them. Nothing by default. */
  fallback?: ReactNode;
}

/**
 * Hides UI that the user's role may not use. It only hides: the server checks the role on every call, and its 403
 * shows "No permission".
 */
export function RequireRole({ roles, children, fallback = null }: RequireRoleProps) {
  const session = useSession();
  return hasAnyRole(session?.role, roles) ? children : fallback;
}

import type { Role } from '../api/types';

/** The roles that may use the dashboard. The login form offers only these. */
export const STAFF_ROLES = ['OPERATOR', 'ADMIN'] as const satisfies readonly Role[];
export type StaffRole = (typeof STAFF_ROLES)[number];

export const ADMIN_ONLY = ['ADMIN'] as const satisfies readonly Role[];

export function hasAnyRole(role: Role | undefined, allowed: readonly Role[]): boolean {
  return role !== undefined && allowed.includes(role);
}

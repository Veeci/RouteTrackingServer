import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { RequireRole } from '../components/RequireRole';
import { testSession } from '../test/server';
import { ADMIN_ONLY, hasAnyRole, STAFF_ROLES } from './roles';
import { sessionStore } from './session';

// TC-12-AUTH-02, first half: the UI hides what a role may not use. The second half (a 403 shows "No permission")
// is in app.test.tsx.

function AdminOnlyButton() {
  return (
    <RequireRole roles={ADMIN_ONLY} fallback={<span>Read only</span>}>
      <button type="button">Disable driver</button>
    </RequireRole>
  );
}

describe('RequireRole', () => {
  it('hides an ADMIN-only element from an OPERATOR', () => {
    sessionStore.getState().signIn(testSession('OPERATOR'));

    render(<AdminOnlyButton />);

    expect(screen.queryByRole('button', { name: 'Disable driver' })).not.toBeInTheDocument();
    expect(screen.getByText('Read only')).toBeInTheDocument();
  });

  it('shows it to an ADMIN', () => {
    sessionStore.getState().signIn(testSession('ADMIN'));

    render(<AdminOnlyButton />);

    expect(screen.getByRole('button', { name: 'Disable driver' })).toBeInTheDocument();
  });

  it('shows nothing without a session', () => {
    render(<AdminOnlyButton />);

    expect(screen.queryByRole('button')).not.toBeInTheDocument();
  });
});

describe('hasAnyRole', () => {
  it('admits staff roles only to staff UI', () => {
    expect(hasAnyRole('OPERATOR', STAFF_ROLES)).toBe(true);
    expect(hasAnyRole('ADMIN', STAFF_ROLES)).toBe(true);
    expect(hasAnyRole('DRIVER', STAFF_ROLES)).toBe(false);
    expect(hasAnyRole(undefined, STAFF_ROLES)).toBe(false);
  });
});

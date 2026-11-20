import { screen, waitFor, within } from '@testing-library/react';
import { HttpResponse } from 'msw';
import { describe, expect, it } from 'vitest';
import { sessionStore } from './auth/session';
import { http, overviewBody, problem, problemResponse, server, testSession } from './test/server';
import { renderApp } from './test/render';

async function logInAs(user: ReturnType<typeof renderApp>['user'], displayName: string, role: 'Operator' | 'Admin') {
  await user.type(await screen.findByLabelText(/Display name/), displayName);
  await user.click(screen.getByRole('radio', { name: role }));
  await user.click(screen.getByRole('button', { name: 'Log in' }));
}

function countIn(label: string) {
  return within(screen.getByRole('group', { name: label }));
}

/** Waits for the overview card, then looks inside it. */
async function findCountIn(label: string) {
  return within(await screen.findByRole('group', { name: label }));
}

describe('without a session', () => {
  it('opens the login page and remembers where the user was going', async () => {
    const { router } = renderApp({ path: '/?view=compact' });

    expect(await screen.findByRole('heading', { name: 'Log in' })).toBeInTheDocument();
    expect(router.state.location.pathname).toBe('/login');
    expect(router.state.location.search).toEqual({ redirect: '/?view=compact' });
  });

  it('logs in and shows the overview with the user in the header', async () => {
    const { user } = renderApp();

    await logInAs(user, 'Lan', 'Admin');

    expect(await screen.findByRole('heading', { name: 'Overview' })).toBeInTheDocument();
    expect(screen.getByText('Lan')).toBeInTheDocument();
    expect(screen.getByText('ADMIN')).toBeInTheDocument();
    expect(sessionStore.getState().session).toMatchObject({ role: 'ADMIN', displayName: 'Lan' });
  });

  it('says so when the staging token is wrong, and stays on the login page', async () => {
    // The staging gate answers 401 before the route.
    server.use(http.post('/api/v1/dev/tokens', ({ response }) => response(401).json(problem(401, 'UNAUTHORIZED'))));
    const { user, router } = renderApp({ path: '/login' });

    await logInAs(user, 'Lan', 'Operator');

    expect(await screen.findByText('Wrong staging token')).toBeInTheDocument();
    expect(router.state.location.pathname).toBe('/login');
  });

  it('says so when the server has no dev login', async () => {
    // Outside dev, test and staging the route is not mounted, so the server answers its plain 404.
    server.use(
      http.post('/api/v1/dev/tokens', ({ response }) => response.untyped(new HttpResponse(null, { status: 404 }))),
    );
    const { user } = renderApp({ path: '/login' });

    await logInAs(user, 'Lan', 'Operator');

    expect(await screen.findByText('No development login')).toBeInTheDocument();
  });
});

describe('TC-12-API-02: a 401 on a query', () => {
  it('sends the user to login and back to the same page and search after login', async () => {
    server.use(
      http.get(
        '/api/v1/admin/overview',
        ({ response }) => response(401).json(problem(401, 'UNAUTHORIZED', 'The token has expired.')),
        { once: true },
      ),
    );
    const { user, router } = renderApp({ path: '/?view=compact', session: testSession('OPERATOR') });

    expect(await screen.findByRole('heading', { name: 'Log in' })).toBeInTheDocument();
    expect(router.state.location.search).toEqual({ redirect: '/?view=compact' });
    expect(sessionStore.getState().session).toBeNull();

    await logInAs(user, 'Lan', 'Operator');

    expect(await screen.findByRole('heading', { name: 'Overview' })).toBeInTheDocument();
    expect(router.state.location.href).toBe('/?view=compact');
    expect((await findCountIn('Online drivers')).getByText('12')).toBeInTheDocument();
  });
});

describe('TC-12-AUTH-02: a 403 from the server', () => {
  it('shows "No permission" and keeps the session', async () => {
    server.use(
      http.get('/api/v1/admin/overview', ({ response }) => response(403).json(problem(403, 'FLEET_FORBIDDEN'))),
    );
    const { router } = renderApp({ session: testSession('OPERATOR') });

    expect(await screen.findByText('No permission')).toBeInTheDocument();
    expect(router.state.location.pathname).toBe('/');
    expect(sessionStore.getState().session).not.toBeNull();
  });
});

describe('the app shell', () => {
  async function navLinks() {
    const nav = await screen.findByRole('navigation', { name: 'Pages' });
    return within(nav)
      .getAllByRole('link')
      .map((link) => link.textContent);
  }

  it('lists every page for an admin', async () => {
    renderApp({ session: testSession('ADMIN') });

    expect(await navLinks()).toEqual(['Overview', 'Drivers', 'Audit', 'Reports']);
  });

  it('TC-12-AUTH-02: hides Audit and Reports from an operator', async () => {
    renderApp({ session: testSession('OPERATOR') });

    expect(await navLinks()).toEqual(['Overview', 'Drivers']);
  });

  it('keeps Drivers marked on a driver page', async () => {
    renderApp({ path: '/drivers/7f1c2a9e-5b3d-4c1e-8a2f-000000000001', session: testSession('OPERATOR') });

    const nav = await screen.findByRole('navigation', { name: 'Pages' });
    expect(within(nav).getByRole('link', { name: 'Drivers' })).toHaveAttribute('data-active', 'true');
    expect(within(nav).getByRole('link', { name: 'Overview' })).not.toHaveAttribute('data-active');
  });

  it('logs out to the login page', async () => {
    const { user, router } = renderApp({ session: testSession('OPERATOR') });

    await user.click(await screen.findByRole('button', { name: 'Log out' }));

    expect(await screen.findByRole('heading', { name: 'Log in' })).toBeInTheDocument();
    expect(sessionStore.getState().session).toBeNull();
    await waitFor(() => {
      expect(router.state.location.pathname).toBe('/login');
    });
  });

  it('shows a not-found page for an unknown path', async () => {
    renderApp({ path: '/nowhere', session: testSession() });

    expect(await screen.findByRole('heading', { name: 'Page not found' })).toBeInTheDocument();
  });
});

describe('the overview', () => {
  it('shows the four counts and the computed time', async () => {
    renderApp({ session: testSession() });

    expect((await findCountIn('Online drivers')).getByText('12')).toBeInTheDocument();
    expect(countIn('Drivers on a trip').getByText('5')).toBeInTheDocument();
    expect(countIn('Active trips').getByText('6')).toBeInTheDocument();
    expect(countIn('Disabled drivers').getByText('1')).toBeInTheDocument();
    expect(screen.getByText(/^Computed at/)).toBeInTheDocument();
    expect(document.querySelector('time')?.getAttribute('dateTime')).toBe(overviewBody.computedAt);
  });

  it('says "Server unreachable" after the retries fail', async () => {
    server.use(http.get('/api/v1/admin/overview', () => HttpResponse.error()));
    renderApp({ session: testSession() });

    expect(await screen.findByText('Server unreachable')).toBeInTheDocument();
  });

  it('keeps the last numbers when a refresh fails', async () => {
    const { queryClient } = renderApp({ session: testSession() });
    expect((await findCountIn('Online drivers')).getByText('12')).toBeInTheDocument();

    // A proxy in front of the server answers 503. The contract cannot list that.
    server.use(
      http.get('/api/v1/admin/overview', ({ response }) =>
        response.untyped(problemResponse(503, 'UNAVAILABLE', 'Down for a moment.')),
      ),
    );
    await queryClient.refetchQueries();

    expect(await screen.findByText(/The numbers below are from/)).toBeInTheDocument();
    expect(countIn('Online drivers').getByText('12')).toBeInTheDocument();
  });
});

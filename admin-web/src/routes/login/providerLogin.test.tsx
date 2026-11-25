import { render, screen, waitFor } from '@testing-library/react';
import { createMemoryHistory } from '@tanstack/react-router';
import { StrictMode } from 'react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { App, createApp } from '../../app';
import { browser, resetDiscovery } from '../../auth/oidc/client';
import { sessionStore } from '../../auth/session';
import { accessToken, answerTokens, AUTHORIZE_URL, discoveryHandler, LOGOUT_URL } from '../../test/provider';
import { renderApp } from '../../test/render';
import { server, testSession } from '../../test/server';
import { resetExchanges } from './CallbackPage';

// A build with an identity provider (12g). vi.mock is hoisted above the imports, so the values are literals.
vi.mock('../../auth/oidc/settings', () => ({
  oidcSettings: { issuer: 'https://sso.test/realms/rts', clientId: 'rts-admin-web' },
  readOidcSettings: () => null,
}));

const PENDING_KEY = 'rts-admin.oidcLogin';

/** A login this tab started, as startLogin leaves it before the trip to the provider. */
function pendingLogin(redirect = '/'): string {
  sessionStorage.setItem(PENDING_KEY, JSON.stringify({ state: 'state-1', verifier: 'v'.repeat(43), redirect }));
  return '/auth/callback?code=code-1&state=state-1';
}

beforeEach(() => {
  server.use(discoveryHandler);
});

afterEach(() => {
  resetDiscovery();
  resetExchanges();
});

describe('the login page with an identity provider', () => {
  it('shows one button instead of the dev form', async () => {
    renderApp({ path: '/login' });

    expect(await screen.findByText('Log in with your staff account.')).toBeInTheDocument();
    expect(screen.queryByLabelText(/Display name/)).not.toBeInTheDocument();
  });

  it('sends the browser to the provider and keeps the page to come back to', async () => {
    const assign = vi.spyOn(browser, 'assign').mockImplementation(() => undefined);
    const { user } = renderApp({ path: '/drivers?online=true' });

    await user.click(await screen.findByRole('button', { name: 'Log in' }));

    await waitFor(() => {
      expect(assign).toHaveBeenCalledOnce();
    });
    expect(assign.mock.lastCall?.[0]).toContain(AUTHORIZE_URL);
    expect(JSON.parse(sessionStorage.getItem(PENDING_KEY) ?? '{}')).toMatchObject({ redirect: '/drivers?online=true' });
  });
});

describe('the callback page', () => {
  it('finishes the login and opens the page the user was going to', async () => {
    answerTokens();
    const { router } = renderApp({ path: pendingLogin('/trips') });

    await waitFor(() => {
      expect(router.state.location.pathname).toBe('/trips');
    });
    expect(sessionStore.getState().session).toMatchObject({ role: 'OPERATOR', displayName: 'Olive Operator' });
    expect(await screen.findByText('Olive Operator')).toBeInTheDocument();
  });

  it('trades the code once, though StrictMode runs the effect twice', async () => {
    const seen = answerTokens();
    const parts = createApp({ history: createMemoryHistory({ initialEntries: [pendingLogin()] }), retryDelay: 0 });
    render(
      <StrictMode>
        <App {...parts} />
      </StrictMode>,
    );

    await waitFor(() => {
      expect(parts.router.state.location.pathname).toBe('/');
    });
    expect(seen).toHaveLength(1);
  });

  it('says so when the account has no staff role, and logs nobody in', async () => {
    answerTokens({ body: { access_token: accessToken(['offline_access']), expires_in: 300 } });
    renderApp({ path: pendingLogin() });

    expect(await screen.findByText('No staff role')).toBeInTheDocument();
    expect(sessionStore.getState().session).toBeNull();
    expect(screen.getByRole('button', { name: 'Back to the login' })).toBeInTheDocument();
  });

  it('refuses a callback this tab did not start', async () => {
    renderApp({ path: '/auth/callback?code=code-1&state=forged' });

    expect(await screen.findByText('Login failed')).toBeInTheDocument();
  });
});

describe('logging out with an identity provider', () => {
  it('ends the session here and at the provider', async () => {
    const assign = vi.spyOn(browser, 'assign').mockImplementation(() => undefined);
    const { user } = renderApp({ session: testSession('ADMIN', { idToken: 'id-1', refreshToken: 'r' }) });

    await user.click(await screen.findByRole('button', { name: 'Log out' }));

    await waitFor(() => {
      expect(assign).toHaveBeenCalledOnce();
    });
    expect(sessionStore.getState().session).toBeNull();
    const target = new URL(assign.mock.lastCall?.[0] ?? '');
    expect(`${target.origin}${target.pathname}`).toBe(LOGOUT_URL);
    expect(target.searchParams.get('id_token_hint')).toBe('id-1');
  });
});

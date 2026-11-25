import { Button, Center, Loader, Paper, Stack, Text, Title } from '@mantine/core';
import { useNavigate, useRouterState } from '@tanstack/react-router';
import { useEffect, useState } from 'react';
import { describeError, type ErrorText } from '../../api/messages';
import { type FinishedLogin, finishLogin, OidcError } from '../../auth/oidc/client';
import { oidcSettings } from '../../auth/oidc/settings';
import { safeRedirect } from '../../auth/redirect';
import { sessionStore } from '../../auth/session';
import { ErrorAlert } from '../../components/ErrorAlert';

// One exchange per callback URL. React's StrictMode runs effects twice in development, and the provider accepts a
// code once only: a second exchange would fail with invalid_grant and hide the first one's success.
const exchanges = new Map<string, Promise<FinishedLogin>>();

/** Forgets the exchanges. Tests only. */
export function resetExchanges(): void {
  exchanges.clear();
}

function errorText(error: unknown): ErrorText {
  if (error instanceof OidcError) {
    return { title: error.kind === 'no-staff-role' ? 'No staff role' : 'Login failed', message: error.message };
  }
  return describeError(error);
}

/** The page the identity provider sends the browser back to (12g). It trades the code for tokens, then moves on. */
export function CallbackPage() {
  const search = useRouterState({ select: (state) => state.location.searchStr });
  const navigate = useNavigate();
  const [error, setError] = useState<unknown>(null);

  useEffect(() => {
    let exchange = exchanges.get(search);
    if (exchange === undefined) {
      exchange =
        oidcSettings === null
          ? Promise.reject(new OidcError('provider', 'This build has no identity provider. Use the login page.'))
          : finishLogin(oidcSettings, new URLSearchParams(search));
      exchanges.set(search, exchange);
    }
    let current = true;
    exchange.then(
      ({ session, redirect }) => {
        if (!current) return;
        sessionStore.getState().signIn(session);
        // Replace, so Back does not return to a callback whose code is spent.
        void navigate({ href: safeRedirect(redirect), replace: true });
      },
      (failure: unknown) => {
        if (current) setError(failure);
      },
    );
    return () => {
      current = false;
    };
  }, [search, navigate]);

  if (error === null) {
    return (
      <Center mih="100vh">
        <Stack align="center">
          <Loader />
          <Text size="sm">Finishing the login…</Text>
        </Stack>
      </Center>
    );
  }

  const text = errorText(error);
  return (
    <Center mih="100vh" p="md">
      <Paper withBorder shadow="sm" p="xl" w={420} maw="100%">
        <Stack>
          <Title order={1} size="h2">
            Log in
          </Title>
          <ErrorAlert error={error} text={text} />
          <Button
            onClick={() => {
              void navigate({ to: '/login', replace: true });
            }}
          >
            Back to the login
          </Button>
        </Stack>
      </Paper>
    </Center>
  );
}

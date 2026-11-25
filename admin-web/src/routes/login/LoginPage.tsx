import { Button, Center, Paper, PasswordInput, Radio, Stack, Text, TextInput, Title } from '@mantine/core';
import { useMutation } from '@tanstack/react-query';
import { getRouteApi, useNavigate } from '@tanstack/react-router';
import { type SubmitEvent, useState } from 'react';
import { api } from '../../api/client';
import { ApiError } from '../../api/errors';
import { describeError, type ErrorText } from '../../api/messages';
import { type LoginInput, logIn } from '../../auth/devLogin';
import { startLogin } from '../../auth/oidc/client';
import { type OidcSettings, oidcSettings } from '../../auth/oidc/settings';
import { safeRedirect } from '../../auth/redirect';
import { STAFF_ROLES, type StaffRole } from '../../auth/roles';
import { sessionStore } from '../../auth/session';
import { savedStagingToken } from '../../auth/storage';
import { ErrorAlert } from '../../components/ErrorAlert';

const routeApi = getRouteApi('/login');

const ROLE_LABELS: Record<StaffRole, string> = { OPERATOR: 'Operator', ADMIN: 'Admin' };

/** The dev-token route answers 401 only from the staging gate, and 404 where it is not mounted. */
function loginErrorText(error: unknown): ErrorText {
  if (error instanceof ApiError && error.status === 401) {
    return { title: 'Wrong staging token', message: 'The staging access token is missing or wrong.' };
  }
  if (error instanceof ApiError && error.status === 404) {
    return {
      title: 'No development login',
      message: 'This server has no development login. It answers only with APP_ENV dev, test or staging.',
    };
  }
  return describeError(error);
}

/** The login page: the identity provider's login when the build has one (12g), else the development login (D3). */
export function LoginPage() {
  return oidcSettings === null ? <DevLoginForm /> : <ProviderLogin settings={oidcSettings} />;
}

/**
 * The real login (12g): one button that sends the browser to the identity provider. It does not go there by itself,
 * so a user the provider keeps logging in, but the server keeps refusing, is not caught in a loop of redirects.
 */
function ProviderLogin({ settings }: { settings: OidcSettings }) {
  const { redirect } = routeApi.useSearch();
  const [pending, setPending] = useState(false);
  const [error, setError] = useState<unknown>(null);

  const onLogIn = () => {
    setPending(true);
    setError(null);
    // On success the browser leaves the page, so only a failure comes back here.
    startLogin(settings, safeRedirect(redirect)).catch((failure: unknown) => {
      setPending(false);
      setError(failure);
    });
  };

  return (
    <Center mih="100vh" p="md">
      <Paper withBorder shadow="sm" p="xl" w={420} maw="100%">
        <Stack>
          <Title order={1} size="h2">
            Log in
          </Title>
          <Text size="sm" c="dimmed">
            Log in with your staff account.
          </Text>
          {error !== null && <ErrorAlert error={error} />}
          <Button onClick={onLogIn} loading={pending}>
            Log in
          </Button>
        </Stack>
      </Paper>
    </Center>
  );
}

/** The development login (D3), for a local server, the E2E tests and staging before 12g. */
function DevLoginForm() {
  const { redirect } = routeApi.useSearch();
  const navigate = useNavigate();
  const [displayName, setDisplayName] = useState('');
  const [role, setRole] = useState<StaffRole>('OPERATOR');
  // After a 401 the tab still has the staging token, so the user only presses "Log in".
  const [stagingToken, setStagingToken] = useState(() => savedStagingToken.get() ?? '');

  const login = useMutation({
    mutationFn: (input: LoginInput) => logIn(api, sessionStore, input),
    meta: { skipAuthRedirect: true },
    onSuccess: () => {
      void navigate({ href: safeRedirect(redirect), replace: true });
    },
  });

  const onSubmit = (event: SubmitEvent<HTMLFormElement>) => {
    event.preventDefault();
    login.mutate({ displayName, role, stagingToken });
  };

  return (
    <Center mih="100vh" p="md">
      <Paper withBorder shadow="sm" p="xl" w={420} maw="100%">
        <form onSubmit={onSubmit}>
          <Stack>
            <Title order={1} size="h2">
              Log in
            </Title>
            <Text size="sm" c="dimmed">
              Development login, for a local server and staging.
            </Text>
            <TextInput
              label="Display name"
              required
              maxLength={100}
              autoComplete="name"
              value={displayName}
              onChange={(event) => {
                setDisplayName(event.currentTarget.value);
              }}
            />
            <Radio.Group
              label="Role"
              value={role}
              onChange={(value) => {
                const picked = STAFF_ROLES.find((candidate) => candidate === value);
                if (picked !== undefined) setRole(picked);
              }}
            >
              <Stack gap="xs" mt="xs">
                {STAFF_ROLES.map((candidate) => (
                  <Radio key={candidate} value={candidate} label={ROLE_LABELS[candidate]} />
                ))}
              </Stack>
            </Radio.Group>
            <PasswordInput
              label="Staging access token"
              description="Staging only. Leave it empty for a local server."
              autoComplete="off"
              value={stagingToken}
              onChange={(event) => {
                setStagingToken(event.currentTarget.value);
              }}
            />
            {login.isError && <ErrorAlert error={login.error} text={loginErrorText(login.error)} />}
            <Button type="submit" loading={login.isPending}>
              Log in
            </Button>
          </Stack>
        </form>
      </Paper>
    </Center>
  );
}

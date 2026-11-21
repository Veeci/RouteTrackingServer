import { AppShell, Badge, Burger, Button, Group, NavLink, Text, Title } from '@mantine/core';
import { useDisclosure } from '@mantine/hooks';
import { useQueryClient } from '@tanstack/react-query';
import { Link, Outlet, useNavigate, useRouterState } from '@tanstack/react-router';
import type { Role } from '../api/types';
import { logOut } from '../auth/devLogin';
import { ADMIN_ONLY, STAFF_ROLES } from '../auth/roles';
import { sessionStore, useSession } from '../auth/session';
import { RequireRole } from './RequireRole';

interface NavItem {
  to: '/' | '/map' | '/drivers' | '/trips' | '/audit' | '/reports';
  label: string;
  roles: readonly Role[];
}

/** Only pages that exist, each shown to the roles that may use it. */
const NAV: readonly NavItem[] = [
  { to: '/', label: 'Overview', roles: STAFF_ROLES },
  { to: '/map', label: 'Live map', roles: STAFF_ROLES },
  { to: '/drivers', label: 'Drivers', roles: STAFF_ROLES },
  { to: '/trips', label: 'Trips', roles: STAFF_ROLES },
  { to: '/audit', label: 'Audit', roles: ADMIN_ONLY },
  { to: '/reports', label: 'Reports', roles: ADMIN_ONLY },
];

/** A section stays marked on its sub-pages: Drivers on a driver's page. */
export function isActive(pathname: string, to: NavItem['to']): boolean {
  return to === '/' ? pathname === '/' : pathname === to || pathname.startsWith(`${to}/`);
}

export function AppLayout() {
  const session = useSession();
  const [opened, { toggle, close }] = useDisclosure();
  const pathname = useRouterState({ select: (state) => state.location.pathname });
  const queryClient = useQueryClient();
  const navigate = useNavigate();

  if (session === null) return null;

  const onLogOut = () => {
    logOut(sessionStore);
    // Drop the cached data after the pages have unmounted, so no query refetches without a token.
    void navigate({ to: '/login' }).then(() => {
      queryClient.clear();
    });
  };

  return (
    <AppShell
      header={{ height: 56 }}
      navbar={{ width: 220, breakpoint: 'sm', collapsed: { mobile: !opened } }}
      padding="md"
    >
      <AppShell.Header>
        <Group h="100%" px="md" justify="space-between" wrap="nowrap">
          <Group gap="sm" wrap="nowrap">
            <Burger opened={opened} onClick={toggle} hiddenFrom="sm" size="sm" aria-label="Show the pages" />
            <Title order={1} size="h4">
              RouteTracking admin
            </Title>
          </Group>
          <Group gap="sm" wrap="nowrap">
            <Text size="sm" truncate maw={200}>
              {session.displayName}
            </Text>
            <Badge variant="light">{session.role}</Badge>
            <Button variant="subtle" size="compact-sm" onClick={onLogOut}>
              Log out
            </Button>
          </Group>
        </Group>
      </AppShell.Header>
      <AppShell.Navbar p="xs" aria-label="Pages">
        {NAV.map((item) => (
          <RequireRole key={item.to} roles={item.roles}>
            <NavLink
              component={Link}
              to={item.to}
              label={item.label}
              active={isActive(pathname, item.to)}
              onClick={close}
            />
          </RequireRole>
        ))}
      </AppShell.Navbar>
      <AppShell.Main>
        <Outlet />
      </AppShell.Main>
    </AppShell>
  );
}

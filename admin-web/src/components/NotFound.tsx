import { Anchor, Stack, Text, Title } from '@mantine/core';
import { Link } from '@tanstack/react-router';

export function NotFound() {
  return (
    <Stack p="xl" align="flex-start">
      <Title order={2}>Page not found</Title>
      <Text>The dashboard has no page at this address.</Text>
      <Anchor component={Link} to="/">
        Go to the overview
      </Anchor>
    </Stack>
  );
}

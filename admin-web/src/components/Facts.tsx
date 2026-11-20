import { SimpleGrid, Text } from '@mantine/core';
import type { ReactNode } from 'react';

export interface Fact {
  label: string;
  value: ReactNode;
}

/** Label and value pairs as a description list, so a screen reader reads each value with its label. */
export function Facts({ facts, cols = 2 }: { facts: readonly Fact[]; cols?: number }) {
  return (
    <SimpleGrid component="dl" cols={{ base: 1, xs: cols }} spacing="xs" verticalSpacing={4} m={0}>
      {facts.map(({ label, value }) => (
        <div key={label}>
          <Text component="dt" size="xs" c="dimmed">
            {label}
          </Text>
          <Text component="dd" size="sm" m={0}>
            {value}
          </Text>
        </div>
      ))}
    </SimpleGrid>
  );
}

import { Button, Group } from '@mantine/core';
import type { CursorPaging } from '../routes/paging';

export interface CursorPagerProps {
  paging: CursorPaging;
  /** The page's `nextCursor`. Absent on the last page. */
  nextCursor: string | undefined;
}

/** "First page", "Previous" and "Next" for a keyset-paged list (C1). Never page numbers: the API has none. */
export function CursorPager({ paging, nextCursor }: CursorPagerProps) {
  if (paging.onFirstPage && nextCursor === undefined) return null;
  return (
    <Group gap="xs" aria-label="Pages of the list" role="group">
      {!paging.onFirstPage && (
        <Button variant="default" size="xs" onClick={paging.first}>
          First page
        </Button>
      )}
      {paging.hasPrevious && (
        <Button variant="default" size="xs" onClick={paging.previous}>
          Previous
        </Button>
      )}
      {nextCursor !== undefined && (
        <Button
          variant="default"
          size="xs"
          onClick={() => {
            paging.next(nextCursor);
          }}
        >
          Next
        </Button>
      )}
    </Group>
  );
}

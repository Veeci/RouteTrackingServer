import { useRouterState } from '@tanstack/react-router';
import { useMemo } from 'react';
import { z } from 'zod';

declare module '@tanstack/history' {
  interface HistoryState {
    /**
     * The cursors of the pages before this one, oldest first (C1). `null` stands for the first page, which has no
     * cursor. It lives in the browser's history entry, not in the URL, so the URL stays short, and a reload or the
     * Back button keeps it. A shared link has none.
     */
    cursorTrail?: (string | null)[];
  }
}

/** Where a pager button leads: the cursor for the URL, and the trail for the new history entry. */
export interface PageMove {
  cursor: string | undefined;
  trail: (string | null)[];
}

// History state can hold anything another version of the app wrote. Anything but a list of cursors is no trail.
const trailSchema = z.array(z.string().nullable()).catch([]);

export function readTrail(value: unknown): (string | null)[] {
  return trailSchema.parse(value ?? []);
}

/** The next page: the current cursor joins the trail. */
export function nextMove(cursor: string | undefined, trail: readonly (string | null)[], nextCursor: string): PageMove {
  return { cursor: nextCursor, trail: [...trail, cursor ?? null] };
}

/** The page before: the last cursor of the trail. Only called when the trail is not empty. */
export function previousMove(trail: readonly (string | null)[]): PageMove {
  return { cursor: trail.at(-1) ?? undefined, trail: trail.slice(0, -1) };
}

export const FIRST_PAGE: PageMove = { cursor: undefined, trail: [] };

export interface CursorPaging {
  /** No cursor in the URL. */
  onFirstPage: boolean;
  /** The trail is not empty, so "Previous" knows where to go. */
  hasPrevious: boolean;
  first: () => void;
  previous: () => void;
  next: (nextCursor: string) => void;
}

/**
 * Keyset paging over a list whose filters and cursor live in the URL. `go` navigates: it puts the move's cursor in the
 * search params and its trail in the history state of a new entry. A filter change navigates without a trail, which
 * starts the paging over.
 */
export function useCursorPaging(cursor: string | undefined, go: (move: PageMove) => void): CursorPaging {
  const state = useRouterState({ select: (router) => router.location.state });
  const trail = useMemo(() => readTrail(state.cursorTrail), [state]);
  return {
    onFirstPage: cursor === undefined,
    hasPrevious: trail.length > 0,
    first: () => {
      go(FIRST_PAGE);
    },
    previous: () => {
      go(previousMove(trail));
    },
    next: (nextCursor) => {
      go(nextMove(cursor, trail, nextCursor));
    },
  };
}

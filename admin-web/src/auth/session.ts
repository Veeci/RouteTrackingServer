import { useStore } from 'zustand';
import { createStore } from 'zustand/vanilla';
import type { DevTokenResponse, Role } from '../api/types';

/** The logged-in staff member. It lives in memory only: a reload means a new login (plan, "Authentication"). */
export interface Session {
  token: string;
  userId: string;
  role: Role;
  displayName: string;
  /** Epoch milliseconds. */
  expiresAt: number;
}

export interface SessionState {
  session: Session | null;
  signIn: (session: Session) => void;
  signOut: () => void;
}

/**
 * A vanilla store, not a React context: the API middleware and the re-mint timer read it outside React, and React
 * components subscribe with {@link useSession}.
 */
export function createSessionStore() {
  return createStore<SessionState>()((set) => ({
    session: null,
    signIn: (session) => {
      set({ session });
    },
    signOut: () => {
      set({ session: null });
    },
  }));
}

export type SessionStore = ReturnType<typeof createSessionStore>;

export const sessionStore: SessionStore = createSessionStore();

export function useSession(): Session | null {
  return useStore(sessionStore, (state) => state.session);
}

export function toSession(response: DevTokenResponse): Session {
  return {
    token: response.token,
    userId: response.userId,
    role: response.role,
    displayName: response.displayName,
    expiresAt: Date.parse(response.expiresAt),
  };
}

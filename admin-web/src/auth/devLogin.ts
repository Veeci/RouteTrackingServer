import { z } from 'zod';
import type { ApiClient } from '../api/client';
import { unwrap } from '../api/errors';
import type { DevTokenRequest, DevTokenResponse } from '../api/types';
import type { StaffRole } from './roles';
import { type Session, type SessionStore, toSession } from './session';
import { savedStagingToken, savedUserId } from './storage';

/** The header the staging server checks before it serves `/api/v1/dev/tokens` (phase 1.5, `StagingGate.kt`). */
export const STAGING_TOKEN_HEADER = 'X-Staging-Token';

export interface LoginInput {
  displayName: string;
  role: StaffRole;
  /** Staging only. Empty for a local server. */
  stagingToken?: string | undefined;
}

/** `POST /api/v1/dev/tokens`. The response gives everything the app needs, so the app never decodes the JWT. */
export function mintToken(api: ApiClient, request: DevTokenRequest, stagingToken?: string): Promise<DevTokenResponse> {
  const headers = stagingToken === undefined || stagingToken === '' ? {} : { [STAGING_TOKEN_HEADER]: stagingToken };
  return api.POST('/api/v1/dev/tokens', { body: request, headers }).then(unwrap);
}

const uuid = z.uuid();

/**
 * Logs in with the dev login. It reuses the remembered user id, so one person stays one actor in the audit log, and
 * remembers the staging token for the re-mint.
 */
export async function logIn(api: ApiClient, store: SessionStore, input: LoginInput): Promise<Session> {
  const remembered = savedUserId.get();
  const userId = uuid.safeParse(remembered).success ? remembered : undefined;
  const stagingToken = input.stagingToken?.trim() ?? '';
  const response = await mintToken(
    api,
    { role: input.role, displayName: input.displayName.trim(), ...(userId === undefined ? {} : { userId }) },
    stagingToken,
  );
  savedUserId.set(response.userId);
  savedStagingToken.set(stagingToken);
  const session = toSession(response);
  store.getState().signIn(session);
  return session;
}

/** A new token for the same person, with the staging token from this tab. */
export async function remintSession(api: ApiClient, session: Session): Promise<Session> {
  const response = await mintToken(
    api,
    { role: session.role, displayName: session.displayName, userId: session.userId },
    savedStagingToken.get(),
  );
  return toSession(response);
}

/** Ends the session and forgets the staging token. The user id stays, for the next login. */
export function logOut(store: SessionStore): void {
  store.getState().signOut();
  savedStagingToken.set(undefined);
}

import type { CommandKind, IssueCommand, TrackingAccuracy } from '../../api/types';

// The issue-command form as data (C2): a draft the fields edit, the errors of a draft, and the request body. The
// limits are the contract's (`IssueCommand`, `Geofence` in rts-v1.yaml), which `IssueCommandBody.validate` on the
// server also checks.

export const COMMAND_KINDS = ['SET_CONSTRAINT', 'REGISTER_GEOFENCES', 'FLUSH_QUEUE'] as const satisfies CommandKind[];
export const ACCURACIES = ['HIGH', 'BALANCED', 'LOW', 'PASSIVE'] as const satisfies TrackingAccuracy[];

/** What a Mantine NumberInput gives: a number, or the text while it is empty or half typed, such as `-` or `1.`. */
export type NumberText = number | string;

export interface RegionDraft {
  /** A React key for the row. Never sent. */
  key: number;
  id: string;
  lat: NumberText;
  lng: NumberText;
  radiusM: NumberText;
}

export interface CommandDraft {
  kind: CommandKind;
  maxAccuracy: TrackingAccuracy | '';
  minIntervalMs: NumberText;
  regions: RegionDraft[];
}

/** Field name to message. Region fields are `regions.0.lat` and so on. */
export type CommandErrors = Record<string, string>;

let regionKeys = 0;

export function newRegion(): RegionDraft {
  regionKeys += 1;
  return { key: regionKeys, id: '', lat: '', lng: '', radiusM: '' };
}

export function emptyDraft(): CommandDraft {
  return { kind: 'SET_CONSTRAINT', maxAccuracy: '', minIntervalMs: '', regions: [newRegion()] };
}

function toNumber(value: NumberText): number {
  if (typeof value === 'number') return value;
  return value.trim() === '' ? Number.NaN : Number(value);
}

function inRange(value: number, min: number, max: number): boolean {
  return Number.isFinite(value) && value >= min && value <= max;
}

export function commandErrors(draft: CommandDraft): CommandErrors {
  const errors: CommandErrors = {};
  switch (draft.kind) {
    case 'SET_CONSTRAINT': {
      if (draft.maxAccuracy === '') errors.maxAccuracy = 'Pick an accuracy.';
      const interval = toNumber(draft.minIntervalMs);
      if (!Number.isSafeInteger(interval) || interval < 0) errors.minIntervalMs = 'A whole number of 0 or more.';
      break;
    }
    case 'REGISTER_GEOFENCES': {
      if (draft.regions.length === 0) errors.regions = 'Add at least one region.';
      draft.regions.forEach((region, index) => {
        const at = `regions.${String(index)}`;
        if (region.id.trim() === '') errors[`${at}.id`] = 'Give an id.';
        if (!inRange(toNumber(region.lat), -90, 90)) errors[`${at}.lat`] = 'From -90 to 90.';
        if (!inRange(toNumber(region.lng), -180, 180)) errors[`${at}.lng`] = 'From -180 to 180.';
        const radius = toNumber(region.radiusM);
        if (!Number.isFinite(radius) || radius <= 0) errors[`${at}.radiusM`] = 'More than 0.';
      });
      break;
    }
    case 'FLUSH_QUEUE':
      break;
  }
  return errors;
}

/**
 * The request body. Call it only for a draft without errors. The reason goes with every kind (C4). The server keeps it
 * for SET_CONSTRAINT and ignores it for the other two kinds.
 */
export function toIssueCommand(draft: CommandDraft, reason: string): IssueCommand {
  switch (draft.kind) {
    case 'SET_CONSTRAINT':
      return {
        kind: draft.kind,
        maxAccuracy: draft.maxAccuracy === '' ? undefined : draft.maxAccuracy,
        minIntervalMs: toNumber(draft.minIntervalMs),
        reason,
      };
    case 'REGISTER_GEOFENCES':
      return {
        kind: draft.kind,
        regions: draft.regions.map((region) => ({
          id: region.id.trim(),
          lat: toNumber(region.lat),
          lng: toNumber(region.lng),
          radiusM: toNumber(region.radiusM),
        })),
        reason,
      };
    case 'FLUSH_QUEUE':
      return { kind: draft.kind, reason };
  }
}

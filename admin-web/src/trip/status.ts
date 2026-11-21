import { z } from 'zod';

// The trip stages, in their order. A module of its own, because the router's search schema needs them in the main
// chunk, and the socket schemas belong in the trip page's chunk.

export const TRIP_STATUSES = ['REQUESTED', 'ACCEPTED', 'ARRIVED', 'IN_PROGRESS', 'COMPLETED', 'CANCELLED'] as const;
export const tripStatus = z.enum(TRIP_STATUSES);
export type TripStatus = z.infer<typeof tripStatus>;

/** COMPLETED and CANCELLED. Nothing changes a trip in one of them, and the trip socket closes. */
export function isFinalStatus(status: TripStatus): boolean {
  return status === 'COMPLETED' || status === 'CANCELLED';
}

/** The stage in words, for badges and filters. */
export const STATUS_LABELS: Record<TripStatus, string> = {
  REQUESTED: 'Requested',
  ACCEPTED: 'Accepted',
  ARRIVED: 'Driver arrived',
  IN_PROGRESS: 'In progress',
  COMPLETED: 'Completed',
  CANCELLED: 'Cancelled',
};

/** Mantine colours per stage: blue while it waits, green while it runs, grey at the end, red when cancelled. */
export const STATUS_COLORS: Record<TripStatus, string> = {
  REQUESTED: 'yellow',
  ACCEPTED: 'blue',
  ARRIVED: 'cyan',
  IN_PROGRESS: 'green',
  COMPLETED: 'gray',
  CANCELLED: 'red',
};

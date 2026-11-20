// TC-12-CON-01. Compiled by `npm run typecheck`, never run. Each line below must fail to compile against the types
// generated from rts-v1.yaml. If the typed mocks stopped checking the contract, a @ts-expect-error would have nothing
// to expect, and the typecheck would fail on it.
import { overviewBody, http } from './server';

export const forbiddenByTheContract = [
  http.get('/api/v1/admin/overview', ({ response }) =>
    // @ts-expect-error onlineDrivers is an integer in FleetOverview, not a string.
    response(200).json({ ...overviewBody, onlineDrivers: 'many' }),
  ),
  http.get('/api/v1/admin/overview', ({ response }) =>
    // @ts-expect-error FleetOverview requires computedAt.
    response(200).json({ onlineDrivers: 1, driversOnTrip: 0, activeTrips: 0, disabledDrivers: 0 }),
  ),
  // @ts-expect-error The contract has no such path.
  http.get('/api/v1/admin/dashboard', () => new Response()),
];

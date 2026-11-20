// Names for the generated contract types (npm run generate, from rts-v1.yaml). Import API types from here, so a
// page never spells out components['schemas'][...] itself.
import type { components, paths } from '../../generated/rts-v1';

export type { paths };
type Schemas = components['schemas'];

export type Role = Schemas['Role'];
export type Problem = Schemas['Problem'];
export type DevTokenRequest = Schemas['DevTokenRequest'];
export type DevTokenResponse = Schemas['DevTokenResponse'];
export type FleetOverview = Schemas['FleetOverview'];

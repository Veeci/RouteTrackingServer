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
export type TripStatus = Schemas['TripStatus'];

export type FleetPage = Schemas['FleetPage'];
export type FleetDriver = Schemas['FleetDriver'];
export type DriverDetail = Schemas['DriverDetail'];
export type DriverAccount = Schemas['DriverAccount'];
export type AccountChangeRequest = Schemas['AccountChangeRequest'];

export type IssueCommand = Schemas['IssueCommand'];
export type CommandKind = IssueCommand['kind'];
export type Command = Schemas['Command'];
export type CommandList = Schemas['CommandList'];
export type CommandStatus = Schemas['CommandStatus'];
export type TrackingAccuracy = Schemas['TrackingAccuracy'];
export type Geofence = Schemas['Geofence'];

export type SessionHealth = Schemas['SessionHealth'];
export type ProviderReport = Schemas['ProviderReport'];
export type ProviderGroup = Schemas['ProviderGroup'];

export type AuditPage = Schemas['AuditPage'];
export type AuditRecord = Schemas['AuditRecord'];

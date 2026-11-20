// Typed sample bodies for the 12c pages. The types come from the contract, so a fixture the spec does not allow
// fails the typecheck.
import type {
  AuditPage,
  AuditRecord,
  Command,
  DriverAccount,
  DriverDetail,
  FleetDriver,
  FleetPage,
  ProviderGroup,
  ProviderReport,
  SessionHealth,
} from '../api/types';

export const AT = '2026-11-20T08:30:00Z';
export const DRIVER_ID = '7f1c2a9e-5b3d-4c1e-8a2f-000000000001';
export const SESSION_ID = '3b9d6f1a-2c4e-4f8a-9b1c-000000000101';
export const ADMIN_ID = '0b9c6a7e-4a52-4b4f-9a1e-3f1f2a8f6d10';
export const COMMAND_ID = 'c0a1b2c3-d4e5-4f60-8a1b-000000000001';

/** A UUID that differs in its last digits. */
export function idOf(prefix: string, n: number): string {
  return `${prefix.slice(0, 24)}${String(n).padStart(12, '0')}`;
}

export function fleetDriver(n: number, overrides: Partial<FleetDriver> = {}): FleetDriver {
  return {
    driverId: idOf(DRIVER_ID, n),
    displayName: `Driver ${String(n)}`,
    enabled: true,
    online: true,
    lastSeenAt: AT,
    position: { lat: 21.03, lng: 105.85, at: AT },
    device: { batteryPct: 80, isCharging: false, at: AT },
    ...overrides,
  };
}

export function fleetPage(items: FleetDriver[], nextCursor?: string): FleetPage {
  return { items, at: AT, ...(nextCursor === undefined ? {} : { nextCursor }) };
}

export function driverDetail(overrides: Partial<DriverDetail> = {}): DriverDetail {
  return {
    driver: fleetDriver(1, { trip: { status: 'IN_PROGRESS', currentTripId: idOf(DRIVER_ID, 900), at: AT } }),
    recentSessions: [
      {
        sessionId: SESSION_ID,
        startedAt: '2026-11-20T07:00:00Z',
        lastSeenAt: AT,
        manufacturer: 'Google',
        model: 'Pixel 7',
        androidSdk: 35,
      },
    ],
    recentTrips: [
      {
        id: idOf(DRIVER_ID, 900),
        guestId: idOf(DRIVER_ID, 500),
        status: 'IN_PROGRESS',
        createdAt: '2026-11-20T08:00:00Z',
        updatedAt: AT,
      },
    ],
    at: AT,
    ...overrides,
  };
}

export function account(disabled: boolean, reason?: string): DriverAccount {
  return {
    driverId: DRIVER_ID,
    displayName: 'Driver 1',
    disabled,
    ...(disabled ? { disabledAt: AT, disabledReason: reason ?? 'test' } : {}),
  };
}

export function command(overrides: Partial<Command> = {}): Command {
  return {
    commandId: COMMAND_ID,
    driverId: DRIVER_ID,
    kind: 'FLUSH_QUEUE',
    status: 'APPLIED',
    createdAt: AT,
    expiresAt: '2026-11-20T08:40:00Z',
    updatedAt: AT,
    ...overrides,
  };
}

export const sessionHealth: SessionHealth = {
  sessionId: SESSION_ID,
  driverId: DRIVER_ID,
  startedAt: '2026-11-20T07:00:00Z',
  sessionLastSeenAt: AT,
  computedAt: '2026-11-20T08:45:00Z',
  fixCount: 1200,
  rejectedCount: 3,
  acceptedRatio: 0.9975,
  rejectionsByReason: { ACCURACY: 3 },
  intervalP50S: 2,
  intervalP95S: 4.5,
  accuracyP50M: 6,
  accuracyP95M: 18,
  gaps: [{ start: '2026-11-20T07:30:00Z', durationS: 420, expectedIntervalS: 2, likelyDoze: true }],
  batteryDrainPctPerHour: 4.2,
  providerMix: { GMS_FUSED: 1 },
  primaryProvider: 'GMS_FUSED',
};

export function auditRecord(n: number, overrides: Partial<AuditRecord> = {}): AuditRecord {
  return {
    id: n,
    at: AT,
    actorId: ADMIN_ID,
    actorRole: 'ADMIN',
    action: 'DRIVER_DISABLED',
    targetType: 'DRIVER',
    targetId: DRIVER_ID,
    reason: 'Phone stolen',
    details: {},
    requestId: `req-${String(n)}`,
    ...overrides,
  };
}

export function auditPage(items: AuditRecord[], nextCursor?: string): AuditPage {
  return { items, ...(nextCursor === undefined ? {} : { nextCursor }) };
}

export function providerGroup(overrides: Partial<ProviderGroup> = {}): ProviderGroup {
  return {
    primaryProvider: 'GMS_FUSED',
    hasGms: true,
    androidSdk: 35,
    sessions: 12,
    lowSample: false,
    trackedHours: 30.5,
    acceptedRatio: 0.98,
    intervalP50S: 2,
    intervalP95S: 5,
    accuracyP50M: 7,
    accuracyP95M: 20,
    batteryDrainPctPerHour: 3.1,
    gapsPerHour: 0.4,
    dozeGapsPerHour: 0.1,
    ...overrides,
  };
}

export function providerReport(groups: ProviderGroup[], from = AT, to = AT): ProviderReport {
  return { from, to, groups };
}

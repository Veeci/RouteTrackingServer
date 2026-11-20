import { describe, expect, it } from 'vitest';
import { type CommandDraft, commandErrors, emptyDraft, newRegion, toIssueCommand } from './commandForm';

function draft(change: Partial<CommandDraft>): CommandDraft {
  return { ...emptyDraft(), ...change };
}

describe('commandErrors', () => {
  it('needs an accuracy and a whole interval of 0 or more for SET_CONSTRAINT', () => {
    expect(Object.keys(commandErrors(draft({})))).toEqual(['maxAccuracy', 'minIntervalMs']);
    expect(commandErrors(draft({ maxAccuracy: 'HIGH', minIntervalMs: -1 })).minIntervalMs).toBeDefined();
    expect(commandErrors(draft({ maxAccuracy: 'HIGH', minIntervalMs: '1.5' })).minIntervalMs).toBeDefined();
    expect(commandErrors(draft({ maxAccuracy: 'HIGH', minIntervalMs: ' ' })).minIntervalMs).toBeDefined();
    expect(commandErrors(draft({ maxAccuracy: 'HIGH', minIntervalMs: 0 }))).toEqual({});
    expect(commandErrors(draft({ maxAccuracy: 'PASSIVE', minIntervalMs: '60000' }))).toEqual({});
  });

  it('checks each geofence against the Geofence schema limits', () => {
    const region = { ...newRegion(), id: 'a', lat: 90, lng: -180, radiusM: 0.5 };
    expect(commandErrors(draft({ kind: 'REGISTER_GEOFENCES', regions: [region] }))).toEqual({});

    const bad = { ...newRegion(), id: '  ', lat: 90.0001, lng: '181', radiusM: 0 };
    expect(commandErrors(draft({ kind: 'REGISTER_GEOFENCES', regions: [region, bad] }))).toEqual({
      'regions.1.id': 'Give an id.',
      'regions.1.lat': 'From -90 to 90.',
      'regions.1.lng': 'From -180 to 180.',
      'regions.1.radiusM': 'More than 0.',
    });
    expect(commandErrors(draft({ kind: 'REGISTER_GEOFENCES', regions: [{ ...region, radiusM: '-' }] }))).toEqual({
      'regions.0.radiusM': 'More than 0.',
    });
  });

  it('needs at least one region', () => {
    expect(commandErrors(draft({ kind: 'REGISTER_GEOFENCES', regions: [] }))).toEqual({
      regions: 'Add at least one region.',
    });
  });

  it('needs nothing for FLUSH_QUEUE', () => {
    expect(commandErrors(draft({ kind: 'FLUSH_QUEUE' }))).toEqual({});
  });
});

describe('toIssueCommand', () => {
  it('sends only the fields of the kind, with the reason', () => {
    const regions = [{ ...newRegion(), id: ' depot ', lat: '21.5', lng: 105, radiusM: '100' }];
    expect(toIssueCommand(draft({ maxAccuracy: 'LOW', minIntervalMs: '5000', regions }), 'r')).toEqual({
      kind: 'SET_CONSTRAINT',
      maxAccuracy: 'LOW',
      minIntervalMs: 5000,
      reason: 'r',
    });
    expect(toIssueCommand(draft({ kind: 'REGISTER_GEOFENCES', regions }), 'r')).toEqual({
      kind: 'REGISTER_GEOFENCES',
      regions: [{ id: 'depot', lat: 21.5, lng: 105, radiusM: 100 }],
      reason: 'r',
    });
    expect(toIssueCommand(draft({ kind: 'FLUSH_QUEUE' }), 'r')).toEqual({ kind: 'FLUSH_QUEUE', reason: 'r' });
  });

  it('gives each new region its own key', () => {
    expect(newRegion().key).not.toBe(newRegion().key);
  });
});

// The online and trip filters, as the Drivers page and the map offer them: a select of "Any", yes or no. The URL
// keeps them as `true`, `false`, or absent.

export const ONLINE_OPTIONS = [
  { value: '', label: 'Any' },
  { value: 'true', label: 'Online' },
  { value: 'false', label: 'Offline' },
];

export const TRIP_OPTIONS = [
  { value: '', label: 'Any' },
  { value: 'true', label: 'On a trip' },
  { value: 'false', label: 'Not on a trip' },
];

/** The select's value for a flag. */
export function flagValue(flag: boolean | undefined): string {
  return flag === undefined ? '' : String(flag);
}

/** The flag for a select's value. */
export function toFlag(value: string): boolean | undefined {
  return value === '' ? undefined : value === 'true';
}

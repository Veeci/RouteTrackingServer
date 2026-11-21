import { formatDecimal, formatInteger } from '../../components/format';

/** Metres below 1 km, kilometres with one decimal above. */
export function formatDistance(metres: number): string {
  return metres < 1_000 ? `${formatInteger(metres)} m` : formatDecimal(metres / 1_000, 'km');
}

/** Seconds as "45 s", "3 min 20 s" or "1 h 5 min". */
export function formatDuration(seconds: number): string {
  const total = Math.round(seconds);
  if (total < 60) return `${String(total)} s`;
  const minutes = Math.floor(total / 60);
  if (minutes < 60) {
    const rest = total % 60;
    return rest === 0 ? `${String(minutes)} min` : `${String(minutes)} min ${String(rest)} s`;
  }
  const hours = Math.floor(minutes / 60);
  const restMinutes = minutes % 60;
  return restMinutes === 0 ? `${String(hours)} h` : `${String(hours)} h ${String(restMinutes)} min`;
}

/** Metres a second as kilometres an hour, the unit a driver reads. */
export function formatSpeed(metresPerSecond: number): string {
  return formatDecimal(metresPerSecond * 3.6, 'km/h');
}

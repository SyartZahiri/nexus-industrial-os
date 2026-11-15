import type { KpiBucket } from '../../nexus/types';

export const WINDOWS = [
  { minutes: 60, label: '1 h' },
  { minutes: 480, label: '8 h' },
  { minutes: 1440, label: '24 h' },
];

export const SLOTS = 12;

// ponytail: each kpiSeries answer runs the analysis once per section, on the server against the database, and grows
// with the run; a section is at least 5 simulated minutes, so every 30 s is enough. Compute all sections in one pass
// in the core if a long server run makes even this slow.
export const SERIES_EVERY_MS = 30_000;

/** Always `slots` values, oldest first; sections the run has not reached yet stay null at the front. */
export function seriesSlots(buckets: KpiBucket[], pick: (b: KpiBucket) => number | null, slots = SLOTS): (number | null)[] {
  const values = buckets.slice(-slots).map((b) => {
    const v = pick(b);
    return v !== null && Number.isFinite(v) ? v : null;
  });
  return [...Array<null>(slots - values.length).fill(null), ...values];
}

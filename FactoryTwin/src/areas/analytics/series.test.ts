import { describe, expect, it } from 'vitest';
import type { KpiBucket } from '../../nexus/types';
import { seriesSlots } from './series';

const bucket = (toMin: number, throughputPerHour: number, oee: number | null): KpiBucket => ({
  fromMs: Math.max(0, (toMin - 10) * 60_000),
  toMs: toMin * 60_000,
  throughputPerHour,
  onTimeRate: 1,
  avgLeadTimeS: 0,
  oee,
});

describe('analytics series', () => {
  it('seriesWithoutDataLeavesGaps', () => {
    const buckets = [bucket(5, 0, null), bucket(15, 6, 0.4), bucket(25, 12, 0.55)];
    expect(seriesSlots(buckets, (b) => b.throughputPerHour, 5)).toEqual([null, null, 0, 6, 12]);
    expect(seriesSlots(buckets, (b) => b.oee, 5)).toEqual([null, null, null, 0.4, 0.55]);
    expect(seriesSlots([], (b) => b.oee, 3)).toEqual([null, null, null]);
    expect(seriesSlots([bucket(5, Number.NaN, null)], (b) => b.throughputPerHour, 1)).toEqual([null]);
  });
});

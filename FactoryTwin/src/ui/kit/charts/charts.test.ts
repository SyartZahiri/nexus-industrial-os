import { describe, expect, it } from 'vitest';
import { niceMax, ticks } from './scale';
import { statusShares } from './status';

describe('chart scales', () => {
  it('rounds the top of an axis to 1, 2 or 5 times a power of ten', () => {
    expect(niceMax(0)).toBe(1);
    expect(niceMax(-3)).toBe(1);
    expect(niceMax(0.8)).toBe(1);
    expect(niceMax(13)).toBe(20);
    expect(niceMax(42)).toBe(50);
    expect(niceMax(50)).toBe(50);
    expect(niceMax(51)).toBe(100);
    expect(ticks(42)).toEqual([0, 12.5, 25, 37.5, 50]);
  });

  it('turns time in status into shares in a fixed order and drops empty ones', () => {
    expect(statusShares({})).toEqual([]);
    expect(statusShares({ IDLE: 0, RUNNING: 0 })).toEqual([]);
    const s = statusShares({ IDLE: 1000, RUNNING: 3000, FAULT: 0 });
    expect(s.map((x) => x.status)).toEqual(['RUNNING', 'IDLE']);
    expect(s.map((x) => x.share)).toEqual([0.75, 0.25]);
  });
});

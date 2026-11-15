/** The top of an axis: the next 1, 2 or 5 × 10^k at or above v; 1 when there is nothing to show. */
export function niceMax(v: number): number {
  if (!(v > 0)) return 1;
  const p = 10 ** Math.floor(Math.log10(v));
  for (const f of [1, 2, 5, 10]) if (f * p >= v) return f * p;
  return 10 * p;
}

/** count + 1 evenly spaced values from 0 to niceMax(max). */
export function ticks(max: number, count = 4): number[] {
  const top = niceMax(max);
  return Array.from({ length: count + 1 }, (_, i) => (top * i) / count);
}

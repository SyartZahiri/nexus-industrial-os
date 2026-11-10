/** Seeded PRNG (mulberry32). The same seed gives the same simulation run. */
export class Rng {
  private s: number;
  constructor(seed: number) {
    this.s = seed >>> 0 || 1;
  }
  next(): number {
    let t = (this.s += 0x6d2b79f5);
    t = Math.imul(t ^ (t >>> 15), t | 1);
    t ^= t + Math.imul(t ^ (t >>> 7), t | 61);
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  }
  range(min: number, max: number): number {
    return min + (max - min) * this.next();
  }
  int(min: number, max: number): number {
    return Math.floor(this.range(min, max + 1));
  }
  pick<T>(items: readonly T[]): T {
    return items[Math.floor(this.next() * items.length)];
  }
  /** Approximately normal distributed noise (Irwin–Hall). */
  gauss(sigma = 1): number {
    return (this.next() + this.next() + this.next() + this.next() - 2) * sigma * 1.732;
  }
  /** Exponential distribution with given mean. */
  exp(mean: number): number {
    return -Math.log(1 - this.next()) * mean;
  }
}

import type { RoutePlanner } from '../integration/contracts';
import type { PathGraph } from './layout';

/** Default local route planner: Dijkstra on the directed AGV network. Replaceable by RouteMind. */
export class DijkstraPlanner implements RoutePlanner {
  readonly id = 'local-dijkstra';
  readonly label = 'Lokal (Dijkstra)';
  private cache = new Map<string, string[] | null>();
  constructor(private graph: PathGraph) {}

  plan(from: string, to: string): string[] | null {
    if (from === to) return [from];
    const key = `${from}>${to}`;
    if (this.cache.has(key)) return this.cache.get(key)!;
    const dist = new Map<string, number>([[from, 0]]);
    const prev = new Map<string, string>();
    const open = new Set<string>([from]);
    while (open.size) {
      let cur = '';
      let best = Infinity;
      for (const n of open) {
        const d = dist.get(n)!;
        if (d < best) {
          best = d;
          cur = n;
        }
      }
      open.delete(cur);
      if (cur === to) break;
      for (const e of this.graph.out.get(cur) ?? []) {
        const nd = best + e.length;
        if (nd < (dist.get(e.to) ?? Infinity)) {
          dist.set(e.to, nd);
          prev.set(e.to, cur);
          open.add(e.to);
        }
      }
    }
    if (!dist.has(to)) {
      this.cache.set(key, null);
      return null;
    }
    const path = [to];
    let c = to;
    while (c !== from) {
      c = prev.get(c)!;
      path.unshift(c);
    }
    this.cache.set(key, path);
    return path;
  }

  distance(from: string, to: string): number {
    const p = this.plan(from, to);
    if (!p) return Infinity;
    let d = 0;
    for (let i = 0; i < p.length - 1; i++) {
      const e = this.graph.out.get(p[i])!.find((x) => x.to === p[i + 1])!;
      d += e.length;
    }
    return d;
  }
}

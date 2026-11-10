import { PRODUCT_IDS } from '../sim/config';
import { PATH_GRAPH, type PathGraph } from '../sim/layout';
import type { ConnectorStatus, FactoryConnector, FactoryTwinApi, RoutePlanner, TelemetryFrame } from './contracts';

/**
 * SmartFactory connector (MES/ERP layer).
 *
 * Demo mode: emulates an MES that subscribes to domain events, consumes telemetry every few seconds
 * and pushes production orders through the public command API. A production adapter would replace
 * `pushOrder`/`publishTelemetry` with real transport (REST, MQTT, OPC UA); the contract stays the same.
 */
export class SmartFactoryConnector implements FactoryConnector {
  readonly id = 'smartfactory';
  readonly label = 'SmartFactory';
  readonly description = 'MES/ERP-Anbindung für Aufträge, Telemetrie und Ereignisse';
  status: ConnectorStatus = 'disconnected';
  stats = { telemetryFrames: 0, eventsReceived: 0, ordersPushed: 0, lastFrame: null as TelemetryFrame | null };
  private unsub: (() => void) | null = null;
  private timer: ReturnType<typeof setInterval> | null = null;
  private api: FactoryTwinApi | null = null;
  private lastOrderAt = 0;

  async connect(api: FactoryTwinApi) {
    this.status = 'connecting';
    this.api = api;
    await new Promise((r) => setTimeout(r, 450));
    this.unsub = api.subscribe('*', () => {
      this.stats.eventsReceived++;
    });
    this.timer = setInterval(() => this.tick(), 2000);
    this.status = 'connected';
  }

  private tick() {
    if (!this.api) return;
    const frame = this.api.getTelemetry();
    this.stats.telemetryFrames++;
    this.stats.lastFrame = frame;
    // MES releases a new order whenever WIP is low (pull principle), at most every 6 sim-minutes.
    if (frame.time < this.lastOrderAt) this.lastOrderAt = 0; // simulation was reset
    if (frame.kpi.wip < 10 && frame.time - this.lastOrderAt > 360) {
      this.lastOrderAt = frame.time;
      const product = PRODUCT_IDS[Math.floor(Math.random() * PRODUCT_IDS.length)];
      this.api.commands.createOrder({ product, quantity: 6 + Math.floor(Math.random() * 6), priority: 'normal', source: 'smartfactory' });
      this.stats.ordersPushed++;
    }
  }

  async disconnect() {
    this.unsub?.();
    if (this.timer) clearInterval(this.timer);
    this.unsub = null;
    this.timer = null;
    this.api = null;
    this.status = 'disconnected';
  }
}

/**
 * Congestion-aware planner as a stand-in for RouteMind: edge cost = length + penalty for vehicles
 * currently travelling on/near the edge. Demonstrates that the planner is swappable at runtime.
 */
export class CongestionAwarePlanner implements RoutePlanner {
  readonly id = 'routemind-demo';
  readonly label = 'RouteMind (Stau-adaptiv)';
  plannedRoutes = 0;
  constructor(
    private graph: PathGraph,
    private occupancy: () => { x: number; z: number }[],
  ) {}

  private penalty(a: { x: number; z: number }, b: { x: number; z: number }, vehicles: { x: number; z: number }[]) {
    let p = 0;
    for (const v of vehicles) {
      const mx = (a.x + b.x) / 2;
      const mz = (a.z + b.z) / 2;
      const d = Math.hypot(v.x - mx, v.z - mz);
      if (d < 3) p += (3 - d) * 2.5;
    }
    return p;
  }

  private cache: { at: number; v: { x: number; z: number }[] } = { at: -1, v: [] };

  private vehicles() {
    const now = performance.now();
    if (now - this.cache.at > 200) this.cache = { at: now, v: this.occupancy() };
    return this.cache.v;
  }

  plan(from: string, to: string): string[] | null {
    if (from === to) return [from];
    this.plannedRoutes++;
    const vehicles = this.vehicles();
    const dist = new Map<string, number>([[from, 0]]);
    const prev = new Map<string, string>();
    const open = new Set([from]);
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
      const a = this.graph.nodes.get(cur)!.pos;
      for (const e of this.graph.out.get(cur) ?? []) {
        const b = this.graph.nodes.get(e.to)!.pos;
        const nd = best + e.length + this.penalty(a, b, vehicles);
        if (nd < (dist.get(e.to) ?? Infinity)) {
          dist.set(e.to, nd);
          prev.set(e.to, cur);
          open.add(e.to);
        }
      }
    }
    if (!dist.has(to)) return null;
    const path = [to];
    let c = to;
    while (c !== from) {
      c = prev.get(c)!;
      path.unshift(c);
    }
    return path;
  }

  distance(from: string, to: string): number {
    const p = this.plan(from, to);
    if (!p) return Infinity;
    let d = 0;
    for (let i = 0; i < p.length - 1; i++) {
      const a = this.graph.nodes.get(p[i])!.pos;
      const b = this.graph.nodes.get(p[i + 1])!.pos;
      d += Math.hypot(a.x - b.x, a.z - b.z);
    }
    return d;
  }
}

export class RouteMindConnector implements FactoryConnector {
  readonly id = 'routemind';
  readonly label = 'RouteMind';
  readonly description = 'Routenplanung für die AGV-Flotte, ersetzt den lokalen Planer';
  status: ConnectorStatus = 'disconnected';
  planner: CongestionAwarePlanner | null = null;
  private api: FactoryTwinApi | null = null;

  async connect(api: FactoryTwinApi) {
    this.status = 'connecting';
    await new Promise((r) => setTimeout(r, 450));
    this.api = api;
    this.planner = new CongestionAwarePlanner(PATH_GRAPH, () => api.getTelemetry().agvs.map((a) => ({ x: a.x, z: a.z })));
    api.setRoutePlanner(this.planner);
    this.status = 'connected';
  }

  async disconnect() {
    this.api?.setRoutePlanner(null);
    this.api = null;
    this.planner = null;
    this.status = 'disconnected';
  }
}

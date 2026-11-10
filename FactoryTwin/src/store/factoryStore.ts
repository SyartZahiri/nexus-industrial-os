import { create } from 'zustand';
import { createFactoryApi } from '../integration/api';
import { RouteMindConnector, SmartFactoryConnector } from '../integration/adapters';
import type { ConnectorStatus, FactoryConnector, FactoryTwinApi } from '../integration/contracts';
import { isMachineAvailable, machineOee, SimulationEngine } from '../sim/engine';
import type {
  Agv,
  Alarm,
  Conveyor,
  HistorySeries,
  Inventory,
  Machine,
  Order,
  Rules,
  SimEvent,
} from '../sim/types';

/* ------------------------------------------------------------------ */
/* Engine singleton: all views read from this one simulation.         */
/* ------------------------------------------------------------------ */

const urlSeed = Number(new URLSearchParams(window.location.search).get('seed'));
export const engine = new SimulationEngine(Number.isFinite(urlSeed) && urlSeed > 0 ? urlSeed : 4711);
export const api: FactoryTwinApi = createFactoryApi(() => engine);

declare global {
  interface Window {
    FactoryTwin?: FactoryTwinApi & { engine: SimulationEngine };
  }
}
window.FactoryTwin = Object.assign(api, { engine });

/* ------------------------------------------------------------------ */
/* Snapshot: immutable view of the engine state for React panels       */
/* ------------------------------------------------------------------ */

export interface Kpis {
  throughput: number;
  oee: number;
  availability: number;
  performance: number;
  quality: number;
  utilization: number;
  powerKW: number;
  energyKWh: number;
  energyPerPart: number;
  wip: number;
  scrapRate: number;
  totalGood: number;
  totalScrap: number;
  avgLeadTime: number;
  onTimeRate: number;
  openOrders: number;
  activeTransports: number;
  agvUtilization: number;
}

export interface Snapshot {
  time: number;
  running: boolean;
  speed: number;
  seed: number;
  machines: Machine[];
  conveyors: Conveyor[];
  agvs: Agv[];
  orders: Order[];
  alarms: Alarm[];
  events: SimEvent[];
  inventory: Inventory;
  rules: Rules;
  history: HistorySeries;
  kpi: Kpis;
  rawQueue: number;
  plannerLabel: string;
  /** Per-machine OEE breakdown. */
  oee: Record<string, { a: number; p: number; q: number; oee: number }>;
  /** Workpiece lookup for the selected detail panels (id → short info). */
  wpInfo: Record<string, { product: string; orderId: string; step: number }>;
}

function buildSnapshot(prev: Snapshot | null): Snapshot {
  const s = engine.state;
  const ms = s.machines;
  const oee: Snapshot['oee'] = {};
  let a = 0;
  let p = 0;
  let q = 0;
  for (const m of ms) {
    const o = machineOee(m);
    oee[m.id] = o;
    a += o.a;
    p += o.p;
    q += o.q;
  }
  const n = ms.length;
  const completedOrders = s.orders.filter((o) => o.status === 'ABGESCHLOSSEN' && o.completedAt !== null);
  const onTime = completedOrders.filter((o) => (o.completedAt ?? 0) <= o.dueAt).length;
  const leads = s.metrics.leadTimes;
  const wip = [...s.workpieces.values()].filter((w) => w.location.type !== 'finished' && w.location.type !== 'scrap').length;
  const wpInfo: Snapshot['wpInfo'] = {};
  for (const w of s.workpieces.values()) {
    if (w.location.type === 'finished' || w.location.type === 'scrap') continue;
    wpInfo[w.id] = { product: w.product, orderId: w.orderId, step: w.step };
  }
  const kpi: Kpis = {
    throughput: engine.throughput(),
    oee: (a / n) * (p / n) * (q / n),
    availability: a / n,
    performance: p / n,
    quality: q / n,
    utilization: ms.reduce((acc, m) => acc + m.stats.utilEma, 0) / n,
    powerKW: s.metrics.powerKW,
    energyKWh: s.metrics.energyKWh,
    energyPerPart: s.metrics.totalGood > 0 ? s.metrics.energyKWh / s.metrics.totalGood : 0,
    wip,
    scrapRate: s.metrics.totalScrap / Math.max(1, s.metrics.totalScrap + s.metrics.totalGood),
    totalGood: s.metrics.totalGood,
    totalScrap: s.metrics.totalScrap,
    avgLeadTime: leads.length ? leads.reduce((x, y) => x + y, 0) / leads.length : 0,
    onTimeRate: completedOrders.length ? onTime / completedOrders.length : 1,
    openOrders: s.orders.filter((o) => o.status !== 'ABGESCHLOSSEN' && o.status !== 'STORNIERT').length,
    activeTransports: s.tasks.length,
    agvUtilization: s.agvs.reduce((acc, v) => acc + v.stats.busyEma, 0) / s.agvs.length,
  };
  const lastEvent = s.events[s.events.length - 1]?.id ?? 0;
  const prevLast = prev?.events[prev.events.length - 1]?.id ?? -1;
  const histLen = s.history.t.length;
  const historyChanged = !prev || prev.history.t.length !== histLen || prev.history.t[histLen - 1] !== s.history.t[histLen - 1];
  return {
    time: s.time,
    running: s.running,
    speed: s.speed,
    seed: s.seed,
    machines: structuredClone(ms),
    conveyors: structuredClone(s.conveyors),
    agvs: structuredClone(s.agvs),
    orders: structuredClone(s.orders),
    alarms: structuredClone(s.alarms),
    events: prev && prevLast === lastEvent && prev.events.length === s.events.length ? prev.events : s.events.slice(),
    inventory: structuredClone(s.inventory),
    rules: { ...s.rules },
    history: historyChanged || !prev ? structuredClone(s.history) : prev.history,
    kpi,
    rawQueue: s.rawQueue.length,
    plannerLabel: engine.planner.label,
    oee,
    wpInfo,
  };
}

/* ------------------------------------------------------------------ */
/* UI store                                                            */
/* ------------------------------------------------------------------ */

export type SelectionType = 'machine' | 'conveyor' | 'agv' | 'raw' | 'finished';
export interface Selection {
  type: SelectionType;
  id: string;
}

export type Quality = 'high' | 'balanced' | 'performance';
export type RightTab = 'details' | 'orders' | 'rules' | 'integration';
export type CameraPreset = 'overview' | 'machining' | 'assembly' | 'logistics' | 'top' | 'focus';

export interface ConnectorView {
  id: string;
  label: string;
  description: string;
  status: ConnectorStatus;
  detail: string;
}

interface FactoryStore {
  snap: Snapshot;
  selected: Selection | null;
  hovered: string | null;
  rightTab: RightTab;
  quality: Quality;
  showLabels: boolean;
  showPaths: boolean;
  logOpen: boolean;
  logTab: 'events' | 'alarms';
  leftOpen: boolean;
  rightOpen: boolean;
  camera: { preset: CameraPreset; nonce: number };
  connectors: ConnectorView[];
  select(sel: Selection | null, focus?: boolean): void;
  hover(id: string | null): void;
  setRightTab(tab: RightTab): void;
  setQuality(q: Quality): void;
  toggle(key: 'showLabels' | 'showPaths' | 'logOpen' | 'leftOpen' | 'rightOpen'): void;
  setPanel(key: 'leftOpen' | 'rightOpen' | 'logOpen', open: boolean): void;
  setCamera(preset: CameraPreset): void;
  showLog(tab: 'events' | 'alarms'): void;
  toggleConnector(id: string): Promise<void>;
  refresh(): void;
}

const isMobile = window.matchMedia('(max-width: 1023px)').matches;
const lowEnd = isMobile || (navigator.hardwareConcurrency ?? 8) <= 4;

export const smartFactory = new SmartFactoryConnector();
export const routeMind = new RouteMindConnector();
const connectorList: FactoryConnector[] = [smartFactory, routeMind];

function connectorViews(): ConnectorView[] {
  return connectorList.map((c) => ({
    id: c.id,
    label: c.label,
    description: c.description,
    status: c.status,
    detail:
      c === smartFactory
        ? c.status === 'connected'
          ? `${smartFactory.stats.telemetryFrames} Telemetrie-Frames, ${smartFactory.stats.eventsReceived} Ereignisse, ${smartFactory.stats.ordersPushed} Aufträge eingesteuert`
          : 'Nicht verbunden. Aufträge kommen aus dem lokalen Generator.'
        : c.status === 'connected'
          ? `Aktiver Planer: ${routeMind.planner?.label ?? '–'}, ${routeMind.planner?.plannedRoutes ?? 0} Routen berechnet`
          : 'Nicht verbunden. Der lokale Dijkstra-Planer fährt die AGVs.',
  }));
}

export const useFactory = create<FactoryStore>((set, get) => ({
  snap: buildSnapshot(null),
  selected: null,
  hovered: null,
  rightTab: 'orders',
  quality: lowEnd ? 'performance' : 'high',
  showLabels: !isMobile,
  showPaths: true,
  logOpen: !isMobile,
  logTab: 'events',
  leftOpen: !isMobile,
  rightOpen: !isMobile,
  camera: { preset: 'overview', nonce: 0 },
  connectors: connectorViews(),
  select(sel, focus = false) {
    set((st) => ({
      selected: sel,
      rightTab: sel ? 'details' : st.rightTab === 'details' ? 'orders' : st.rightTab,
      rightOpen: sel ? true : st.rightOpen,
      camera: focus && sel ? { preset: 'focus', nonce: st.camera.nonce + 1 } : st.camera,
    }));
  },
  hover(id) {
    if (get().hovered !== id) set({ hovered: id });
  },
  setRightTab(tab) {
    set({ rightTab: tab, rightOpen: true });
  },
  setQuality(q) {
    set({ quality: q });
  },
  toggle(key) {
    set((st) => ({ [key]: !st[key] }) as Partial<FactoryStore>);
  },
  setPanel(key, open) {
    set({ [key]: open } as Partial<FactoryStore>);
  },
  setCamera(preset) {
    set((st) => ({ camera: { preset, nonce: st.camera.nonce + 1 } }));
  },
  showLog(tab) {
    set({ logOpen: true, logTab: tab });
  },
  async toggleConnector(id) {
    const c = connectorList.find((x) => x.id === id);
    if (!c || c.status === 'connecting') return;
    const refreshViews = () => set({ connectors: connectorViews() });
    if (c.status === 'connected') {
      await c.disconnect();
      engine.note('info', 'Integration', c.label + ' getrennt');
    } else {
      const p = c.connect(api);
      refreshViews();
      await p;
      engine.note('success', 'Integration', c.label + ' verbunden');
    }
    refreshViews();
    get().refresh();
  },
  refresh() {
    set({ snap: buildSnapshot(get().snap), connectors: connectorViews() });
  },
}));

/* ------------------------------------------------------------------ */
/* Simulation driver                                                    */
/* ------------------------------------------------------------------ */

let loopStarted = false;
const PUBLISH_INTERVAL = 150; // ms

export function startSimulationLoop() {
  if (loopStarted) return;
  loopStarted = true;
  let last = performance.now();
  let lastPublish = 0;
  let lastRevision = -1;
  const frame = (now: number) => {
    const dt = (now - last) / 1000;
    last = now;
    engine.advance(dt);
    const due = now - lastPublish > PUBLISH_INTERVAL;
    if (due && (engine.state.running || engine.revision !== lastRevision)) {
      lastPublish = now;
      lastRevision = engine.revision;
      useFactory.getState().refresh();
    }
    requestAnimationFrame(frame);
  };
  requestAnimationFrame(frame);
}

/** Run a command against the engine and publish the resulting state immediately. */
export function command<T>(fn: (e: SimulationEngine) => T): T {
  const r = fn(engine);
  useFactory.getState().refresh();
  return r;
}

export { isMachineAvailable };

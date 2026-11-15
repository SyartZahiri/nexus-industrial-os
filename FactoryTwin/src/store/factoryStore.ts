import { create } from 'zustand';
import type { NexusClient } from '../nexus/client';
import type { EventFilter, EventPage } from '../nexus/eventFilter';
import type { Answer, Command, NexusEvent, Query, SimState } from '../nexus/types';
import { toScene } from '../twin/sceneModel';
import { Twin } from '../twin/twin';
import type { SceneState } from '../twin/types';

/**
 * The user interface's state. The plant itself lives in the Java core (worker or server); this store only holds
 * what arrived from it through the NexusClient, plus selection, camera and panel settings.
 */

/** Latest and previous frame; the 3D scene reads interpolated poses from it in useFrame. */
export const twin = new Twin();

const MAX_EVENTS = 500;

export type SelectionType = 'machine' | 'conveyor' | 'agv' | 'raw' | 'finished';
export interface Selection {
  type: SelectionType;
  id: string;
}

export type Quality = 'high' | 'balanced' | 'performance';
export type RightTab = 'details' | 'events';
export type CameraPreset = 'overview' | 'machining' | 'assembly' | 'logistics' | 'top' | 'focus';
export type Connection = 'connecting' | 'open' | 'closed';

interface FactoryStore {
  scene: SceneState | null;
  sim: SimState;
  mode: 'browser' | 'server';
  connection: Connection;
  events: NexusEvent[];
  /** Counts restarts, so views holding data of the old run (a page of events, the rules form) reload. */
  run: number;
  /** The last reason the plant gave for refusing a command; shown until dismissed. */
  notice: string | null;
  selected: Selection | null;
  hovered: string | null;
  rightTab: RightTab;
  quality: Quality;
  showLabels: boolean;
  showPaths: boolean;
  leftOpen: boolean;
  rightOpen: boolean;
  camera: { preset: CameraPreset; nonce: number };
  select(sel: Selection | null, focus?: boolean): void;
  hover(id: string | null): void;
  setRightTab(tab: RightTab): void;
  setQuality(q: Quality): void;
  toggle(key: 'showLabels' | 'showPaths' | 'leftOpen' | 'rightOpen'): void;
  setPanel(key: 'leftOpen' | 'rightOpen', open: boolean): void;
  setCamera(preset: CameraPreset): void;
  dismissNotice(): void;
}

const hasWindow = typeof window !== 'undefined';
const isMobile = hasWindow && window.matchMedia('(max-width: 1023px)').matches;
const lowEnd = isMobile || (hasWindow && (navigator.hardwareConcurrency ?? 8) <= 4);

export const useFactory = create<FactoryStore>((set, get) => ({
  scene: null,
  sim: { running: false, speed: 1, seed: 0 },
  mode: 'browser',
  connection: 'connecting',
  events: [],
  run: 0,
  notice: null,
  selected: null,
  hovered: null,
  rightTab: 'events',
  quality: lowEnd ? 'performance' : 'high',
  showLabels: !isMobile,
  showPaths: true,
  leftOpen: !isMobile,
  rightOpen: !isMobile,
  camera: { preset: 'overview', nonce: 0 },
  select(sel, focus = false) {
    set((st) => ({
      selected: sel,
      rightTab: sel ? 'details' : st.rightTab === 'details' ? 'events' : st.rightTab,
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
  dismissNotice() {
    set({ notice: null });
  },
}));

let current: NexusClient | null = null;
let stop: (() => void) | null = null;

/** Connects the store to the plant; every message updates the twin first, then the store. */
export function startNexus(client: NexusClient): () => void {
  stop?.();
  current = client;
  useFactory.setState({ mode: client.mode });
  stop = client.connect((m) => {
    twin.apply(m, performance.now());
    switch (m.type) {
      case 'reset':
        useFactory.setState((s) => ({ events: [], scene: null, run: s.run + 1 }));
        break;
      case 'sim':
        useFactory.setState({ sim: { running: m.running, speed: m.speed, seed: m.seed } });
        break;
      case 'frame':
        useFactory.setState({ scene: twin.scene });
        break;
      case 'events':
        useFactory.setState((s) => ({ events: [...s.events, ...m.items].slice(-MAX_EVENTS) }));
        break;
      case 'connection':
        useFactory.setState({ connection: m.state });
        break;
    }
  });
  return stop;
}

function client(): NexusClient {
  if (!current) throw new Error('NexusClient nicht verbunden');
  return current;
}

/** Asks the plant; throws when no client is connected yet. */
export function query<T>(q: Query): Promise<Answer<T>> {
  return client().query<T>(q);
}

/** One page of the run's events that match the filter, oldest first. */
export function eventPage(f: EventFilter): Promise<EventPage> {
  return client().events(f);
}

/** Sends a command; if the plant refuses it, its reason becomes the notice and the result is false. */
export async function send(c: Command): Promise<boolean> {
  const answer = await client().command(c);
  if (!answer.ok) {
    useFactory.setState({ notice: answer.message });
    return false;
  }
  // the frame does not carry override and coolant: remember what the plant accepted
  if (c.command === 'machineConfigure' && typeof c.machineId === 'string') {
    twin.setConfig(c.machineId, { override: Number(c.override), coolant: Boolean(c.coolant) });
    useFactory.setState({ scene: twin.scene });
  }
  useFactory.setState({ notice: null });
  return true;
}

async function guarded(action: () => Promise<void>): Promise<void> {
  try {
    await action();
  } catch (e) {
    useFactory.setState({ notice: e instanceof Error ? e.message : 'Aktion fehlgeschlagen' });
  }
}

/** Play, pause, speed and reset of the simulation, in either runtime. */
export const control = {
  play: () => guarded(() => client().play()),
  pause: () => guarded(() => client().pause()),
  setSpeed: (speed: number) => guarded(() => client().setSpeed(speed)),
  reset: (seed?: number) => guarded(() => client().reset(seed)),
};

const EMPTY_SCENE: SceneState = toScene({
  simTimeMs: 0,
  machines: [],
  conveyors: [],
  agvs: [],
  stock: [],
  orders: [],
  transports: [],
});

/**
 * Read access for the 3D scene, shaped like the former engine: the latest scene state plus running and speed.
 * Before the first frame the hall stands with its machines in state UNKNOWN and no AGVs.
 */
export const plant = {
  /** AGV poses are interpolated between the two latest frames (twin.agvPose), so motion stays smooth. */
  get state(): SceneState & { running: boolean; speed: number } {
    const { running, speed } = useFactory.getState().sim;
    const scene = twin.scene ?? EMPTY_SCENE;
    const now = performance.now();
    const agvs = scene.agvs.map((a) => {
      const p = twin.agvPose(a.id, now);
      return p ? { ...a, pos: { x: p.x, z: p.z }, heading: p.heading } : a;
    });
    return { ...scene, agvs, running, speed };
  },
  machine: (id: string) => plant.state.machines.find((m) => m.id === id),
  conveyor: (id: string) => plant.state.conveyors.find((c) => c.id === id),
  agv: (id: string) => plant.state.agvs.find((a) => a.id === id),
};

/** For selectors: the scene state, or the empty hall before the first frame (a stable object, safe for React). */
export const sceneOf = (s: { scene: SceneState | null }): SceneState => s.scene ?? EMPTY_SCENE;

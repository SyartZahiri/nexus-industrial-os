import type { Frame } from '../nexus/types';
import { DEFAULT_MACHINE_CONFIG, PRODUCT_IDS } from './config';
import { nodePos } from './layout';
import { CHARGER_NODES, CONVEYOR_DEFS, INVENTORY_CAPACITY, MACHINE_DEFS } from './plantLayout';
import type {
  MachineConfig,
  ProductId,
  SceneAgv,
  SceneCharger,
  SceneConveyor,
  SceneMachine,
  SceneState,
  Workpiece,
} from './types';

/** The conveyor that carries batches in the core: from the assembly cells to the inspection cell. */
const CARRYING_CONVEYOR = 'CV-03';
/** Machines in id order, as the core lists them. */
const MACHINES_BY_ID = [...MACHINE_DEFS].sort((a, b) => (a.id < b.id ? -1 : a.id > b.id ? 1 : 0));
/** A charging AGV within this distance of a charger occupies it. */
const CHARGER_RADIUS_M = 1;

/**
 * Maps a frame of the Java core onto the shapes the 3D scene reads. The core works in batches: a machine holds the
 * batch of one operation, an AGV carries one container, only CV-03 moves parts. What the core does not model gets a
 * fixed value (empty buffers, no inspection verdict, empty side belts).
 */
export function toScene(frame: Frame, configs: Record<string, Partial<MachineConfig>> = {}): SceneState {
  const productOf = new Map(frame.orders.map((o) => [o.id, o.productId as ProductId]));
  const workpieces = new Map<string, Workpiece>();
  const wp = (orderId: string, step: number): string => {
    const id = `${orderId}/${step}`;
    const product = productOf.get(orderId);
    if (product) workpieces.set(id, { product, orderId, step });
    return id;
  };

  const machines: SceneMachine[] = MACHINES_BY_ID.map((def) => {
    const m = frame.machines.find((x) => x.id === def.id);
    return {
      ...def,
      status: m?.status ?? 'UNKNOWN',
      job:
        m?.orderId != null
          ? { wpId: wp(m.orderId, m.stepSeq), progress: m.progress, duration: 1, finished: m.progress >= 1 }
          : null,
      sensors: {
        temperature: m?.temperature ?? 0,
        vibration: m?.vibration ?? 0,
        power: m?.power ?? 0,
        spindle: m?.spindle ?? 0,
      },
      wear: m?.wear ?? 0,
      fault: m?.faultLabel ? { typeId: '', label: m.faultLabel, since: 0 } : null,
      config: { ...DEFAULT_MACHINE_CONFIG, ...configs[def.id] },
      inBuffer: [],
      outBuffer: [],
      lastVerdict: null,
    };
  });

  const conveyors: SceneConveyor[] = CONVEYOR_DEFS.map((def) => {
    const items =
      def.id === CARRYING_CONVEYOR
        ? (frame.conveyors.find((c) => c.conveyorId === def.id)?.items ?? []).map((i) => ({
            wpId: wp(i.orderId, i.stepSeq),
            s: i.positionM,
          }))
        : [];
    return {
      ...def,
      length: Math.hypot(def.end.x - def.start.x, def.end.z - def.start.z),
      status: 'RUNNING',
      items,
      beltOffset: ((frame.simTimeMs / 1000) * def.speed) % 1,
    };
  });

  const transports = new Map(frame.transports.map((t) => [t.id, t]));
  const agvs: SceneAgv[] = frame.agvs.map((a) => {
    const t = a.transportId ? transports.get(a.transportId) : undefined;
    const carrying = a.status === 'TO_DROP' || a.status === 'UNLOADING';
    let load: string | null = null;
    if (t && carrying) {
      // WIP and putaway carry a batch of the order; supply carries a material
      load = t.kind !== 'SUPPLY' && t.orderId ? wp(t.orderId, stepOf(t, frame)) : (t.materialId ?? null);
    }
    return {
      id: a.id,
      name: a.name,
      pos: { x: a.x, z: a.z },
      heading: a.heading,
      speed: a.speed,
      state: a.status,
      battery: a.battery,
      load,
      route: a.route,
      transportId: a.transportId,
      fault: a.status === 'FAULT' ? { typeId: '', label: 'Störung', since: 0 } : null,
      blockedBy: null,
    };
  });

  const chargers: SceneCharger[] = CHARGER_NODES.map((c) => {
    const at = nodePos(c.node);
    const occupant = frame.agvs.find(
      (a) => a.status === 'CHARGING' && Math.hypot(a.x - at.x, a.z - at.z) <= CHARGER_RADIUS_M,
    );
    return { ...c, occupiedBy: occupant?.id ?? null };
  });

  const onHand = (materialId: string) =>
    frame.stock.filter((s) => s.materialId === materialId).reduce((sum, s) => sum + s.onHand, 0);
  const finished = Object.fromEntries(PRODUCT_IDS.map((p) => [p, onHand(`FG-${p}`)])) as Record<ProductId, number>;

  return {
    time: frame.simTimeMs / 1000,
    machines,
    conveyors,
    agvs,
    chargers,
    inventory: {
      raw: frame.stock.filter((s) => s.materialId.startsWith('RM-')).reduce((sum, s) => sum + s.onHand, 0),
      rawCapacity: INVENTORY_CAPACITY.raw,
      finished,
      finishedCapacity: INVENTORY_CAPACITY.finished,
      scrap: frame.orders.reduce((sum, o) => sum + o.scrapped, 0),
    },
    workpieces,
    rawQueue: [],
  };
}

/** The step a carried batch belongs to: the order's next step for WIP, its last one for putaway. */
function stepOf(t: Frame['transports'][number], frame: Frame): number {
  const order = frame.orders.find((o) => o.id === t.orderId);
  if (!order) return 0;
  return t.kind === 'PUTAWAY' ? order.steps : order.stepsDone + 1;
}

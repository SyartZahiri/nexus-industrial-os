/** The data both runtimes send: the same JSON from the Java core (PlantJson, PlantCommands, PlantQueries). */

export type MachineStatus =
  | 'IDLE'
  | 'SETUP'
  | 'RUNNING'
  | 'BLOCKED'
  | 'STOPPED'
  | 'FAULT'
  | 'REPAIR'
  | 'MAINTENANCE'
  | 'UNKNOWN';

export type AgvStatus =
  | 'IDLE'
  | 'TO_PICKUP'
  | 'LOADING'
  | 'TO_DROP'
  | 'UNLOADING'
  | 'TO_CHARGE'
  | 'CHARGING'
  | 'FAULT'
  | 'OFFLINE';

export interface FrameMachine {
  id: string;
  status: MachineStatus;
  orderId: string | null;
  stepSeq: number;
  progress: number;
  temperature: number;
  vibration: number;
  power: number;
  spindle: number;
  wear: number;
  faultLabel: string | null;
}

export interface FrameConveyor {
  conveyorId: string;
  items: { orderId: string; stepSeq: number; positionM: number }[];
}

export interface FrameAgv {
  id: string;
  name: string;
  status: AgvStatus;
  x: number;
  z: number;
  heading: number;
  speed: number;
  battery: number;
  transportId: string | null;
  route: string[];
}

export interface FrameStock {
  materialId: string;
  locationId: string;
  onHand: number;
  reserved: number;
}

export interface FrameOrder {
  id: string;
  productId: string;
  quantity: number;
  priority: string;
  status: string;
  dueAtMs: number;
  good: number;
  scrapped: number;
  stepsDone: number;
  steps: number;
}

export interface FrameTransport {
  id: string;
  kind: string;
  orderId: string | null;
  materialId: string | null;
  quantity: number;
  status: string;
  agvId: string | null;
  from: string;
  to: string;
}

export interface Frame {
  simTimeMs: number;
  machines: FrameMachine[];
  conveyors: FrameConveyor[];
  agvs: FrameAgv[];
  stock: FrameStock[];
  orders: FrameOrder[];
  transports: FrameTransport[];
}

export interface NexusEvent {
  seq: number;
  eventId: string;
  simTimeMs: number;
  type: string;
  source: string;
  severity: 'INFO' | 'SUCCESS' | 'WARNING' | 'ERROR';
  correlationId: string | null;
  payload: Record<string, unknown>;
  /** Only the browser runtime sends it (PlantJson); the server's event table does not. */
  payloadType?: string;
}

export interface SimState {
  running: boolean;
  speed: number;
  seed: number;
}

export type Answer<T = unknown> = { ok: true; result: T } | { ok: false; status: number; message: string };

export type Command = { command: string } & Record<string, unknown>;
export type Query = { query: string } & Record<string, unknown>;

/** What both clients deliver; within one tick in this order: reset, sim, frame, events. */
export type NexusMessage =
  | { type: 'reset'; seed: number }
  | { type: 'sim'; running: boolean; speed: number; seed: number }
  | { type: 'frame'; frame: Frame }
  | { type: 'events'; items: NexusEvent[] }
  | { type: 'connection'; state: 'connecting' | 'open' | 'closed' };

/** Key figures of a time window, as the core's "kpis" query answers. */
export interface Kpis {
  fromMs: number;
  toMs: number;
  throughputPerHour: number;
  avgLeadTimeS: number;
  p90LeadTimeS: number;
  onTimeRate: number;
  bottleneckMachineId: string | null;
  machines: Record<
    string,
    {
      utilization: number;
      availability: number;
      performance: number;
      quality: number;
      oee: number;
      timeInStatusMs: Record<string, number>;
    }
  >;
}

/** The plant's rules, exactly the fields of the "rules" query; setRules takes any subset of them. */
export interface Rules {
  randomFaults: boolean;
  autoRepair: boolean;
  autoMaintenance: boolean;
  maintenanceThreshold: number;
  autoOrders: boolean;
  autoOrderIntervalMinutes: number;
  maxOpenOrders: number;
  shipping: boolean;
  dispatchRule: 'PRIORITY' | 'EDD' | 'FIFO' | 'SPT';
}

export type Priority = 'NORMAL' | 'HOCH' | 'EILIG';

export interface ProductInfo {
  id: string;
  name: string;
  shortName: string;
  finishedMaterialId: string;
  routing: { seq: number; process: string; baseTimeS: number; preferredMachineId: string }[];
  bom: { seq: number; materialId: string; quantity: number }[];
}

export interface PlannedOp {
  orderId: string;
  seq: number;
  machineId: string;
  startMs: number;
  endMs: number;
  setupMs: number;
}

export interface Schedule {
  rule: Rules['dispatchRule'];
  ops: PlannedOp[];
  unplannable: { orderId: string; seq: number; reason: string }[];
  lateOrders: string[];
  utilization: Record<string, number>;
  bottleneckMachineId: string | null;
}

export interface OrderDetail {
  order: {
    id: string;
    productId: string;
    quantity: number;
    priority: Priority;
    status: string;
    dueAtMs: number;
    createdAtMs: number;
    completedAtMs: number | null;
    good: number;
    scrapped: number;
  };
  operations: {
    seq: number;
    process: string;
    machineId: string | null;
    status: string;
    plannedStartMs: number | null;
    plannedEndMs: number | null;
    startedAtMs: number | null;
    endedAtMs: number | null;
    good: number;
    scrap: number;
  }[];
  materials: {
    stepSeq: number;
    kind: 'SUPPLY' | 'WIP' | 'PUTAWAY';
    materialId: string;
    quantity: number;
    transportId: string | null;
    delivered: boolean;
  }[];
}

export interface WarehouseInfo {
  materials: { id: string; name: string; kind: string; reorderPoint: number; reorderQty: number }[];
  locations: { id: string; name: string; zone: string; capacity: number }[];
}

export interface NetworkNode {
  id: string;
  x: number;
  z: number;
  dock: boolean;
}

export interface NetworkEdge {
  id: string;
  from: string;
  to: string;
  length: number;
  blocked: boolean;
}

export interface Network {
  nodes: NetworkNode[];
  edges: NetworkEdge[];
}

/** One section of the key-figure history; oee is the mean over the machines, null without any machine time. */
export interface KpiBucket {
  fromMs: number;
  toMs: number;
  throughputPerHour: number;
  onTimeRate: number;
  avgLeadTimeS: number;
  oee: number | null;
}

export type ScenarioState = 'WAITING' | 'RUNNING' | 'PASSED' | 'FAILED';

/** One run of a scenario (core query "scenarios"); steps carry simulation time and a sentence. */
export interface ScenarioRun {
  runId: string;
  scenario: string;
  state: ScenarioState;
  orderId: string | null;
  startedAtMs: number;
  finishedAtMs: number | null;
  steps: { atMs: number; text: string }[];
}

export interface ScenarioList {
  definitions: { id: string; title: string; trigger: string; expected: string; limitMinutes: number }[];
  runs: ScenarioRun[];
}

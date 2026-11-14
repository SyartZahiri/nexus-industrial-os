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

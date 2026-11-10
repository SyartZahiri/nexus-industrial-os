/**
 * Public integration contracts of FactoryTwin.
 *
 * These interfaces are the only coupling points for external systems:
 *  - SmartFactory (MES / ERP layer): pushes production orders, consumes telemetry and domain events.
 *  - RouteMind (routing / fleet intelligence): replaces the local AGV route planner.
 *
 * The simulation engine depends on these abstractions only, never on concrete adapters.
 */
import type {
  AgvState,
  ConveyorStatus,
  MachineConfig,
  MachineStatus,
  OrderPriority,
  ProductId,
  Rules,
  Severity,
} from '../sim/types';

/* ------------------------------------------------------------------ */
/* Domain events                                                       */
/* ------------------------------------------------------------------ */

export type DomainEvent =
  | { type: 'machine.status'; time: number; machineId: string; from: MachineStatus; to: MachineStatus }
  | { type: 'machine.fault'; time: number; machineId: string; fault: string; scrapped: boolean }
  | { type: 'conveyor.status'; time: number; conveyorId: string; to: ConveyorStatus }
  | { type: 'agv.state'; time: number; agvId: string; to: AgvState }
  | { type: 'transport.assigned'; time: number; taskId: string; agvId: string; wpId: string; from: string; to: string }
  | { type: 'transport.completed'; time: number; taskId: string; agvId: string; wpId: string }
  | { type: 'workpiece.processed'; time: number; wpId: string; machineId: string; step: number }
  | { type: 'workpiece.completed'; time: number; wpId: string; orderId: string; leadTime: number }
  | { type: 'workpiece.scrapped'; time: number; wpId: string; orderId: string; reason: string }
  | { type: 'order.created'; time: number; orderId: string; product: ProductId; quantity: number }
  | { type: 'order.completed'; time: number; orderId: string }
  | { type: 'order.rerouted'; time: number; wpId: string; fromMachine: string; toMachine: string }
  | { type: 'log'; time: number; severity: Severity; source: string; message: string };

export type DomainEventType = DomainEvent['type'];

/* ------------------------------------------------------------------ */
/* Commands (the control surface used by UI and external systems)       */
/* ------------------------------------------------------------------ */

export interface NewOrderInput {
  product: ProductId;
  quantity: number;
  priority: OrderPriority;
  /** Due time relative to now in seconds. */
  dueIn?: number;
  source?: 'local' | 'smartfactory' | 'operator';
}

export interface FactoryCommands {
  play(): void;
  pause(): void;
  setSpeed(speed: number): void;
  reset(seed?: number): void;
  startMachine(id: string): void;
  stopMachine(id: string): void;
  injectFault(id: string, faultTypeId?: string): void;
  repair(id: string): void;
  maintain(id: string): void;
  configureMachine(id: string, patch: Partial<MachineConfig>): void;
  startConveyor(id: string): void;
  stopConveyor(id: string): void;
  setConveyorSpeed(id: string, speed: number): void;
  setAgvEnabled(id: string, enabled: boolean): void;
  sendAgvToCharge(id: string): void;
  createOrder(input: NewOrderInput): string;
  cancelOrder(id: string): void;
  setOrderPriority(id: string, priority: OrderPriority): void;
  setRules(patch: Partial<Rules>): void;
  orderRawMaterial(qty: number): void;
  acknowledgeAlarm(key: string): void;
}

/* ------------------------------------------------------------------ */
/* Extension points                                                    */
/* ------------------------------------------------------------------ */

/** Route planning for AGVs. Default is the local Dijkstra planner; RouteMind plugs in here. */
export interface RoutePlanner {
  readonly id: string;
  readonly label: string;
  /** Returns the node sequence from `from` to `to` (inclusive) or null if unreachable. */
  plan(from: string, to: string): string[] | null;
  /** Path length in metres (used for AGV selection). */
  distance(from: string, to: string): number;
}

/** Telemetry frame published periodically to connected systems. */
export interface TelemetryFrame {
  time: number;
  machines: { id: string; status: MachineStatus; temperature: number; power: number; vibration: number; wear: number; utilization: number }[];
  agvs: { id: string; state: AgvState; battery: number; x: number; z: number }[];
  kpi: { throughput: number; oee: number; powerKW: number; energyKWh: number; wip: number; scrapRate: number };
}

export interface FactoryTwinApi {
  readonly version: string;
  commands: FactoryCommands;
  subscribe<T extends DomainEventType>(type: T | '*', handler: (e: Extract<DomainEvent, { type: T }>) => void): () => void;
  getTelemetry(): TelemetryFrame;
  setRoutePlanner(planner: RoutePlanner | null): void;
}

export type ConnectorStatus = 'disconnected' | 'connecting' | 'connected' | 'error';

/** An external system adapter (SmartFactory, RouteMind, …). */
export interface FactoryConnector {
  readonly id: 'smartfactory' | 'routemind' | string;
  readonly label: string;
  readonly description: string;
  status: ConnectorStatus;
  connect(api: FactoryTwinApi): Promise<void>;
  disconnect(): Promise<void>;
}

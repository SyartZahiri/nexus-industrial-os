/**
 * Domain model of the FactoryTwin simulation.
 * Everything rendered in 3D or shown in the dashboard is derived from these types.
 */

export type Vec2 = { x: number; z: number };

export type ProcessType = 'DREHEN' | 'FRAESEN' | 'MONTAGE' | 'PRUEFEN';
export type ProductId = 'GH200' | 'AW50' | 'FP12' | 'MT7';

export type MachineKind = 'cnc' | 'robot' | 'qc';

export type MachineStatus =
  | 'IDLE' // ready, waiting for material (starved)
  | 'SETUP' // changeover between product types
  | 'RUNNING'
  | 'BLOCKED' // finished part cannot be handed over (output full)
  | 'STOPPED' // stopped by operator
  | 'FAULT'
  | 'REPAIR'
  | 'MAINTENANCE';

export type ConveyorStatus = 'RUNNING' | 'STOPPED' | 'FAULT' | 'REPAIR';

export type AgvState =
  | 'IDLE'
  | 'TO_PICKUP'
  | 'LOADING'
  | 'TO_DROP'
  | 'UNLOADING'
  | 'TO_CHARGE'
  | 'CHARGING'
  | 'OFFLINE';

export type Severity = 'info' | 'success' | 'warning' | 'error';

export type DispatchRule = 'FIFO' | 'PRIORITY' | 'EDD' | 'SPT';

export type OrderPriority = 'normal' | 'hoch' | 'eilig';

export type OrderStatus = 'GEPLANT' | 'AKTIV' | 'VERZUG' | 'BLOCKIERT' | 'ABGESCHLOSSEN' | 'STORNIERT';

export interface RoutingStep {
  process: ProcessType;
  /** Nominal processing time in seconds at 100 % override. */
  baseTime: number;
  /** Machine used by the static route plan (when dynamic rescheduling is disabled). */
  preferred: string;
}

export interface ProductDef {
  id: ProductId;
  name: string;
  shortName: string;
  color: string;
  routing: RoutingStep[];
}

export interface FaultType {
  id: string;
  label: string;
  /** Repair duration in seconds. */
  repairTime: number;
  /** True if the workpiece inside the machine is destroyed. */
  scrapsWorkpiece: boolean;
  kinds: (MachineKind | 'conveyor' | 'agv')[];
}

export type MachineInput = { type: 'buffer'; capacity: number } | { type: 'conveyor'; conveyorId: string };
export type MachineOutput =
  | { type: 'buffer'; capacity: number }
  | { type: 'conveyor'; conveyorId: string; at: number };

export interface MachineConfig {
  /** Feed / speed override in percent (50 to 130). */
  override: number;
  /** Coolant supply (CNC only). */
  coolant: boolean;
  /** Temperature at which a warning is raised. */
  tempWarn: number;
  /** Temperature at which the machine trips (fault). */
  tempTrip: number;
}

export interface MachineSensors {
  temperature: number;
  vibration: number;
  power: number; // kW
  spindle: number; // rpm (CNC) / joint speed % (robot) / scanner rate (qc)
}

export interface MachineJob {
  wpId: string;
  progress: number; // seconds of work done
  duration: number; // seconds needed at current override
  finished: boolean;
}

export interface StateTimes {
  IDLE: number;
  SETUP: number;
  RUNNING: number;
  BLOCKED: number;
  STOPPED: number;
  FAULT: number;
  REPAIR: number;
  MAINTENANCE: number;
}

export interface Machine {
  id: string;
  name: string;
  kind: MachineKind;
  model: string;
  capabilities: ProcessType[];
  position: Vec2;
  /** Yaw rotation in radians for the 3D model. */
  rotation: number;
  dockNode: string | null;
  input: MachineInput;
  output: MachineOutput;
  status: MachineStatus;
  /** Status to return to after repair / maintenance. */
  resumeStopped: boolean;
  inBuffer: string[];
  outBuffer: string[];
  reservedIn: number;
  job: MachineJob | null;
  lastProduct: ProductId | null;
  setupRemaining: number;
  repairRemaining: number;
  repairDelay: number;
  maintenanceRemaining: number;
  fault: { typeId: string; label: string; since: number } | null;
  wear: number; // 0..1
  config: MachineConfig;
  sensors: MachineSensors;
  stats: {
    partsGood: number;
    partsScrap: number;
    faults: number;
    energyKWh: number;
    times: StateTimes;
    utilEma: number;
    statusSince: number;
  };
  /** Visual-only: last QC verdict for the signal light. */
  lastVerdict: { ok: boolean; at: number } | null;
}

export interface ConveyorItem {
  wpId: string;
  s: number;
}

export interface Conveyor {
  id: string;
  name: string;
  start: Vec2;
  end: Vec2;
  length: number;
  /** Belt speed in m/s (configurable). */
  speed: number;
  spacing: number;
  status: ConveyorStatus;
  items: ConveyorItem[];
  reserved: number;
  /** Visual belt offset (m). */
  beltOffset: number;
  dockNode: string | null;
  repairRemaining: number;
  fault: { typeId: string; label: string; since: number } | null;
  stats: { transported: number; energyKWh: number; runTime: number };
  power: number;
}

export type Location =
  | { type: 'raw' }
  | { type: 'inBuffer'; machineId: string }
  | { type: 'outBuffer'; machineId: string }
  | { type: 'machine'; machineId: string }
  | { type: 'agv'; agvId: string }
  | { type: 'conveyor'; conveyorId: string }
  | { type: 'finished' }
  | { type: 'scrap' };

export interface WorkpieceVisit {
  machineId: string;
  process: ProcessType;
  start: number;
  end: number | null;
}

export interface Workpiece {
  id: string;
  orderId: string;
  product: ProductId;
  /** Index of the next routing step to perform (== routing.length when all steps done). */
  step: number;
  location: Location;
  createdAt: number;
  finishedAt: number | null;
  defectRisk: number;
  riskSources: string[];
  visits: WorkpieceVisit[];
  /** Destination already decided (reserved) while being transported. */
  target: TransportTarget | null;
  scrapped: boolean;
}

export type TransportTarget = { type: 'machine'; machineId: string } | { type: 'finished' };

export interface TransportTask {
  id: string;
  wpId: string;
  fromNode: string;
  toNode: string;
  target: TransportTarget;
  createdAt: number;
}

export interface Agv {
  id: string;
  name: string;
  pos: Vec2;
  heading: number;
  speed: number;
  maxSpeed: number;
  state: AgvState;
  enabled: boolean;
  battery: number;
  task: TransportTask | null;
  load: string | null;
  route: string[]; // node ids remaining (first element = next node to reach)
  routeTarget: string | null;
  currentNode: string;
  timer: number;
  chargerId: string | null;
  blockedBy: string | null;
  blockedTime: number;
  forcedCharge: boolean;
  /** Retries while waiting for a part at a pickup point. */
  waitCount?: number;
  fault: { typeId: string; label: string; since: number } | null;
  repairRemaining: number;
  stats: { distance: number; tasks: number; energyKWh: number; busyEma: number };
  power: number;
}

export interface Charger {
  id: string;
  node: string;
  occupiedBy: string | null;
}

export interface Order {
  id: string;
  product: ProductId;
  quantity: number;
  priority: OrderPriority;
  createdAt: number;
  dueAt: number;
  released: number;
  completed: number;
  scrapped: number;
  inProgress: number;
  status: OrderStatus;
  startedAt: number | null;
  completedAt: number | null;
  shippedAt: number | null;
  source: 'local' | 'smartfactory' | 'operator';
}

export interface SimEvent {
  id: number;
  time: number;
  severity: Severity;
  source: string;
  message: string;
}

export interface Alarm {
  key: string;
  severity: 'warning' | 'error';
  source: string;
  message: string;
  since: number;
  acknowledged: boolean;
}

export interface Rules {
  /** Dynamic rescheduling to alternative machines on failures. */
  dynamicRouting: boolean;
  dispatchRule: DispatchRule;
  /** Random failures based on MTBF, wear and temperature. */
  randomFaults: boolean;
  /** Automatic maintenance when wear exceeds the threshold. */
  autoMaintenance: boolean;
  maintenanceThreshold: number;
  /** Maintenance crew starts repairs automatically. */
  autoRepair: boolean;
  /** CONWIP limit for order release. */
  wipLimit: number;
  /** Automatic incoming orders. */
  autoOrders: boolean;
  /** Automatic raw material reordering. */
  autoReplenish: boolean;
}

export interface HistorySeries {
  t: number[];
  throughput: number[];
  power: number[];
  avgTemp: number[];
  wip: number[];
  utilization: number[];
  oee: number[];
  machineTemp: Record<string, number[]>;
  machinePower: Record<string, number[]>;
}

export interface Inventory {
  raw: number;
  rawCapacity: number;
  reorderPoint: number;
  reorderQty: number;
  pendingDelivery: { qty: number; arrivesAt: number } | null;
  finished: Record<ProductId, number>;
  finishedCapacity: number;
  scrap: number;
  shipped: number;
}

export interface Metrics {
  completedTimes: number[]; // sim times of good completions (rolling)
  totalGood: number;
  totalScrap: number;
  energyKWh: number;
  powerKW: number;
  leadTimes: number[];
}

export interface SimState {
  seed: number;
  time: number;
  running: boolean;
  speed: number;
  machines: Machine[];
  conveyors: Conveyor[];
  agvs: Agv[];
  chargers: Charger[];
  workpieces: Map<string, Workpiece>;
  rawQueue: string[];
  orders: Order[];
  tasks: TransportTask[];
  events: SimEvent[];
  eventSeq: number;
  alarms: Alarm[];
  rules: Rules;
  inventory: Inventory;
  metrics: Metrics;
  history: HistorySeries;
  counters: { wp: number; order: number; task: number };
  nextOrderAt: number;
  nextHistoryAt: number;
  nextDispatchAt: number;
  pendingShipments: { orderId: string; at: number }[];
}

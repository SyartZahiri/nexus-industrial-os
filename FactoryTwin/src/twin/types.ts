/**
 * The shapes the 3D scene and the panels read. Drawing data (positions, names, products) is fixed here; the state
 * comes from the Java core's frame through toScene (sceneModel.ts).
 */
import type { AgvStatus, MachineStatus } from '../nexus/types';

export type { AgvStatus, MachineStatus };
/** The scene's old name for an AGV's state. */
export type AgvState = AgvStatus;

export type Vec2 = { x: number; z: number };

export type ProcessType = 'DREHEN' | 'FRAESEN' | 'MONTAGE' | 'PRUEFEN';
export type ProductId = 'GH200' | 'AW50' | 'FP12' | 'MT7';
export type MachineKind = 'cnc' | 'robot' | 'qc';
export type ConveyorStatus = 'RUNNING' | 'STOPPED' | 'FAULT' | 'REPAIR';

export interface RoutingStep {
  process: ProcessType;
  baseTime: number;
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
  repairTime: number;
  scrapsWorkpiece: boolean;
  kinds: (MachineKind | 'conveyor' | 'agv')[];
}

export type MachineInput = { type: 'buffer'; capacity: number } | { type: 'conveyor'; conveyorId: string };
export type MachineOutput =
  | { type: 'buffer'; capacity: number }
  | { type: 'conveyor'; conveyorId: string; at: number };

export interface MachineConfig {
  override: number;
  coolant: boolean;
  tempWarn: number;
  tempTrip: number;
}

export interface MachineSensors {
  temperature: number;
  vibration: number;
  power: number;
  spindle: number;
}

export interface Fault {
  typeId: string;
  label: string;
  since: number;
}

export interface MachineDef {
  id: string;
  name: string;
  kind: MachineKind;
  model: string;
  capabilities: ProcessType[];
  position: Vec2;
  rotation: number;
  dockNode: string | null;
  input: MachineInput;
  output: MachineOutput;
}

/** progress and duration share one unit: progress runs from 0 to duration (here 1). */
export interface SceneJob {
  wpId: string;
  progress: number;
  duration: number;
  finished: boolean;
}

export interface SceneMachine extends MachineDef {
  status: MachineStatus;
  job: SceneJob | null;
  sensors: MachineSensors;
  wear: number;
  fault: Fault | null;
  config: MachineConfig;
  inBuffer: string[];
  outBuffer: string[];
  lastVerdict: { ok: boolean; at: number } | null;
}

export interface ConveyorItem {
  wpId: string;
  s: number;
}

export interface SceneConveyor {
  id: string;
  name: string;
  start: Vec2;
  end: Vec2;
  length: number;
  speed: number;
  status: ConveyorStatus;
  items: ConveyorItem[];
  beltOffset: number;
  dockNode: string | null;
}

export interface SceneAgv {
  id: string;
  name: string;
  pos: Vec2;
  heading: number;
  speed: number;
  state: AgvState;
  battery: number;
  load: string | null;
  route: string[];
  transportId: string | null;
  fault: Fault | null;
  blockedBy: string | null;
}

export interface SceneCharger {
  id: string;
  node: string;
  occupiedBy: string | null;
}

export interface SceneInventory {
  raw: number;
  rawCapacity: number;
  finished: Record<ProductId, number>;
  finishedCapacity: number;
  scrap: number;
}

export interface Workpiece {
  product: ProductId;
  orderId: string;
  step: number;
}

export interface SceneState {
  time: number;
  machines: SceneMachine[];
  conveyors: SceneConveyor[];
  agvs: SceneAgv[];
  chargers: SceneCharger[];
  inventory: SceneInventory;
  workpieces: Map<string, Workpiece>;
  rawQueue: string[];
}

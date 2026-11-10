import { AGV_PHYSICS, AMBIENT_TEMP, DEFAULT_MACHINE_CONFIG, DEFAULT_RULES, HISTORY_INTERVAL } from './config';
import { nodePos } from './layout';
import type { Rng } from './rng';
import type {
  Agv,
  Charger,
  Conveyor,
  HistorySeries,
  Machine,
  MachineInput,
  MachineKind,
  MachineOutput,
  ProcessType,
  SimState,
  StateTimes,
  Vec2,
} from './types';

const emptyTimes = (): StateTimes => ({
  IDLE: 0,
  SETUP: 0,
  RUNNING: 0,
  BLOCKED: 0,
  STOPPED: 0,
  FAULT: 0,
  REPAIR: 0,
  MAINTENANCE: 0,
});

interface MachineDef {
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

export const MACHINE_DEFS: MachineDef[] = [
  {
    id: 'CNC-01',
    name: 'Fräszentrum 1',
    kind: 'cnc',
    model: 'VMC-850 · 3-Achs-Vertikal',
    capabilities: ['FRAESEN'],
    position: { x: -12, z: -10 },
    rotation: 0,
    dockNode: 'D_CNC01',
    input: { type: 'buffer', capacity: 3 },
    output: { type: 'buffer', capacity: 3 },
  },
  {
    id: 'CNC-02',
    name: 'Fräszentrum 2',
    kind: 'cnc',
    model: 'VMC-850 · 3-Achs-Vertikal',
    capabilities: ['FRAESEN'],
    position: { x: -4, z: -10 },
    rotation: 0,
    dockNode: 'D_CNC02',
    input: { type: 'buffer', capacity: 3 },
    output: { type: 'buffer', capacity: 3 },
  },
  {
    id: 'CNC-03',
    name: 'Drehzentrum',
    kind: 'cnc',
    model: 'TC-400 · CNC-Drehmaschine',
    capabilities: ['DREHEN'],
    position: { x: 4, z: -10 },
    rotation: 0,
    dockNode: 'D_CNC03',
    input: { type: 'buffer', capacity: 3 },
    output: { type: 'buffer', capacity: 3 },
  },
  {
    id: 'CNC-04',
    name: '5-Achs-Zentrum',
    kind: 'cnc',
    model: 'UMC-500 · 5-Achs-Universal',
    capabilities: ['DREHEN', 'FRAESEN'],
    position: { x: 12, z: -10 },
    rotation: 0,
    dockNode: 'D_CNC04',
    input: { type: 'buffer', capacity: 3 },
    output: { type: 'buffer', capacity: 3 },
  },
  {
    id: 'ROB-01',
    name: 'Montagezelle 1',
    kind: 'robot',
    model: 'Knickarm 6-Achs · 12 kg',
    capabilities: ['MONTAGE'],
    position: { x: -7.3, z: 10.9 },
    rotation: 0,
    dockNode: null,
    input: { type: 'conveyor', conveyorId: 'CV-01' },
    output: { type: 'conveyor', conveyorId: 'CV-03', at: 6.7 },
  },
  {
    id: 'ROB-02',
    name: 'Montagezelle 2',
    kind: 'robot',
    model: 'Knickarm 6-Achs · 12 kg',
    capabilities: ['MONTAGE'],
    position: { x: 1.7, z: 10.9 },
    rotation: 0,
    dockNode: null,
    input: { type: 'conveyor', conveyorId: 'CV-02' },
    output: { type: 'conveyor', conveyorId: 'CV-03', at: 15.7 },
  },
  {
    id: 'QC-01',
    name: 'Qualitätsprüfung',
    kind: 'qc',
    model: 'Vision- & Laser-Messzelle',
    capabilities: ['PRUEFEN'],
    position: { x: 14.8, z: 12.6 },
    rotation: 0,
    dockNode: null,
    input: { type: 'conveyor', conveyorId: 'CV-03' },
    output: { type: 'conveyor', conveyorId: 'CV-04', at: 0 },
  },
];

const CONVEYOR_DEFS: { id: string; name: string; start: Vec2; end: Vec2; speed: number; dockNode: string | null }[] = [
  { id: 'CV-01', name: 'Zuführband Zelle 1', start: { x: -9, z: 6.2 }, end: { x: -9, z: 10.2 }, speed: 0.4, dockNode: 'D_CV01' },
  { id: 'CV-02', name: 'Zuführband Zelle 2', start: { x: 0, z: 6.2 }, end: { x: 0, z: 10.2 }, speed: 0.4, dockNode: 'D_CV02' },
  { id: 'CV-03', name: 'Sammelband Prüflinie', start: { x: -14, z: 12.6 }, end: { x: 13, z: 12.6 }, speed: 0.6, dockNode: 'D_CV03' },
  { id: 'CV-04', name: 'Auslaufband', start: { x: 17.3, z: 12.6 }, end: { x: 17.3, z: 6.2 }, speed: 0.4, dockNode: 'D_CV04' },
];

export const CHARGER_NODES: { id: string; node: string }[] = [
  { id: 'CH-1', node: 'D_CH1' },
  { id: 'CH-2', node: 'D_CH2' },
  { id: 'CH-3', node: 'D_CH3' },
  { id: 'CH-4', node: 'D_CH4' },
  { id: 'CH-5', node: 'D_CH5' },
  { id: 'CH-6', node: 'D_CH6' },
];

function emptyHistory(): HistorySeries {
  const machineTemp: Record<string, number[]> = {};
  const machinePower: Record<string, number[]> = {};
  for (const m of MACHINE_DEFS) {
    machineTemp[m.id] = [];
    machinePower[m.id] = [];
  }
  return { t: [], throughput: [], power: [], avgTemp: [], wip: [], utilization: [], oee: [], machineTemp, machinePower };
}

export function createInitialState(seed: number, rng: Rng): SimState {
  const machines: Machine[] = MACHINE_DEFS.map((d) => ({
    ...d,
    status: 'IDLE',
    resumeStopped: false,
    inBuffer: [],
    outBuffer: [],
    reservedIn: 0,
    job: null,
    lastProduct: null,
    setupRemaining: 0,
    repairRemaining: 0,
    repairDelay: 0,
    maintenanceRemaining: 0,
    fault: null,
    wear: Math.round(rng.range(0.08, 0.45) * 100) / 100,
    config: { ...DEFAULT_MACHINE_CONFIG, coolant: d.kind === 'cnc' },
    sensors: { temperature: AMBIENT_TEMP + rng.range(1, 4), vibration: 0.2, power: 0, spindle: 0 },
    stats: { partsGood: 0, partsScrap: 0, faults: 0, energyKWh: 0, times: emptyTimes(), utilEma: 0, statusSince: 0 },
    lastVerdict: null,
  }));

  const conveyors: Conveyor[] = CONVEYOR_DEFS.map((c) => ({
    ...c,
    length: Math.hypot(c.end.x - c.start.x, c.end.z - c.start.z),
    spacing: 0.85,
    status: 'RUNNING',
    items: [],
    reserved: 0,
    beltOffset: 0,
    repairRemaining: 0,
    fault: null,
    stats: { transported: 0, energyKWh: 0, runTime: 0 },
    power: 0,
  }));

  const chargers: Charger[] = CHARGER_NODES.map((c) => ({ id: c.id, node: c.node, occupiedBy: null }));
  const startChargers = ['CH-1', 'CH-2', 'CH-3', 'CH-4', 'CH-5', 'CH-6'];
  const agvs: Agv[] = startChargers.map((chId, i) => {
    const ch = chargers.find((c) => c.id === chId)!;
    ch.occupiedBy = `AGV-0${i + 1}`;
    const p = nodePos(ch.node);
    return {
      id: `AGV-0${i + 1}`,
      name: `Transportroboter ${i + 1}`,
      pos: { ...p },
      heading: ch.node === 'D_CH1' || ch.node === 'D_CH2' ? Math.PI / 2 : ch.node === 'D_CH5' || ch.node === 'D_CH6' ? 0 : -Math.PI / 2,
      speed: 0,
      maxSpeed: AGV_PHYSICS.maxSpeed,
      state: 'CHARGING',
      enabled: true,
      battery: Math.round(rng.range(55, 95)),
      task: null,
      load: null,
      route: [],
      routeTarget: null,
      currentNode: ch.node,
      timer: 0,
      chargerId: ch.id,
      blockedBy: null,
      blockedTime: 0,
      forcedCharge: false,
      fault: null,
      repairRemaining: 0,
      stats: { distance: 0, tasks: 0, energyKWh: 0, busyEma: 0 },
      power: 0,
    };
  });

  return {
    seed,
    time: 0,
    running: true,
    speed: 5,
    machines,
    conveyors,
    agvs,
    chargers,
    workpieces: new Map(),
    rawQueue: [],
    orders: [],
    tasks: [],
    events: [],
    eventSeq: 0,
    alarms: [],
    rules: { ...DEFAULT_RULES },
    inventory: {
      raw: 70,
      rawCapacity: 120,
      reorderPoint: 24,
      reorderQty: 60,
      pendingDelivery: null,
      finished: { GH200: 6, AW50: 4, FP12: 8, MT7: 3 },
      finishedCapacity: 160,
      scrap: 0,
      shipped: 0,
    },
    metrics: { completedTimes: [], totalGood: 0, totalScrap: 0, energyKWh: 0, powerKW: 0, leadTimes: [] },
    history: emptyHistory(),
    counters: { wp: 0, order: 0, task: 0 },
    nextOrderAt: 0,
    nextHistoryAt: HISTORY_INTERVAL,
    nextDispatchAt: 0,
    pendingShipments: [],
  };
}

import type { FaultType, MachineConfig, ProcessType, ProductDef, ProductId, Rules } from './types';

export const PRODUCTS: Record<ProductId, ProductDef> = {
  GH200: {
    id: 'GH200',
    name: 'Getriebegehäuse GH-200',
    shortName: 'GH-200',
    color: '#3987e5',
    routing: [
      { process: 'FRAESEN', baseTime: 85, preferred: 'CNC-01' },
      { process: 'MONTAGE', baseTime: 65, preferred: 'ROB-01' },
      { process: 'PRUEFEN', baseTime: 28, preferred: 'QC-01' },
    ],
  },
  AW50: {
    id: 'AW50',
    name: 'Antriebswelle AW-50',
    shortName: 'AW-50',
    color: '#d95926',
    routing: [
      { process: 'DREHEN', baseTime: 70, preferred: 'CNC-03' },
      { process: 'FRAESEN', baseTime: 45, preferred: 'CNC-02' },
      { process: 'PRUEFEN', baseTime: 22, preferred: 'QC-01' },
    ],
  },
  FP12: {
    id: 'FP12',
    name: 'Flanschplatte FP-12',
    shortName: 'FP-12',
    color: '#199e70',
    routing: [
      { process: 'FRAESEN', baseTime: 55, preferred: 'CNC-02' },
      { process: 'PRUEFEN', baseTime: 18, preferred: 'QC-01' },
    ],
  },
  MT7: {
    id: 'MT7',
    name: 'Motorträger MT-7',
    shortName: 'MT-7',
    color: '#c98500',
    routing: [
      { process: 'DREHEN', baseTime: 60, preferred: 'CNC-04' },
      { process: 'MONTAGE', baseTime: 80, preferred: 'ROB-02' },
      { process: 'PRUEFEN', baseTime: 30, preferred: 'QC-01' },
    ],
  },
};

export const PRODUCT_IDS = Object.keys(PRODUCTS) as ProductId[];

export const PROCESS_LABEL: Record<ProcessType, string> = {
  DREHEN: 'Drehen',
  FRAESEN: 'Fräsen',
  MONTAGE: 'Montage',
  PRUEFEN: 'Prüfen',
};

export const FAULT_TYPES: FaultType[] = [
  { id: 'OVERTEMP', label: 'Spindel-Übertemperatur', repairTime: 240, scrapsWorkpiece: false, kinds: ['cnc'] },
  { id: 'TOOL', label: 'Werkzeugbruch', repairTime: 150, scrapsWorkpiece: true, kinds: ['cnc'] },
  { id: 'SERVO', label: 'Achsantrieb gestört (Servo)', repairTime: 300, scrapsWorkpiece: false, kinds: ['cnc', 'robot'] },
  { id: 'GRIPPER', label: 'Greiferfehler', repairTime: 120, scrapsWorkpiece: false, kinds: ['robot'] },
  { id: 'COLLISION', label: 'Kollisionserkennung', repairTime: 200, scrapsWorkpiece: true, kinds: ['robot'] },
  { id: 'VISION', label: 'Sensorstörung Kamera', repairTime: 90, scrapsWorkpiece: false, kinds: ['qc'] },
  { id: 'COMM', label: 'Kommunikationsfehler (Feldbus)', repairTime: 60, scrapsWorkpiece: false, kinds: ['cnc', 'robot', 'qc', 'conveyor', 'agv'] },
  { id: 'ESTOP', label: 'Not-Halt ausgelöst', repairTime: 45, scrapsWorkpiece: false, kinds: ['cnc', 'robot', 'qc', 'conveyor'] },
  { id: 'BELT', label: 'Bandantrieb blockiert', repairTime: 180, scrapsWorkpiece: false, kinds: ['conveyor'] },
  { id: 'NAV', label: 'Navigationsfehler (Lidar)', repairTime: 60, scrapsWorkpiece: false, kinds: ['agv'] },
];

export const DEFAULT_RULES: Rules = {
  dynamicRouting: true,
  dispatchRule: 'PRIORITY',
  randomFaults: true,
  autoMaintenance: true,
  maintenanceThreshold: 0.85,
  autoRepair: true,
  wipLimit: 20,
  autoOrders: true,
  autoReplenish: true,
};

export const DEFAULT_MACHINE_CONFIG: MachineConfig = {
  override: 100,
  coolant: true,
  tempWarn: 68,
  tempTrip: 82,
};

/** Physical constants of the thermal / energy / reliability models per machine kind. */
export const MACHINE_PHYSICS = {
  cnc: { idlePower: 3.2, runPower: 17, heatRun: 34, heatIdle: 3, coolantEffect: 11, mtbf: 3600, lifetime: 5400, setupTime: 30, spindleRpm: 12000 },
  robot: { idlePower: 0.9, runPower: 6.5, heatRun: 18, heatIdle: 2, coolantEffect: 0, mtbf: 5400, lifetime: 7200, setupTime: 15, spindleRpm: 100 },
  qc: { idlePower: 1.4, runPower: 3.2, heatRun: 9, heatIdle: 2, coolantEffect: 0, mtbf: 7200, lifetime: 10800, setupTime: 0, spindleRpm: 60 },
} as const;

export const AMBIENT_TEMP = 22;
export const THERMAL_TAU = 110; // s

export const AGV_PHYSICS = {
  maxSpeed: 2.2,
  accel: 1.0,
  loadTime: 2.2,
  drivePower: 1.1, // kW
  idlePower: 0.08,
  chargePower: 3.2,
  drainPerMeter: 0.022, // % battery per metre
  drainIdle: 0.004, // % per second
  chargeRate: 0.5, // % per second
  lowBattery: 25,
  minForTask: 18,
  resumeFromCharge: 40,
  safetyDistance: 1.75,
};

export const CONVEYOR_POWER_PER_MPS = 1.6; // kW at 1 m/s

export const HISTORY_INTERVAL = 10; // s sim time
export const HISTORY_LENGTH = 180; // samples
export const SHIFT_START_HOUR = 6;

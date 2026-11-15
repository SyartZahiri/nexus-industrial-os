/** Drawing data of the hall: where machines, conveyors and chargers stand. The core holds the same ids. */
import type { MachineDef, Vec2 } from './types';

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

export const CONVEYOR_DEFS: { id: string; name: string; start: Vec2; end: Vec2; speed: number; dockNode: string | null }[] = [
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

/** Capacities the warehouse view draws against (from the former FactoryTwin start state). */
export const INVENTORY_CAPACITY = { raw: 120, finished: 160 };

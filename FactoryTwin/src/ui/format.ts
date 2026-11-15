import { SHIFT_START_HOUR } from '../twin/config';
import type { AgvState, ConveyorStatus, MachineStatus } from '../twin/types';

/**
 * The state colours of Spec 14 (runs, waits, fault, maintenance …), the only colours that carry meaning, and their
 * only definition. Hex values, because the 3D scene hands them to Three.js; the interface uses them as inline styles.
 */
export const STATE_HEX = {
  run: '#22c55e',
  idle: '#94a3b8',
  setup: '#38bdf8',
  wait: '#f59e0b',
  fault: '#ef4444',
  repair: '#fb923c',
  maint: '#a78bfa',
  unknown: '#6b7280',
} as const;

export const STATUS_COLOR: Record<MachineStatus, string> = {
  RUNNING: STATE_HEX.run,
  IDLE: STATE_HEX.idle,
  SETUP: STATE_HEX.setup,
  BLOCKED: STATE_HEX.wait,
  STOPPED: STATE_HEX.idle,
  FAULT: STATE_HEX.fault,
  REPAIR: STATE_HEX.repair,
  MAINTENANCE: STATE_HEX.maint,
  UNKNOWN: STATE_HEX.unknown,
};

export const STATUS_LABEL: Record<MachineStatus, string> = {
  RUNNING: 'In Produktion',
  IDLE: 'Bereit · wartet auf Material',
  SETUP: 'Rüsten',
  BLOCKED: 'Blockiert · Ausgang belegt',
  STOPPED: 'Gestoppt',
  FAULT: 'Störung',
  REPAIR: 'Instandsetzung',
  MAINTENANCE: 'Wartung',
  UNKNOWN: 'Nicht erreichbar',
};

export const STATUS_SHORT: Record<MachineStatus, string> = {
  RUNNING: 'Läuft',
  IDLE: 'Bereit',
  SETUP: 'Rüsten',
  BLOCKED: 'Blockiert',
  STOPPED: 'Gestoppt',
  FAULT: 'Störung',
  REPAIR: 'Reparatur',
  MAINTENANCE: 'Wartung',
  UNKNOWN: 'Unbekannt',
};

export const CONVEYOR_COLOR: Record<ConveyorStatus, string> = {
  RUNNING: STATE_HEX.run,
  STOPPED: STATE_HEX.idle,
  FAULT: STATE_HEX.fault,
  REPAIR: STATE_HEX.repair,
};

export const CONVEYOR_LABEL: Record<ConveyorStatus, string> = {
  RUNNING: 'Läuft',
  STOPPED: 'Gestoppt',
  FAULT: 'Störung',
  REPAIR: 'Reparatur',
};

/** Driving and handling are one state ("unterwegs"); charging counts as running, the AGV is doing its job. */
export const AGV_COLOR: Record<AgvState, string> = {
  IDLE: STATE_HEX.idle,
  TO_PICKUP: STATE_HEX.setup,
  LOADING: STATE_HEX.setup,
  TO_DROP: STATE_HEX.setup,
  UNLOADING: STATE_HEX.setup,
  TO_CHARGE: STATE_HEX.setup,
  CHARGING: STATE_HEX.run,
  OFFLINE: STATE_HEX.unknown,
  FAULT: STATE_HEX.fault,
};

export const AGV_LABEL: Record<AgvState, string> = {
  IDLE: 'Bereit',
  TO_PICKUP: 'Fährt zur Abholung',
  LOADING: 'Lädt auf',
  TO_DROP: 'Transportiert',
  UNLOADING: 'Lädt ab',
  TO_CHARGE: 'Fährt zur Ladestation',
  CHARGING: 'Lädt Akku',
  OFFLINE: 'Außer Betrieb',
  FAULT: 'Störung',
};

export type EventSeverity = 'INFO' | 'SUCCESS' | 'WARNING' | 'ERROR';

export const SEVERITY_COLOR: Record<EventSeverity, string> = {
  INFO: STATE_HEX.idle,
  SUCCESS: STATE_HEX.run,
  WARNING: STATE_HEX.wait,
  ERROR: STATE_HEX.fault,
};

const pad = (n: number) => String(Math.floor(n)).padStart(2, '0');

export function clock(t: number, seconds = true): string {
  const total = SHIFT_START_HOUR * 3600 + t;
  const h = Math.floor(total / 3600) % 24;
  const m = Math.floor(total / 60) % 60;
  const s = Math.floor(total) % 60;
  return seconds ? `${pad(h)}:${pad(m)}:${pad(s)}` : `${pad(h)}:${pad(m)}`;
}

export function dayOf(t: number): number {
  return Math.floor((SHIFT_START_HOUR * 3600 + t) / 86400) + 1;
}

export function shiftOf(t: number): string {
  const h = (Math.floor((SHIFT_START_HOUR * 3600 + t) / 3600) % 24 + 24) % 24;
  if (h >= 6 && h < 14) return 'Frühschicht';
  if (h >= 14 && h < 22) return 'Spätschicht';
  return 'Nachtschicht';
}

export function duration(sec: number): string {
  const s = Math.max(0, Math.round(sec));
  if (s < 60) return `${s} s`;
  const m = Math.floor(s / 60);
  if (m < 60) return `${m} min ${pad(s % 60)} s`;
  return `${Math.floor(m / 60)} h ${pad(m % 60)} min`;
}

export function compactDuration(sec: number): string {
  const s = Math.max(0, Math.round(sec));
  if (s < 60) return `${s}s`;
  const m = Math.floor(s / 60);
  if (m < 60) return `${m}m`;
  return `${Math.floor(m / 60)}h ${pad(m % 60)}m`;
}

export const nf = (v: number, digits = 0) =>
  v.toLocaleString('de-DE', { minimumFractionDigits: digits, maximumFractionDigits: digits });

export const pct = (v: number, digits = 0) => `${nf(v * 100, digits)} %`;

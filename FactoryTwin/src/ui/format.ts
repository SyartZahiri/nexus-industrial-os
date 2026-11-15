import { SHIFT_START_HOUR } from '../twin/config';
import type { AgvState, ConveyorStatus, MachineStatus } from '../twin/types';

export const STATUS_COLOR: Record<MachineStatus, string> = {
  RUNNING: '#22c55e',
  IDLE: '#64748b',
  SETUP: '#38bdf8',
  BLOCKED: '#f59e0b',
  STOPPED: '#94a3b8',
  FAULT: '#ef4444',
  REPAIR: '#fb923c',
  MAINTENANCE: '#a78bfa',
  UNKNOWN: '#6b7280',
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
  RUNNING: '#22c55e',
  STOPPED: '#94a3b8',
  FAULT: '#ef4444',
  REPAIR: '#fb923c',
};

export const CONVEYOR_LABEL: Record<ConveyorStatus, string> = {
  RUNNING: 'Läuft',
  STOPPED: 'Gestoppt',
  FAULT: 'Störung',
  REPAIR: 'Reparatur',
};

export const AGV_COLOR: Record<AgvState, string> = {
  IDLE: '#cbd5e1',
  TO_PICKUP: '#38bdf8',
  LOADING: '#2dd4bf',
  TO_DROP: '#2dd4bf',
  UNLOADING: '#2dd4bf',
  TO_CHARGE: '#a3e635',
  CHARGING: '#22c55e',
  OFFLINE: '#64748b',
  FAULT: '#ef4444',
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
  INFO: '#64748b',
  SUCCESS: '#22c55e',
  WARNING: '#f59e0b',
  ERROR: '#ef4444',
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

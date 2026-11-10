import { SHIFT_START_HOUR } from '../sim/config';
import type { AgvState, ConveyorStatus, MachineStatus, OrderPriority, OrderStatus, Severity } from '../sim/types';

export const STATUS_COLOR: Record<MachineStatus, string> = {
  RUNNING: '#22c55e',
  IDLE: '#64748b',
  SETUP: '#38bdf8',
  BLOCKED: '#f59e0b',
  STOPPED: '#94a3b8',
  FAULT: '#ef4444',
  REPAIR: '#fb923c',
  MAINTENANCE: '#a78bfa',
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
};

export const ORDER_STATUS_COLOR: Record<OrderStatus, string> = {
  GEPLANT: '#64748b',
  AKTIV: '#38bdf8',
  VERZUG: '#f59e0b',
  BLOCKIERT: '#ef4444',
  ABGESCHLOSSEN: '#22c55e',
  STORNIERT: '#475569',
};

export const ORDER_STATUS_LABEL: Record<OrderStatus, string> = {
  GEPLANT: 'Geplant',
  AKTIV: 'In Arbeit',
  VERZUG: 'Verzug',
  BLOCKIERT: 'Blockiert',
  ABGESCHLOSSEN: 'Fertig',
  STORNIERT: 'Storniert',
};

export const PRIORITY_LABEL: Record<OrderPriority, string> = { normal: 'Normal', hoch: 'Hoch', eilig: 'Eilig' };
export const PRIORITY_COLOR: Record<OrderPriority, string> = { normal: '#64748b', hoch: '#f59e0b', eilig: '#ef4444' };

export const SEVERITY_COLOR: Record<Severity, string> = {
  info: '#64748b',
  success: '#22c55e',
  warning: '#f59e0b',
  error: '#ef4444',
};

export const SEVERITY_LABEL: Record<Severity, string> = {
  info: 'Info',
  success: 'OK',
  warning: 'Warnung',
  error: 'Fehler',
};

const pad = (n: number) => String(Math.floor(n)).padStart(2, '0');

/** Simulation time → shop-floor clock (shift starts 06:00 on day 1). */
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

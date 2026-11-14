import type { NexusEvent } from '../../nexus/types';
import { AGV_LABEL, STATUS_SHORT } from '../format';
import type { AgvState, MachineStatus } from '../../twin/types';

const ORDER_STATUS: Record<string, string> = {
  CREATED: 'angelegt',
  MATERIAL_PENDING: 'wartet auf Material',
  SHORTAGE: 'Materialmangel',
  RELEASED: 'freigegeben',
  IN_PROGRESS: 'in Arbeit',
  COMPLETED: 'abgeschlossen',
  CANCELLED: 'storniert',
};

const TRANSPORT_STATE: Record<string, string> = {
  CREATED: 'angelegt',
  ASSIGNED: 'zugeteilt',
  TO_PICKUP: 'fährt zur Abholung',
  LOADING: 'lädt',
  IN_TRANSIT: 'unterwegs',
  UNLOADING: 'entlädt',
  DELIVERED: 'zugestellt',
  FAILED: 'unterbrochen',
  CANCELLED: 'storniert',
};

const TRANSPORT_KIND: Record<string, string> = { SUPPLY: 'Material', WIP: 'Zwischenteile', PUTAWAY: 'Fertigteile' };

/** A machine state as a word; UNKNOWN reads "Nicht erreichbar" in sentences, as the gateway means it. */
function machineState(v: unknown): string {
  if (v === 'UNKNOWN') return 'Nicht erreichbar';
  return STATUS_SHORT[v as MachineStatus] ?? String(v ?? '');
}

/** A place in an event payload ({ kind, id }) as plain text. */
function place(p: unknown): string {
  return p && typeof p === 'object' && 'id' in p ? String((p as { id: unknown }).id) : '?';
}

/** A short German description of an event, for the event log and the event monitor. */
export function eventText(e: NexusEvent): string {
  const p = e.payload as Record<string, unknown>;
  const s = (k: string) => String(p[k] ?? '');
  switch (e.type) {
    case 'order.created':
      return `Auftrag ${s('orderId')} angelegt: ${s('quantity')} × ${s('productId')}`;
    case 'order.material_pending':
    case 'order.shortage':
    case 'order.released':
    case 'order.started':
    case 'order.completed':
    case 'order.cancelled':
      return `Auftrag ${s('orderId')} ${ORDER_STATUS[s('to')] ?? s('to')}`;
    case 'order.rescheduled':
      return `Auftrag ${s('orderId')}, Schritt ${s('stepSeq')}: ${s('fromMachineId')} → ${s('toMachineId')}`;
    case 'operation.started':
      return `${s('machineId')}: Schritt ${s('stepSeq')} von ${s('orderId')} gestartet`;
    case 'operation.completed':
      return `${s('machineId')}: Schritt ${s('stepSeq')} von ${s('orderId')} fertig`;
    case 'machine.status':
      return `${s('machineId')}: ${machineState(p.from)} → ${machineState(p.to)}`;
    case 'machine.fault':
      return `${s('machineId')}: Störung – ${s('label')}${p.scrappedWorkpiece ? ' (Teil verloren)' : ''}`;
    case 'material.shortage':
      return `Materialmangel ${s('materialId')}: ${s('requested')} benötigt, ${s('available')} verfügbar`;
    case 'stock.changed':
      return `Bestand ${s('materialId')} @ ${s('locationId')}: ${s('onHand')} (reserviert ${s('reserved')})`;
    case 'transport.created':
      return `Transport ${s('transportId')}: ${TRANSPORT_KIND[s('kind')] ?? s('kind')} ${place(p.from)} → ${place(p.to)}`;
    case 'transport.assigned':
      return `Transport ${s('transportId')}: ${s('agvId')} zugeteilt`;
    case 'transport.state':
      return `Transport ${s('transportId')}: ${TRANSPORT_STATE[s('to')] ?? s('to')}${p.agvId ? ` (${s('agvId')})` : ''}`;
    case 'transport.delivered':
      return `Transport ${s('transportId')} zugestellt an ${place(p.to)}`;
    case 'transport.failed':
      return `Transport ${s('transportId')} unterbrochen${p.agvId ? ` (${s('agvId')})` : ''}`;
    case 'agv.state':
      return `${s('agvId')}: ${AGV_LABEL[p.to as AgvState] ?? s('to')}`;
    case 'agv.fault':
      return `${s('agvId')}: Störung – ${s('label')}`;
    case 'opcua.connection':
    case 'scenario.started':
    case 'scenario.finished':
      return s('message');
    case 'decision':
      return `${s('subject')}: ${s('reason')}`;
    default:
      return e.type;
  }
}

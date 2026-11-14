import { describe, expect, it } from 'vitest';
import { eventText } from './eventText';
import type { NexusEvent } from '../../nexus/types';

const e = (type: string, payload: Record<string, unknown>): NexusEvent => ({
  seq: 1,
  eventId: '1-1',
  simTimeMs: 0,
  type,
  source: 'x',
  severity: 'INFO',
  correlationId: null,
  payload,
});

describe('eventText', () => {
  it('describes the common events in German', () => {
    expect(eventText(e('machine.status', { machineId: 'CNC-01', from: 'IDLE', to: 'UNKNOWN' }))).toBe(
      'CNC-01: Bereit → Nicht erreichbar',
    );
    expect(eventText(e('order.created', { orderId: 'PO-0001', productId: 'FP12', quantity: 3 }))).toBe(
      'Auftrag PO-0001 angelegt: 3 × FP12',
    );
    expect(eventText(e('opcua.connection', { message: 'OPC UA getrennt, Maschinen gelten als unbekannt' }))).toBe(
      'OPC UA getrennt, Maschinen gelten als unbekannt',
    );
    expect(eventText(e('decision', { subject: 'AGV-03', chosen: 'TR-0004', reason: 'kürzeste Anfahrt' }))).toBe(
      'AGV-03: kürzeste Anfahrt',
    );
  });

  it('describes transport progress, the most frequent events', () => {
    expect(eventText(e('transport.state', { transportId: 'TR-0003', from: 'LOADING', to: 'IN_TRANSIT', agvId: 'AGV-02' })))
      .toBe('Transport TR-0003: unterwegs (AGV-02)');
    expect(eventText(e('transport.assigned', { transportId: 'TR-0003', from: 'CREATED', to: 'ASSIGNED', agvId: 'AGV-02' })))
      .toBe('Transport TR-0003: AGV-02 zugeteilt');
  });

  it('falls back to the type for events it does not know', () => {
    expect(eventText(e('route.replanned', {}))).toBe('route.replanned');
  });
});

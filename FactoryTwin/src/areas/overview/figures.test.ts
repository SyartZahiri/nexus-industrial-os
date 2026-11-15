import { describe, expect, it } from 'vitest';
import * as core from '@nexus-core';
import { overviewFigures } from './figures';
import type { Frame, Kpis, NexusEvent } from '../../nexus/types';

const frameAfter = (minutes: number): Frame => {
  core.plantStart(7);
  core.plantAdvance(minutes * 60_000);
  return JSON.parse(core.plantFrame()) as Frame;
};
const kpisAfter = (): Kpis =>
  (JSON.parse(core.plantQuery('{"query":"kpis","windowMinutes":60}')) as { result: Kpis }).result;

describe('overviewFigures', () => {
  it('figuresWithoutFinishedOrdersShowADash', () => {
    const f = overviewFigures(frameAfter(1), kpisAfter(), []); // one minute: no order can be finished yet
    expect(f.throughput).toBe('0');
    expect(f.onTime).toBe('–');
    expect(f.leadTime).toBe('–');
    expect(f.oee).not.toContain('NaN');
    expect(f.bottleneck).toBeNull();
  });

  it('counts open, late and moving work from the frame', () => {
    const frame = frameAfter(90);
    const f = overviewFigures(frame, kpisAfter(), []);
    expect(f.openOrders).toBe(frame.orders.filter((o) => !['COMPLETED', 'CANCELLED'].includes(o.status)).length);
    expect(f.lateOrders).toBe(
      frame.orders.filter((o) => !['COMPLETED', 'CANCELLED'].includes(o.status) && o.dueAtMs < frame.simTimeMs).length,
    );
    expect(f.activeTransports).toBe(frame.transports.length);
    expect(f.machines).toHaveLength(7);
  });

  it('lists the newest errors and warnings first, at most eight', () => {
    const ev = (seq: number, severity: NexusEvent['severity']): NexusEvent => ({
      seq,
      eventId: `1-${seq}`,
      simTimeMs: seq,
      type: 'machine.fault',
      source: 'smartfactory',
      severity,
      correlationId: null,
      payload: {},
    });
    const events = Array.from({ length: 20 }, (_, i) => ev(i + 1, i % 2 ? 'ERROR' : 'INFO'));
    const f = overviewFigures(null, null, events);
    expect(f.alerts.map((e) => e.seq)).toEqual([20, 18, 16, 14, 12, 10, 8, 6]);
  });
});

import { describe, expect, it } from 'vitest';
import { filterEvents, matches } from './eventFilter';
import type { NexusEvent } from './types';

const ev = (
  seq: number,
  type: string,
  correlationId: string | null = null,
  severity: NexusEvent['severity'] = 'INFO',
): NexusEvent => ({
  seq,
  eventId: `1-${seq}`,
  simTimeMs: seq * 100,
  type,
  source: type.split('.')[0],
  severity,
  correlationId,
  payload: {},
});

describe('event filter', () => {
  it('matches a type prefix, an order and a severity', () => {
    expect(matches(ev(1, 'machine.fault'), { type: 'machine.' })).toBe(true);
    expect(matches(ev(1, 'order.created'), { type: 'machine.' })).toBe(false);
    expect(matches(ev(1, 'order.created', 'PO-0004'), { correlationId: 'PO-0004' })).toBe(true);
    expect(matches(ev(1, 'order.created', 'PO-0004'), { correlationId: 'PO-00' })).toBe(false);
    expect(matches(ev(1, 'machine.fault', null, 'ERROR'), { severity: 'ERROR' })).toBe(true);
  });

  it('filterKeepsAtMostAPageAndSaysSo', () => {
    const all = Array.from({ length: 10_000 }, (_, i) => ev(i + 1, i % 2 ? 'machine.status' : 'order.created'));
    const first = filterEvents(all, { type: 'machine.' });
    expect(first.items).toHaveLength(500);
    expect(first.more).toBe(true);
    const next = filterEvents(all, { type: 'machine.', after: first.items.at(-1)!.seq });
    expect(next.items[0].seq).toBe(first.items.at(-1)!.seq + 2);
  });
});

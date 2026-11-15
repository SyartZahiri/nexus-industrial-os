import { describe, expect, it } from 'vitest';
import { answered, queryKey, shown, type QueryState } from './queryState';

describe('query state', () => {
  it('aRefusalForANewQueryDoesNotShowTheOldAnswer', () => {
    let s: QueryState<string> = { key: '', data: null, error: null };
    s = answered(s, 'order PO-1', { ok: true, result: 'PO-1 data' });
    expect(shown(s, 'order PO-1')).toEqual({ data: 'PO-1 data', error: null });
    s = answered(s, 'order PO-1', { ok: false, status: 0, message: 'Server nicht erreichbar' });
    expect(shown(s, 'order PO-1')).toEqual({ data: 'PO-1 data', error: 'Server nicht erreichbar' }); // same query: keep the last data
    expect(shown(s, 'order PO-9999')).toEqual({ data: null, error: null }); // another query before its answer: nothing yet
    s = answered(s, 'order PO-9999', { ok: false, status: 404, message: 'Auftrag nicht gefunden' });
    expect(shown(s, 'order PO-9999')).toEqual({ data: null, error: 'Auftrag nicht gefunden' });
  });

  it('aRestartDropsTheAnswersOfTheOldRun', () => {
    const q = { query: 'order', orderId: 'PO-0010' };
    let s: QueryState<string> = { key: '', data: null, error: null };
    s = answered(s, queryKey(q, 1), { ok: true, result: 'PO-0010 of run 1' });
    // after a reset the same order id asks again and the new run does not know it
    s = answered(s, queryKey(q, 2), { ok: false, status: 404, message: 'Auftrag nicht gefunden' });
    expect(shown(s, queryKey(q, 2))).toEqual({ data: null, error: 'Auftrag nicht gefunden' });
  });
});

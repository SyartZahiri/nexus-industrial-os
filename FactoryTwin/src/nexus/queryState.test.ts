import { describe, expect, it } from 'vitest';
import { answered, shown, type QueryState } from './queryState';

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
});

import { describe, expect, it } from 'vitest';
import { OP_STATE, ORDER_STATE, PRIORITY_LABEL, stateOf, STATE_HEX, TRANSPORT_STATE } from './format';

const colours = new Set<string>(Object.values(STATE_HEX));

describe('states of orders, operations and transports', () => {
  it('everyCodeOfTheCoreHasAWordAndAStateColour', () => {
    const orders = ['CREATED', 'MATERIAL_PENDING', 'SHORTAGE', 'RELEASED', 'IN_PROGRESS', 'COMPLETED', 'CANCELLED'];
    const ops = ['PLANNED', 'WAITING_MATERIAL', 'READY', 'RUNNING', 'DONE', 'CANCELLED'];
    const transports = ['CREATED', 'ASSIGNED', 'TO_PICKUP', 'LOADING', 'IN_TRANSIT', 'UNLOADING', 'DELIVERED', 'FAILED', 'CANCELLED'];
    for (const [map, codes] of [
      [ORDER_STATE, orders],
      [OP_STATE, ops],
      [TRANSPORT_STATE, transports],
    ] as const) {
      for (const c of codes) {
        expect(map[c]?.text, c).toMatch(/^[A-ZÄÖÜ]/);
        expect(colours.has(map[c].color), c).toBe(true);
      }
    }
    expect(PRIORITY_LABEL).toEqual({ NORMAL: 'Normal', HOCH: 'Hoch', EILIG: 'Eilig' });
  });

  it('anUnknownCodeStaysReadable', () => {
    expect(stateOf(ORDER_STATE, 'ON_HOLD')).toEqual({ text: 'ON_HOLD', color: STATE_HEX.unknown });
  });
});

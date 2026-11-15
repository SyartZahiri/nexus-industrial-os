import { describe, expect, it } from 'vitest';
import type { Command, Schedule } from '../../nexus/types';
import { ganttLayout } from './ganttLayout';
import { newOrderForm, orderCommand, submitOrder } from './orderForm';

const HOUR = 3_600_000;
const plan = (ops: Schedule['ops'], lateOrders: string[] = []): Schedule => ({
  rule: 'PRIORITY',
  ops,
  unplannable: [],
  lateOrders,
  utilization: {},
  bottleneckMachineId: null,
});

describe('order form', () => {
  it('sends what the form holds', () => {
    expect(orderCommand({ productId: 'GH200', quantity: 5, priority: 'EILIG', dueInMinutes: 90 })).toEqual({
      command: 'createOrder',
      productId: 'GH200',
      quantity: 5,
      priority: 'EILIG',
      dueInMinutes: 90,
    });
  });

  it('aRefusedOrderKeepsWhatTheUserTyped', async () => {
    const typed = { productId: 'AW50', quantity: 0, priority: 'HOCH' as const, dueInMinutes: -5 };
    const sent: Command[] = [];
    expect(await submitOrder(typed, async (c) => (sent.push(c), false))).toBe(typed);
    expect(await submitOrder(typed, async () => true)).toEqual(newOrderForm('AW50'));
    expect(sent).toHaveLength(1);
  });
});

describe('gantt', () => {
  it('ganttHandlesAnEmptyPlanAndZeroLengthOps', () => {
    const empty = ganttLayout(plan([]), 2 * HOUR, ['CNC-01', 'QC-01']);
    expect(empty.lanes.map((l) => l.machineId)).toEqual(['CNC-01', 'QC-01']);
    expect(empty.lanes.every((l) => l.bars.length === 0)).toBe(true);
    expect(empty.toMs - empty.fromMs).toBe(HOUR);
    expect(empty.nowLeft).toBe(0);

    const g = ganttLayout(
      plan(
        [
          { orderId: 'PO-1', seq: 1, machineId: 'CNC-01', startMs: 2 * HOUR, endMs: 2 * HOUR, setupMs: 0 },
          { orderId: 'PO-2', seq: 1, machineId: 'QC-01', startMs: 3 * HOUR, endMs: 5 * HOUR, setupMs: 30 * 60_000 },
        ],
        ['PO-2'],
      ),
      2 * HOUR,
      ['CNC-01', 'QC-01'],
    );
    const [zero] = g.lanes[0].bars;
    const [late] = g.lanes[1].bars;
    expect(zero.width).toBeGreaterThanOrEqual(0.5);
    expect(Number.isFinite(zero.left)).toBe(true);
    expect(late).toMatchObject({ late: true, setupShare: 0.25 });
    expect(late.left).toBeCloseTo((1 / 3) * 100);
    expect(late.width).toBeCloseTo((2 / 3) * 100);
  });
});

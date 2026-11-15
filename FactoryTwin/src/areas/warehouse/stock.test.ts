import { describe, expect, it } from 'vitest';
import type { FrameStock, WarehouseInfo } from '../../nexus/types';
import { freeCapacity, receiptCommand, stockRows } from './stock';

const info: WarehouseInfo = {
  materials: [
    { id: 'RAW-AL', name: 'Aluminium-Rohling', kind: 'RAW', reorderPoint: 40, reorderQty: 100 },
    { id: 'FG-GH200', name: 'GH-200 fertig', kind: 'FINISHED', reorderPoint: 0, reorderQty: 0 },
  ],
  locations: [
    { id: 'RAW-1', name: 'Rohlager 1', zone: 'RAW', capacity: 120 },
    { id: 'FG-1', name: 'Fertigwaren 1', zone: 'FINISHED', capacity: 160 },
  ],
};
const stock: FrameStock[] = [
  { materialId: 'RAW-AL', locationId: 'RAW-1', onHand: 50, reserved: 20 },
  { materialId: 'FG-GH200', locationId: 'FG-1', onHand: 12, reserved: 0 },
];

describe('warehouse', () => {
  it('names stock and flags material below its reorder point', () => {
    const rows = stockRows(stock, info);
    expect(rows[0]).toMatchObject({ locationName: 'Rohlager 1', materialName: 'Aluminium-Rohling', available: 30, belowReorder: true });
    expect(rows[1]).toMatchObject({ materialName: 'GH-200 fertig', belowReorder: false });
    expect(stockRows([{ materialId: 'X', locationId: 'Y', onHand: 1, reserved: 0 }], info)[0]).toMatchObject({
      materialName: 'X',
      locationName: 'Y',
    });
  });

  it('receiptRoomIsTheFreeCapacity', () => {
    expect(freeCapacity(stock, info, 'RAW-1')).toBe(70);
    expect(freeCapacity(stock, info, 'FG-1')).toBe(148);
    expect(freeCapacity(stock, info, 'NOPE')).toBeNull();
    expect(freeCapacity([{ materialId: 'RAW-AL', locationId: 'RAW-1', onHand: 130, reserved: 0 }], info, 'RAW-1')).toBe(0);
    expect(receiptCommand('RAW-AL', 'RAW-1', 25)).toEqual({ command: 'stockReceipt', materialId: 'RAW-AL', locationId: 'RAW-1', quantity: 25 });
  });
});

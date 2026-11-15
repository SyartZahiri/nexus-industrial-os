import type { Command, FrameStock, WarehouseInfo } from '../../nexus/types';

export interface StockRow {
  key: string;
  locationId: string;
  locationName: string;
  materialId: string;
  materialName: string;
  onHand: number;
  reserved: number;
  available: number;
  belowReorder: boolean;
}

/** One row per stock line, with names from FlowPilot's master data; below reorder counts all locations together. */
export function stockRows(stock: FrameStock[], info: WarehouseInfo): StockRow[] {
  const available = new Map<string, number>();
  for (const s of stock) available.set(s.materialId, (available.get(s.materialId) ?? 0) + s.onHand - s.reserved);
  return stock.map((s) => {
    const m = info.materials.find((x) => x.id === s.materialId);
    const l = info.locations.find((x) => x.id === s.locationId);
    return {
      key: `${s.locationId}/${s.materialId}`,
      locationId: s.locationId,
      locationName: l?.name ?? s.locationId,
      materialId: s.materialId,
      materialName: m?.name ?? s.materialId,
      onHand: s.onHand,
      reserved: s.reserved,
      available: s.onHand - s.reserved,
      belowReorder: m !== undefined && m.reorderPoint > 0 && (available.get(s.materialId) ?? 0) < m.reorderPoint,
    };
  });
}

/** Room left at a location; null for a location FlowPilot does not know. */
export function freeCapacity(stock: FrameStock[], info: WarehouseInfo, locationId: string): number | null {
  const l = info.locations.find((x) => x.id === locationId);
  if (!l) return null;
  const used = stock.filter((s) => s.locationId === locationId).reduce((sum, s) => sum + s.onHand, 0);
  return Math.max(0, l.capacity - used);
}

export function receiptCommand(materialId: string, locationId: string, quantity: number): Command {
  return { command: 'stockReceipt', materialId, locationId, quantity };
}

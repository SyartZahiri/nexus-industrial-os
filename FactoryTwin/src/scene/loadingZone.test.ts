import { describe, expect, it } from 'vitest';
import type { FrameDock, FrameTruck } from '../nexus/types';
import { baySlots, dockSlots, MAX_PALLETS } from './loadingZoneLayout';

const truck = (id: string, status: FrameTruck['status'], from = 'D0', to = 'D0'): FrameTruck => ({ id, name: id, status, tourId: null, from, to, progress: 0 });

describe('loading zone', () => {
  it('shows one pallet per pallet of the waiting deliveries, at most MAX_PALLETS, inside the zone', () => {
    const dock: FrameDock[] = [
      { deliveryId: 'DL-0001', status: 'READY_TO_LOAD', pallets: 2 },
      { deliveryId: 'DL-0002', status: 'LOADED', pallets: 1 },
    ];
    const slots = dockSlots(dock);
    expect(slots).toHaveLength(2); // loaded goods are on the truck, not on the floor
    for (const s of slots) {
      expect(s.x).toBeGreaterThan(19);
      expect(s.x).toBeLessThan(24);
      expect(s.z).toBeGreaterThan(16);
      expect(s.z).toBeLessThan(18);
    }
    expect(dockSlots([{ deliveryId: 'X', status: 'READY_TO_LOAD', pallets: 99 }])).toHaveLength(MAX_PALLETS);
  });

  it('parks the vehicles that are at the depot in their own bays', () => {
    const bays = baySlots([truck('LKW-1', 'AVAILABLE'), truck('LKW-2', 'EN_ROUTE', 'D0', 'K01'), truck('LW-1', 'LOADING'), truck('LW-2', 'MAINTENANCE')]);
    expect(bays.map((b) => b.id)).toEqual(['LKW-1', 'LW-1', 'LW-2']);
    expect(bays.find((b) => b.id === 'LW-1')!.loading).toBe(true);
    expect(new Set(bays.map((b) => b.x)).size).toBe(3);
  });
});

import type { FrameDock, FrameTruck } from '../nexus/types';

/** Where the loading zone shows waiting pallets and parked delivery vehicles (pure, for the scene and tests). */
export const MAX_PALLETS = 12;
/** Truck bays along the south wall, east of the dock; one per delivery vehicle (four). */
export const BAYS_X = [24.6, 26.6, 28.6, 30.6];
export const BAY_Z = 16.2;

/** Floor places for the pallets waiting for loading: two rows between the dock and the wall. */
export function dockSlots(dock: FrameDock[]): { x: number; z: number }[] {
  let pallets = 0;
  for (const d of dock) if (d.status === 'READY_TO_LOAD') pallets += d.pallets;
  const n = Math.min(pallets, MAX_PALLETS);
  return Array.from({ length: n }, (_, i) => ({ x: 19.6 + (i % 6) * 0.7, z: i < 6 ? 16.9 : 17.6 }));
}

/** Vehicles at the depot (not on the road) in fixed bays, in the order of their ids. */
export function baySlots(trucks: FrameTruck[]): { id: string; x: number; z: number; loading: boolean }[] {
  return trucks
    .map((t, i) => ({ t, i }))
    .filter(({ t }) => t.from === t.to && t.status !== 'EN_ROUTE' && t.status !== 'RETURNING')
    .map(({ t, i }) => ({ id: t.id, x: BAYS_X[i % BAYS_X.length], z: BAY_Z, loading: t.status === 'LOADING' }));
}

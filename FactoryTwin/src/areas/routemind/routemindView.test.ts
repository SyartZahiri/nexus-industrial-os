import { describe, expect, it } from 'vitest';
import roadsFile from '../../../public/routemind/roads.json';
import type { FrameTruck, RmDelivery, RmPlan, RmTour } from '../../nexus/types';
import type { Roads } from './roads';
import { delayMinutes, planDelta, placesGeoJson, routesGeoJson, shownTours, tourColor, trucksGeoJson } from './routemindView';

const roads = roadsFile as Roads;
const MIN = 60_000;

const tour = (id: string, status: RmTour['status'], stops: string[]): RmTour => ({
  id,
  planId: 'RP-0001',
  vehicleId: 'LKW-1',
  departAtMs: 60 * MIN,
  status,
  km: 40,
  cost: 120,
  startedAtMs: null,
  endedAtMs: null,
  stops: stops.map((c, i) => ({ seq: i + 1, deliveryId: `DL-000${i + 1}`, customerId: c, plannedArrivalMs: (70 + i * 20) * MIN, arrivedAtMs: null })),
});

const delivery = (over: Partial<RmDelivery>): RmDelivery => ({
  id: 'DL-0001',
  orderId: 'PO-0001',
  customerId: 'K01',
  customerName: 'Kunde Altstetten',
  productId: 'GH200',
  quantity: 4,
  storedQuantity: 4,
  weightKg: 72,
  pallets: 1,
  priority: 'NORMAL',
  earliestMs: 30 * MIN,
  latestMs: 270 * MIN,
  status: 'DELIVERED',
  tourId: 'TR-0001',
  plannedArrivalMs: 100 * MIN,
  deliveredAtMs: 112 * MIN,
  failures: 0,
  ...over,
});

describe('RouteMind view', () => {
  it('shows planned, loading and running tours only', () => {
    const tours = [tour('TR-0001', 'COMPLETED', ['K01']), tour('TR-0002', 'EN_ROUTE', ['K05']), tour('TR-0003', 'REPLANNED', ['K06']), tour('TR-0004', 'PLANNED', ['K07'])];
    expect(shownTours(tours).map((t) => t.id)).toEqual(['TR-0002', 'TR-0004']);
  });

  it('gives every shown tour its own colour, the same on every call', () => {
    const tours = [tour('TR-0002', 'EN_ROUTE', ['K05']), tour('TR-0004', 'PLANNED', ['K07'])];
    expect(tourColor(tours, 'TR-0002')).not.toBe(tourColor(tours, 'TR-0004'));
    expect(tourColor(tours, 'TR-0002')).toBe(tourColor(tours, 'TR-0002'));
  });

  it('draws a tour along the roads from the depot over its stops and back', () => {
    const fc = routesGeoJson(roads, [tour('TR-0002', 'EN_ROUTE', ['K05', 'K06'])], null);
    expect(fc.features).toHaveLength(1);
    const line = fc.features[0].geometry.coordinates;
    const d0 = roads.places[0];
    expect(line[0]).toEqual([d0.lon, d0.lat]);
    expect(line[line.length - 1]).toEqual([d0.lon, d0.lat]);
    expect(line.length).toBeGreaterThan(6); // road shape, not three straight lines
    expect(fc.features[0].properties).toMatchObject({ tourId: 'TR-0002', selected: false, dim: false });
    expect(routesGeoJson(roads, [tour('TR-0002', 'EN_ROUTE', ['K05'])], 'TR-0009').features[0].properties.dim).toBe(true);
  });

  it('puts a truck on the road between two places', () => {
    const t: FrameTruck = { id: 'LKW-1', name: 'LKW', status: 'EN_ROUTE', tourId: 'TR-0002', from: 'D0', to: 'K24', progress: 0.5 };
    const [lon, lat] = trucksGeoJson(roads, [t]).features[0].geometry.coordinates;
    const a = roads.places.find((p) => p.id === 'D0')!;
    const b = roads.places.find((p) => p.id === 'K24')!;
    expect(lon).toBeGreaterThan(Math.min(a.lon, b.lon));
    expect(lon).toBeLessThan(Math.max(a.lon, b.lon));
    expect(lat).toBeGreaterThan(Math.min(a.lat, b.lat) - 0.05);
  });

  it('marks customers with an open delivery', () => {
    const fc = placesGeoJson(roads, [delivery({ status: 'IN_TRANSIT', customerId: 'K05' })]);
    expect(fc.features.find((f) => f.properties.id === 'K05')!.properties.open).toBe(true);
    expect(fc.features.find((f) => f.properties.id === 'D0')!.properties.depot).toBe(true);
  });

  it('measures lateness against the window and the change of a replanning', () => {
    expect(delayMinutes(delivery({ deliveredAtMs: 280 * MIN }))).toBe(10);
    expect(delayMinutes(delivery({}))).toBe(0);
    expect(delayMinutes(delivery({ deliveredAtMs: null }))).toBeNull();
    const plan = { replaced: { km: 50, driveMinutes: 60, cost: 200, lateMinutes: 0, tours: 2 }, optimized: { km: 40, driveMinutes: 55, cost: 180, lateMinutes: 5, tours: 2 } } as RmPlan;
    expect(planDelta(plan)).toEqual({ km: -10, cost: -20, lateMinutes: 5 });
    expect(planDelta({ ...plan, replaced: null })).toBeNull();
  });
});

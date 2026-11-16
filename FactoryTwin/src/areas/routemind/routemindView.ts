import type { FrameTruck, RmDelivery, RmPlan, RmTour } from '../../nexus/types';
import { DELIVERY_STATE, VEHICLE_STATE, stateOf } from '../../ui/format';
import { legPath, pointAlong, type LngLat, type Roads } from './roads';

/**
 * Tours must be told apart on the map (Spec 15a), so they get categorical colours. They are not state colours and
 * are used for the route lines only; states keep STATE_HEX.
 */
export const TOUR_COLORS = ['#7dd3fc', '#fcd34d', '#c4b5fd', '#86efac', '#fda4af', '#5eead4'];

interface Feature<G, P> {
  type: 'Feature';
  geometry: G;
  properties: P;
}
interface Collection<G, P> {
  type: 'FeatureCollection';
  features: Feature<G, P>[];
}
type Line = { type: 'LineString'; coordinates: LngLat[] };
type Point = { type: 'Point'; coordinates: LngLat };

/** What the map draws: tours that are planned, being loaded or under way. */
export function shownTours(tours: RmTour[]): RmTour[] {
  return tours.filter((t) => t.status === 'PLANNED' || t.status === 'LOADING' || t.status === 'EN_ROUTE');
}

export function tourColor(tours: RmTour[], tourId: string): string {
  const shown = shownTours(tours);
  const i = shown.findIndex((t) => t.id === tourId);
  return TOUR_COLORS[(i < 0 ? 0 : i) % TOUR_COLORS.length];
}

export function routesGeoJson(roads: Roads, tours: RmTour[], selected: string | null) {
  const shown = shownTours(tours);
  const features = shown.map((t, i) => {
    const stops = ['D0', ...t.stops.map((s) => s.customerId), 'D0'];
    const coordinates: LngLat[] = [];
    for (let k = 1; k < stops.length; k++) {
      const leg = legPath(roads, stops[k - 1], stops[k]);
      coordinates.push(...(coordinates.length > 0 ? leg.slice(1) : leg));
    }
    return {
      type: 'Feature' as const,
      geometry: { type: 'LineString' as const, coordinates },
      properties: { tourId: t.id, color: TOUR_COLORS[i % TOUR_COLORS.length], selected: t.id === selected, dim: selected !== null && t.id !== selected },
    };
  });
  return { type: 'FeatureCollection', features } as Collection<Line, { tourId: string; color: string; selected: boolean; dim: boolean }>;
}

export function placesGeoJson(roads: Roads, deliveries: RmDelivery[]) {
  const openAt = new Map<string, RmDelivery>();
  for (const d of deliveries) {
    if (d.status !== 'DELIVERED' && d.status !== 'CANCELLED') openAt.set(d.customerId, d);
  }
  const features = roads.places.map((p, i) => {
    const d = openAt.get(p.id);
    return {
      type: 'Feature' as const,
      geometry: { type: 'Point' as const, coordinates: [p.lon, p.lat] as LngLat },
      properties: { id: p.id, name: p.name, depot: i === 0, open: d !== undefined, color: d ? stateOf(DELIVERY_STATE, d.status).color : '#94a3b8' },
    };
  });
  return { type: 'FeatureCollection', features } as Collection<Point, { id: string; name: string; depot: boolean; open: boolean; color: string }>;
}

export function trucksGeoJson(roads: Roads, trucks: FrameTruck[]) {
  const features = trucks
    .filter((t) => t.from !== t.to || t.status === 'BROKEN_DOWN')
    .map((t) => ({
      type: 'Feature' as const,
      geometry: { type: 'Point' as const, coordinates: pointAlong(legPath(roads, t.from, t.to), t.progress) },
      properties: { id: t.id, color: stateOf(VEHICLE_STATE, t.status).color },
    }));
  return { type: 'FeatureCollection', features } as Collection<Point, { id: string; color: string }>;
}

/** Minutes after the customer's window closed; 0 on time; null while not delivered. */
export function delayMinutes(d: RmDelivery): number | null {
  if (d.deliveredAtMs === null) return null;
  return Math.max(0, Math.round((d.deliveredAtMs - d.latestMs) / 60_000));
}

/** New plan against the plan it replaced: negative = better. */
export function planDelta(p: RmPlan): { km: number; cost: number; lateMinutes: number } | null {
  if (!p.replaced) return null;
  const r = (v: number) => Math.round(v * 10) / 10;
  return { km: r(p.optimized.km - p.replaced.km), cost: r(p.optimized.cost - p.replaced.cost), lateMinutes: r(p.optimized.lateMinutes - p.replaced.lateMinutes) };
}

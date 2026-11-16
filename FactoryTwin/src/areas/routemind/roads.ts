/** RouteMind's fixed road network (Spec 15a): places and OSRM road shapes from public/routemind/roads.json. */
export interface RoadPlace {
  id: string;
  name: string;
  lat: number;
  lon: number;
}

export interface Roads {
  attribution: string;
  generated: string;
  places: RoadPlace[];
  /** Google polyline (precision 5) per pair "a>b", a before b in place order. */
  geometry: Record<string, string>;
}

export type LngLat = [number, number];

export async function loadRoads(): Promise<Roads> {
  const res = await fetch(`${import.meta.env.BASE_URL}routemind/roads.json`);
  if (!res.ok) throw new Error(`Strassendaten fehlen (HTTP ${res.status})`);
  return (await res.json()) as Roads;
}

export function decodePolyline(text: string): LngLat[] {
  const out: LngLat[] = [];
  let i = 0;
  let lat = 0;
  let lon = 0;
  while (i < text.length) {
    for (let axis = 0; axis < 2; axis++) {
      let shift = 0;
      let result = 0;
      let b: number;
      do {
        b = text.charCodeAt(i++) - 63;
        result |= (b & 0x1f) << shift;
        shift += 5;
      } while (b >= 0x20);
      const delta = result & 1 ? ~(result >> 1) : result >> 1;
      if (axis === 0) lat += delta;
      else lon += delta;
    }
    out.push([lon / 1e5, lat / 1e5]);
  }
  return out;
}

const decoded = new WeakMap<Roads, Map<string, LngLat[]>>();

/** The road from one place to another as [lon, lat] points; a single point if both are the same. */
export function legPath(roads: Roads, from: string, to: string): LngLat[] {
  const a = roads.places.findIndex((p) => p.id === from);
  const b = roads.places.findIndex((p) => p.id === to);
  if (a < 0 || b < 0) throw new Error(`Unbekannter Ort ${a < 0 ? from : to}`);
  if (a === b) return [[roads.places[a].lon, roads.places[a].lat]];
  const key = a < b ? `${from}>${to}` : `${to}>${from}`;
  let cache = decoded.get(roads);
  if (!cache) decoded.set(roads, (cache = new Map()));
  let path = cache.get(key);
  if (!path) cache.set(key, (path = decodePolyline(roads.geometry[key])));
  return a < b ? path : [...path].reverse();
}

/** The point at share t (0..1) of the path's length; longitude scaled to the latitude. */
export function pointAlong(path: LngLat[], t: number): LngLat {
  if (path.length === 1 || t <= 0) return path[0];
  if (t >= 1) return path[path.length - 1];
  const c = Math.cos((path[0][1] * Math.PI) / 180);
  const len = (p: LngLat, q: LngLat) => Math.hypot((q[0] - p[0]) * c, q[1] - p[1]);
  let total = 0;
  for (let i = 1; i < path.length; i++) total += len(path[i - 1], path[i]);
  let left = total * t;
  for (let i = 1; i < path.length; i++) {
    const l = len(path[i - 1], path[i]);
    if (left <= l && l > 0) {
      const f = left / l;
      return [path[i - 1][0] + (path[i][0] - path[i - 1][0]) * f, path[i - 1][1] + (path[i][1] - path[i - 1][1]) * f];
    }
    left -= l;
  }
  return path[path.length - 1];
}

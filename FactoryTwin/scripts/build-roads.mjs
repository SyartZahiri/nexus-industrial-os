// One-time generator for RouteMind's road network (Spec 15a, "Strassennetz"). Not part of any build: it asks the
// public OSRM demo server (OpenStreetMap data, ODbL) for driving time and distance between every pair of places and
// for the road geometry between them, and writes
//   RouteMind/src/main/java/io/nexus/routemind/master/RoadData.java   matrix for the core (both runtimes)
//   FactoryTwin/public/routemind/roads.json                            places and geometry for the map
// Usage, from the repository root: node FactoryTwin/scripts/build-roads.mjs   (about 9 minutes, ~1 request/s)
import { mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '../..');
const OSRM = 'https://router.project-osrm.org';
const ATTRIBUTION = 'Strassendaten © OpenStreetMap-Mitwirkende (ODbL), Routen berechnet mit OSRM';
const places = JSON.parse(readFileSync(resolve(root, 'RouteMind/roads/places.json'), 'utf8'));
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const round5 = (v) => Math.round(v * 1e5) / 1e5;

async function get(path) {
  for (let attempt = 1; ; attempt++) {
    try {
      const res = await fetch(OSRM + path, { headers: { 'User-Agent': 'nexus-roads-generator/1.0' } });
      if (!res.ok) throw new Error(`HTTP ${res.status}`);
      const body = await res.json();
      if (body.code !== 'Ok') throw new Error(`${body.code} ${body.message ?? ''}`);
      return body;
    } catch (e) {
      if (attempt === 5) throw new Error(`${path}: ${e.message}`);
      await sleep(3000 * attempt);
    }
  }
}

/** Google polyline, precision 5: [[lat, lon], ...]. */
function decode(text) {
  const points = [];
  let i = 0;
  let lat = 0;
  let lon = 0;
  while (i < text.length) {
    for (let axis = 0; axis < 2; axis++) {
      let shift = 0;
      let result = 0;
      let b;
      do {
        b = text.charCodeAt(i++) - 63;
        result |= (b & 0x1f) << shift;
        shift += 5;
      } while (b >= 0x20);
      const delta = result & 1 ? ~(result >> 1) : result >> 1;
      if (axis === 0) lat += delta;
      else lon += delta;
    }
    points.push([lat / 1e5, lon / 1e5]);
  }
  return points;
}

function encode(points) {
  let out = '';
  let prevLat = 0;
  let prevLon = 0;
  const put = (value) => {
    let v = value < 0 ? ~(value << 1) : value << 1;
    while (v >= 0x20) {
      out += String.fromCharCode((0x20 | (v & 0x1f)) + 63);
      v >>= 5;
    }
    out += String.fromCharCode(v + 63);
  };
  for (const [la, lo] of points) {
    const a = Math.round(la * 1e5);
    const b = Math.round(lo * 1e5);
    put(a - prevLat);
    put(b - prevLon);
    prevLat = a;
    prevLon = b;
  }
  return out;
}

/** Distance of p from segment a-b in degrees, longitude scaled to the latitude. */
function segmentDistance(p, a, b) {
  const c = Math.cos((a[0] * Math.PI) / 180);
  const [px, py, ax, ay, bx, by] = [p[1] * c, p[0], a[1] * c, a[0], b[1] * c, b[0]];
  const dx = bx - ax;
  const dy = by - ay;
  const len = dx * dx + dy * dy;
  const t = len === 0 ? 0 : Math.max(0, Math.min(1, ((px - ax) * dx + (py - ay) * dy) / len));
  return Math.hypot(px - (ax + t * dx), py - (ay + t * dy));
}

/** Douglas-Peucker; 0.00005° is about 5 m. */
function simplify(points, tolerance) {
  if (points.length < 3) return points;
  const keep = points.map((_, k) => k === 0 || k === points.length - 1);
  const stack = [[0, points.length - 1]];
  while (stack.length > 0) {
    const [a, b] = stack.pop();
    let best = -1;
    let dist = 0;
    for (let k = a + 1; k < b; k++) {
      const d = segmentDistance(points[k], points[a], points[b]);
      if (d > dist) {
        dist = d;
        best = k;
      }
    }
    if (best >= 0 && dist > tolerance) {
      keep[best] = true;
      stack.push([a, best], [best, b]);
    }
  }
  return points.filter((_, k) => keep[k]);
}

const coords = places.map((p) => `${p.lon},${p.lat}`).join(';');
const table = await get(`/table/v1/driving/${coords}?annotations=duration,distance`);
for (const [matrix, what] of [[table.durations, 'Fahrzeit'], [table.distances, 'Strecke']]) {
  matrix.forEach((row, i) =>
    row.forEach((v, j) => {
      if (v == null || (i !== j && v <= 0)) throw new Error(`${what} ${places[i].id}>${places[j].id} fehlt`);
    }),
  );
}
const seconds = table.durations.map((row) => row.map((v) => Math.round(v)));
const meters = table.distances.map((row) => row.map((v) => Math.round(v)));
// where the road network reaches each place; the map draws them there
const snapped = table.sources.map((s, i) => ({ ...places[i], lat: round5(s.location[1]), lon: round5(s.location[0]) }));

const geometry = {};
for (let i = 0; i < snapped.length; i++) {
  for (let j = i + 1; j < snapped.length; j++) {
    const a = snapped[i];
    const b = snapped[j];
    const route = await get(`/route/v1/driving/${a.lon},${a.lat};${b.lon},${b.lat}?overview=full&geometries=polyline`);
    geometry[`${a.id}>${b.id}`] = encode(simplify(decode(route.routes[0].geometry), 0.00005));
    await sleep(1100); // the demo server allows about one request per second
  }
  console.log(`${snapped[i].id} fertig`);
}

const today = new Date().toISOString().slice(0, 10);
mkdirSync(resolve(root, 'FactoryTwin/public/routemind'), { recursive: true });
writeFileSync(
  resolve(root, 'FactoryTwin/public/routemind/roads.json'),
  JSON.stringify({ attribution: ATTRIBUTION, generated: today, places: snapped, geometry }) + '\n',
);

const strings = (xs) => xs.map((x) => JSON.stringify(x)).join(', ');
const rows = (m) => m.map((r) => `      {${r.join(', ')}}`).join(',\n');
writeFileSync(
  resolve(root, 'RouteMind/src/main/java/io/nexus/routemind/master/RoadData.java'),
  `// Generated by FactoryTwin/scripts/build-roads.mjs from RouteMind/roads/places.json on ${today}. Do not edit.
// ${ATTRIBUTION} (router.project-osrm.org).
package io.nexus.routemind.master;

/** Fixed road network of RouteMind: places (snapped to the road) with OSRM driving time and distance. */
final class RoadData {
  private RoadData() {}

  static final String ATTRIBUTION = ${JSON.stringify(ATTRIBUTION)};
  static final String[] IDS = {${strings(snapped.map((p) => p.id))}};
  static final String[] NAMES = {${strings(snapped.map((p) => p.name))}};
  static final double[] LAT = {${snapped.map((p) => p.lat).join(', ')}};
  static final double[] LON = {${snapped.map((p) => p.lon).join(', ')}};
  /** Driving time in seconds; row = from, column = to. */
  static final int[][] SECONDS = {
${rows(seconds)}
  };
  /** Driving distance in metres; row = from, column = to. */
  static final int[][] METERS = {
${rows(meters)}
  };
}
`,
);
console.log('roads.json und RoadData.java geschrieben');

import type { Vec2 } from './types';

/**
 * Physical layout of the hall and the directed AGV path network.
 *
 * The AGV network is a one-way ring (clockwise seen from above) plus one-way
 * cross connectors, with one-way trapezoid detours to every docking point.
 * A fully directed graph means AGVs never meet head-on; only car-following
 * and merges need to be resolved, which keeps the traffic logic deadlock-free.
 */

export const HALL = { minX: -32, maxX: 32, minZ: -18, maxZ: 18, height: 11 };

export interface PathNode {
  id: string;
  pos: Vec2;
  dock?: boolean;
}

export interface PathEdge {
  from: string;
  to: string;
  length: number;
}

export interface PathGraph {
  nodes: Map<string, PathNode>;
  edges: PathEdge[];
  out: Map<string, PathEdge[]>;
}

type LaneId = 'N' | 'E' | 'S' | 'W' | 'X1' | 'X2' | 'X3';

interface LaneDef {
  id: LaneId;
  from: Vec2;
  to: Vec2;
  /** Outward normal for detours. */
  normal: Vec2;
}

/** Main ring (clockwise) plus three one-way cross connectors through the centre aisle. */
export const LANES: LaneDef[] = [
  { id: 'N', from: { x: -20, z: -3 }, to: { x: 20, z: -3 }, normal: { x: 0, z: -1 } },
  { id: 'E', from: { x: 20, z: -3 }, to: { x: 20, z: 3 }, normal: { x: 1, z: 0 } },
  { id: 'S', from: { x: 20, z: 3 }, to: { x: -20, z: 3 }, normal: { x: 0, z: 1 } },
  { id: 'W', from: { x: -20, z: 3 }, to: { x: -20, z: -3 }, normal: { x: -1, z: 0 } },
  { id: 'X1', from: { x: -6.5, z: -3 }, to: { x: -6.5, z: 3 }, normal: { x: -1, z: 0 } },
  { id: 'X2', from: { x: 8, z: -3 }, to: { x: 8, z: 3 }, normal: { x: 1, z: 0 } },
  { id: 'X3', from: { x: 14.6, z: 3 }, to: { x: 14.6, z: -3 }, normal: { x: 1, z: 0 } },
];

interface DetourDef {
  dock: string;
  lane: LaneId;
  /** Center coordinate along the lane axis (x for N/S, z for E/W/X). */
  at: number;
  depth: number;
  /** Optional custom polyline (entry .. exit, including dock) replacing the trapezoid. */
  custom?: { points: Vec2[]; dockIndex: number };
}

export const DETOURS: DetourDef[] = [
  // North lane (eastbound): chargers and CNC docks
  { dock: 'D_CH1', lane: 'N', at: -17, depth: 2.5 },
  { dock: 'D_CNC01', lane: 'N', at: -12, depth: 2.5 },
  { dock: 'D_CNC02', lane: 'N', at: -4, depth: 2.5 },
  { dock: 'D_CNC03', lane: 'N', at: 4, depth: 2.5 },
  { dock: 'D_CNC04', lane: 'N', at: 12, depth: 2.5 },
  { dock: 'D_CH2', lane: 'N', at: 17, depth: 2.5 },
  // East connector (southbound): finished goods warehouse
  { dock: 'D_FG', lane: 'E', at: 0, depth: 2.4 },
  // South lane (westbound): outfeed, chargers, line infeeds
  { dock: 'D_CV04', lane: 'S', at: 17.3, depth: 2.3 },
  { dock: 'D_CH3', lane: 'S', at: 11.8, depth: 2.3 },
  { dock: 'D_CH4', lane: 'S', at: 4.6, depth: 2.3 },
  { dock: 'D_CV02', lane: 'S', at: 0, depth: 2.3 },
  { dock: 'D_CV01', lane: 'S', at: -9, depth: 2.3 },
  {
    dock: 'D_CV03',
    lane: 'S',
    at: -14,
    depth: 0,
    custom: {
      points: [
        { x: -12, z: 3 },
        { x: -12.5, z: 9.6 },
        { x: -13.2, z: 11.4 },
        { x: -14, z: 11.4 },
        { x: -14.8, z: 11.4 },
        { x: -15.5, z: 9.6 },
        { x: -16, z: 3 },
      ],
      dockIndex: 3,
    },
  },
  // West connector (northbound): raw material warehouse
  // Loading zone (RouteMind): out of the ring at the south-east corner, along the finished-goods store to the
  // truck bays and back onto the south lane.
  {
    dock: 'D_LZ',
    lane: 'S',
    at: 19.6,
    depth: 0,
    custom: {
      points: [
        { x: 20, z: 3 },
        { x: 23.2, z: 5.5 },
        { x: 23.2, z: 14 },
        { x: 22, z: 15.4 },
        { x: 20.8, z: 14 },
        { x: 19.3, z: 3 },
      ],
      dockIndex: 3,
    },
  },
  { dock: 'D_RAW', lane: 'W', at: 0, depth: 2.4 },
  // Cross connector X2: centre-aisle charging bay
  { dock: 'D_CH5', lane: 'X2', at: 0, depth: 1.9 },
  { dock: 'D_CH6', lane: 'X1', at: 0, depth: 1.9 },
];

const r1 = (v: number) => Math.round(v * 100) / 100;

function laneAxis(l: LaneDef): 'x' | 'z' {
  return l.from.x !== l.to.x ? 'x' : 'z';
}

export function buildPathGraph(): PathGraph {
  const nodes = new Map<string, PathNode>();
  const byPos = new Map<string, string>();
  const edges: PathEdge[] = [];
  /** One node per position, so lanes that touch share their junction node. */
  const addNode = (id: string, pos: Vec2, dock = false) => {
    const key = `${r1(pos.x)},${r1(pos.z)}`;
    const existing = byPos.get(key);
    if (existing) return existing;
    nodes.set(id, { id, pos: { x: r1(pos.x), z: r1(pos.z) }, dock });
    byPos.set(key, id);
    return id;
  };
  const addEdge = (from: string, to: string) => {
    if (from === to) return;
    const a = nodes.get(from)!.pos;
    const b = nodes.get(to)!.pos;
    edges.push({ from, to, length: Math.hypot(a.x - b.x, a.z - b.z) });
  };

  for (const lane of LANES) {
    const axis = laneAxis(lane);
    const dir = { x: Math.sign(lane.to.x - lane.from.x), z: Math.sign(lane.to.z - lane.from.z) };
    const sign = axis === 'x' ? dir.x : dir.z;
    const coord = (p: Vec2) => (axis === 'x' ? p.x : p.z);
    const point = (c: number): Vec2 => (axis === 'x' ? { x: c, z: lane.from.z } : { x: lane.from.x, z: c });
    const stations: { c: number; id: string }[] = [];
    stations.push({ c: coord(lane.from), id: addNode(`J_${lane.from.x}_${lane.from.z}`, lane.from) });
    stations.push({ c: coord(lane.to), id: addNode(`J_${lane.to.x}_${lane.to.z}`, lane.to) });

    // Junctions: endpoints of other lanes lying on this lane.
    const lo = Math.min(coord(lane.from), coord(lane.to));
    const hi = Math.max(coord(lane.from), coord(lane.to));
    for (const other of LANES) {
      if (other === lane) continue;
      for (const p of [other.from, other.to]) {
        const onLine = axis === 'x' ? Math.abs(p.z - lane.from.z) < 1e-6 : Math.abs(p.x - lane.from.x) < 1e-6;
        const c = coord(p);
        if (onLine && c > lo + 1e-6 && c < hi - 1e-6) stations.push({ c, id: addNode(`J_${p.x}_${p.z}`, p) });
      }
    }

    const chains: string[][] = [];
    for (const d of DETOURS.filter((x) => x.lane === lane.id)) {
      let pts: Vec2[];
      let dockIndex: number;
      if (d.custom) {
        pts = d.custom.points;
        dockIndex = d.custom.dockIndex;
      } else {
        const c = point(d.at);
        const off = (along: number, depth: number): Vec2 => ({
          x: c.x + dir.x * along + lane.normal.x * depth,
          z: c.z + dir.z * along + lane.normal.z * depth,
        });
        pts = [off(-2, 0), off(-0.9, d.depth), off(0, d.depth), off(0.9, d.depth), off(2, 0)];
        dockIndex = 2;
      }
      const ids = pts.map((p, i) => {
        if (i === 0 || i === pts.length - 1) {
          const id = addNode(`L${lane.id}_${r1(coord(p))}`, p);
          stations.push({ c: coord(p), id });
          return id;
        }
        if (i === dockIndex) return addNode(d.dock, p, true);
        return addNode(`${d.dock}_${i}`, p);
      });
      chains.push(ids);
    }
    stations.sort((a, b) => (a.c - b.c) * sign);
    const seq = stations.map((s) => s.id).filter((id, i, arr) => i === 0 || arr[i - 1] !== id);
    for (let i = 0; i < seq.length - 1; i++) addEdge(seq[i], seq[i + 1]);
    for (const chain of chains) for (let i = 0; i < chain.length - 1; i++) addEdge(chain[i], chain[i + 1]);
  }

  const out = new Map<string, PathEdge[]>();
  for (const e of edges) {
    if (!out.has(e.from)) out.set(e.from, []);
    out.get(e.from)!.push(e);
  }
  return { nodes, edges, out };
}

export const PATH_GRAPH = buildPathGraph();

export function nodePos(id: string): Vec2 {
  const n = PATH_GRAPH.nodes.get(id);
  if (!n) throw new Error(`Unknown path node ${id}`);
  return n.pos;
}

/** Static scene anchors used by the renderer (purely visual geometry). */
export const ZONES = {
  rawStore: { x: -27.5, z: 0, w: 6, d: 26 },
  finishedStore: { x: 27.5, z: 0, w: 6, d: 26 },
  machining: { x: 0, z: -10, w: 32, d: 7 },
  assembly: { x: -2, z: 10.5, w: 30, d: 8 },
  scrapBin: { x: 14.8, z: 14.7 },
  rawStaging: { x: -23.6, z: 2.4 },
};

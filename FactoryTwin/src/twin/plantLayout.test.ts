import { describe, expect, it } from 'vitest';
import * as core from '@nexus-core';
import { CHARGER_NODES, MACHINE_DEFS } from './plantLayout';
import { PATH_GRAPH } from './layout';
import type { Frame } from '../nexus/types';

describe('plantLayout', () => {
  it('draws exactly the machines the core simulates', () => {
    core.plantStart(1);
    const frame = JSON.parse(core.plantFrame()) as Frame;
    expect(MACHINE_DEFS.map((m) => m.id).sort()).toEqual(frame.machines.map((m) => m.id).sort());
  });

  it('puts every charger on a node of the path graph', () => {
    for (const c of CHARGER_NODES) expect(PATH_GRAPH.nodes.has(c.node), c.id).toBe(true);
  });
});

describe('loading zone', () => {
  it('has the dock D_LZ on a one-way detour from J_20_3 back to LS_19.3, as in V5__loading_zone.sql', () => {
    const pos = (id: string) => PATH_GRAPH.nodes.get(id)?.pos;
    expect(pos('D_LZ')).toEqual({ x: 22, z: 15.4 });
    expect(PATH_GRAPH.nodes.get('D_LZ')?.dock).toBe(true);
    expect(pos('D_LZ_1')).toEqual({ x: 23.2, z: 5.5 });
    expect(pos('D_LZ_2')).toEqual({ x: 23.2, z: 14 });
    expect(pos('D_LZ_4')).toEqual({ x: 20.8, z: 14 });
    const edges = PATH_GRAPH.edges.map((e) => `${e.from}>${e.to}`);
    expect(edges).toEqual(expect.arrayContaining(['J_20_3>D_LZ_1', 'D_LZ_1>D_LZ_2', 'D_LZ_2>D_LZ', 'D_LZ>D_LZ_4', 'D_LZ_4>LS_19.3']));
  });
});

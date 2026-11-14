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

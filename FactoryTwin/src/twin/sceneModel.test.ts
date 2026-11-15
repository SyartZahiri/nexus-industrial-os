import { describe, expect, it } from 'vitest';
import * as core from '@nexus-core';
import { toScene } from './sceneModel';
import type { Frame } from '../nexus/types';

function runningFrame(): Frame {
  core.plantStart(7);
  core.plantAdvance(40 * 60_000);
  return JSON.parse(core.plantFrame()) as Frame;
}

describe('toScene', () => {
  it('places every machine, conveyor, AGV and charger of the plant', () => {
    const s = toScene(runningFrame());
    expect(s.machines.map((m) => m.id)).toEqual(['CNC-01', 'CNC-02', 'CNC-03', 'CNC-04', 'QC-01', 'ROB-01', 'ROB-02']);
    expect(s.conveyors.map((c) => c.id)).toEqual(['CV-01', 'CV-02', 'CV-03', 'CV-04']);
    expect(s.agvs).toHaveLength(6);
    expect(s.chargers).toHaveLength(6);
  });

  it('gives every busy machine a job whose workpiece is known', () => {
    const s = toScene(runningFrame());
    const busy = s.machines.filter((m) => m.job);
    expect(busy.length).toBeGreaterThan(0);
    for (const m of busy) {
      expect(s.workpieces.get(m.job!.wpId)).toBeDefined();
      expect(m.job!.progress).toBeGreaterThanOrEqual(0);
      expect(m.job!.progress).toBeLessThanOrEqual(1);
    }
  });

  it('counts raw and finished stock from the warehouse', () => {
    const s = toScene(runningFrame());
    expect(s.inventory.raw).toBeGreaterThan(0);
    expect(Object.keys(s.inventory.finished).sort()).toEqual(['AW50', 'FP12', 'GH200', 'MT7']);
  });
});

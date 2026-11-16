import { describe, expect, it } from 'vitest';
import { Twin } from './twin';
import type { Frame } from '../nexus/types';

const frame = (t: number, x: number): Frame => ({
  simTimeMs: t,
  machines: [],
  conveyors: [],
  stock: [],
  orders: [],
  transports: [],
  trucks: [],
  dock: [],
  agvs: [
    {
      id: 'AGV-01',
      name: 'AGV 1',
      status: 'TO_PICKUP',
      x,
      z: 0,
      heading: 0,
      speed: 1,
      battery: 80,
      transportId: null,
      route: [],
    },
  ],
});

describe('Twin', () => {
  it('interpolates an AGV between the two latest frames over the time they arrived apart', () => {
    const twin = new Twin();
    twin.apply({ type: 'frame', frame: frame(0, 0) }, 1_000);
    twin.apply({ type: 'frame', frame: frame(100, 10) }, 1_100);
    expect(twin.agvPose('AGV-01', 1_150)!.x).toBeCloseTo(5);
  });

  it('interpolationStopsAtTheLatestFrame', () => {
    const twin = new Twin();
    twin.apply({ type: 'frame', frame: frame(0, 0) }, 1_000);
    twin.apply({ type: 'frame', frame: frame(100, 10) }, 1_100);
    expect(twin.agvPose('AGV-01', 5_000)!.x).toBe(10);
  });

  it('resetForgetsBothFrames', () => {
    const twin = new Twin();
    twin.apply({ type: 'frame', frame: frame(0, 0) }, 1_000);
    twin.apply({ type: 'reset', seed: 3 }, 1_050);
    expect(twin.latest).toBeNull();
    expect(twin.previous).toBeNull();
    twin.apply({ type: 'frame', frame: frame(0, 7) }, 1_100);
    expect(twin.agvPose('AGV-01', 1_200)!.x).toBe(7);
  });
});

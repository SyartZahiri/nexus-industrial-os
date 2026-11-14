import type { Frame, NexusMessage } from '../nexus/types';
import { toScene } from './sceneModel';
import type { MachineConfig, SceneState } from './types';

/**
 * Holds the latest and the previous frame (Spec 14). The scene reads interpolated poses from here in useFrame;
 * panels read the scene state through the store. Between two frames it interpolates over the real time they arrived
 * apart and never beyond the latest one: a late frame makes the picture wait, it does not make an AGV jump ahead.
 */
export class Twin {
  latest: Frame | null = null;
  previous: Frame | null = null;
  /** Override and coolant the user set; only the machines know them, the frame does not carry them. */
  configs: Record<string, Partial<MachineConfig>> = {};
  private latestAt = 0;
  private previousAt = 0;
  private sceneCache: SceneState | null = null;

  apply(m: NexusMessage, now: number): void {
    if (m.type === 'reset') {
      this.latest = this.previous = null;
      this.configs = {};
      this.sceneCache = null;
    } else if (m.type === 'frame') {
      this.previous = this.latest;
      this.previousAt = this.latestAt;
      this.latest = m.frame;
      this.latestAt = now;
      this.sceneCache = null;
    }
  }

  /** 0 at the previous frame, 1 at the latest; never more. */
  alpha(now: number): number {
    if (!this.previous) return 1;
    const span = this.latestAt - this.previousAt;
    if (span <= 0) return 1;
    return Math.min(1, Math.max(0, (now - this.latestAt) / span));
  }

  agvPose(id: string, now: number): { x: number; z: number; heading: number } | null {
    const b = this.latest?.agvs.find((a) => a.id === id);
    if (!b) return null;
    const a = this.previous?.agvs.find((v) => v.id === id) ?? b;
    const t = this.alpha(now);
    let dh = b.heading - a.heading;
    if (dh > Math.PI) dh -= 2 * Math.PI;
    if (dh < -Math.PI) dh += 2 * Math.PI;
    return { x: a.x + (b.x - a.x) * t, z: a.z + (b.z - a.z) * t, heading: a.heading + dh * t };
  }

  setConfig(machineId: string, config: Partial<MachineConfig>): void {
    this.configs = { ...this.configs, [machineId]: { ...this.configs[machineId], ...config } };
    this.sceneCache = null;
  }

  get scene(): SceneState | null {
    if (!this.latest) return null;
    this.sceneCache ??= toScene(this.latest, this.configs);
    return this.sceneCache;
  }
}

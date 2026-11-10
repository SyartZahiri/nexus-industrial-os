import * as THREE from 'three';
import type { Machine } from '../sim/types';

/**
 * Per-frame visual runtime data shared between 3D components (not part of the simulation state).
 * e.g. the world position of each robot gripper, so the workpiece renderer can attach parts to it.
 */
export const robotTips = new Map<string, THREE.Vector3>();

export type RobotPhase = 'pick' | 'carry' | 'work' | 'place' | 'home';

/** Converts the robot's job progress into a motion phase (shared by arm and workpiece renderer). */
export function robotPhase(m: Machine): { phase: RobotPhase; f: number } {
  if (m.status === 'BLOCKED') return { phase: 'place', f: 1 };
  if (!m.job) return { phase: 'home', f: 0 };
  if (m.status === 'SETUP') return { phase: 'pick', f: 0 };
  const f = Math.min(1, m.job.progress / m.job.duration);
  if (f < 0.18) return { phase: 'pick', f };
  if (f < 0.32) return { phase: 'carry', f };
  if (f < 0.78) return { phase: 'work', f };
  return { phase: 'place', f };
}

/** Robot cell geometry relative to the robot base (sim x/z → local offsets). */
export const ROBOT_CELL = {
  pick: new THREE.Vector3(-1.7, 1.12, -0.7),
  fixture: new THREE.Vector3(1.7, 1.05, -0.9),
  place: new THREE.Vector3(0, 1.12, 1.7),
  home: new THREE.Vector3(0.7, 2.05, 0.4),
};

export const BELT_TOP = 0.85;


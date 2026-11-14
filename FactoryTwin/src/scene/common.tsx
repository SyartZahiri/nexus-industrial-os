import type { ThreeEvent } from '@react-three/fiber';
import { useFrame } from '@react-three/fiber';
import { useMemo, useRef, type ReactNode } from 'react';
import * as THREE from 'three';
import { plant, useFactory, type Selection } from '../store/factoryStore';
import { ledMaterial } from './materials';

/** Wraps an asset so it can be hovered, clicked (select) and double clicked (select + focus camera). */
export function Selectable({ sel, children }: { sel: Selection; children: ReactNode }) {
  const select = useFactory((s) => s.select);
  const hover = useFactory((s) => s.hover);
  return (
    <group
      onClick={(e: ThreeEvent<MouseEvent>) => {
        e.stopPropagation();
        select(sel);
      }}
      onDoubleClick={(e: ThreeEvent<MouseEvent>) => {
        e.stopPropagation();
        select(sel, true);
      }}
      onPointerOver={(e: ThreeEvent<PointerEvent>) => {
        e.stopPropagation();
        hover(sel.id);
        document.body.style.cursor = 'pointer';
      }}
      onPointerOut={() => {
        hover(null);
        document.body.style.cursor = 'auto';
      }}
    >
      {children}
    </group>
  );
}

const SIGNAL_COLORS = ['#ff2d2d', '#ffb020', '#22ff6a', '#3b82ff'];

export type LampState = { on: boolean; blink?: boolean };

/** Industrial signal tower (red / amber / green / blue). */
export function StackLight({
  position,
  get,
}: {
  position: [number, number, number];
  get: () => [LampState, LampState, LampState, LampState];
}) {

  const mats = useMemo(() => SIGNAL_COLORS.map((c) => ledMaterial(c, 0)), []);
  useFrame(({ clock }) => {
    const states = get();
    const blinkOn = Math.floor(clock.elapsedTime * 2.5) % 2 === 0;
    states.forEach((st, i) => {
      const lit = st.on && (!st.blink || blinkOn);
      mats[i].emissiveIntensity = lit ? 3.2 : 0.04;
    });
  });
  return (
    <group position={position}>
      <mesh position={[0, -0.12, 0]}>
        <cylinderGeometry args={[0.025, 0.025, 0.25, 8]} />
        <meshStandardMaterial color="#30353d" metalness={0.7} roughness={0.4} />
      </mesh>
      {mats.map((m, i) => (
        <mesh key={i} position={[0, 0.06 + (3 - i) * 0.105, 0]} material={m}>
          <cylinderGeometry args={[0.06, 0.06, 0.1, 16]} />
        </mesh>
      ))}
      <mesh position={[0, 0.5, 0]}>
        <cylinderGeometry args={[0.065, 0.065, 0.03, 16]} />
        <meshStandardMaterial color="#1b1f24" />
      </mesh>
    </group>
  );
}

/** Animation clock that only advances while the simulation runs (capped so motion stays smooth at high speed). */
export function useAnimClock() {
  const t = useRef(0);
  useFrame((_, dt) => {
    const s = plant.state;
    if (s.running) t.current += Math.min(dt, 0.1) * Math.min(s.speed, 4);
  });
  return t;
}

export const tmpV = new THREE.Vector3();

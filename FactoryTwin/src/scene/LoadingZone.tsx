import { useFrame } from '@react-three/fiber';
import { useMemo, useRef } from 'react';
import * as THREE from 'three';
import { twin } from '../store/factoryStore';
import { nodePos } from '../twin/layout';
import { BAY_Z, BAYS_X, baySlots, dockSlots, MAX_PALLETS } from './loadingZoneLayout';
import { MAT } from './materials';

/** Loading zone of RouteMind: floor marking, waiting pallets and the delivery vehicles at their bays. */
export function LoadingZone() {
  const dock = nodePos('D_LZ');
  const pallets = useRef<THREE.InstancedMesh>(null);
  const trucks = useRef<THREE.Group>(null);
  const box = useMemo(() => new THREE.BoxGeometry(1, 1, 1), []);
  const tmp = useMemo(() => new THREE.Object3D(), []);
  const last = useRef('');

  useFrame(() => {
    const frame = twin.latest;
    if (!frame) return;
    const slots = dockSlots(frame.dock ?? []);
    const bays = baySlots(frame.trucks ?? []);
    const key = `${slots.length}|${bays.map((b) => `${b.id}${b.loading}`).join(',')}`;
    if (key === last.current) return;
    last.current = key;
    const mesh = pallets.current!;
    slots.forEach((s, i) => {
      tmp.position.set(s.x, 0.35, s.z);
      tmp.scale.set(0.6, 0.6, 0.6);
      tmp.updateMatrix();
      mesh.setMatrixAt(i, tmp.matrix);
    });
    mesh.count = slots.length;
    mesh.instanceMatrix.needsUpdate = true;
    mesh.computeBoundingSphere();
    const g = trucks.current!;
    g.children.forEach((child, i) => {
      const bay = bays.find((b) => BAYS_X.indexOf(b.x) === i);
      child.visible = bay !== undefined;
    });
  });

  return (
    <group>
      {/* floor marking of the zone */}
      <mesh rotation-x={-Math.PI / 2} position={[25.2, 0.011, 16.1]}>
        <planeGeometry args={[12.4, 3.6]} />
        <meshStandardMaterial color="#1c2533" roughness={0.95} transparent opacity={0.85} />
      </mesh>
      <mesh rotation-x={-Math.PI / 2} position={[dock.x, 0.013, dock.z]}>
        <planeGeometry args={[1.6, 1.6]} />
        <meshStandardMaterial color="#13261d" roughness={0.9} transparent opacity={0.8} />
      </mesh>
      <instancedMesh ref={pallets} args={[box, MAT.pallet, MAX_PALLETS]} castShadow />
      <group ref={trucks}>
        {BAYS_X.map((x) => (
          <group key={x} position={[x, 0, BAY_Z]} visible={false}>
            {/* box body and cab of a delivery vehicle, nose towards the wall */}
            <mesh position={[0, 1.1, -0.3]} material={MAT.steel} castShadow>
              <boxGeometry args={[1.7, 1.8, 2.6]} />
            </mesh>
            <mesh position={[0, 0.8, 1.35]} material={MAT.machineDark} castShadow>
              <boxGeometry args={[1.7, 1.3, 0.9]} />
            </mesh>
          </group>
        ))}
      </group>
    </group>
  );
}

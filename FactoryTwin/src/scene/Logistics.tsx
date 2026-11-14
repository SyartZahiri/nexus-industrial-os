import { RoundedBox } from '@react-three/drei';
import { useFrame } from '@react-three/fiber';
import { useLayoutEffect, useMemo, useRef } from 'react';
import * as THREE from 'three';
import { PRODUCT_IDS, PRODUCTS } from '../twin/config';
import { nodePos } from '../twin/layout';
import { plant, sceneOf, useFactory } from '../store/factoryStore';
import { AGV_COLOR, CONVEYOR_COLOR } from '../ui/format';
import { Selectable } from './common';
import { createBeltTexture, ledMaterial, MAT } from './materials';
import { BELT_TOP } from './runtime';

/* ====================================================================== */
/* Conveyors                                                               */
/* ====================================================================== */

export function ConveyorModel({ id }: { id: string }) {
  const c0 = plant.conveyor(id)!;
  const len = c0.length;
  const dx = c0.end.x - c0.start.x;
  const dz = c0.end.z - c0.start.z;
  const yaw = Math.atan2(dx, dz);
  const mid = { x: (c0.start.x + c0.end.x) / 2, z: (c0.start.z + c0.end.z) / 2 };
  const belt = useMemo(() => {
    const t = createBeltTexture();
    t.repeat.set(1, len / 0.5);
    return new THREE.MeshStandardMaterial({ map: t, roughness: 0.9, metalness: 0.05 });
  }, [len]);
  const status = useMemo(() => ledMaterial('#22c55e', 2), []);
  const legs = useMemo(() => {
    const out: number[] = [];
    const n = Math.max(2, Math.round(len / 1.6) + 1);
    for (let i = 0; i < n; i++) out.push(-len / 2 + 0.2 + ((len - 0.4) * i) / (n - 1));
    return out;
  }, [len]);
  const rollers = useRef<THREE.Group>(null);

  useFrame(() => {
    const c = plant.state.conveyors.find((x) => x.id === id)!;
    // texture runs along +v; belt moves from start → end
    belt.map!.offset.y = c.beltOffset / 0.5;
    status.emissive.set(CONVEYOR_COLOR[c.status]);
    status.emissiveIntensity = c.status === 'FAULT' ? (Math.floor(performance.now() / 300) % 2 ? 3 : 0.2) : 2;
    if (rollers.current) rollers.current.children.forEach((r) => (r.rotation.x = c.beltOffset / 0.06));
  });

  return (
    <Selectable sel={{ type: 'conveyor', id }}>
      <group position={[mid.x, 0, mid.z]} rotation-y={yaw}>
        <mesh position={[0, BELT_TOP - 0.03, 0]} rotation-x={-Math.PI / 2} material={belt} receiveShadow>
          <planeGeometry args={[0.62, len]} />
        </mesh>
        <mesh position={[0, BELT_TOP - 0.1, 0]} material={MAT.steelDark} castShadow receiveShadow>
          <boxGeometry args={[0.66, 0.12, len]} />
        </mesh>
        {[-0.37, 0.37].map((x) => (
          <mesh key={x} position={[x, BELT_TOP - 0.02, 0]} material={MAT.steel} castShadow>
            <boxGeometry args={[0.06, 0.12, len + 0.05]} />
          </mesh>
        ))}
        <group ref={rollers}>
          {[-len / 2, len / 2].map((z) => (
            <mesh key={z} position={[0, BELT_TOP - 0.08, z]} rotation-z={Math.PI / 2} material={MAT.steelBright}>
              <cylinderGeometry args={[0.07, 0.07, 0.66, 14]} />
            </mesh>
          ))}
        </group>
        {legs.map((z) =>
          [-0.28, 0.28].map((x) => (
            <mesh key={`${z}${x}`} position={[x, (BELT_TOP - 0.16) / 2, z]} material={MAT.steelDark}>
              <boxGeometry args={[0.05, BELT_TOP - 0.16, 0.05]} />
            </mesh>
          )),
        )}
        {/* drive unit + status beacon at the discharge end */}
        <mesh position={[0.48, BELT_TOP - 0.12, len / 2 - 0.2]} material={MAT.machineDark}>
          <boxGeometry args={[0.24, 0.24, 0.32]} />
        </mesh>
        <mesh position={[0.48, BELT_TOP + 0.12, len / 2 - 0.2]} material={status}>
          <cylinderGeometry args={[0.04, 0.04, 0.12, 10]} />
        </mesh>
        <mesh position={[0, BELT_TOP + 0.04, len / 2 + 0.04]} material={MAT.yellow}>
          <boxGeometry args={[0.7, 0.08, 0.04]} />
        </mesh>
      </group>
    </Selectable>
  );
}

/* ====================================================================== */
/* Pallet racks (raw material / finished goods)                           */
/* ====================================================================== */

const RACK = { bays: 8, levels: 3, bayLen: 3, perBay: 2, depth: 1.2, levelH: 1.55 };

function rackSlots(x0: number) {
  // Two rows of racks, oriented along z
  const slots: THREE.Vector3[] = [];
  const rows = [x0 - 1.4, x0 + 1.4];
  for (let lvl = 0; lvl < RACK.levels; lvl++) {
    for (let b = 0; b < RACK.bays; b++) {
      for (const rx of rows) {
        for (let p = 0; p < RACK.perBay; p++) {
          const z = -12 + b * RACK.bayLen + 0.75 + p * 1.5;
          slots.push(new THREE.Vector3(rx, 0.18 + lvl * RACK.levelH, z));
        }
      }
    }
  }
  return slots;
}

function RackFrame({ x0 }: { x0: number }) {
  const uprights = useRef<THREE.InstancedMesh>(null);
  const beams = useRef<THREE.InstancedMesh>(null);
  const { up, bm } = useMemo(() => {
    const up: THREE.Matrix4[] = [];
    const bm: THREE.Matrix4[] = [];
    const h = RACK.levels * RACK.levelH + 0.4;
    for (const rx of [x0 - 1.4, x0 + 1.4]) {
      for (let b = 0; b <= RACK.bays; b++) {
        const z = -12 + b * RACK.bayLen;
        for (const ox of [-RACK.depth / 2, RACK.depth / 2]) up.push(new THREE.Matrix4().compose(new THREE.Vector3(rx + ox, h / 2, z), new THREE.Quaternion(), new THREE.Vector3(0.08, h, 0.08)));
      }
      for (let lvl = 0; lvl < RACK.levels; lvl++) {
        for (const ox of [-RACK.depth / 2, RACK.depth / 2]) {
          bm.push(
            new THREE.Matrix4().compose(
              new THREE.Vector3(rx + ox, 0.1 + lvl * RACK.levelH, 0),
              new THREE.Quaternion(),
              new THREE.Vector3(0.06, 0.12, RACK.bays * RACK.bayLen),
            ),
          );
        }
      }
    }
    return { up, bm };
  }, [x0]);
  useLayoutEffect(() => {
    up.forEach((m, i) => uprights.current!.setMatrixAt(i, m));
    bm.forEach((m, i) => beams.current!.setMatrixAt(i, m));
    uprights.current!.instanceMatrix.needsUpdate = true;
    beams.current!.instanceMatrix.needsUpdate = true;
    uprights.current!.computeBoundingSphere();
    beams.current!.computeBoundingSphere();
  }, [up, bm]);
  const box = useMemo(() => new THREE.BoxGeometry(1, 1, 1), []);
  return (
    <group>
      <instancedMesh ref={uprights} args={[box, MAT.rackUpright, up.length]} castShadow />
      <instancedMesh ref={beams} args={[box, MAT.rackBeam, bm.length]} castShadow />
    </group>
  );
}

/** Rack with live fill level: one crate per stocked unit (up to the number of slots). */
export function Warehouse({ kind }: { kind: 'raw' | 'finished' }) {
  const x0 = kind === 'raw' ? -27.6 : 27.6;
  const slots = useMemo(() => rackSlots(x0), [x0]);
  const crates = useRef<THREE.InstancedMesh>(null);
  const pallets = useRef<THREE.InstancedMesh>(null);
  const box = useMemo(() => new THREE.BoxGeometry(1, 1, 1), []);
  const crateMat = useMemo(() => new THREE.MeshStandardMaterial({ roughness: 0.75, metalness: 0.05 }), []);
  const tmp = useMemo(() => new THREE.Object3D(), []);
  const colors = useMemo(() => PRODUCT_IDS.map((p) => new THREE.Color(PRODUCTS[p].color).multiplyScalar(0.75)), []);
  const rawColor = useMemo(() => new THREE.Color('#77706a'), []);
  const last = useRef('');

  useLayoutEffect(() => {
    slots.forEach((s, i) => {
      tmp.position.set(s.x, s.y + 0.06, s.z);
      tmp.scale.set(1.1, 0.12, 1.2);
      tmp.updateMatrix();
      pallets.current!.setMatrixAt(i, tmp.matrix);
    });
    pallets.current!.instanceMatrix.needsUpdate = true;
    pallets.current!.computeBoundingSphere();
  }, [slots, tmp]);

  useFrame(() => {
    const inv = plant.state.inventory;
    const key = kind === 'raw' ? `${inv.raw}` : PRODUCT_IDS.map((p) => inv.finished[p]).join(',');
    if (key === last.current) return;
    last.current = key;
    const mesh = crates.current!;
    // Unit → crate: each slot holds one crate standing for 2 units.
    let list: THREE.Color[] = [];
    if (kind === 'raw') list = Array.from({ length: Math.ceil(inv.raw / 2) }, () => rawColor);
    else PRODUCT_IDS.forEach((p, pi) => list.push(...Array.from({ length: Math.ceil(inv.finished[p] / 2) }, () => colors[pi])));
    const n = Math.min(list.length, slots.length);
    for (let i = 0; i < n; i++) {
      const s = slots[i];
      tmp.position.set(s.x, s.y + 0.55, s.z);
      tmp.scale.set(1.0, 0.85, 1.1);
      tmp.updateMatrix();
      mesh.setMatrixAt(i, tmp.matrix);
      mesh.setColorAt(i, list[i]);
    }
    mesh.count = n;
    mesh.instanceMatrix.needsUpdate = true;
    if (mesh.instanceColor) mesh.instanceColor.needsUpdate = true;
    mesh.computeBoundingSphere();
  });

  return (
    <Selectable sel={{ type: kind, id: kind === 'raw' ? 'RAW' : 'FG' }}>
      <group>
        <RackFrame x0={x0} />
        <instancedMesh ref={pallets} args={[box, MAT.pallet, slots.length]} receiveShadow />
        <instancedMesh ref={crates} args={[box, crateMat, slots.length]} castShadow />
        {/* transfer table next to the AGV dock */}
        <mesh position={[kind === 'raw' ? -23.7 : 23.7, 0.4, 0]} material={MAT.steelDark} castShadow>
          <boxGeometry args={[0.9, 0.8, 1.6]} />
        </mesh>
        <mesh position={[kind === 'raw' ? -23.7 : 23.7, 0.82, 0]} material={MAT.steel}>
          <boxGeometry args={[0.95, 0.04, 1.65]} />
        </mesh>
      </group>
    </Selectable>
  );
}

/* ====================================================================== */
/* Charging stations                                                       */
/* ====================================================================== */

export function Chargers() {
  return (
    <group>
      {plant.state.chargers.map((c) => (
        <Charger key={c.id} id={c.id} />
      ))}
    </group>
  );
}

function Charger({ id }: { id: string }) {
  const ch = plant.state.chargers.find((c) => c.id === id)!;
  const p = nodePos(ch.node);
  const led = useMemo(() => ledMaterial('#22c55e', 1), []);
  useFrame(({ clock }) => {
    const c = plant.state.chargers.find((x) => x.id === id)!;
    const agv = c.occupiedBy ? plant.agv(c.occupiedBy) : null;
    const charging = agv && (agv.state === 'CHARGING' || agv.state === 'OFFLINE') && agv.battery < 100;
    led.emissive.set(charging ? '#22c55e' : agv ? '#38bdf8' : '#334155');
    led.emissiveIntensity = charging ? 1.5 + Math.sin(clock.elapsedTime * 3) * 0.8 : 1;
  });
  // place the post on the far side of the dock (away from the lane)
  const lanePoint = { x: p.x, z: Math.abs(p.z) > 3 ? Math.sign(p.z) * 3 : p.z };
  const away = { x: p.x - lanePoint.x, z: p.z - lanePoint.z };
  const al = Math.hypot(away.x, away.z) || 1;
  const off = id === 'CH-5' ? { x: 0.9, z: 0 } : id === 'CH-6' ? { x: -0.9, z: 0 } : { x: (away.x / al) * 0.85, z: (away.z / al) * 0.85 };
  return (
    <group position={[p.x, 0, p.z]}>
      <mesh rotation-x={-Math.PI / 2} position-y={0.012}>
        <planeGeometry args={[1.6, 1.6]} />
        <meshStandardMaterial color="#13261d" roughness={0.9} transparent opacity={0.8} />
      </mesh>
      <mesh position={[off.x, 0.45, off.z]} material={MAT.machineDark} castShadow>
        <boxGeometry args={[0.3, 0.9, 0.3]} />
      </mesh>
      <mesh position={[off.x, 0.75, off.z]} material={led}>
        <boxGeometry args={[0.32, 0.08, 0.32]} />
      </mesh>
    </group>
  );
}

/* ====================================================================== */
/* AGVs                                                                    */
/* ====================================================================== */

export function AgvFleet() {
  // the fleet is known with the first frame; re-render only when its ids change
  const ids = useFactory((s) => sceneOf(s).agvs.map((a) => a.id).join(","));
  return (
    <group>
      {ids
        .split(",")
        .filter(Boolean)
        .map((id) => (
          <AgvModel key={id} id={id} />
        ))}
    </group>
  );
}

function AgvModel({ id }: { id: string }) {
  const g = useRef<THREE.Group>(null);
  const lidar = useRef<THREE.Mesh>(null);
  const led = useMemo(() => ledMaterial('#38bdf8', 2), []);
  const warn = useMemo(() => ledMaterial('#ffb020', 0), []);
  const lift = useRef<THREE.Mesh>(null);
  useFrame(({ clock }, dt) => {
    const a = plant.state.agvs.find((x) => x.id === id)!;
    if (!g.current) return;
    g.current.position.set(a.pos.x, 0, a.pos.z);
    g.current.rotation.y = a.heading;
    if (lidar.current && a.state !== 'OFFLINE') lidar.current.rotation.y += dt * 9;
    const col = a.fault ? '#ef4444' : a.blockedBy && a.speed < 0.05 && a.route.length ? '#f59e0b' : AGV_COLOR[a.state];
    led.emissive.set(col);
    led.emissiveIntensity = a.fault ? (Math.floor(clock.elapsedTime * 3) % 2 ? 3 : 0.3) : a.state === 'CHARGING' ? 1.2 + Math.sin(clock.elapsedTime * 2.5) * 0.6 : 2.2;
    warn.emissiveIntensity = a.speed > 0.05 ? (Math.floor(clock.elapsedTime * 4) % 2 ? 3 : 0.2) : 0.1;
    if (lift.current) {
      const up = a.load ? 0.04 : 0;
      lift.current.position.y = THREE.MathUtils.damp(lift.current.position.y, 0.36 + up, 6, dt);
    }
  });
  return (
    <Selectable sel={{ type: 'agv', id }}>
      <group ref={g}>
        <RoundedBox args={[0.86, 0.26, 1.3]} radius={0.06} smoothness={3} position={[0, 0.2, 0]} material={MAT.agvBody} castShadow />
        <mesh ref={lift} position={[0, 0.36, 0]} material={MAT.agvDeck} castShadow>
          <boxGeometry args={[0.72, 0.04, 1.0]} />
        </mesh>
        {/* LED band */}
        <mesh position={[0, 0.17, 0.655]} material={led}>
          <boxGeometry args={[0.7, 0.035, 0.01]} />
        </mesh>
        <mesh position={[0, 0.17, -0.655]} material={led}>
          <boxGeometry args={[0.7, 0.035, 0.01]} />
        </mesh>
        {[-0.435, 0.435].map((x) => (
          <mesh key={x} position={[x, 0.17, 0]} material={led}>
            <boxGeometry args={[0.01, 0.035, 1.1]} />
          </mesh>
        ))}
        {/* lidar + warning light */}
        <mesh position={[0, 0.36, 0.56]} material={MAT.machineDark}>
          <cylinderGeometry args={[0.07, 0.07, 0.06, 14]} />
        </mesh>
        <mesh ref={lidar} position={[0, 0.41, 0.56]} material={MAT.steelBright}>
          <cylinderGeometry args={[0.05, 0.06, 0.05, 6]} />
        </mesh>
        <mesh position={[0.3, 0.39, -0.55]} material={warn}>
          <sphereGeometry args={[0.035, 8, 8]} />
        </mesh>
        {/* wheels */}
        {[-0.45, 0.45].map((z) =>
          [-0.38, 0.38].map((x) => (
            <mesh key={`${x}${z}`} position={[x, 0.07, z]} rotation-z={Math.PI / 2} material={MAT.rubber}>
              <cylinderGeometry args={[0.07, 0.07, 0.06, 12]} />
            </mesh>
          )),
        )}
        <mesh position={[0, 0.005, 0]} rotation-x={-Math.PI / 2}>
          <planeGeometry args={[1.05, 1.5]} />
          <meshBasicMaterial color="#000" transparent opacity={0.35} depthWrite={false} />
        </mesh>
      </group>
    </Selectable>
  );
}

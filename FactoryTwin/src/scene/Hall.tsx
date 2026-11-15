import { useFrame } from '@react-three/fiber';
import { useLayoutEffect, useMemo, useRef, type ReactNode } from 'react';
import * as THREE from 'three';
import { HALL, PATH_GRAPH } from '../twin/layout';
import { useFactory } from '../store/factoryStore';
import { floorRoughness, floorTexture, MAT } from './materials';

const W = HALL.maxX - HALL.minX;
const D = HALL.maxZ - HALL.minZ;

function Instanced({
  geometry,
  material,
  matrices,
  castShadow = false,
  receiveShadow = false,
}: {
  geometry: THREE.BufferGeometry;
  material: THREE.Material;
  matrices: THREE.Matrix4[];
  castShadow?: boolean;
  receiveShadow?: boolean;
}) {
  const ref = useRef<THREE.InstancedMesh>(null);
  useLayoutEffect(() => {
    const m = ref.current!;
    matrices.forEach((mx, i) => m.setMatrixAt(i, mx));
    m.instanceMatrix.needsUpdate = true;
    m.computeBoundingSphere();
  }, [matrices]);
  return <instancedMesh ref={ref} args={[geometry, material, matrices.length]} castShadow={castShadow} receiveShadow={receiveShadow} />;
}

const mx = (x: number, y: number, z: number, sx = 1, sy = 1, sz = 1, ry = 0) =>
  new THREE.Matrix4().compose(new THREE.Vector3(x, y, z), new THREE.Quaternion().setFromEuler(new THREE.Euler(0, ry, 0)), new THREE.Vector3(sx, sy, sz));

export function Hall() {
  useMemo(() => {
    floorTexture.repeat.set(W / 4, D / 4);
    floorRoughness.repeat.set(W / 4, D / 4);
  }, []);

  const box = useMemo(() => new THREE.BoxGeometry(1, 1, 1), []);
  const H = HALL.height;

  const columns = useMemo(() => {
    const n: THREE.Matrix4[] = [];
    const sth: THREE.Matrix4[] = [];
    const w: THREE.Matrix4[] = [];
    const e: THREE.Matrix4[] = [];
    for (let x = HALL.minX; x <= HALL.maxX + 0.01; x += 8) {
      n.push(mx(x, H / 2, HALL.minZ + 0.25, 0.45, H, 0.45));
      sth.push(mx(x, H / 2, HALL.maxZ - 0.25, 0.45, H, 0.45));
    }
    for (let z = HALL.minZ + 9; z < HALL.maxZ; z += 9) {
      w.push(mx(HALL.minX + 0.25, H / 2, z, 0.45, H, 0.45));
      e.push(mx(HALL.maxX - 0.25, H / 2, z, 0.45, H, 0.45));
    }
    return { n, s: sth, w, e };
  }, [H]);

  const trusses = useMemo(() => {
    const out: THREE.Matrix4[] = [];
    for (let x = HALL.minX; x <= HALL.maxX + 0.01; x += 8) {
      out.push(mx(x, H - 0.2, 0, 0.25, 0.5, D));
      out.push(mx(x, H - 1.1, 0, 0.12, 0.12, D));
    }
    for (let z = HALL.minZ + 4.5; z < HALL.maxZ; z += 9) out.push(mx(0, H - 0.3, z, W, 0.18, 0.18));
    return out;
  }, [H]);

  const lamps = useMemo(() => {
    const out: THREE.Matrix4[] = [];
    for (let x = HALL.minX + 4; x < HALL.maxX; x += 8) {
      for (let z = HALL.minZ + 4.5; z < HALL.maxZ; z += 9) out.push(mx(x, H - 1.6, z, 2.4, 0.08, 0.5));
    }
    return out;
  }, [H]);

  const wallMat = useMemo(() => {
    const m = MAT.wall.clone();
    m.side = THREE.FrontSide;
    return m;
  }, []);

  return (
    <group>
      {/* Floor */}
      <mesh rotation-x={-Math.PI / 2} receiveShadow material={MAT.floor}>
        <planeGeometry args={[W, D]} />
      </mesh>
      {/* Outer apron */}
      <mesh rotation-x={-Math.PI / 2} position-y={-0.02}>
        <planeGeometry args={[W + 80, D + 80]} />
        <meshStandardMaterial color="#0a0c0f" roughness={1} />
      </mesh>

      {/* Walls: only rendered from the inside (front side faces inwards) → never block the camera */}
      <mesh position={[0, H / 2, HALL.minZ]} material={wallMat}>
        <planeGeometry args={[W, H]} />
      </mesh>
      <mesh position={[0, H / 2, HALL.maxZ]} rotation-y={Math.PI} material={wallMat}>
        <planeGeometry args={[W, H]} />
      </mesh>
      <mesh position={[HALL.minX, H / 2, 0]} rotation-y={Math.PI / 2} material={wallMat}>
        <planeGeometry args={[D, H]} />
      </mesh>
      <mesh position={[HALL.maxX, H / 2, 0]} rotation-y={-Math.PI / 2} material={wallMat}>
        <planeGeometry args={[D, H]} />
      </mesh>
      {/* Low perimeter plinth (visible from outside) */}
      <Instanced
        geometry={box}
        material={MAT.wallPanel}
        matrices={[
          mx(0, 0.4, HALL.minZ - 0.1, W + 0.4, 0.8, 0.2),
          mx(0, 0.4, HALL.maxZ + 0.1, W + 0.4, 0.8, 0.2),
          mx(HALL.minX - 0.1, 0.4, 0, 0.2, 0.8, D),
          mx(HALL.maxX + 0.1, 0.4, 0, 0.2, 0.8, D),
        ]}
      />
      {/* Window band on north wall */}
      <mesh position={[0, H - 2.6, HALL.minZ + 0.02]}>
        <planeGeometry args={[W - 2, 1.2]} />
        <meshStandardMaterial color="#0d1a24" emissive="#1c3b52" emissiveIntensity={0.6} />
      </mesh>

      {(['n', 's', 'w', 'e'] as const).map((side) => (
        <WallSide key={side} side={side}>
          <Instanced geometry={box} material={MAT.steelDark} matrices={columns[side]} castShadow />
        </WallSide>
      ))}
      <RoofOnlyFromBelow height={H}>
        <Instanced geometry={box} material={MAT.steelDark} matrices={trusses} />
        <Instanced geometry={box} material={MAT.lampOn} matrices={lamps} />
      </RoofOnlyFromBelow>

      {/* Dock doors */}
      <DockDoor x={HALL.minX + 0.05} z={6} rot={Math.PI / 2} />
      <DockDoor x={HALL.minX + 0.05} z={-6} rot={Math.PI / 2} />
      <DockDoor x={HALL.maxX - 0.05} z={6} rot={-Math.PI / 2} />
      <DockDoor x={HALL.maxX - 0.05} z={-6} rot={-Math.PI / 2} />

      <FloorMarkings />
    </group>
  );
}

/** Columns of the wall the camera stands behind are hidden so they never block the view. */
function WallSide({ side, children }: { side: 'n' | 's' | 'w' | 'e'; children: ReactNode }) {
  const g = useRef<THREE.Group>(null);
  useFrame(({ camera }) => {
    if (!g.current) return;
    const p = camera.position;
    const outside =
      side === 'n' ? p.z < HALL.minZ + 1 : side === 's' ? p.z > HALL.maxZ - 1 : side === 'w' ? p.x < HALL.minX + 1 : p.x > HALL.maxX - 1;
    g.current.visible = !outside;
  });
  return <group ref={g}>{children}</group>;
}

/** Roof structure is only drawn when the camera is below roof level, so it never hides the shop floor. */
function RoofOnlyFromBelow({ height, children }: { height: number; children: ReactNode }) {
  const g = useRef<THREE.Group>(null);
  useFrame(({ camera }) => {
    if (g.current) g.current.visible = camera.position.y < height - 0.6;
  });
  return <group ref={g}>{children}</group>;
}

function DockDoor({ x, z, rot }: { x: number; z: number; rot: number }) {
  return (
    <group position={[x, 0, z]} rotation-y={rot}>
      <mesh position={[0, 2.2, 0.01]}>
        <planeGeometry args={[4, 4.4]} />
        <meshStandardMaterial color="#1f252d" roughness={0.6} metalness={0.5} />
      </mesh>
      <mesh position={[0, 4.5, 0.03]}>
        <planeGeometry args={[4.3, 0.12]} />
        <meshStandardMaterial color="#e8b40e" />
      </mesh>
    </group>
  );
}

/** Painted floor: AGV lanes with direction arrows, zones, safety areas. */
function FloorMarkings() {
  const showPaths = useFactory((s) => s.showPaths);
  const plane = useMemo(() => new THREE.PlaneGeometry(1, 1), []);

  const { lines, arrows } = useMemo(() => {
    const lines: THREE.Matrix4[] = [];
    const arrows: THREE.Matrix4[] = [];
    const q = new THREE.Quaternion();
    for (const e of PATH_GRAPH.edges) {
      const a = PATH_GRAPH.nodes.get(e.from)!.pos;
      const b = PATH_GRAPH.nodes.get(e.to)!.pos;
      const dx = b.x - a.x;
      const dz = b.z - a.z;
      const len = Math.hypot(dx, dz);
      const yaw = Math.atan2(dx, dz);
      q.setFromEuler(new THREE.Euler(-Math.PI / 2, 0, yaw, 'YXZ'));
      // plane is in XY → rotate flat, then align its local Y with the edge direction
      const m = new THREE.Matrix4().compose(
        new THREE.Vector3((a.x + b.x) / 2, 0.012, (a.z + b.z) / 2),
        new THREE.Quaternion().setFromEuler(new THREE.Euler(-Math.PI / 2, 0, Math.PI + yaw, 'YXZ')),
        new THREE.Vector3(0.07, len, 1),
      );
      lines.push(m);
      const n = Math.floor(len / 3);
      for (let i = 1; i <= n; i++) {
        const t = i / (n + 1);
        arrows.push(
          new THREE.Matrix4().compose(
            new THREE.Vector3(a.x + dx * t, 0.014, a.z + dz * t),
            new THREE.Quaternion().setFromEuler(new THREE.Euler(-Math.PI / 2, 0, Math.PI + yaw, 'YXZ')),
            new THREE.Vector3(0.32, 0.32, 1),
          ),
        );
      }
    }
    return { lines, arrows };
  }, []);

  const arrowGeo = useMemo(() => {
    const s = new THREE.Shape();
    s.moveTo(0, 0.5);
    s.lineTo(0.5, -0.2);
    s.lineTo(0.18, -0.2);
    s.lineTo(0, 0.05);
    s.lineTo(-0.18, -0.2);
    s.lineTo(-0.5, -0.2);
    s.closePath();
    return new THREE.ShapeGeometry(s);
  }, []);

  const zones = useMemo(() => {
    const rect = (cx: number, cz: number, w: number, d: number, t = 0.08) => [
      mx(cx, 0.011, cz - d / 2, w, 1, t),
      mx(cx, 0.011, cz + d / 2, w, 1, t),
      mx(cx - w / 2, 0.011, cz, t, 1, d),
      mx(cx + w / 2, 0.011, cz, t, 1, d),
    ];
    return [
      // Robot safety cells
      ...rect(-6.4, 11.1, 5.2, 4.2),
      ...rect(2.6, 11.1, 5.2, 4.2),
      // QC cell
      ...rect(14.8, 13.2, 4.2, 3.6),
      // CNC zones
      ...[-12, -4, 4, 12].flatMap((x) => rect(x, -8.9, 5.6, 6.4)),
    ];
  }, []);

  const flat = useMemo(() => {
    const g = new THREE.BoxGeometry(1, 0.002, 1);
    return g;
  }, []);

  return (
    <group>
      {showPaths && (
        <>
          <Instanced geometry={plane} material={MAT.laneLine} matrices={lines} />
          <Instanced geometry={arrowGeo} material={MAT.laneArrow} matrices={arrows} />
        </>
      )}
      <Instanced geometry={flat} material={MAT.zoneLine} matrices={zones} />
      {/* Storage zones */}
      <mesh rotation-x={-Math.PI / 2} position={[-27.6, 0.01, 0]} material={MAT.zoneFill}>
        <planeGeometry args={[7, 28]} />
      </mesh>
      <mesh rotation-x={-Math.PI / 2} position={[27.6, 0.01, 0]} material={MAT.zoneFill}>
        <planeGeometry args={[7, 28]} />
      </mesh>
      {/* Pedestrian walkway along the south wall */}
      <mesh rotation-x={-Math.PI / 2} position={[0, 0.01, 16.6]} material={MAT.walkway}>
        <planeGeometry args={[50, 1.4]} />
      </mesh>
      {/* Hazard strips in front of dock doors */}
      {[
        [-31, 6],
        [-31, -6],
        [31, 6],
        [31, -6],
      ].map(([x, z]) => (
        <mesh key={`${x}${z}`} rotation-x={-Math.PI / 2} position={[x, 0.012, z]} material={MAT.hazard}>
          <planeGeometry args={[1.2, 4]} />
        </mesh>
      ))}
    </group>
  );
}

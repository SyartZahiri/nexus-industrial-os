import { useFrame } from '@react-three/fiber';
import { useMemo, useRef } from 'react';
import * as THREE from 'three';
import { PRODUCT_IDS, PRODUCTS } from '../twin/config';
import type { ProductId, Workpiece } from '../twin/types';
import { plant } from '../store/factoryStore';
import { QC_SPAN } from './Machines';
import { finishedMaterial, WP_MAT } from './materials';
import { BELT_TOP, ROBOT_CELL, robotPhase, robotTips } from './runtime';

const MAX = 48;
type Finish = 'raw' | 'machined' | 'finished';
const FINISHES: Finish[] = ['raw', 'machined', 'finished'];

/** Geometry per product + half height (to sit on surfaces). */
function productGeometry(p: ProductId): { geo: THREE.BufferGeometry; half: number } {
  switch (p) {
    case 'GH200': {
      const g = new THREE.BoxGeometry(0.42, 0.3, 0.36);
      return { geo: g, half: 0.15 };
    }
    case 'AW50': {
      const g = new THREE.CylinderGeometry(0.09, 0.09, 0.56, 18);
      g.rotateZ(Math.PI / 2);
      return { geo: g, half: 0.09 };
    }
    case 'FP12': {
      const g = new THREE.CylinderGeometry(0.22, 0.22, 0.07, 24);
      return { geo: g, half: 0.035 };
    }
    case 'MT7': {
      const g = new THREE.CylinderGeometry(0.2, 0.2, 0.24, 6);
      return { geo: g, half: 0.12 };
    }
  }
}

const tmp = new THREE.Object3D();

export function Workpieces() {
  const geos = useMemo(() => Object.fromEntries(PRODUCT_IDS.map((p) => [p, productGeometry(p)])) as Record<ProductId, { geo: THREE.BufferGeometry; half: number }>, []);
  const finMats = useMemo(() => Object.fromEntries(PRODUCT_IDS.map((p) => [p, finishedMaterial(PRODUCTS[p].color)])) as unknown as Record<ProductId, THREE.Material>, []);
  const refs = useRef<Record<string, THREE.InstancedMesh | null>>({});
  const counts = useMemo(() => ({}) as Record<string, number>, []);

  useFrame(() => {
    const s = plant.state;
    for (const k of Object.keys(counts)) counts[k] = 0;
    const put = (wp: Workpiece, x: number, y: number, z: number, ry = 0, spinX = 0) => {
      const routing = PRODUCTS[wp.product].routing;
      const fin: Finish = wp.step === 0 ? 'raw' : wp.step >= routing.length ? 'finished' : 'machined';
      const key = `${wp.product}:${fin}`;
      const mesh = refs.current[key];
      if (!mesh) return;
      const i = counts[key] ?? 0;
      if (i >= MAX) return;
      counts[key] = i + 1;
      tmp.position.set(x, y, z);
      tmp.rotation.set(spinX, ry, 0);
      tmp.scale.setScalar(1);
      tmp.updateMatrix();
      mesh.setMatrixAt(i, tmp.matrix);
    };

    // Released blanks waiting at the raw material transfer table
    s.rawQueue.slice(0, 8).forEach((id, i) => {
      const wp = s.workpieces.get(id)!;
      const g = geos[wp.product];
      put(wp, -23.7 + (i % 2 ? 0.2 : -0.2), 0.84 + g.half + Math.floor(i / 4) * 0.32, -0.6 + (i % 4) * 0.4);
    });

    for (const m of s.machines) {
      const bx = m.position.x;
      const bz = m.position.z;
      if (m.kind === 'cnc') {
        m.inBuffer.forEach((id, i) => {
          const wp = s.workpieces.get(id)!;
          put(wp, bx - 1.25 - 0.38 + i * 0.38, 0.805 + geos[wp.product].half, bz + 3.1);
        });
        m.outBuffer.forEach((id, i) => {
          const wp = s.workpieces.get(id)!;
          put(wp, bx + 1.25 - 0.38 + i * 0.38, 0.805 + geos[wp.product].half, bz + 3.1);
        });
        if (m.job) {
          const wp = s.workpieces.get(m.job.wpId)!;
          const lathe = m.capabilities.length === 1 && m.capabilities[0] === 'DREHEN';
          const spin = lathe && wp.product === 'AW50' && m.status === 'RUNNING' && s.running ? performance.now() * 0.02 : 0;
          if (lathe) put(wp, bx - 0.45, 1.78, bz, 0, spin);
          else put(wp, bx, 1.35 + geos[wp.product].half, bz);
        }
      } else if (m.kind === 'robot' && m.job) {
        const wp = s.workpieces.get(m.job.wpId)!;
        const g = geos[wp.product];
        const { phase } = robotPhase(m);
        const tipPos = robotTips.get(m.id);
        if (phase === 'pick' && m.status !== 'BLOCKED') {
          const cv = plant.conveyor(m.input.type === 'conveyor' ? m.input.conveyorId : '')!;
          const tipNear = tipPos && Math.hypot(tipPos.x - (bx + ROBOT_CELL.pick.x), tipPos.z - (bz + ROBOT_CELL.pick.z)) < 0.12 && tipPos.y < 1.3;
          if (tipNear && tipPos) put(wp, tipPos.x, tipPos.y - g.half, tipPos.z);
          else put(wp, cv.end.x, BELT_TOP + g.half, cv.end.z);
        } else if (phase === 'work') {
          put(wp, bx + ROBOT_CELL.fixture.x, 1.045 + g.half, bz + ROBOT_CELL.fixture.z);
        } else if (tipPos) {
          put(wp, tipPos.x, tipPos.y - g.half, tipPos.z);
        }
      } else if (m.kind === 'qc' && m.job) {
        const wp = s.workpieces.get(m.job.wpId)!;
        const f = Math.min(1, m.job.progress / m.job.duration);
        put(wp, bx + QC_SPAN.from + (QC_SPAN.to - QC_SPAN.from) * f, 0.84 + geos[wp.product].half, bz);
      }
    }

    for (const c of s.conveyors) {
      const dx = (c.end.x - c.start.x) / c.length;
      const dz = (c.end.z - c.start.z) / c.length;
      const yaw = Math.atan2(dx, dz) + Math.PI / 2;
      for (const it of c.items) {
        const wp = s.workpieces.get(it.wpId);
        if (!wp) continue;
        put(wp, c.start.x + dx * it.s, BELT_TOP + geos[wp.product].half, c.start.z + dz * it.s, yaw);
      }
    }

    for (const a of s.agvs) {
      if (!a.load) continue;
      const wp = s.workpieces.get(a.load);
      if (!wp) continue;
      put(wp, a.pos.x, 0.4 + geos[wp.product].half, a.pos.z, a.heading + Math.PI / 2);
    }

    for (const [key, mesh] of Object.entries(refs.current)) {
      if (!mesh) continue;
      mesh.count = counts[key] ?? 0;
      mesh.instanceMatrix.needsUpdate = true;
    }
  });

  return (
    <group>
      {PRODUCT_IDS.flatMap((p) =>
        FINISHES.map((f) => {
          const key = `${p}:${f}`;
          const mat = f === 'raw' ? WP_MAT.raw : f === 'machined' ? WP_MAT.machined : finMats[p];
          return (
            <instancedMesh
              key={key}
              ref={(r) => {
                refs.current[key] = r;
                if (r) r.frustumCulled = false;
              }}
              args={[geos[p].geo, mat, MAX]}
              castShadow
            />
          );
        }),
      )}
    </group>
  );
}

import { RoundedBox } from '@react-three/drei';
import { useFrame } from '@react-three/fiber';
import { useMemo, useRef } from 'react';
import * as THREE from 'three';
import type { MachineStatus, SceneMachine as Machine } from '../twin/types';
import { plant } from '../store/factoryStore';
import { STATUS_COLOR } from '../ui/format';
import { Selectable, StackLight, useAnimClock, type LampState } from './common';
import { ledMaterial, MAT } from './materials';
import { ROBOT_CELL, robotPhase, robotTips } from './runtime';

const lamps = (st: MachineStatus): [LampState, LampState, LampState, LampState] => [
  { on: st === 'FAULT' || st === 'REPAIR', blink: st === 'FAULT' },
  { on: st === 'IDLE' || st === 'BLOCKED' || st === 'SETUP' || st === 'STOPPED', blink: st === 'BLOCKED' },
  { on: st === 'RUNNING' },
  { on: st === 'MAINTENANCE' || st === 'REPAIR', blink: st === 'MAINTENANCE' },
];

function useMachine(id: string) {
  const idx = plant.state.machines.findIndex((m) => m.id === id);
  return () => plant.state.machines[idx] ?? plant.machine(id)!;
}

const color = new THREE.Color();

/* ====================================================================== */
/* CNC machining centre                                                    */
/* ====================================================================== */

export function CncMachine({ id }: { id: string }) {
  const get = useMachine(id);
  const m0 = get();
  const anim = useAnimClock();
  const spindle = useRef<THREE.Group>(null);
  const tool = useRef<THREE.Mesh>(null);
  const screen = useMemo(() => ledMaterial('#22c55e', 1.2), []);
  const glow = useMemo(() => ledMaterial('#cfe8ff', 1), []);
  const coolant = useMemo(() => ledMaterial('#38bdf8', 0), []);
  const isLathe = m0.capabilities.length === 1 && m0.capabilities[0] === 'DREHEN';

  useFrame((_, dt) => {
    const m = get();
    screen.emissive.set(STATUS_COLOR[m.status]);
    screen.emissiveIntensity = m.status === 'FAULT' ? 1.6 + Math.sin(anim.current * 10) * 0.6 : 1.3;
    const running = m.status === 'RUNNING';
    glow.emissive.set(m.status === 'FAULT' ? '#ff4d4d' : '#d6ecff');
    glow.emissiveIntensity = THREE.MathUtils.damp(glow.emissiveIntensity, running ? 2.4 : m.status === 'SETUP' ? 1.2 : 0.35, 4, dt);
    coolant.emissiveIntensity = running && m.config.coolant ? 0.9 + Math.sin(anim.current * 18) * 0.25 : 0;
    const t = anim.current;
    if (spindle.current) {
      const tx = running ? (isLathe ? Math.sin(t * 0.7) * 0.35 : Math.sin(t * 0.9) * 0.45) : 0;
      const tz = running && !isLathe ? Math.cos(t * 0.63) * 0.2 : 0;
      const ty = running ? 0 : 0.25;
      spindle.current.position.x = THREE.MathUtils.damp(spindle.current.position.x, tx, 5, dt);
      spindle.current.position.z = THREE.MathUtils.damp(spindle.current.position.z, tz, 5, dt);
      spindle.current.position.y = THREE.MathUtils.damp(spindle.current.position.y, ty, 3, dt);
    }
    if (tool.current) tool.current.rotation.y += dt * (m.sensors.spindle / 60) * 0.6 * (plant.state.running ? 1 : 0);
  });

  return (
    <Selectable sel={{ type: 'machine', id }}>
      <group position={[m0.position.x, 0, m0.position.z]} rotation-y={m0.rotation}>
        {/* plinth + lower body */}
        <mesh position={[0, 0.1, 0]} material={MAT.machineDark} castShadow receiveShadow>
          <boxGeometry args={[3.4, 0.2, 2.9]} />
        </mesh>
        <RoundedBox args={[3.2, 1.05, 2.7]} radius={0.06} smoothness={3} position={[0, 0.72, 0]} material={MAT.machineBody} castShadow receiveShadow />
        {/* hollow upper enclosure */}
        <mesh position={[0, 1.9, -1.31]} material={MAT.machineBody} castShadow>
          <boxGeometry args={[3.2, 1.4, 0.08]} />
        </mesh>
        <mesh position={[-1.56, 1.9, 0]} material={MAT.machineBody} castShadow>
          <boxGeometry args={[0.08, 1.4, 2.7]} />
        </mesh>
        <mesh position={[1.56, 1.9, 0]} material={MAT.machineBody} castShadow>
          <boxGeometry args={[0.08, 1.4, 2.7]} />
        </mesh>
        <mesh position={[0, 2.62, 0]} material={MAT.machineDark} castShadow>
          <boxGeometry args={[3.26, 0.16, 2.76]} />
        </mesh>
        <mesh position={[-1.3, 1.9, 1.32]} material={MAT.machineBody}>
          <boxGeometry args={[0.6, 1.4, 0.08]} />
        </mesh>
        <mesh position={[1.08, 1.9, 1.32]} material={MAT.machineBody}>
          <boxGeometry args={[1.04, 1.4, 0.08]} />
        </mesh>
        <mesh position={[-0.22, 2.48, 1.32]} material={MAT.machineBody}>
          <boxGeometry args={[1.6, 0.24, 0.08]} />
        </mesh>
        {/* accent line */}
        <mesh position={[0, 2.53, 1.39]} material={MAT.machineAccent}>
          <boxGeometry args={[3.2, 0.035, 0.02]} />
        </mesh>
        {/* glass door */}
        <mesh position={[-0.22, 1.82, 1.33]} material={MAT.glass}>
          <boxGeometry args={[1.62, 1.08, 0.02]} />
        </mesh>
        <mesh position={[-0.22, 1.25, 1.36]} material={MAT.steel}>
          <boxGeometry args={[1.7, 0.05, 0.06]} />
        </mesh>
        <mesh position={[0.45, 1.85, 1.39]} material={MAT.steelBright}>
          <boxGeometry args={[0.04, 0.5, 0.05]} />
        </mesh>
        {/* interior: ceiling light, table, spindle */}
        <mesh position={[0, 2.5, 0]} rotation-x={Math.PI / 2} material={glow}>
          <planeGeometry args={[2.2, 0.15]} />
        </mesh>
        <mesh position={[0, 1.31, 0]} material={MAT.steel} receiveShadow>
          <boxGeometry args={[2.4, 0.08, 1.4]} />
        </mesh>
        <mesh position={[0, 1.25, -1.2]} material={MAT.steelDark}>
          <boxGeometry args={[3.0, 0.5, 0.15]} />
        </mesh>
        {isLathe ? (
          <group>
            <mesh position={[-0.9, 1.78, 0]} rotation-z={Math.PI / 2} material={MAT.steelDark}>
              <cylinderGeometry args={[0.32, 0.32, 0.3, 24]} />
            </mesh>
            <group ref={spindle}>
              <mesh position={[0.4, 1.95, 0.25]} material={MAT.machineDark}>
                <boxGeometry args={[0.35, 0.3, 0.35]} />
              </mesh>
              <mesh ref={tool} position={[0.4, 1.72, 0.25]} material={MAT.steelBright}>
                <cylinderGeometry args={[0.03, 0.05, 0.2, 6]} />
              </mesh>
            </group>
          </group>
        ) : (
          <group ref={spindle}>
            <mesh position={[0, 2.2, 0]} material={MAT.machineDark}>
              <boxGeometry args={[0.5, 0.45, 0.5]} />
            </mesh>
            <mesh position={[0, 1.92, 0]} material={MAT.steel}>
              <cylinderGeometry args={[0.13, 0.16, 0.16, 20]} />
            </mesh>
            <mesh ref={tool} position={[0, 1.76, 0]} material={MAT.steelBright}>
              <cylinderGeometry args={[0.03, 0.03, 0.18, 6]} />
            </mesh>
            <mesh position={[0.18, 1.82, 0.12]} material={coolant}>
              <sphereGeometry args={[0.025, 6, 6]} />
            </mesh>
          </group>
        )}
        {/* control panel */}
        <group position={[1.12, 1.82, 1.42]} rotation-x={-0.2}>
          <mesh material={MAT.machineDark}>
            <boxGeometry args={[0.62, 0.78, 0.1]} />
          </mesh>
          <mesh position={[0, 0.14, 0.052]} material={screen}>
            <planeGeometry args={[0.5, 0.32]} />
          </mesh>
          {[-0.18, -0.06, 0.06, 0.18].map((x) => (
            <mesh key={x} position={[x, -0.2, 0.06]} material={MAT.steel}>
              <cylinderGeometry args={[0.025, 0.025, 0.02, 10]} />
            </mesh>
          ))}
          <mesh position={[0.2, -0.3, 0.06]} rotation-x={Math.PI / 2}>
            <cylinderGeometry args={[0.04, 0.04, 0.03, 12]} />
            <meshStandardMaterial color="#d92020" />
          </mesh>
        </group>
        <StackLight position={[1.35, 2.82, -1.05]} get={() => lamps(get().status)} />
        {/* chip conveyor + coolant tank */}
        <mesh position={[-1.95, 0.5, -0.2]} material={MAT.machineDark} castShadow>
          <boxGeometry args={[0.5, 0.8, 1.8]} />
        </mesh>
        <mesh position={[-2.05, 0.28, 1.0]} material={MAT.steelDark} castShadow>
          <boxGeometry args={[0.6, 0.45, 0.6]} />
        </mesh>
        <mesh position={[0, 0.45, -1.75]} material={MAT.machineDark} castShadow>
          <boxGeometry args={[2.2, 0.9, 0.6]} />
        </mesh>
        {/* buffer tables (in = blue, out = green) */}
        <BufferTable x={-1.25} z={3.1} accent="#38bdf8" />
        <BufferTable x={1.25} z={3.1} accent="#22c55e" />
      </group>
    </Selectable>
  );
}

function BufferTable({ x, z, accent }: { x: number; z: number; accent: string }) {
  return (
    <group position={[x, 0, z]}>
      <mesh position={[0, 0.78, 0]} material={MAT.steel} castShadow receiveShadow>
        <boxGeometry args={[1.2, 0.05, 0.8]} />
      </mesh>
      <mesh position={[0, 0.74, 0.41]}>
        <boxGeometry args={[1.2, 0.05, 0.01]} />
        <meshStandardMaterial color={accent} emissive={accent} emissiveIntensity={0.8} toneMapped={false} />
      </mesh>
      {[
        [-0.55, -0.35],
        [0.55, -0.35],
        [-0.55, 0.35],
        [0.55, 0.35],
      ].map(([lx, lz]) => (
        <mesh key={`${lx}${lz}`} position={[lx, 0.38, lz]} material={MAT.steelDark}>
          <boxGeometry args={[0.05, 0.76, 0.05]} />
        </mesh>
      ))}
    </group>
  );
}

/* ====================================================================== */
/* 6-axis robot with analytic IK                                           */
/* ====================================================================== */

const SHOULDER_H = 0.92;
const L1 = 1.15;
const L2 = 1.0;
const L3 = 0.38;

function solveIk(target: THREE.Vector3) {
  const yaw = Math.atan2(-target.z, target.x);
  const r = Math.hypot(target.x, target.z);
  const px = r;
  const py = target.y + L3 - SHOULDER_H;
  let d = Math.hypot(px, py);
  d = THREE.MathUtils.clamp(d, Math.abs(L1 - L2) + 0.02, L1 + L2 - 0.02);
  const cosE = (d * d - L1 * L1 - L2 * L2) / (2 * L1 * L2);
  const th2 = Math.acos(THREE.MathUtils.clamp(cosE, -1, 1));
  const a1 = Math.atan2(py, px) + Math.atan2(L2 * Math.sin(th2), L1 + L2 * Math.cos(th2));
  const a2 = -th2;
  const a3 = -Math.PI / 2 - a1 - a2;
  return { yaw, a1, a2, a3 };
}

const smooth = (t: number) => t * t * (3 - 2 * t);
const v3 = new THREE.Vector3();
const lift = (a: THREE.Vector3, b: THREE.Vector3, t: number, h = 0.45) => {
  const k = smooth(THREE.MathUtils.clamp(t, 0, 1));
  return v3.copy(a).lerp(b, k).setY(a.y + (b.y - a.y) * k + Math.sin(Math.PI * k) * h);
};

export function RobotCell({ id }: { id: string }) {
  const get = useMachine(id);
  const m0 = get();
  const anim = useAnimClock();
  const base = useRef<THREE.Group>(null);
  const shoulder = useRef<THREE.Group>(null);
  const elbow = useRef<THREE.Group>(null);
  const wrist = useRef<THREE.Group>(null);
  const fingerA = useRef<THREE.Mesh>(null);
  const fingerB = useRef<THREE.Mesh>(null);
  const tip = useRef<THREE.Object3D>(null);
  const spark = useRef<THREE.PointLight>(null);
  const sparkMat = useMemo(() => ledMaterial('#ffd27a', 0), []);
  const joints = useRef({ yaw: 0.3, a1: 1.2, a2: -1.9, a3: -0.8 });
  const target = useMemo(() => new THREE.Vector3(), []);
  if (!robotTips.has(id)) robotTips.set(id, new THREE.Vector3(m0.position.x, 1.2, m0.position.z));

  useFrame((_, dt) => {
    const m = get();
    const { phase, f } = robotPhase(m);
    const P = ROBOT_CELL;
    let grip = 0; // 0 open, 1 closed
    let working = false;
    const frozen = m.status === 'FAULT' || m.status === 'STOPPED' || m.status === 'REPAIR';
    if (!frozen) {
      switch (phase) {
        case 'home': {
          const wob = Math.sin(anim.current * 0.6) * 0.06;
          target.copy(P.home).add(v3.set(wob, 0, -wob));
          break;
        }
        case 'pick':
          if (m.status === 'SETUP') {
            target.copy(P.home).add(v3.set(Math.sin(anim.current * 2) * 0.15, 0.1, 0));
          } else if (f < 0.12) {
            target.copy(lift(P.home, v3.copy(P.pick).setY(P.pick.y + 0.35), f / 0.12, 0.2));
          } else {
            const k = (f - 0.12) / 0.06;
            target.copy(P.pick).setY(P.pick.y + 0.35 * (1 - smooth(Math.min(1, k * 1.4))));
            grip = k > 0.75 ? 1 : 0;
          }
          break;
        case 'carry':
          target.copy(lift(P.pick, P.fixture, (f - 0.18) / 0.14));
          grip = 1;
          break;
        case 'work': {
          const k = (f - 0.32) / 0.46;
          const ang = k * Math.PI * 6;
          target.copy(P.fixture).add(v3.set(Math.cos(ang) * 0.12, 0.18 + Math.sin(ang * 2) * 0.03, Math.sin(ang) * 0.12));
          working = true;
          break;
        }
        case 'place':
          if (m.status === 'BLOCKED') {
            target.copy(P.place).setY(P.place.y + 0.25);
            grip = 1;
          } else {
            const k = (f - 0.78) / 0.22;
            target.copy(lift(P.fixture, P.place, Math.min(1, k * 1.25)));
            grip = k < 0.95 ? 1 : 0;
          }
          break;
      }
      const ik = solveIk(target);
      const j = joints.current;
      const lam = 9;
      let dy = ik.yaw - j.yaw;
      while (dy > Math.PI) dy -= Math.PI * 2;
      while (dy < -Math.PI) dy += Math.PI * 2;
      j.yaw += dy * (1 - Math.exp(-lam * dt));
      j.a1 = THREE.MathUtils.damp(j.a1, ik.a1, lam, dt);
      j.a2 = THREE.MathUtils.damp(j.a2, ik.a2, lam, dt);
      j.a3 = THREE.MathUtils.damp(j.a3, ik.a3, lam, dt);
    }
    const j = joints.current;
    if (base.current) base.current.rotation.y = j.yaw;
    if (shoulder.current) shoulder.current.rotation.z = j.a1;
    if (elbow.current) elbow.current.rotation.z = j.a2;
    if (wrist.current) wrist.current.rotation.z = j.a3;
    const open = grip ? 0.045 : 0.11;
    if (fingerA.current) fingerA.current.position.z = THREE.MathUtils.damp(fingerA.current.position.z, open, 12, dt);
    if (fingerB.current) fingerB.current.position.z = THREE.MathUtils.damp(fingerB.current.position.z, -open, 12, dt);
    if (tip.current) tip.current.getWorldPosition(robotTips.get(id)!);
    const flicker = working && m.status === 'RUNNING' && plant.state.running ? 0.6 + Math.random() * 2.4 : 0;
    sparkMat.emissiveIntensity = flicker * 2;
    if (spark.current) spark.current.intensity = flicker * 1.2;
  });

  return (
    <Selectable sel={{ type: 'machine', id }}>
      <group position={[m0.position.x, 0, m0.position.z]}>
        {/* base plate & pedestal */}
        <mesh position={[0, 0.04, 0]} material={MAT.robotDark} receiveShadow>
          <boxGeometry args={[1.1, 0.08, 1.1]} />
        </mesh>
        <mesh position={[0, 0.3, 0]} material={MAT.robotDark} castShadow>
          <cylinderGeometry args={[0.32, 0.38, 0.45, 24]} />
        </mesh>
        <group ref={base} position={[0, 0.55, 0]}>
          <mesh position={[0, 0.12, 0]} material={MAT.robot} castShadow>
            <cylinderGeometry args={[0.3, 0.32, 0.24, 24]} />
          </mesh>
          <mesh position={[0, 0.32, 0]} material={MAT.robot} castShadow>
            <boxGeometry args={[0.36, 0.3, 0.42]} />
          </mesh>
          <group ref={shoulder} position={[0, SHOULDER_H - 0.55, 0]}>
            <mesh rotation-x={Math.PI / 2} material={MAT.robotDark}>
              <cylinderGeometry args={[0.16, 0.16, 0.5, 20]} />
            </mesh>
            <mesh position={[L1 / 2, 0, 0]} material={MAT.robot} castShadow>
              <boxGeometry args={[L1, 0.22, 0.26]} />
            </mesh>
            <group ref={elbow} position={[L1, 0, 0]}>
              <mesh rotation-x={Math.PI / 2} material={MAT.robotDark}>
                <cylinderGeometry args={[0.13, 0.13, 0.36, 20]} />
              </mesh>
              <mesh position={[L2 / 2, 0, 0]} material={MAT.robot} castShadow>
                <boxGeometry args={[L2, 0.16, 0.18]} />
              </mesh>
              <group ref={wrist} position={[L2, 0, 0]}>
                <mesh rotation-x={Math.PI / 2} material={MAT.robotDark}>
                  <cylinderGeometry args={[0.08, 0.08, 0.22, 16]} />
                </mesh>
                <mesh position={[0.14, 0, 0]} rotation-z={Math.PI / 2} material={MAT.steel}>
                  <cylinderGeometry args={[0.07, 0.07, 0.16, 16]} />
                </mesh>
                <mesh position={[0.25, 0, 0]} material={MAT.robotDark}>
                  <boxGeometry args={[0.06, 0.1, 0.28]} />
                </mesh>
                <mesh ref={fingerA} position={[0.33, 0, 0.11]} material={MAT.steelBright}>
                  <boxGeometry args={[0.12, 0.05, 0.025]} />
                </mesh>
                <mesh ref={fingerB} position={[0.33, 0, -0.11]} material={MAT.steelBright}>
                  <boxGeometry args={[0.12, 0.05, 0.025]} />
                </mesh>
                <object3D ref={tip} position={[L3, 0, 0]} />
                <mesh position={[L3 + 0.02, 0, 0]} material={sparkMat}>
                  <sphereGeometry args={[0.035, 8, 8]} />
                </mesh>
              </group>
            </group>
          </group>
        </group>
        <pointLight ref={spark} position={[ROBOT_CELL.fixture.x, 1.5, ROBOT_CELL.fixture.z]} color="#ffc46b" distance={4} intensity={0} />
        {/* assembly fixture */}
        <group position={[ROBOT_CELL.fixture.x, 0, ROBOT_CELL.fixture.z]}>
          <mesh position={[0, 0.5, 0]} material={MAT.steelDark} castShadow>
            <boxGeometry args={[0.9, 1.0, 0.8]} />
          </mesh>
          <mesh position={[0, 1.02, 0]} material={MAT.steel}>
            <boxGeometry args={[0.95, 0.05, 0.85]} />
          </mesh>
        </group>
        {/* controller cabinet + safety fence */}
        <mesh position={[0.2, 0.9, -1.85]} material={MAT.machineDark} castShadow>
          <boxGeometry args={[0.9, 1.8, 0.55]} />
        </mesh>
        <StackLight position={[0.55, 1.95, -1.85]} get={() => lamps(get().status)} />
        <Fence />
      </group>
    </Selectable>
  );
}

function Fence() {
  // Fence on the far side and flanks of the cell (open towards conveyors), relative to robot base.
  const posts: [number, number][] = [
    [-2.5, -2.0],
    [-0.8, -2.0],
    [0.9, -2.0],
    [2.6, -2.0],
    [2.6, -0.4],
    [2.6, 1.2],
  ];
  return (
    <group>
      {posts.map(([x, z]) => (
        <mesh key={`${x}${z}`} position={[x, 0.9, z]} material={MAT.yellow} castShadow>
          <boxGeometry args={[0.06, 1.8, 0.06]} />
        </mesh>
      ))}
      <mesh position={[0.05, 0.95, -2.0]}>
        <boxGeometry args={[5.1, 1.5, 0.02]} />
        <meshStandardMaterial color="#1c232b" transparent opacity={0.45} metalness={0.5} roughness={0.5} depthWrite={false} />
      </mesh>
      <mesh position={[2.6, 0.95, -0.4]}>
        <boxGeometry args={[0.02, 1.5, 3.2]} />
        <meshStandardMaterial color="#1c232b" transparent opacity={0.45} metalness={0.5} roughness={0.5} depthWrite={false} />
      </mesh>
    </group>
  );
}

/* ====================================================================== */
/* Quality inspection cell                                                 */
/* ====================================================================== */

export const QC_SPAN = { from: -1.4, to: 1.4 };

export function QcStation({ id }: { id: string }) {
  const get = useMachine(id);
  const m0 = get();
  const anim = useAnimClock();
  const head = useRef<THREE.Group>(null);
  const curtain = useMemo(() => ledMaterial('#38bdf8', 0.5), []);
  const verdict = useMemo(() => ledMaterial('#22c55e', 0), []);
  const scanLine = useMemo(() => ledMaterial('#ff3b3b', 0), []);

  useFrame((_, dt) => {
    const m: Machine = get();
    const running = m.status === 'RUNNING';
    if (head.current) {
      const f = m.job ? Math.min(1, m.job.progress / m.job.duration) : 0;
      const x = running ? QC_SPAN.from + (QC_SPAN.to - QC_SPAN.from) * f : 0;
      head.current.position.x = THREE.MathUtils.damp(head.current.position.x, x, 6, dt);
    }
    curtain.emissiveIntensity = running ? 1.4 + Math.sin(anim.current * 8) * 0.4 : m.status === 'FAULT' ? 0 : 0.35;
    curtain.emissive.set(m.status === 'FAULT' ? '#ef4444' : '#38bdf8');
    scanLine.emissiveIntensity = running ? 3 : 0;
    const v = m.lastVerdict;
    const age = v ? plant.state.time - v.at : 99;
    if (v && age < 6) {
      color.set(v.ok ? '#22c55e' : '#ef4444');
      verdict.emissive.copy(color);
      verdict.emissiveIntensity = 3 * (1 - age / 6);
    } else verdict.emissiveIntensity = 0.05;
  });

  return (
    <Selectable sel={{ type: 'machine', id }}>
      <group position={[m0.position.x, 0, m0.position.z]}>
        {/* internal belt */}
        <mesh position={[0, 0.8, 0]} material={MAT.rubber} receiveShadow>
          <boxGeometry args={[3.4, 0.08, 0.7]} />
        </mesh>
        <mesh position={[0, 0.4, 0]} material={MAT.steelDark}>
          <boxGeometry args={[3.2, 0.75, 0.6]} />
        </mesh>
        {/* gantry */}
        {[-1.6, 1.6].map((x) => (
          <group key={x}>
            <mesh position={[x, 1.2, -0.75]} material={MAT.machineBody} castShadow>
              <boxGeometry args={[0.18, 2.4, 0.18]} />
            </mesh>
            <mesh position={[x, 1.2, 0.75]} material={MAT.machineBody} castShadow>
              <boxGeometry args={[0.18, 2.4, 0.18]} />
            </mesh>
            <mesh position={[x, 1.3, 0.62]} material={curtain}>
              <boxGeometry args={[0.04, 1.0, 0.04]} />
            </mesh>
            <mesh position={[x, 1.3, -0.62]} material={curtain}>
              <boxGeometry args={[0.04, 1.0, 0.04]} />
            </mesh>
          </group>
        ))}
        <mesh position={[0, 2.45, -0.75]} material={MAT.machineBody} castShadow>
          <boxGeometry args={[3.4, 0.16, 0.18]} />
        </mesh>
        <mesh position={[0, 2.45, 0.75]} material={MAT.machineBody} castShadow>
          <boxGeometry args={[3.4, 0.16, 0.18]} />
        </mesh>
        <mesh position={[0, 2.62, 0]} material={MAT.machineDark} castShadow>
          <boxGeometry args={[3.5, 0.14, 1.7]} />
        </mesh>
        <group ref={head}>
          <mesh position={[0, 2.25, 0]} material={MAT.machineDark}>
            <boxGeometry args={[0.36, 0.28, 1.4]} />
          </mesh>
          <mesh position={[0, 2.06, 0]} material={MAT.steelBright}>
            <cylinderGeometry args={[0.07, 0.09, 0.14, 16]} />
          </mesh>
          <mesh position={[0, 0.86, 0]} material={scanLine}>
            <boxGeometry args={[0.015, 0.005, 0.66]} />
          </mesh>
        </group>
        {/* verdict light + operator screen */}
        <mesh position={[1.75, 2.5, 0]} material={verdict}>
          <boxGeometry args={[0.06, 0.18, 1.3]} />
        </mesh>
        <mesh position={[-1.9, 1.1, -1.1]} material={MAT.machineDark} castShadow>
          <boxGeometry args={[0.6, 2.2, 0.5]} />
        </mesh>
        <StackLight position={[-1.9, 2.32, -1.1]} get={() => lamps(get().status)} />
        {/* scrap bin */}
        <ScrapBin />
      </group>
    </Selectable>
  );
}

function ScrapBin() {
  const fill = useRef<THREE.Mesh>(null);
  useFrame(() => {
    const n = plant.state.inventory.scrap;
    const h = Math.min(0.7, 0.04 + n * 0.02);
    if (fill.current) {
      fill.current.scale.y = h;
      fill.current.position.y = 0.08 + h / 2;
    }
  });
  return (
    <group position={[0, 0, 2.1]}>
      <mesh position={[0, 0.42, 0]}>
        <boxGeometry args={[1.2, 0.84, 0.8]} />
        <meshStandardMaterial color="#7f1d1d" roughness={0.6} metalness={0.3} transparent opacity={0.85} />
      </mesh>
      <mesh ref={fill} position={[0, 0.1, 0]}>
        <boxGeometry args={[1.1, 1, 0.7]} />
        <meshStandardMaterial color="#3b3f45" roughness={0.9} />
      </mesh>
      <mesh position={[0, 0.86, 0.41]} material={MAT.hazard}>
        <boxGeometry args={[1.2, 0.08, 0.01]} />
      </mesh>
    </group>
  );
}

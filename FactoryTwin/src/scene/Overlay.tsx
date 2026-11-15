import { CameraControls, Html } from '@react-three/drei';
import { useFrame, useThree } from '@react-three/fiber';
import { useEffect, useMemo, useRef } from 'react';
import * as THREE from 'three';
import { plant, sceneOf, useFactory, type CameraPreset, type Selection } from '../store/factoryStore';
import { labelPortal } from './labelPortal';
import { AGV_COLOR, AGV_LABEL, CONVEYOR_COLOR, CONVEYOR_LABEL, STATUS_COLOR, STATUS_SHORT } from '../ui/format';

/* ------------------------------------------------------------------ */
/* Footprints of selectable assets                                     */
/* ------------------------------------------------------------------ */

export function footprint(sel: Selection): { x: number; z: number; w: number; d: number; yaw: number; h: number } | null {
  if (sel.type === 'machine') {
    const m = plant.machine(sel.id);
    if (!m) return null;
    if (m.kind === 'cnc') return { x: m.position.x - 0.15, z: m.position.z + 1.1, w: 5.0, d: 5.4, yaw: 0, h: 3.2 };
    if (m.kind === 'robot') return { x: m.position.x + 0.05, z: m.position.z + 0.2, w: 5.2, d: 4.2, yaw: 0, h: 2.6 };
    return { x: m.position.x, z: m.position.z + 0.6, w: 4.2, d: 3.6, yaw: 0, h: 3 };
  }
  if (sel.type === 'conveyor') {
    const c = plant.conveyor(sel.id);
    if (!c) return null;
    return { x: (c.start.x + c.end.x) / 2, z: (c.start.z + c.end.z) / 2, w: 1.1, d: c.length + 0.5, yaw: Math.atan2(c.end.x - c.start.x, c.end.z - c.start.z), h: 1.3 };
  }
  if (sel.type === 'agv') {
    const a = plant.agv(sel.id);
    if (!a) return null;
    return { x: a.pos.x, z: a.pos.z, w: 1.3, d: 1.8, yaw: a.heading, h: 1 };
  }
  if (sel.type === 'raw') return { x: -26.5, z: 0, w: 8.5, d: 25.4, yaw: 0, h: 5.5 };
  return { x: 26.5, z: 0, w: 8.5, d: 25.4, yaw: 0, h: 5.5 };
}

function Highlight({ sel, strong }: { sel: Selection; strong: boolean }) {
  const g = useRef<THREE.Group>(null);
  const mat = useMemo(
    () => new THREE.MeshBasicMaterial({ color: '#5eead4', transparent: true, opacity: 0.9, depthWrite: false, toneMapped: false }),
    [],
  );
  const fill = useMemo(() => new THREE.MeshBasicMaterial({ color: '#5eead4', transparent: true, opacity: 0.06, depthWrite: false }), []);
  const geo = useMemo(() => new THREE.BoxGeometry(1, 0.01, 1), []);
  useFrame(({ clock }) => {
    const fp = footprint(sel);
    if (!fp || !g.current) return;
    g.current.position.set(fp.x, 0.02, fp.z);
    g.current.rotation.y = fp.yaw;
    g.current.scale.set(1, 1, 1);
    const kids = g.current.children as THREE.Mesh[];
    const t = 0.07;
    kids[0].position.set(0, 0, -fp.d / 2);
    kids[0].scale.set(fp.w, 1, t);
    kids[1].position.set(0, 0, fp.d / 2);
    kids[1].scale.set(fp.w, 1, t);
    kids[2].position.set(-fp.w / 2, 0, 0);
    kids[2].scale.set(t, 1, fp.d);
    kids[3].position.set(fp.w / 2, 0, 0);
    kids[3].scale.set(t, 1, fp.d);
    kids[4].scale.set(fp.w, 1, fp.d);
    mat.opacity = strong ? 0.65 + Math.sin(clock.elapsedTime * 4) * 0.3 : 0.35;
    fill.opacity = strong ? 0.08 : 0.04;
  });
  return (
    <group ref={g}>
      {[0, 1, 2, 3].map((i) => (
        <mesh key={i} geometry={geo} material={mat} />
      ))}
      <mesh geometry={geo} material={fill} />
    </group>
  );
}

export function SelectionOverlay() {
  const selected = useFactory((s) => s.selected);
  const hovered = useFactory((s) => s.hovered);
  const hoverSel = useMemo<Selection | null>(() => {
    if (!hovered || hovered === selected?.id) return null;
    if (hovered === 'RAW') return { type: 'raw', id: 'RAW' };
    if (hovered === 'FG') return { type: 'finished', id: 'FG' };
    if (plant.machine(hovered)) return { type: 'machine', id: hovered };
    if (plant.conveyor(hovered)) return { type: 'conveyor', id: hovered };
    if (plant.agv(hovered)) return { type: 'agv', id: hovered };
    return null;
  }, [hovered, selected]);
  return (
    <>
      {selected && <Highlight key={`s-${selected.id}`} sel={selected} strong />}
      {hoverSel && <Highlight key={`h-${hoverSel.id}`} sel={hoverSel} strong={false} />}
    </>
  );
}

/* ------------------------------------------------------------------ */
/* Floating labels                                                     */
/* ------------------------------------------------------------------ */

function Tag({
  name,
  status,
  color,
  progress,
  active,
  visible = true,
}: {
  name: string;
  status: string;
  color: string;
  progress?: number;
  active: boolean;
  visible?: boolean;
}) {
  return (
    <div
      aria-hidden="true"
      className={`pointer-events-none select-none whitespace-nowrap rounded-md border px-2 py-1 text-[10px] leading-tight shadow-lg backdrop-blur-md transition-colors ${
        active ? 'border-teal-300/60 bg-[#0b1116]/90' : 'border-white/10 bg-[#0b0f14]/75'
      } ${visible ? '' : 'hidden'}`}
    >
      <div className="flex items-center gap-1.5">
        <span className="h-1.5 w-1.5 rounded-full" style={{ background: color, boxShadow: `0 0 6px ${color}` }} />
        <span className="font-mono font-semibold tracking-wide text-slate-100">{name}</span>
        <span className="text-slate-400">{status}</span>
      </div>
      <div className={`mt-1 h-[2px] w-full overflow-hidden rounded bg-white/10 ${progress === undefined ? 'hidden' : ''}`}>
        <div className="h-full rounded" style={{ width: `${Math.round((progress ?? 0) * 100)}%`, background: color }} />
      </div>
    </div>
  );
}

/**
 * Floating labels. Every <Html> stays mounted for the lifetime of the scene and is only hidden
 * with CSS: each <Html> owns a separate React root, and unmounting those roots while the parent
 * renders makes React try to remove nodes that are already gone.
 */
export function Labels() {
  const show = useFactory((s) => s.showLabels);
  const machines = useFactory((s) => sceneOf(s).machines);
  const conveyors = useFactory((s) => sceneOf(s).conveyors);
  const selected = useFactory((s) => s.selected);
  const hovered = useFactory((s) => s.hovered);
  const focus = (id: string) => selected?.id === id || hovered === id;
  return (
    <group>
      {machines.map((m) => (
        <Html portal={labelPortal} key={m.id} position={[m.position.x, m.kind === 'robot' ? 3.2 : 3.5, m.position.z]} center zIndexRange={[20, 0]}>
          <Tag
            name={m.id}
            status={STATUS_SHORT[m.status]}
            color={STATUS_COLOR[m.status]}
            progress={m.job && !m.job.finished ? Math.min(1, m.job.progress / m.job.duration) : undefined}
            active={focus(m.id)}
            visible={show || focus(m.id)}
          />
        </Html>
      ))}
      {conveyors.map((c) => (
        <Html portal={labelPortal} key={c.id} position={[(c.start.x + c.end.x) / 2, 1.5, (c.start.z + c.end.z) / 2]} center zIndexRange={[20, 0]}>
          <Tag
            name={c.id}
            status={`${CONVEYOR_LABEL[c.status]}, ${c.items.length} Teile`}
            color={CONVEYOR_COLOR[c.status]}
            active={focus(c.id)}
            visible={focus(c.id) || (show && c.status !== 'RUNNING')}
          />
        </Html>
      ))}
      <Html portal={labelPortal} position={[-27.6, 6, -12.5]} center zIndexRange={[20, 0]}>
        <ZoneTag title="Rohlager" kind="raw" visible={show || focus('RAW')} />
      </Html>
      <Html portal={labelPortal} position={[27.6, 6, -12.5]} center zIndexRange={[20, 0]}>
        <ZoneTag title="Fertigwarenlager" kind="finished" visible={show || focus('FG')} />
      </Html>
      <AgvLabels show={show} focusId={hovered ?? selected?.id ?? null} />
    </group>
  );
}

function ZoneTag({ title, kind, visible }: { title: string; kind: 'raw' | 'finished'; visible: boolean }) {
  const inv = useFactory((s) => sceneOf(s).inventory);
  const n = kind === 'raw' ? inv.raw : Object.values(inv.finished).reduce((a, b) => a + b, 0);
  const cap = kind === 'raw' ? inv.rawCapacity : inv.finishedCapacity;
  return (
    <div
      aria-hidden="true"
      className={`pointer-events-none select-none whitespace-nowrap rounded-md border border-white/10 bg-[#0b0f14]/75 px-2 py-1 text-[10px] text-slate-300 backdrop-blur-md ${visible ? '' : 'hidden'}`}
    >
      <span className="font-semibold uppercase tracking-[0.14em] text-slate-100">{title}</span>
      <span className="ml-2 font-mono text-slate-400">
        {n}/{cap}
      </span>
    </div>
  );
}

function AgvLabels({ show, focusId }: { show: boolean; focusId: string | null }) {
  const agvs = useFactory((s) => sceneOf(s).agvs);
  return (
    <>
      {agvs.map((a) => (
        <AgvLabel
          key={a.id}
          id={a.id}
          state={a.fault ? 'Störung' : AGV_LABEL[a.state]}
          color={a.fault ? '#ef4444' : AGV_COLOR[a.state]}
          battery={a.battery}
          visible={show || focusId === a.id}
        />
      ))}
    </>
  );
}

function AgvLabel({ id, state, color, battery, visible }: { id: string; state: string; color: string; battery: number; visible: boolean }) {
  const g = useRef<THREE.Group>(null);
  useFrame(() => {
    const a = plant.state.agvs.find((x) => x.id === id);
    if (a && g.current) g.current.position.set(a.pos.x, 1.4, a.pos.z);
  });
  return (
    <group ref={g}>
      <Html portal={labelPortal} center zIndexRange={[20, 0]}>
        <div
          aria-hidden="true"
          className={`pointer-events-none select-none whitespace-nowrap rounded-md border border-white/10 bg-[#0b0f14]/75 px-1.5 py-0.5 text-[10px] backdrop-blur-md ${visible ? '' : 'hidden'}`}
        >
          <span className="mr-1 inline-block h-1.5 w-1.5 rounded-full" style={{ background: color }} />
          <span className="font-mono font-semibold text-slate-100">{id}</span>
          <span className="ml-1 text-slate-400">{state}</span>
          <span className={`ml-1 font-mono ${battery < 25 ? 'text-amber-400' : 'text-slate-500'}`}>{Math.round(battery)}%</span>
        </div>
      </Html>
    </group>
  );
}

/* ------------------------------------------------------------------ */
/* Camera                                                               */
/* ------------------------------------------------------------------ */

/** Camera flights are spatial context, but they are skipped for users who ask for reduced motion. */
const reducedMotion = () => window.matchMedia('(prefers-reduced-motion: reduce)').matches;

const PRESETS: Record<Exclude<CameraPreset, 'focus'>, [number, number, number, number, number, number]> = {
  overview: [24, 30, 40, 0, 0, 1.5],
  machining: [2, 11, 4, 0, 1, -9.5],
  assembly: [4, 11, 27, 1, 0.5, 10],
  logistics: [-12, 16, 22, -24, 0, 0],
  top: [0, 62, 0.01, 0, 0, 0],
};

export function CameraRig() {
  const ref = useRef<CameraControls>(null);
  const camera = useFactory((s) => s.camera);
  const { size } = useThree();
  const portrait = size.width < size.height;

  useEffect(() => {
    const c = ref.current;
    if (!c) return;
    if (camera.preset === 'focus') {
      const selected = useFactory.getState().selected;
      if (!selected) return;
      const fp = footprint(selected);
      if (!fp) return;
      const dist = Math.max(7, Math.max(fp.w, fp.d) * 1.25) * (portrait ? 1.6 : 1);
      void c.setLookAt(fp.x + dist * 0.55, fp.h + dist * 0.75, fp.z + dist * 0.9, fp.x, fp.h * 0.35, fp.z, !reducedMotion());
      return;
    }
    const p = PRESETS[camera.preset];
    const k = portrait ? 1.7 : 1;
    void c.setLookAt(p[3] + (p[0] - p[3]) * k, p[4] + (p[1] - p[4]) * k, p[5] + (p[2] - p[5]) * k, p[3], p[4], p[5], camera.nonce > 0 && !reducedMotion());
  }, [camera, portrait]);

  return (
    <CameraControls
      ref={ref}
      makeDefault
      minDistance={3}
      maxDistance={120}
      maxPolarAngle={Math.PI * 0.47}
      smoothTime={0.45}
      draggingSmoothTime={0.12}
      dollyToCursor
    />
  );
}

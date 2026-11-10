import * as THREE from 'three';

/** Shared, procedurally generated materials (no external assets → GitHub Pages friendly). */

function canvasTexture(size: number, draw: (ctx: CanvasRenderingContext2D, s: number) => void): THREE.CanvasTexture {
  const c = document.createElement('canvas');
  c.width = c.height = size;
  const ctx = c.getContext('2d')!;
  draw(ctx, size);
  const t = new THREE.CanvasTexture(c);
  t.wrapS = t.wrapT = THREE.RepeatWrapping;
  t.anisotropy = 8;
  t.colorSpace = THREE.SRGBColorSpace;
  return t;
}

function seeded(seed: number) {
  let s = seed;
  return () => {
    s = (s * 16807) % 2147483647;
    return s / 2147483647;
  };
}

/** Polished concrete with saw-cut joints every 4 m (texture covers 4 × 4 m). */
export const floorTexture = canvasTexture(1024, (ctx, s) => {
  const rnd = seeded(7);
  ctx.fillStyle = '#23272d';
  ctx.fillRect(0, 0, s, s);
  for (let i = 0; i < 9000; i++) {
    const v = 30 + rnd() * 18;
    ctx.fillStyle = `rgba(${v},${v + 2},${v + 5},${0.18 + rnd() * 0.2})`;
    const r = rnd() * 3 + 0.5;
    ctx.fillRect(rnd() * s, rnd() * s, r, r);
  }
  for (let i = 0; i < 14; i++) {
    const g = ctx.createRadialGradient(rnd() * s, rnd() * s, 0, rnd() * s, rnd() * s, 120 + rnd() * 220);
    g.addColorStop(0, 'rgba(255,255,255,0.025)');
    g.addColorStop(1, 'rgba(255,255,255,0)');
    ctx.fillStyle = g;
    ctx.fillRect(0, 0, s, s);
  }
  ctx.strokeStyle = 'rgba(0,0,0,0.55)';
  ctx.lineWidth = 3;
  ctx.strokeRect(0, 0, s, s);
});

export const floorRoughness = canvasTexture(256, (ctx, s) => {
  const rnd = seeded(13);
  ctx.fillStyle = '#b4b4b4';
  ctx.fillRect(0, 0, s, s);
  for (let i = 0; i < 2500; i++) {
    const v = 120 + rnd() * 120;
    ctx.fillStyle = `rgb(${v},${v},${v})`;
    ctx.fillRect(rnd() * s, rnd() * s, 2, 2);
  }
});

/** Conveyor belt cleats: 1 texture repeat = 0.5 m. */
export function createBeltTexture() {
  const t = canvasTexture(128, (ctx, s) => {
    ctx.fillStyle = '#16181c';
    ctx.fillRect(0, 0, s, s);
    ctx.fillStyle = '#2a2e35';
    ctx.fillRect(0, 0, s, s * 0.14);
    ctx.fillStyle = 'rgba(255,255,255,0.03)';
    for (let i = 0; i < 40; i++) ctx.fillRect((i * 37) % s, (i * 53) % s, 2, 2);
  });
  return t;
}

export const hazardTexture = canvasTexture(128, (ctx, s) => {
  ctx.fillStyle = '#111';
  ctx.fillRect(0, 0, s, s);
  ctx.fillStyle = '#e8b40e';
  for (let i = -s; i < s * 2; i += 32) {
    ctx.beginPath();
    ctx.moveTo(i, 0);
    ctx.lineTo(i + 16, 0);
    ctx.lineTo(i + 16 - s, s);
    ctx.lineTo(i - s, s);
    ctx.closePath();
    ctx.fill();
  }
});

const std = (color: string, roughness: number, metalness: number, extra: Partial<THREE.MeshStandardMaterialParameters> = {}) =>
  new THREE.MeshStandardMaterial({ color, roughness, metalness, ...extra });

export const MAT = {
  floor: std('#ffffff', 0.78, 0.05, { map: floorTexture, roughnessMap: floorRoughness }),
  wall: std('#14181e', 0.92, 0.1),
  wallPanel: std('#1a1f26', 0.8, 0.3),
  steelDark: std('#2b3038', 0.55, 0.7),
  steel: std('#8a929d', 0.38, 0.85),
  steelBright: std('#c9cfd6', 0.25, 0.95),
  machineBody: std('#c4c9d0', 0.42, 0.35),
  machineDark: std('#1d2128', 0.6, 0.4),
  machineAccent: std('#2dd4bf', 0.35, 0.2),
  rubber: std('#0e1013', 0.9, 0),
  robot: std('#f27a1a', 0.38, 0.25),
  robotDark: std('#2a2d33', 0.5, 0.6),
  rackUpright: std('#2f5d98', 0.5, 0.5),
  rackBeam: std('#e8782a', 0.45, 0.4),
  pallet: std('#7c6040', 0.85, 0),
  crate: std('#6f685d', 0.8, 0.05),
  crateLid: std('#5b554c', 0.8, 0.05),
  yellow: std('#e8b40e', 0.5, 0.1),
  hazard: std('#ffffff', 0.6, 0.1, { map: hazardTexture }),
  glass: new THREE.MeshPhysicalMaterial({
    color: '#9fc6dd',
    roughness: 0.05,
    metalness: 0,
    transparent: true,
    opacity: 0.28,
    depthWrite: false,
  }),
  agvBody: std('#262a31', 0.45, 0.55),
  agvDeck: std('#a7aeb8', 0.4, 0.7),
  lampOn: new THREE.MeshStandardMaterial({ color: '#ffffff', emissive: '#e8f1ff', emissiveIntensity: 2.2, toneMapped: false }),
  laneLine: new THREE.MeshBasicMaterial({ color: '#2b8fb3', transparent: true, opacity: 0.55, depthWrite: false }),
  laneArrow: new THREE.MeshBasicMaterial({ color: '#38bdf8', transparent: true, opacity: 0.65, depthWrite: false }),
  zoneFill: new THREE.MeshBasicMaterial({ color: '#2dd4bf', transparent: true, opacity: 0.035, depthWrite: false }),
  zoneLine: new THREE.MeshBasicMaterial({ color: '#e8b40e', transparent: true, opacity: 0.6, depthWrite: false }),
  walkway: new THREE.MeshBasicMaterial({ color: '#16a34a', transparent: true, opacity: 0.12, depthWrite: false }),
};

/** Emissive "LED" material whose colour/intensity is driven per frame. */
export function ledMaterial(color = '#22c55e', intensity = 2) {
  return new THREE.MeshStandardMaterial({ color: '#111', emissive: color, emissiveIntensity: intensity, toneMapped: false, roughness: 0.4 });
}

/** Workpiece materials: raw blank, machined, finished (product colour). */
export const WP_MAT = {
  raw: std('#5d6168', 0.85, 0.45),
  machined: std('#d3d8de', 0.22, 0.95),
};

export function finishedMaterial(color: string) {
  return std(color, 0.35, 0.55);
}

import { useLayoutEffect, useMemo, useRef, useState } from 'react';
import { clock } from '../format';

export interface RefLine {
  value: number;
  label: string;
  color: string;
}

function niceTicks(min: number, max: number, count = 3): number[] {
  const span = max - min || 1;
  const raw = span / count;
  const mag = Math.pow(10, Math.floor(Math.log10(raw)));
  const step = [1, 2, 2.5, 5, 10].map((m) => m * mag).find((s) => span / s <= count + 0.5) ?? mag * 10;
  const out: number[] = [];
  for (let v = Math.ceil(min / step) * step; v <= max + 1e-9; v += step) out.push(Number(v.toFixed(6)));
  return out;
}

/**
 * Single-series time chart (line + soft area) with crosshair tooltip.
 * One measure per chart (no dual axis); reference lines are dashed and labelled in text ink.
 */
export function TimeChart({
  t,
  values,
  height = 110,
  color = '#3987e5',
  unit = '',
  digits = 0,
  min,
  max,
  refs = [],
  label,
}: {
  t: number[];
  values: number[];
  height?: number;
  color?: string;
  unit?: string;
  digits?: number;
  min?: number;
  max?: number;
  refs?: RefLine[];
  label: string;
}) {
  const wrap = useRef<HTMLDivElement>(null);
  const [w, setW] = useState(300);
  const [hover, setHover] = useState<number | null>(null);
  useLayoutEffect(() => {
    const el = wrap.current;
    if (!el) return;
    const ro = new ResizeObserver(([e]) => setW(Math.max(120, e.contentRect.width)));
    ro.observe(el);
    return () => ro.disconnect();
  }, []);

  const pad = { l: 34, r: 8, t: 8, b: 18 };
  const iw = w - pad.l - pad.r;
  const ih = height - pad.t - pad.b;

  const geo = useMemo(() => {
    if (values.length < 2) return null;
    const refVals = refs.map((r) => r.value);
    let lo = min ?? Math.min(...values, ...refVals);
    let hi = max ?? Math.max(...values, ...refVals);
    if (min === undefined) lo = lo - (hi - lo) * 0.12;
    if (max === undefined) hi = hi + (hi - lo) * 0.12;
    if (hi - lo < 1e-6) hi = lo + 1;
    if (min === undefined && lo < 0 && Math.min(...values) >= 0) lo = 0;
    const t0 = t[0];
    const t1 = t[t.length - 1];
    const x = (tt: number) => pad.l + ((tt - t0) / Math.max(1, t1 - t0)) * iw;
    const y = (v: number) => pad.t + ih - ((v - lo) / (hi - lo)) * ih;
    let d = '';
    values.forEach((v, i) => (d += `${i ? 'L' : 'M'}${x(t[i]).toFixed(1)},${y(v).toFixed(1)}`));
    const area = `${d}L${x(t1).toFixed(1)},${pad.t + ih}L${x(t0).toFixed(1)},${pad.t + ih}Z`;
    const ticks = niceTicks(lo, hi, 3).filter((v) => v >= lo && v <= hi);
    const xt = [t0, t0 + (t1 - t0) / 2, t1];
    return { x, y, d, area, ticks, xt, lo, hi };
  }, [t, values, min, max, refs, iw, ih, pad.l, pad.t]);

  const gid = useMemo(() => `g${Math.random().toString(36).slice(2)}`, []);
  const fmt = (v: number) => v.toLocaleString('de-DE', { minimumFractionDigits: digits, maximumFractionDigits: digits });

  const onMove = (e: React.PointerEvent<SVGRectElement>) => {
    if (!geo) return;
    const r = e.currentTarget.getBoundingClientRect();
    const px = e.clientX - r.left + pad.l;
    let best = 0;
    let bd = Infinity;
    t.forEach((tt, i) => {
      const dd = Math.abs(geo.x(tt) - px);
      if (dd < bd) {
        bd = dd;
        best = i;
      }
    });
    setHover(best);
  };

  return (
    <div ref={wrap} className="relative w-full select-none" style={{ height }} role="img" aria-label={label}>
      {!geo ? (
        <div className="flex h-full items-center justify-center text-[11px] text-txt-3">Daten werden gesammelt …</div>
      ) : (
        <svg width={w} height={height} className="block overflow-visible">
          <defs>
            <linearGradient id={gid} x1="0" x2="0" y1="0" y2="1">
              <stop offset="0" stopColor={color} stopOpacity="0.22" />
              <stop offset="1" stopColor={color} stopOpacity="0" />
            </linearGradient>
          </defs>
          {geo.ticks.map((v) => (
            <g key={v}>
              <line x1={pad.l} x2={w - pad.r} y1={geo.y(v)} y2={geo.y(v)} stroke="#1e2630" strokeWidth={1} />
              <text x={pad.l - 6} y={geo.y(v) + 3} textAnchor="end" className="fill-txt-3 font-mono text-[10px]">
                {fmt(v)}
              </text>
            </g>
          ))}
          {geo.xt.map((tt, i) => (
            <text
              key={i}
              x={geo.x(tt)}
              y={height - 4}
              textAnchor={i === 0 ? 'start' : i === 2 ? 'end' : 'middle'}
              className="fill-txt-3 font-mono text-[10px]"
            >
              {clock(tt, false)}
            </text>
          ))}
          {refs.map((r) => (
            <g key={r.label}>
              <line x1={pad.l} x2={w - pad.r} y1={geo.y(r.value)} y2={geo.y(r.value)} stroke={r.color} strokeWidth={1} strokeDasharray="3 3" opacity={0.8} />
              <text x={w - pad.r} y={geo.y(r.value) - 3} textAnchor="end" className="fill-txt-2 text-[10px]">
                {r.label}
              </text>
            </g>
          ))}
          <path d={geo.area} fill={`url(#${gid})`} />
          <path d={geo.d} fill="none" stroke={color} strokeWidth={2} strokeLinejoin="round" strokeLinecap="round" />
          {hover === null && (
            <circle cx={geo.x(t[t.length - 1])} cy={geo.y(values[values.length - 1])} r={3.5} fill={color} stroke="#0e1217" strokeWidth={2} />
          )}
          {hover !== null && (
            <g>
              <line x1={geo.x(t[hover])} x2={geo.x(t[hover])} y1={pad.t} y2={pad.t + ih} stroke="#a3adb9" strokeWidth={1} opacity={0.5} />
              <circle cx={geo.x(t[hover])} cy={geo.y(values[hover])} r={4} fill={color} stroke="#0e1217" strokeWidth={2} />
            </g>
          )}
          <rect
            x={pad.l}
            y={0}
            width={iw}
            height={height}
            fill="transparent"
            onPointerMove={onMove}
            onPointerLeave={() => setHover(null)}
            style={{ cursor: 'crosshair' }}
          />
        </svg>
      )}
      {geo && hover !== null && (
        <div
          className="pointer-events-none absolute top-0 z-10 rounded-md border border-line-2 bg-ink-1/95 px-2 py-1 text-[10px] shadow-xl"
          style={{ left: Math.min(Math.max(geo.x(t[hover]) - 50, 0), w - 104), width: 104 }}
        >
          <div className="font-mono text-txt-3">{clock(t[hover])}</div>
          <div className="font-mono text-[11px] text-txt-1 tnum">
            {fmt(values[hover])}
            <span className="ml-0.5 text-txt-3">{unit}</span>
          </div>
        </div>
      )}
    </div>
  );
}

/** Compact trend line for stat tiles (no axes). */
export function Sparkline({ values, color = '#3987e5', height = 26 }: { values: number[]; color?: string; height?: number }) {
  const w = 100;
  if (values.length < 2) return <div style={{ height }} />;
  const lo = Math.min(...values);
  const hi = Math.max(...values);
  const span = hi - lo || 1;
  const pts = values.map((v, i) => [(i / (values.length - 1)) * w, height - 3 - ((v - lo) / span) * (height - 6)] as const);
  const d = pts.map(([x, y], i) => `${i ? 'L' : 'M'}${x.toFixed(1)},${y.toFixed(1)}`).join('');
  return (
    <svg viewBox={`0 0 ${w} ${height}`} preserveAspectRatio="none" className="block w-full" style={{ height }} aria-hidden>
      <path d={d} fill="none" stroke={color} strokeWidth={1.5} vectorEffect="non-scaling-stroke" strokeLinejoin="round" opacity={0.9} />
    </svg>
  );
}

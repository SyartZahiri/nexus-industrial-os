import type { ReactNode } from 'react';

/** One key figure: a small label over a large monospaced value. tone colours the value only for a state. */
export function Stat({
  label,
  value,
  unit,
  hint,
  tone,
}: {
  label: string;
  value: ReactNode;
  unit?: string;
  hint?: ReactNode;
  tone?: string;
}) {
  return (
    <div className="min-w-0">
      <div className="text-[11px] uppercase tracking-[0.08em] text-txt-3">{label}</div>
      <div className="mt-1 flex items-baseline gap-1.5">
        <span className="font-mono text-[32px] leading-none tracking-tight tnum" style={{ color: tone }}>
          {value}
        </span>
        {unit && <span className="text-[13px] text-txt-2">{unit}</span>}
      </div>
      {hint && <div className="mt-1.5 text-[12px] text-txt-3">{hint}</div>}
    </div>
  );
}

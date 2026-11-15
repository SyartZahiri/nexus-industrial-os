import type { ReactNode } from 'react';

/** Horizontal bars, neutral grey: a value is not a state. null shows a dash and no bar. */
export function BarList({
  rows,
  max,
  label,
}: {
  rows: { id: string; label: ReactNode; value: number | null; text: string }[];
  max: number;
  label: string;
}) {
  return (
    <ul aria-label={label} className="flex flex-col">
      {rows.map((r) => (
        <li
          key={r.id}
          className="grid grid-cols-[minmax(0,1fr)_auto] items-center gap-x-3 gap-y-1.5 border-b border-line py-2 text-[12px] last:border-b-0 sm:grid-cols-[minmax(0,140px)_minmax(0,1fr)_auto]"
        >
          <span className="min-w-0 truncate text-txt-1">{r.label}</span>
          {/* below 640 px the bar takes its own row under label and value, so a long value never squeezes it away */}
          <span className="col-span-2 row-start-2 h-1.5 rounded-sm bg-ink-3 sm:col-span-1 sm:col-start-2 sm:row-start-1" aria-hidden="true">
            {r.value !== null && (
              <span className="block h-full rounded-sm bg-txt-2" style={{ width: `${Math.max(0, Math.min(1, r.value / max)) * 100}%` }} />
            )}
          </span>
          <span className="col-start-2 row-start-1 text-right font-mono tnum text-txt-1 sm:col-start-3">{r.value === null ? '–' : r.text}</span>
        </li>
      ))}
    </ul>
  );
}

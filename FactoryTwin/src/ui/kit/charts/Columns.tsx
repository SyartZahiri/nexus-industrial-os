import { niceMax } from './scale';

/** Columns over equal sections; a null section stays empty (no data, not zero). */
export function Columns({
  values,
  labels,
  label,
  format,
}: {
  values: (number | null)[];
  labels: string[];
  label: string;
  format: (v: number) => string;
}) {
  const top = niceMax(Math.max(0, ...values.filter((v): v is number => v !== null)));
  const text = values.map((v, i) => `${labels[i] || 'früher'} ${v === null ? '–' : format(v)}`).join(', ');
  return (
    <figure className="min-w-0">
      <div role="img" aria-label={`${label}: ${text}`} className="flex h-32 items-end gap-1 border-b border-line">
        {values.map((v, i) => (
          <div key={i} className="flex h-full min-w-0 flex-1 items-end" title={`${labels[i] || 'früher'}: ${v === null ? '–' : format(v)}`}>
            {v !== null && <div className="w-full rounded-t-sm bg-txt-2" style={{ height: `${(v / top) * 100}%`, minHeight: v > 0 ? 2 : 0 }} />}
          </div>
        ))}
      </div>
      <div className="mt-1 flex justify-between font-mono text-[11px] text-txt-3 tnum" aria-hidden="true">
        <span>{labels.find((l) => l) ?? ''}</span>
        <span>max {format(top)}</span>
        <span>{labels[labels.length - 1] ?? ''}</span>
      </div>
    </figure>
  );
}

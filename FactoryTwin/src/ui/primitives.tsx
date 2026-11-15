import type { ButtonHTMLAttributes, ReactNode } from 'react';

export function cx(...c: (string | false | null | undefined)[]) {
  return c.filter(Boolean).join(' ');
}

type Variant = 'default' | 'primary' | 'danger' | 'ghost' | 'warn';

export function Button({
  variant = 'default',
  size = 'md',
  className,
  children,
  ...rest
}: ButtonHTMLAttributes<HTMLButtonElement> & { variant?: Variant; size?: 'sm' | 'md' }) {
  const v: Record<Variant, string> = {
    default: 'border-line-2 bg-ink-3 text-txt-1 hover:bg-ink-4 hover:border-txt-3',
    primary: 'border-transparent bg-txt-1 text-ink-1 hover:bg-white',
    danger: 'border-red-500/40 bg-red-500/10 text-red-300 hover:bg-red-500/20',
    warn: 'border-amber-500/40 bg-amber-500/10 text-amber-200 hover:bg-amber-500/20',
    ghost: 'border-transparent bg-transparent text-txt-2 hover:bg-white/5 hover:text-txt-1',
  };
  return (
    <button
      className={cx(
        'inline-flex select-none items-center justify-center gap-1.5 rounded-md border font-medium',
        'transition-[transform,background-color,border-color,color] duration-150 ease-[var(--ease-out)] active:scale-[0.97]',
        'disabled:pointer-events-none disabled:opacity-35',
        size === 'sm' ? 'h-7 px-2 text-[11px]' : 'h-8 px-3 text-xs',
        v[variant],
        className,
      )}
      {...rest}
    >
      {children}
    </button>
  );
}

export function SectionTitle({ children, right }: { children: ReactNode; right?: ReactNode }) {
  return (
    <div className="mb-2 flex items-center justify-between gap-2">
      <h2 className="text-[11px] font-semibold uppercase tracking-[0.08em] text-txt-3">{children}</h2>
      {right}
    </div>
  );
}

export function Card({ children, className }: { children: ReactNode; className?: string }) {
  // a group of the detail panel: a hairline above, no box (Spec 14: no cards inside sections)
  return <div className={cx('border-t border-line pt-3', className)}>{children}</div>;
}

export function StatusDot({ color, pulse = false, size = 8 }: { color: string; pulse?: boolean; size?: number }) {
  return (
    <span
      className={cx('inline-block shrink-0 rounded-full', pulse && 'pulse-dot')}
      style={{ width: size, height: size, background: color, color }}
    />
  );
}

export function Badge({ color, children }: { color: string; children: ReactNode }) {
  return (
    <span
      className="inline-flex items-center gap-1 whitespace-nowrap rounded border px-1.5 py-[1px] text-[11px] font-medium text-txt-1"
      style={{ borderColor: `${color}55`, background: `${color}14` }}
    >
      <span className="h-1.5 w-1.5 rounded-full" style={{ background: color }} />
      {children}
    </span>
  );
}

export function Toggle({ checked, onChange, label, hint }: { checked: boolean; onChange: (v: boolean) => void; label: string; hint?: string }) {
  return (
    <label className="flex cursor-pointer items-start justify-between gap-3 py-1.5">
      <span className="min-w-0">
        <span className="block text-xs text-txt-1">{label}</span>
        {hint && <span className="mt-0.5 block text-[11px] leading-snug text-txt-3">{hint}</span>}
      </span>
      <button
        type="button"
        role="switch"
        aria-checked={checked}
        aria-label={label}
        onClick={() => onChange(!checked)}
        className={cx(
          'relative mt-0.5 block h-[18px] w-8 shrink-0 rounded-full border p-0 transition-colors duration-150',
          checked ? 'border-txt-3 bg-ink-4' : 'border-line-2 bg-ink-2',
        )}
      >
        <span
          className={cx(
            'pointer-events-none absolute left-0 top-[2px] h-3 w-3 rounded-full transition-transform duration-200 ease-[var(--ease-out)]',
            // track is 32px incl. 1px border → 30px inside; knob 12px with 2px inset on both ends
            checked ? 'translate-x-[16px] bg-txt-1' : 'translate-x-[2px] bg-txt-3',
          )}
        />
      </button>
    </label>
  );
}

export function Segmented<T extends string | number>({
  value,
  options,
  onChange,
  size = 'md',
  ariaLabel,
}: {
  value: T;
  options: { value: T; label: ReactNode; title?: string }[];
  onChange: (v: T) => void;
  size?: 'sm' | 'md';
  ariaLabel?: string;
}) {
  return (
    <div className="inline-flex max-w-full flex-wrap rounded-md border border-line-2 bg-ink-1 p-0.5" role="group" aria-label={ariaLabel}>
      {options.map((o) => (
        <button
          key={String(o.value)}
          type="button"
          title={o.title}
          aria-label={typeof o.label === 'string' ? o.label : undefined}
          aria-pressed={o.value === value}
          onClick={() => onChange(o.value)}
          className={cx(
            'whitespace-nowrap rounded-[4px] font-medium transition-[background-color,color] duration-150 active:scale-[0.97]',
            size === 'sm' ? 'min-h-6 px-1.5 text-[11px]' : 'min-h-7 px-2 text-[11px]',
            o.value === value ? 'bg-ink-4 text-txt-1 shadow-[inset_0_0_0_1px_var(--color-line-2)]' : 'text-txt-3 hover:text-txt-1',
          )}
        >
          {o.label}
        </button>
      ))}
    </div>
  );
}

export function Slider({
  label,
  value,
  min,
  max,
  step = 1,
  unit,
  onChange,
  format,
}: {
  label: string;
  value: number;
  min: number;
  max: number;
  step?: number;
  unit?: string;
  onChange: (v: number) => void;
  format?: (v: number) => string;
}) {
  return (
    <div className="py-1.5">
      <div className="mb-1.5 flex items-baseline justify-between text-xs">
        <span className="text-txt-2">{label}</span>
        <span className="font-mono tnum text-txt-1">
          {format ? format(value) : value}
          {unit && <span className="ml-0.5 text-txt-3">{unit}</span>}
        </span>
      </div>
      <input
        type="range"
        aria-label={label}
        className="w-full"
        min={min}
        max={max}
        step={step}
        value={value}
        onChange={(e) => onChange(Number(e.target.value))}
        style={{ background: `linear-gradient(to right, var(--color-txt-2) ${((value - min) / (max - min)) * 100}%, var(--color-line) 0)` /* hard stop: a filled track, not a blend */ }}
      />
    </div>
  );
}

/** Horizontal meter; fill colour carries the state, track is a dim step of the same hue. */
export function Meter({ value, color = 'var(--color-txt-2)', height = 4 }: { value: number; color?: string; height?: number }) {
  const v = Math.max(0, Math.min(1, value));
  return (
    <div className="w-full overflow-hidden rounded-full" style={{ height, background: 'var(--color-ink-3)' }} aria-hidden="true">
      {/* transform instead of width: compositor-only, no layout per update */}
      <div
        className="h-full w-full rounded-full transition-transform duration-300 ease-[var(--ease-out)]"
        style={{ transform: `translateX(${(v - 1) * 100}%)`, background: color }}
      />
    </div>
  );
}

export function KV({ k, v, mono = true }: { k: ReactNode; v: ReactNode; mono?: boolean }) {
  return (
    <div className="flex items-baseline justify-between gap-3 py-[3px] text-xs">
      <span className="text-txt-3">{k}</span>
      <span className={cx('text-right text-txt-1', mono && 'font-mono tnum')}>{v}</span>
    </div>
  );
}

export const Icon = {
  play: (
    <svg viewBox="0 0 16 16" className="h-3.5 w-3.5" fill="currentColor">
      <path d="M4.5 2.8v10.4a.6.6 0 0 0 .9.5l8.2-5.2a.6.6 0 0 0 0-1L5.4 2.3a.6.6 0 0 0-.9.5Z" />
    </svg>
  ),
  pause: (
    <svg viewBox="0 0 16 16" className="h-3.5 w-3.5" fill="currentColor">
      <rect x="3.5" y="2.5" width="3" height="11" rx="0.8" />
      <rect x="9.5" y="2.5" width="3" height="11" rx="0.8" />
    </svg>
  ),
  reset: (
    <svg viewBox="0 0 16 16" className="h-3.5 w-3.5" fill="none" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round">
      <path d="M2.8 8a5.2 5.2 0 1 0 1.6-3.8" />
      <path d="M2.5 2.5v3h3" />
    </svg>
  ),
  step: (
    <svg viewBox="0 0 16 16" className="h-3.5 w-3.5" fill="currentColor">
      <path d="M3 3v10l7-5-7-5Z" />
      <rect x="11" y="3" width="2" height="10" rx="0.6" />
    </svg>
  ),
  close: (
    <svg viewBox="0 0 16 16" className="h-3.5 w-3.5" fill="none" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round">
      <path d="M4 4l8 8M12 4l-8 8" />
    </svg>
  ),
  focus: (
    <svg viewBox="0 0 16 16" className="h-3.5 w-3.5" fill="none" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round">
      <path d="M2 5.5V3a1 1 0 0 1 1-1h2.5M10.5 2H13a1 1 0 0 1 1 1v2.5M14 10.5V13a1 1 0 0 1-1 1h-2.5M5.5 14H3a1 1 0 0 1-1-1v-2.5" />
      <circle cx="8" cy="8" r="2" />
    </svg>
  ),
  panelLeft: (
    <svg viewBox="0 0 16 16" className="h-4 w-4" fill="none" stroke="currentColor" strokeWidth="1.4">
      <rect x="2" y="2.5" width="12" height="11" rx="1.5" />
      <path d="M6 2.5v11" />
    </svg>
  ),
  panelRight: (
    <svg viewBox="0 0 16 16" className="h-4 w-4" fill="none" stroke="currentColor" strokeWidth="1.4">
      <rect x="2" y="2.5" width="12" height="11" rx="1.5" />
      <path d="M10 2.5v11" />
    </svg>
  ),
  log: (
    <svg viewBox="0 0 16 16" className="h-4 w-4" fill="none" stroke="currentColor" strokeWidth="1.4" strokeLinecap="round">
      <path d="M3 4h10M3 8h10M3 12h6" />
    </svg>
  ),
  download: (
    <svg viewBox="0 0 16 16" className="h-3.5 w-3.5" fill="none" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round">
      <path d="M8 2.5v8M4.5 7 8 10.5 11.5 7M3 13.5h10" />
    </svg>
  ),
  alert: (
    <svg viewBox="0 0 16 16" className="h-3.5 w-3.5" fill="none" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round">
      <path d="M8 2.2 1.8 13h12.4L8 2.2Z" />
      <path d="M8 6.5v3M8 11.3v.2" />
    </svg>
  ),
  check: (
    <svg viewBox="0 0 16 16" className="h-3.5 w-3.5" fill="none" stroke="currentColor" strokeWidth="1.7" strokeLinecap="round" strokeLinejoin="round">
      <path d="m3.5 8.5 3 3 6-7" />
    </svg>
  ),
  bolt: (
    <svg viewBox="0 0 16 16" className="h-3.5 w-3.5" fill="currentColor">
      <path d="M9 1.5 3.5 9H7l-1 5.5L12.5 7H9l0-5.5Z" />
    </svg>
  ),
  wrench: (
    <svg viewBox="0 0 16 16" className="h-3.5 w-3.5" fill="none" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round">
      <path d="M10.5 2.2a3.3 3.3 0 0 0-3.9 4.4L2.4 10.8a1.3 1.3 0 0 0 1.8 1.8l4.2-4.2a3.3 3.3 0 0 0 4.4-3.9l-2 2-1.8-.5-.5-1.8 2-2Z" />
    </svg>
  ),
  stop: (
    <svg viewBox="0 0 16 16" className="h-3.5 w-3.5" fill="currentColor">
      <rect x="3.5" y="3.5" width="9" height="9" rx="1.2" />
    </svg>
  ),
  plus: (
    <svg viewBox="0 0 16 16" className="h-3.5 w-3.5" fill="none" stroke="currentColor" strokeWidth="1.7" strokeLinecap="round">
      <path d="M8 3v10M3 8h10" />
    </svg>
  ),
};

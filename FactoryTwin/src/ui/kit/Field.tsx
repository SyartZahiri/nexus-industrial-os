import type { ReactNode } from 'react';

/** Classes for an input or select: hairline, no fill change on focus beyond the ring. */
export const control =
  'h-8 w-full min-w-0 rounded-md border border-line-2 bg-ink-2 px-2 text-[13px] text-txt-1 placeholder:text-txt-3 focus:border-accent focus:outline-none';

/** A labelled form control; the label sits above, small and quiet. */
export function Field({ label, children, className }: { label: string; children: ReactNode; className?: string }) {
  return (
    <label className={`flex min-w-0 flex-col gap-1 ${className ?? ''}`}>
      <span className="text-[11px] uppercase tracking-[0.08em] text-txt-3">{label}</span>
      {children}
    </label>
  );
}

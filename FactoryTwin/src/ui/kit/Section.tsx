import type { ReactNode } from 'react';

/** A part of a page: hairline above, title on the left, an optional action on the right. No box around it. */
export function Section({
  title,
  action,
  children,
  className,
}: {
  title: string;
  action?: ReactNode;
  children: ReactNode;
  className?: string;
}) {
  return (
    <section className={`border-t border-line pt-4 ${className ?? ''}`} aria-label={title}>
      <div className="mb-3 flex items-baseline justify-between gap-3">
        <h2 className="text-[15px] font-semibold tracking-tight text-txt-1">{title}</h2>
        {action}
      </div>
      {children}
    </section>
  );
}

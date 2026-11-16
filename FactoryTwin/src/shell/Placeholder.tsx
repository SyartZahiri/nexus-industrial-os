import type { Area, AreaId } from './areas';
import { areaById } from './areas';
import { hrefOf } from './route';

/** Where the work of a coming area can already be seen today. */
const MEANWHILE: Partial<Record<AreaId, { to: AreaId; text: string }>> = {};

/** An area a later milestone builds: what it is for, when it comes, and where to look meanwhile. */
export function Placeholder({ area }: { area: Area }) {
  const meanwhile = MEANWHILE[area.id];
  return (
    <div className="max-w-[640px] px-4 py-10 lg:px-6">
      <p className="text-[15px] text-txt-1">{area.summary}</p>
      {area.comesWith && <p className="mt-2 text-[13px] text-txt-3">Dieser Bereich kommt mit {area.comesWith}.</p>}
      {meanwhile && (
        <p className="mt-6 border-t border-line pt-4 text-[13px] text-txt-2">
          {meanwhile.text}{' '}
          <a href={hrefOf(meanwhile.to)} className="text-txt-1 underline decoration-line-2 underline-offset-4 hover:decoration-accent">
            Zu {areaById(meanwhile.to).label}
          </a>
        </p>
      )}
    </div>
  );
}

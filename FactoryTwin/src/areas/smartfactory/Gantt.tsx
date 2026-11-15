import type { Schedule } from '../../nexus/types';
import { hrefOf } from '../../shell/route';
import { clock, STATE_HEX } from '../../ui/format';
import { ganttLayout } from './ganttLayout';

/** Planned operations per machine; late orders are outlined in the waiting colour, setup is the lighter part. */
export function Gantt({ schedule, nowMs, machineIds }: { schedule: Schedule; nowMs: number; machineIds: string[] }) {
  const g = ganttLayout(schedule, nowMs, machineIds);
  return (
    <div className="min-w-0">
      <div className="mb-1 flex justify-between pl-[88px] font-mono text-[11px] text-txt-3 tnum" aria-hidden="true">
        <span>{clock(g.fromMs / 1000, false)}</span>
        <span>{clock(g.toMs / 1000, false)}</span>
      </div>
      <ul aria-label="Plan je Maschine">
        {g.lanes.map((lane) => (
          <li key={lane.machineId} className="grid grid-cols-[80px_minmax(0,1fr)] items-center gap-2 border-b border-line py-1.5 last:border-b-0">
            <span className="font-mono text-[12px] text-txt-1">{lane.machineId}</span>
            <div className="relative h-5 rounded-sm bg-ink-2">
              <span className="absolute inset-y-0 w-px bg-txt-3" style={{ left: `${g.nowLeft}%` }} aria-hidden="true" />
              {lane.bars.map((b) => (
                <a
                  key={b.key}
                  href={hrefOf('smartfactory', b.orderId)}
                  className="absolute inset-y-0.5 flex overflow-hidden rounded-sm bg-ink-4 hover:bg-line-2"
                  style={{
                    left: `${b.left}%`,
                    width: `${b.width}%`,
                    outline: b.late ? `1px solid ${STATE_HEX.wait}` : undefined,
                    outlineOffset: -1,
                  }}
                  title={`${b.orderId}, Schritt ${b.seq}, ${lane.machineId} ${clock(b.startMs / 1000, false)}–${clock(b.endMs / 1000, false)}${b.setupShare > 0 ? ', mit Rüsten' : ''}${b.late ? ', verspätet' : ''}`}
                  aria-label={`${b.orderId}, Schritt ${b.seq}, ${lane.machineId} ${clock(b.startMs / 1000, false)} bis ${clock(b.endMs / 1000, false)}${b.setupShare > 0 ? ', mit Rüsten' : ''}${b.late ? ', verspätet' : ''}`}
                >
                  <span className="h-full" style={{ width: `${b.setupShare * 100}%`, background: STATE_HEX.setup }} aria-hidden="true" />
                </a>
              ))}
            </div>
          </li>
        ))}
      </ul>
    </div>
  );
}

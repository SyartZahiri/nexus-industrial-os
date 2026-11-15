import { useFactory, type SelectionType } from '../../store/factoryStore';
import type { NexusEvent } from '../../nexus/types';
import { clock, SEVERITY_COLOR } from '../format';
import { eventText } from './eventText';

const SHOWN = 200;

/** The object an event is about, if the 3D view can select it. */
function target(e: NexusEvent): { type: SelectionType; id: string } | null {
  const p = e.payload as Record<string, unknown>;
  if (typeof p.machineId === 'string') return { type: 'machine', id: p.machineId };
  if (typeof p.agvId === 'string') return { type: 'agv', id: p.agvId };
  return null;
}

/** The latest events of the run, newest first; a click selects the machine or AGV an event is about. */
export function EventLog() {
  const events = useFactory((s) => s.events);
  const select = useFactory((s) => s.select);
  const shown = events.slice(-SHOWN).reverse();
  if (shown.length === 0) {
    return <p className="py-6 text-center text-[12px] text-txt-3">Noch keine Ereignisse. Starte die Simulation.</p>;
  }
  return (
    <ol className="flex flex-col gap-px" aria-label="Ereignisse, neueste zuerst">
      {shown.map((e) => {
        const t = target(e);
        return (
          <li key={e.seq}>
            <button
              type="button"
              disabled={!t}
              onClick={() => t && select(t, true)}
              className="grid w-full grid-cols-[auto_auto_1fr] items-baseline gap-2 rounded px-1.5 py-1 text-left text-[11.5px] hover:bg-white/5 disabled:cursor-default disabled:hover:bg-transparent"
            >
              <span className="font-mono text-[10.5px] text-txt-3 tnum">{clock(e.simTimeMs / 1000)}</span>
              <span className="h-1.5 w-1.5 self-center rounded-full" style={{ background: SEVERITY_COLOR[e.severity] }} />
              <span className="min-w-0 text-txt-1">
                {eventText(e)}
                {e.correlationId && <span className="ml-1.5 font-mono text-[11px] text-txt-3">{e.correlationId}</span>}
              </span>
            </button>
          </li>
        );
      })}
    </ol>
  );
}

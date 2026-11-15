import { useFactory } from '../store/factoryStore';
import { STATE_HEX } from '../ui/format';
import { cx } from '../ui/primitives';
import type { AreaId } from './areas';
import { groupedAreas } from './groups';

/** Runtime and connection (Spec 20: the interface names the active mode), at the foot of the navigation. */
function RuntimeNote() {
  const mode = useFactory((s) => s.mode);
  const connection = useFactory((s) => s.connection);
  const seed = useFactory((s) => s.sim.seed);
  const color = connection === 'open' ? STATE_HEX.run : connection === 'connecting' ? STATE_HEX.wait : STATE_HEX.fault;
  const state = connection === 'open' ? 'verbunden' : connection === 'connecting' ? 'verbindet …' : 'getrennt';
  return (
    <div className="border-t border-line px-4 py-3 text-[12px] leading-relaxed text-txt-3">
      <div className="flex items-center gap-2 text-txt-2">
        <span className="h-1.5 w-1.5 rounded-full" style={{ background: color }} aria-hidden="true" />
        {mode === 'browser' ? 'Browser' : 'Server'} · {state}
      </div>
      <div>{mode === 'browser' ? 'Java-Kern im Web Worker' : 'PostgreSQL und OPC UA'}</div>
      <div className="font-mono tnum">Seed {seed}</div>
    </div>
  );
}

/** The left column: word mark, the eleven areas in four groups, runtime at the foot. */
export function Nav({ current, onNavigate }: { current: AreaId; onNavigate?: () => void }) {
  return (
    <nav aria-label="Bereiche" className="flex h-full flex-col">
      <div className="px-4 pb-4 pt-4">
        <div className="text-[13px] font-semibold tracking-tight text-txt-1">NEXUS</div>
        <div className="text-[11px] text-txt-3">Industrial OS</div>
      </div>
      <div className="scroll-thin min-h-0 flex-1 overflow-y-auto pb-4">
        {groupedAreas().map(({ group, areas }) => (
          <div key={group} className="mt-3">
            <div className="px-4 pb-1 text-[11px] uppercase tracking-[0.08em] text-txt-3">{group}</div>
            <ul>
              {areas.map((a) => {
                const active = a.id === current;
                return (
                  <li key={a.id}>
                    <a
                      href={a.hash}
                      onClick={onNavigate}
                      aria-current={active ? 'page' : undefined}
                      className={cx(
                        'flex items-baseline justify-between gap-2 border-l-2 py-1.5 pl-[14px] pr-4 text-[13px]',
                        active
                          ? 'border-accent text-txt-1'
                          : 'border-transparent text-txt-2 hover:bg-white/[0.03] hover:text-txt-1',
                      )}
                    >
                      <span>{a.label}</span>
                      {a.comesWith && <span className="text-[10px] text-txt-3">{a.comesWith}</span>}
                    </a>
                  </li>
                );
              })}
            </ul>
          </div>
        ))}
      </div>
      <RuntimeNote />
    </nav>
  );
}

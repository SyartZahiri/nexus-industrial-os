import { useEffect, useState } from 'react';
import { control, useFactory } from '../store/factoryStore';
import { clock, dayOf, shiftOf, STATE_HEX } from '../ui/format';
import { Button, Icon, Segmented } from '../ui/primitives';
import { hrefOf } from './route';

export const SPEEDS = [1, 2, 5, 10, 25];

/** Two-step reset: the first click asks inline, no blocking browser dialog. */
function ResetButton({ seed }: { seed: number }) {
  const [asking, setAsking] = useState(false);
  useEffect(() => {
    if (!asking) return;
    const t = setTimeout(() => setAsking(false), 4000);
    return () => clearTimeout(t);
  }, [asking]);
  if (asking)
    return (
      <span className="flex items-center gap-1" role="group" aria-label="Zurücksetzen bestätigen">
        <span className="hidden text-[12px] text-txt-2 md:inline">Neu starten?</span>
        <Button
          variant="danger"
          size="sm"
          autoFocus
          onClick={() => {
            setAsking(false);
            void control.reset();
          }}
        >
          Ja
        </Button>
        <Button variant="ghost" size="sm" onClick={() => setAsking(false)}>
          Nein
        </Button>
      </span>
    );
  return (
    <Button variant="ghost" onClick={() => setAsking(true)} title={`Zurücksetzen (Seed ${seed})`} aria-label="Simulation zurücksetzen">
      {Icon.reset}
      <span className="hidden xl:inline">Reset</span>
    </Button>
  );
}

/**
 * The bar above every area: menu (below 1024 px), area title, simulation clock and control, error and warning
 * counts. The notice line below shows why the plant refused the last command.
 */
export function SimBar({ title, onMenu }: { title: string; onMenu: () => void }) {
  const time = useFactory((s) => s.scene?.time ?? 0);
  const running = useFactory((s) => s.sim.running);
  const speed = useFactory((s) => s.sim.speed);
  const seed = useFactory((s) => s.sim.seed);
  const errors = useFactory((s) => s.events.filter((e) => e.severity === 'ERROR').length);
  const warnings = useFactory((s) => s.events.filter((e) => e.severity === 'WARNING').length);
  const notice = useFactory((s) => s.notice);
  const dismissNotice = useFactory((s) => s.dismissNotice);
  return (
    <>
      <header className="safe-top flex min-h-12 shrink-0 flex-wrap items-center gap-x-3 gap-y-2 border-b border-line bg-ink-1 px-3 py-2 lg:px-6">
        <button
          type="button"
          onClick={onMenu}
          className="rounded p-1.5 text-txt-2 hover:bg-white/5 hover:text-txt-1 lg:hidden"
          aria-label="Bereiche öffnen"
        >
          <svg viewBox="0 0 16 16" className="h-4 w-4" fill="none" stroke="currentColor" strokeWidth="1.5" aria-hidden="true">
            <path d="M2.5 4h11M2.5 8h11M2.5 12h11" strokeLinecap="round" />
          </svg>
        </button>
        <h1 className="mr-auto text-[20px] font-semibold tracking-tight text-txt-1">{title}</h1>

        <div className="flex items-baseline gap-2">
          <span className="font-mono text-[15px] font-semibold text-txt-1 tnum">{clock(time)}</span>
          <span className="hidden whitespace-nowrap text-[11px] text-txt-3 md:inline">
            Tag {dayOf(time)}, {shiftOf(time)}
          </span>
        </div>

        <div className="flex items-center gap-1.5">
          <Button
            variant={running ? 'default' : 'primary'}
            onClick={() => void (running ? control.pause() : control.play())}
            title={running ? 'Pausieren (Leertaste)' : 'Starten (Leertaste)'}
            className="w-[84px]"
          >
            {running ? Icon.pause : Icon.play}
            {running ? 'Pause' : 'Start'}
          </Button>
          <Segmented
            value={speed}
            onChange={(v) => void control.setSpeed(v)}
            ariaLabel="Simulationsgeschwindigkeit"
            options={SPEEDS.map((s) => ({ value: s, label: `${s}×`, title: `Simulationsgeschwindigkeit ${s}×` }))}
          />
          <ResetButton seed={seed} />
        </div>

        <a
          href={hrefOf('events')}
          className="flex items-center gap-3 whitespace-nowrap rounded-md px-2 py-1 text-[12px] text-txt-2 hover:bg-white/5"
          title="Fehler und Warnungen der letzten Ereignisse im Event Monitor"
        >
          <span className="flex items-center gap-1.5">
            <span className="h-1.5 w-1.5 rounded-full" style={{ background: STATE_HEX.fault }} aria-hidden="true" />
            <span className="font-mono tnum">{errors}</span>
            <span className="sr-only">Fehler</span>
          </span>
          <span className="flex items-center gap-1.5">
            <span className="h-1.5 w-1.5 rounded-full" style={{ background: STATE_HEX.wait }} aria-hidden="true" />
            <span className="font-mono tnum">{warnings}</span>
            <span className="sr-only">Warnungen</span>
          </span>
        </a>
      </header>
      {notice && (
        <div role="alert" className="flex items-center gap-3 border-b border-line bg-ink-2 px-3 py-1.5 text-[12px] text-txt-1 lg:px-6">
          <span className="h-1.5 w-1.5 shrink-0 rounded-full" style={{ background: STATE_HEX.fault }} aria-hidden="true" />
          <span className="min-w-0 flex-1">{notice}</span>
          <Button variant="ghost" size="sm" onClick={dismissNotice} aria-label="Meldung schliessen">
            Schliessen
          </Button>
        </div>
      )}
    </>
  );
}

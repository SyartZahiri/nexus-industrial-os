import { control, useFactory } from '../../store/factoryStore';
import { clock, dayOf, shiftOf } from '../format';
import { useEffect, useState } from 'react';
import { Button, cx, Icon, Segmented } from '../primitives';

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
        <span className="hidden text-[11px] text-txt-2 md:inline">Neu starten?</span>
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

export const SPEEDS = [1, 2, 5, 10, 25];

export function TopBar() {
  const time = useFactory((s) => s.scene?.time ?? 0);
  const running = useFactory((s) => s.sim.running);
  const speed = useFactory((s) => s.sim.speed);
  const seed = useFactory((s) => s.sim.seed);
  const errors = useFactory((s) => s.events.filter((e) => e.severity === 'ERROR').length);
  const warnings = useFactory((s) => s.events.filter((e) => e.severity === 'WARNING').length);
  const mode = useFactory((s) => s.mode);
  const connection = useFactory((s) => s.connection);
  const notice = useFactory((s) => s.notice);
  const dismissNotice = useFactory((s) => s.dismissNotice);
  const quality = useFactory((s) => s.quality);
  const setQuality = useFactory((s) => s.setQuality);
  const toggle = useFactory((s) => s.toggle);
  const rightOpen = useFactory((s) => s.rightOpen);
  const setRightTab = useFactory((s) => s.setRightTab);
  return (
    <>
    <header className="safe-top relative z-30 flex min-h-12 shrink-0 items-center gap-2 border-b border-line bg-ink-1/95 px-2 backdrop-blur md:gap-3 md:px-3">
      <h1 className="sr-only">FactoryTwin, Digital Twin der Smart Factory</h1>
      <div className="flex min-w-0 items-center gap-2.5">
        <div className="grid h-7 w-7 shrink-0 place-items-center rounded-md border border-teal-400/30 bg-teal-400/10">
          <svg viewBox="0 0 32 32" className="h-4 w-4">
            <path d="M6 24V13l6 4v-4l6 4V8h7v16z" fill="none" stroke="#5eead4" strokeWidth="2.4" strokeLinejoin="round" />
          </svg>
        </div>
        <div className="hidden min-w-0 leading-tight sm:block">
          <div className="text-[13px] font-semibold tracking-tight text-txt-1">FactoryTwin</div>
          <div className="truncate text-[10px] text-txt-3">Werk Nord · Halle 3 · Digital Twin</div>
        </div>
      </div>

      <div className="mx-1 hidden h-6 w-px bg-line lg:block" />

      {/* clock */}
      <div className="hidden items-baseline gap-2 md:flex">
        <span className="font-mono text-[15px] font-semibold text-txt-1 tnum">{clock(time)}</span>
        <span className="text-[10px] text-txt-3 whitespace-nowrap">
          Tag {dayOf(time)}, {shiftOf(time)}
        </span>
      </div>

      {/* simulation control */}
      <div className="ml-auto flex items-center gap-1.5 md:ml-4">
        <Button
          variant={running ? 'default' : 'primary'}
          onClick={() => void (running ? control.pause() : control.play())}
          title={running ? 'Pausieren (Leertaste)' : 'Starten (Leertaste)'}
          className="w-[84px]"
        >
          {running ? Icon.pause : Icon.play}
          {running ? 'Pause' : 'Start'}
        </Button>
        <div className="hidden sm:block">
          <Segmented
            value={speed}
            onChange={(v) => void control.setSpeed(v)}
            ariaLabel="Simulationsgeschwindigkeit"
            options={SPEEDS.map((s) => ({ value: s, label: `${s}×`, title: `Simulationsgeschwindigkeit ${s}×` }))}
          />
        </div>
        <select
          aria-label="Geschwindigkeit"
          className="h-8 rounded-md border border-line-2 bg-ink-3 px-1 text-xs text-txt-1 sm:hidden"
          value={speed}
          onChange={(e) => void control.setSpeed(Number(e.target.value))}
        >
          {SPEEDS.map((s) => (
            <option key={s} value={s}>
              {s}×
            </option>
          ))}
        </select>
        <ResetButton seed={seed} />
      </div>

      <div className="mx-1 hidden h-6 w-px bg-line lg:block" />

      {/* alarms */}
      <button
        onClick={() => setRightTab('events')}
        className="hidden items-center gap-2 whitespace-nowrap rounded-md border border-line px-2 py-1 text-[11px] hover:bg-white/5 lg:flex"
        title="Fehler und Warnungen der letzten Ereignisse anzeigen"
      >
        <span className={cx('flex items-center gap-1', errors ? 'text-red-300' : 'text-txt-3')}>
          {Icon.alert}
          <span className="font-mono tnum">{errors}</span>
        </span>
        <span className={cx('flex items-center gap-1', warnings ? 'text-amber-200' : 'text-txt-3')}>
          <span className="h-1.5 w-1.5 rounded-full bg-amber-400" />
          <span className="font-mono tnum">{warnings}</span>
        </span>
      </button>

      {/* runtime and connection (Spec 20: the interface names the active mode) */}
      <span
        className="hidden items-center gap-1.5 rounded-md px-2 py-1 text-[11px] text-txt-3 xl:flex"
        title={mode === 'browser' ? 'Java-Kern im Browser (Web Worker)' : 'Server mit PostgreSQL und OPC UA'}
      >
        <span
          className={cx(
            'h-1.5 w-1.5 rounded-full',
            connection === 'open' ? 'bg-emerald-400' : connection === 'connecting' ? 'bg-amber-400' : 'bg-red-500',
          )}
        />
        {mode === 'browser' ? 'Browser' : 'Server'}
        {connection === 'closed' && ' · getrennt'}
      </span>

      <div className="hidden lg:block">
        <Segmented
          size="sm"
          ariaLabel="Grafikqualität"
          value={quality}
          onChange={setQuality}
          options={[
            { value: 'high', label: 'Hoch', title: 'Schatten, Bloom, volle Auflösung' },
            { value: 'balanced', label: 'Mittel', title: 'Schatten ohne Post-Processing' },
            { value: 'performance', label: 'Schnell', title: 'Keine Schatten, reduzierte Auflösung' },
          ]}
        />
      </div>

      <button
        onClick={() => toggle('rightOpen')}
        className={cx('rounded p-1.5 text-txt-3 hover:bg-white/5 hover:text-txt-1', rightOpen && 'text-txt-1')}
        title="Detail-Panel ein/aus"
        aria-label="Detail-Panel umschalten"
      >
        {Icon.panelRight}
      </button>
    </header>
    {notice && (
      <div role="alert" className="relative z-30 flex items-center gap-3 border-b border-red-500/30 bg-red-500/10 px-3 py-1.5 text-[12px] text-red-200">
        <span className="min-w-0 flex-1">{notice}</span>
        <Button variant="ghost" size="sm" onClick={dismissNotice} aria-label="Meldung schliessen">
          Schliessen
        </Button>
      </div>
    )}
    </>
  );
}

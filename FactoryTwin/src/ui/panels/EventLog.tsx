import { useMemo, useState } from 'react';
import type { Severity } from '../../sim/types';
import { command, engine, useFactory, type Selection } from '../../store/factoryStore';
import { clock, duration, SEVERITY_COLOR, SEVERITY_LABEL } from '../format';
import { Button, cx, Icon } from '../primitives';

function selectionFor(source: string): Selection | null {
  if (engine.machine(source)) return { type: 'machine', id: source };
  if (engine.conveyor(source)) return { type: 'conveyor', id: source };
  if (engine.agv(source)) return { type: 'agv', id: source };
  if (source === 'Rohlager' || source === 'Wareneingang') return { type: 'raw', id: 'RAW' };
  if (source === 'Versand') return { type: 'finished', id: 'FG' };
  return null;
}

function download(name: string, content: string, type: string) {
  const blob = new Blob([content], { type });
  const url = URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = url;
  a.download = name;
  a.click();
  setTimeout(() => URL.revokeObjectURL(url), 1000);
}

export function EventLog() {
  const events = useFactory((s) => s.snap.events);
  const alarms = useFactory((s) => s.snap.alarms);
  const time = useFactory((s) => s.snap.time);
  const open = useFactory((s) => s.logOpen);
  const toggle = useFactory((s) => s.toggle);
  const select = useFactory((s) => s.select);
  const setRightTab = useFactory((s) => s.setRightTab);
  const tab = useFactory((s) => s.logTab);
  const showLog = useFactory((s) => s.showLog);
  const setTab = (t: 'alarms' | 'events') => showLog(t);
  const [sev, setSev] = useState<Record<Severity, boolean>>({ info: true, success: true, warning: true, error: true });
  const [q, setQ] = useState('');

  const filtered = useMemo(() => {
    const needle = q.trim().toLowerCase();
    return events
      .filter((e) => sev[e.severity] && (!needle || e.source.toLowerCase().includes(needle) || e.message.toLowerCase().includes(needle)))
      .slice(-250)
      .reverse();
  }, [events, sev, q]);

  const errors = alarms.filter((a) => a.severity === 'error').length;
  const latestCritical = useMemo(() => {
    for (let i = events.length - 1; i >= 0; i--) if (events[i].severity === 'error' || events[i].severity === 'warning') return events[i];
    return null;
  }, [events]);
  const onSource = (src: string) => {
    const s = selectionFor(src);
    if (s) select(s);
    else if (src.startsWith('FA-')) setRightTab('orders');
  };

  const exportCsv = () => {
    const rows = [['id', 'sim_time_s', 'uhrzeit', 'schwere', 'quelle', 'meldung'], ...events.map((e) => [e.id, e.time.toFixed(1), clock(e.time), e.severity, e.source, e.message])];
    // BOM so that Excel opens UTF-8 with correct umlauts
    download(`factorytwin-ereignisse-${Math.round(time)}.csv`, '﻿' + rows.map((r) => r.map((c) => `"${String(c).replace(/"/g, '""')}"`).join(';')).join('\n'), 'text/csv;charset=utf-8');
  };
  const exportJson = () => download(`factorytwin-ereignisse-${Math.round(time)}.json`, JSON.stringify(events, null, 2), 'application/json');

  return (
    <section aria-label="Ereignisprotokoll und Alarme" className={cx('safe-bottom pointer-events-auto flex flex-col border-t border-line bg-ink-1/95 backdrop-blur', open ? 'h-[34vh] max-h-[300px] min-h-[180px]' : 'min-h-9')}>
      {/* Screen-reader announcement of the newest warning / error (WCAG 4.1.3) */}
      <div className="sr-only" aria-live="polite" aria-atomic="true">
        {latestCritical ? `${SEVERITY_LABEL[latestCritical.severity]} von ${latestCritical.source}: ${latestCritical.message}` : ''}
      </div>
      <div className="@container flex h-9 min-w-0 shrink-0 items-center gap-1 overflow-hidden px-2">
        <button onClick={() => toggle('logOpen')} className="flex items-center gap-1.5 rounded px-1.5 py-1 text-[11px] text-txt-2 hover:bg-white/5" aria-expanded={open} aria-label={open ? 'Protokoll einklappen' : 'Protokoll ausklappen'}>
          {Icon.log}
          <span className="hidden sm:inline">Protokoll</span>
          <svg viewBox="0 0 12 12" className={cx('h-2.5 w-2.5 transition-transform', open ? 'rotate-180' : '')} fill="currentColor">
            <path d="M6 3 2 8h8z" />
          </svg>
        </button>
        <div className="flex items-center">
          {(
            [
              ['events', `Ereignisse`],
              ['alarms', `Alarme`],
            ] as const
          ).map(([k, label]) => (
            <button
              key={k}
              onClick={() => {
                setTab(k);
              }}
              className={cx('flex items-center gap-1.5 rounded px-2 py-1 text-[11px]', tab === k && open ? 'bg-ink-4 text-txt-1' : 'text-txt-3 hover:text-txt-1')}
            >
              {label}
              {k === 'alarms' && alarms.length > 0 && (
                <span className={cx('rounded px-1 font-mono text-[10px]', errors ? 'bg-red-500/20 text-red-200' : 'bg-amber-500/20 text-amber-100')}>{alarms.length}</span>
              )}
            </button>
          ))}
        </div>
        {!open && filtered[0] && (
          <div className="ml-2 hidden min-w-0 flex-1 items-center gap-2 truncate text-[11px] text-txt-3 @xl:flex">
            <span className="h-1.5 w-1.5 shrink-0 rounded-full" style={{ background: SEVERITY_COLOR[filtered[0].severity] }} />
            <span className="font-mono">{clock(filtered[0].time)}</span>
            <span className="font-mono text-txt-2">{filtered[0].source}</span>
            <span className="truncate">{filtered[0].message}</span>
          </div>
        )}
        {open && tab === 'events' && (
          <div className="ml-auto flex min-w-0 items-center gap-1.5">
            <div className="hidden items-center gap-1 @3xl:flex">
              {(Object.keys(sev) as Severity[]).map((k) => (
                <button
                  key={k}
                  onClick={() => setSev({ ...sev, [k]: !sev[k] })}
                  className={cx('flex items-center gap-1 rounded border px-1.5 py-0.5 text-[10px]', sev[k] ? 'border-line-2 text-txt-1' : 'border-transparent text-txt-3 line-through')}
                  aria-pressed={sev[k]}
                >
                  <span className="h-1.5 w-1.5 rounded-full" style={{ background: SEVERITY_COLOR[k] }} />
                  {SEVERITY_LABEL[k]}
                </button>
              ))}
            </div>
            <input
              value={q}
              onChange={(e) => setQ(e.target.value)}
              placeholder="Filtern …"
              aria-label="Ereignisse filtern"
              type="search"
              className="h-6 w-24 min-w-0 rounded border border-line-2 bg-ink-2 px-2 text-[11px] text-txt-1 placeholder:text-txt-3 @xl:w-40"
            />
            <Button size="sm" variant="ghost" onClick={exportCsv} title="Ereignisprotokoll als CSV exportieren">
              {Icon.download}CSV
            </Button>
            <Button size="sm" variant="ghost" onClick={exportJson} title="Ereignisprotokoll als JSON exportieren" className="hidden @lg:inline-flex">
              JSON
            </Button>
          </div>
        )}
      </div>
      {open && (
        <div className="scroll-thin min-h-0 flex-1 overflow-y-auto">
          {tab === 'events' ? (
            <table className="w-full text-[11px]">
              <tbody>
                {filtered.map((e) => (
                  <tr key={e.id} className="border-t border-line/50 hover:bg-white/[0.02]">
                    <td className="w-[78px] whitespace-nowrap py-1 pl-3 pr-2 font-mono text-txt-3 tnum">{clock(e.time)}</td>
                    <td className="w-[78px] py-1 pr-2">
                      <span className="flex items-center gap-1.5 text-txt-2">
                        <span className="h-1.5 w-1.5 rounded-full" style={{ background: SEVERITY_COLOR[e.severity] }} />
                        {SEVERITY_LABEL[e.severity]}
                      </span>
                    </td>
                    <td className="w-[96px] py-1 pr-2">
                      <button onClick={() => onSource(e.source)} className="font-mono text-txt-1 hover:text-teal-200 hover:underline">
                        {e.source}
                      </button>
                    </td>
                    <td className="py-1 pr-3 text-txt-2">{e.message}</td>
                  </tr>
                ))}
                {filtered.length === 0 && (
                  <tr>
                    <td className="p-4 text-center text-txt-3">Keine Ereignisse für diesen Filter</td>
                  </tr>
                )}
              </tbody>
            </table>
          ) : (
            <table className="w-full text-[11px]">
              <tbody>
                {alarms.length === 0 && (
                  <tr>
                    <td className="p-4 text-center text-txt-3">
                      <span className="inline-flex items-center gap-1.5 text-emerald-300">{Icon.check}Keine aktiven Alarme</span>
                    </td>
                  </tr>
                )}
                {[...alarms]
                  .sort((a, b) => (a.severity === b.severity ? b.since - a.since : a.severity === 'error' ? -1 : 1))
                  .map((a) => (
                    <tr key={a.key} className={cx('border-t border-line/50', !a.acknowledged && (a.severity === 'error' ? 'bg-red-500/[0.06]' : 'bg-amber-500/[0.04]'))}>
                      <td className="w-[90px] py-1.5 pl-3 pr-2">
                        <span className={cx('flex items-center gap-1.5', a.severity === 'error' ? 'text-red-300' : 'text-amber-200')}>
                          {Icon.alert}
                          {a.severity === 'error' ? 'Fehler' : 'Warnung'}
                        </span>
                      </td>
                      <td className="w-[96px] py-1.5 pr-2">
                        <button onClick={() => onSource(a.source)} className="font-mono text-txt-1 hover:text-teal-200 hover:underline">
                          {a.source}
                        </button>
                      </td>
                      <td className="py-1.5 pr-2 text-txt-2">{a.message}</td>
                      <td className="w-[90px] whitespace-nowrap py-1.5 pr-2 text-right font-mono text-txt-3">seit {duration(time - a.since)}</td>
                      <td className="w-[110px] py-1.5 pr-3 text-right">
                        {a.acknowledged ? (
                          <span className="text-[10px] text-txt-3">quittiert</span>
                        ) : (
                          <Button size="sm" variant="ghost" onClick={() => command((e) => e.acknowledgeAlarm(a.key))}>
                            Quittieren
                          </Button>
                        )}
                      </td>
                    </tr>
                  ))}
              </tbody>
            </table>
          )}
        </div>
      )}
    </section>
  );
}

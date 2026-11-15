import { useEffect, useMemo, useRef, useState } from 'react';
import { matches, PAGE, type EventFilter } from '../../nexus/eventFilter';
import type { NexusEvent } from '../../nexus/types';
import { hrefOf } from '../../shell/route';
import { eventPage, useFactory } from '../../store/factoryStore';
import { clock, SEVERITY_COLOR } from '../../ui/format';
import { control, DataTable, Field, type Column } from '../../ui/kit';
import { eventText } from '../../ui/panels/eventText';
import { Button, Toggle } from '../../ui/primitives';

const TYPES: [string, string][] = [
  ['', 'Alle'],
  ['order.', 'Aufträge'],
  ['operation.', 'Arbeitsgänge'],
  ['machine.', 'Maschinen'],
  ['transport.', 'Transporte'],
  ['agv.', 'AGVs'],
  ['stock.', 'Bestand'],
  ['material.', 'Material'],
  ['route.', 'Routen'],
  ['opcua.', 'OPC UA'],
  ['scenario.', 'Szenarien'],
  ['system.', 'System'],
  ['decision', 'Entscheidungen'],
];

const SEVERITY_LABEL: Record<NexusEvent['severity'], string> = {
  ERROR: 'Fehler',
  WARNING: 'Warnung',
  SUCCESS: 'Erfolg',
  INFO: 'Info',
};

const columns: Column<NexusEvent>[] = [
  { key: 'time', label: 'Zeit', value: (e) => e.seq, render: (e) => clock(e.simTimeMs / 1000), mono: true, width: '88px' },
  {
    key: 'severity',
    label: 'Schweregrad',
    value: (e) => e.severity,
    width: '112px',
    render: (e) => (
      <span className="inline-flex items-center gap-1.5 whitespace-nowrap">
        <span className="h-1.5 w-1.5 rounded-full" style={{ background: SEVERITY_COLOR[e.severity] }} aria-hidden="true" />
        {SEVERITY_LABEL[e.severity]}
      </span>
    ),
  },
  { key: 'type', label: 'Typ', value: (e) => e.type, mono: true, width: '180px' },
  { key: 'text', label: 'Beschreibung', value: (e) => eventText(e) },
  {
    key: 'order',
    label: 'Auftrag',
    value: (e) => e.correlationId,
    mono: true,
    width: '120px',
    render: (e) =>
      e.correlationId && (
        <a href={hrefOf('events', e.correlationId)} className="underline decoration-line-2 underline-offset-4 hover:decoration-accent" title="Nur diesen Auftrag zeigen">
          {e.correlationId}
        </a>
      ),
  },
];

type Paged = { rows: NexusEvent[]; more: boolean; next: number; loading: boolean; error: string | null };

/**
 * Every event of the run, filterable. Live follows the newest events the stream brought; without live the run's
 * history is read page by page from the plant (browser core or server table). An order filter lives in the URL.
 */
export function EventsArea({ filterId }: { filterId: string | null }) {
  const [type, setType] = useState('');
  const [severity, setSeverity] = useState<'' | NexusEvent['severity']>('');
  const [live, setLive] = useState(filterId === null);
  const [draft, setDraft] = useState(filterId ?? '');
  const [paged, setPaged] = useState<Paged>({ rows: [], more: false, next: 0, loading: false, error: null });
  const streamed = useFactory((s) => s.events);
  const run = useFactory((s) => s.run);
  const latest = useRef(0);

  useEffect(() => {
    setDraft(filterId ?? '');
    if (filterId) setLive(false); // an order's whole history, not only what the stream still holds
  }, [filterId]);

  const filter = useMemo<EventFilter>(
    () => ({ type: type || undefined, severity: severity || undefined, correlationId: filterId ?? undefined }),
    [type, severity, filterId],
  );

  const load = (after?: number) => {
    const ask = ++latest.current; // only the newest request may fill the table (server answers can overtake)
    setPaged((p) => ({ ...p, loading: true, error: null }));
    eventPage({ ...filter, after })
      .then((page) =>
        ask === latest.current &&
        setPaged((p) => ({
          rows: after === undefined ? page.items : [...p.rows, ...page.items],
          more: page.more,
          next: page.next,
          loading: false,
          error: null,
        })),
      )
      .catch((e: unknown) =>
        ask === latest.current &&
        setPaged((p) => ({ ...p, loading: false, error: e instanceof Error ? e.message : 'Ereignisse nicht lesbar' })),
      );
  };

  useEffect(() => {
    if (!live) load(); // load reads the same filter; a restart starts the pages over
  }, [live, filter, run]);

  const liveRows = useMemo(
    () => (live ? streamed.filter((e) => matches(e, filter)).slice(-PAGE).reverse() : []),
    [live, streamed, filter],
  );
  const rows = live ? liveRows : paged.rows;

  const applyOrder = (id: string) => {
    window.location.hash = hrefOf('events', id.trim() || undefined);
  };

  return (
    <div className="px-4 py-6 lg:px-6 lg:py-8">
      <form
        className="grid grid-cols-1 items-end gap-4 border-b border-line pb-4 sm:grid-cols-2 lg:grid-cols-[minmax(0,220px)_minmax(0,200px)_minmax(0,160px)_auto]"
        onSubmit={(e) => {
          e.preventDefault();
          applyOrder(draft);
        }}
        aria-label="Ereignisfilter"
      >
        <Field label="Auftrag">
          <input
            className={control}
            value={draft}
            placeholder="z. B. PO-0004"
            onChange={(e) => setDraft(e.target.value)}
            onBlur={() => draft.trim() !== (filterId ?? '') && applyOrder(draft)}
            spellCheck={false}
          />
        </Field>
        <Field label="Typ">
          <select className={control} value={type} onChange={(e) => setType(e.target.value)}>
            {TYPES.map(([v, l]) => (
              <option key={v} value={v}>
                {l}
              </option>
            ))}
          </select>
        </Field>
        <Field label="Schweregrad">
          <select className={control} value={severity} onChange={(e) => setSeverity(e.target.value as typeof severity)}>
            <option value="">Alle</option>
            {(Object.keys(SEVERITY_LABEL) as NexusEvent['severity'][]).map((s) => (
              <option key={s} value={s}>
                {SEVERITY_LABEL[s]}
              </option>
            ))}
          </select>
        </Field>
        <div className="sm:col-span-2 lg:col-span-1 lg:min-w-[180px]">
          <Toggle checked={live} onChange={setLive} label="Live folgen" hint={live ? 'neueste oben' : 'ganzer Lauf, älteste oben'} />
        </div>
      </form>

      <div className="flex flex-wrap items-baseline justify-between gap-3 py-3 text-[12px] text-txt-3" aria-live={live ? 'off' : 'polite'}>
        <span>
          {live
            ? `Live: ${rows.length} der zuletzt empfangenen Ereignisse`
            : `${rows.length} Ereignisse${paged.more ? ', weitere vorhanden' : ''}`}
          {filterId && (
            <>
              {' · '}Auftrag <span className="font-mono text-txt-1">{filterId}</span>{' '}
              <a href={hrefOf('events')} className="text-txt-2 underline decoration-line-2 underline-offset-4 hover:text-txt-1">
                Filter lösen
              </a>
            </>
          )}
        </span>
        {!live && (
          <Button variant="ghost" size="sm" onClick={() => load()} disabled={paged.loading}>
            Neu laden
          </Button>
        )}
      </div>

      {!live && paged.error ? (
        <p role="alert" className="py-6 text-[13px] text-txt-1">
          {paged.error}
        </p>
      ) : (
        <DataTable label="Ereignisse" rows={rows} columns={columns} rowKey={(e) => e.eventId} empty="Keine Ereignisse für diesen Filter." />
      )}

      {!live && paged.more && (
        <div className="border-t border-line pt-4">
          <Button onClick={() => load(paged.next)} disabled={paged.loading}>
            {paged.loading ? 'Lädt …' : 'Weitere laden'}
          </Button>
        </div>
      )}
    </div>
  );
}

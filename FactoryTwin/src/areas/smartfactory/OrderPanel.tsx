import { useState } from 'react';
import type { Command, OrderDetail, Priority } from '../../nexus/types';
import { usePlantQuery } from '../../nexus/usePlantQuery';
import { hrefOf } from '../../shell/route';
import { send } from '../../store/factoryStore';
import { PROCESS_LABEL } from '../../twin/config';
import { clock, OP_STATE, ORDER_STATE } from '../../ui/format';
import { CodeTag, DataTable, type Column } from '../../ui/kit';
import { Button, Segmented } from '../../ui/primitives';

const PRIORITIES: { value: Priority; label: string }[] = [
  { value: 'NORMAL', label: 'Normal' },
  { value: 'HOCH', label: 'Hoch' },
  { value: 'EILIG', label: 'Eilig' },
];
const FINISHED = new Set(['COMPLETED', 'CANCELLED']);
const time = (ms: number | null) => (ms === null ? '–' : clock(ms / 1000, false));

type Op = OrderDetail['operations'][number];
const opColumns: Column<Op>[] = [
  { key: 'seq', label: 'Schritt', value: (o) => o.seq, mono: true, width: '64px' },
  { key: 'process', label: 'Prozess', value: (o) => PROCESS_LABEL[o.process as keyof typeof PROCESS_LABEL] ?? o.process },
  { key: 'machine', label: 'Maschine', value: (o) => o.machineId, mono: true, render: (o) => o.machineId ?? '–' },
  { key: 'status', label: 'Stand', value: (o) => o.status, render: (o) => <CodeTag map={OP_STATE} code={o.status} /> },
  { key: 'plan', label: 'Geplant', value: (o) => o.plannedStartMs, render: (o) => `${time(o.plannedStartMs)}–${time(o.plannedEndMs)}`, mono: true },
  { key: 'good', label: 'Gut', value: (o) => o.good, align: 'right', mono: true },
];

/** One order with its steps, its priority and cancelling; the core decides what is allowed. */
export function OrderPanel({ orderId }: { orderId: string }) {
  const { data, error, reload } = usePlantQuery<OrderDetail>({ query: 'order', orderId }, 2_000);
  const [asking, setAsking] = useState(false);
  if (!data)
    return (
      <p role={error ? 'alert' : undefined} className="text-[13px] text-txt-2">
        {error ? `Den Auftrag ${orderId} gibt es nicht.` : 'Auftrag wird geladen …'}{' '}
        <a href={hrefOf('smartfactory')} className="underline decoration-line-2 underline-offset-4">
          Zur Liste
        </a>
      </p>
    );
  const o = data.order;
  const open = !FINISHED.has(o.status);
  const act = async (c: Command) => {
    await send(c);
    reload();
  };
  return (
    <div className="flex flex-col gap-4">
      <dl className="grid grid-cols-2 gap-x-4 gap-y-2 text-[13px]">
        <dt className="text-txt-3">Produkt</dt>
        <dd className="font-mono text-txt-1">{o.productId}</dd>
        <dt className="text-txt-3">Menge</dt>
        <dd className="font-mono text-txt-1">{o.quantity}</dd>
        <dt className="text-txt-3">Stand</dt>
        <dd>
          <CodeTag map={ORDER_STATE} code={o.status} />
        </dd>
        <dt className="text-txt-3">Termin</dt>
        <dd className="font-mono text-txt-1">{time(o.dueAtMs)}</dd>
        <dt className="text-txt-3">Gut / Ausschuss</dt>
        <dd className="font-mono text-txt-1">
          {o.good} / {o.scrapped}
        </dd>
      </dl>
      {open && (
        <div>
          <div className="mb-1.5 text-[11px] uppercase tracking-[0.08em] text-txt-3">Priorität</div>
          <Segmented
            ariaLabel="Priorität"
            value={o.priority}
            options={PRIORITIES}
            onChange={(p) => void act({ command: 'changeOrder', orderId: o.id, priority: p })}
          />
        </div>
      )}
      <DataTable label="Arbeitsgänge" rows={data.operations} columns={opColumns} rowKey={(x) => String(x.seq)} />
      <div className="flex flex-wrap items-center gap-2">
        <a href={hrefOf('events', o.id)} className="text-[12px] text-txt-2 underline decoration-line-2 underline-offset-4 hover:text-txt-1">
          Ereignisse dieses Auftrags
        </a>
        {open &&
          (asking ? (
            <span className="flex items-center gap-2" role="group" aria-label="Stornieren bestätigen">
              <span className="text-[12px] text-txt-2">Auftrag stornieren?</span>
              <Button
                variant="danger"
                size="sm"
                autoFocus
                onClick={() => {
                  setAsking(false);
                  void act({ command: 'cancelOrder', orderId: o.id });
                }}
              >
                Ja
              </Button>
              <Button variant="ghost" size="sm" onClick={() => setAsking(false)}>
                Nein
              </Button>
            </span>
          ) : (
            <Button variant="ghost" size="sm" onClick={() => setAsking(true)}>
              Stornieren
            </Button>
          ))}
      </div>
    </div>
  );
}

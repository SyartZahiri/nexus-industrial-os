import { useEffect, useState, type ChangeEvent } from 'react';
import type { FrameOrder, ProductInfo, Schedule } from '../../nexus/types';
import { usePlantQuery } from '../../nexus/usePlantQuery';
import { hrefOf } from '../../shell/route';
import { sceneOf, send, twin, useFactory } from '../../store/factoryStore';
import { clock, ORDER_STATE, PRIORITY_LABEL, STATE_HEX } from '../../ui/format';
import { CodeTag, control, DataTable, Field, Section, type Column } from '../../ui/kit';
import { Button, Segmented } from '../../ui/primitives';
import { Gantt } from './Gantt';
import { OrderPanel } from './OrderPanel';
import { newOrderForm, submitOrder, type OrderForm } from './orderForm';

const RULE_LABEL: Record<Schedule['rule'], string> = {
  PRIORITY: 'Priorität',
  EDD: 'Frühester Termin',
  FIFO: 'Reihenfolge',
  SPT: 'Kürzeste Bearbeitung',
};

function orderColumns(nowMs: number): Column<FrameOrder>[] {
  return [
    {
      key: 'id',
      label: 'Auftrag',
      value: (o) => o.id,
      mono: true,
      width: '104px',
      render: (o) => (
        <a href={hrefOf('smartfactory', o.id)} className="underline decoration-line-2 underline-offset-4 hover:decoration-accent">
          {o.id}
        </a>
      ),
    },
    { key: 'product', label: 'Produkt', value: (o) => o.productId, mono: true },
    { key: 'qty', label: 'Menge', value: (o) => o.quantity, align: 'right', mono: true },
    { key: 'prio', label: 'Priorität', value: (o) => o.priority, render: (o) => PRIORITY_LABEL[o.priority as keyof typeof PRIORITY_LABEL] ?? o.priority },
    { key: 'status', label: 'Stand', value: (o) => o.status, render: (o) => <CodeTag map={ORDER_STATE} code={o.status} /> },
    {
      key: 'steps',
      label: 'Schritte',
      value: (o) => o.stepsDone / Math.max(1, o.steps),
      render: (o) => `${o.stepsDone}/${o.steps}`,
      align: 'right',
      mono: true,
    },
    {
      key: 'due',
      label: 'Termin',
      value: (o) => o.dueAtMs,
      mono: true,
      render: (o) => {
        const late = o.dueAtMs < nowMs && o.status !== 'COMPLETED' && o.status !== 'CANCELLED';
        return (
          <span className="inline-flex items-center gap-1.5">
            {late && <span className="h-1.5 w-1.5 rounded-full" style={{ background: STATE_HEX.wait }} aria-hidden="true" />}
            {clock(o.dueAtMs / 1000, false)}
            {late && <span className="sr-only">, verspätet</span>}
          </span>
        );
      },
    },
  ];
}

function NewOrder({ products }: { products: ProductInfo[] }) {
  const [form, setForm] = useState<OrderForm>(() => newOrderForm(products[0]?.id ?? ''));
  const [busy, setBusy] = useState(false);
  useEffect(() => {
    if (!form.productId && products[0]) setForm(newOrderForm(products[0].id));
  }, [products, form.productId]);
  const number = (k: 'quantity' | 'dueInMinutes') => (e: ChangeEvent<HTMLInputElement>) => {
    const n = Math.round(Number(e.target.value));
    if (e.target.value !== '' && Number.isFinite(n)) setForm({ ...form, [k]: n }); // the core judges the value
  };
  return (
    <form
      aria-label="Neuer Auftrag"
      className="grid grid-cols-1 items-end gap-4 sm:grid-cols-2 xl:grid-cols-[minmax(0,1fr)_96px_minmax(0,auto)_120px_auto]"
      onSubmit={async (e) => {
        e.preventDefault();
        setBusy(true);
        setForm(await submitOrder(form, send));
        setBusy(false);
      }}
    >
      <Field label="Produkt">
        <select className={control} value={form.productId} onChange={(e) => setForm({ ...form, productId: e.target.value })}>
          {products.map((p) => (
            <option key={p.id} value={p.id}>
              {p.name}
            </option>
          ))}
        </select>
      </Field>
      <Field label="Menge">
        <input type="number" inputMode="numeric" className={`${control} font-mono tnum`} value={form.quantity} onChange={number('quantity')} />
      </Field>
      <div>
        <div className="mb-1 text-[11px] uppercase tracking-[0.08em] text-txt-3">Priorität</div>
        <Segmented
          ariaLabel="Priorität"
          value={form.priority}
          onChange={(priority) => setForm({ ...form, priority })}
          options={[
            { value: 'NORMAL', label: 'Normal' },
            { value: 'HOCH', label: 'Hoch' },
            { value: 'EILIG', label: 'Eilig' },
          ]}
        />
      </div>
      <Field label="Termin in (min)">
        <input type="number" inputMode="numeric" className={`${control} font-mono tnum`} value={form.dueInMinutes} onChange={number('dueInMinutes')} />
      </Field>
      <Button type="submit" variant="primary" disabled={busy || !form.productId}>
        {busy ? 'Wird angelegt …' : 'Anlegen'}
      </Button>
    </form>
  );
}

/** Orders, the plan per machine and a new order; the order of the link (#/smartfactory/PO-0004) opens beside. */
export function SmartFactoryArea({ orderId }: { orderId: string | null }) {
  useFactory((s) => s.scene);
  const machineIds = useFactory((s) => sceneOf(s).machines.map((m) => m.id).join(','));
  const nowMs = twin.latest?.simTimeMs ?? 0;
  const orders = twin.latest?.orders ?? [];
  const { data: schedule } = usePlantQuery<Schedule>({ query: 'schedule' }, 2_000);
  const { data: products } = usePlantQuery<ProductInfo[]>({ query: 'products' });
  return (
    <div className="grid grid-cols-12 gap-x-8 gap-y-10 px-4 py-6 lg:px-6 lg:py-8">
      <Section title="Aufträge" className="col-span-12 xl:col-span-8">
        <DataTable label="Aufträge" rows={orders} columns={orderColumns(nowMs)} rowKey={(o) => o.id} empty="Noch keine Aufträge." />
        <p className="mt-2 text-[12px] text-txt-3">Offene Aufträge und die 20 zuletzt abgeschlossenen.</p>
      </Section>
      <Section title={orderId ?? 'Auftrag'} className="col-span-12 xl:col-span-4">
        {orderId ? (
          <OrderPanel orderId={orderId} />
        ) : (
          <p className="text-[13px] text-txt-3">Wähle einen Auftrag, um Schritte, Priorität und Storno zu sehen.</p>
        )}
      </Section>
      <Section
        title="Plan"
        className="col-span-12"
        action={
          schedule && (
            <span className="text-[12px] text-txt-2">
              Regel {RULE_LABEL[schedule.rule]} ·{' '}
              <a href={hrefOf('settings')} className="underline decoration-line-2 underline-offset-4 hover:text-txt-1">
                ändern
              </a>
              {schedule.bottleneckMachineId && (
                <>
                  {' '}
                  · Engpass <span className="font-mono text-txt-1">{schedule.bottleneckMachineId}</span>
                </>
              )}
            </span>
          )
        }
      >
        {schedule ? (
          <>
            <Gantt schedule={schedule} nowMs={nowMs} machineIds={machineIds ? machineIds.split(',') : []} />
            {schedule.unplannable.length > 0 && (
              <ul className="mt-3 text-[12px] text-txt-2">
                {schedule.unplannable.map((u) => (
                  <li key={`${u.orderId}/${u.seq}`}>
                    <span className="font-mono text-txt-1">
                      {u.orderId}/{u.seq}
                    </span>{' '}
                    nicht planbar: {u.reason}
                  </li>
                ))}
              </ul>
            )}
          </>
        ) : (
          <p className="text-[13px] text-txt-3">Plan wird geladen …</p>
        )}
      </Section>
      <Section title="Neuer Auftrag" className="col-span-12">
        {products ? <NewOrder products={products} /> : <p className="text-[13px] text-txt-3">Produkte werden geladen …</p>}
      </Section>
    </div>
  );
}

import { useState } from 'react';
import { PRODUCT_IDS, PRODUCTS, PROCESS_LABEL } from '../../sim/config';
import type { Order, OrderPriority, ProductId } from '../../sim/types';
import { command, useFactory } from '../../store/factoryStore';
import { clock, compactDuration, ORDER_STATUS_COLOR, ORDER_STATUS_LABEL, PRIORITY_COLOR, PRIORITY_LABEL } from '../format';
import { Button, Card, cx, Icon, SectionTitle, Segmented } from '../primitives';

function NewOrderForm({ onDone }: { onDone: () => void }) {
  const [product, setProduct] = useState<ProductId>('GH200');
  const [qty, setQty] = useState(8);
  const [priority, setPriority] = useState<OrderPriority>('normal');
  const p = PRODUCTS[product];
  return (
    <Card className="border-teal-400/25">
      <SectionTitle>Neuer Fertigungsauftrag</SectionTitle>
      <div className="grid grid-cols-2 gap-1.5">
        {PRODUCT_IDS.map((id) => (
          <button
            key={id}
            onClick={() => setProduct(id)}
            className={cx(
              'flex items-center gap-2 rounded-md border px-2 py-1.5 text-left text-[11px] transition-colors',
              product === id ? 'border-teal-400/50 bg-teal-400/10 text-txt-1' : 'border-line-2 bg-ink-1 text-txt-2 hover:border-[#3a4654]',
            )}
          >
            <span className="h-2 w-2 shrink-0 rounded-sm" style={{ background: PRODUCTS[id].color }} />
            <span className="truncate">{PRODUCTS[id].name}</span>
          </button>
        ))}
      </div>
      <div className="mt-2 text-[10px] text-txt-3">
        Arbeitsplan: {p.routing.map((r) => `${PROCESS_LABEL[r.process]} (${r.baseTime}s)`).join(' → ')}
      </div>
      <div className="mt-2.5 flex flex-wrap items-center gap-2">
        <label className="flex items-center gap-1.5 text-[11px] text-txt-3">
          Menge
          <input
            type="number"
            min={1}
            max={60}
            value={qty}
            onChange={(e) => setQty(Math.max(1, Math.min(60, Number(e.target.value) || 1)))}
            className="h-7 w-14 rounded-md border border-line-2 bg-ink-1 px-2 font-mono text-xs text-txt-1"
          />
        </label>
        <Segmented
          size="sm"
          value={priority}
          onChange={setPriority}
          options={(['normal', 'hoch', 'eilig'] as OrderPriority[]).map((v) => ({ value: v, label: PRIORITY_LABEL[v] }))}
        />
      </div>
      <div className="mt-3 flex justify-end gap-1.5">
        <Button variant="ghost" size="sm" onClick={onDone}>
          Abbrechen
        </Button>
        <Button
          variant="primary"
          size="sm"
          onClick={() => {
            command((e) => e.createOrder({ product, quantity: qty, priority, source: 'operator' }));
            onDone();
          }}
        >
          {Icon.plus}Auftrag einplanen
        </Button>
      </div>
    </Card>
  );
}

function OrderRow({ o, now, expanded, onToggle }: { o: Order; now: number; expanded: boolean; onToggle: () => void }) {
  const p = PRODUCTS[o.product];
  const done = o.status === 'ABGESCHLOSSEN' || o.status === 'STORNIERT';
  const slack = o.dueAt - now;
  const good = o.completed / o.quantity;
  const wip = Math.max(0, o.inProgress) / o.quantity;
  return (
    <li className="border-b border-line/70 last:border-0">
      <button onClick={onToggle} className="w-full px-3 py-2 text-left hover:bg-white/[0.02]">
        <div className="flex items-center gap-2">
          <span className="h-2 w-2 shrink-0 rounded-sm" style={{ background: p.color }} />
          <span className="font-mono text-[11px] font-semibold text-txt-1">{o.id}</span>
          <span className="truncate text-[11px] text-txt-2">{p.shortName}</span>
          {o.priority !== 'normal' && (
            <span className="rounded px-1 text-[10px] font-semibold uppercase tracking-wide text-txt-1" style={{ background: `${PRIORITY_COLOR[o.priority]}33` }}>
              {PRIORITY_LABEL[o.priority]}
            </span>
          )}
          <span className="ml-auto flex items-center gap-1 text-[10px] text-txt-2">
            <span className="h-1.5 w-1.5 rounded-full" style={{ background: ORDER_STATUS_COLOR[o.status] }} />
            {ORDER_STATUS_LABEL[o.status]}
          </span>
        </div>
        <div className="mt-1.5 flex h-1.5 w-full gap-[2px] overflow-hidden rounded-full bg-[#3987e51f]">
          {good > 0 && <div className="h-full rounded-full bg-[#3987e5]" style={{ width: `${good * 100}%` }} />}
          {wip > 0 && <div className="h-full rounded-full bg-[#3987e5]/45" style={{ width: `${Math.min(1 - good, wip) * 100}%` }} />}
        </div>
        <div className="mt-1 flex justify-between font-mono text-[10px] text-txt-3 tnum">
          <span>
            {o.completed}/{o.quantity} fertig{o.inProgress > 0 ? ` · ${o.inProgress} in Arbeit` : ''}
            {o.scrapped ? ` · ${o.scrapped} Ausschuss` : ''}
          </span>
          <span className={cx(!done && slack < 0 && 'text-amber-300')}>
            {done ? (o.completedAt ? `fertig ${clock(o.completedAt, false)}` : '') : slack >= 0 ? `Termin in ${compactDuration(slack)}` : `${compactDuration(-slack)} überfällig`}
          </span>
        </div>
      </button>
      {expanded && (
        <div className="space-y-2 px-3 pb-3 text-[11px]">
          <div className="grid grid-cols-2 gap-x-3 gap-y-0.5 text-txt-3">
            <span>Eingang</span>
            <span className="text-right font-mono text-txt-2">{clock(o.createdAt, false)}</span>
            <span>Liefertermin</span>
            <span className="text-right font-mono text-txt-2">{clock(o.dueAt, false)}</span>
            <span>Quelle</span>
            <span className="text-right text-txt-2">{o.source === 'smartfactory' ? 'SmartFactory' : o.source === 'operator' ? 'Bediener' : 'ERP (lokal)'}</span>
            <span>Freigegeben</span>
            <span className="text-right font-mono text-txt-2">{o.released}</span>
          </div>
          <div className="flex flex-wrap gap-1 text-[10px] text-txt-3">
            {p.routing.map((r, i) => (
              <span key={i} className="rounded border border-line-2 px-1.5 py-0.5">
                {i + 1}. {PROCESS_LABEL[r.process]} · {r.preferred}
              </span>
            ))}
          </div>
          {!done && (
            <div className="flex items-center gap-1.5">
              <Segmented
                size="sm"
                value={o.priority}
                onChange={(v) => command((e) => e.setOrderPriority(o.id, v))}
                options={(['normal', 'hoch', 'eilig'] as OrderPriority[]).map((v) => ({ value: v, label: PRIORITY_LABEL[v] }))}
              />
              <Button variant="danger" size="sm" className="ml-auto" onClick={() => command((e) => e.cancelOrder(o.id))}>
                Stornieren
              </Button>
            </div>
          )}
        </div>
      )}
    </li>
  );
}

export function OrdersPanel() {
  const orders = useFactory((s) => s.snap.orders);
  const now = useFactory((s) => s.snap.time);
  const [showForm, setShowForm] = useState(false);
  const [filter, setFilter] = useState<'open' | 'done'>('open');
  const [expanded, setExpanded] = useState<string | null>(null);
  const open = orders.filter((o) => o.status !== 'ABGESCHLOSSEN' && o.status !== 'STORNIERT');
  const done = orders.filter((o) => o.status === 'ABGESCHLOSSEN' || o.status === 'STORNIERT').reverse();
  const list = filter === 'open' ? open : done;
  return (
    <div className="space-y-3">
      <div className="flex items-center justify-between gap-2">
        <Segmented
          size="sm"
          value={filter}
          onChange={setFilter}
          options={[
            { value: 'open', label: `Offen · ${open.length}` },
            { value: 'done', label: `Abgeschlossen · ${done.length}` },
          ]}
        />
        {!showForm && (
          <Button variant="primary" size="sm" onClick={() => setShowForm(true)}>
            {Icon.plus}Auftrag
          </Button>
        )}
      </div>
      {showForm && <NewOrderForm onDone={() => setShowForm(false)} />}
      <Card className="!p-0">
        {list.length === 0 ? (
          <div className="p-6 text-center text-xs text-txt-3">{filter === 'open' ? 'Keine offenen Aufträge' : 'Noch keine abgeschlossenen Aufträge'}</div>
        ) : (
          <ul>
            {list.map((o) => (
              <OrderRow key={o.id} o={o} now={now} expanded={expanded === o.id} onToggle={() => setExpanded(expanded === o.id ? null : o.id)} />
            ))}
          </ul>
        )}
      </Card>
      <p className="px-1 text-[10px] leading-relaxed text-txt-3">
        Neue Rohteile kommen erst in die Fertigung, wenn der Umlaufbestand unter dem WIP-Limit liegt (CONWIP). Ausschuss wird automatisch nachproduziert.
      </p>
    </div>
  );
}

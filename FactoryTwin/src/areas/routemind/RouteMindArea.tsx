import { lazy, Suspense, useEffect, useState } from 'react';
import type { RmDelivery, RmPlan, RmTour, RmVehicle, RouteMindData } from '../../nexus/types';
import { usePlantQuery } from '../../nexus/usePlantQuery';
import { hrefOf } from '../../shell/route';
import { send } from '../../store/factoryStore';
import { clock, DELIVERY_STATE, nf, pct, TOUR_STATE, VEHICLE_STATE } from '../../ui/format';
import { CodeTag, DataTable, Section, Stat, type Column } from '../../ui/kit';
import { Button } from '../../ui/primitives';
import { loadRoads, type Roads } from './roads';
import { delayMinutes, planDelta, tourColor } from './routemindView';

const RouteMap = lazy(() => import('./RouteMap'));
const time = (ms: number | null) => (ms === null ? '–' : clock(ms / 1000, false));

const deliveryColumns: Column<RmDelivery>[] = [
  { key: 'id', label: 'Lieferung', value: (d) => d.id, mono: true, width: '96px' },
  { key: 'order', label: 'Auftrag', value: (d) => d.orderId, mono: true, width: '96px',
    render: (d) => <a href={hrefOf('smartfactory', d.orderId)} className="underline decoration-line-2 underline-offset-4">{d.orderId}</a> },
  { key: 'customer', label: 'Kunde', value: (d) => d.customerName },
  { key: 'status', label: 'Stand', value: (d) => d.status, render: (d) => <CodeTag map={DELIVERY_STATE} code={d.status} /> },
  { key: 'load', label: 'Ladung', value: (d) => d.weightKg, align: 'right', mono: true, render: (d) => `${nf(d.weightKg)} kg · ${d.pallets} Pal.` },
  { key: 'window', label: 'Zeitfenster', value: (d) => d.earliestMs, mono: true, render: (d) => `${time(d.earliestMs)}–${time(d.latestMs)}` },
  { key: 'planned', label: 'Geplant', value: (d) => d.plannedArrivalMs, mono: true, render: (d) => time(d.plannedArrivalMs) },
  { key: 'actual', label: 'Zugestellt', value: (d) => d.deliveredAtMs, mono: true, render: (d) => time(d.deliveredAtMs) },
  { key: 'late', label: 'Verspätung', value: (d) => delayMinutes(d), align: 'right', mono: true,
    render: (d) => { const m = delayMinutes(d); return m === null ? '–' : m === 0 ? 'pünktlich' : `${m} min`; } },
];

function tourColumns(tours: RmTour[]): Column<RmTour>[] {
  return [
    { key: 'id', label: 'Tour', value: (t) => t.id, mono: true, width: '104px',
      render: (t) => (
        <span className="inline-flex items-center gap-2">
          <span aria-hidden className="h-2.5 w-2.5 rounded-full" style={{ background: tourColor(tours, t.id) }} />
          {t.id}
        </span>
      ) },
    { key: 'vehicle', label: 'Fahrzeug', value: (t) => t.vehicleId, mono: true },
    { key: 'status', label: 'Stand', value: (t) => t.status, render: (t) => <CodeTag map={TOUR_STATE} code={t.status} /> },
    { key: 'depart', label: 'Abfahrt', value: (t) => t.departAtMs, mono: true, render: (t) => time(t.departAtMs) },
    { key: 'stops', label: 'Stopps', value: (t) => t.stops.length, align: 'right', mono: true },
    { key: 'km', label: 'Strecke', value: (t) => t.km, align: 'right', mono: true, render: (t) => `${nf(t.km, 1)} km` },
    { key: 'cost', label: 'Kosten', value: (t) => t.cost, align: 'right', mono: true, render: (t) => `${nf(t.cost, 2)} CHF` },
  ];
}

const planColumns: Column<RmPlan>[] = [
  { key: 'wave', label: 'Welle', value: (p) => p.waveAtMs, mono: true, render: (p) => time(p.waveAtMs) },
  { key: 'trigger', label: 'Anlass', value: (p) => p.trigger },
  { key: 'solver', label: 'Löser', value: (p) => p.solver },
  { key: 'base', label: 'Nächster Nachbar', value: (p) => p.baseline.cost, align: 'right', mono: true,
    render: (p) => `${nf(p.baseline.km, 1)} km · ${nf(p.baseline.driveMinutes)} min · ${nf(p.baseline.cost, 2)} CHF · ${nf(p.baseline.lateMinutes)} min spät` },
  { key: 'best', label: 'Optimiert', value: (p) => p.optimized.cost, align: 'right', mono: true,
    render: (p) => `${nf(p.optimized.km, 1)} km · ${nf(p.optimized.driveMinutes)} min · ${nf(p.optimized.cost, 2)} CHF · ${nf(p.optimized.lateMinutes)} min spät` },
  { key: 'delta', label: 'Gegen vorigen Plan', value: (p) => planDelta(p)?.cost ?? null, align: 'right', mono: true,
    render: (p) => { const d = planDelta(p); return d ? `${d.km >= 0 ? '+' : ''}${nf(d.km, 1)} km · ${d.cost >= 0 ? '+' : ''}${nf(d.cost, 2)} CHF` : '–'; } },
  { key: 'open', label: 'Offen oder verspätet', value: (p) => p.unassigned.length, render: (p) => (p.unassigned.length ? p.unassigned.join('; ') : '–') },
];

function VehicleActions({ v }: { v: RmVehicle }) {
  const act = (command: string, extra: Record<string, unknown> = {}) => void send({ command, vehicleId: v.id, ...extra });
  return (
    <span className="flex gap-2">
      {(v.status === 'EN_ROUTE' || v.status === 'RETURNING' || v.status === 'AVAILABLE') && (
        <Button size="sm" onClick={() => act('vehicleBreakdown')} aria-label={`Panne an ${v.id} auslösen`}>Panne</Button>
      )}
      {v.status === 'BROKEN_DOWN' && <Button size="sm" onClick={() => act('vehicleRepair')} aria-label={`${v.id} reparieren`}>Reparieren</Button>}
      {v.status === 'AVAILABLE' && <Button size="sm" onClick={() => act('vehicleMaintenance', { on: true })} aria-label={`${v.id} in Wartung`}>Wartung</Button>}
      {v.status === 'MAINTENANCE' && <Button size="sm" onClick={() => act('vehicleMaintenance', { on: false })} aria-label={`${v.id} freigeben`}>Freigeben</Button>}
    </span>
  );
}

const vehicleColumns: Column<RmVehicle>[] = [
  { key: 'id', label: 'Fahrzeug', value: (v) => v.id, mono: true, width: '88px' },
  { key: 'name', label: 'Typ', value: (v) => v.name },
  { key: 'status', label: 'Zustand', value: (v) => v.status, render: (v) => <CodeTag map={VEHICLE_STATE} code={v.status} /> },
  { key: 'tour', label: 'Tour', value: (v) => v.tourId, mono: true, render: (v) => v.tourId ?? '–' },
  { key: 'leg', label: 'Strecke', value: (v) => `${v.from}→${v.to}`, mono: true, render: (v) => (v.from === v.to ? 'Depot' : `${v.from} → ${v.to} · ${pct(v.progress)}`) },
  { key: 'cap', label: 'Kapazität', value: (v) => v.maxKg, align: 'right', mono: true, render: (v) => `${nf(v.maxKg)} kg · ${v.maxPallets} Pal.` },
  { key: 'act', label: 'Befehle', value: () => null, render: (v) => <VehicleActions v={v} /> },
];

/** Deliveries to the customers (Spec 15a): map, tours, vehicles, plan comparison and the logistics dashboard. */
export function RouteMindArea({ tourId }: { tourId: string | null }) {
  const { data } = usePlantQuery<RouteMindData>({ query: 'routemind' }, 2000);
  const [roads, setRoads] = useState<Roads | null>(null);
  const [roadsError, setRoadsError] = useState<string | null>(null);
  const [selected, setSelected] = useState<string | null>(tourId);
  useEffect(() => setSelected(tourId), [tourId]);
  useEffect(() => {
    loadRoads().then(setRoads, (e: Error) => setRoadsError(e.message));
  }, []);

  const tours = data?.tours ?? [];
  const tour = tours.find((t) => t.id === selected) ?? null;
  const k = data?.kpis;
  const solver = data?.plans[0]?.solver ?? '–';
  return (
    <div className="grid grid-cols-12 gap-x-8 gap-y-10 px-4 py-6 lg:px-6 lg:py-8">
      <Section title="Logistik" className="col-span-12" action={<span className="text-[12px] text-txt-3">Löser: {solver}</span>}>
        <div className="grid grid-cols-2 gap-6 md:grid-cols-4 xl:grid-cols-8">
          <Stat label="Aktive Touren" value={k ? k.activeTours : '–'} />
          <Stat label="Auslastung" value={k ? pct(k.utilization) : '–'} hint="Fahrzeuge in Verladung oder unterwegs" />
          <Stat label="Zugestellt" value={k ? k.delivered : '–'} />
          <Stat label="Pünktlich" value={k && k.delivered > 0 ? pct(k.onTime / k.delivered) : '–'} />
          <Stat label="Ø Verspätung" value={k ? nf(k.avgDelayMinutes, 1) : '–'} unit="min" />
          <Stat label="Offen" value={k ? k.open : '–'} />
          <Stat label="Strecke" value={k ? nf(k.km) : '–'} unit="km" />
          <Stat label="Kosten" value={k ? nf(k.cost) : '–'} unit="CHF" />
        </div>
      </Section>

      <Section title="Karte" className="col-span-12 xl:col-span-8"
        action={<Button size="sm" onClick={() => void send({ command: 'replanDeliveries' })}>Neu planen</Button>}>
        {roadsError && <p role="alert" className="text-[13px] text-txt-1">{roadsError}</p>}
        {roads && data && (
          <Suspense fallback={<div className="h-[480px] rounded-md border border-line" />}>
            <RouteMap roads={roads} tours={tours} deliveries={data.deliveries} selected={selected} onSelect={setSelected} />
          </Suspense>
        )}
        <p className="mt-2 text-[12px] text-txt-3">
          {data?.attribution ?? ''}. Kartenkacheln © OpenStreetMap-Mitwirkende, zur Laufzeit von openstreetmap.org geladen.
        </p>
      </Section>

      <Section title={tour ? `Tour ${tour.id}` : 'Tour'} className="col-span-12 xl:col-span-4"
        action={tour && <Button size="sm" variant="ghost" onClick={() => setSelected(null)}>Alle Touren</Button>}>
        {!tour && <p className="text-[13px] text-txt-3">Eine Tour in der Karte oder in der Liste wählen.</p>}
        {tour && (
          <ol className="space-y-2 text-[13px]">
            <li className="text-txt-3">Abfahrt {time(tour.departAtMs)} mit {tour.vehicleId} · <CodeTag map={TOUR_STATE} code={tour.status} /></li>
            {tour.stops.map((s) => {
              const d = data?.deliveries.find((x) => x.id === s.deliveryId);
              return (
                <li key={s.seq} className="flex items-baseline justify-between gap-3">
                  <span><span className="font-mono">{s.seq}.</span> {d?.customerName ?? s.customerId}</span>
                  <span className="font-mono text-txt-2">
                    {s.arrivedAtMs !== null ? `an ${time(s.arrivedAtMs)}` : `ETA ${time(s.plannedArrivalMs)}`}
                  </span>
                </li>
              );
            })}
          </ol>
        )}
      </Section>

      <Section title="Touren" className="col-span-12">
        <DataTable label="Touren" rows={[...tours].reverse()} columns={tourColumns(tours)} rowKey={(t) => t.id}
          onRowClick={(t) => setSelected(t.id)} empty="Noch keine Tour geplant." />
      </Section>

      <Section title="Lieferfahrzeuge" className="col-span-12">
        <DataTable label="Lieferfahrzeuge" rows={data?.vehicles ?? []} columns={vehicleColumns} rowKey={(v) => v.id} />
      </Section>

      <Section title="Lieferungen" className="col-span-12">
        <DataTable label="Lieferungen" rows={[...(data?.deliveries ?? [])].reverse()} columns={deliveryColumns}
          rowKey={(d) => d.id} empty="Noch keine Lieferung." />
      </Section>

      <Section title="Planungen" className="col-span-12">
        <DataTable label="Planungen" rows={data?.plans ?? []} columns={planColumns} rowKey={(p) => p.planId}
          empty="Noch keine Planung. Die erste Welle wird 30 min nach dem Start geplant." />
      </Section>
    </div>
  );
}

import { useEffect, useState } from 'react';
import type { WarehouseInfo } from '../../nexus/types';
import { usePlantQuery } from '../../nexus/usePlantQuery';
import { send, twin, useFactory } from '../../store/factoryStore';
import { STATE_HEX } from '../../ui/format';
import { control, DataTable, Field, Section, type Column } from '../../ui/kit';
import { Button } from '../../ui/primitives';
import { freeCapacity, receiptCommand, stockRows, type StockRow } from './stock';

const columns: Column<StockRow>[] = [
  { key: 'location', label: 'Lagerplatz', value: (r) => r.locationName },
  { key: 'material', label: 'Material', value: (r) => r.materialName },
  { key: 'onHand', label: 'Bestand', value: (r) => r.onHand, align: 'right', mono: true, width: '96px' },
  { key: 'reserved', label: 'Reserviert', value: (r) => r.reserved, align: 'right', mono: true, width: '96px' },
  { key: 'available', label: 'Verfügbar', value: (r) => r.available, align: 'right', mono: true, width: '96px' },
  {
    key: 'reorder',
    label: 'Meldebestand',
    value: (r) => (r.belowReorder ? 1 : 0),
    width: '140px',
    render: (r) =>
      r.belowReorder ? (
        <span className="inline-flex items-center gap-1.5 whitespace-nowrap">
          <span className="h-1.5 w-1.5 rounded-full" style={{ background: STATE_HEX.wait }} aria-hidden="true" />
          unterschritten
        </span>
      ) : (
        <span className="text-txt-3">ok</span>
      ),
  },
];

function Receipt({ info }: { info: WarehouseInfo }) {
  useFactory((s) => s.scene); // free room follows the frames
  const [materialId, setMaterialId] = useState(info.materials[0]?.id ?? '');
  const [locationId, setLocationId] = useState(info.locations[0]?.id ?? '');
  const [quantity, setQuantity] = useState(10);
  const [busy, setBusy] = useState(false);
  useEffect(() => {
    if (!materialId && info.materials[0]) setMaterialId(info.materials[0].id);
    if (!locationId && info.locations[0]) setLocationId(info.locations[0].id);
  }, [info, materialId, locationId]);
  const room = freeCapacity(twin.latest?.stock ?? [], info, locationId);
  return (
    <form
      className="grid grid-cols-1 items-end gap-4 sm:grid-cols-2 xl:grid-cols-[minmax(0,1fr)_minmax(0,1fr)_120px_auto]"
      onSubmit={async (e) => {
        e.preventDefault();
        setBusy(true);
        await send(receiptCommand(materialId, locationId, quantity)); // a refusal shows its reason in the notice line
        setBusy(false);
      }}
      aria-label="Wareneingang"
    >
      <Field label="Material">
        <select className={control} value={materialId} onChange={(e) => setMaterialId(e.target.value)}>
          {info.materials.map((m) => (
            <option key={m.id} value={m.id}>
              {m.name}
            </option>
          ))}
        </select>
      </Field>
      <Field label="Lagerplatz">
        <select className={control} value={locationId} onChange={(e) => setLocationId(e.target.value)}>
          {info.locations.map((l) => (
            <option key={l.id} value={l.id}>
              {l.name}
            </option>
          ))}
        </select>
      </Field>
      <Field label="Menge">
        <input
          type="number"
          inputMode="numeric"
          min={1}
          step={1}
          className={`${control} font-mono tnum`}
          value={quantity}
          onChange={(e) => {
            const n = Math.round(Number(e.target.value));
            if (e.target.value !== '' && Number.isFinite(n) && n >= 1) setQuantity(n);
          }}
        />
      </Field>
      <Button type="submit" variant="primary" disabled={busy || !materialId || !locationId}>
        {busy ? 'Wird gebucht …' : 'Einbuchen'}
      </Button>
      <p className="text-[12px] text-txt-3 sm:col-span-2 xl:col-span-4">
        {room === null ? 'Lagerplatz unbekannt.' : `Freier Platz am Lagerplatz: ${room} Stück.`} Die Anlage prüft Kapazität und Materialart.
      </p>
    </form>
  );
}

/** Stock per location from the frames, names and reorder points from FlowPilot, and the goods receipt. */
export function WarehouseArea() {
  useFactory((s) => s.scene);
  const { data: info, error } = usePlantQuery<WarehouseInfo>({ query: 'warehouse' });
  const rows = info ? stockRows(twin.latest?.stock ?? [], info) : [];
  return (
    <div className="flex flex-col gap-10 px-4 py-6 lg:px-6 lg:py-8">
      <Section title="Bestände">
        {!info ? (
          <p className="text-[13px] text-txt-3">{error ?? 'Stammdaten werden geladen …'}</p>
        ) : (
          <DataTable label="Bestände je Lagerplatz" rows={rows} columns={columns} rowKey={(r) => r.key} empty="Keine Bestände." />
        )}
      </Section>
      {info && (
        <Section title="Wareneingang">
          <Receipt info={info} />
        </Section>
      )}
    </div>
  );
}

import { useState } from 'react';
import type { KpiBucket, Kpis } from '../../nexus/types';
import { usePlantQuery } from '../../nexus/usePlantQuery';
import { hrefOf } from '../../shell/route';
import { clock, duration, nf, pct } from '../../ui/format';
import { BarList, Columns, Section, Stat, StatusStack } from '../../ui/kit';
import { Segmented } from '../../ui/primitives';
import { SLOTS, WINDOWS, seriesSlots } from './series';

/** Figures from the core's events and state intervals (Spec 10); the interface only formats and draws them. */
export function AnalyticsArea() {
  const [minutes, setMinutes] = useState(60);
  const { data: k } = usePlantQuery<Kpis>({ query: 'kpis', windowMinutes: minutes }, 2_000);
  const { data: series } = usePlantQuery<KpiBucket[]>({ query: 'kpiSeries', bucketMinutes: minutes / SLOTS, buckets: SLOTS }, 5_000);
  const flow = k ? k.throughputPerHour > 0 : false;
  const machines = k ? Object.entries(k.machines) : [];
  const labels = Array.from({ length: SLOTS }, (_, i) => {
    const b = series?.[series.length - SLOTS + i];
    return b ? clock(b.toMs / 1000, false) : '';
  });
  return (
    <div className="flex flex-col gap-10 px-4 py-6 lg:px-6 lg:py-8">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <p className="text-[13px] text-txt-2">Fenster der Auswertung, bis jetzt</p>
        <Segmented ariaLabel="Zeitfenster" value={minutes} onChange={setMinutes} options={WINDOWS.map((w) => ({ value: w.minutes, label: w.label }))} />
      </div>

      <div className="grid grid-cols-2 gap-x-8 gap-y-8 lg:grid-cols-4">
        <Stat label="Durchsatz" value={k ? nf(k.throughputPerHour, k.throughputPerHour < 10 ? 1 : 0).replace(/,0$/, '') : '–'} unit="Aufträge/h" />
        <Stat label="Termintreue" value={k && flow ? pct(k.onTimeRate) : '–'} />
        <Stat label="Durchlaufzeit" value={k && flow ? duration(k.avgLeadTimeS) : '–'} hint="Mittel" />
        <Stat label="Durchlaufzeit P90" value={k && flow ? duration(k.p90LeadTimeS) : '–'} hint="9 von 10 Aufträgen schneller" />
      </div>

      <Section
        title="OEE je Maschine"
        action={
          k?.bottleneckMachineId && flow ? (
            <span className="text-[12px] text-txt-2">
              Engpass <span className="font-mono text-txt-1">{k.bottleneckMachineId}</span>
            </span>
          ) : undefined
        }
      >
        <BarList
          label="OEE je Maschine"
          max={1}
          rows={machines.map(([id, m]) => ({
            id,
            label: (
              <a href={hrefOf('machines', id)} className="font-mono hover:underline">
                {id}
              </a>
            ),
            value: Number.isFinite(m.oee) ? m.oee : null,
            text: `${pct(m.oee)} (V ${pct(m.availability)} · L ${pct(m.performance)} · Q ${pct(m.quality)})`,
          }))}
        />
        <p className="mt-2 text-[12px] text-txt-3">OEE = Verfügbarkeit (V) × Leistung (L) × Qualität (Q).</p>
      </Section>

      <Section title="Zeit je Zustand">
        <ul className="flex flex-col">
          {machines.map(([id, m]) => (
            <li key={id} className="grid grid-cols-[80px_minmax(0,1fr)_64px] items-center gap-3 border-b border-line py-2 last:border-b-0">
              <span className="font-mono text-[12px] text-txt-1">{id}</span>
              <StatusStack timeInStatusMs={m.timeInStatusMs} label={`Zeit je Zustand ${id}`} />
              <span className="text-right font-mono text-[12px] text-txt-2 tnum" title="Auslastung">
                {pct(m.utilization)}
              </span>
            </li>
          ))}
        </ul>
      </Section>

      <div className="grid grid-cols-1 gap-x-8 gap-y-10 lg:grid-cols-2">
        <Section title="Durchsatz im Verlauf">
          <Columns
            label="Durchsatz je Abschnitt"
            values={seriesSlots(series ?? [], (b) => b.throughputPerHour)}
            labels={labels}
            format={(v) => `${nf(v, 1)}/h`}
          />
        </Section>
        <Section title="OEE im Verlauf">
          <Columns label="OEE je Abschnitt" values={seriesSlots(series ?? [], (b) => b.oee)} labels={labels} format={(v) => pct(v)} />
        </Section>
      </div>
    </div>
  );
}

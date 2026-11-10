import { useState } from 'react';
import { DEFAULT_MACHINE_CONFIG } from '../../sim/config';
import { useFactory } from '../../store/factoryStore';
import { Sparkline, TimeChart } from '../charts/TimeChart';
import { AGV_COLOR, AGV_LABEL, compactDuration, nf, pct, STATUS_COLOR, STATUS_SHORT } from '../format';
import { Card, cx, Meter, SectionTitle, Segmented, StatusDot } from '../primitives';

function Tile({ label, value, unit, sub, spark, tone }: { label: string; value: string; unit?: string; sub?: string; spark?: number[]; tone?: 'warn' | 'bad' }) {
  return (
    <div className="rounded-lg border border-line bg-ink-2/80 px-2.5 pb-2 pt-2">
      <div className="text-[10px] font-medium uppercase tracking-[0.12em] text-txt-3">{label}</div>
      <div className="mt-1 flex items-baseline gap-1">
        <span className={cx('font-mono text-[19px] font-semibold leading-none tracking-tight tnum', tone === 'bad' ? 'text-red-300' : tone === 'warn' ? 'text-amber-200' : 'text-txt-1')}>
          {value}
        </span>
        {unit && <span className="text-[10px] text-txt-3">{unit}</span>}
      </div>
      {sub && <div className="mt-0.5 truncate text-[10px] text-txt-3">{sub}</div>}
      {spark && (
        <div className="mt-1.5">
          <Sparkline values={spark.slice(-60)} />
        </div>
      )}
    </div>
  );
}

type ChartKey = 'throughput' | 'power' | 'avgTemp' | 'wip' | 'oee';

const CHARTS: Record<ChartKey, { label: string; title: string; unit: string; digits: number }> = {
  throughput: { label: 'Durchsatz', title: 'Durchsatz (gleitend 60 min)', unit: 'Teile/h', digits: 0 },
  power: { label: 'Leistung', title: 'Leistungsaufnahme Halle', unit: 'kW', digits: 0 },
  avgTemp: { label: 'Temperatur', title: 'Ø Temperatur CNC-Zentren', unit: '°C', digits: 1 },
  wip: { label: 'WIP', title: 'Umlaufbestand (WIP)', unit: 'Teile', digits: 0 },
  oee: { label: 'OEE', title: 'Gesamtanlageneffektivität', unit: '%', digits: 1 },
};

export function KpiPanel() {
  const kpi = useFactory((s) => s.snap.kpi);
  const h = useFactory((s) => s.snap.history);
  const rules = useFactory((s) => s.snap.rules);
  const [chart, setChart] = useState<ChartKey>('throughput');
  const def = CHARTS[chart];
  const series = chart === 'oee' ? h.oee.map((v) => v * 100) : h[chart];
  const refs =
    chart === 'avgTemp'
      ? [{ value: DEFAULT_MACHINE_CONFIG.tempWarn, label: `Warngrenze ${DEFAULT_MACHINE_CONFIG.tempWarn} °C`, color: '#fab219' }]
      : chart === 'wip'
        ? [{ value: rules.wipLimit, label: `WIP-Limit ${rules.wipLimit}`, color: '#a3adb9' }]
        : [];

  return (
    <div className="space-y-3">
      <div className="grid grid-cols-2 gap-2">
        <Tile label="Durchsatz" value={nf(kpi.throughput)} unit="Teile/h" sub={`${nf(kpi.totalGood)} Gutteile gesamt`} spark={h.throughput} />
        <Tile label="OEE" value={nf(kpi.oee * 100, 1)} unit="%" sub={`V ${pct(kpi.availability)} · L ${pct(kpi.performance)} · Q ${pct(kpi.quality)}`} spark={h.oee} tone={kpi.oee < 0.25 ? 'warn' : undefined} />
        <Tile label="Auslastung" value={nf(kpi.utilization * 100)} unit="%" sub={`AGV-Flotte ${pct(kpi.agvUtilization)}`} spark={h.utilization} />
        <Tile label="Leistung" value={nf(kpi.powerKW)} unit="kW" sub={`${nf(kpi.energyKWh)} kWh · ${nf(kpi.energyPerPart, 2)} kWh/Teil`} spark={h.power} />
        <Tile label="WIP" value={nf(kpi.wip)} unit="Teile" sub={`${kpi.activeTransports} Transporte aktiv`} spark={h.wip} tone={kpi.wip >= rules.wipLimit ? 'warn' : undefined} />
        <Tile
          label="Ausschuss"
          value={nf(kpi.scrapRate * 100, 1)}
          unit="%"
          sub={`${kpi.totalScrap} Teile n.i.O.`}
          tone={kpi.scrapRate > 0.08 ? 'bad' : kpi.scrapRate > 0.04 ? 'warn' : undefined}
        />
        <Tile label="Liefertreue" value={nf(kpi.onTimeRate * 100)} unit="%" sub={`${kpi.openOrders} offene Aufträge`} tone={kpi.onTimeRate < 0.7 ? 'warn' : undefined} />
        <Tile label="Ø Durchlaufzeit" value={kpi.avgLeadTime ? compactDuration(kpi.avgLeadTime) : '–'} sub="Freigabe → Fertiglager" />
      </div>

      <Card>
        <SectionTitle>{def.title}</SectionTitle>
        <div className="mb-2 overflow-x-auto scroll-thin">
          <Segmented size="sm" value={chart} onChange={setChart} options={(Object.keys(CHARTS) as ChartKey[]).map((k) => ({ value: k, label: CHARTS[k].label }))} />
        </div>
        <TimeChart
          t={h.t}
          values={series}
          unit={def.unit}
          digits={def.digits}
          refs={refs}
          min={chart === 'oee' || chart === 'wip' || chart === 'throughput' ? 0 : undefined}
          label={def.title}
          height={120}
        />
      </Card>

      <MachineList />
      <FleetList />
    </div>
  );
}

function MachineList() {
  const machines = useFactory((s) => s.snap.machines);
  const conveyors = useFactory((s) => s.snap.conveyors);
  const inventory = useFactory((s) => s.snap.inventory);
  const oee = useFactory((s) => s.snap.oee);
  const selected = useFactory((s) => s.selected);
  const select = useFactory((s) => s.select);
  return (
    <Card className="!p-0">
      <div className="px-3 pt-3">
        <SectionTitle right={<span className="text-[10px] text-txt-3">Auslastung 10 min</span>}>Anlagen</SectionTitle>
      </div>
      <ul className="pb-1.5">
        {machines.map((m) => {
          const active = selected?.id === m.id;
          return (
            <li key={m.id}>
              <button
                aria-pressed={active}
                onClick={() => select({ type: 'machine', id: m.id })}
                onDoubleClick={() => select({ type: 'machine', id: m.id }, true)}
                className={cx('grid w-full grid-cols-[auto_1fr_auto] items-center gap-x-2.5 px-3 py-1.5 text-left transition-colors hover:bg-white/[0.03]', active && 'bg-teal-400/[0.06]')}
              >
                <StatusDot color={STATUS_COLOR[m.status]} pulse={m.status === 'FAULT'} />
                <span className="min-w-0">
                  <span className="flex items-baseline gap-1.5">
                    <span className="font-mono text-[11px] font-semibold text-txt-1">{m.id}</span>
                    <span className="truncate text-[11px] text-txt-3">{STATUS_SHORT[m.status]}</span>
                  </span>
                  <span className="mt-1 block">
                    <Meter value={m.stats.utilEma} color="#3987e5" height={3} />
                  </span>
                </span>
                <span className="text-right">
                  <span className="block font-mono text-[11px] text-txt-1 tnum">{pct(m.stats.utilEma)}</span>
                  <span className="block font-mono text-[10px] text-txt-3 tnum">OEE {pct(oee[m.id]?.oee ?? 0)}</span>
                </span>
              </button>
            </li>
          );
        })}
        {conveyors.map((c) => {
          const active = selected?.id === c.id;
          const color = c.status === 'RUNNING' ? '#22c55e' : c.status === 'STOPPED' ? '#94a3b8' : c.status === 'FAULT' ? '#ef4444' : '#fb923c';
          return (
            <li key={c.id}>
              <button
                aria-pressed={active}
                onClick={() => select({ type: 'conveyor', id: c.id })}
                onDoubleClick={() => select({ type: 'conveyor', id: c.id }, true)}
                className={cx('flex w-full items-center gap-2.5 px-3 py-1 text-left hover:bg-white/[0.03]', active && 'bg-teal-400/[0.06]')}
              >
                <StatusDot color={color} size={6} />
                <span className="font-mono text-[11px] text-txt-2">{c.id}</span>
                <span className="truncate text-[11px] text-txt-3">{c.name}</span>
                <span className="ml-auto font-mono text-[10px] text-txt-3 tnum">{c.items.length} Teile</span>
              </button>
            </li>
          );
        })}
        {(
          [
            { type: 'raw', id: 'RAW', label: 'Rohlager', value: `${inventory.raw} / ${inventory.rawCapacity}`, low: inventory.raw <= inventory.reorderPoint },
            {
              type: 'finished',
              id: 'FG',
              label: 'Fertigwarenlager',
              value: `${Object.values(inventory.finished).reduce((a, b) => a + b, 0)} / ${inventory.finishedCapacity}`,
              low: false,
            },
          ] as const
        ).map((w) => (
          <li key={w.id}>
            <button
              aria-pressed={selected?.id === w.id}
              onClick={() => select({ type: w.type, id: w.id })}
              onDoubleClick={() => select({ type: w.type, id: w.id }, true)}
              className={cx('flex w-full items-center gap-2.5 px-3 py-1 text-left hover:bg-white/[0.03]', selected?.id === w.id && 'bg-teal-400/[0.06]')}
            >
              <StatusDot color={w.low ? '#fab219' : '#22c55e'} size={6} />
              <span className="text-[11px] text-txt-2">{w.label}</span>
              <span className="ml-auto font-mono text-[10px] text-txt-3 tnum">{w.value}</span>
            </button>
          </li>
        ))}
      </ul>
    </Card>
  );
}

function FleetList() {
  const agvs = useFactory((s) => s.snap.agvs);
  const selected = useFactory((s) => s.selected);
  const select = useFactory((s) => s.select);
  return (
    <Card className="!p-0">
      <div className="px-3 pt-3">
        <SectionTitle right={<span className="text-[10px] text-txt-3">Akku</span>}>AGV-Flotte</SectionTitle>
      </div>
      <ul className="pb-1.5">
        {agvs.map((a) => {
          const col = a.fault ? '#ef4444' : AGV_COLOR[a.state];
          return (
            <li key={a.id}>
              <button
                onClick={() => select({ type: 'agv', id: a.id })}
                onDoubleClick={() => select({ type: 'agv', id: a.id }, true)}
                className={cx('grid w-full grid-cols-[auto_1fr_64px] items-center gap-2.5 px-3 py-1.5 text-left hover:bg-white/[0.03]', selected?.id === a.id && 'bg-teal-400/[0.06]')}
              >
                <StatusDot color={col} />
                <span className="min-w-0">
                  <span className="font-mono text-[11px] font-semibold text-txt-1">{a.id}</span>
                  <span className="ml-1.5 truncate text-[11px] text-txt-3">{a.fault ? 'Störung' : AGV_LABEL[a.state]}</span>
                </span>
                <span>
                  <span className="mb-1 block text-right font-mono text-[10px] text-txt-2 tnum">{Math.round(a.battery)} %</span>
                  <Meter value={a.battery / 100} color={a.battery < 25 ? '#fab219' : '#22c55e'} height={3} />
                </span>
              </button>
            </li>
          );
        })}
      </ul>
    </Card>
  );
}

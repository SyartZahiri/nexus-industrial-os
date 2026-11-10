import { useState, type ReactNode } from 'react';
import { FAULT_TYPES, PRODUCT_IDS, PRODUCTS, PROCESS_LABEL } from '../../sim/config';
import type { Agv, Conveyor, Machine, MachineStatus } from '../../sim/types';
import { command, useFactory } from '../../store/factoryStore';
import { TimeChart } from '../charts/TimeChart';
import {
  AGV_COLOR,
  AGV_LABEL,
  CONVEYOR_COLOR,
  CONVEYOR_LABEL,
  duration,
  nf,
  pct,
  STATUS_COLOR,
  STATUS_LABEL,
  STATUS_SHORT,
} from '../format';
import { Badge, Button, Card, Icon, KV, Meter, SectionTitle, Slider, Toggle } from '../primitives';

function Header({ id, title, subtitle, badge }: { id: string; title: string; subtitle: string; badge: ReactNode }) {
  const select = useFactory((s) => s.select);
  const selected = useFactory((s) => s.selected);
  return (
    <div className="mb-3 flex items-start gap-2">
      <div className="min-w-0 flex-1">
        <div className="flex items-center gap-2">
          <span className="font-mono text-sm font-semibold text-txt-1">{id}</span>
          {badge}
        </div>
        <div className="mt-0.5 truncate text-xs text-txt-2">{title}</div>
        <div className="truncate text-[11px] text-txt-3">{subtitle}</div>
      </div>
      <button
        className="rounded p-1.5 text-txt-3 hover:bg-white/5 hover:text-txt-1"
        title="Kamera fokussieren (F)"
        onClick={() => selected && select(selected, true)}
        aria-label="Kamera fokussieren"
      >
        {Icon.focus}
      </button>
      <button className="rounded p-1.5 text-txt-3 hover:bg-white/5 hover:text-txt-1" title="Auswahl aufheben (Esc)" onClick={() => select(null)} aria-label="Schließen">
        {Icon.close}
      </button>
    </div>
  );
}

function WpChip({ id }: { id: string }) {
  const info = useFactory((s) => s.snap.wpInfo[id]);
  if (!info) return null;
  const p = PRODUCTS[info.product as keyof typeof PRODUCTS];
  return (
    <span className="inline-flex items-center gap-1 rounded border border-line-2 bg-ink-3 px-1.5 py-0.5 font-mono text-[10px] text-txt-2" title={`${p.name} · ${info.orderId}`}>
      <span className="h-1.5 w-1.5 rounded-sm" style={{ background: p.color }} />
      {id.replace('WP-', '')}
      <span className="text-txt-3">{p.shortName}</span>
    </span>
  );
}

function StateBar({ m }: { m: Machine }) {
  const t = m.stats.times;
  const total = Object.values(t).reduce((a, b) => a + b, 0) || 1;
  const order: MachineStatus[] = ['RUNNING', 'SETUP', 'IDLE', 'BLOCKED', 'STOPPED', 'MAINTENANCE', 'REPAIR', 'FAULT'];
  const parts = order.filter((k) => t[k] / total > 0.002);
  return (
    <div>
      <div className="flex h-2 w-full gap-[2px] overflow-hidden rounded-full">
        {parts.map((k) => (
          <div key={k} title={`${STATUS_SHORT[k]}: ${pct(t[k] / total, 1)}`} style={{ width: `${(t[k] / total) * 100}%`, background: STATUS_COLOR[k] }} />
        ))}
      </div>
      <div className="mt-2 grid grid-cols-2 gap-x-3 gap-y-1">
        {parts.map((k) => (
          <div key={k} className="flex items-center gap-1.5 text-[10px]">
            <span className="h-2 w-2 rounded-sm" style={{ background: STATUS_COLOR[k] }} />
            <span className="text-txt-3">{STATUS_SHORT[k]}</span>
            <span className="ml-auto font-mono text-txt-2 tnum">{pct(t[k] / total, 1)}</span>
          </div>
        ))}
      </div>
    </div>
  );
}

function FaultInjector({ id, kind }: { id: string; kind: Machine['kind'] | 'conveyor' | 'agv' }) {
  const types = FAULT_TYPES.filter((f) => f.kinds.includes(kind));
  const [ft, setFt] = useState(types[0]?.id ?? '');
  return (
    <div className="flex gap-1.5">
      <select
        value={ft}
        onChange={(e) => setFt(e.target.value)}
        className="h-8 min-w-0 flex-1 rounded-md border border-line-2 bg-ink-3 px-2 text-xs text-txt-1"
        aria-label="Störungsart"
      >
        {types.map((f) => (
          <option key={f.id} value={f.id}>
            {f.label}
            {f.scrapsWorkpiece ? ' (Ausschuss)' : ''}
          </option>
        ))}
      </select>
      <Button variant="danger" onClick={() => command((e) => e.injectFault(id, ft))}>
        {Icon.bolt}
        Auslösen
      </Button>
    </div>
  );
}

/* ====================================================================== */

function MachineDetail({ m }: { m: Machine }) {
  const history = useFactory((s) => s.snap.history);
  const oee = useFactory((s) => s.snap.oee[m.id]);
  const time = useFactory((s) => s.snap.time);
  const rules = useFactory((s) => s.snap.rules);
  const wpInfo = useFactory((s) => (m.job ? s.snap.wpInfo[m.job.wpId] : undefined));
  const job = m.job;
  const product = wpInfo ? PRODUCTS[wpInfo.product as keyof typeof PRODUCTS] : null;
  const stepIdx = wpInfo ? (job?.finished ? wpInfo.step - 1 : wpInfo.step) : -1;
  const step = product && stepIdx >= 0 ? product.routing[stepIdx] : null;
  const down = m.status === 'FAULT' || m.status === 'REPAIR' || m.status === 'MAINTENANCE';
  const canStart = m.status === 'STOPPED';
  const canStop = !['STOPPED'].includes(m.status) && !(down && m.resumeStopped);
  const tempColor = m.sensors.temperature >= m.config.tempTrip ? '#ef4444' : m.sensors.temperature >= m.config.tempWarn ? '#fab219' : '#3987e5';

  return (
    <div className="space-y-3">
      <Header id={m.id} title={m.name} subtitle={`${m.model} · ${m.capabilities.map((c) => PROCESS_LABEL[c]).join(' / ')}`} badge={<Badge color={STATUS_COLOR[m.status]}>{STATUS_SHORT[m.status]}</Badge>} />

      <Card>
        <div className="text-xs text-txt-1">{STATUS_LABEL[m.status]}</div>
        {m.fault && (
          <div className="mt-1.5 flex items-center gap-1.5 text-[11px] text-red-300">
            {Icon.alert}
            {m.fault.label} · seit {duration(time - m.fault.since)}
          </div>
        )}
        {m.status === 'FAULT' && (
          <div className="mt-1 text-[11px] text-txt-3">
            {rules.autoRepair ? `Instandhaltung trifft in ca. ${duration(Math.max(0, m.repairDelay))} ein` : 'Automatische Instandsetzung ist aus, bitte manuell starten'}
          </div>
        )}
        {m.status === 'REPAIR' && <div className="mt-1 text-[11px] text-txt-3">Fertig in ca. {duration(m.repairRemaining)}</div>}
        {m.status === 'MAINTENANCE' && <div className="mt-1 text-[11px] text-txt-3">Wartung, fertig in ca. {duration(m.maintenanceRemaining)}</div>}
        {m.status === 'SETUP' && <div className="mt-1 text-[11px] text-txt-3">Umrüsten auf {product?.shortName}, noch ca. {duration(m.setupRemaining)}</div>}
        {job && product && (
          <div className="mt-2.5">
            <div className="mb-1 flex items-center justify-between gap-2 text-[11px]">
              <span className="flex min-w-0 items-center gap-1.5">
                <WpChip id={job.wpId} />
                <span className="truncate text-txt-3">
                  {wpInfo?.orderId} · {step ? PROCESS_LABEL[step.process] : ''}
                </span>
              </span>
              <span className="font-mono text-txt-1 tnum">{job.finished ? 'fertig' : pct(job.progress / job.duration)}</span>
            </div>
            <Meter value={job.finished ? 1 : job.progress / job.duration} color={STATUS_COLOR[m.status]} />
            {!job.finished && <div className="mt-1 text-right text-[10px] text-txt-3">Restzeit {duration(job.duration - job.progress)}</div>}
          </div>
        )}
      </Card>

      <Card>
        <SectionTitle>Steuerung</SectionTitle>
        <div className="grid grid-cols-2 gap-1.5">
          <Button variant="primary" disabled={!canStart} onClick={() => command((e) => e.startMachine(m.id))}>
            {Icon.play}Starten
          </Button>
          <Button disabled={!canStop} onClick={() => command((e) => e.stopMachine(m.id))}>
            {Icon.stop}Stoppen
          </Button>
          <Button variant="warn" disabled={m.status !== 'FAULT'} onClick={() => command((e) => e.repair(m.id))}>
            {Icon.wrench}Instandsetzen
          </Button>
          <Button disabled={down} onClick={() => command((e) => e.maintain(m.id))} title="Vorbeugende Wartung, setzt den Verschleiß zurück">
            {Icon.check}Wartung
          </Button>
        </div>
        <div className="mt-2.5">
          <div className="mb-1 text-[10px] uppercase tracking-[0.12em] text-txt-3">Störung simulieren</div>
          <FaultInjector id={m.id} kind={m.kind} />
        </div>
      </Card>

      <Card>
        <SectionTitle right={<span className="font-mono text-[11px] text-txt-1 tnum" style={{ color: tempColor === '#3987e5' ? undefined : tempColor }}>{nf(m.sensors.temperature, 1)} °C</span>}>
          Temperatur
        </SectionTitle>
        <TimeChart
          t={history.t}
          values={history.machineTemp[m.id] ?? []}
          unit="°C"
          digits={1}
          color="#3987e5"
          refs={[
            { value: m.config.tempWarn, label: `Warnung ${m.config.tempWarn}`, color: '#fab219' },
            { value: m.config.tempTrip, label: `Abschaltung ${m.config.tempTrip}`, color: '#d03b3b' },
          ]}
          label={`Temperaturverlauf ${m.id}`}
          height={104}
        />
        <div className="mt-3 grid grid-cols-3 gap-2">
          <Sensor label="Leistung" value={nf(m.sensors.power, 1)} unit="kW" />
          <Sensor label="Vibration" value={nf(m.sensors.vibration, 2)} unit="mm/s" warn={m.sensors.vibration > 3.5} />
          <Sensor label={m.kind === 'cnc' ? 'Spindel' : m.kind === 'robot' ? 'Achsen' : 'Scanrate'} value={nf(m.sensors.spindle)} unit={m.kind === 'cnc' ? 'U/min' : m.kind === 'robot' ? '%' : 'Hz'} />
        </div>
        <div className="mt-3">
          <div className="mb-1 flex justify-between text-[11px]">
            <span className="text-txt-3">Verschleiß</span>
            <span className="font-mono text-txt-1 tnum">{pct(m.wear)}</span>
          </div>
          <Meter value={m.wear} color={m.wear >= 0.8 ? '#ec835a' : m.wear >= 0.6 ? '#fab219' : '#22c55e'} />
          <div className="mt-1 text-[10px] text-txt-3">
            Steigt mit dem Quadrat der Last und schneller ohne Kühlung. Wartungsschwelle {pct(rules.maintenanceThreshold)}
            {rules.autoMaintenance ? ' (automatisch)' : ''}
          </div>
        </div>
      </Card>

      {(m.input.type === 'buffer' || m.output.type === 'buffer') && (
        <Card>
          <SectionTitle>Puffer</SectionTitle>
          <div className="space-y-2 text-[11px]">
            <div>
              <div className="mb-1 flex justify-between text-txt-3">
                <span>Eingang</span>
                <span className="font-mono tnum">
                  {m.inBuffer.length}/{m.input.type === 'buffer' ? m.input.capacity : 0}
                  {m.reservedIn ? ` · ${m.reservedIn} unterwegs` : ''}
                </span>
              </div>
              <div className="flex min-h-5 flex-wrap gap-1">{m.inBuffer.length ? m.inBuffer.map((id) => <WpChip key={id} id={id} />) : <span className="text-txt-3">leer</span>}</div>
            </div>
            <div>
              <div className="mb-1 flex justify-between text-txt-3">
                <span>Ausgang</span>
                <span className="font-mono tnum">
                  {m.outBuffer.length}/{m.output.type === 'buffer' ? m.output.capacity : 0}
                </span>
              </div>
              <div className="flex min-h-5 flex-wrap gap-1">{m.outBuffer.length ? m.outBuffer.map((id) => <WpChip key={id} id={id} />) : <span className="text-txt-3">leer</span>}</div>
            </div>
          </div>
        </Card>
      )}

      <Card>
        <SectionTitle>Konfiguration</SectionTitle>
        <Slider
          label={m.kind === 'cnc' ? 'Vorschub-Override' : 'Geschwindigkeits-Override'}
          value={m.config.override}
          min={50}
          max={130}
          step={5}
          unit="%"
          onChange={(v) => command((e) => e.configureMachine(m.id, { override: v }))}
        />
        <p className="-mt-0.5 mb-1 text-[10px] leading-snug text-txt-3">Mehr Override heißt kürzere Taktzeit. Leistung steigt mit Override¹·⁶, Wärme mit Override², dazu Verschleiß und Ausschussrisiko.</p>
        {m.kind === 'cnc' && (
          <Toggle
            checked={m.config.coolant}
            onChange={(v) => command((e) => e.configureMachine(m.id, { coolant: v }))}
            label="Kühlschmierstoff"
            hint="Senkt die Spindeltemperatur um etwa 11 °C, die Pumpe braucht 1,4 kW"
          />
        )}
        <Slider label="Temperatur-Warngrenze" value={m.config.tempWarn} min={40} max={90} unit="°C" onChange={(v) => command((e) => e.configureMachine(m.id, { tempWarn: v }))} />
        <Slider label="Abschaltgrenze (Thermoschutz)" value={m.config.tempTrip} min={50} max={100} unit="°C" onChange={(v) => command((e) => e.configureMachine(m.id, { tempTrip: v }))} />
      </Card>

      <Card>
        <SectionTitle>Kennzahlen</SectionTitle>
        <div className="mb-3 grid grid-cols-4 gap-2 text-center">
          {[
            ['OEE', oee?.oee ?? 0],
            ['Verfügb.', oee?.a ?? 0],
            ['Leistung', oee?.p ?? 0],
            ['Qualität', oee?.q ?? 0],
          ].map(([k, v]) => (
            <div key={k as string} className="rounded-md border border-line bg-ink-1 py-1.5">
              <div className="font-mono text-[13px] font-semibold text-txt-1 tnum">{nf((v as number) * 100)}%</div>
              <div className="text-[10px] uppercase tracking-wider text-txt-3">{k}</div>
            </div>
          ))}
        </div>
        <KV k="Gutteile" v={nf(m.stats.partsGood)} />
        <KV k="Ausschuss (verursacht)" v={nf(m.stats.partsScrap)} />
        <KV k="Störungen" v={nf(m.stats.faults)} />
        <KV k="Energie" v={`${nf(m.stats.energyKWh, 1)} kWh`} />
        <div className="mt-3">
          <div className="mb-1.5 text-[10px] uppercase tracking-[0.12em] text-txt-3">Zeitanteile seit Start</div>
          <StateBar m={m} />
        </div>
      </Card>
    </div>
  );
}

function Sensor({ label, value, unit, warn }: { label: string; value: string; unit: string; warn?: boolean }) {
  return (
    <div className="rounded-md border border-line bg-ink-1 px-2 py-1.5">
      <div className="text-[10px] uppercase tracking-wider text-txt-3">{label}</div>
      <div className={`font-mono text-[13px] font-semibold tnum ${warn ? 'text-amber-200' : 'text-txt-1'}`}>
        {value}
        <span className="ml-0.5 text-[10px] font-normal text-txt-3">{unit}</span>
      </div>
    </div>
  );
}

/* ====================================================================== */

function ConveyorDetail({ c }: { c: Conveyor }) {
  const machines = useFactory((s) => s.snap.machines);
  const feeds = machines.filter((m) => m.input.type === 'conveyor' && m.input.conveyorId === c.id).map((m) => m.id);
  const fedBy = machines.filter((m) => m.output.type === 'conveyor' && m.output.conveyorId === c.id).map((m) => m.id);
  const cap = Math.floor(c.length / c.spacing);
  return (
    <div className="space-y-3">
      <Header id={c.id} title={c.name} subtitle={`${nf(c.length, 1)} m · Rollenförderer`} badge={<Badge color={CONVEYOR_COLOR[c.status]}>{CONVEYOR_LABEL[c.status]}</Badge>} />
      <Card>
        <SectionTitle>Steuerung</SectionTitle>
        <div className="grid grid-cols-2 gap-1.5">
          <Button variant="primary" disabled={c.status !== 'STOPPED'} onClick={() => command((e) => e.startConveyor(c.id))}>
            {Icon.play}Starten
          </Button>
          <Button disabled={c.status !== 'RUNNING'} onClick={() => command((e) => e.stopConveyor(c.id))}>
            {Icon.stop}Stoppen
          </Button>
          <Button variant="warn" className="col-span-2" disabled={c.status !== 'FAULT'} onClick={() => command((e) => e.repair(c.id))}>
            {Icon.wrench}Reparatur starten
          </Button>
        </div>
        {c.fault && <div className="mt-2 text-[11px] text-red-300">{c.fault.label}</div>}
        <div className="mt-2.5">
          <FaultInjector id={c.id} kind="conveyor" />
        </div>
        <Slider label="Bandgeschwindigkeit" value={c.speed} min={0.1} max={1.2} step={0.05} unit="m/s" format={(v) => nf(v, 2)} onChange={(v) => command((e) => e.setConveyorSpeed(c.id, v))} />
      </Card>
      <Card>
        <SectionTitle>Materialfluss</SectionTitle>
        <KV k="Belegung" v={`${c.items.length} / ${cap}`} />
        <div className="my-1.5">
          <Meter value={c.items.length / cap} color="#3987e5" />
        </div>
        <KV k="Reserviert (AGV unterwegs)" v={c.reserved} />
        <KV k="Speist" v={feeds.join(', ') || 'AGV-Abholung'} />
        <KV k="Gespeist von" v={[c.dockNode ? 'AGV' : null, ...fedBy].filter(Boolean).join(', ')} />
        <KV k="Transportiert" v={nf(c.stats.transported)} />
        <KV k="Leistung" v={`${nf(c.power, 2)} kW`} />
        <KV k="Energie" v={`${nf(c.stats.energyKWh, 2)} kWh`} />
        <div className="mt-2 flex flex-wrap gap-1">
          {c.items.map((it) => (
            <WpChip key={it.wpId} id={it.wpId} />
          ))}
        </div>
      </Card>
    </div>
  );
}

function AgvDetail({ a }: { a: Agv }) {
  const time = useFactory((s) => s.snap.time);
  const planner = useFactory((s) => s.snap.plannerLabel);
  const col = a.fault ? '#ef4444' : AGV_COLOR[a.state];
  const nodeLabel = (n: string) =>
    n
      .replace('D_RAW', 'Rohlager')
      .replace('D_FG', 'Fertiglager')
      .replace(/D_CNC0(\d)/, 'CNC-0$1')
      .replace(/D_CV0(\d)/, 'CV-0$1')
      .replace(/D_CH(\d)/, 'Ladestation $1');
  return (
    <div className="space-y-3">
      <Header id={a.id} title={a.name} subtitle={`Unterfahr-AGV · Lidar-Navigation · ${planner}`} badge={<Badge color={col}>{a.fault ? 'Störung' : AGV_LABEL[a.state]}</Badge>} />
      <Card>
        <div className="mb-1 flex justify-between text-[11px]">
          <span className="text-txt-3">Akku</span>
          <span className="font-mono text-txt-1 tnum">{nf(a.battery, 1)} %</span>
        </div>
        <Meter value={a.battery / 100} color={a.battery < 25 ? '#fab219' : '#22c55e'} />
        <div className="mt-3 space-y-0.5">
          <KV k="Geschwindigkeit" v={`${nf(a.speed, 2)} m/s`} />
          <KV k="Position" v={`x ${nf(a.pos.x, 1)} · z ${nf(a.pos.z, 1)}`} />
          {a.blockedBy && a.speed < 0.05 && a.route.length > 0 && <KV k="Wartet auf" v={a.blockedBy} />}
          {a.task ? (
            <>
              <KV k="Transportauftrag" v={a.task.id} />
              <KV k="Route" v={`${nodeLabel(a.task.fromNode)} → ${nodeLabel(a.task.toNode)}`} />
              <KV k="Ladung" v={a.load ? <WpChip id={a.load} /> : 'leer (Anfahrt)'} />
            </>
          ) : (
            <KV k="Transportauftrag" v="–" />
          )}
          {a.fault && <KV k="Störung" v={`${a.fault.label} · ${duration(time - a.fault.since)}`} />}
        </div>
      </Card>
      <Card>
        <SectionTitle>Steuerung</SectionTitle>
        <div className="grid grid-cols-2 gap-1.5">
          <Button onClick={() => command((e) => e.sendAgvToCharge(a.id))} disabled={a.forcedCharge || a.state === 'CHARGING'}>
            {Icon.bolt}Laden
          </Button>
          <Button variant={a.enabled ? 'default' : 'primary'} onClick={() => command((e) => e.setAgvEnabled(a.id, !a.enabled))}>
            {a.enabled ? (
              <>
                {Icon.stop}Außer Betrieb
              </>
            ) : (
              <>
                {Icon.play}In Betrieb
              </>
            )}
          </Button>
          <Button variant="warn" className="col-span-2" disabled={!a.fault} onClick={() => command((e) => e.repair(a.id))}>
            {Icon.check}Störung quittieren
          </Button>
        </div>
        <div className="mt-2.5">
          <FaultInjector id={a.id} kind="agv" />
        </div>
      </Card>
      <Card>
        <SectionTitle>Statistik</SectionTitle>
        <KV k="Transporte" v={nf(a.stats.tasks)} />
        <KV k="Fahrstrecke" v={`${nf(a.stats.distance / 1000, 2)} km`} />
        <KV k="Auslastung (10 min)" v={pct(a.stats.busyEma)} />
        <KV k="Energie" v={`${nf(a.stats.energyKWh, 2)} kWh`} />
      </Card>
    </div>
  );
}

function StorageDetail({ kind }: { kind: 'raw' | 'finished' }) {
  const inv = useFactory((s) => s.snap.inventory);
  const time = useFactory((s) => s.snap.time);
  const rawQueue = useFactory((s) => s.snap.rawQueue);
  const rules = useFactory((s) => s.snap.rules);
  if (kind === 'raw') {
    return (
      <div className="space-y-3">
        <Header id="RAW" title="Rohlager · Wareneingang" subtitle="Palettenregal · 96 Stellplätze" badge={<Badge color={inv.raw <= inv.reorderPoint ? '#fab219' : '#22c55e'}>{inv.raw <= inv.reorderPoint ? 'Meldebestand' : 'OK'}</Badge>} />
        <Card>
          <div className="mb-1 flex justify-between text-[11px]">
            <span className="text-txt-3">Bestand Rohteile</span>
            <span className="font-mono text-txt-1 tnum">
              {inv.raw} / {inv.rawCapacity}
            </span>
          </div>
          <Meter value={inv.raw / inv.rawCapacity} color={inv.raw <= inv.reorderPoint ? '#fab219' : '#3987e5'} />
          <div className="mt-3 space-y-0.5">
            <KV k="Meldebestand" v={inv.reorderPoint} />
            <KV k="Bestellmenge" v={inv.reorderQty} />
            <KV k="Bereitgestellt (Übergabetisch)" v={rawQueue} />
            <KV k="Lieferung" v={inv.pendingDelivery ? `${inv.pendingDelivery.qty} Stk. in ${duration(inv.pendingDelivery.arrivesAt - time)}` : '–'} />
            <KV k="Automatische Nachbestellung" v={rules.autoReplenish ? 'aktiv' : 'inaktiv'} />
          </div>
          <div className="mt-3 grid grid-cols-2 gap-1.5">
            <Button onClick={() => command((e) => e.orderRawMaterial(20))}>{Icon.plus}20 bestellen</Button>
            <Button variant="primary" onClick={() => command((e) => e.orderRawMaterial(60))}>
              {Icon.plus}60 bestellen
            </Button>
          </div>
        </Card>
      </div>
    );
  }
  const total = PRODUCT_IDS.reduce((a, p) => a + inv.finished[p], 0);
  return (
    <div className="space-y-3">
      <Header id="FG" title="Fertigwarenlager · Versand" subtitle="Palettenregal · 96 Stellplätze" badge={<Badge color="#22c55e">{total} Stk.</Badge>} />
      <Card>
        <SectionTitle>Bestand je Produkt</SectionTitle>
        <div className="space-y-2.5">
          {PRODUCT_IDS.map((p) => (
            <div key={p}>
              <div className="mb-1 flex items-center justify-between text-[11px]">
                <span className="flex items-center gap-1.5 text-txt-2">
                  <span className="h-2 w-2 rounded-sm" style={{ background: PRODUCTS[p].color }} />
                  {PRODUCTS[p].name}
                </span>
                <span className="font-mono text-txt-1 tnum">{inv.finished[p]}</span>
              </div>
              <Meter value={inv.finished[p] / 40} color={PRODUCTS[p].color} height={3} />
            </div>
          ))}
        </div>
        <div className="mt-3 space-y-0.5">
          <KV k="Auslastung" v={`${total} / ${inv.finishedCapacity}`} />
          <KV k="Versendet gesamt" v={nf(inv.shipped)} />
          <KV k="Ausschussbehälter" v={nf(inv.scrap)} />
        </div>
      </Card>
    </div>
  );
}

export function DetailPanel() {
  const selected = useFactory((s) => s.selected);
  const machine = useFactory((s) => (selected?.type === 'machine' ? s.snap.machines.find((m) => m.id === selected.id) : undefined));
  const conveyor = useFactory((s) => (selected?.type === 'conveyor' ? s.snap.conveyors.find((m) => m.id === selected.id) : undefined));
  const agv = useFactory((s) => (selected?.type === 'agv' ? s.snap.agvs.find((m) => m.id === selected.id) : undefined));
  if (!selected)
    return (
      <div className="flex h-48 flex-col items-center justify-center gap-2 text-center text-xs text-txt-3">
        <div className="grid h-9 w-9 place-items-center rounded-full border border-line-2">{Icon.focus}</div>
        Anlage in der 3D-Ansicht oder in der Anlagenliste auswählen.
        <span className="text-[11px]">Doppelklick fokussiert die Kamera.</span>
      </div>
    );
  if (machine) return <MachineDetail m={machine} />;
  if (conveyor) return <ConveyorDetail c={conveyor} />;
  if (agv) return <AgvDetail a={agv} />;
  if (selected.type === 'raw' || selected.type === 'finished') return <StorageDetail kind={selected.type} />;
  return null;
}

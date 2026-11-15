import type { ReactNode } from 'react';
import { send, sceneOf, twin, useFactory } from '../../store/factoryStore';
import { FAULT_TYPES, PRODUCTS } from '../../twin/config';
import type { SceneAgv, SceneConveyor, SceneMachine } from '../../twin/types';
import { AGV_COLOR, AGV_LABEL, clock, nf, pct, STATE_HEX, STATUS_COLOR, STATUS_LABEL } from '../format';
import { Button, Card, Icon, KV, Meter, SectionTitle, Slider, StatusDot, Toggle } from '../primitives';

function Header({ title, subtitle, color, state, onClose }: { title: string; subtitle: string; color: string; state: string; onClose?: () => void }) {
  const select = useFactory((s) => s.select);
  return (
    <div className="mb-3 flex items-start justify-between gap-2">
      <div className="min-w-0">
        <h2 className="truncate text-[15px] font-semibold text-txt-1">{title}</h2>
        <p className="truncate text-[11px] text-txt-3">{subtitle}</p>
        <p className="mt-1.5 flex items-center gap-1.5 text-[12px] text-txt-1">
          <StatusDot color={color} /> {state}
        </p>
      </div>
      <Button variant="ghost" size="sm" onClick={() => (onClose ? onClose() : select(null))} aria-label="Auswahl aufheben">
        {Icon.close}
      </Button>
    </div>
  );
}

function Commands({ children }: { children: ReactNode }) {
  return <div className="mt-3 flex flex-wrap gap-1.5">{children}</div>;
}

export function MachineDetails({ m, onClose }: { m: SceneMachine; onClose?: () => void }) {
  const product = m.job ? sceneOf(useFactory.getState()).workpieces.get(m.job.wpId)?.product : undefined;
  const faults = FAULT_TYPES.filter((f) => f.kinds.includes(m.kind));
  const cmd = (command: string, extra: Record<string, unknown> = {}) => void send({ command, machineId: m.id, ...extra });
  return (
    <>
      <Header title={`${m.id} · ${m.name}`} subtitle={m.model} color={STATUS_COLOR[m.status]} state={STATUS_LABEL[m.status]} onClose={onClose} />
      <Card>
        <SectionTitle>Auftrag</SectionTitle>
        {m.job ? (
          <>
            <KV k="Los" v={m.job.wpId} />
            <KV k="Produkt" v={product ? PRODUCTS[product].name : '–'} mono={false} />
            <div className="mt-2 flex items-center gap-2">
              <Meter value={m.job.progress / m.job.duration} />
              <span className="font-mono text-[11px] text-txt-2 tnum">{pct(m.job.progress / m.job.duration)}</span>
            </div>
          </>
        ) : (
          <p className="text-[12px] text-txt-3">Kein Los an der Maschine.</p>
        )}
      </Card>
      <Card className="mt-2">
        <SectionTitle>Zustand</SectionTitle>
        <KV k="Temperatur" v={`${nf(m.sensors.temperature, 1)} °C`} />
        <KV k="Vibration" v={`${nf(m.sensors.vibration, 2)} mm/s`} />
        <KV k="Leistung" v={`${nf(m.sensors.power, 1)} kW`} />
        <KV k="Drehzahl" v={nf(m.sensors.spindle)} />
        <KV k="Verschleiss" v={pct(m.wear)} />
        {m.fault && <KV k="Störung" v={m.fault.label} mono={false} />}
      </Card>
      <Card className="mt-2">
        <SectionTitle>Einstellungen</SectionTitle>
        <Slider
          label="Override"
          value={m.config.override}
          min={50}
          max={130}
          step={5}
          unit="%"
          onChange={(v) => cmd('machineConfigure', { override: v, coolant: m.config.coolant })}
        />
        <Toggle
          checked={m.config.coolant}
          onChange={(v) => cmd('machineConfigure', { override: m.config.override, coolant: v })}
          label="Kühlung"
        />
      </Card>
      <Commands>
        <Button size="sm" onClick={() => cmd('machineStart')}>
          {Icon.play} Start
        </Button>
        <Button size="sm" onClick={() => cmd('machineStop')}>
          {Icon.stop} Stopp
        </Button>
        <Button size="sm" onClick={() => cmd('machineMaintain')}>
          {Icon.wrench} Wartung
        </Button>
        <Button size="sm" onClick={() => cmd('machineRepair')}>
          {Icon.check} Reparatur
        </Button>
      </Commands>
      <Commands>
        {faults.map((f) => (
          <Button key={f.id} size="sm" variant="danger" onClick={() => cmd('machineFault', { faultTypeId: f.id })}>
            {f.label}
          </Button>
        ))}
      </Commands>
    </>
  );
}

export function AgvDetails({ a, onClose }: { a: SceneAgv; onClose?: () => void }) {
  const transport = twin.latest?.transports.find((t) => t.id === a.transportId);
  const cmd = (command: string) => void send({ command, agvId: a.id });
  return (
    <>
      <Header title={a.name} subtitle={a.id} color={AGV_COLOR[a.state]} state={AGV_LABEL[a.state]} onClose={onClose} />
      <Card>
        <SectionTitle>Fahrzeug</SectionTitle>
        <div className="mb-1 flex items-center gap-2">
          <Meter value={a.battery / 100} color={a.battery < 25 ? STATE_HEX.fault : undefined} />
          <span className="whitespace-nowrap font-mono text-[11px] text-txt-2 tnum">{nf(a.battery)} %</span>
        </div>
        <KV k="Geschwindigkeit" v={`${nf(a.speed, 2)} m/s`} />
        <KV k="Wegpunkte offen" v={a.route.length} />
      </Card>
      <Card className="mt-2">
        <SectionTitle>Transport</SectionTitle>
        {transport ? (
          <>
            <KV k="Auftrag" v={transport.id} />
            <KV k="Ladung" v={transport.orderId ?? transport.materialId ?? '–'} />
            <KV k="Von" v={transport.from} />
            <KV k="Nach" v={transport.to} />
          </>
        ) : (
          <p className="text-[12px] text-txt-3">Kein Transport.</p>
        )}
      </Card>
      <Commands>
        <Button size="sm" onClick={() => cmd('agvEnable')}>
          Aktivieren
        </Button>
        <Button size="sm" onClick={() => cmd('agvDisable')}>
          Deaktivieren
        </Button>
        <Button size="sm" onClick={() => cmd('agvCharge')}>
          {Icon.bolt} Laden
        </Button>
        <Button size="sm" variant="danger" onClick={() => cmd('agvFault')}>
          Störung
        </Button>
      </Commands>
    </>
  );
}

function ConveyorDetails({ c }: { c: SceneConveyor }) {
  return (
    <>
      <Header title={`${c.id} · ${c.name}`} subtitle={`${nf(c.length, 1)} m · ${nf(c.speed, 1)} m/s`} color="#22c55e" state="Läuft" />
      <Card>
        <SectionTitle>Lose auf dem Band</SectionTitle>
        {c.items.length === 0 ? (
          <p className="text-[12px] text-txt-3">Das Band ist leer.</p>
        ) : (
          c.items.map((i) => <KV key={i.wpId} k={i.wpId} v={`${nf(i.s, 1)} m`} />)
        )}
      </Card>
    </>
  );
}

function StockDetails({ kind }: { kind: 'raw' | 'finished' }) {
  useFactory((s) => s.scene); // re-render with every frame
  const prefix = kind === 'raw' ? ['RM-', 'KIT-'] : ['FG-'];
  const lines = (twin.latest?.stock ?? []).filter((l) => prefix.some((p) => l.materialId.startsWith(p)));
  return (
    <>
      <Header
        title={kind === 'raw' ? 'Rohmaterial-Lager' : 'Fertigwaren-Lager'}
        subtitle={`Stand ${clock(sceneOf(useFactory.getState()).time)}`}
        color="#64748b"
        state={`${lines.reduce((n, l) => n + l.onHand, 0)} Stück`}
      />
      <Card>
        <SectionTitle>Bestände</SectionTitle>
        {lines.map((l) => (
          <KV key={`${l.materialId}@${l.locationId}`} k={`${l.materialId} @ ${l.locationId}`} v={`${l.onHand - l.reserved} von ${l.onHand}`} />
        ))}
      </Card>
    </>
  );
}

/** Details of the selected object, with the commands the plant accepts for it. */
export function DetailPanel() {
  const selected = useFactory((s) => s.selected);
  const scene = useFactory(sceneOf);
  if (!selected) {
    return <p className="py-6 text-center text-[12px] text-txt-3">Klicke in der Halle auf eine Maschine, ein AGV, ein Band oder ein Lager.</p>;
  }
  if (selected.type === 'machine') {
    const m = scene.machines.find((x) => x.id === selected.id);
    return m ? <MachineDetails m={m} /> : null;
  }
  if (selected.type === 'agv') {
    const a = scene.agvs.find((x) => x.id === selected.id);
    return a ? <AgvDetails a={a} /> : null;
  }
  if (selected.type === 'conveyor') {
    const c = scene.conveyors.find((x) => x.id === selected.id);
    return c ? <ConveyorDetails c={c} /> : null;
  }
  return <StockDetails kind={selected.type} />;
}

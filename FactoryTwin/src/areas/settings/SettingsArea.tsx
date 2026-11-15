import { useEffect, useState, type ReactNode } from 'react';
import type { Rules } from '../../nexus/types';
import { control as sim, query, send, useFactory } from '../../store/factoryStore';
import { nf } from '../../ui/format';
import { control, Field, Section } from '../../ui/kit';
import { Button, Segmented, Slider, Toggle } from '../../ui/primitives';
import { applyRules, rulesPatch } from './rulesForm';

const SPEEDS = [0.25, 0.5, 1, 2, 5, 10, 25];

const RULES: { value: Rules['dispatchRule']; label: string; title: string }[] = [
  { value: 'PRIORITY', label: 'Priorität', title: 'Eilige und hohe Aufträge zuerst' },
  { value: 'EDD', label: 'Frühester Termin', title: 'Earliest Due Date: der früheste Liefertermin zuerst' },
  { value: 'FIFO', label: 'Reihenfolge', title: 'First In, First Out: in der Reihenfolge des Eingangs' },
  { value: 'SPT', label: 'Kürzeste Bearbeitung', title: 'Shortest Processing Time: der kürzeste Arbeitsgang zuerst' },
];

async function loadRules(): Promise<Rules | null> {
  try {
    const a = await query<Rules>({ query: 'rules' });
    return a.ok ? a.result : null;
  } catch {
    return null; // not connected yet
  }
}

/** A whole number field that never holds a value outside [min, max]; an empty field keeps the last value. */
function WholeField({ label, value, min, max, unit, onChange }: { label: string; value: number; min: number; max: number; unit: string; onChange: (v: number) => void }) {
  return (
    <Field label={`${label} (${unit})`}>
      <input
        type="number"
        inputMode="numeric"
        className={`${control} font-mono tnum`}
        min={min}
        max={max}
        step={1}
        value={value}
        onChange={(e) => {
          const n = Number(e.target.value);
          if (e.target.value !== '' && Number.isFinite(n)) onChange(Math.min(max, Math.max(min, Math.round(n))));
        }}
      />
    </Field>
  );
}

/** Form on the left, what it does on the right; one column below 1024 px. */
function Part({ title, aside, children }: { title: string; aside: ReactNode; children: ReactNode }) {
  return (
    <Section title={title}>
      <div className="grid grid-cols-12 gap-x-8 gap-y-4">
        <div className="col-span-12 lg:col-span-8">{children}</div>
        <p className="col-span-12 text-[13px] leading-relaxed text-txt-3 lg:col-span-4">{aside}</p>
      </div>
    </Section>
  );
}

function RulesPart() {
  const [current, setCurrent] = useState<Rules | null>(null);
  const [edited, setEdited] = useState<Rules | null>(null);
  const [busy, setBusy] = useState(false);
  const [failed, setFailed] = useState(false);
  const load = () => {
    setFailed(false);
    void loadRules().then((r) => {
      setCurrent(r);
      setEdited(r);
      setFailed(r === null);
    });
  };
  useEffect(load, []);

  const aside =
    'Regeln gelten nach „Übernehmen“ für den laufenden Betrieb. Lehnt die Anlage einen Wert ab, steht der Grund oben in der Meldungszeile, und das Formular zeigt wieder die gültigen Werte.';
  if (!current || !edited)
    return (
      <Part title="Regeln der Simulation" aside={aside}>
        {failed ? (
          <p className="flex items-center gap-3 text-[13px] text-txt-2">
            Die Regeln liessen sich nicht laden.
            <Button size="sm" onClick={load}>
              Erneut versuchen
            </Button>
          </p>
        ) : (
          <p className="text-[13px] text-txt-3">Regeln werden geladen …</p>
        )}
      </Part>
    );

  const set = <K extends keyof Rules>(k: K, v: Rules[K]) => setEdited({ ...edited, [k]: v });
  const changed = rulesPatch(current, edited) !== null;
  const apply = async () => {
    setBusy(true);
    const r = await applyRules(current, edited, send, loadRules);
    setCurrent(r);
    setEdited(r);
    setBusy(false);
  };

  return (
    <Part title="Regeln der Simulation" aside={aside}>
      <div className="grid grid-cols-1 gap-x-8 sm:grid-cols-2">
        <div>
          <Toggle label="Zufallsstörungen" hint="Maschinen fallen gelegentlich aus" checked={edited.randomFaults} onChange={(v) => set('randomFaults', v)} />
          <Toggle label="Automatische Reparatur" hint="Störungen werden ohne Eingriff behoben" checked={edited.autoRepair} onChange={(v) => set('autoRepair', v)} />
          <Toggle label="Automatische Wartung" hint="ab der Wartungsschwelle" checked={edited.autoMaintenance} onChange={(v) => set('autoMaintenance', v)} />
          <Slider
            label="Wartungsschwelle"
            value={Math.round(edited.maintenanceThreshold * 100)}
            min={50}
            max={100}
            step={5}
            unit="%"
            onChange={(v) => set('maintenanceThreshold', v / 100)}
          />
        </div>
        <div>
          <Toggle label="Automatische Aufträge" hint="neue Aufträge in festem Abstand" checked={edited.autoOrders} onChange={(v) => set('autoOrders', v)} />
          <Toggle label="Versand" hint="Fertigware verlässt das Lager" checked={edited.shipping} onChange={(v) => set('shipping', v)} />
          <div className="mt-2 grid grid-cols-2 gap-4">
            <WholeField label="Abstand" unit="min" value={edited.autoOrderIntervalMinutes} min={1} max={120} onChange={(v) => set('autoOrderIntervalMinutes', v)} />
            <WholeField label="Offen höchstens" unit="Aufträge" value={edited.maxOpenOrders} min={1} max={30} onChange={(v) => set('maxOpenOrders', v)} />
          </div>
        </div>
      </div>
      <div className="mt-5">
        <div className="mb-1.5 text-[11px] uppercase tracking-[0.08em] text-txt-3">Dispositionsregel</div>
        <Segmented ariaLabel="Dispositionsregel" value={edited.dispatchRule} onChange={(v) => set('dispatchRule', v)} options={RULES} />
      </div>
      <div className="mt-6 flex gap-2">
        <Button variant="primary" onClick={() => void apply()} disabled={!changed || busy}>
          {busy ? 'Wird übernommen …' : 'Übernehmen'}
        </Button>
        <Button variant="ghost" onClick={() => setEdited(current)} disabled={!changed || busy}>
          Verwerfen
        </Button>
      </div>
    </Part>
  );
}

function SimulationPart() {
  const speed = useFactory((s) => s.sim.speed);
  const seed = useFactory((s) => s.sim.seed);
  const mode = useFactory((s) => s.mode);
  const [nextSeed, setNextSeed] = useState(seed);
  const [asking, setAsking] = useState(false);
  useEffect(() => setNextSeed(seed), [seed]);
  return (
    <Part
      title="Simulation"
      aside={
        mode === 'browser'
          ? 'Betriebsart Browser: der Java-Kern läuft im Web Worker dieses Tabs. Gleicher Seed, gleicher Ablauf.'
          : 'Betriebsart Server: der Kern läuft auf dem Server mit PostgreSQL und OPC UA. Alle offenen Fenster sehen denselben Lauf.'
      }
    >
      <div className="mb-1.5 text-[11px] uppercase tracking-[0.08em] text-txt-3">Tempo</div>
      <Segmented
        ariaLabel="Simulationsgeschwindigkeit"
        value={speed}
        onChange={(v) => void sim.setSpeed(v)}
        options={SPEEDS.map((s) => ({ value: s, label: `${nf(s, s < 1 ? 2 : 0)}×` }))}
      />
      <div className="mt-5 flex flex-wrap items-end gap-3">
        <Field label="Seed" className="w-36">
          <input
            type="number"
            inputMode="numeric"
            className={`${control} font-mono tnum`}
            min={1}
            step={1}
            value={nextSeed}
            onChange={(e) => {
              const n = Math.round(Number(e.target.value));
              if (Number.isFinite(n) && n >= 1) setNextSeed(n);
            }}
          />
        </Field>
        {asking ? (
          <span className="flex items-center gap-2" role="group" aria-label="Neustart bestätigen">
            <span className="text-[13px] text-txt-2">Lauf verwerfen und mit Seed {nextSeed} neu starten?</span>
            <Button
              variant="danger"
              size="sm"
              autoFocus
              onClick={() => {
                setAsking(false);
                void sim.reset(nextSeed);
              }}
            >
              Ja
            </Button>
            <Button variant="ghost" size="sm" onClick={() => setAsking(false)}>
              Nein
            </Button>
          </span>
        ) : (
          <Button onClick={() => setAsking(true)}>Neu starten mit diesem Seed</Button>
        )}
      </div>
    </Part>
  );
}

function DisplayPart() {
  const quality = useFactory((s) => s.quality);
  const setQuality = useFactory((s) => s.setQuality);
  const showLabels = useFactory((s) => s.showLabels);
  const showPaths = useFactory((s) => s.showPaths);
  const toggle = useFactory((s) => s.toggle);
  return (
    <Part title="Darstellung" aside="Gilt für FactoryTwin in diesem Browser. Auf schwacher Grafik hilft „Schnell“.">
      <div className="mb-1.5 text-[11px] uppercase tracking-[0.08em] text-txt-3">Grafikqualität</div>
      <Segmented
        ariaLabel="Grafikqualität"
        value={quality}
        onChange={setQuality}
        options={[
          { value: 'high', label: 'Hoch', title: 'Schatten, Bloom, volle Auflösung' },
          { value: 'balanced', label: 'Mittel', title: 'Schatten ohne Post-Processing' },
          { value: 'performance', label: 'Schnell', title: 'Keine Schatten, reduzierte Auflösung' },
        ]}
      />
      <div className="mt-3 max-w-sm">
        <Toggle label="Beschriftungen" hint="Taste L in FactoryTwin" checked={showLabels} onChange={() => toggle('showLabels')} />
        <Toggle label="AGV-Fahrwege" hint="Taste P in FactoryTwin" checked={showPaths} onChange={() => toggle('showPaths')} />
      </div>
    </Part>
  );
}

/** Rules of the plant, the simulation's pace and seed, and how FactoryTwin draws. */
export function SettingsArea() {
  return (
    <div className="flex flex-col gap-10 px-4 py-6 lg:px-6 lg:py-8">
      <RulesPart />
      <SimulationPart />
      <DisplayPart />
    </div>
  );
}

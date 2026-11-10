import type { DispatchRule, Rules } from '../../sim/types';
import { command, useFactory } from '../../store/factoryStore';
import { Card, SectionTitle, Segmented, Slider, Toggle } from '../primitives';

const DISPATCH: { value: DispatchRule; label: string; hint: string }[] = [
  { value: 'PRIORITY', label: 'Priorität', hint: 'Eilaufträge zuerst, danach Eingangsreihenfolge' },
  { value: 'EDD', label: 'EDD', hint: 'Frühester Liefertermin zuerst (Earliest Due Date)' },
  { value: 'SPT', label: 'SPT', hint: 'Kürzeste Bearbeitungszeit zuerst (Shortest Processing Time)' },
  { value: 'FIFO', label: 'FIFO', hint: 'Strikt nach Freigabezeitpunkt' },
];

export function RulesPanel() {
  const rules = useFactory((s) => s.snap.rules);
  const set = (patch: Partial<Rules>) => command((e) => e.setRules(patch));
  const d = DISPATCH.find((x) => x.value === rules.dispatchRule)!;
  return (
    <div className="space-y-3">
      <Card>
        <SectionTitle>Umplanung bei Ausfällen</SectionTitle>
        <Toggle
          checked={rules.dynamicRouting}
          onChange={(v) => set({ dynamicRouting: v })}
          label="Dynamische Umplanung"
          hint={
            rules.dynamicRouting
              ? 'Teile gehen an andere Maschinen mit derselben Fähigkeit. Wartende Teile an gestörten Maschinen und beladene AGVs werden umgeleitet.'
              : 'Jeder Schritt läuft auf der im Arbeitsplan vorgesehenen Maschine. Fällt sie aus, warten die Teile und der Auftrag gilt als blockiert.'
          }
        />
        <div className="mt-2 rounded-md border border-line bg-ink-1 p-2 text-[10px] leading-relaxed text-txt-3">
          Alternativen: Fräsen → CNC-01 · CNC-02 · CNC-04 &nbsp;|&nbsp; Drehen → CNC-03 · CNC-04 &nbsp;|&nbsp; Montage → ROB-01 · ROB-02 &nbsp;|&nbsp; Prüfen → nur QC-01 (Engpass ohne
          Ausweichmöglichkeit)
        </div>
      </Card>

      <Card>
        <SectionTitle>Reihenfolgeplanung</SectionTitle>
        <Segmented value={rules.dispatchRule} onChange={(v) => set({ dispatchRule: v })} options={DISPATCH.map((x) => ({ value: x.value, label: x.label, title: x.hint }))} />
        <p className="mt-2 text-[11px] text-txt-3">{d.hint}. Die Regel gilt für Freigabe, Transporte und Maschinenpuffer.</p>
        <Slider label="WIP-Limit (CONWIP)" value={rules.wipLimit} min={4} max={40} unit="Teile" onChange={(v) => set({ wipLimit: v })} />
      </Card>

      <Card>
        <SectionTitle>Instandhaltung</SectionTitle>
        <Toggle checked={rules.autoRepair} onChange={(v) => set({ autoRepair: v })} label="Automatische Instandsetzung" hint="Das Instandhaltungsteam ist nach 15–35 s vor Ort" />
        <Toggle
          checked={rules.autoMaintenance}
          onChange={(v) => set({ autoMaintenance: v })}
          label="Vorbeugende Wartung"
          hint="Erreicht der Verschleiß die Schwelle, geht die Maschine nach dem aktuellen Teil in Wartung"
        />
        <Slider
          label="Wartungsschwelle"
          value={Math.round(rules.maintenanceThreshold * 100)}
          min={50}
          max={98}
          unit="%"
          onChange={(v) => set({ maintenanceThreshold: v / 100 })}
        />
        <Toggle
          checked={rules.randomFaults}
          onChange={(v) => set({ randomFaults: v })}
          label="Zufallsstörungen"
          hint="Die Ausfallrate hängt von MTBF, Verschleiß, Temperatur und Override ab"
        />
      </Card>

      <Card>
        <SectionTitle>Auftrags- & Materialfluss</SectionTitle>
        <Toggle checked={rules.autoOrders} onChange={(v) => set({ autoOrders: v })} label="Automatischer Auftragseingang" hint="Lokaler ERP-Generator, im Schnitt ein Auftrag alle 5 min" />
        <Toggle checked={rules.autoReplenish} onChange={(v) => set({ autoReplenish: v })} label="Automatische Nachbestellung" hint="Bestellt Rohmaterial, sobald der Meldebestand erreicht ist" />
      </Card>
    </div>
  );
}

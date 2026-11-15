import { useKpis } from '../../nexus/useKpis';
import { hrefOf } from '../../shell/route';
import { twin, useFactory } from '../../store/factoryStore';
import { clock, SEVERITY_COLOR, STATE_HEX } from '../../ui/format';
import { Section, Stat, StateTag } from '../../ui/kit';
import { eventText } from '../../ui/panels/eventText';
import { overviewFigures } from './figures';

function Count({ label, value, tone }: { label: string; value: number; tone?: string }) {
  return (
    <div className="flex items-baseline justify-between gap-3 border-b border-line py-2 last:border-b-0">
      <span className="text-[13px] text-txt-2">{label}</span>
      <span className="font-mono text-[20px] tnum" style={{ color: tone }}>
        {value}
      </span>
    </div>
  );
}

/** The whole plant at a glance: key figures, machine states, open work and the latest faults. */
export function OverviewArea() {
  useFactory((s) => s.scene); // re-render with every frame; the figures read the twin's latest frame
  const events = useFactory((s) => s.events);
  const kpis = useKpis(60);
  const f = overviewFigures(twin.latest, kpis, events);

  return (
    <div className="grid grid-cols-12 gap-x-8 gap-y-10 px-4 py-6 lg:px-6 lg:py-8">
      <div className="col-span-12 grid grid-cols-2 gap-x-8 gap-y-8 lg:grid-cols-4">
        <Stat label="Durchsatz" value={f.throughput} unit="Aufträge/h" hint="letzte 60 min" />
        <Stat label="OEE" value={f.oee} hint="Mittel aller Maschinen" />
        <Stat label="Termintreue" value={f.onTime} hint="fertige Aufträge, letzte 60 min" />
        <Stat label="Durchlaufzeit" value={f.leadTime} hint="Mittel" />
      </div>

      <Section title="Maschinen" className="col-span-12 lg:col-span-8">
        {f.machines.length === 0 ? (
          <p className="text-[13px] text-txt-3">Noch keine Daten der Anlage.</p>
        ) : (
          <ul className="grid grid-cols-1 sm:grid-cols-2">
            {f.machines.map((m) => (
              <li key={m.id} className="border-b border-line">
                <a
                  href={hrefOf('machines', m.id)}
                  className="flex items-center justify-between gap-3 py-2 pr-4 hover:bg-white/[0.03]"
                  title={`${m.id} in Machines zeigen`}
                >
                  <span className="font-mono text-[13px] text-txt-1">{m.id}</span>
                  <StateTag kind="machine" status={m.status} />
                </a>
              </li>
            ))}
          </ul>
        )}
        {f.bottleneck && (
          <p className="mt-3 text-[13px] text-txt-2">
            Engpass: <span className="font-mono text-txt-1">{f.bottleneck}</span>
          </p>
        )}
      </Section>

      <Section title="Aufträge und Transporte" className="col-span-12 lg:col-span-4">
        <Count label="Offene Aufträge" value={f.openOrders} />
        <Count label="Davon verspätet" value={f.lateOrders} tone={f.lateOrders > 0 ? STATE_HEX.wait : undefined} />
        <Count label="Laufende Transporte" value={f.activeTransports} />
      </Section>

      <Section
        title="Meldungen"
        className="col-span-12"
        action={
          <a href={hrefOf('events')} className="text-[12px] text-txt-2 hover:text-txt-1">
            Alle Ereignisse
          </a>
        }
      >
        {f.alerts.length === 0 ? (
          <p className="text-[13px] text-txt-3">Keine Störungen oder Warnungen.</p>
        ) : (
          <ul>
            {f.alerts.map((e) => (
              <li key={e.eventId} className="flex items-baseline gap-3 border-b border-line py-2 text-[13px] last:border-b-0">
                <span className="font-mono text-[12px] text-txt-3 tnum">{clock(e.simTimeMs / 1000)}</span>
                <span className="h-1.5 w-1.5 shrink-0 translate-y-[-1px] rounded-full" style={{ background: SEVERITY_COLOR[e.severity] }} aria-hidden="true" />
                <span className="sr-only">{e.severity === 'ERROR' ? 'Fehler' : 'Warnung'}:</span>
                <span className="min-w-0 text-txt-1">{eventText(e)}</span>
              </li>
            ))}
          </ul>
        )}
      </Section>
    </div>
  );
}

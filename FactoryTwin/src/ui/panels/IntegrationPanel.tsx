import { useState } from 'react';
import { API_VERSION } from '../../integration/api';
import { useFactory } from '../../store/factoryStore';
import { Button, Card, cx, SectionTitle } from '../primitives';

const SNIPPET = `// Browser-Konsole oder eigener Adapter
const ft = window.FactoryTwin;
ft.subscribe('machine.fault', e => console.log(e));
ft.commands.createOrder({ product: 'GH200', quantity: 5, priority: 'eilig' });
ft.getTelemetry().kpi;`;

export function IntegrationPanel() {
  const connectors = useFactory((s) => s.connectors);
  const toggle = useFactory((s) => s.toggleConnector);
  const planner = useFactory((s) => s.snap.plannerLabel);
  const [copied, setCopied] = useState(false);
  return (
    <div className="space-y-3">
      {connectors.map((c) => (
        <Card key={c.id}>
          <div className="flex items-start justify-between gap-2">
            <div className="min-w-0">
              <div className="flex items-center gap-2">
                <span
                  className={cx(
                    'h-2 w-2 rounded-full',
                    c.status === 'connected' ? 'bg-emerald-400' : c.status === 'connecting' ? 'animate-pulse bg-amber-400' : 'bg-slate-600',
                  )}
                />
                <span className="text-sm font-semibold text-txt-1">{c.label}</span>
                <span className="text-[10px] text-txt-3">{c.status === 'connected' ? 'verbunden' : c.status === 'connecting' ? 'verbindet …' : 'getrennt'}</span>
              </div>
              <div className="mt-1 text-[11px] text-txt-2">{c.description}</div>
            </div>
            <Button size="sm" variant={c.status === 'connected' ? 'default' : 'primary'} disabled={c.status === 'connecting'} onClick={() => void toggle(c.id)}>
              {c.status === 'connected' ? 'Trennen' : 'Verbinden'}
            </Button>
          </div>
          <div className="mt-2 rounded-md border border-line bg-ink-1 px-2 py-1.5 text-[10px] leading-relaxed text-txt-3">{c.detail}</div>
        </Card>
      ))}

      <Card>
        <SectionTitle>Schnittstellen-Vertrag · v{API_VERSION}</SectionTitle>
        <ul className="space-y-1.5 text-[11px] text-txt-2">
          <li>
            <span className="font-mono text-teal-200">FactoryCommands</span>: alle Bedienbefehle wie Start/Stopp, Störungen, Konfiguration, Aufträge und Regeln
          </li>
          <li>
            <span className="font-mono text-teal-200">DomainEvent</span>: typisierte Ereignisse über einen Event-Bus, z. B. Statuswechsel, Transporte, Ausschuss
          </li>
          <li>
            <span className="font-mono text-teal-200">TelemetryFrame</span>: Sensordaten und Kennzahlen im festen Takt für MES oder Historian
          </li>
          <li>
            <span className="font-mono text-teal-200">RoutePlanner</span>: austauschbarer AGV-Routenplaner, aktiv ist {planner}
          </li>
          <li>
            <span className="font-mono text-teal-200">FactoryConnector</span>: connect/disconnect für externe Adapter
          </li>
        </ul>
        <pre tabIndex={0} aria-label="Code-Beispiel für die Schnittstelle" className="scroll-thin mt-3 overflow-x-auto rounded-md border border-line bg-ink-0 p-2 font-mono text-[10px] leading-relaxed text-txt-2">{SNIPPET}</pre>
        <div className="mt-2 flex justify-end">
          <Button
            size="sm"
            variant="ghost"
            onClick={() => {
              void navigator.clipboard?.writeText(SNIPPET).then(() => {
                setCopied(true);
                setTimeout(() => setCopied(false), 1500);
              });
            }}
          >
            {copied ? 'Kopiert ✓' : 'Snippet kopieren'}
          </Button>
        </div>
      </Card>
    </div>
  );
}

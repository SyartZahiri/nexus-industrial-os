import { Component, lazy, Suspense, type ReactNode } from 'react';
import { labelPortal } from '../scene/labelPortal';
import { useFactory, type CameraPreset, type RightTab } from '../store/factoryStore';
import { STATUS_COLOR, STATUS_SHORT } from '../ui/format';
import { DetailPanel } from '../ui/panels/DetailPanel';
import { EventLog } from '../ui/panels/EventLog';
import { cx, Icon, Segmented } from '../ui/primitives';
import { hrefOf } from '../shell/route';

const FactoryScene = lazy(() => import('../scene/FactoryScene').then((m) => ({ default: m.FactoryScene })));

class SceneBoundary extends Component<{ children: ReactNode }, { error: string | null }> {
  state = { error: null as string | null };
  static getDerivedStateFromError(e: Error) {
    return { error: e.message };
  }
  render() {
    if (this.state.error)
      return (
        <div className="absolute inset-0 grid place-items-center p-6 text-center">
          <div className="max-w-sm text-[13px] text-txt-2">
            <div className="mb-2 font-semibold text-txt-1">3D-Ansicht nicht verfügbar</div>
            WebGL konnte nicht initialisiert werden ({this.state.error}). Simulation, Bereiche und Steuerung laufen weiter.
          </div>
        </div>
      );
    return this.props.children;
  }
}

export const CAMS: { id: CameraPreset; label: string; key: string }[] = [
  { id: 'overview', label: 'Übersicht', key: 'Q' },
  { id: 'machining', label: 'Zerspanung', key: 'W' },
  { id: 'assembly', label: 'Montage', key: 'E' },
  { id: 'logistics', label: 'Logistik', key: 'R' },
  { id: 'top', label: 'Draufsicht', key: 'T' },
];

const toolbar = 'pointer-events-auto flex flex-wrap gap-1 rounded-md border border-line bg-ink-1 p-1';
const toolButton = (on: boolean) =>
  cx('rounded px-2 py-1 text-[11px] font-medium transition-colors', on ? 'bg-ink-4 text-txt-1' : 'text-txt-3 hover:text-txt-1');

function ViewportOverlay() {
  const setCamera = useFactory((s) => s.setCamera);
  const camera = useFactory((s) => s.camera);
  const showLabels = useFactory((s) => s.showLabels);
  const showPaths = useFactory((s) => s.showPaths);
  const toggle = useFactory((s) => s.toggle);
  const running = useFactory((s) => s.sim.running);
  const quality = useFactory((s) => s.quality);
  const setQuality = useFactory((s) => s.setQuality);
  const rightOpen = useFactory((s) => s.rightOpen);
  return (
    <>
      <div className="pointer-events-none absolute inset-x-0 top-0 flex flex-wrap items-start justify-between gap-2 p-2.5">
        <div className={toolbar} role="group" aria-label="Kameraperspektive">
          {CAMS.map((c) => (
            <button
              key={c.id}
              aria-pressed={camera.preset === c.id}
              onClick={() => setCamera(c.id)}
              title={`${c.label} (${c.key})`}
              className={toolButton(camera.preset === c.id)}
            >
              {c.label}
            </button>
          ))}
        </div>
        <div className="pointer-events-auto flex flex-wrap items-center gap-1.5">
          <div className={toolbar}>
            <button onClick={() => toggle('showLabels')} aria-pressed={showLabels} className={toolButton(showLabels)} title="Beschriftungen (L)">
              Labels
            </button>
            <button onClick={() => toggle('showPaths')} aria-pressed={showPaths} className={toolButton(showPaths)} title="AGV-Fahrwege (P)">
              Fahrwege
            </button>
          </div>
          <div className="hidden lg:block">
            <Segmented
              size="sm"
              ariaLabel="Grafikqualität"
              value={quality}
              onChange={setQuality}
              options={[
                { value: 'high', label: 'Hoch', title: 'Schatten, Bloom, volle Auflösung' },
                { value: 'balanced', label: 'Mittel', title: 'Schatten ohne Post-Processing' },
                { value: 'performance', label: 'Schnell', title: 'Keine Schatten, reduzierte Auflösung' },
              ]}
            />
          </div>
          <button
            onClick={() => toggle('rightOpen')}
            className={cx(toolbar, 'px-2 py-1.5 text-txt-3 hover:text-txt-1', rightOpen && 'text-txt-1')}
            title="Details ein/aus"
            aria-label="Detailspalte umschalten"
            aria-pressed={rightOpen}
          >
            {Icon.panelRight}
          </button>
        </div>
      </div>

      {!running && (
        <div className="pointer-events-none absolute left-1/2 top-14 hidden -translate-x-1/2 rounded-md @3xl:block border border-line bg-ink-1 px-3 py-1 text-[12px] text-txt-2">
          Simulation pausiert · Leertaste zum Fortsetzen
        </div>
      )}

      <div className="pointer-events-none absolute bottom-2.5 left-2.5 hidden rounded-md border border-line bg-ink-1 px-2.5 py-2 @md:block">
        <div className="grid grid-cols-3 gap-x-3 gap-y-1">
          {(['RUNNING', 'IDLE', 'SETUP', 'BLOCKED', 'STOPPED', 'FAULT', 'REPAIR', 'MAINTENANCE', 'UNKNOWN'] as const).map((s) => (
            <span key={s} className="flex items-center gap-1.5 text-[11px] text-txt-2">
              <span className="h-1.5 w-1.5 rounded-full" style={{ background: STATUS_COLOR[s] }} aria-hidden="true" />
              {STATUS_SHORT[s]}
            </span>
          ))}
        </div>
      </div>
      <div className="pointer-events-none absolute bottom-2.5 right-2.5 hidden text-right text-[11px] leading-relaxed text-txt-3 @4xl:block">
        <div>Linke Maustaste: drehen · Rechte: verschieben · Mausrad: zoomen</div>
        <div>Klick: auswählen · Doppelklick: fokussieren</div>
      </div>
    </>
  );
}

const TABS: { id: RightTab; label: string }[] = [
  { id: 'details', label: 'Anlage' },
  { id: 'events', label: 'Ereignisse' },
];

function RightPanel() {
  const tab = useFactory((s) => s.rightTab);
  const setTab = useFactory((s) => s.setRightTab);
  return (
    <div className="flex h-full flex-col">
      <nav className="flex shrink-0 items-end gap-0.5 border-b border-line px-2 pt-2" role="tablist" aria-label="Detailbereiche">
        {TABS.map((t) => (
          <button
            key={t.id}
            role="tab"
            id={`tab-${t.id}`}
            aria-selected={tab === t.id}
            aria-controls="right-tabpanel"
            onClick={() => setTab(t.id)}
            className={cx(
              '-mb-px border-b-2 px-2.5 pb-2 pt-1 text-[12px] font-medium transition-colors',
              tab === t.id ? 'border-accent text-txt-1' : 'border-transparent text-txt-3 hover:text-txt-1',
            )}
          >
            {t.label}
          </button>
        ))}
        {tab === 'events' && (
          <a href={hrefOf('events')} className="mb-2 ml-auto pr-1 text-[11px] text-txt-3 hover:text-txt-1">
            Im Event Monitor öffnen
          </a>
        )}
      </nav>
      <div id="right-tabpanel" role="tabpanel" aria-labelledby={`tab-${tab}`} className="scroll-thin safe-bottom min-h-0 flex-1 overflow-y-auto p-3">
        {tab === 'details' && <DetailPanel />}
        {tab === 'events' && <EventLog />}
      </div>
    </div>
  );
}

/** The hall in 3D with its right column (details and events); fills the content area edge to edge. */
export function TwinArea() {
  const rightOpen = useFactory((s) => s.rightOpen);
  const setPanel = useFactory((s) => s.setPanel);
  return (
    <div className="relative flex h-full min-h-0">
      {rightOpen && (
        <button
          aria-label="Detailspalte schliessen"
          className="absolute inset-0 z-10 bg-black/40 lg:hidden"
          onClick={() => setPanel('rightOpen', false)}
        />
      )}
      <div
        className="@container relative min-h-0 min-w-0 flex-1 touch-none"
        role="region"
        aria-label="3D-Ansicht der Fabrikhalle. Alle Anlagen lassen sich auch über die Bereiche Machines und Warehouse erreichen."
      >
        <SceneBoundary>
          <Suspense
            fallback={
              <div className="absolute inset-0 grid place-items-center text-[12px] text-txt-3">
                <span className="flex items-center gap-2">
                  <span className="h-3 w-3 animate-spin rounded-full border-2 border-accent border-t-transparent" />
                  3D-Szene wird geladen …
                </span>
              </div>
            }
          >
            <FactoryScene />
          </Suspense>
        </SceneBoundary>
        <div ref={(el) => { if (el) labelPortal.current = el; }} className="pointer-events-none absolute inset-0 overflow-hidden" aria-hidden="true" />
        <ViewportOverlay />
      </div>
      <aside
        aria-label="Anlagendetails und Ereignisse"
        className={cx(
          'z-20 w-[360px] max-w-[92vw] shrink-0 border-l border-line bg-ink-1 transition-transform duration-300 ease-[var(--ease-drawer)] lg:w-[320px] xl:w-[360px]',
          'absolute inset-y-0 right-0 lg:static',
          rightOpen ? 'translate-x-0' : 'translate-x-full lg:hidden',
        )}
      >
        <RightPanel />
      </aside>
    </div>
  );
}

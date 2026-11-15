import { Component, lazy, Suspense, useEffect, type ReactNode } from 'react';
import { control, useFactory, type CameraPreset, type RightTab } from './store/factoryStore';
import { STATUS_COLOR, STATUS_SHORT } from './ui/format';
import { DetailPanel } from './ui/panels/DetailPanel';
import { EventLog } from './ui/panels/EventLog';
import { SPEEDS, TopBar } from './ui/panels/TopBar';
import { labelPortal } from './scene/labelPortal';
import { cx } from './ui/primitives';

const FactoryScene = lazy(() => import('./scene/FactoryScene').then((m) => ({ default: m.FactoryScene })));

class SceneBoundary extends Component<{ children: ReactNode }, { error: string | null }> {
  state = { error: null as string | null };
  static getDerivedStateFromError(e: Error) {
    return { error: e.message };
  }
  render() {
    if (this.state.error)
      return (
        <div className="absolute inset-0 grid place-items-center p-6 text-center">
          <div className="max-w-sm text-sm text-txt-2">
            <div className="mb-2 font-semibold text-txt-1">3D-Ansicht nicht verfügbar</div>
            WebGL konnte nicht initialisiert werden ({this.state.error}). Simulation, Dashboard und Steuerung laufen weiter.
          </div>
        </div>
      );
    return this.props.children;
  }
}

const CAMS: { id: CameraPreset; label: string; key: string }[] = [
  { id: 'overview', label: 'Übersicht', key: 'Q' },
  { id: 'machining', label: 'Zerspanung', key: 'W' },
  { id: 'assembly', label: 'Montage', key: 'E' },
  { id: 'logistics', label: 'Logistik', key: 'R' },
  { id: 'top', label: 'Draufsicht', key: 'T' },
];

function ViewportOverlay() {
  const setCamera = useFactory((s) => s.setCamera);
  const camera = useFactory((s) => s.camera);
  const showLabels = useFactory((s) => s.showLabels);
  const showPaths = useFactory((s) => s.showPaths);
  const toggle = useFactory((s) => s.toggle);
  const running = useFactory((s) => s.sim.running);
  const speed = useFactory((s) => s.sim.speed);
  return (
    <>
      <div className="pointer-events-none absolute inset-x-0 top-0 flex flex-wrap items-start justify-between gap-2 p-2.5">
        <div className="pointer-events-auto flex flex-wrap gap-1 rounded-lg border border-line bg-ink-1/80 p-1 backdrop-blur-md" role="group" aria-label="Kameraperspektive">
          {CAMS.map((c) => (
            <button
              key={c.id}
              aria-pressed={camera.preset === c.id}
              onClick={() => setCamera(c.id)}
              title={`${c.label} (${c.key})`}
              className={cx('rounded-md px-2 py-1 text-[11px] font-medium transition-colors', camera.preset === c.id ? 'bg-ink-4 text-txt-1' : 'text-txt-3 hover:text-txt-1')}
            >
              {c.label}
            </button>
          ))}
        </div>
        <div className="pointer-events-auto flex gap-1 rounded-lg border border-line bg-ink-1/80 p-1 backdrop-blur-md">
          <button onClick={() => toggle('showLabels')} aria-pressed={showLabels} className={cx('rounded-md px-2 py-1 text-[11px] font-medium', showLabels ? 'bg-ink-4 text-txt-1' : 'text-txt-3 hover:text-txt-1')} title="Beschriftungen (L)">
            Labels
          </button>
          <button onClick={() => toggle('showPaths')} aria-pressed={showPaths} className={cx('rounded-md px-2 py-1 text-[11px] font-medium', showPaths ? 'bg-ink-4 text-txt-1' : 'text-txt-3 hover:text-txt-1')} title="AGV-Fahrwege (P)">
            Fahrwege
          </button>
        </div>
      </div>

      {!running && (
        <div className="pointer-events-none absolute left-1/2 top-14 -translate-x-1/2 rounded-full border border-amber-400/30 bg-amber-400/10 px-3 py-1 text-[11px] font-medium text-amber-100 backdrop-blur">
          Simulation pausiert · Leertaste zum Fortsetzen
        </div>
      )}

      <div className="pointer-events-none absolute bottom-2.5 left-2.5 hidden rounded-lg border border-line bg-ink-1/80 px-2.5 py-2 backdrop-blur-md @md:block">
        <div className="grid grid-cols-4 gap-x-3 gap-y-1">
          {(['RUNNING', 'IDLE', 'SETUP', 'BLOCKED', 'STOPPED', 'FAULT', 'REPAIR', 'MAINTENANCE', 'UNKNOWN'] as const).map((s) => (
            <span key={s} className="flex items-center gap-1.5 text-[10px] text-txt-2">
              <span className="h-1.5 w-1.5 rounded-full" style={{ background: STATUS_COLOR[s] }} />
              {STATUS_SHORT[s]}
            </span>
          ))}
        </div>
      </div>
      <div className="pointer-events-none absolute bottom-2.5 right-2.5 hidden text-right text-[10px] leading-relaxed text-txt-3 @4xl:block">
        <div>Linke Maustaste: drehen · Rechte: verschieben · Mausrad: zoomen</div>
        <div>Klick: auswählen · Doppelklick: fokussieren · Sim-Tempo {speed}×</div>
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
      <nav className="flex shrink-0 gap-0.5 border-b border-line px-2 pt-2" role="tablist" aria-label="Detailbereiche">
        {TABS.map((t) => (
          <button
            key={t.id}
            role="tab"
            id={`tab-${t.id}`}
            aria-selected={tab === t.id}
            aria-controls="right-tabpanel"
            onClick={() => setTab(t.id)}
            className={cx(
              '-mb-px flex items-center gap-1.5 border-b-2 px-2.5 pb-2 pt-1 text-[11px] font-medium transition-colors',
              tab === t.id ? 'border-teal-300 text-txt-1' : 'border-transparent text-txt-3 hover:text-txt-1',
            )}
          >
            {t.label}
          </button>
        ))}
      </nav>
      <div id="right-tabpanel" role="tabpanel" aria-labelledby={`tab-${tab}`} className="scroll-thin safe-bottom min-h-0 flex-1 overflow-y-auto p-3">
        {tab === 'details' && <DetailPanel />}
        {tab === 'events' && <EventLog />}
      </div>
    </div>
  );
}

function useShortcuts() {
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      const t = e.target as HTMLElement;
      if (t && (t.tagName === 'INPUT' || t.tagName === 'SELECT' || t.tagName === 'TEXTAREA' || t.isContentEditable)) return;
      if (e.ctrlKey || e.metaKey || e.altKey) return;
      const st = useFactory.getState();
      const k = e.key.toLowerCase();
      if (e.key === ' ') {
        e.preventDefault();
        void (st.sim.running ? control.pause() : control.play());
      } else if (/^[1-5]$/.test(e.key)) void control.setSpeed(SPEEDS[Number(e.key) - 1]);
      else if (e.key === 'Escape') st.select(null);
      else if (k === 'f' && st.selected) st.select(st.selected, true);
      else if (k === 'l') st.toggle('showLabels');
      else if (k === 'p') st.toggle('showPaths');
      else {
        const cam = CAMS.find((c) => c.key.toLowerCase() === k);
        if (cam) st.setCamera(cam.id);
      }
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, []);
}

export default function App() {
  const rightOpen = useFactory((s) => s.rightOpen);
  const setPanel = useFactory((s) => s.setPanel);
  useShortcuts();

  return (
    <div className="flex h-full flex-col overflow-hidden">
      <TopBar />
      <div className="relative flex min-h-0 flex-1">
        {/* mobile backdrop */}
        {rightOpen && (
          <button
            aria-label="Panels schließen"
            className="absolute inset-0 z-10 bg-black/40 lg:hidden"
            onClick={() => {
              setPanel('rightOpen', false);
            }}
          />
        )}
        <main className="relative flex min-w-0 flex-1 flex-col">
          <div className="@container relative min-h-0 flex-1 touch-none" role="region" aria-label="3D-Ansicht der Fabrikhalle. Alle Anlagen lassen sich auch über die Anlagenliste auswählen.">
            <SceneBoundary>
              <Suspense
                fallback={
                  <div className="absolute inset-0 grid place-items-center text-xs text-txt-3">
                    <span className="flex items-center gap-2">
                      <span className="h-3 w-3 animate-spin rounded-full border-2 border-teal-300 border-t-transparent" />
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
        </main>
        <aside
          aria-label="Anlagendetails und Ereignisse"
          className={cx(
            'z-20 w-[360px] max-w-[92vw] lg:w-[320px] xl:w-[360px] shrink-0 border-l border-line bg-ink-1 transition-transform duration-300 ease-[var(--ease-drawer)]',
            'absolute inset-y-0 right-0 lg:static',
            rightOpen ? 'translate-x-0' : 'translate-x-full lg:hidden',
          )}
        >
          <RightPanel />
        </aside>
      </div>
    </div>
  );
}

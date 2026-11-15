import { useEffect, useRef, useState, type ReactNode } from 'react';
import { EventsArea } from '../areas/events/EventsArea';
import { OverviewArea } from '../areas/overview/OverviewArea';
import { SettingsArea } from '../areas/settings/SettingsArea';
import { MachinesArea } from '../areas/machines/MachinesArea';
import { TwinArea } from '../areas/TwinArea';
import { cx } from '../ui/primitives';
import { areaById, type AreaId } from './areas';
import { Nav } from './Nav';
import { Placeholder } from './Placeholder';
import { useRoute } from './route';
import { SimBar } from './SimBar';

/** The page of an area. Areas built in later tasks replace their line here. */
function page(area: AreaId, param: string | null): ReactNode {
  switch (area) {
    case 'overview':
      return <OverviewArea />;
    case 'events':
      return <EventsArea filterId={param} />;
    case 'settings':
      return <SettingsArea />;
    case 'machines':
      return <MachinesArea id={param} />;
    case 'twin':
      return <TwinArea />;
    default:
      return <Placeholder area={areaById(area)} />;
  }
}

/**
 * The frame of every area: navigation on the left (an overlay menu below 1024 px), the simulation bar on top, the
 * area below. FactoryTwin fills its space edge to edge; every other area scrolls inside a bounded column.
 */
export function Shell() {
  const { area, param } = useRoute();
  const [menuOpen, setMenuOpen] = useState(false);
  const menuRef = useRef<HTMLDivElement>(null);
  const opener = useRef<HTMLElement | null>(null);

  useEffect(() => {
    if (!menuOpen) return;
    opener.current = document.activeElement as HTMLElement | null;
    const menu = menuRef.current;
    (menu?.querySelector<HTMLElement>('a[aria-current="page"]') ?? menu?.querySelector<HTMLElement>('a'))?.focus();
    const onKey = (e: KeyboardEvent) => e.key === 'Escape' && setMenuOpen(false);
    window.addEventListener('keydown', onKey);
    return () => {
      window.removeEventListener('keydown', onKey);
      opener.current?.focus();
    };
  }, [menuOpen]);

  const twin = area === 'twin';
  return (
    <div className="flex h-full overflow-hidden">
      <div className="hidden w-[224px] shrink-0 border-r border-line bg-ink-1 lg:block">
        <Nav current={area} />
      </div>
      {menuOpen && (
        <div className="fixed inset-0 z-40 lg:hidden">
          <button aria-label="Menü schliessen" className="absolute inset-0 bg-black/50" onClick={() => setMenuOpen(false)} />
          <div ref={menuRef} id="area-menu" role="dialog" aria-modal="true" aria-label="Bereiche" className="absolute inset-y-0 left-0 w-[264px] max-w-[85vw] border-r border-line bg-ink-1">
            <Nav current={area} onNavigate={() => setMenuOpen(false)} />
          </div>
        </div>
      )}
      <div className="flex min-w-0 flex-1 flex-col">
        <SimBar title={areaById(area).label} onMenu={() => setMenuOpen(true)} menuOpen={menuOpen} />
        <main className={cx('min-h-0 flex-1', twin ? 'overflow-hidden' : 'scroll-thin overflow-y-auto')}>
          {twin ? page(area, param) : <div className="mx-auto w-full max-w-[1600px]">{page(area, param)}</div>}
        </main>
      </div>
    </div>
  );
}

import { useEffect } from 'react';
import { CAMS } from './areas/TwinArea';
import { Shell } from './shell/Shell';
import { parseHash } from './shell/route';
import { SPEEDS } from './shell/SimBar';
import { control, useFactory } from './store/factoryStore';

/** Space and 1–5 steer the simulation everywhere; the 3D keys (camera, labels, paths, focus) only in FactoryTwin. */
function useShortcuts() {
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      const t = e.target as HTMLElement;
      if (t && (t.tagName === 'INPUT' || t.tagName === 'SELECT' || t.tagName === 'TEXTAREA' || t.isContentEditable)) return;
      if (e.ctrlKey || e.metaKey || e.altKey) return;
      if (t?.closest('[role="dialog"]')) return; // the open menu owns its keys (Escape closes it)
      const st = useFactory.getState();
      const k = e.key.toLowerCase();
      if (e.key === ' ') {
        if (t?.closest('button, a, summary, [role="switch"], [role="button"], [role="tab"], [tabindex]')) return;
        e.preventDefault();
        void (st.sim.running ? control.pause() : control.play());
        return;
      }
      if (/^[1-5]$/.test(e.key)) {
        void control.setSpeed(SPEEDS[Number(e.key) - 1]);
        return;
      }
      if (parseHash(window.location.hash).area !== 'twin') return;
      if (e.key === 'Escape') st.select(null);
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
  useShortcuts();
  return <Shell />;
}

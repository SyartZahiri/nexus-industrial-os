import { useEffect, useState } from 'react';
import { query } from '../store/factoryStore';
import type { Kpis } from './types';

const EVERY_MS = 2_000;

/** Key figures of the last windowMinutes, asked every 2 s while the component is shown; refusals keep the last. */
export function useKpis(windowMinutes = 60): Kpis | null {
  const [kpis, setKpis] = useState<Kpis | null>(null);
  useEffect(() => {
    let alive = true;
    const ask = async () => {
      try {
        const a = await query<Kpis>({ query: 'kpis', windowMinutes });
        if (alive && a.ok) setKpis(a.result);
      } catch {
        // not connected yet or connection lost: keep the last figures, the next tick asks again
      }
    };
    void ask();
    const timer = setInterval(() => void ask(), EVERY_MS);
    return () => {
      alive = false;
      clearInterval(timer);
    };
  }, [windowMinutes]);
  return kpis;
}

import { useEffect, useState } from 'react';
import { query, useFactory } from '../store/factoryStore';
import type { Query } from './types';

const RETRY_MS = 2_000;

/**
 * Asks the plant now and then every everyMs. everyMs = 0 asks until the first answer and stops (master data, the
 * path network). A restart asks again; a refusal keeps the last data and gives its reason as error.
 */
export function usePlantQuery<T>(q: Query, everyMs = 0): { data: T | null; error: string | null; reload: () => void } {
  const key = JSON.stringify(q);
  const run = useFactory((s) => s.run);
  const [nonce, setNonce] = useState(0);
  const [state, setState] = useState<{ data: T | null; error: string | null }>({ data: null, error: null });
  useEffect(() => {
    let alive = true;
    let timer: ReturnType<typeof setInterval> | undefined;
    const ask = async () => {
      try {
        const a = await query<T>(JSON.parse(key) as Query);
        if (!alive) return;
        if (a.ok) {
          setState({ data: a.result, error: null });
          if (everyMs <= 0) clearInterval(timer);
        } else setState((s) => ({ data: s.data, error: a.message }));
      } catch {
        // not connected yet: the next tick asks again
      }
    };
    timer = setInterval(() => void ask(), everyMs > 0 ? everyMs : RETRY_MS);
    void ask();
    return () => {
      alive = false;
      clearInterval(timer);
    };
  }, [key, everyMs, run, nonce]);
  return { ...state, reload: () => setNonce((n) => n + 1) };
}

import type { Kpis } from './types';
import { usePlantQuery } from './usePlantQuery';

/** Key figures of the last windowMinutes, asked every 2 s while the component is shown; refusals keep the last. */
export function useKpis(windowMinutes = 60): Kpis | null {
  return usePlantQuery<Kpis>({ query: 'kpis', windowMinutes }, 2_000).data;
}

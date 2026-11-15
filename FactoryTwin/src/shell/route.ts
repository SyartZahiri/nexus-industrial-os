import { useSyncExternalStore } from 'react';
import { AREAS, type AreaId } from './areas';

export interface Route {
  area: AreaId;
  param: string | null;
}

/** "#/area" or "#/area/param"; anything else is the overview, so no link ever shows an empty page. */
export function parseHash(hash: string): Route {
  const m = /^#\/([a-z]+)(?:\/(.+))?$/.exec(hash);
  const known = m !== null && AREAS.some((a) => a.id === m[1]);
  return known ? { area: m[1] as AreaId, param: decode(m[2]) } : { area: 'overview', param: null };
}

function decode(p: string | undefined): string | null {
  if (!p) return null;
  try {
    return decodeURIComponent(p);
  } catch {
    return null;
  }
}

export function hrefOf(area: AreaId, param?: string): string {
  return `#/${area}${param ? `/${encodeURIComponent(param)}` : ''}`;
}

const subscribe = (onChange: () => void) => {
  window.addEventListener('hashchange', onChange);
  return () => window.removeEventListener('hashchange', onChange);
};

/** The current route; re-renders on every hash change. The hash string is the snapshot, so React sees a stable value. */
export function useRoute(): Route {
  const hash = useSyncExternalStore(subscribe, () => window.location.hash);
  return parseHash(hash);
}

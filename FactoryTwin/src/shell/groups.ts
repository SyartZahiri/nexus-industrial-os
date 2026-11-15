import { AREAS, type Area } from './areas';

const GROUPS: Area['group'][] = ['Betrieb', 'Systeme', 'Auswertung', 'System'];

/** The navigation's groups in a fixed order; within a group the areas keep the order of AREAS. */
export function groupedAreas(): { group: Area['group']; areas: Area[] }[] {
  return GROUPS.map((group) => ({ group, areas: AREAS.filter((a) => a.group === group) }));
}

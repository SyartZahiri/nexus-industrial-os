export type AreaId =
  | 'overview'
  | 'twin'
  | 'smartfactory'
  | 'flowpilot'
  | 'routemind'
  | 'machines'
  | 'warehouse'
  | 'analytics'
  | 'events'
  | 'scenarios'
  | 'settings';

export interface Area {
  id: AreaId;
  label: string;
  group: 'Betrieb' | 'Systeme' | 'Auswertung' | 'System';
  hash: string;
  /** null: built; otherwise the milestone that builds it (the page says so meanwhile). */
  comesWith: null | 'M2-C' | 'M3' | 'M4';
  /** One sentence: what the area is for. Shown on placeholders and as the page subtitle. */
  summary: string;
}

/** The eleven areas of Spec 14, in navigation order. The only source for navigation, routes and placeholders. */
export const AREAS: Area[] = [
  { id: 'overview', label: 'Overview', group: 'Betrieb', hash: '#/overview', comesWith: null,
    summary: 'Lage der ganzen Anlage auf einen Blick.' },
  { id: 'twin', label: 'FactoryTwin', group: 'Betrieb', hash: '#/twin', comesWith: null,
    summary: 'Die Halle in 3D, mit Details und Befehlen zu jeder Anlage.' },
  { id: 'smartfactory', label: 'SmartFactory', group: 'Systeme', hash: '#/smartfactory', comesWith: 'M2-C',
    summary: 'Aufträge, Planung und Termine der Fertigung.' },
  { id: 'flowpilot', label: 'FlowPilot', group: 'Systeme', hash: '#/flowpilot', comesWith: 'M2-C',
    summary: 'Materialfluss, Transporte und die AGV-Flotte.' },
  { id: 'routemind', label: 'RouteMind', group: 'Systeme', hash: '#/routemind', comesWith: 'M4',
    summary: 'Auslieferung an Kunden: Touren, Fahrzeuge und Karte.' },
  { id: 'machines', label: 'Machines', group: 'Betrieb', hash: '#/machines', comesWith: null,
    summary: 'Jede Maschine mit Zustand, Sensoren und Verlauf.' },
  { id: 'warehouse', label: 'Warehouse', group: 'Betrieb', hash: '#/warehouse', comesWith: null,
    summary: 'Bestände, Reservierungen und Wareneingang.' },
  { id: 'analytics', label: 'Analytics', group: 'Auswertung', hash: '#/analytics', comesWith: 'M2-C',
    summary: 'Durchsatz, OEE, Durchlaufzeiten und Engpass aus den Ereignissen.' },
  { id: 'events', label: 'Event Monitor', group: 'Auswertung', hash: '#/events', comesWith: null,
    summary: 'Jedes Ereignis der Anlage, filterbar nach Auftrag, Quelle und Typ.' },
  { id: 'scenarios', label: 'Scenario Lab', group: 'Auswertung', hash: '#/scenarios', comesWith: 'M3',
    summary: 'Störungen und Engpässe gezielt auslösen und die Reaktion verfolgen.' },
  { id: 'settings', label: 'Settings', group: 'System', hash: '#/settings', comesWith: null,
    summary: 'Regeln der Simulation, Tempo, Seed und Darstellung.' },
];

export function areaById(id: AreaId): Area {
  return AREAS.find((a) => a.id === id)!;
}

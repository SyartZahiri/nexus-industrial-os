# M2-B Shell, Navigation und Designsystem – Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Aus FactoryTwin wird die NEXUS-Oberfläche: eine Shell mit elf Bereichen über Hash-Routing, ein ruhiges, dunkles Designsystem nach Spec 14 und die ersten vier fertigen Bereiche Overview, FactoryTwin, Event Monitor und Settings, in beiden Betriebsarten.

**Architecture:** Eine Bereichsliste (`src/shell/areas.ts`) ist die einzige Quelle für Navigation, Routen und Platzhalter. `useRoute` liest den Hash. Die Shell (Seitenleiste, Kopfleiste mit Simulationssteuerung, Inhaltsfläche) rendert den aktiven Bereich. Gemeinsame Bausteine (Kennzahl, Statusmarke, Datentabelle, Abschnitt) liegen in `src/ui/kit`, ihre Logik (Sortieren, Filtern, Kennzahlen aus Frame und Abfrage) in reinen, getesteten Modulen. Die 3D-Szene bleibt, wie sie ist, und lebt im Bereich FactoryTwin.

**Tech Stack:** React 19, TypeScript 7, Vite 8, Tailwind 4, Zustand 5, Vitest 5; Java-Kern über `NexusClient` (M2-A).

**Spec:** `docs/superpowers/specs/2026-11-10-nexus-industrial-os-design.md` (Revision 3), Abschnitt 14 (Navigation, Designsystem, Layout), 12 (`GET /events`-Filter), 20 (Betriebsart ausweisen). Voraussetzung: M2-A ist gemergt (`008fdfd`).

**Form:** wie M2-A: vollständiger Code für Logik und Bausteine, für die Bereichsseiten vollständiges JSX.

## Entscheidungen dieses Plans

- **Designrichtung „Leitstand, Schweizer Raster".** Eine Spalte Navigation, ein 12-Spalten-Raster mit 8-px-Grundmass, Hierarchie über Schriftgrösse und -gewicht statt über Kästen. Haarlinien (`border-line`) trennen Bereiche; keine Karten in Karten, keine Schatten, keine Verläufe, kein Glas (`backdrop-blur` entfällt überall). Zahlen und IDs in JetBrains Mono mit Tabellenziffern, Text in Inter. Farbe trägt nur Zustand (Spec 14): läuft, wartet, gestört, Wartung, Rüsten, unbekannt. Der einzige Akzent (Türkis) markiert Fokus und Auswahl.
- **Typografie-Skala:** 11 (Marker), 12 (Tabellen), 13 (Fliesstext), 15 (Abschnitt), 20 (Bereichstitel), 32 (Kennzahl). Kleinbuchstaben-Versalien nur für Spaltenköpfe und Marker, mit `tracking-[0.08em]`.
- **Zustandsfarben als Tokens** (`--color-state-*` in `@theme`), damit Szene, Tabellen und Marken dieselben Werte nutzen; `STATUS_COLOR` in `format.ts` liest daraus.
- **Platzhalter statt leerer Seiten.** Bereiche, die M2-C, M3 und M4 bauen, zeigen in der Navigation ihren Namen und eine Seite mit einem Satz, was dort kommt, und einem Verweis auf das, was es heute schon gibt (z. B. Machines → FactoryTwin mit Maschinenliste). So ist die Navigation von Anfang an vollständig (Spec 14: elf Bereiche).
- **Ereignisverlauf über `NexusClient.events(filter)`.** Server: `GET /api/v1/events` mit `type`, `source`, `correlationId`, `after`, `limit` (Spec 12). Browser: der Worker filtert die Ereignisse des Laufs aus `plantEventsFrom(0)` mit derselben Regel (Filtern ist Darstellung, keine Fachlogik). Der Store behält weiter nur die letzten 500 für Live-Ansichten.
- **Kennzahlen alle 2 s Echtzeit** über die Abfrage `kpis` (M2-A), solange ein Bereich sie zeigt; nicht bei jedem Frame.
- **Der 3D-Canvas lebt nur im Bereich FactoryTwin.** Beim Wechsel wird er abgebaut; der Twin (Frames) läuft im Store weiter, die Szene steht beim Zurückkehren sofort wieder. Ein dauerhaft versteckter Canvas würde auf allen anderen Seiten Grafikleistung kosten.

## Global Constraints

- Alles aus M2-A gilt weiter: keine Geschäftsregeln im Frontend, TypeScript strikt, keine neuen Laufzeitabhängigkeiten, Vitest, Texte auf Deutsch, Code und Kommentare auf Englisch.
- Spec 14: elf Bereiche über Hash-Routing: Overview, FactoryTwin, SmartFactory, FlowPilot, RouteMind, Machines, Warehouse, Analytics, Event Monitor, Scenario Lab, Settings.
- Spec 14: Inter für Text, JetBrains Mono für Zahlen und IDs; dunkles Thema mit neutralen Flächen, Farbe nur für Zustände, keine Verläufe, kein Glas-Effekt; Layout für Desktop und grosse Leitstandbildschirme, einspaltig unter 1024 px.
- Spec 20: die Oberfläche weist die aktive Betriebsart aus.
- Barrierefreiheit: jede Navigation per Tastatur erreichbar, sichtbarer Fokus (bestehender `:focus-visible`-Ring), Tabellen mit `<th scope="col">` und `aria-sort`, Statusfarbe nie allein (immer mit Text).
- Commits enden mit `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`. Nicht pushen.

## Review Focus

1. **Unbekannter oder alter Link.** `#/maschinen`, `#/`, ein leerer Hash oder ein Bereich mit Parameter (`#/machines/CNC-01`) darf nie eine leere Seite zeigen. Erwartet: unbekannt → Overview, Parameter bleiben lesbar. Test in Task 1 (`unknownHashesLandOnTheOverview`).
2. **Schmaler Bildschirm.** Unter 1024 px ist die Navigation ein Menü über dem Inhalt, nichts läuft seitlich aus dem Bild, Tabellen scrollen in sich. Sichtprüfung in Task 7 bei 390 px und 1024 px.
3. **Tausende Ereignisse.** Der Event Monitor bleibt bei 10 000 Ereignissen bedienbar: er zeigt höchstens 500 Zeilen auf einmal und lädt weitere seitenweise. Test in Task 5 (`filterKeepsAtMostAPageAndSaysSo`).
4. **Regeln, die der Kern ablehnt.** Ein ungültiger Wert in Settings (z. B. Wartungsschwelle 1,5) wird nicht still übernommen: die Meldung des Kerns erscheint, das Formular zeigt wieder den gültigen Stand. Test in Task 6 (`aRefusedRuleKeepsTheFormOnTheValidValues`).
5. **Kennzahlen vor dem ersten Lauf.** Ohne abgeschlossene Aufträge sind Durchsatz 0 und Termintreue undefiniert; die Overview zeigt „–" statt `NaN %` oder `100 %`. Test in Task 4 (`figuresWithoutFinishedOrdersShowADash`).

---

### Task 1: Bereiche und Hash-Routing

**Files:**
- Create: `FactoryTwin/src/shell/areas.ts`, `FactoryTwin/src/shell/route.ts`
- Test: `FactoryTwin/src/shell/route.test.ts`

**Interfaces:**
- Produces:
  - `type AreaId = 'overview' | 'twin' | 'smartfactory' | 'flowpilot' | 'routemind' | 'machines' | 'warehouse' | 'analytics' | 'events' | 'scenarios' | 'settings'`
  - `interface Area { id: AreaId; label: string; group: 'Betrieb' | 'Systeme' | 'Auswertung' | 'System'; hash: string; comesWith: null | 'M2-C' | 'M3' | 'M4'; summary: string }`
  - `const AREAS: Area[]` (Reihenfolge = Navigation), `areaById(id): Area`
  - `parseHash(hash: string): { area: AreaId; param: string | null }`, `hrefOf(area: AreaId, param?: string): string`
  - `useRoute(): { area: AreaId; param: string | null }` (hört auf `hashchange`)

- [ ] **Step 1: Failing test schreiben**

`FactoryTwin/src/shell/route.test.ts`:

```ts
import { describe, expect, it } from 'vitest';
import { AREAS } from './areas';
import { hrefOf, parseHash } from './route';

describe('route', () => {
  it('has the eleven areas of the spec, in navigation order', () => {
    expect(AREAS.map((a) => a.label)).toEqual([
      'Overview', 'FactoryTwin', 'SmartFactory', 'FlowPilot', 'RouteMind', 'Machines', 'Warehouse', 'Analytics',
      'Event Monitor', 'Scenario Lab', 'Settings',
    ]);
  });

  it('reads an area and an optional parameter', () => {
    expect(parseHash('#/twin')).toEqual({ area: 'twin', param: null });
    expect(parseHash('#/machines/CNC-01')).toEqual({ area: 'machines', param: 'CNC-01' });
    expect(parseHash('#/events/PO-0004')).toEqual({ area: 'events', param: 'PO-0004' });
  });

  it('unknownHashesLandOnTheOverview', () => {
    for (const h of ['', '#', '#/', '#/maschinen', '#twin', 'twin']) {
      expect(parseHash(h).area, h).toBe('overview');
    }
  });

  it('writes links that read back to the same place', () => {
    for (const a of AREAS) expect(parseHash(hrefOf(a.id)).area).toBe(a.id);
    expect(parseHash(hrefOf('machines', 'ROB-01'))).toEqual({ area: 'machines', param: 'ROB-01' });
    expect(hrefOf('events', 'PO 1/2')).toBe('#/events/PO%201%2F2');
    expect(parseHash('#/events/PO%201%2F2').param).toBe('PO 1/2');
  });
});
```

- [ ] **Step 2: Test laufen lassen, rot**

Run: `cd FactoryTwin && npx vitest run src/shell`
Expected: FAIL, Module fehlen.

- [ ] **Step 3: Umsetzen**

`FactoryTwin/src/shell/areas.ts`:

```ts
export type AreaId =
  | 'overview' | 'twin' | 'smartfactory' | 'flowpilot' | 'routemind' | 'machines' | 'warehouse' | 'analytics'
  | 'events' | 'scenarios' | 'settings';

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
  { id: 'machines', label: 'Machines', group: 'Betrieb', hash: '#/machines', comesWith: 'M2-C',
    summary: 'Jede Maschine mit Zustand, Sensoren und Verlauf.' },
  { id: 'warehouse', label: 'Warehouse', group: 'Betrieb', hash: '#/warehouse', comesWith: 'M2-C',
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
```

`FactoryTwin/src/shell/route.ts`:

```ts
import { useSyncExternalStore } from 'react';
import { AREAS, type AreaId } from './areas';

export interface Route {
  area: AreaId;
  param: string | null;
}

/** "#/area" or "#/area/param"; anything else is the overview, so no link ever shows an empty page. */
export function parseHash(hash: string): Route {
  const m = /^#\/([a-z]+)(?:\/(.+))?$/.exec(hash);
  const area = m && AREAS.some((a) => a.id === m[1]) ? (m[1] as AreaId) : 'overview';
  return { area, param: area !== 'overview' || m?.[1] === 'overview' ? decode(m?.[2]) : null };
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
```

- [ ] **Step 4: Test laufen lassen, grün**

Run: `cd FactoryTwin && npx vitest run src/shell`
Expected: PASS (4 Tests).

- [ ] **Step 5: Commit**

```bash
git add FactoryTwin/src/shell
git commit -m "feat(ui): elf Bereiche und Hash-Routing"
```

---

### Task 2: Designsystem – Tokens und Bausteine

**Files:**
- Modify: `FactoryTwin/src/index.css` (Zustands- und Flächen-Tokens), `FactoryTwin/src/ui/format.ts` (`STATUS_COLOR`, `AGV_COLOR` aus den Tokens), `FactoryTwin/index.html` (Titel „NEXUS Industrial OS", Beschreibung)
- Create: `FactoryTwin/src/ui/kit/table.ts` (Sortierlogik), `FactoryTwin/src/ui/kit/DataTable.tsx`, `FactoryTwin/src/ui/kit/Stat.tsx`, `FactoryTwin/src/ui/kit/StateTag.tsx`, `FactoryTwin/src/ui/kit/Section.tsx`, `FactoryTwin/src/ui/kit/index.ts`
- Test: `FactoryTwin/src/ui/kit/table.test.ts`

**Interfaces:**
- Produces:
  - CSS-Tokens in `@theme`: `--color-state-run: #22c55e`, `--color-state-idle: #94a3b8`, `--color-state-setup: #38bdf8`, `--color-state-wait: #f59e0b`, `--color-state-fault: #ef4444`, `--color-state-repair: #fb923c`, `--color-state-maint: #a78bfa`, `--color-state-unknown: #6b7280`.
  - `type Column<T> = { key: string; label: string; value: (row: T) => string | number | null; render?: (row: T) => ReactNode; align?: 'left' | 'right'; mono?: boolean; width?: string }`
  - `sortRows<T>(rows: T[], column: Column<T>, dir: 'asc' | 'desc'): T[]` (stabil; `null` immer am Ende; Zahlen numerisch; Zeichenketten mit `localeCompare(…, 'de', { numeric: true })`)
  - `<DataTable rows columns rowKey initialSort? onRowClick? empty? />` (klickbare Spaltenköpfe mit `aria-sort`, Zeilen per Tastatur fokussierbar und mit Enter auslösbar, horizontales Scrollen in sich)
  - `<Stat label value unit? hint? tone? />` (Kennzahl: Marker 11 px, Wert 32 px Mono), `<StateTag status kind="machine" | "agv" />` (Punkt + Text, Farbe aus Token), `<Section title action? children />` (Haarlinie oben, Titel 15 px)

- [ ] **Step 1: Failing test schreiben**

`FactoryTwin/src/ui/kit/table.test.ts`:

```ts
import { describe, expect, it } from 'vitest';
import { sortRows, type Column } from './table';

interface Row { id: string; n: number | null }
const rows: Row[] = [
  { id: 'CNC-10', n: 3 }, { id: 'CNC-2', n: null }, { id: 'CNC-1', n: 3 }, { id: 'ROB-01', n: 1 },
];
const byId: Column<Row> = { key: 'id', label: 'ID', value: (r) => r.id };
const byN: Column<Row> = { key: 'n', label: 'N', value: (r) => r.n };

describe('sortRows', () => {
  it('sorts text naturally, numbers numerically', () => {
    expect(sortRows(rows, byId, 'asc').map((r) => r.id)).toEqual(['CNC-1', 'CNC-2', 'CNC-10', 'ROB-01']);
    expect(sortRows(rows, byN, 'asc').map((r) => r.id)).toEqual(['ROB-01', 'CNC-10', 'CNC-1', 'CNC-2']);
  });

  it('keeps equal rows in their order and puts empty values last in both directions', () => {
    expect(sortRows(rows, byN, 'desc').map((r) => r.id)).toEqual(['CNC-10', 'CNC-1', 'ROB-01', 'CNC-2']);
  });

  it('does not change the rows it was given', () => {
    const copy = [...rows];
    sortRows(rows, byId, 'desc');
    expect(rows).toEqual(copy);
  });
});
```

- [ ] **Step 2: Test laufen lassen, rot**

Run: `cd FactoryTwin && npx vitest run src/ui/kit`
Expected: FAIL, `./table` fehlt.

- [ ] **Step 3: Umsetzen**

`FactoryTwin/src/ui/kit/table.ts`:

```ts
import type { ReactNode } from 'react';

export interface Column<T> {
  key: string;
  label: string;
  value: (row: T) => string | number | null;
  render?: (row: T) => ReactNode;
  align?: 'left' | 'right';
  mono?: boolean;
  width?: string;
}

export type SortDir = 'asc' | 'desc';

/** A sorted copy: stable, empty values last in both directions, text in natural order ("CNC-2" before "CNC-10"). */
export function sortRows<T>(rows: T[], column: Column<T>, dir: SortDir): T[] {
  const sign = dir === 'asc' ? 1 : -1;
  return rows
    .map((row, i) => ({ row, i, v: column.value(row) }))
    .sort((a, b) => {
      if (a.v === null || b.v === null) return a.v === b.v ? a.i - b.i : a.v === null ? 1 : -1;
      const c = typeof a.v === 'number' && typeof b.v === 'number'
        ? a.v - b.v
        : String(a.v).localeCompare(String(b.v), 'de', { numeric: true });
      return c !== 0 ? sign * c : a.i - b.i;
    })
    .map((x) => x.row);
}
```

`FactoryTwin/src/ui/kit/DataTable.tsx`:

```tsx
import { useMemo, useState, type ReactNode } from 'react';
import { cx } from '../primitives';
import { sortRows, type Column, type SortDir } from './table';

export function DataTable<T>({
  rows, columns, rowKey, initialSort, onRowClick, empty = 'Keine Einträge.', label,
}: {
  rows: T[];
  columns: Column<T>[];
  rowKey: (row: T) => string;
  initialSort?: { key: string; dir: SortDir };
  onRowClick?: (row: T) => void;
  empty?: ReactNode;
  label: string;
}) {
  const [sort, setSort] = useState(initialSort ?? null);
  const shown = useMemo(() => {
    const column = sort && columns.find((c) => c.key === sort.key);
    return column ? sortRows(rows, column, sort!.dir) : rows;
  }, [rows, columns, sort]);
  const toggle = (key: string) =>
    setSort((s) => (s?.key === key ? { key, dir: s.dir === 'asc' ? 'desc' : 'asc' } : { key, dir: 'asc' }));
  return (
    <div className="scroll-thin overflow-x-auto">
      <table className="w-full border-collapse text-[12px]" aria-label={label}>
        <thead>
          <tr className="border-b border-line">
            {columns.map((c) => (
              <th
                key={c.key}
                scope="col"
                style={{ width: c.width }}
                aria-sort={sort?.key === c.key ? (sort.dir === 'asc' ? 'ascending' : 'descending') : 'none'}
                className={cx('px-3 py-2 font-medium', c.align === 'right' ? 'text-right' : 'text-left')}
              >
                <button
                  type="button"
                  onClick={() => toggle(c.key)}
                  className="text-[11px] uppercase tracking-[0.08em] text-txt-3 hover:text-txt-1"
                >
                  {c.label}
                  {sort?.key === c.key && <span aria-hidden="true">{sort.dir === 'asc' ? ' ↑' : ' ↓'}</span>}
                </button>
              </th>
            ))}
          </tr>
        </thead>
        <tbody>
          {shown.length === 0 && (
            <tr>
              <td colSpan={columns.length} className="px-3 py-8 text-center text-txt-3">{empty}</td>
            </tr>
          )}
          {shown.map((row) => (
            <tr
              key={rowKey(row)}
              tabIndex={onRowClick ? 0 : undefined}
              onClick={onRowClick ? () => onRowClick(row) : undefined}
              onKeyDown={onRowClick ? (e) => e.key === 'Enter' && onRowClick(row) : undefined}
              className={cx('border-b border-line/60', onRowClick && 'cursor-pointer hover:bg-white/[0.03] focus-visible:bg-white/[0.05]')}
            >
              {columns.map((c) => (
                <td
                  key={c.key}
                  className={cx('px-3 py-2 align-top', c.align === 'right' && 'text-right', c.mono && 'font-mono tnum')}
                >
                  {c.render ? c.render(row) : (c.value(row) ?? '–')}
                </td>
              ))}
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}
```

`FactoryTwin/src/ui/kit/Stat.tsx`:

```tsx
import type { ReactNode } from 'react';

/** One key figure: a small label over a large monospaced value. tone colours the value only for a state. */
export function Stat({ label, value, unit, hint, tone }: {
  label: string; value: ReactNode; unit?: string; hint?: ReactNode; tone?: string;
}) {
  return (
    <div className="min-w-0">
      <div className="text-[11px] uppercase tracking-[0.08em] text-txt-3">{label}</div>
      <div className="mt-1 flex items-baseline gap-1.5">
        <span className="font-mono text-[32px] leading-none tracking-tight tnum" style={{ color: tone }}>{value}</span>
        {unit && <span className="text-[13px] text-txt-2">{unit}</span>}
      </div>
      {hint && <div className="mt-1.5 text-[12px] text-txt-3">{hint}</div>}
    </div>
  );
}
```

`FactoryTwin/src/ui/kit/StateTag.tsx`:

```tsx
import type { AgvState, MachineStatus } from '../../twin/types';
import { AGV_COLOR, AGV_LABEL, STATUS_COLOR, STATUS_SHORT } from '../format';

/** A state as dot and word; the colour never stands alone. */
export function StateTag(props: { kind: 'machine'; status: MachineStatus } | { kind: 'agv'; status: AgvState }) {
  const color = props.kind === 'machine' ? STATUS_COLOR[props.status] : AGV_COLOR[props.status];
  const text = props.kind === 'machine' ? STATUS_SHORT[props.status] : AGV_LABEL[props.status];
  return (
    <span className="inline-flex items-center gap-1.5 whitespace-nowrap text-[12px] text-txt-1">
      <span className="h-1.5 w-1.5 shrink-0 rounded-full" style={{ background: color }} aria-hidden="true" />
      {text}
    </span>
  );
}
```

`FactoryTwin/src/ui/kit/Section.tsx`:

```tsx
import type { ReactNode } from 'react';

/** A part of a page: hairline above, title on the left, an optional action on the right. No box around it. */
export function Section({ title, action, children, className }: {
  title: string; action?: ReactNode; children: ReactNode; className?: string;
}) {
  return (
    <section className={`border-t border-line pt-4 ${className ?? ''}`} aria-label={title}>
      <div className="mb-3 flex items-baseline justify-between gap-3">
        <h2 className="text-[15px] font-semibold tracking-tight text-txt-1">{title}</h2>
        {action}
      </div>
      {children}
    </section>
  );
}
```

`FactoryTwin/src/ui/kit/index.ts`: `export * from './DataTable'; export * from './Section'; export * from './Stat'; export * from './StateTag'; export * from './table';`

`index.css`, im `@theme`-Block nach `--color-series`: die acht `--color-state-*`-Tokens aus Interfaces. `format.ts`: `STATUS_COLOR` und `AGV_COLOR` verweisen auf `var(--color-state-…)` (RUNNING→run, IDLE→idle, SETUP→setup, BLOCKED→wait, STOPPED→idle, FAULT→fault, REPAIR→repair, MAINTENANCE→maint, UNKNOWN→unknown; AGV: IDLE→idle, Fahrten und Laden→setup, CHARGING→run, FAULT→fault, OFFLINE→unknown). Die 3D-Materialien in `src/scene/materials.ts` brauchen echte Farbwerte, nicht `var(...)`: dort eine eigene Hex-Tabelle `STATE_HEX` mit denselben Werten anlegen und die Szene darauf umstellen, wo sie heute `STATUS_COLOR` als Farbe an Three.js gibt (`grep -rn "STATUS_COLOR\|AGV_COLOR" src/scene`).

`index.html`: `<title>NEXUS Industrial OS</title>`, Beschreibung „Fertigung, Materialfluss und Logistik in einem Leitstand: Digital Twin, MES und Intralogistik auf einem Java-Kern."

- [ ] **Step 4: Tests, Typprüfung**

Run: `cd FactoryTwin && npx vitest run && npx tsc -b --noEmit`
Expected: PASS, keine Typfehler.

- [ ] **Step 5: Commit**

```bash
git add FactoryTwin/src/ui FactoryTwin/src/index.css FactoryTwin/src/scene FactoryTwin/index.html
git commit -m "feat(ui): Designsystem – Zustandsfarben als Tokens, Tabelle, Kennzahl, Statusmarke, Abschnitt"
```

---

### Task 3: Shell mit Navigation, Kopfleiste und Bereichen

**Files:**
- Create: `FactoryTwin/src/shell/Shell.tsx`, `FactoryTwin/src/shell/Nav.tsx`, `FactoryTwin/src/shell/SimBar.tsx`, `FactoryTwin/src/shell/Placeholder.tsx`, `FactoryTwin/src/areas/TwinArea.tsx`
- Modify: `FactoryTwin/src/App.tsx` (nur noch `<Shell />` und Tastenkürzel), `FactoryTwin/src/ui/panels/TopBar.tsx` (entfällt; Inhalte wandern in `SimBar`)
- Test: `FactoryTwin/src/shell/nav.test.ts`

**Interfaces:**
- Consumes: `AREAS`, `useRoute`, `hrefOf` (Task 1); `useFactory`, `control` (M2-A).
- Produces: `groupedAreas(): { group: string; areas: Area[] }[]` (in `Nav.tsx` exportiert, getestet); `<Shell />`; `<TwinArea />` (die heutige 3D-Ansicht samt rechter Spalte, unverändert im Verhalten).

Aufbau der Shell (Desktop ≥ 1024 px): links eine feste Navigationsspalte 224 px (`bg-ink-1`, Haarlinie rechts): oben Wortmarke „NEXUS" (13 px, 600) mit Unterzeile „Industrial OS", darunter die Gruppen als 11-px-Marker und die Bereiche als Links (13 px; aktiver Bereich: Text `txt-1`, 2-px-Akzentlinie links, `aria-current="page"`; Bereiche mit `comesWith` zeigen rechts den Meilenstein in 10 px `txt-3`). Ganz unten in der Spalte: Betriebsart und Verbindung (wie heute in der Kopfleiste) und der Seed. Rechts davon oben die Simulationsleiste `SimBar` (48 px hoch, Haarlinie unten): Bereichstitel 20 px links; rechts Uhrzeit (Mono), Start/Pause, Tempo (`Segmented` 1/2/5/10/25), Reset mit Bestätigung, Fehler/Warnungen-Zähler (Klick → `#/events`). Darunter die Meldungszeile (`notice`) wie heute. Darunter der Bereich, scrollend; Seiten haben `max-w-[1600px]` und 24 px Rand, der FactoryTwin-Bereich füllt die Fläche randlos.

Unter 1024 px: die Navigationsspalte wird ein Menü-Knopf links in der `SimBar`, der ein Overlay mit derselben Liste öffnet (Escape und Klick auf einen Eintrag schliessen es, Fokus springt hinein und zurück); Uhrzeit und Tempo-Leiste rücken in eine zweite Zeile.

`Placeholder` für Bereiche mit `comesWith`: Titel, `summary`, „Kommt mit {comesWith}." und je Bereich ein Verweis auf das, was es schon gibt: Machines/FlowPilot → FactoryTwin, SmartFactory/Analytics → Overview, Warehouse → FactoryTwin (Lager anklicken), Scenario Lab → Settings (Regeln), RouteMind → Overview.

- [ ] **Step 1: Failing test schreiben**

`FactoryTwin/src/shell/nav.test.ts`:

```ts
import { describe, expect, it } from 'vitest';
import { groupedAreas } from './Nav';

describe('groupedAreas', () => {
  it('groups the areas in a fixed order and keeps every area once', () => {
    const groups = groupedAreas();
    expect(groups.map((g) => g.group)).toEqual(['Betrieb', 'Systeme', 'Auswertung', 'System']);
    expect(groups.flatMap((g) => g.areas.map((a) => a.id)).sort()).toHaveLength(11);
    expect(groups[0].areas.map((a) => a.id)).toEqual(['overview', 'twin', 'machines', 'warehouse']);
  });
});
```

- [ ] **Step 2: Test laufen lassen, rot**

Run: `cd FactoryTwin && npx vitest run src/shell`
Expected: FAIL, `./Nav` fehlt.

- [ ] **Step 3: Umsetzen**

`Nav.tsx` exportiert `groupedAreas()`:

```ts
const GROUPS: Area['group'][] = ['Betrieb', 'Systeme', 'Auswertung', 'System'];

/** The navigation's groups; within a group the areas keep the order of AREAS. */
export function groupedAreas(): { group: Area['group']; areas: Area[] }[] {
  return GROUPS.map((group) => ({ group, areas: AREAS.filter((a) => a.group === group) }));
}
```

und die Komponente `Nav({ onNavigate }: { onNavigate?: () => void })` nach der Beschreibung oben (Links sind `<a href={a.hash}>`, kein Router-Paket). `SimBar.tsx` übernimmt aus `TopBar.tsx` Uhr, Start/Pause, Tempo, Reset mit Bestätigung, Fehlerzähler und die Meldungszeile; ohne Glas (`backdrop-blur` und halbtransparente Hintergründe entfallen: `bg-ink-1`). `TwinArea.tsx` enthält den heutigen Inhalt von `App` ab `<div className="relative flex min-h-0 flex-1">` (Szene, Overlay, rechte Spalte); in `ViewportOverlay` alle `backdrop-blur-md` und `bg-ink-1/80` durch `bg-ink-1` ersetzen. `Shell.tsx`:

```tsx
export function Shell() {
  const { area, param } = useRoute();
  const page = (() => {
    switch (area) {
      case 'overview': return <OverviewArea />;
      case 'twin': return <TwinArea />;
      case 'events': return <EventsArea filterId={param} />;
      case 'settings': return <SettingsArea />;
      default: return <Placeholder area={areaById(area)} />;
    }
  })();
  // ... Layout wie beschrieben; OverviewArea, EventsArea, SettingsArea entstehen in Task 4–6,
  // bis dahin rendert Shell für diese drei ebenfalls <Placeholder> (Task 4–6 ersetzen je eine Zeile).
}
```

`App.tsx`: `return <Shell />` plus `useShortcuts()`; die Kürzel L, P, F, Escape und die Kameratasten gelten nur im Bereich FactoryTwin (`parseHash(location.hash).area === 'twin'`), Leertaste und 1–5 überall.

- [ ] **Step 4: Tests, Typprüfung, Build, Sichtprüfung**

Run: `cd FactoryTwin && npx vitest run && npx tsc -b --noEmit && npx vite build`
Expected: grün.

Sichtprüfung im Browser-Pane (`twin-browser` aus `.claude/launch.json`): `#/overview` zeigt den Platzhalter in der Shell, `#/twin` die Halle wie bisher, Navigation per Tab erreichbar, aktive Seite markiert; bei 390 px Breite Menü statt Spalte, kein seitliches Scrollen. Konsole ohne Fehler.

- [ ] **Step 5: Commit**

```bash
git add FactoryTwin/src
git commit -m "feat(ui): Shell mit Navigation über elf Bereiche und Simulationsleiste"
```

---

### Task 4: Overview

**Files:**
- Create: `FactoryTwin/src/areas/overview/figures.ts`, `FactoryTwin/src/areas/overview/OverviewArea.tsx`, `FactoryTwin/src/nexus/useKpis.ts`
- Modify: `FactoryTwin/src/shell/Shell.tsx` (Overview statt Platzhalter)
- Test: `FactoryTwin/src/areas/overview/figures.test.ts`

**Interfaces:**
- Consumes: `twin.latest` (Frame), `useFactory` (`scene`, `events`), `NexusClient.query` über einen neuen Store-Export `query<T>(q: Query): Promise<Answer<T>>`.
- Produces:
  - `interface Kpis { fromMs: number; toMs: number; throughputPerHour: number; avgLeadTimeS: number; p90LeadTimeS: number; onTimeRate: number; bottleneckMachineId: string | null; machines: Record<string, { utilization: number; availability: number; performance: number; quality: number; oee: number; timeInStatusMs: Record<string, number> }> }` (in `src/nexus/types.ts`)
  - `useKpis(windowMinutes = 60): Kpis | null` (fragt alle 2 s Echtzeit, solange die Komponente lebt; bei Ablehnung bleibt der letzte Wert)
  - `overviewFigures(frame: Frame | null, kpis: Kpis | null, events: NexusEvent[]): OverviewFigures` mit `{ throughput: string; oee: string; onTime: string; leadTime: string; openOrders: number; lateOrders: number; activeTransports: number; machines: { id: string; status: MachineStatus }[]; bottleneck: string | null; alerts: NexusEvent[] }` – Zahlen als fertig formatierte deutsche Zeichenketten, „–" wenn nicht definiert.

- [ ] **Step 1: Failing test schreiben**

`FactoryTwin/src/areas/overview/figures.test.ts`:

```ts
import { describe, expect, it } from 'vitest';
import * as core from '@nexus-core';
import { overviewFigures } from './figures';
import type { Frame, Kpis, NexusEvent } from '../../nexus/types';

const frameAfter = (minutes: number): Frame => {
  core.plantStart(7);
  core.plantAdvance(minutes * 60_000);
  return JSON.parse(core.plantFrame()) as Frame;
};
const kpisAfter = (): Kpis =>
  (JSON.parse(core.plantQuery('{"query":"kpis","windowMinutes":60}')) as { result: Kpis }).result;

describe('overviewFigures', () => {
  it('figuresWithoutFinishedOrdersShowADash', () => {
    const f = overviewFigures(frameAfter(1), kpisAfter(), []); // one minute: no order can be finished yet
    expect(f.throughput).toBe('0');
    expect(f.onTime).toBe('–');
    expect(f.leadTime).toBe('–');
    expect(f.oee).not.toContain('NaN');
  });

  it('counts open, late and moving work from the frame', () => {
    const frame = frameAfter(90);
    const f = overviewFigures(frame, kpisAfter(), []);
    expect(f.openOrders).toBe(frame.orders.filter((o) => !['COMPLETED', 'CANCELLED'].includes(o.status)).length);
    expect(f.lateOrders).toBe(frame.orders.filter((o) =>
      !['COMPLETED', 'CANCELLED'].includes(o.status) && o.dueAtMs < frame.simTimeMs).length);
    expect(f.activeTransports).toBe(frame.transports.length);
    expect(f.machines).toHaveLength(7);
  });

  it('lists the newest errors and warnings first, at most eight', () => {
    const ev = (seq: number, severity: NexusEvent['severity']): NexusEvent => ({
      seq, eventId: `1-${seq}`, simTimeMs: seq, type: 'machine.fault', source: 'smartfactory', severity,
      correlationId: null, payload: {} });
    const events = Array.from({ length: 20 }, (_, i) => ev(i + 1, i % 2 ? 'ERROR' : 'INFO'));
    const f = overviewFigures(null, null, events);
    expect(f.alerts.map((e) => e.seq)).toEqual([20, 18, 16, 14, 12, 10, 8, 6]);
  });
});
```

- [ ] **Step 2: Test laufen lassen, rot**

Run: `cd FactoryTwin && npx vitest run src/areas`
Expected: FAIL, `./figures` fehlt.

- [ ] **Step 3: Umsetzen**

`figures.ts`:

```ts
import type { Frame, Kpis, MachineStatus, NexusEvent } from '../../nexus/types';

const FINISHED = new Set(['COMPLETED', 'CANCELLED']);
const de = (v: number, digits = 0) =>
  v.toLocaleString('de-CH', { minimumFractionDigits: digits, maximumFractionDigits: digits });

export interface OverviewFigures {
  throughput: string;
  oee: string;
  onTime: string;
  leadTime: string;
  openOrders: number;
  lateOrders: number;
  activeTransports: number;
  machines: { id: string; status: MachineStatus }[];
  bottleneck: string | null;
  alerts: NexusEvent[];
}

/**
 * The overview's figures, formatted. A figure without a basis (no finished order yet) is "–", never NaN or a
 * flattering 100 %: the core reports onTimeRate 1 and lead time 0 when nothing was finished.
 */
export function overviewFigures(frame: Frame | null, kpis: Kpis | null, events: NexusEvent[]): OverviewFigures {
  const open = frame ? frame.orders.filter((o) => !FINISHED.has(o.status)) : [];
  const finishedInWindow = kpis ? kpis.throughputPerHour > 0 : false;
  const oees = kpis ? Object.values(kpis.machines).map((m) => m.oee).filter((v) => Number.isFinite(v)) : [];
  return {
    throughput: kpis ? de(kpis.throughputPerHour, kpis.throughputPerHour < 10 ? 1 : 0).replace(/,0$/, '') : '–',
    oee: oees.length ? `${de((oees.reduce((a, b) => a + b, 0) / oees.length) * 100)} %` : '–',
    onTime: kpis && finishedInWindow ? `${de(kpis.onTimeRate * 100)} %` : '–',
    leadTime: kpis && finishedInWindow ? `${de(kpis.avgLeadTimeS / 60)} min` : '–',
    openOrders: open.length,
    lateOrders: frame ? open.filter((o) => o.dueAtMs < frame.simTimeMs).length : 0,
    activeTransports: frame ? frame.transports.length : 0,
    machines: frame ? frame.machines.map((m) => ({ id: m.id, status: m.status })) : [],
    bottleneck: kpis?.bottleneckMachineId ?? null,
    alerts: events.filter((e) => e.severity === 'ERROR' || e.severity === 'WARNING').slice(-8).reverse(),
  };
}
```

Das `replace` macht aus „0,0" ein „0"; bei Werten unter 10 bleibt eine Nachkommastelle („2,5").

`useKpis.ts`:

```ts
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
      const a = await query<Kpis>({ query: 'kpis', windowMinutes });
      if (alive && a.ok) setKpis(a.result);
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
```

In `factoryStore.ts`: `export function query<T>(q: Query): Promise<Answer<T>> { return client().query<T>(q); }`.

`OverviewArea.tsx`: Seitentitel kommt aus der `SimBar`. Inhalt im 12-Spalten-Raster:
- Zeile 1 (vier `Stat` nebeneinander, unter 1024 px zwei pro Zeile): „Durchsatz" (`throughput`, Einheit „Aufträge/h", Hinweis „letzte 60 min"), „OEE" (`oee`, Hinweis „Mittel aller Maschinen"), „Termintreue" (`onTime`), „Durchlaufzeit" (`leadTime`, Hinweis „Mittel").
- Zeile 2, links 8 Spalten: `Section` „Maschinen" mit einer Zeile je Maschine (ID in Mono, `StateTag`, Link `#/twin` mit Auswahl: `useFactory.getState().select({ type: 'machine', id }, true)` vor dem Wechsel); darunter „Engpass: {bottleneck}" wenn gesetzt. Rechts 4 Spalten: `Section` „Aufträge und Transporte" mit drei Zahlen (`openOrders`, `lateOrders` in `--color-state-wait` wenn > 0, `activeTransports`).
- Zeile 3: `Section` „Meldungen" (Aktion: Link „Alle Ereignisse" → `#/events`), Liste der `alerts` mit Zeit, Schweregrad-Punkt und `eventText`; leer: „Keine Störungen oder Warnungen."
- Kein Diagramm in M2-B (Analytics in M2-C).

`Shell.tsx`: Fall `overview` rendert `<OverviewArea />`.

- [ ] **Step 4: Tests, Typprüfung, Sichtprüfung**

Run: `cd FactoryTwin && npx vitest run && npx tsc -b --noEmit`
Expected: PASS.

Sichtprüfung: Start, Tempo 25, eine Minute laufen lassen; die Kennzahlen füllen sich, Maschinenzeilen wechseln den Zustand, ein Klick auf eine Maschine öffnet FactoryTwin mit Auswahl. Vor dem Start stehen „–" statt `NaN`.

- [ ] **Step 5: Commit**

```bash
git add FactoryTwin/src
git commit -m "feat(ui): Overview mit Kennzahlen, Maschinenlage, Aufträgen und Meldungen"
```

---

### Task 5: Event Monitor

**Files:**
- Modify: `FactoryTwin/src/nexus/client.ts` (`events(filter)`), `FactoryTwin/src/nexus/server/ServerClient.ts`, `FactoryTwin/src/nexus/browser/engine.ts`, `FactoryTwin/src/nexus/browser/BrowserClient.ts`, `FactoryTwin/src/shell/Shell.tsx`
- Create: `FactoryTwin/src/nexus/eventFilter.ts`, `FactoryTwin/src/areas/events/EventsArea.tsx`
- Test: `FactoryTwin/src/nexus/eventFilter.test.ts`, Ergänzung in `FactoryTwin/src/nexus/browser/engine.test.ts`

**Interfaces:**
- Produces:
  - `interface EventFilter { type?: string; source?: string; severity?: NexusEvent['severity']; correlationId?: string; after?: number; limit?: number }`
  - `matches(e: NexusEvent, f: EventFilter): boolean` (`type` als Präfix: `machine.` trifft `machine.status` und `machine.fault`; `correlationId` exakt; `severity` exakt)
  - `filterEvents(all: NexusEvent[], f: EventFilter): { items: NexusEvent[]; more: boolean }` (`after` = nur `seq > after`, aufsteigend; höchstens `limit`, Standard 500, `more` wenn es mehr gäbe)
  - `NexusClient.events(f: EventFilter): Promise<{ items: NexusEvent[]; more: boolean }>`; Worker-Anfrage `{ kind: 'events'; filter: EventFilter }`; Server: `GET /api/v1/events?type&source&correlationId&after&limit` (Typ als Präfix und Schweregrad filtert der Client nach, weil der Server exakt filtert), `more` = `items.length === limit`.

- [ ] **Step 1: Failing tests schreiben**

`FactoryTwin/src/nexus/eventFilter.test.ts`:

```ts
import { describe, expect, it } from 'vitest';
import { filterEvents, matches } from './eventFilter';
import type { NexusEvent } from './types';

const ev = (seq: number, type: string, correlationId: string | null = null, severity: NexusEvent['severity'] = 'INFO'): NexusEvent => ({
  seq, eventId: `1-${seq}`, simTimeMs: seq * 100, type, source: type.split('.')[0], severity, correlationId, payload: {},
});

describe('event filter', () => {
  it('matches a type prefix, an order and a severity', () => {
    expect(matches(ev(1, 'machine.fault'), { type: 'machine.' })).toBe(true);
    expect(matches(ev(1, 'order.created'), { type: 'machine.' })).toBe(false);
    expect(matches(ev(1, 'order.created', 'PO-0004'), { correlationId: 'PO-0004' })).toBe(true);
    expect(matches(ev(1, 'order.created', 'PO-0004'), { correlationId: 'PO-00' })).toBe(false);
    expect(matches(ev(1, 'machine.fault', null, 'ERROR'), { severity: 'ERROR' })).toBe(true);
  });

  it('filterKeepsAtMostAPageAndSaysSo', () => {
    const all = Array.from({ length: 10_000 }, (_, i) => ev(i + 1, i % 2 ? 'machine.status' : 'order.created'));
    const first = filterEvents(all, { type: 'machine.' });
    expect(first.items).toHaveLength(500);
    expect(first.more).toBe(true);
    const next = filterEvents(all, { type: 'machine.', after: first.items.at(-1)!.seq });
    expect(next.items[0].seq).toBe(first.items.at(-1)!.seq + 2);
  });
});
```

In `engine.test.ts`:

```ts
  it('answers an event query with the run’s events, filtered', () => {
    const engine = new BrowserEngine(core, 7);
    engine.handle({ id: 1, kind: 'speed', speed: 25 });
    engine.handle({ id: 2, kind: 'play' });
    for (let i = 0; i < 200; i++) engine.tick(50);
    const { answer } = engine.handle({ id: 3, kind: 'events', filter: { type: 'order.' } });
    const page = answer as { items: { type: string }[]; more: boolean };
    expect(page.items.length).toBeGreaterThan(0);
    expect(page.items.every((e) => e.type.startsWith('order.'))).toBe(true);
  });
```

- [ ] **Step 2: Tests laufen lassen, rot**

Run: `cd FactoryTwin && npx vitest run src/nexus`
Expected: FAIL (`eventFilter` fehlt, `kind: 'events'` unbekannt).

- [ ] **Step 3: Umsetzen**

`eventFilter.ts`:

```ts
import type { NexusEvent } from './types';

export interface EventFilter {
  type?: string;
  source?: string;
  severity?: NexusEvent['severity'];
  correlationId?: string;
  after?: number;
  limit?: number;
}

export const PAGE = 500;

/** type is a prefix ("machine." covers machine.status and machine.fault); the others match exactly. */
export function matches(e: NexusEvent, f: EventFilter): boolean {
  return (!f.type || e.type.startsWith(f.type))
    && (!f.source || e.source === f.source)
    && (!f.severity || e.severity === f.severity)
    && (!f.correlationId || e.correlationId === f.correlationId);
}

/** At most one page of matching events after f.after, oldest first; more says whether another page follows. */
export function filterEvents(all: NexusEvent[], f: EventFilter): { items: NexusEvent[]; more: boolean } {
  const limit = f.limit ?? PAGE;
  const items: NexusEvent[] = [];
  for (const e of all) {
    if (f.after !== undefined && e.seq <= f.after) continue;
    if (!matches(e, f)) continue;
    if (items.length === limit) return { items, more: true };
    items.push(e);
  }
  return { items, more: false };
}
```

`engine.ts`: `WorkerRequest` um `{ id: number; kind: 'events'; filter: EventFilter }` erweitern; in `handle`:

```ts
      case 'events':
        return { answer: filterEvents(JSON.parse(this.core.plantEventsFrom(0)) as NexusEvent[], r.filter), messages: [] };
```

`BrowserClient`: `events(f) { return this.request({ kind: 'events', filter: f }) as Promise<{ items: NexusEvent[]; more: boolean }>; }`.

`ServerClient`:

```ts
  async events(f: EventFilter): Promise<{ items: NexusEvent[]; more: boolean }> {
    const limit = f.limit ?? PAGE_EVENTS;
    const params = new URLSearchParams({ after: String(f.after ?? 0), limit: String(limit) });
    if (f.source) params.set('source', f.source);
    if (f.correlationId) params.set('correlationId', f.correlationId);
    if (f.type && !f.type.endsWith('.')) params.set('type', f.type);       // the server filters types exactly
    const r = await this.deps.fetch(`${this.base}/api/v1/events?${params}`, { headers: { Accept: 'application/json' } });
    if (!r.ok) throw new Error(`Ereignisse nicht lesbar (${r.status})`);
    const page = (await r.json()) as NexusEvent[];
    return { items: page.filter((e) => matches(e, f)), more: page.length === limit };
  }
```

(`PAGE_EVENTS = 500`; Import `matches`, `type EventFilter` aus `../eventFilter`.)

`EventsArea.tsx` (`filterId` = Routenparameter, vorbelegt als Auftrags-ID):
- Filterzeile: Eingabe „Auftrag" (`correlationId`, aus `filterId`), Auswahl „Typ" (Alle, `order.`, `operation.`, `machine.`, `transport.`, `agv.`, `stock.`, `material.`, `opcua.`, `decision`), Auswahl „Schweregrad" (Alle, Fehler, Warnung, Erfolg, Info), Umschalter „Live folgen".
- „Live folgen" an: Tabelle zeigt `useFactory(s => s.events)` gefiltert mit `matches`, neueste oben.
- „Live folgen" aus oder ein Filter gesetzt: Tabelle zeigt `client.events(filter)`-Seiten aufsteigend; unten „Weitere laden" solange `more` (nächste Seite mit `after` = letzte `seq`); oben die Anzahl („500 Ereignisse, weitere vorhanden").
- `DataTable` mit Spalten Zeit (`clock(simTimeMs/1000)`, Mono), Schweregrad (Punkt + Wort), Typ (Mono), Beschreibung (`eventText`), Auftrag (Mono, Klick setzt den Filter: `location.hash = hrefOf('events', id)`).
- Leer: „Keine Ereignisse für diesen Filter."

`Shell.tsx`: Fall `events` rendert `<EventsArea filterId={param} />`. Die Ereignis-Liste im FactoryTwin-Bereich bekommt oben den Link „Im Event Monitor öffnen".

- [ ] **Step 4: Tests, Typprüfung, Sichtprüfung**

Run: `cd FactoryTwin && npx vitest run && npx tsc -b --noEmit`
Expected: PASS.

Sichtprüfung in beiden Betriebsarten: Filter „machine.", dann Auftrags-ID aus einer Zeile anklicken → Hash `#/events/PO-…`, Tabelle zeigt nur diesen Auftrag; „Weitere laden" erscheint bei mehr als 500 Treffern.

- [ ] **Step 5: Commit**

```bash
git add FactoryTwin/src
git commit -m "feat(ui): Event Monitor mit Filtern, Seiten und Live-Ansicht"
```

---

### Task 6: Settings

**Files:**
- Create: `FactoryTwin/src/areas/settings/rulesForm.ts`, `FactoryTwin/src/areas/settings/SettingsArea.tsx`
- Modify: `FactoryTwin/src/nexus/types.ts` (`Rules`), `FactoryTwin/src/shell/Shell.tsx`
- Test: `FactoryTwin/src/areas/settings/rulesForm.test.ts`

**Interfaces:**
- Produces:
  - `interface Rules { randomFaults: boolean; autoRepair: boolean; autoMaintenance: boolean; maintenanceThreshold: number; autoOrders: boolean; autoOrderIntervalMinutes: number; maxOpenOrders: number; shipping: boolean; dispatchRule: 'PRIORITY' | 'EDD' | 'FIFO' | 'SPT' }` (genau die Felder der Abfrage `rules`)
  - `rulesPatch(current: Rules, edited: Rules): Command | null` (nur geänderte Felder, `{ command: 'setRules', …}`; `null` wenn nichts geändert)
  - `async applyRules(current: Rules, edited: Rules, send: (c: Command) => Promise<boolean>, reload: () => Promise<Rules | null>): Promise<Rules>` (sendet den Patch; gibt danach immer den Stand zurück, den der Kern meldet)

- [ ] **Step 1: Failing test schreiben**

`FactoryTwin/src/areas/settings/rulesForm.test.ts`:

```ts
import { describe, expect, it } from 'vitest';
import { applyRules, rulesPatch } from './rulesForm';
import type { Rules } from '../../nexus/types';

const base: Rules = {
  randomFaults: true, autoRepair: true, autoMaintenance: true, maintenanceThreshold: 0.85, autoOrders: true,
  autoOrderIntervalMinutes: 6, maxOpenOrders: 6, shipping: true, dispatchRule: 'PRIORITY',
};

describe('rules form', () => {
  it('sends only what changed', () => {
    expect(rulesPatch(base, { ...base })).toBeNull();
    expect(rulesPatch(base, { ...base, dispatchRule: 'EDD', maxOpenOrders: 8 }))
      .toEqual({ command: 'setRules', dispatchRule: 'EDD', maxOpenOrders: 8 });
  });

  it('aRefusedRuleKeepsTheFormOnTheValidValues', async () => {
    const sent: unknown[] = [];
    const result = await applyRules(
      base,
      { ...base, maintenanceThreshold: 1.5 },
      async (c) => { sent.push(c); return false; },       // the core refuses
      async () => base,                                     // and still reports the old rules
    );
    expect(sent).toEqual([{ command: 'setRules', maintenanceThreshold: 1.5 }]);
    expect(result).toEqual(base);
  });
});
```

- [ ] **Step 2: Test laufen lassen, rot**

Run: `cd FactoryTwin && npx vitest run src/areas/settings`
Expected: FAIL, `./rulesForm` fehlt.

- [ ] **Step 3: Umsetzen**

`rulesForm.ts`:

```ts
import type { Command, Rules } from '../../nexus/types';

/** The setRules command with only the changed fields, or null when nothing changed. */
export function rulesPatch(current: Rules, edited: Rules): Command | null {
  const changed = (Object.keys(edited) as (keyof Rules)[]).filter((k) => edited[k] !== current[k]);
  if (changed.length === 0) return null;
  return { command: 'setRules', ...Object.fromEntries(changed.map((k) => [k, edited[k]])) };
}

/**
 * Sends the change and returns the rules the core then reports. If the core refuses (send shows its reason), the
 * form falls back to the valid values instead of keeping one the plant does not run with.
 */
export async function applyRules(
  current: Rules,
  edited: Rules,
  send: (c: Command) => Promise<boolean>,
  reload: () => Promise<Rules | null>,
): Promise<Rules> {
  const patch = rulesPatch(current, edited);
  if (patch) await send(patch);
  return (await reload()) ?? current;
}
```

`SettingsArea.tsx` – drei Abschnitte untereinander, Formular links 8 Spalten, Erläuterung rechts 4 Spalten:
- „Regeln der Simulation": Zufallsstörungen, automatische Reparatur, automatische Wartung (Schalter), Wartungsschwelle (Schieber 50–100 % in 5er-Schritten, zeigt `maintenanceThreshold`), automatische Aufträge (Schalter), Abstand der Aufträge (Zahl 1–120 min), höchstens offene Aufträge (Zahl 1–30), Versand (Schalter), Dispositionsregel (`Segmented` PRIORITY/EDD/FIFO/SPT mit deutschen Titeln „Priorität", „Frühester Termin", „Reihenfolge", „Kürzeste Bearbeitung"). Knöpfe „Übernehmen" (aktiv nur bei Änderung; `applyRules(current, edited, send, loadRules)`) und „Verwerfen". Laden beim Öffnen über `query<Rules>({ query: 'rules' })`.
- „Simulation": Tempo (`Segmented` 0,25/0,5/1/2/5/10/25 → `control.setSpeed`), Seed-Feld mit „Neu starten mit diesem Seed" (Bestätigung wie beim Reset → `control.reset(seed)`), aktuelle Betriebsart und Verbindung als Text („Browser – Java-Kern im Web Worker" / „Server – PostgreSQL und OPC UA").
- „Darstellung": Grafikqualität (Hoch/Mittel/Schnell), Beschriftungen, Fahrwege (die bisherigen Store-Schalter).

`Shell.tsx`: Fall `settings` rendert `<SettingsArea />`.

- [ ] **Step 4: Tests, Typprüfung, Sichtprüfung**

Run: `cd FactoryTwin && npx vitest run && npx tsc -b --noEmit`
Expected: PASS.

Sichtprüfung: Dispositionsregel EDD übernehmen → bleibt nach Seitenwechsel EDD (aus dem Kern gelesen); Abstand 0 eintippen → Feld begrenzt auf 1, oder der Kern lehnt ab und die rote Meldungszeile erscheint, das Formular zeigt wieder den gültigen Wert; „Neu starten mit Seed 9" → Uhr steht auf 06:00, Seed 9 unten in der Navigation.

- [ ] **Step 5: Commit**

```bash
git add FactoryTwin/src
git commit -m "feat(ui): Settings mit Regeln, Tempo, Seed und Darstellung"
```

---

### Task 7: Feinschliff, Barrierefreiheit, beide Betriebsarten

**Files:**
- Modify: Dateien aus Task 3–6 nach den Befunden; `FactoryTwin/README.md` (Abschnitt „Bereiche")
- Test: bestehende Suiten

- [ ] **Step 1: Design-Prüfung** mit dem Skill `impeccable` (Kritik der Shell und der drei fertigen Bereiche gegen die Designrichtung oben: Raster, Hierarchie, Farbe nur für Zustände, kein Glas, keine Karten in Karten). Befunde, die der Spec 14 widersprechen, beheben; Geschmacksfragen ohne Spec-Bezug im Ledger als „minor (deferred)".

- [ ] **Step 2: Barrierefreiheit:** im Browser-Pane `axe-core` (bereits Dev-Abhängigkeit) auf `#/overview`, `#/events`, `#/settings` ausführen (`javascript_tool`: `await import('/node_modules/axe-core/axe.min.js'); (await axe.run()).violations`); Verstösse der Stufen `serious` und `critical` beheben.

- [ ] **Step 3: Sichtprüfung beider Betriebsarten** bei 1440 × 900, 1024 × 768 und 390 × 844 (`resize_window`): Navigation, alle elf Einträge, die vier fertigen Bereiche, Platzhalter mit Verweis; Server-Modus zusätzlich Verbindungsanzeige unten in der Navigation. Ergebnisse mit Breite und Bereich im Ledger.

- [ ] **Step 4: README** – Abschnitt „Bereiche" mit der Tabelle der elf Bereiche und ihrem Stand (fertig / kommt mit M2-C, M3, M4).

- [ ] **Step 5: Ganze Prüfung und Commit**

Run: `cd FactoryTwin && npx vitest run && npx tsc -b --noEmit && npx vite build` und `./mvnw -B verify` (Exitcode von Maven prüfen, nicht den eines folgenden Befehls)
Expected: alles grün.

```bash
git add FactoryTwin
git commit -m "feat(ui): Feinschliff der Shell, Barrierefreiheit und README"
```

---

## Nicht in diesem Plan

- **Bereichsinhalte SmartFactory, FlowPilot, Machines, Warehouse, Analytics:** M2-C (dort auch Diagramme, Auftragsanlage, Wareneingang).
- **Scenario Lab:** M3. **RouteMind:** M4.
- **Messung 60 Bilder pro Sekunde bei 25-fachem Tempo:** M3 mit Playwright.
- **Helles Thema:** Spec 14 sieht nur das dunkle vor.

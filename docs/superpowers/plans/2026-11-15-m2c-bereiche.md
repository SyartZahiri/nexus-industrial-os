# M2-C Bereiche SmartFactory, FlowPilot, Machines, Warehouse, Analytics – Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Die fünf Bereiche, die in M2-B noch Platzhalter sind, werden fertig: SmartFactory (Aufträge, Plan, Auftragsanlage), FlowPilot (Flotte, Transporte, Wegenetz mit Sperren), Machines (alle Maschinen mit Sensoren und Befehlen), Warehouse (Bestände je Lagerplatz, Wareneingang) und Analytics (Kennzahlen, OEE, Zeit je Zustand, Verlauf als SVG-Charts), in beiden Betriebsarten.

**Architecture:** Was sich laufend ändert (Maschinen, AGVs, Bestände, offene Aufträge und Transporte), liest die Oberfläche wie bisher aus Frame und Szene. Was der Frame nicht trägt (Stammdaten, Plan, Auftragsdetails, Wegenetz, Kennzahlverlauf), beantwortet der Java-Kern über neue Abfragen in `PlantQueries`, damit Browser und Server dieselbe Antwort geben. Ein Hook `usePlantQuery` fragt sie ab, je nach Bedarf einmal je Lauf oder in festem Takt. Diagramme sind kleine SVG-Bausteine im Kit; ihre Rechnung (Skalen, Anteile, Lagen) steckt in reinen, getesteten Modulen. Befehle laufen über das bestehende `send` (M2-A), der Kern prüft sie.

**Tech Stack:** Java 25 (Kern, TeaVM), React 19, TypeScript, Vite, Tailwind 4, Zustand, Vitest; `NexusClient` aus M2-A, Shell und Kit aus M2-B.

**Spec:** `docs/superpowers/specs/2026-11-10-nexus-industrial-os-design.md` (Revision 3), Abschnitt 5 (eine Datenquelle pro Objekt), 8 (Zustände), 9 (FlowPilot), 10 (SmartFactory, Analysen), 12 (Endpunkte), 14 (Frontend, Designsystem, SVG-Charts), 18 (M2: alle Bereiche ausser RouteMind). Voraussetzung: M2-B ist gemergt (`89fd905`).

**Form:** wie M2-B: vollständiger Code für Kern-Abfragen, Logik und Bausteine, vollständiges JSX für die Bereichsseiten.

## Entscheidungen dieses Plans

- **Neue Abfragen im Kern statt neuer REST-Wege.** `products`, `schedule`, `order`, `warehouse`, `network`, `kpiSeries` kommen in `PlantQueries`. Der Server leitet `POST /api/v1/sim/queries` schon dorthin (M2-A), der Browser über den Worker. Die bestehenden REST-Controller bleiben für externe Clients; die Oberfläche nutzt sie nicht, sonst wäre der Browser-Modus anders.
- **Kein `orders`- oder `machines`-Abruf.** Der Frame trägt offene und die 20 zuletzt fertigen Aufträge, die Szene trägt Maschinen mit Name, Modell, Fähigkeiten, Sensoren und Verschleiss. Das reicht für die Tabellen (YAGNI). Details eines einzelnen Auftrags kommen über `order`.
- **`usePlantQuery<T>(q, everyMs)`.** Fragt sofort, dann alle `everyMs` ms. Bei `everyMs = 0` (Stammdaten, Wegenetz) fragt er alle 2 s, bis eine Antwort kommt, und hört dann auf. Ein Neustart (`run` aus dem Store, M2-B) fragt neu. Eine Ablehnung behält die letzten Daten und liefert den Grund als `error`. `reload()` fragt sofort neu, etwa nach dem Sperren einer Kante. `useKpis` wird zu einer Zeile darüber.
- **Farbe nur für Zustände, auch in Diagrammen.** Balken für OEE, Auslastung und Durchsatz sind neutral (`txt-2` auf `ink-3`). Farbig sind nur Zustandsanteile (STATE_HEX), verspätete Aufträge im Plan (`wait`) und gesperrte Kanten (`fault`).
- **Wegenetz als SVG-Karte mit zugänglicher Auswahl.** Die Karte zeigt Knoten, Kanten (gesperrt rot), Ladestationen und AGVs (Szene, je Frame). Ein Klick auf eine Kante wählt sie aus; dieselbe Auswahl gibt es als `<select>`, damit Tastatur und Screenreader nicht ~100 Kanten durchtabben müssen. Sperren und Freigeben über `edgeBlock` und `edgeUnblock`.
- **Machines nutzt die vorhandene Detailansicht.** `MachineDetails` und `AgvDetails` aus `DetailPanel.tsx` werden exportiert und in Machines bzw. FlowPilot neben der Tabelle gezeigt. Es gibt keinen zweiten Satz Bedienelemente.
- **Ein Bereich mit Parameter zeigt die Auswahl:** `#/machines/CNC-02`, `#/smartfactory/PO-0004`, `#/flowpilot/AGV-03`. Eine unbekannte ID zeigt einen klaren Satz und die Liste, nie eine leere Seite.
- **Gantt im Bereich SmartFactory,** je Maschine eine Zeile, Zeitachse vom früheren Wert aus „jetzt“ und erstem geplantem Start bis zum letzten Ende (mindestens eine Stunde). Rüstanteil heller, verspätete Aufträge mit Rand in `wait`, eine Linie für „jetzt“.
- **Analytics-Fenster 1 h, 8 h, 24 h,** Verlauf in 12 Abschnitten. Abschnitte vor dem Laufbeginn liefert der Kern nicht. Die Oberfläche lässt ihre Plätze leer, statt Nullen zu zeichnen.
- **Overview verlinkt auf Machines.** Ein Klick auf eine Maschine in der Overview öffnet jetzt `#/machines/<id>` statt FactoryTwin.

## Global Constraints

- Alles aus M2-A und M2-B gilt weiter: keine Geschäftsregeln im Frontend (der Kern prüft jeden Befehl), TypeScript strikt, keine neuen Laufzeitabhängigkeiten, Vitest, Texte auf Deutsch, Code und Kommentare auf Englisch.
- Kern: kein Reflection-JSON (TeaVM), JSON nur über `JsonWriter`; keine Streams in `PlantQueries` (einfache Schleifen wie im bestehenden Code); Abfragen verändern nichts.
- Spec 5: eine Datenquelle pro Objekt. Produkte und Pläne von SmartFactory, Material, Lager und Wegenetz von FlowPilot. Die Oberfläche liest sie über Abfragen, nicht aus `twin/config.ts`.
- Spec 10: Analysen werden „aus `event` und `machine_state_interval` berechnet, nicht aus Zählern im Frontend“. Die Oberfläche formatiert nur.
- Spec 14: „Tabellen, Statusanzeigen, Maschinenkarten und SVG-Charts als gemeinsame Komponenten“. Farbe nur für Zustände, keine Verläufe, kein Glas, einspaltig unter 1024 px.
- Typskala 11/12/13/15/20/32, Haarlinien statt Karten in Karten, Akzent nur für Fokus und Aktiv (M2-B).
- Barrierefreiheit: jede Aktion per Tastatur, Tabellen mit `th scope="col"` und `aria-sort` (DataTable), Diagramme mit Textalternative (`role="img"` und `aria-label` mit den Werten oder eine Tabelle daneben), Zustandsfarbe nie allein.
- Nach jeder Änderung am Java-Kern `npm run core` in `FactoryTwin`, damit der Browser-Modus den neuen Kern hat.
- Commits enden mit `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`. Nicht pushen.

## Review Focus

1. **Ein Auftrag, den der Kern ablehnt** (Menge 0, Termin in der Vergangenheit, unbekanntes Produkt). Erwartet: die Meldungszeile nennt den Grund, das Formular behält, was der Nutzer eingegeben hat. Test in Task 5 (`aRefusedOrderKeepsWhatTheUserTyped`).
2. **Eine unbekannte ID im Link** (`#/machines/CNC-99`, `#/smartfactory/PO-9999`). Erwartet: ein Satz „gibt es nicht“ und die Liste, keine leere Seite und kein Absturz. Tests in Task 1 (`anUnknownOrderIsNotFound`) und Task 3 (`anUnknownMachineIdIsSaidAndTheListStays`).
3. **Ein leerer Plan oder Arbeitsgänge ohne Dauer** (direkt nach dem Start, alle Aufträge fertig). Erwartet: das Gantt zeigt die Maschinenzeilen und die Zeitachse, kein `NaN`, keine Balken mit Breite 0. Test in Task 5 (`ganttHandlesAnEmptyPlanAndZeroLengthOps`).
4. **Ein Analysefenster, das länger ist als der Lauf** (24 h nach 25 min). Erwartet: kein Abschnitt vor dem Laufbeginn, leere Plätze statt Nullen, kein `NaN`. Tests in Task 1 (`seriesNeverStartsBeforeTheRun`) und Task 7 (`seriesWithoutDataLeavesGaps`).
5. **Ein Wareneingang über die Lagerkapazität.** Erwartet: das Formular zeigt den freien Platz des gewählten Lagerplatzes, und lehnt der Kern ab, steht der Grund in der Meldungszeile. Test in Task 4 (`receiptRoomIsTheFreeCapacity`).

---

### Task 1: Abfragen im Kern

**Files:**
- Modify: `platform/plant/src/main/java/io/nexus/plant/PlantQueries.java`
- Test: `platform/plant/src/test/java/io/nexus/plant/PlantQueriesTest.java`
- Modify: `FactoryTwin/src/nexus/types.ts` (Antworttypen)
- Test: `FactoryTwin/src/nexus/browser/engine.test.ts` (eine Abfrage über den TeaVM-Kern)

**Interfaces:**
- Produces (JSON, in beiden Betriebsarten über `query`):
  - `{"query":"products"}` → `ProductInfo[]`
  - `{"query":"schedule"}` → `Schedule`
  - `{"query":"order","orderId":"PO-0001"}` → `OrderDetail`; unbekannte ID → `{ok:false,status:404}`
  - `{"query":"warehouse"}` → `WarehouseInfo`
  - `{"query":"network"}` → `Network`
  - `{"query":"kpiSeries","bucketMinutes":10,"buckets":12}` → `KpiBucket[]` (älteste zuerst, nur Abschnitte mit `toMs > 0`, `fromMs` nie unter 0; `bucketMinutes` 1–240, `buckets` 1–48, sonst 400)
- TypeScript in `src/nexus/types.ts`:

```ts
export type Priority = 'NORMAL' | 'HOCH' | 'EILIG';

export interface ProductInfo {
  id: string;
  name: string;
  shortName: string;
  finishedMaterialId: string;
  routing: { seq: number; process: string; baseTimeS: number; preferredMachineId: string }[];
  bom: { seq: number; materialId: string; quantity: number }[];
}

export interface PlannedOp {
  orderId: string;
  seq: number;
  machineId: string;
  startMs: number;
  endMs: number;
  setupMs: number;
}

export interface Schedule {
  rule: Rules['dispatchRule'];
  ops: PlannedOp[];
  unplannable: { orderId: string; seq: number; reason: string }[];
  lateOrders: string[];
  utilization: Record<string, number>;
  bottleneckMachineId: string | null;
}

export interface OrderDetail {
  order: {
    id: string;
    productId: string;
    quantity: number;
    priority: Priority;
    status: string;
    dueAtMs: number;
    createdAtMs: number;
    completedAtMs: number | null;
    good: number;
    scrapped: number;
  };
  operations: {
    seq: number;
    process: string;
    machineId: string | null;
    status: string;
    plannedStartMs: number | null;
    plannedEndMs: number | null;
    startedAtMs: number | null;
    endedAtMs: number | null;
    good: number;
    scrap: number;
  }[];
  materials: {
    stepSeq: number;
    kind: 'SUPPLY' | 'WIP' | 'PUTAWAY';
    materialId: string;
    quantity: number;
    transportId: string | null;
    delivered: boolean;
  }[];
}

export interface WarehouseInfo {
  materials: { id: string; name: string; kind: string; reorderPoint: number; reorderQty: number }[];
  locations: { id: string; name: string; zone: string; capacity: number }[];
}

export interface NetworkNode {
  id: string;
  x: number;
  z: number;
  dock: boolean;
}

export interface NetworkEdge {
  id: string;
  from: string;
  to: string;
  length: number;
  blocked: boolean;
}

export interface Network {
  nodes: NetworkNode[];
  edges: NetworkEdge[];
}

/** One section of the key-figure history; oee is the mean over the machines, null without any machine time. */
export interface KpiBucket {
  fromMs: number;
  toMs: number;
  throughputPerHour: number;
  onTimeRate: number;
  avgLeadTimeS: number;
  oee: number | null;
}
```

- [ ] **Step 1: Failing tests schreiben**

In `PlantQueriesTest.java` (bestehende Hilfsmethode `answer` nutzen):

```java
  @Test
  @SuppressWarnings("unchecked")
  void productsCarryRoutingAndBillOfMaterial() {
    Plant plant = Plant.inMemory(7, PlantRules.DEFAULT);
    var a = answer(plant, "{\"query\":\"products\"}");
    assertThat(a).containsEntry("ok", true);
    var products = (java.util.List<Map<String, Object>>) a.get("result");
    assertThat(products).extracting(p -> p.get("id")).contains("GH200", "AW50", "FP12", "MT7");
    var gh = products.stream().filter(p -> p.get("id").equals("GH200")).findFirst().orElseThrow();
    assertThat((java.util.List<?>) gh.get("routing")).isNotEmpty();
    assertThat(gh).containsKeys("name", "shortName", "finishedMaterialId", "bom");
  }

  @Test
  @SuppressWarnings("unchecked")
  void scheduleNamesRuleOpsAndBottleneck() {
    Plant plant = Plant.inMemory(7, PlantRules.DEFAULT);
    plant.advance(20 * 60_000);
    var result = (Map<String, Object>) answer(plant, "{\"query\":\"schedule\"}").get("result");
    assertThat(result).containsKeys("rule", "ops", "unplannable", "lateOrders", "utilization", "bottleneckMachineId");
    assertThat(result).containsEntry("rule", "PRIORITY");
  }

  @Test
  @SuppressWarnings("unchecked")
  void anOrderComesWithItsOperationsAndMaterials() {
    Plant plant = Plant.inMemory(7, PlantRules.DEFAULT);
    plant.advance(20 * 60_000);
    String id = plant.smartFactory().orders().get(0).id();
    var result = (Map<String, Object>) answer(plant, "{\"query\":\"order\",\"orderId\":\"" + id + "\"}").get("result");
    assertThat((Map<String, Object>) result.get("order")).containsEntry("id", id);
    assertThat((java.util.List<?>) result.get("operations")).isNotEmpty();
    assertThat(result).containsKey("materials");
  }

  @Test
  void anUnknownOrderIsNotFound() {
    Plant plant = Plant.inMemory(7, PlantRules.DEFAULT);
    assertThat(answer(plant, "{\"query\":\"order\",\"orderId\":\"PO-9999\"}")).containsEntry("ok", false)
        .containsEntry("status", 404L);
  }

  @Test
  @SuppressWarnings("unchecked")
  void warehouseListsMaterialsAndLocations() {
    Plant plant = Plant.inMemory(7, PlantRules.DEFAULT);
    var result = (Map<String, Object>) answer(plant, "{\"query\":\"warehouse\"}").get("result");
    assertThat((java.util.List<?>) result.get("materials")).isNotEmpty();
    assertThat((java.util.List<Map<String, Object>>) result.get("locations")).allSatisfy(l ->
        assertThat(l).containsKeys("id", "name", "zone", "capacity"));
  }

  @Test
  @SuppressWarnings("unchecked")
  void networkShowsABlockedEdge() {
    Plant plant = Plant.inMemory(7, PlantRules.DEFAULT);
    var edges = (java.util.List<Map<String, Object>>) ((Map<String, Object>) answer(plant,
        "{\"query\":\"network\"}").get("result")).get("edges");
    String first = (String) edges.get(0).get("id");
    PlantCommands.apply(plant, "{\"command\":\"edgeBlock\",\"edgeId\":\"" + first + "\"}");
    var after = (java.util.List<Map<String, Object>>) ((Map<String, Object>) answer(plant,
        "{\"query\":\"network\"}").get("result")).get("edges");
    assertThat(after.get(0)).containsEntry("id", first).containsEntry("blocked", true);
    assertThat(after.get(1)).containsEntry("blocked", false);
  }

  @Test
  @SuppressWarnings("unchecked")
  void seriesNeverStartsBeforeTheRun() {
    Plant plant = Plant.inMemory(7, PlantRules.DEFAULT);
    plant.advance(25 * 60_000);
    var buckets = (java.util.List<Map<String, Object>>) answer(plant,
        "{\"query\":\"kpiSeries\",\"bucketMinutes\":10,\"buckets\":12}").get("result");
    assertThat(buckets).hasSize(3);
    assertThat(buckets.get(0)).containsEntry("fromMs", 0L).containsEntry("toMs", 300_000L);
    assertThat(buckets.get(2)).containsEntry("fromMs", 900_000L).containsEntry("toMs", 1_500_000L);
    assertThat(answer(plant, "{\"query\":\"kpiSeries\",\"bucketMinutes\":0}")).containsEntry("status", 400L);
  }
```

`PlantCommands.apply(Plant, String)` ist der bestehende öffentliche Einstieg für Befehle.

In `FactoryTwin/src/nexus/browser/engine.test.ts`:

```ts
  it('answers the new queries from the TeaVM core', () => {
    const engine = new BrowserEngine(core, 7);
    const ask = (body: object) => engine.handle({ id: 1, kind: 'query', body: body as never }).answer as { ok: boolean };
    expect(ask({ query: 'products' }).ok).toBe(true);
    expect(ask({ query: 'schedule' }).ok).toBe(true);
    expect(ask({ query: 'warehouse' }).ok).toBe(true);
    expect(ask({ query: 'network' }).ok).toBe(true);
    expect(ask({ query: 'kpiSeries', bucketMinutes: 10, buckets: 6 }).ok).toBe(true);
  });
```

- [ ] **Step 2: Tests laufen lassen, rot**

Run: `./mvnw -B -q -pl platform/plant -am test -Dtest=PlantQueriesTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: FAIL mit „Unbekannte Abfrage“ (`ok:false`, Status 400) für die neuen Abfragen.

- [ ] **Step 3: Umsetzen**

In `PlantQueries.apply` den `switch` erweitern:

```java
        case "kpis" -> kpis(plant, q);
        case "rules" -> rules(plant.rules());
        case "products" -> products();
        case "schedule" -> schedule(plant);
        case "order" -> order(plant, PlantCommands.text(q, "orderId"));
        case "warehouse" -> warehouse();
        case "network" -> network(plant);
        case "kpiSeries" -> kpiSeries(plant, q);
```

Imports ergänzen:

```java
import io.nexus.flowpilot.master.FlowPilotMasterData;
import io.nexus.smartfactory.analytics.Kpis;
import io.nexus.smartfactory.master.SmartFactoryMasterData;
import io.nexus.smartfactory.planning.Plan;
import io.nexus.smartfactory.store.Operation;
import io.nexus.smartfactory.store.OperationMaterial;
import io.nexus.smartfactory.store.ProductionOrder;
```

Neue Methoden (Schleifen, kein Stream, `Long`-Felder über `nullable`):

```java
  private static JsonWriter nullable(JsonWriter w, Long v) {
    return v == null ? w.nullValue() : w.value((long) v);
  }

  private static String products() {
    var w = new JsonWriter().beginArray();
    for (var p : SmartFactoryMasterData.products()) {
      w.beginObject().name("id").value(p.id()).name("name").value(p.name()).name("shortName").value(p.shortName())
          .name("finishedMaterialId").value(p.finishedMaterialId()).name("routing").beginArray();
      for (var s : SmartFactoryMasterData.routing()) {
        if (!s.productId().equals(p.id())) continue;
        w.beginObject().name("seq").value(s.seq()).name("process").value(s.process().name())
            .name("baseTimeS").value(s.baseTimeS()).name("preferredMachineId").value(s.preferredMachineId()).endObject();
      }
      w.endArray().name("bom").beginArray();
      for (var b : SmartFactoryMasterData.bom()) {
        if (!b.productId().equals(p.id())) continue;
        w.beginObject().name("seq").value(b.seq()).name("materialId").value(b.materialId())
            .name("quantity").value(b.quantity()).endObject();
      }
      w.endArray().endObject();
    }
    return w.endArray().toString();
  }

  private static String schedule(Plant plant) {
    var sf = plant.smartFactory();
    Plan p = sf.plan();
    var w = new JsonWriter().beginObject().name("rule").value(sf.dispatchRule().name()).name("ops").beginArray();
    for (Plan.PlannedOp op : p.ops()) {
      w.beginObject().name("orderId").value(op.orderId()).name("seq").value(op.seq()).name("machineId").value(op.machineId())
          .name("startMs").value(op.startMs()).name("endMs").value(op.endMs()).name("setupMs").value(op.setupMs()).endObject();
    }
    w.endArray().name("unplannable").beginArray();
    for (Plan.Unplannable u : p.unplannable()) {
      w.beginObject().name("orderId").value(u.orderId()).name("seq").value(u.seq()).name("reason").value(u.reason()).endObject();
    }
    w.endArray().name("lateOrders").beginArray();
    for (String id : p.lateOrders()) w.value(id);
    w.endArray().name("utilization").beginObject();
    for (var e : p.utilization().entrySet()) w.name(e.getKey()).value((double) e.getValue());
    return w.endObject().name("bottleneckMachineId").value(p.bottleneckMachineId()).endObject().toString();
  }

  private static String order(Plant plant, String orderId) {
    var sf = plant.smartFactory();
    ProductionOrder o = sf.order(orderId); // NOT_FOUND → 404
    var w = new JsonWriter().beginObject().name("order").beginObject().name("id").value(o.id())
        .name("productId").value(o.productId()).name("quantity").value(o.quantity())
        .name("priority").value(o.priority().name()).name("status").value(o.status().name())
        .name("dueAtMs").value(o.dueAtMs()).name("createdAtMs").value(o.createdAtMs()).name("completedAtMs");
    nullable(w, o.completedAtMs()).name("good").value(o.good()).name("scrapped").value(o.scrapped()).endObject()
        .name("operations").beginArray();
    for (Operation op : sf.operations(orderId)) {
      w.beginObject().name("seq").value(op.seq()).name("process").value(op.process().name())
          .name("machineId").value(op.machineId()).name("status").value(op.status().name()).name("plannedStartMs");
      nullable(w, op.plannedStartMs()).name("plannedEndMs");
      nullable(w, op.plannedEndMs()).name("startedAtMs");
      nullable(w, op.startedAtMs()).name("endedAtMs");
      nullable(w, op.endedAtMs()).name("good").value(op.good()).name("scrap").value(op.scrap()).endObject();
    }
    w.endArray().name("materials").beginArray();
    for (OperationMaterial m : sf.materials(orderId)) {
      w.beginObject().name("stepSeq").value(m.stepSeq()).name("kind").value(m.kind().name())
          .name("materialId").value(m.materialId()).name("quantity").value(m.quantity())
          .name("transportId").value(m.transportId()).name("delivered").value(m.delivered()).endObject();
    }
    return w.endArray().endObject().toString();
  }

  private static String warehouse() {
    var w = new JsonWriter().beginObject().name("materials").beginArray();
    for (var m : FlowPilotMasterData.materials()) {
      w.beginObject().name("id").value(m.id()).name("name").value(m.name()).name("kind").value(m.kind())
          .name("reorderPoint").value(m.reorderPoint()).name("reorderQty").value(m.reorderQty()).endObject();
    }
    w.endArray().name("locations").beginArray();
    for (var l : FlowPilotMasterData.locations()) {
      w.beginObject().name("id").value(l.id()).name("name").value(l.name()).name("zone").value(l.zone())
          .name("capacity").value(l.capacity()).endObject();
    }
    return w.endArray().endObject().toString();
  }

  private static String network(Plant plant) {
    var graph = plant.flowPilot().graph();
    var w = new JsonWriter().beginObject().name("nodes").beginArray();
    for (var n : FlowPilotMasterData.nodes()) {
      w.beginObject().name("id").value(n.id()).name("x").value(n.x()).name("z").value(n.z()).name("dock").value(n.dock())
          .endObject();
    }
    w.endArray().name("edges").beginArray();
    for (var e : FlowPilotMasterData.edges()) {
      w.beginObject().name("id").value(e.id()).name("from").value(e.from()).name("to").value(e.to())
          .name("length").value(e.length()).name("blocked").value(graph.isBlocked(e.id())).endObject();
    }
    return w.endArray().endObject().toString();
  }

  /** Oldest first; sections that would end before the run started are left out, the first one starts at 0 at the earliest. */
  private static String kpiSeries(Plant plant, Map<String, Object> q) {
    int bucket = q.containsKey("bucketMinutes") ? PlantCommands.whole(q, "bucketMinutes") : 10;
    int count = q.containsKey("buckets") ? PlantCommands.whole(q, "buckets") : 12;
    if (bucket < 1 || bucket > 240) throw DomainException.invalid("bucketMinutes muss zwischen 1 und 240 liegen");
    if (count < 1 || count > 48) throw DomainException.invalid("buckets muss zwischen 1 und 48 liegen");
    long now = plant.nowMs();
    var w = new JsonWriter().beginArray();
    for (int i = 0; i < count; i++) {
      long to = now - (long) (count - 1 - i) * bucket * MINUTE_MS;
      if (to <= 0) continue;
      long from = Math.max(0, to - bucket * MINUTE_MS);
      Kpis k = plant.smartFactory().kpis(from, to);
      double sum = 0;
      int n = 0;
      for (Kpis.MachineKpi m : k.machines().values()) {
        if (Double.isFinite(m.oee())) {
          sum += m.oee();
          n++;
        }
      }
      w.beginObject().name("fromMs").value(from).name("toMs").value(to)
          .name("throughputPerHour").value(k.throughputPerHour()).name("onTimeRate").value(k.onTimeRate())
          .name("avgLeadTimeS").value(k.avgLeadTimeS()).name("oee");
      if (n == 0) w.nullValue();
      else w.value(sum / n);
      w.endObject();
    }
    return w.endArray().toString();
  }
```

`PlantCommands.text` und `PlantCommands.whole` sind paketsichtbar und werden schon von `kpis` genutzt. `PathGraph.isBlocked(String)` ist öffentlich; der FlowPilot-Controller nutzt sie genauso.

Typen aus dem Interfaces-Block an das Ende von `FactoryTwin/src/nexus/types.ts` anfügen.

- [ ] **Step 4: Tests laufen lassen, grün; Kern für den Browser bauen**

Run: `./mvnw -B -q -pl platform/plant -am test -Dtest=PlantQueriesTest -Dsurefire.failIfNoSpecifiedTests=false; echo "exit=$?"`
Expected: `exit=0`.

Run: `cd FactoryTwin && npm run core && npx vitest run src/nexus && npx tsc -b --noEmit`
Expected: PASS, auch `answers the new queries from the TeaVM core`.

- [ ] **Step 5: Commit**

```bash
git add platform/plant FactoryTwin/src/nexus
git commit -m "feat(plant): Abfragen für Produkte, Plan, Auftrag, Lager, Wegenetz und Kennzahlverlauf"
```

---

### Task 2: Abfrage-Hook und SVG-Chart-Bausteine

**Files:**
- Create: `FactoryTwin/src/nexus/usePlantQuery.ts`
- Modify: `FactoryTwin/src/nexus/useKpis.ts` (eine Zeile über `usePlantQuery`)
- Create: `FactoryTwin/src/ui/kit/charts/scale.ts`, `FactoryTwin/src/ui/kit/charts/status.ts`, `FactoryTwin/src/ui/kit/charts/BarList.tsx`, `FactoryTwin/src/ui/kit/charts/StatusStack.tsx`, `FactoryTwin/src/ui/kit/charts/Columns.tsx`
- Modify: `FactoryTwin/src/ui/kit/index.ts`
- Modify: `FactoryTwin/src/ui/panels/DetailPanel.tsx` (`MachineDetails`, `AgvDetails` exportieren)
- Test: `FactoryTwin/src/ui/kit/charts/charts.test.ts`

**Interfaces:**
- Consumes: `query<T>(q: Query): Promise<Answer<T>>` und `useFactory(s => s.run)` aus `store/factoryStore.ts`.
- Produces:
  - `usePlantQuery<T>(q: Query, everyMs = 0): { data: T | null; error: string | null; reload: () => void }`
  - `niceMax(v: number): number` (0 oder kleiner → 1; sonst die nächste Zahl 1, 2 oder 5 × 10^k ≥ v)
  - `ticks(max: number, count = 4): number[]` (gleich verteilt von 0 bis `niceMax(max)`, `count + 1` Werte)
  - `statusShares(timeInStatusMs: Record<string, number>): { status: MachineStatus; share: number; ms: number }[]` (Reihenfolge RUNNING, SETUP, IDLE, BLOCKED, STOPPED, FAULT, REPAIR, MAINTENANCE, UNKNOWN; nur Anteile > 0; Summe 0 → `[]`)
  - `<BarList rows={{ id: string; label: ReactNode; value: number | null; text: string }[]} max={number} label={string} />` (Werte 0..max, `null` → „–“ ohne Balken)
  - `<StatusStack timeInStatusMs={Record<string, number>} label={string} />` (ein gestapelter Balken in Zustandsfarben)
  - `<Columns values={(number | null)[]} labels={string[]} label={string} format={(v: number) => string} />` (Säulen, `null` = leerer Platz)
  - `export function MachineDetails({ m }: { m: SceneMachine })`, `export function AgvDetails({ a }: { a: SceneAgv })`

- [ ] **Step 1: Failing test schreiben**

`FactoryTwin/src/ui/kit/charts/charts.test.ts`:

```ts
import { describe, expect, it } from 'vitest';
import { niceMax, ticks } from './scale';
import { statusShares } from './status';

describe('chart scales', () => {
  it('rounds the top of an axis to 1, 2 or 5 times a power of ten', () => {
    expect(niceMax(0)).toBe(1);
    expect(niceMax(-3)).toBe(1);
    expect(niceMax(0.8)).toBe(1);
    expect(niceMax(13)).toBe(20);
    expect(niceMax(42)).toBe(50);
    expect(niceMax(50)).toBe(50);
    expect(niceMax(51)).toBe(100);
    expect(ticks(42)).toEqual([0, 12.5, 25, 37.5, 50]);
  });

  it('turns time in status into shares in a fixed order and drops empty ones', () => {
    expect(statusShares({})).toEqual([]);
    expect(statusShares({ IDLE: 0, RUNNING: 0 })).toEqual([]);
    const s = statusShares({ IDLE: 1000, RUNNING: 3000, FAULT: 0 });
    expect(s.map((x) => x.status)).toEqual(['RUNNING', 'IDLE']);
    expect(s.map((x) => x.share)).toEqual([0.75, 0.25]);
  });
});
```

- [ ] **Step 2: Test laufen lassen, rot**

Run: `cd FactoryTwin && npx vitest run src/ui/kit/charts`
Expected: FAIL, `./scale` fehlt.

- [ ] **Step 3: Umsetzen**

`scale.ts`:

```ts
/** The top of an axis: the next 1, 2 or 5 × 10^k at or above v; 1 when there is nothing to show. */
export function niceMax(v: number): number {
  if (!(v > 0)) return 1;
  const p = 10 ** Math.floor(Math.log10(v));
  for (const f of [1, 2, 5, 10]) if (f * p >= v) return f * p;
  return 10 * p;
}

/** count + 1 evenly spaced values from 0 to niceMax(max). */
export function ticks(max: number, count = 4): number[] {
  const top = niceMax(max);
  return Array.from({ length: count + 1 }, (_, i) => (top * i) / count);
}
```

`status.ts`:

```ts
import type { MachineStatus } from '../../../nexus/types';

const ORDER: MachineStatus[] = ['RUNNING', 'SETUP', 'IDLE', 'BLOCKED', 'STOPPED', 'FAULT', 'REPAIR', 'MAINTENANCE', 'UNKNOWN'];

/** Shares of the time in each status, in a fixed order so stacked bars of different machines line up. */
export function statusShares(timeInStatusMs: Record<string, number>): { status: MachineStatus; share: number; ms: number }[] {
  const total = ORDER.reduce((sum, s) => sum + (timeInStatusMs[s] ?? 0), 0);
  if (total <= 0) return [];
  return ORDER.filter((s) => (timeInStatusMs[s] ?? 0) > 0).map((s) => ({
    status: s,
    ms: timeInStatusMs[s],
    share: timeInStatusMs[s] / total,
  }));
}
```

`BarList.tsx`:

```tsx
import type { ReactNode } from 'react';

/** Horizontal bars, neutral grey: a value is not a state. null shows a dash and no bar. */
export function BarList({
  rows,
  max,
  label,
}: {
  rows: { id: string; label: ReactNode; value: number | null; text: string }[];
  max: number;
  label: string;
}) {
  return (
    <ul aria-label={label} className="flex flex-col">
      {rows.map((r) => (
        <li key={r.id} className="grid grid-cols-[minmax(0,140px)_minmax(0,1fr)_64px] items-center gap-3 border-b border-line py-2 text-[12px] last:border-b-0">
          <span className="min-w-0 truncate text-txt-1">{r.label}</span>
          <span className="h-1.5 rounded-sm bg-ink-3" aria-hidden="true">
            {r.value !== null && (
              <span className="block h-full rounded-sm bg-txt-2" style={{ width: `${Math.max(0, Math.min(1, r.value / max)) * 100}%` }} />
            )}
          </span>
          <span className="text-right font-mono tnum text-txt-1">{r.value === null ? '–' : r.text}</span>
        </li>
      ))}
    </ul>
  );
}
```

`StatusStack.tsx`:

```tsx
import { STATUS_COLOR, STATUS_SHORT, pct } from '../../format';
import { statusShares } from './status';

/** One bar split by the time a machine spent in each status; the text alternative lists the shares. */
export function StatusStack({ timeInStatusMs, label }: { timeInStatusMs: Record<string, number>; label: string }) {
  const shares = statusShares(timeInStatusMs);
  const text = shares.length ? shares.map((s) => `${STATUS_SHORT[s.status]} ${pct(s.share)}`).join(', ') : 'keine Daten';
  return (
    <div role="img" aria-label={`${label}: ${text}`} title={text} className="flex h-2 w-full overflow-hidden rounded-sm bg-ink-3">
      {shares.map((s) => (
        <span key={s.status} style={{ width: `${s.share * 100}%`, background: STATUS_COLOR[s.status] }} />
      ))}
    </div>
  );
}
```

`Columns.tsx`:

```tsx
import { niceMax } from './scale';

/** Columns over equal sections; a null section stays empty (no data, not zero). */
export function Columns({
  values,
  labels,
  label,
  format,
}: {
  values: (number | null)[];
  labels: string[];
  label: string;
  format: (v: number) => string;
}) {
  const top = niceMax(Math.max(0, ...values.filter((v): v is number => v !== null)));
  const text = values.map((v, i) => `${labels[i]} ${v === null ? '–' : format(v)}`).join(', ');
  return (
    <figure className="min-w-0">
      <div role="img" aria-label={`${label}: ${text}`} className="flex h-32 items-end gap-1 border-b border-line">
        {values.map((v, i) => (
          <div key={i} className="flex h-full min-w-0 flex-1 items-end" title={`${labels[i]}: ${v === null ? '–' : format(v)}`}>
            {v !== null && <div className="w-full rounded-t-sm bg-txt-2" style={{ height: `${(v / top) * 100}%`, minHeight: v > 0 ? 2 : 0 }} />}
          </div>
        ))}
      </div>
      <div className="mt-1 flex justify-between font-mono text-[11px] text-txt-3 tnum" aria-hidden="true">
        <span>{labels[0] ?? ''}</span>
        <span>
          max {format(top)}
        </span>
        <span>{labels[labels.length - 1] ?? ''}</span>
      </div>
    </figure>
  );
}
```

`index.ts` ergänzen:

```ts
export * from './charts/BarList';
export * from './charts/Columns';
export * from './charts/StatusStack';
export * from './charts/scale';
export * from './charts/status';
```

`usePlantQuery.ts`:

```ts
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
          if (everyMs <= 0 && timer) clearInterval(timer);
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
```

`useKpis.ts` wird:

```ts
import type { Kpis } from './types';
import { usePlantQuery } from './usePlantQuery';

/** Key figures of the last windowMinutes, asked every 2 s while the component is shown; refusals keep the last. */
export function useKpis(windowMinutes = 60): Kpis | null {
  return usePlantQuery<Kpis>({ query: 'kpis', windowMinutes }, 2_000).data;
}
```

In `DetailPanel.tsx` `function MachineDetails` und `function AgvDetails` zu `export function …` machen.

- [ ] **Step 4: Tests und Typprüfung**

Run: `cd FactoryTwin && npx vitest run && npx tsc -b --noEmit`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add FactoryTwin/src
git commit -m "feat(ui): Abfrage-Hook und SVG-Chart-Bausteine"
```

---

### Task 3: Machines

**Files:**
- Create: `FactoryTwin/src/areas/machines/pick.ts`, `FactoryTwin/src/areas/machines/MachinesArea.tsx`
- Modify: `FactoryTwin/src/shell/Shell.tsx`, `FactoryTwin/src/shell/areas.ts` (`machines`: `comesWith: null`), `FactoryTwin/src/shell/Placeholder.tsx` (Eintrag `machines` aus `MEANWHILE` entfernen), `FactoryTwin/src/areas/overview/OverviewArea.tsx` (Link auf `#/machines/<id>`)
- Test: `FactoryTwin/src/areas/machines/pick.test.ts`

**Interfaces:**
- Consumes: `sceneOf`, `useFactory` (Szene mit `SceneMachine`), `MachineDetails` (Task 2), `hrefOf`, `DataTable`, `StateTag`.
- Produces: `pickMachine<M extends { id: string }>(machines: M[], id: string | null): { machine: M | null; unknown: string | null }`, `<MachinesArea id={string | null} />`.

- [ ] **Step 1: Failing test schreiben**

`pick.test.ts`:

```ts
import { describe, expect, it } from 'vitest';
import { pickMachine } from './pick';

const machines = [{ id: 'CNC-01' }, { id: 'CNC-02' }];

describe('pickMachine', () => {
  it('finds the machine of the link', () => {
    expect(pickMachine(machines, 'CNC-02')).toEqual({ machine: { id: 'CNC-02' }, unknown: null });
  });

  it('anUnknownMachineIdIsSaidAndTheListStays', () => {
    expect(pickMachine(machines, 'CNC-99')).toEqual({ machine: null, unknown: 'CNC-99' });
    expect(pickMachine(machines, null)).toEqual({ machine: null, unknown: null });
    expect(pickMachine([], 'CNC-01')).toEqual({ machine: null, unknown: null }); // no scene yet: not unknown, just not loaded
  });
});
```

- [ ] **Step 2: Test laufen lassen, rot**

Run: `cd FactoryTwin && npx vitest run src/areas/machines`
Expected: FAIL, `./pick` fehlt.

- [ ] **Step 3: Umsetzen**

`pick.ts`:

```ts
/** The machine a link names. Before the first frame there are no machines, and no id counts as unknown. */
export function pickMachine<M extends { id: string }>(machines: M[], id: string | null): { machine: M | null; unknown: string | null } {
  if (id === null || machines.length === 0) return { machine: null, unknown: null };
  const machine = machines.find((m) => m.id === id) ?? null;
  return { machine, unknown: machine ? null : id };
}
```

`MachinesArea.tsx`:

```tsx
import { hrefOf } from '../../shell/route';
import { sceneOf, useFactory } from '../../store/factoryStore';
import { PROCESS_LABEL } from '../../twin/config';
import type { SceneMachine } from '../../twin/types';
import { nf, pct } from '../../ui/format';
import { DataTable, Section, StateTag, type Column } from '../../ui/kit';
import { MachineDetails } from '../../ui/panels/DetailPanel';
import { pickMachine } from './pick';

const columns: Column<SceneMachine>[] = [
  { key: 'id', label: 'Maschine', value: (m) => m.id, mono: true, width: '96px' },
  { key: 'name', label: 'Name', value: (m) => m.name },
  { key: 'status', label: 'Zustand', value: (m) => m.status, render: (m) => <StateTag kind="machine" status={m.status} />, width: '120px' },
  { key: 'abilities', label: 'Fähigkeiten', value: (m) => m.capabilities.map((c) => PROCESS_LABEL[c]).join(', ') },
  { key: 'progress', label: 'Fortschritt', value: (m) => (m.job ? m.job.progress / m.job.duration : null), render: (m) => (m.job ? pct(m.job.progress / m.job.duration) : '–'), align: 'right', mono: true, width: '96px' },
  { key: 'temp', label: 'Temperatur', value: (m) => m.sensors.temperature, render: (m) => `${nf(m.sensors.temperature, 1)} °C`, align: 'right', mono: true, width: '104px' },
  { key: 'wear', label: 'Verschleiss', value: (m) => m.wear, render: (m) => pct(m.wear), align: 'right', mono: true, width: '96px' },
];

/** Every machine as a row; the machine of the link (#/machines/CNC-02) shows its sensors and commands beside. */
export function MachinesArea({ id }: { id: string | null }) {
  const machines = useFactory((s) => sceneOf(s).machines);
  const { machine, unknown } = pickMachine(machines, id);
  return (
    <div className="grid grid-cols-12 gap-x-8 gap-y-8 px-4 py-6 lg:px-6 lg:py-8">
      <Section title="Alle Maschinen" className="col-span-12 xl:col-span-8">
        {unknown && (
          <p role="alert" className="mb-3 text-[13px] text-txt-1">
            Eine Maschine <span className="font-mono">{unknown}</span> gibt es nicht. Wähle eine aus der Liste.
          </p>
        )}
        <DataTable
          label="Maschinen"
          rows={machines}
          columns={columns}
          rowKey={(m) => m.id}
          onRowClick={(m) => {
            window.location.hash = hrefOf('machines', m.id);
          }}
          empty="Noch keine Daten der Anlage."
        />
      </Section>
      <Section title={machine ? machine.id : 'Auswahl'} className="col-span-12 xl:col-span-4">
        {machine ? (
          <MachineDetails m={machine} />
        ) : (
          <p className="text-[13px] text-txt-3">Wähle eine Maschine, um Sensoren, Auftrag und Befehle zu sehen.</p>
        )}
      </Section>
    </div>
  );
}
```

`Shell.tsx`, im `switch` von `page`:

```tsx
    case 'machines':
      return <MachinesArea id={param} />;
```

(Import `import { MachinesArea } from '../areas/machines/MachinesArea';`.)

`areas.ts`: beim Eintrag `machines` `comesWith: 'M2-C'` → `comesWith: null`. `Placeholder.tsx`: die Zeile `machines: { … }` aus `MEANWHILE` löschen.

`OverviewArea.tsx`: im Maschinen-Link `href={hrefOf('twin')}` und `onClick={() => select({ type: 'machine', id: m.id }, true)}` ersetzen durch `href={hrefOf('machines', m.id)}` ohne `onClick`, `title={`${m.id} in Machines zeigen`}`. Die dann unbenutzte Variable `select` entfernen.

- [ ] **Step 4: Tests, Typprüfung, Sichtprüfung**

Run: `cd FactoryTwin && npx vitest run && npx tsc -b --noEmit`
Expected: PASS.

Sichtprüfung (`twin-browser`): `#/machines` zeigt sieben Zeilen; Klick auf CNC-02 → `#/machines/CNC-02`, rechts Sensoren und Befehle; „Störung“ auslösen → Zustand wechselt in der Zeile; `#/machines/CNC-99` → Satz „gibt es nicht“, Liste bleibt; Overview-Klick auf eine Maschine landet in Machines.

- [ ] **Step 5: Commit**

```bash
git add FactoryTwin/src
git commit -m "feat(ui): Machines mit Zustand, Sensoren und Befehlen je Maschine"
```

---

### Task 4: Warehouse

**Files:**
- Create: `FactoryTwin/src/areas/warehouse/stock.ts`, `FactoryTwin/src/areas/warehouse/WarehouseArea.tsx`
- Modify: `FactoryTwin/src/shell/Shell.tsx`, `FactoryTwin/src/shell/areas.ts` (`warehouse`: `comesWith: null`), `FactoryTwin/src/shell/Placeholder.tsx` (`warehouse` aus `MEANWHILE`)
- Test: `FactoryTwin/src/areas/warehouse/stock.test.ts`

**Interfaces:**
- Consumes: `usePlantQuery<WarehouseInfo>({ query: 'warehouse' })` (Task 1/2), `twin.latest.stock` (`FrameStock[]`), `send`.
- Produces:
  - `interface StockRow { key: string; locationId: string; locationName: string; materialId: string; materialName: string; onHand: number; reserved: number; available: number; belowReorder: boolean }`
  - `stockRows(stock: FrameStock[], info: WarehouseInfo): StockRow[]` (eine Zeile je Bestand; `belowReorder`, wenn die verfügbare Menge des Materials über alle Lagerplätze unter seinem Meldebestand liegt; unbekannte IDs zeigen die ID als Namen)
  - `freeCapacity(stock: FrameStock[], info: WarehouseInfo, locationId: string): number | null` (Kapazität minus Bestand des Platzes; unbekannter Platz → `null`; nie unter 0)
  - `receiptCommand(materialId: string, locationId: string, quantity: number): Command`

- [ ] **Step 1: Failing test schreiben**

`stock.test.ts`:

```ts
import { describe, expect, it } from 'vitest';
import type { FrameStock, WarehouseInfo } from '../../nexus/types';
import { freeCapacity, receiptCommand, stockRows } from './stock';

const info: WarehouseInfo = {
  materials: [
    { id: 'RAW-AL', name: 'Aluminium-Rohling', kind: 'RAW', reorderPoint: 40, reorderQty: 100 },
    { id: 'FG-GH200', name: 'GH-200 fertig', kind: 'FINISHED', reorderPoint: 0, reorderQty: 0 },
  ],
  locations: [
    { id: 'RAW-1', name: 'Rohlager 1', zone: 'RAW', capacity: 120 },
    { id: 'FG-1', name: 'Fertigwaren 1', zone: 'FINISHED', capacity: 160 },
  ],
};
const stock: FrameStock[] = [
  { materialId: 'RAW-AL', locationId: 'RAW-1', onHand: 50, reserved: 20 },
  { materialId: 'FG-GH200', locationId: 'FG-1', onHand: 12, reserved: 0 },
];

describe('warehouse', () => {
  it('names stock and flags material below its reorder point', () => {
    const rows = stockRows(stock, info);
    expect(rows[0]).toMatchObject({ locationName: 'Rohlager 1', materialName: 'Aluminium-Rohling', available: 30, belowReorder: true });
    expect(rows[1]).toMatchObject({ materialName: 'GH-200 fertig', belowReorder: false });
    expect(stockRows([{ materialId: 'X', locationId: 'Y', onHand: 1, reserved: 0 }], info)[0]).toMatchObject({ materialName: 'X', locationName: 'Y' });
  });

  it('receiptRoomIsTheFreeCapacity', () => {
    expect(freeCapacity(stock, info, 'RAW-1')).toBe(70);
    expect(freeCapacity(stock, info, 'FG-1')).toBe(148);
    expect(freeCapacity(stock, info, 'NOPE')).toBeNull();
    expect(freeCapacity([{ materialId: 'RAW-AL', locationId: 'RAW-1', onHand: 130, reserved: 0 }], info, 'RAW-1')).toBe(0);
    expect(receiptCommand('RAW-AL', 'RAW-1', 25)).toEqual({ command: 'stockReceipt', materialId: 'RAW-AL', locationId: 'RAW-1', quantity: 25 });
  });
});
```

- [ ] **Step 2: Test laufen lassen, rot**

Run: `cd FactoryTwin && npx vitest run src/areas/warehouse`
Expected: FAIL, `./stock` fehlt.

- [ ] **Step 3: Umsetzen**

`stock.ts`:

```ts
import type { Command, FrameStock, WarehouseInfo } from '../../nexus/types';

export interface StockRow {
  key: string;
  locationId: string;
  locationName: string;
  materialId: string;
  materialName: string;
  onHand: number;
  reserved: number;
  available: number;
  belowReorder: boolean;
}

/** One row per stock line, with names from FlowPilot's master data; below reorder counts all locations together. */
export function stockRows(stock: FrameStock[], info: WarehouseInfo): StockRow[] {
  const available = new Map<string, number>();
  for (const s of stock) available.set(s.materialId, (available.get(s.materialId) ?? 0) + s.onHand - s.reserved);
  return stock.map((s) => {
    const m = info.materials.find((x) => x.id === s.materialId);
    const l = info.locations.find((x) => x.id === s.locationId);
    return {
      key: `${s.locationId}/${s.materialId}`,
      locationId: s.locationId,
      locationName: l?.name ?? s.locationId,
      materialId: s.materialId,
      materialName: m?.name ?? s.materialId,
      onHand: s.onHand,
      reserved: s.reserved,
      available: s.onHand - s.reserved,
      belowReorder: m !== undefined && m.reorderPoint > 0 && (available.get(s.materialId) ?? 0) < m.reorderPoint,
    };
  });
}

/** Room left at a location; null for a location FlowPilot does not know. */
export function freeCapacity(stock: FrameStock[], info: WarehouseInfo, locationId: string): number | null {
  const l = info.locations.find((x) => x.id === locationId);
  if (!l) return null;
  const used = stock.filter((s) => s.locationId === locationId).reduce((sum, s) => sum + s.onHand, 0);
  return Math.max(0, l.capacity - used);
}

export function receiptCommand(materialId: string, locationId: string, quantity: number): Command {
  return { command: 'stockReceipt', materialId, locationId, quantity };
}
```

`WarehouseArea.tsx`:

```tsx
import { useEffect, useState } from 'react';
import type { WarehouseInfo } from '../../nexus/types';
import { usePlantQuery } from '../../nexus/usePlantQuery';
import { send, twin, useFactory } from '../../store/factoryStore';
import { STATE_HEX } from '../../ui/format';
import { control, DataTable, Field, Section, type Column } from '../../ui/kit';
import { Button } from '../../ui/primitives';
import { freeCapacity, receiptCommand, stockRows, type StockRow } from './stock';

const columns: Column<StockRow>[] = [
  { key: 'location', label: 'Lagerplatz', value: (r) => r.locationName },
  { key: 'material', label: 'Material', value: (r) => r.materialName },
  { key: 'onHand', label: 'Bestand', value: (r) => r.onHand, align: 'right', mono: true, width: '96px' },
  { key: 'reserved', label: 'Reserviert', value: (r) => r.reserved, align: 'right', mono: true, width: '96px' },
  { key: 'available', label: 'Verfügbar', value: (r) => r.available, align: 'right', mono: true, width: '96px' },
  {
    key: 'reorder',
    label: 'Meldebestand',
    value: (r) => (r.belowReorder ? 1 : 0),
    width: '140px',
    render: (r) =>
      r.belowReorder ? (
        <span className="inline-flex items-center gap-1.5 whitespace-nowrap">
          <span className="h-1.5 w-1.5 rounded-full" style={{ background: STATE_HEX.wait }} aria-hidden="true" />
          unterschritten
        </span>
      ) : (
        <span className="text-txt-3">ok</span>
      ),
  },
];

function Receipt({ info }: { info: WarehouseInfo }) {
  useFactory((s) => s.scene); // free room follows the frames
  const [materialId, setMaterialId] = useState(info.materials[0]?.id ?? '');
  const [locationId, setLocationId] = useState(info.locations[0]?.id ?? '');
  const [quantity, setQuantity] = useState(10);
  const [busy, setBusy] = useState(false);
  useEffect(() => {
    if (!materialId && info.materials[0]) setMaterialId(info.materials[0].id);
    if (!locationId && info.locations[0]) setLocationId(info.locations[0].id);
  }, [info, materialId, locationId]);
  const room = freeCapacity(twin.latest?.stock ?? [], info, locationId);
  return (
    <form
      className="grid grid-cols-1 items-end gap-4 sm:grid-cols-2 xl:grid-cols-[minmax(0,1fr)_minmax(0,1fr)_120px_auto]"
      onSubmit={async (e) => {
        e.preventDefault();
        setBusy(true);
        await send(receiptCommand(materialId, locationId, quantity)); // a refusal shows its reason in the notice line
        setBusy(false);
      }}
      aria-label="Wareneingang"
    >
      <Field label="Material">
        <select className={control} value={materialId} onChange={(e) => setMaterialId(e.target.value)}>
          {info.materials.map((m) => (
            <option key={m.id} value={m.id}>
              {m.name}
            </option>
          ))}
        </select>
      </Field>
      <Field label="Lagerplatz">
        <select className={control} value={locationId} onChange={(e) => setLocationId(e.target.value)}>
          {info.locations.map((l) => (
            <option key={l.id} value={l.id}>
              {l.name}
            </option>
          ))}
        </select>
      </Field>
      <Field label="Menge">
        <input
          type="number"
          inputMode="numeric"
          min={1}
          step={1}
          className={`${control} font-mono tnum`}
          value={quantity}
          onChange={(e) => {
            const n = Math.round(Number(e.target.value));
            if (e.target.value !== '' && Number.isFinite(n) && n >= 1) setQuantity(n);
          }}
        />
      </Field>
      <Button type="submit" variant="primary" disabled={busy || !materialId || !locationId}>
        {busy ? 'Wird gebucht …' : 'Einbuchen'}
      </Button>
      <p className="text-[12px] text-txt-3 sm:col-span-2 xl:col-span-4" aria-live="polite">
        {room === null ? 'Lagerplatz unbekannt.' : `Freier Platz am Lagerplatz: ${room} Stück.`} Die Anlage prüft Kapazität und Materialart.
      </p>
    </form>
  );
}

/** Stock per location from the frames, names and reorder points from FlowPilot, and the goods receipt. */
export function WarehouseArea() {
  useFactory((s) => s.scene);
  const { data: info, error } = usePlantQuery<WarehouseInfo>({ query: 'warehouse' });
  const rows = info ? stockRows(twin.latest?.stock ?? [], info) : [];
  return (
    <div className="flex flex-col gap-10 px-4 py-6 lg:px-6 lg:py-8">
      <Section title="Bestände">
        {!info ? (
          <p className="text-[13px] text-txt-3">{error ?? 'Stammdaten werden geladen …'}</p>
        ) : (
          <DataTable label="Bestände je Lagerplatz" rows={rows} columns={columns} rowKey={(r) => r.key} empty="Keine Bestände." />
        )}
      </Section>
      {info && (
        <Section title="Wareneingang">
          <Receipt info={info} />
        </Section>
      )}
    </div>
  );
}
```

`Shell.tsx`:

```tsx
    case 'warehouse':
      return <WarehouseArea />;
```

`areas.ts` (`warehouse` → `comesWith: null`), `Placeholder.tsx` (`warehouse` aus `MEANWHILE`).

- [ ] **Step 4: Tests, Typprüfung, Sichtprüfung**

Run: `cd FactoryTwin && npx vitest run && npx tsc -b --noEmit`
Expected: PASS.

Sichtprüfung: Bestände mit Namen; 10 Rohlinge einbuchen → Bestand +10, freier Platz −10; eine Menge über dem freien Platz → Meldungszeile mit dem Grund des Kerns; nach Tempo 25 sinkt der Rohbestand, „unterschritten“ erscheint mit Punkt und Wort.

- [ ] **Step 5: Commit**

```bash
git add FactoryTwin/src
git commit -m "feat(ui): Warehouse mit Beständen je Lagerplatz und Wareneingang"
```

---

### Task 5: SmartFactory

**Files:**
- Create: `FactoryTwin/src/areas/smartfactory/orderForm.ts`, `FactoryTwin/src/areas/smartfactory/gantt.ts`, `FactoryTwin/src/areas/smartfactory/Gantt.tsx`, `FactoryTwin/src/areas/smartfactory/OrderPanel.tsx`, `FactoryTwin/src/areas/smartfactory/SmartFactoryArea.tsx`
- Modify: `FactoryTwin/src/shell/Shell.tsx`, `FactoryTwin/src/shell/areas.ts` (`smartfactory`: `comesWith: null`), `FactoryTwin/src/shell/Placeholder.tsx` (`smartfactory` aus `MEANWHILE`)
- Test: `FactoryTwin/src/areas/smartfactory/smartfactory.test.ts`

**Interfaces:**
- Consumes: `usePlantQuery` (`products`, `schedule`, `order`), `twin.latest.orders` (`FrameOrder[]`), `send`, `Schedule`, `OrderDetail`, `ProductInfo`, `Priority` (Task 1).
- Produces:
  - `interface OrderForm { productId: string; quantity: number; priority: Priority; dueInMinutes: number }`
  - `newOrderForm(productId: string): OrderForm` (Menge 10, NORMAL, Termin in 240 min)
  - `orderCommand(f: OrderForm): Command` (`{ command: 'createOrder', productId, quantity, priority, dueInMinutes }`)
  - `submitOrder(f: OrderForm, send: (c: Command) => Promise<boolean>): Promise<OrderForm>` (angenommen → neues Formular mit demselben Produkt; abgelehnt → dasselbe Formular)
  - `ganttLayout(s: Schedule, nowMs: number, machineIds: string[]): { fromMs: number; toMs: number; nowLeft: number; lanes: { machineId: string; bars: { key: string; orderId: string; seq: number; left: number; width: number; setupShare: number; late: boolean }[] }[] }` (Prozent 0–100; mindestens eine Stunde; Breite mindestens 0,5; eine Zeile je Maschine, auch ohne Balken)

- [ ] **Step 1: Failing tests schreiben**

`smartfactory.test.ts`:

```ts
import { describe, expect, it } from 'vitest';
import type { Command, Schedule } from '../../nexus/types';
import { ganttLayout } from './gantt';
import { newOrderForm, orderCommand, submitOrder } from './orderForm';

const HOUR = 3_600_000;
const plan = (ops: Schedule['ops'], lateOrders: string[] = []): Schedule => ({
  rule: 'PRIORITY',
  ops,
  unplannable: [],
  lateOrders,
  utilization: {},
  bottleneckMachineId: null,
});

describe('order form', () => {
  it('sends what the form holds', () => {
    expect(orderCommand({ productId: 'GH200', quantity: 5, priority: 'EILIG', dueInMinutes: 90 })).toEqual({
      command: 'createOrder',
      productId: 'GH200',
      quantity: 5,
      priority: 'EILIG',
      dueInMinutes: 90,
    });
  });

  it('aRefusedOrderKeepsWhatTheUserTyped', async () => {
    const typed = { productId: 'AW50', quantity: 0, priority: 'HOCH' as const, dueInMinutes: -5 };
    const sent: Command[] = [];
    expect(await submitOrder(typed, async (c) => (sent.push(c), false))).toBe(typed);
    expect(await submitOrder(typed, async () => true)).toEqual(newOrderForm('AW50'));
    expect(sent).toHaveLength(1);
  });
});

describe('gantt', () => {
  it('ganttHandlesAnEmptyPlanAndZeroLengthOps', () => {
    const empty = ganttLayout(plan([]), 2 * HOUR, ['CNC-01', 'QC-01']);
    expect(empty.lanes.map((l) => l.machineId)).toEqual(['CNC-01', 'QC-01']);
    expect(empty.lanes.every((l) => l.bars.length === 0)).toBe(true);
    expect(empty.toMs - empty.fromMs).toBe(HOUR);
    expect(empty.nowLeft).toBe(0);

    const g = ganttLayout(
      plan(
        [
          { orderId: 'PO-1', seq: 1, machineId: 'CNC-01', startMs: 2 * HOUR, endMs: 2 * HOUR, setupMs: 0 },
          { orderId: 'PO-2', seq: 1, machineId: 'QC-01', startMs: 3 * HOUR, endMs: 5 * HOUR, setupMs: 30 * 60_000 },
        ],
        ['PO-2'],
      ),
      2 * HOUR,
      ['CNC-01', 'QC-01'],
    );
    const [zero] = g.lanes[0].bars;
    const [late] = g.lanes[1].bars;
    expect(zero.width).toBeGreaterThanOrEqual(0.5);
    expect(Number.isFinite(zero.left)).toBe(true);
    expect(late).toMatchObject({ late: true, left: (1 / 3) * 100, width: (2 / 3) * 100, setupShare: 0.25 });
  });
});
```

- [ ] **Step 2: Tests laufen lassen, rot**

Run: `cd FactoryTwin && npx vitest run src/areas/smartfactory`
Expected: FAIL, `./gantt` und `./orderForm` fehlen.

- [ ] **Step 3: Umsetzen**

`orderForm.ts`:

```ts
import type { Command, Priority } from '../../nexus/types';

export interface OrderForm {
  productId: string;
  quantity: number;
  priority: Priority;
  dueInMinutes: number;
}

export const newOrderForm = (productId: string): OrderForm => ({ productId, quantity: 10, priority: 'NORMAL', dueInMinutes: 240 });

export function orderCommand(f: OrderForm): Command {
  return { command: 'createOrder', productId: f.productId, quantity: f.quantity, priority: f.priority, dueInMinutes: f.dueInMinutes };
}

/** Accepted: a fresh form for the same product. Refused (send shows the reason): the form as the user left it. */
export async function submitOrder(f: OrderForm, send: (c: Command) => Promise<boolean>): Promise<OrderForm> {
  return (await send(orderCommand(f))) ? newOrderForm(f.productId) : f;
}
```

`gantt.ts`:

```ts
import type { Schedule } from '../../nexus/types';

const HOUR = 3_600_000;
const MIN_WIDTH = 0.5;

export interface GanttBar {
  key: string;
  orderId: string;
  seq: number;
  left: number;
  width: number;
  setupShare: number;
  late: boolean;
}

/**
 * Positions of the planned operations in percent of the axis. The axis starts at now or the first start, whichever
 * is earlier, and spans at least an hour; every machine gets a lane, also without operations.
 */
export function ganttLayout(s: Schedule, nowMs: number, machineIds: string[]) {
  const fromMs = Math.min(nowMs, ...s.ops.map((o) => o.startMs));
  const toMs = Math.max(fromMs + HOUR, ...s.ops.map((o) => o.endMs));
  const span = toMs - fromMs;
  const at = (ms: number) => ((ms - fromMs) / span) * 100;
  const late = new Set(s.lateOrders);
  const lanes = machineIds.map((machineId) => ({
    machineId,
    bars: s.ops
      .filter((o) => o.machineId === machineId)
      .map((o): GanttBar => {
        const length = o.endMs - o.startMs;
        return {
          key: `${o.orderId}/${o.seq}`,
          orderId: o.orderId,
          seq: o.seq,
          left: at(o.startMs),
          width: Math.max(MIN_WIDTH, at(o.endMs) - at(o.startMs)),
          setupShare: length > 0 ? Math.min(1, o.setupMs / length) : 0,
          late: late.has(o.orderId),
        };
      }),
  }));
  return { fromMs, toMs, nowLeft: at(nowMs), lanes };
}
```

`Gantt.tsx`:

```tsx
import { hrefOf } from '../../shell/route';
import { clock, STATE_HEX } from '../../ui/format';
import type { Schedule } from '../../nexus/types';
import { ganttLayout } from './gantt';

/** Planned operations per machine; late orders have a border in the waiting colour, setup is the lighter part. */
export function Gantt({ schedule, nowMs, machineIds }: { schedule: Schedule; nowMs: number; machineIds: string[] }) {
  const g = ganttLayout(schedule, nowMs, machineIds);
  return (
    <div className="min-w-0">
      <div className="mb-1 flex justify-between pl-[88px] font-mono text-[11px] text-txt-3 tnum" aria-hidden="true">
        <span>{clock(g.fromMs / 1000, false)}</span>
        <span>{clock(g.toMs / 1000, false)}</span>
      </div>
      <ul aria-label="Plan je Maschine">
        {g.lanes.map((lane) => (
          <li key={lane.machineId} className="grid grid-cols-[80px_minmax(0,1fr)] items-center gap-2 border-b border-line py-1.5 last:border-b-0">
            <span className="font-mono text-[12px] text-txt-1">{lane.machineId}</span>
            <div className="relative h-5 rounded-sm bg-ink-2">
              <span className="absolute inset-y-0 w-px bg-txt-3" style={{ left: `${g.nowLeft}%` }} aria-hidden="true" />
              {lane.bars.map((b) => (
                <a
                  key={b.key}
                  href={hrefOf('smartfactory', b.orderId)}
                  className="absolute inset-y-0.5 flex overflow-hidden rounded-sm bg-ink-4 hover:bg-line-2"
                  style={{ left: `${b.left}%`, width: `${b.width}%`, boxShadow: b.late ? `inset 0 0 0 1px ${STATE_HEX.wait}` : undefined }}
                  title={`${b.orderId}, Schritt ${b.seq}${b.late ? ', verspätet' : ''}`}
                  aria-label={`${b.orderId}, Schritt ${b.seq}${b.late ? ', verspätet' : ''}`}
                >
                  <span className="h-full bg-line-2" style={{ width: `${b.setupShare * 100}%` }} aria-hidden="true" />
                </a>
              ))}
            </div>
          </li>
        ))}
      </ul>
    </div>
  );
}
```

`OrderPanel.tsx` (Details eines Auftrags, Priorität ändern, stornieren):

```tsx
import { useState } from 'react';
import type { OrderDetail, Priority } from '../../nexus/types';
import { usePlantQuery } from '../../nexus/usePlantQuery';
import { send } from '../../store/factoryStore';
import { PROCESS_LABEL } from '../../twin/config';
import { clock } from '../../ui/format';
import { DataTable, type Column } from '../../ui/kit';
import { Button, Segmented } from '../../ui/primitives';
import { hrefOf } from '../../shell/route';

const PRIORITIES: { value: Priority; label: string }[] = [
  { value: 'NORMAL', label: 'Normal' },
  { value: 'HOCH', label: 'Hoch' },
  { value: 'EILIG', label: 'Eilig' },
];
const FINISHED = new Set(['COMPLETED', 'CANCELLED']);
const time = (ms: number | null) => (ms === null ? '–' : clock(ms / 1000, false));

type Op = OrderDetail['operations'][number];
const opColumns: Column<Op>[] = [
  { key: 'seq', label: 'Schritt', value: (o) => o.seq, mono: true, width: '64px' },
  { key: 'process', label: 'Prozess', value: (o) => PROCESS_LABEL[o.process as keyof typeof PROCESS_LABEL] ?? o.process },
  { key: 'machine', label: 'Maschine', value: (o) => o.machineId, mono: true, render: (o) => o.machineId ?? '–' },
  { key: 'status', label: 'Stand', value: (o) => o.status, mono: true },
  { key: 'plan', label: 'Geplant', value: (o) => o.plannedStartMs, render: (o) => `${time(o.plannedStartMs)}–${time(o.plannedEndMs)}`, mono: true },
  { key: 'good', label: 'Gut', value: (o) => o.good, align: 'right', mono: true },
];

/** One order with its steps, its priority and cancelling; the core decides what is allowed. */
export function OrderPanel({ orderId }: { orderId: string }) {
  const { data, error, reload } = usePlantQuery<OrderDetail>({ query: 'order', orderId }, 2_000);
  const [asking, setAsking] = useState(false);
  if (!data)
    return (
      <p role={error ? 'alert' : undefined} className="text-[13px] text-txt-2">
        {error ? `Den Auftrag ${orderId} gibt es nicht.` : 'Auftrag wird geladen …'}{' '}
        <a href={hrefOf('smartfactory')} className="underline decoration-line-2 underline-offset-4">
          Zur Liste
        </a>
      </p>
    );
  const o = data.order;
  const open = !FINISHED.has(o.status);
  const act = async (c: Parameters<typeof send>[0]) => {
    await send(c);
    reload();
  };
  return (
    <div className="flex flex-col gap-4">
      <dl className="grid grid-cols-2 gap-x-4 gap-y-2 text-[13px]">
        <dt className="text-txt-3">Produkt</dt>
        <dd className="font-mono text-txt-1">{o.productId}</dd>
        <dt className="text-txt-3">Menge</dt>
        <dd className="font-mono text-txt-1">{o.quantity}</dd>
        <dt className="text-txt-3">Stand</dt>
        <dd className="font-mono text-txt-1">{o.status}</dd>
        <dt className="text-txt-3">Termin</dt>
        <dd className="font-mono text-txt-1">{time(o.dueAtMs)}</dd>
        <dt className="text-txt-3">Gut / Ausschuss</dt>
        <dd className="font-mono text-txt-1">
          {o.good} / {o.scrapped}
        </dd>
      </dl>
      {open && (
        <div>
          <div className="mb-1.5 text-[11px] uppercase tracking-[0.08em] text-txt-3">Priorität</div>
          <Segmented ariaLabel="Priorität" value={o.priority} options={PRIORITIES} onChange={(p) => void act({ command: 'changeOrder', orderId: o.id, priority: p })} />
        </div>
      )}
      <DataTable label="Arbeitsgänge" rows={data.operations} columns={opColumns} rowKey={(x) => String(x.seq)} />
      <div className="flex flex-wrap items-center gap-2">
        <a href={hrefOf('events', o.id)} className="text-[12px] text-txt-2 underline decoration-line-2 underline-offset-4 hover:text-txt-1">
          Ereignisse dieses Auftrags
        </a>
        {open &&
          (asking ? (
            <span className="flex items-center gap-2" role="group" aria-label="Stornieren bestätigen">
              <span className="text-[12px] text-txt-2">Auftrag stornieren?</span>
              <Button variant="danger" size="sm" autoFocus onClick={() => (setAsking(false), void act({ command: 'cancelOrder', orderId: o.id }))}>
                Ja
              </Button>
              <Button variant="ghost" size="sm" onClick={() => setAsking(false)}>
                Nein
              </Button>
            </span>
          ) : (
            <Button variant="ghost" size="sm" onClick={() => setAsking(true)}>
              Stornieren
            </Button>
          ))}
      </div>
    </div>
  );
}
```

`SmartFactoryArea.tsx`:

```tsx
import { useEffect, useState, type ChangeEvent } from 'react';
import type { FrameOrder, ProductInfo, Schedule } from '../../nexus/types';
import { usePlantQuery } from '../../nexus/usePlantQuery';
import { hrefOf } from '../../shell/route';
import { sceneOf, send, twin, useFactory } from '../../store/factoryStore';
import { clock, STATE_HEX } from '../../ui/format';
import { control, DataTable, Field, Section, type Column } from '../../ui/kit';
import { Button, Segmented } from '../../ui/primitives';
import { Gantt } from './Gantt';
import { OrderPanel } from './OrderPanel';
import { newOrderForm, submitOrder, type OrderForm } from './orderForm';

const RULE_LABEL: Record<Schedule['rule'], string> = {
  PRIORITY: 'Priorität',
  EDD: 'Frühester Termin',
  FIFO: 'Reihenfolge',
  SPT: 'Kürzeste Bearbeitung',
};

function orderColumns(nowMs: number): Column<FrameOrder>[] {
  return [
    {
      key: 'id',
      label: 'Auftrag',
      value: (o) => o.id,
      mono: true,
      width: '104px',
      render: (o) => (
        <a href={hrefOf('smartfactory', o.id)} className="underline decoration-line-2 underline-offset-4 hover:decoration-accent">
          {o.id}
        </a>
      ),
    },
    { key: 'product', label: 'Produkt', value: (o) => o.productId, mono: true },
    { key: 'qty', label: 'Menge', value: (o) => o.quantity, align: 'right', mono: true },
    { key: 'prio', label: 'Priorität', value: (o) => o.priority },
    { key: 'status', label: 'Stand', value: (o) => o.status, mono: true },
    { key: 'steps', label: 'Schritte', value: (o) => o.stepsDone / Math.max(1, o.steps), render: (o) => `${o.stepsDone}/${o.steps}`, align: 'right', mono: true },
    {
      key: 'due',
      label: 'Termin',
      value: (o) => o.dueAtMs,
      mono: true,
      render: (o) => {
        const late = o.dueAtMs < nowMs && o.status !== 'COMPLETED' && o.status !== 'CANCELLED';
        return (
          <span className="inline-flex items-center gap-1.5">
            {late && <span className="h-1.5 w-1.5 rounded-full" style={{ background: STATE_HEX.wait }} aria-hidden="true" />}
            {clock(o.dueAtMs / 1000, false)}
            {late && <span className="sr-only">, verspätet</span>}
          </span>
        );
      },
    },
  ];
}

function NewOrder({ products }: { products: ProductInfo[] }) {
  const [form, setForm] = useState<OrderForm>(() => newOrderForm(products[0]?.id ?? ''));
  const [busy, setBusy] = useState(false);
  useEffect(() => {
    if (!form.productId && products[0]) setForm(newOrderForm(products[0].id));
  }, [products, form.productId]);
  const number = (k: 'quantity' | 'dueInMinutes') => (e: ChangeEvent<HTMLInputElement>) => {
    const n = Math.round(Number(e.target.value));
    if (e.target.value !== '' && Number.isFinite(n)) setForm({ ...form, [k]: n }); // the core judges the value
  };
  return (
    <form
      aria-label="Neuer Auftrag"
      className="grid grid-cols-1 items-end gap-4 sm:grid-cols-2 xl:grid-cols-[minmax(0,1fr)_96px_minmax(0,auto)_120px_auto]"
      onSubmit={async (e) => {
        e.preventDefault();
        setBusy(true);
        setForm(await submitOrder(form, send));
        setBusy(false);
      }}
    >
      <Field label="Produkt">
        <select className={control} value={form.productId} onChange={(e) => setForm({ ...form, productId: e.target.value })}>
          {products.map((p) => (
            <option key={p.id} value={p.id}>
              {p.name}
            </option>
          ))}
        </select>
      </Field>
      <Field label="Menge">
        <input type="number" inputMode="numeric" className={`${control} font-mono tnum`} value={form.quantity} onChange={number('quantity')} />
      </Field>
      <div>
        <div className="mb-1 text-[11px] uppercase tracking-[0.08em] text-txt-3">Priorität</div>
        <Segmented
          ariaLabel="Priorität"
          value={form.priority}
          onChange={(priority) => setForm({ ...form, priority })}
          options={[
            { value: 'NORMAL', label: 'Normal' },
            { value: 'HOCH', label: 'Hoch' },
            { value: 'EILIG', label: 'Eilig' },
          ]}
        />
      </div>
      <Field label="Termin in (min)">
        <input type="number" inputMode="numeric" className={`${control} font-mono tnum`} value={form.dueInMinutes} onChange={number('dueInMinutes')} />
      </Field>
      <Button type="submit" variant="primary" disabled={busy || !form.productId}>
        {busy ? 'Wird angelegt …' : 'Anlegen'}
      </Button>
    </form>
  );
}

/** Orders, the plan per machine and a new order; the order of the link (#/smartfactory/PO-0004) opens beside. */
export function SmartFactoryArea({ orderId }: { orderId: string | null }) {
  useFactory((s) => s.scene);
  const machineIds = useFactory((s) => sceneOf(s).machines.map((m) => m.id).join(','));
  const nowMs = twin.latest?.simTimeMs ?? 0;
  const orders = twin.latest?.orders ?? [];
  const { data: schedule } = usePlantQuery<Schedule>({ query: 'schedule' }, 2_000);
  const { data: products } = usePlantQuery<ProductInfo[]>({ query: 'products' });
  return (
    <div className="grid grid-cols-12 gap-x-8 gap-y-10 px-4 py-6 lg:px-6 lg:py-8">
      <Section title="Aufträge" className="col-span-12 xl:col-span-8">
        <DataTable label="Aufträge" rows={orders} columns={orderColumns(nowMs)} rowKey={(o) => o.id} empty="Noch keine Aufträge." />
        <p className="mt-2 text-[12px] text-txt-3">Offene Aufträge und die 20 zuletzt abgeschlossenen.</p>
      </Section>
      <Section title={orderId ?? 'Auftrag'} className="col-span-12 xl:col-span-4">
        {orderId ? <OrderPanel orderId={orderId} /> : <p className="text-[13px] text-txt-3">Wähle einen Auftrag, um Schritte, Priorität und Storno zu sehen.</p>}
      </Section>
      <Section
        title="Plan"
        className="col-span-12"
        action={
          schedule && (
            <span className="text-[12px] text-txt-2">
              Regel {RULE_LABEL[schedule.rule]} ·{' '}
              <a href={hrefOf('settings')} className="underline decoration-line-2 underline-offset-4 hover:text-txt-1">
                ändern
              </a>
              {schedule.bottleneckMachineId && (
                <>
                  {' '}· Engpass <span className="font-mono text-txt-1">{schedule.bottleneckMachineId}</span>
                </>
              )}
            </span>
          )
        }
      >
        {schedule ? (
          <>
            <Gantt schedule={schedule} nowMs={nowMs} machineIds={machineIds ? machineIds.split(',') : []} />
            {schedule.unplannable.length > 0 && (
              <ul className="mt-3 text-[12px] text-txt-2">
                {schedule.unplannable.map((u) => (
                  <li key={`${u.orderId}/${u.seq}`}>
                    <span className="font-mono text-txt-1">
                      {u.orderId}/{u.seq}
                    </span>{' '}
                    nicht planbar: {u.reason}
                  </li>
                ))}
              </ul>
            )}
          </>
        ) : (
          <p className="text-[13px] text-txt-3">Plan wird geladen …</p>
        )}
      </Section>
      <Section title="Neuer Auftrag" className="col-span-12">
        {products ? <NewOrder products={products} /> : <p className="text-[13px] text-txt-3">Produkte werden geladen …</p>}
      </Section>
    </div>
  );
}
```

`Shell.tsx`:

```tsx
    case 'smartfactory':
      return <SmartFactoryArea orderId={param} />;
```

`areas.ts` (`smartfactory` → `comesWith: null`), `Placeholder.tsx` (`smartfactory` aus `MEANWHILE`).

- [ ] **Step 4: Tests, Typprüfung, Sichtprüfung**

Run: `cd FactoryTwin && npx vitest run && npx tsc -b --noEmit`
Expected: PASS.

Sichtprüfung: Tempo 25 eine Minute; Aufträge füllen die Tabelle, Gantt zeigt Balken je Maschine, „jetzt“-Linie wandert; Klick auf einen Auftrag → `#/smartfactory/PO-…` mit Schritten; Priorität „Eilig“ → Liste zeigt EILIG; neuer Auftrag mit Menge 0 → Meldungszeile, Formular behält 0; gültiger Auftrag erscheint in der Liste; `#/smartfactory/PO-9999` → „gibt es nicht“.

- [ ] **Step 5: Commit**

```bash
git add FactoryTwin/src
git commit -m "feat(ui): SmartFactory mit Aufträgen, Plan je Maschine und Auftragsanlage"
```

---

### Task 6: FlowPilot

**Files:**
- Create: `FactoryTwin/src/areas/flowpilot/network.ts`, `FactoryTwin/src/areas/flowpilot/NetworkMap.tsx`, `FactoryTwin/src/areas/flowpilot/FlowPilotArea.tsx`
- Modify: `FactoryTwin/src/shell/Shell.tsx`, `FactoryTwin/src/shell/areas.ts` (`flowpilot`: `comesWith: null`), `FactoryTwin/src/shell/Placeholder.tsx` (`flowpilot` aus `MEANWHILE`)
- Test: `FactoryTwin/src/areas/flowpilot/network.test.ts`

**Interfaces:**
- Consumes: `usePlantQuery<Network>({ query: 'network' })` mit `reload`, Szene (`SceneAgv` mit `pos`, `state`), `twin.latest.transports` (`FrameTransport[]`), `AgvDetails` (Task 2), `send`.
- Produces:
  - `fitView(nodes: NetworkNode[], pad = 2): { x: number; y: number; width: number; height: number }` (SVG-`viewBox` in Hallenmetern; ohne Knoten `{0,0,1,1}`)
  - `edgeAction(e: NetworkEdge): { command: Command; label: string }` (gesperrt → `edgeUnblock`, „Freigeben“; sonst `edgeBlock`, „Sperren“)
  - `<NetworkMap network agvs selected onSelect />`, `<FlowPilotArea agvId={string | null} />`

- [ ] **Step 1: Failing test schreiben**

`network.test.ts`:

```ts
import { describe, expect, it } from 'vitest';
import { edgeAction, fitView } from './network';

describe('network', () => {
  it('frames the nodes with a margin', () => {
    expect(fitView([])).toEqual({ x: 0, y: 0, width: 1, height: 1 });
    expect(
      fitView([
        { id: 'a', x: -20, z: -3, dock: false },
        { id: 'b', x: 20, z: 3, dock: true },
      ]),
    ).toEqual({ x: -22, y: -5, width: 44, height: 10 });
  });

  it('aBlockedEdgeOffersUnblock', () => {
    const edge = { id: 'E-12', from: 'a', to: 'b', length: 4, blocked: true };
    expect(edgeAction(edge)).toEqual({ command: { command: 'edgeUnblock', edgeId: 'E-12' }, label: 'Freigeben' });
    expect(edgeAction({ ...edge, blocked: false })).toEqual({ command: { command: 'edgeBlock', edgeId: 'E-12' }, label: 'Sperren' });
  });
});
```

- [ ] **Step 2: Test laufen lassen, rot**

Run: `cd FactoryTwin && npx vitest run src/areas/flowpilot`
Expected: FAIL, `./network` fehlt.

- [ ] **Step 3: Umsetzen**

`network.ts`:

```ts
import type { Command, NetworkEdge, NetworkNode } from '../../nexus/types';

/** SVG viewBox around all nodes (hall metres, z downwards), with pad metres of margin. */
export function fitView(nodes: NetworkNode[], pad = 2) {
  if (nodes.length === 0) return { x: 0, y: 0, width: 1, height: 1 };
  const xs = nodes.map((n) => n.x);
  const zs = nodes.map((n) => n.z);
  const x = Math.min(...xs) - pad;
  const y = Math.min(...zs) - pad;
  return { x, y, width: Math.max(...xs) + pad - x, height: Math.max(...zs) + pad - y };
}

export function edgeAction(e: NetworkEdge): { command: Command; label: string } {
  return e.blocked
    ? { command: { command: 'edgeUnblock', edgeId: e.id }, label: 'Freigeben' }
    : { command: { command: 'edgeBlock', edgeId: e.id }, label: 'Sperren' };
}
```

`NetworkMap.tsx`:

```tsx
import type { Network } from '../../nexus/types';
import type { SceneAgv } from '../../twin/types';
import { AGV_COLOR, STATE_HEX } from '../../ui/format';
import { fitView } from './network';

/**
 * The path network seen from above: edges as lines (blocked ones in the fault colour), docks as squares, AGVs as dots
 * in their state colour. A click on an edge selects it; the same choice exists as a select list beside the map.
 */
export function NetworkMap({
  network,
  agvs,
  selected,
  onSelect,
}: {
  network: Network;
  agvs: SceneAgv[];
  selected: string | null;
  onSelect: (edgeId: string) => void;
}) {
  const v = fitView(network.nodes);
  const node = new Map(network.nodes.map((n) => [n.id, n]));
  const blocked = network.edges.filter((e) => e.blocked).length;
  return (
    <svg
      viewBox={`${v.x} ${v.y} ${v.width} ${v.height}`}
      className="h-auto w-full rounded-sm bg-ink-2"
      role="img"
      aria-label={`Wegenetz mit ${network.edges.length} Kanten, davon ${blocked} gesperrt, und ${agvs.length} AGVs`}
    >
      {network.edges.map((e) => {
        const a = node.get(e.from);
        const b = node.get(e.to);
        if (!a || !b) return null;
        const on = e.id === selected;
        return (
          <g key={e.id} onClick={() => onSelect(e.id)} className="cursor-pointer">
            <line x1={a.x} y1={a.z} x2={b.x} y2={b.z} stroke="transparent" strokeWidth={1.2} />
            <line
              x1={a.x}
              y1={a.z}
              x2={b.x}
              y2={b.z}
              stroke={e.blocked ? STATE_HEX.fault : on ? 'var(--color-txt-1)' : 'var(--color-line-2)'}
              strokeWidth={on ? 0.35 : 0.18}
              strokeDasharray={e.blocked ? '0.6 0.4' : undefined}
            />
          </g>
        );
      })}
      {network.nodes
        .filter((n) => n.dock)
        .map((n) => (
          <rect key={n.id} x={n.x - 0.35} y={n.z - 0.35} width={0.7} height={0.7} fill="var(--color-txt-3)" />
        ))}
      {agvs.map((a) => (
        <circle key={a.id} cx={a.pos.x} cy={a.pos.z} r={0.55} fill={AGV_COLOR[a.state]} stroke="var(--color-ink-1)" strokeWidth={0.15}>
          <title>{a.name}</title>
        </circle>
      ))}
    </svg>
  );
}
```

`FlowPilotArea.tsx`:

```tsx
import { useState } from 'react';
import type { FrameTransport, Network } from '../../nexus/types';
import { usePlantQuery } from '../../nexus/usePlantQuery';
import { hrefOf } from '../../shell/route';
import { sceneOf, send, twin, useFactory } from '../../store/factoryStore';
import type { SceneAgv } from '../../twin/types';
import { nf } from '../../ui/format';
import { control, DataTable, Field, Section, StateTag, type Column } from '../../ui/kit';
import { AgvDetails } from '../../ui/panels/DetailPanel';
import { Button } from '../../ui/primitives';
import { NetworkMap } from './NetworkMap';
import { edgeAction } from './network';

const agvColumns: Column<SceneAgv>[] = [
  { key: 'id', label: 'AGV', value: (a) => a.id, mono: true, width: '88px' },
  { key: 'state', label: 'Zustand', value: (a) => a.state, render: (a) => <StateTag kind="agv" status={a.state} />, width: '140px' },
  { key: 'battery', label: 'Akku', value: (a) => a.battery, render: (a) => `${nf(a.battery)} %`, align: 'right', mono: true, width: '80px' },
  { key: 'transport', label: 'Transport', value: (a) => a.transportId, render: (a) => a.transportId ?? '–', mono: true },
  { key: 'blocked', label: 'Wartet auf', value: (a) => a.blockedBy, render: (a) => a.blockedBy ?? '–', mono: true },
];

const transportColumns: Column<FrameTransport>[] = [
  { key: 'id', label: 'Transport', value: (t) => t.id, mono: true, width: '112px' },
  { key: 'kind', label: 'Art', value: (t) => t.kind, mono: true, width: '88px' },
  { key: 'status', label: 'Stand', value: (t) => t.status, mono: true },
  { key: 'agv', label: 'AGV', value: (t) => t.agvId, render: (t) => t.agvId ?? '–', mono: true },
  { key: 'route', label: 'Von → Nach', value: (t) => `${t.from} → ${t.to}`, mono: true },
  {
    key: 'order',
    label: 'Auftrag',
    value: (t) => t.orderId,
    mono: true,
    render: (t) =>
      t.orderId ? (
        <a href={hrefOf('smartfactory', t.orderId)} className="underline decoration-line-2 underline-offset-4">
          {t.orderId}
        </a>
      ) : (
        '–'
      ),
  },
];

/** The fleet, open transports and the path network with blocking; the AGV of the link opens its details beside. */
export function FlowPilotArea({ agvId }: { agvId: string | null }) {
  useFactory((s) => s.scene);
  const agvs = useFactory((s) => sceneOf(s).agvs);
  const transports = twin.latest?.transports ?? [];
  const { data: network, reload } = usePlantQuery<Network>({ query: 'network' });
  const [edgeId, setEdgeId] = useState<string | null>(null);
  const edge = network?.edges.find((e) => e.id === edgeId) ?? null;
  const agv = agvId ? agvs.find((a) => a.id === agvId) ?? null : null;
  return (
    <div className="grid grid-cols-12 gap-x-8 gap-y-10 px-4 py-6 lg:px-6 lg:py-8">
      <Section title="Flotte" className="col-span-12 xl:col-span-8">
        {agvId && agvs.length > 0 && !agv && (
          <p role="alert" className="mb-3 text-[13px] text-txt-1">
            Ein AGV <span className="font-mono">{agvId}</span> gibt es nicht.
          </p>
        )}
        <DataTable
          label="AGV-Flotte"
          rows={agvs}
          columns={agvColumns}
          rowKey={(a) => a.id}
          onRowClick={(a) => {
            window.location.hash = hrefOf('flowpilot', a.id);
          }}
          empty="Noch keine Daten der Anlage."
        />
      </Section>
      <Section title={agv ? agv.id : 'Auswahl'} className="col-span-12 xl:col-span-4">
        {agv ? <AgvDetails a={agv} /> : <p className="text-[13px] text-txt-3">Wähle ein AGV für Akku, Auftrag und Befehle.</p>}
      </Section>
      <Section title="Wegenetz" className="col-span-12 xl:col-span-8">
        {network ? (
          <NetworkMap network={network} agvs={agvs} selected={edgeId} onSelect={setEdgeId} />
        ) : (
          <p className="text-[13px] text-txt-3">Wegenetz wird geladen …</p>
        )}
      </Section>
      <Section title="Kante sperren" className="col-span-12 xl:col-span-4">
        {network && (
          <div className="flex flex-col gap-3">
            <Field label="Kante">
              <select className={control} value={edgeId ?? ''} onChange={(e) => setEdgeId(e.target.value || null)}>
                <option value="">Kante wählen</option>
                {network.edges.map((e) => (
                  <option key={e.id} value={e.id}>
                    {e.id} ({e.from} → {e.to}){e.blocked ? ', gesperrt' : ''}
                  </option>
                ))}
              </select>
            </Field>
            {edge && (
              <Button
                onClick={async () => {
                  await send(edgeAction(edge).command);
                  reload();
                }}
              >
                {edgeAction(edge).label}
              </Button>
            )}
            <p className="text-[12px] leading-relaxed text-txt-3">
              Eine gesperrte Kante plant FlowPilot um. Ohne gültige Route wartet der Transport und meldet route.blocked im Event Monitor.
            </p>
          </div>
        )}
      </Section>
      <Section title="Offene Transporte" className="col-span-12">
        <DataTable label="Offene Transporte" rows={transports} columns={transportColumns} rowKey={(t) => t.id} empty="Keine offenen Transporte." />
      </Section>
    </div>
  );
}
```

`Shell.tsx`:

```tsx
    case 'flowpilot':
      return <FlowPilotArea agvId={param} />;
```

`areas.ts` (`flowpilot` → `comesWith: null`), `Placeholder.tsx` (`flowpilot` aus `MEANWHILE`).

- [ ] **Step 4: Tests, Typprüfung, Sichtprüfung**

Run: `cd FactoryTwin && npx vitest run && npx tsc -b --noEmit`
Expected: PASS.

Sichtprüfung: Karte zeigt das Netz und sechs AGV-Punkte, die sich bei laufender Simulation bewegen; eine Kante in der Liste wählen → in der Karte hervorgehoben; „Sperren“ → Kante rot gestrichelt, Knopf heisst „Freigeben“; Klick auf ein AGV → `#/flowpilot/AGV-…` mit Akku und Befehlen.

- [ ] **Step 5: Commit**

```bash
git add FactoryTwin/src
git commit -m "feat(ui): FlowPilot mit Flotte, Transporten und Wegenetz mit Sperren"
```

---

### Task 7: Analytics

**Files:**
- Create: `FactoryTwin/src/areas/analytics/series.ts`, `FactoryTwin/src/areas/analytics/AnalyticsArea.tsx`
- Modify: `FactoryTwin/src/shell/Shell.tsx`, `FactoryTwin/src/shell/areas.ts` (`analytics`: `comesWith: null`), `FactoryTwin/src/shell/Placeholder.tsx` (`analytics` aus `MEANWHILE`)
- Test: `FactoryTwin/src/areas/analytics/series.test.ts`

**Interfaces:**
- Consumes: `usePlantQuery<Kpis>` (`kpis`), `usePlantQuery<KpiBucket[]>` (`kpiSeries`), `BarList`, `StatusStack`, `Columns`, `Stat` (Kit).
- Produces:
  - `WINDOWS: { minutes: number; label: string }[]` (60 „1 h“, 480 „8 h“, 1440 „24 h“)
  - `SLOTS = 12`
  - `seriesSlots(buckets: KpiBucket[], pick: (b: KpiBucket) => number | null, slots = SLOTS): (number | null)[]` (immer `slots` Werte; fehlende frühe Abschnitte vorn als `null`; nicht endliche Werte als `null`)

- [ ] **Step 1: Failing test schreiben**

`series.test.ts`:

```ts
import { describe, expect, it } from 'vitest';
import type { KpiBucket } from '../../nexus/types';
import { seriesSlots } from './series';

const bucket = (toMin: number, throughputPerHour: number, oee: number | null): KpiBucket => ({
  fromMs: Math.max(0, (toMin - 10) * 60_000),
  toMs: toMin * 60_000,
  throughputPerHour,
  onTimeRate: 1,
  avgLeadTimeS: 0,
  oee,
});

describe('analytics series', () => {
  it('seriesWithoutDataLeavesGaps', () => {
    const buckets = [bucket(5, 0, null), bucket(15, 6, 0.4), bucket(25, 12, 0.55)];
    expect(seriesSlots(buckets, (b) => b.throughputPerHour, 5)).toEqual([null, null, 0, 6, 12]);
    expect(seriesSlots(buckets, (b) => b.oee, 5)).toEqual([null, null, null, 0.4, 0.55]);
    expect(seriesSlots([], (b) => b.oee, 3)).toEqual([null, null, null]);
    expect(seriesSlots([bucket(5, Number.NaN, null)], (b) => b.throughputPerHour, 1)).toEqual([null]);
  });
});
```

- [ ] **Step 2: Test laufen lassen, rot**

Run: `cd FactoryTwin && npx vitest run src/areas/analytics`
Expected: FAIL, `./series` fehlt.

- [ ] **Step 3: Umsetzen**

`series.ts`:

```ts
import type { KpiBucket } from '../../nexus/types';

export const WINDOWS = [
  { minutes: 60, label: '1 h' },
  { minutes: 480, label: '8 h' },
  { minutes: 1440, label: '24 h' },
];

export const SLOTS = 12;

/** Always `slots` values, oldest first; sections the run has not reached yet stay null at the front. */
export function seriesSlots(buckets: KpiBucket[], pick: (b: KpiBucket) => number | null, slots = SLOTS): (number | null)[] {
  const values = buckets.slice(-slots).map((b) => {
    const v = pick(b);
    return v !== null && Number.isFinite(v) ? v : null;
  });
  return [...Array<null>(slots - values.length).fill(null), ...values];
}
```

`AnalyticsArea.tsx`:

```tsx
import { useState } from 'react';
import type { KpiBucket, Kpis } from '../../nexus/types';
import { usePlantQuery } from '../../nexus/usePlantQuery';
import { hrefOf } from '../../shell/route';
import { clock, duration, nf, pct } from '../../ui/format';
import { BarList, Columns, Section, Stat, StatusStack } from '../../ui/kit';
import { Segmented } from '../../ui/primitives';
import { SLOTS, WINDOWS, seriesSlots } from './series';

/** Figures from the core's events and state intervals (Spec 10); the interface only formats and draws them. */
export function AnalyticsArea() {
  const [minutes, setMinutes] = useState(60);
  const { data: k } = usePlantQuery<Kpis>({ query: 'kpis', windowMinutes: minutes }, 2_000);
  const { data: series } = usePlantQuery<KpiBucket[]>({ query: 'kpiSeries', bucketMinutes: minutes / SLOTS, buckets: SLOTS }, 5_000);
  const flow = k ? k.throughputPerHour > 0 : false;
  const machines = k ? Object.entries(k.machines) : [];
  const labels = Array.from({ length: SLOTS }, (_, i) => {
    const b = series?.[series.length - SLOTS + i];
    return b ? clock(b.toMs / 1000, false) : '';
  });
  return (
    <div className="flex flex-col gap-10 px-4 py-6 lg:px-6 lg:py-8">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <p className="text-[13px] text-txt-2">Fenster der Auswertung, bis jetzt</p>
        <Segmented ariaLabel="Zeitfenster" value={minutes} onChange={setMinutes} options={WINDOWS.map((w) => ({ value: w.minutes, label: w.label }))} />
      </div>

      <div className="grid grid-cols-2 gap-x-8 gap-y-8 lg:grid-cols-4">
        <Stat label="Durchsatz" value={k ? nf(k.throughputPerHour, k.throughputPerHour < 10 ? 1 : 0) : '–'} unit="Aufträge/h" />
        <Stat label="Termintreue" value={k && flow ? pct(k.onTimeRate) : '–'} />
        <Stat label="Durchlaufzeit" value={k && flow ? duration(k.avgLeadTimeS) : '–'} hint="Mittel" />
        <Stat label="Durchlaufzeit P90" value={k && flow ? duration(k.p90LeadTimeS) : '–'} hint="9 von 10 Aufträgen schneller" />
      </div>

      <Section title="OEE je Maschine" action={k?.bottleneckMachineId && flow ? <span className="text-[12px] text-txt-2">Engpass <span className="font-mono text-txt-1">{k.bottleneckMachineId}</span></span> : undefined}>
        <BarList
          label="OEE je Maschine"
          max={1}
          rows={machines.map(([id, m]) => ({
            id,
            label: (
              <a href={hrefOf('machines', id)} className="font-mono hover:underline">
                {id}
              </a>
            ),
            value: Number.isFinite(m.oee) ? m.oee : null,
            text: `${pct(m.oee)} (V ${pct(m.availability)} · L ${pct(m.performance)} · Q ${pct(m.quality)})`,
          }))}
        />
        <p className="mt-2 text-[12px] text-txt-3">OEE = Verfügbarkeit (V) × Leistung (L) × Qualität (Q).</p>
      </Section>

      <Section title="Zeit je Zustand">
        <ul className="flex flex-col">
          {machines.map(([id, m]) => (
            <li key={id} className="grid grid-cols-[80px_minmax(0,1fr)_64px] items-center gap-3 border-b border-line py-2 last:border-b-0">
              <span className="font-mono text-[12px] text-txt-1">{id}</span>
              <StatusStack timeInStatusMs={m.timeInStatusMs} label={`Zeit je Zustand ${id}`} />
              <span className="text-right font-mono text-[12px] text-txt-2 tnum" title="Auslastung">
                {pct(m.utilization)}
              </span>
            </li>
          ))}
        </ul>
      </Section>

      <div className="grid grid-cols-1 gap-x-8 gap-y-10 lg:grid-cols-2">
        <Section title="Durchsatz im Verlauf">
          <Columns label="Durchsatz je Abschnitt" values={seriesSlots(series ?? [], (b) => b.throughputPerHour)} labels={labels} format={(v) => `${nf(v, 1)}/h`} />
        </Section>
        <Section title="OEE im Verlauf">
          <Columns label="OEE je Abschnitt" values={seriesSlots(series ?? [], (b) => b.oee)} labels={labels} format={(v) => pct(v)} />
        </Section>
      </div>
    </div>
  );
}
```

`Shell.tsx`:

```tsx
    case 'analytics':
      return <AnalyticsArea />;
```

`areas.ts` (`analytics` → `comesWith: null`), `Placeholder.tsx` (`analytics` aus `MEANWHILE`). Danach stehen in `MEANWHILE` nur noch `routemind` und `scenarios`.

- [ ] **Step 4: Tests, Typprüfung, Sichtprüfung**

Run: `cd FactoryTwin && npx vitest run && npx tsc -b --noEmit`
Expected: PASS.

Sichtprüfung: direkt nach dem Start „–“ und leere Säulenplätze; nach Tempo 25 und zwei Minuten füllen sich OEE-Balken, Zustandsbalken und die letzten Säulen; Fenster 24 h → links leere Plätze, kein `NaN`.

- [ ] **Step 5: Commit**

```bash
git add FactoryTwin/src
git commit -m "feat(ui): Analytics mit Kennzahlen, OEE, Zeit je Zustand und Verlauf"
```

---

### Task 8: Feinschliff, beide Betriebsarten, README

**Files:**
- Modify: Dateien aus Task 3–7 nach den Befunden; `FactoryTwin/README.md` (Tabelle „Bereiche“, Abschnitt „Aufbau“)
- Test: bestehende Suiten

- [ ] **Step 1: Design-Prüfung** mit dem Skill `impeccable` (`critique` der fünf neuen Bereiche gegen die Richtung aus M2-B). Befunde, die Spec 14 oder den Global Constraints widersprechen, beheben; Geschmacksfragen ohne Spec-Bezug im Ledger als „minor (deferred)“.

- [ ] **Step 2: Barrierefreiheit:** axe-core im Browser-Pane auf `#/smartfactory`, `#/flowpilot`, `#/machines`, `#/warehouse`, `#/analytics` (`await import('/node_modules/axe-core/axe.min.js'); (await axe.run()).violations`); `serious` und `critical` beheben. Per Tastatur: Auftrag anlegen, Kante sperren, Wareneingang buchen, Maschine wählen.

- [ ] **Step 3: Sichtprüfung beider Betriebsarten** bei 1440 × 900, 1024 × 768 und 390 × 844: jede der fünf Seiten ohne seitliches Überlaufen (Tabellen scrollen in sich), Gantt und Karte bei 390 lesbar, Befehle in beiden Betriebsarten wirksam (Server: `nexus-server` und `twin-server` aus `.claude/launch.json`). Ergebnisse mit Breite und Bereich im Ledger.

- [ ] **Step 4: README** – Tabelle „Bereiche“: SmartFactory, FlowPilot, Machines, Warehouse, Analytics auf „fertig“ mit einem Halbsatz Inhalt; „Aufbau“ um `areas/smartfactory`, `flowpilot`, `machines`, `warehouse`, `analytics` und `ui/kit/charts` ergänzen; Satz zu den neuen Abfragen (`products`, `schedule`, `order`, `warehouse`, `network`, `kpiSeries`).

- [ ] **Step 5: Ganze Prüfung und Commit**

Run: `cd FactoryTwin && npx vitest run && npx tsc -b --noEmit && npx vite build`, dann im Wurzelverzeichnis `./mvnw -B verify` (vorher laufende `java`- und eingebettete `postgres`-Prozesse aus der Sichtprüfung beenden; den Exitcode von Maven selbst prüfen, nicht den eines folgenden Befehls).
Expected: alles grün.

```bash
git add FactoryTwin docs
git commit -m "feat(ui): Feinschliff der fünf Bereiche und README"
```

---

## Nicht in diesem Plan

- **Scenario Lab:** M3. **RouteMind:** M4.
- **Reservierungen je Auftrag** als eigene Tabelle (`GET /reservations`): die Materialien je Schritt im Auftragsdetail zeigen Bedarf und Lieferung; eine Reservierungsliste kommt, wenn Scenario Lab Engpässe auslöst (M3).
- **Messung 60 Bilder pro Sekunde bei 25-fachem Tempo:** M3 mit Playwright.
- **Auftragsliste über die letzten 20 fertigen hinaus:** mit wachsendem Lauf würde jede Abfrage grösser; heute reicht der Frame (offen und 20 zuletzt fertig).

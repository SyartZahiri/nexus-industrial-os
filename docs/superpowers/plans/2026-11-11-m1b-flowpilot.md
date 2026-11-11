# M1-B FlowPilot – Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** FlowPilot als Kernmodul: Lager und Reservierung, Transportaufträge, AGV-Flotte mit Disposition, A*-Routing, Akku, Störungen und Ersatz, dazu PostgreSQL-Speicher, REST-API und Browser-Selbsttest.

**Architecture:** Die Fachlogik liegt in `FlowPilot/` (reines Java, läuft im Browser). Eine Fassade `FlowPilot` bündelt alle Befehle und den Simulationsschritt `step(dtMs)`, jeder Befehl läuft in einer eigenen `UnitOfWork`. Geschäftszustand (Bestand, Reservierungen, Transporte, AGV-Status) geht über Speicher-Ports, Bewegungsdaten (Position, Geschwindigkeit, Route) bleiben im Arbeitsspeicher. `platform/app` liefert JDBC-Speicher, Spring-Verdrahtung und REST.

**Tech Stack:** Java 25, Maven, Spring Boot 4.1.2, PostgreSQL 18 (embedded), TeaVM 0.16.0, JUnit 5, AssertJ.

**Spec:** `docs/superpowers/specs/2026-11-10-nexus-industrial-os-design.md` (Revision 2), Abschnitte 2a, 5, 6, 7, 8, 9, 12, 13. Voraussetzung: `2026-11-11-m1bc0-vorbereitung.md` ist auf `main` umgesetzt.

**Form:** Schnittstellen, Verhalten und Tests sind festgelegt; wörtlicher Code nur bei heiklen Stellen (A*, Reservierung, Folgeabstand).

## Entscheidungen dieses Plans

- **Ladungsträger statt Einzelteile.** Ein Transport bewegt einen Behälter mit `quantity` Einheiten eines Materials oder eines Auftrags-Loses. Ein AGV trägt genau einen Behälter (`agv.capacity = 1`). Das passt zur Losfertigung in M1-C. Die 3D-Ansicht kann den Behälter in M2 als Teile darstellen.
- **Stammdaten liegen zweimal vor, aber geprüft gleich.** Der Browser hat keine Datenbank, deshalb stehen FlowPilots Stammdaten zusätzlich als Java-Klasse im Kern. Ein Test im Server vergleicht sie Feld für Feld mit `V2`/`V3`. Weicht eine Seite ab, schlägt der Build fehl.
- **Nachbestellungen unterwegs** (Lieferung in Arbeit) sind Bewegungsdaten und liegen im Arbeitsspeicher; ein Neustart des Servers beginnt ohnehin einen neuen Simulationslauf.
- **Zufällige AGV-Störungen** kommen in M1-D mit der Simulationsschleife. Hier gibt es ausgelöste Störungen und Reparatur nach Zeit.

## Global Constraints

- Alles aus `2026-11-10-m1a2-portabler-kern.md`, Abschnitt Global Constraints, gilt weiter (Kern ohne Spring/JDBC, `SeededRandom`, `SimTime`, Zeichenketten-IDs, Problem-JSON).
- Determinismus im Kern (Spec 2a): keine Iteration über `HashMap`/`HashSet` mit Enum- oder Record-Schlüsseln, kein `Set.of`/`Map.of` dort, wo iteriert wird; Listen immer sortiert (AGVs nach ID, Aufträge nach Priorität, Zeit, ID).
- Physik-Werte aus `FactoryTwin/src/sim/config.ts` (`AGV_PHYSICS`): `maxSpeed 2.2` m/s, `accel 1.0` m/s², `loadTime 2.2` s, `drainPerMeter 0.022` %/m, `drainIdle 0.004` %/s, `chargeRate 0.5` %/s, `lowBattery 25`, `minForTask 18`, `resumeFromCharge 40`, `safetyDistance 1.75` m. Störungsdauern aus `FAULT_TYPES`: `NAV` 60 s, `COMM` 60 s.
- Migrationen nur `V100`..`V199`. Code nur unter `FlowPilot/` und `platform/app/src/{main,test}/java/io/nexus/app/flowpilot/`; einzige weitere Datei: `FlowPilot/src/main/java/io/nexus/flowpilot/FlowPilotSelfTest.java`.
- Ports anderer Module über `ObjectProvider` beim ersten Aufruf beziehen (Regel aus M1-BC0). FlowPilot braucht hier keine.
- Commits enden mit `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.

## Review Focus

1. **Zwei AGVs am selben Ort.** Über einen langen Lauf mit vielen Aufträgen und Störungen unterschreiten zwei fahrende AGVs nie den Folgeabstand, und kein AGV hat zwei aktive Transporte. Invariantentest in Task 6.
2. **Reservierung über Lagerplatzgrenzen.** 20 Stück liegen auf RAW-A, 20 auf RAW-B, angefragt sind 30: Ergebnis ist `Shortage` mit `available = 20`, nichts wird reserviert. Test in Task 4.
3. **Fertiglager voll bei Einlagerung.** Der Transport bleibt in `UNLOADING`, meldet einmal eine Warnung und schliesst ab, sobald Platz ist; nichts geht verloren. Test in Task 6.
4. **Ausfall genau beim Beladen.** Ein AGV fällt in `LOADING` aus, nachdem die Reservierung verbraucht ist: Der Ersatz holt den Behälter ab, der Bestand wird nicht ein zweites Mal verbraucht. Test in Task 7.
5. **Gesperrte Kante macht ein Ziel unerreichbar.** Der Auftrag wartet mit einem einzigen `route.blocked`, statt bei jedem Schritt neu zu melden, und fährt nach dem Entsperren los. Test in Task 7.

---

### Task 1: Stammdaten im Kern und Gleichheitsprüfung mit der Datenbank

**Files:**
- Create: `FlowPilot/src/main/java/io/nexus/flowpilot/master/FlowPilotMasterData.java`
- Create: `FlowPilot/src/main/java/io/nexus/flowpilot/master/PathData.java` (generiert)
- Create: `FlowPilot/src/test/java/io/nexus/flowpilot/master/FlowPilotMasterDataTest.java`
- Create: `platform/app/src/test/java/io/nexus/app/flowpilot/MasterDataMatchesDatabaseTest.java`

**Interfaces (Produces):**

```java
public final class FlowPilotMasterData {
  public record Material(String id, String name, String kind, int reorderPoint, int reorderQty) {}
  public record StorageLocation(String id, String name, String zone, String dockNode, int capacity) {}
  public record StockSeed(String materialId, String locationId, int onHand) {}
  public record Node(String id, double x, double z, boolean dock) {}
  public record Edge(String id, String from, String to, double length) {}
  public record AgvSeed(String id, String name, String nodeId) {}
  public record Charger(String id, String nodeId) {}
  /** Where AGVs deliver to (in) and pick up from (out) a machine; out is null for conveyor handover. */
  public record MachineDock(String machineId, String inNode, String outNode) {}
  public record ConveyorDock(String conveyorId, String node) {}

  public static List<Material> materials();          // order of V3
  public static List<StorageLocation> locations();
  public static List<StockSeed> initialStock();
  public static List<Node> nodes();                   // from PathData, order of V2
  public static List<Edge> edges();
  public static List<AgvSeed> agvs();
  public static List<Charger> chargers();
  public static List<MachineDock> machineDocks();
  public static List<ConveyorDock> conveyorDocks();
}
```

`PathData` wird einmalig mit einem Skript im Scratchpad aus `V2__path_graph.sql` erzeugt (Zeilen `('<id>', x, z, dock)` und `('<id>', '<from>', '<to>', length)` in Java-Literale umsetzen). Die Datei beginnt mit dem Kommentar `// Generated from V2__path_graph.sql; MasterDataMatchesDatabaseTest keeps both equal.`

**Tests:**
- `FlowPilotMasterDataTest`: 92 Knoten, 111 Kanten, jede Kante verweist auf vorhandene Knoten, jeder Dock-Knoten aus Lagerplätzen, Maschinen, Bändern und Ladestationen existiert; jeder Knoten hat eine ein- und eine ausgehende Kante.
- `MasterDataMatchesDatabaseTest` (Spring, embedded): für jede Liste dieselben Zeilen in derselben Reihenfolge wie `select … order by id` (Java-Listen vor dem Vergleich ebenfalls nach ID sortieren), Doubles mit Toleranz 1e-9. Maschinen-Docks gegen `machine.dock_node`/`out_dock_node`, Band-Docks gegen `conveyor.dock_node`, Anfangsbestand gegen `stock` (frische Datenbank).

- [ ] Tests schreiben, Lauf rot.
- [ ] Skript ausführen, Klassen schreiben, `./mvnw -B verify` grün.
- [ ] Commit `feat(flowpilot): Stammdaten im Kern, geprüft gegen die Datenbank`.

---

### Task 2: Wegegraph und A*

**Files:**
- Create: `FlowPilot/src/main/java/io/nexus/flowpilot/routing/PathGraph.java`, `Route.java`
- Test: `FlowPilot/src/test/java/io/nexus/flowpilot/routing/PathGraphTest.java`

**Interfaces (Produces):**

```java
public record Route(List<String> nodes, double length) {
  public static final Route NONE = new Route(List.of(), Double.POSITIVE_INFINITY);
  public boolean found() { return !nodes.isEmpty(); }
}

public final class PathGraph {
  public PathGraph(List<FlowPilotMasterData.Node> nodes, List<FlowPilotMasterData.Edge> edges);
  public void setBlocked(String edgeId, boolean blocked);      // unknown id: DomainException NOT_FOUND
  public Set<String> blockedEdges();                            // sorted (TreeSet view)
  /** Shortest route avoiding blocked edges and the given nodes (except from and to). */
  public Route route(String from, String to, Set<String> avoidNodes);
  public FlowPilotMasterData.Node node(String id);
  public FlowPilotMasterData.Edge edge(String from, String to);
}
```

A* (wörtlich; Heuristik euklidisch, zulässig, weil jede Kantenlänge ≥ Luftlinie ist; Gleichstand nach Knoten-ID, damit das Ergebnis deterministisch ist):

```java
public Route route(String from, String to, Set<String> avoidNodes) {
  if (from.equals(to)) return new Route(List.of(from), 0);
  Node goal = node(to);
  Map<String, Double> g = new HashMap<>();          // lookups only, never iterated
  Map<String, String> prev = new HashMap<>();
  record Open(String id, double f, double g) {}
  PriorityQueue<Open> open = new PriorityQueue<>(
      Comparator.comparingDouble(Open::f).thenComparing(Open::id));
  g.put(from, 0.0);
  open.add(new Open(from, heuristic(node(from), goal), 0));
  while (!open.isEmpty()) {
    Open cur = open.poll();
    if (cur.g() > g.get(cur.id())) continue;        // stale entry
    if (cur.id().equals(to)) return rebuild(prev, from, to, cur.g());
    for (Edge e : outgoing(cur.id())) {             // list sorted by target id
      if (blocked.contains(e.id())) continue;
      if (avoidNodes.contains(e.to()) && !e.to().equals(to)) continue;
      double ng = cur.g() + e.length();
      if (ng < g.getOrDefault(e.to(), Double.POSITIVE_INFINITY)) {
        g.put(e.to(), ng);
        prev.put(e.to(), cur.id());
        open.add(new Open(e.to(), ng + heuristic(node(e.to()), goal), ng));
      }
    }
  }
  return Route.NONE;
}
```

**Tests:**
- `aStarFindsTheSameLengthAsDijkstraForEveryPairOfDocks`: Dijkstra als Referenz im Test (eigene einfache Implementierung), alle Dock-Paare, Längen gleich (Toleranz 1e-9).
- `blockedEdgeForcesTheDetourAndUnblockingRestoresTheShortRoute`
- `unreachableTargetGivesNone`: alle eingehenden Kanten eines Docks sperren.
- `avoidedNodeIsNotPassedButMayBeTheTarget`
- `routeIsDeterministic`: zweimal derselbe Aufruf, identische Knotenliste.
- `unknownEdgeIdIsNotFound`

- [ ] Tests schreiben, Lauf rot.
- [ ] Umsetzen, `./mvnw -B -pl FlowPilot -am test` grün.
- [ ] Commit `feat(flowpilot): Wegegraph mit A* und Sperrungen`.

---

### Task 3: Speicher-Ports, Speicher-Implementierung, JDBC

**Files:**
- Create: `FlowPilot/src/main/java/io/nexus/flowpilot/store/StockStore.java`, `TransportStore.java`, `FleetStore.java`, `StockLine.java`, `Reservation.java`, `TransportOrder.java`, `AgvRecord.java`
- Create: `FlowPilot/src/main/java/io/nexus/flowpilot/store/memory/MemoryStockStore.java`, `MemoryTransportStore.java`, `MemoryFleetStore.java`
- Create: `FlowPilot/src/test/java/io/nexus/flowpilot/store/StoreContract.java` (abstrakt), `MemoryStoreTest.java`
- Create: `platform/app/src/main/java/io/nexus/app/flowpilot/JdbcStockStore.java`, `JdbcTransportStore.java`, `JdbcFleetStore.java`
- Create: `platform/app/src/test/java/io/nexus/app/flowpilot/JdbcStoreTest.java` (erbt `StoreContract`)

**Interfaces (Produces):**

```java
public record StockLine(String materialId, String locationId, int onHand, int reserved) {
  public int available() { return onHand - reserved; }
}
public record Reservation(String id, String requestId, String orderId, String materialId, String locationId,
                          int quantity, Status status, long createdAtMs) {
  public enum Status { ACTIVE, RELEASED, CONSUMED }
}
public record TransportOrder(String id, String requestId, TransportKind kind, String orderId, String materialId,
                             int quantity, Place from, String fromNode, Place to, String toNode, Priority priority,
                             TransportStatus status, String agvId, String reservationId, long createdAtMs, int version) {}
public record AgvRecord(String id, String name, AgvStatus status, String nodeId, double battery, boolean enabled,
                        int version) {}

public interface StockStore {
  /** Locks every stock line of the material for the rest of the unit of work; ordered by location id. */
  List<StockLine> lockMaterial(String materialId);
  void saveLine(StockLine line);
  List<StockLine> lines();                                   // ordered by material, location
  List<Reservation> reservationsOfRequest(String requestId); // ordered by material id
  Optional<Reservation> reservation(String id);
  void saveReservation(Reservation r);                       // insert or update status
  List<Reservation> reservationsOfOrder(String orderId);
}
public interface TransportStore {
  void insert(TransportOrder t);
  /** Fails with DomainException CONFLICT if the stored version differs; stores version + 1. */
  TransportOrder update(TransportOrder t);
  Optional<TransportOrder> byId(String id);
  Optional<TransportOrder> byRequest(String requestId);
  /** Not DELIVERED and not CANCELLED; ordered by priority (EILIG first), createdAtMs, id. */
  List<TransportOrder> open();
  List<TransportOrder> all();                                // ordered by id
}
public interface FleetStore {
  List<AgvRecord> agvs();                                    // ordered by id
  AgvRecord save(AgvRecord agv);                             // optimistic like TransportStore.update
  Set<String> blockedEdges();
  void setEdgeBlocked(String edgeId, boolean blocked);
}
```

Speicher-Implementierungen registrieren jeden Schreibvorgang mit `MemoryUnitOfWork.onRollback`; `lockMaterial` ist dort ein normales Lesen (ein Thread). Die Speicher-Implementierung startet mit den Stammdaten aus Task 1 (Anfangsbestand, AGVs an ihren Ladeplätzen).

JDBC: `lockMaterial` = `select … from stock where material_id = :m order by location_id for update`. Optimistic Locking: `update … set …, version = version + 1 where id = :id and version = :v`, null Zeilen → `DomainException.conflict`. `V100__flowpilot.sql` nur, falls eine Spalte fehlt; erwartet wird, dass `V1` reicht.

**Tests (`StoreContract`, je Port):**
- Bestand: Anfangsbestand gelesen, Zeile gespeichert und wieder gelesen, Rollback einer Einheit nimmt Speichern zurück.
- Reservierung: speichern, nach Anfrage und Auftrag finden, Statuswechsel.
- Transport: einfügen, `open()`-Reihenfolge (EILIG vor HOCH vor NORMAL, dann Zeit, dann ID), `update` mit veralteter Version → `CONFLICT`, zweiter Einfügeversuch mit derselben `requestId` → Ausnahme.
- Flotte: sechs AGVs an `D_CH1`..`D_CH6`, speichern mit Versionsprüfung, Kantensperren setzen und lesen.

- [ ] Tests schreiben, Lauf rot.
- [ ] Umsetzen, `./mvnw -B verify` grün (Speicher und PostgreSQL bestehen denselben Vertrag).
- [ ] Commit `feat(flowpilot): Speicher-Ports für Bestand, Transporte und Flotte`.

---

### Task 4: Material und Reservierung

**Files:**
- Create: `FlowPilot/src/main/java/io/nexus/flowpilot/material/MaterialServiceImpl.java`, `Replenishment.java`
- Test: `FlowPilot/src/test/java/io/nexus/flowpilot/material/MaterialServiceTest.java`
- Test: `platform/app/src/test/java/io/nexus/app/flowpilot/ConcurrentReservationTest.java`

**Interfaces:**
- Consumes: `MaterialService` (contracts), `StockStore`, `EventPublisher`, `IdSource`, `UnitOfWork`, `SimTime`.
- Produces:
  - `MaterialServiceImpl implements MaterialService` plus
  - `void consume(String reservationId)`: `ACTIVE → CONSUMED`, `onHand -= q`, `reserved -= q`; schon `CONSUMED` ist ein No-op.
  - `void receive(String materialId, String locationId, int quantity)`: Wareneingang; Kapazität überschritten → `DomainException.conflict("Lagerplatz … voll")`, Menge ≤ 0 → `invalid`.
  - `Replenishment.step(long nowMs)`: prüft je Rohmaterial `verfügbar + unterwegs < reorderPoint` → bestellt `reorderQty` mit Lieferzeit 300 s Simulationszeit; ankommende Lieferungen gehen per `receive` auf den Lagerplatz mit dem meisten freien Platz in der passenden Zone; passt nichts, wartet die Lieferung (Warnung einmal).

Reservierung (wörtlich, ohne Ereignisse):

```java
public ReservationResult reserve(ReservationRequest request) {
  validate(request);                                        // lines non-empty, quantity > 0, one line per material
  List<Reservation> existing = stock.reservationsOfRequest(request.requestId());
  if (!existing.isEmpty()) return success(request.requestId(), existing);   // idempotent
  List<Line> lines = new ArrayList<>(request.lines());
  lines.sort(Comparator.comparing(Line::materialId));       // fixed lock order: no deadlock between requests
  List<StockLine> chosen = new ArrayList<>();
  for (Line line : lines) {
    StockLine best = null;                                   // one location must cover the line on its own
    for (StockLine l : stock.lockMaterial(line.materialId())) {
      if (best == null || l.available() > best.available()) best = l;
    }
    int available = best == null ? 0 : best.available();
    if (available < line.quantity()) {
      return new Shortage(line.materialId(), line.quantity(), available);   // nothing written, no id taken
    }
    chosen.add(best);
  }
  List<Reserved> made = new ArrayList<>();
  for (int i = 0; i < lines.size(); i++) {                   // ids only once every line is covered
    Line line = lines.get(i);
    StockLine l = chosen.get(i);
    var r = new Reserved(ids.next("RES"), line.materialId(), l.locationId(), line.quantity());
    stock.saveLine(new StockLine(l.materialId(), l.locationId(), l.onHand(), l.reserved() + r.quantity()));
    stock.saveReservation(new Reservation(r.reservationId(), request.requestId(), request.orderId(),
        r.materialId(), r.locationId(), r.quantity(), Reservation.Status.ACTIVE, time.nowMs()));
    made.add(r);
  }
  return new Success(request.requestId(), made);
}
```

`reserve` läuft in `work.inNewTransaction`, damit die Zeilensperren bis zum Ende der Reservierung gehalten werden. Ereignisse: `material.reserved` (Nutzlast `MaterialReserved`), `material.shortage` (`MaterialShortage`, ausserhalb der zurückgerollten Einheit veröffentlicht), `stock.changed` je geänderter Zeile, `material.released` bei `release`.

**Tests (`MaterialServiceTest`, Speicher):**
- `reservesAllLinesOrNothing`: GH200-Rohteil und -Kit; Kit zu knapp → `Shortage` für das Kit, Rohteil-Bestand unverändert.
- `sameRequestTwiceReservesOnce`
- `shortageIsNotStoredAndASecondCallRechecks`: nach `receive` gelingt dieselbe Anfrage.
- `aLineMustBeCoveredByOneLocation` (Review Focus 2).
- `releaseFreesTheQuantityAndIsIdempotent`
- `consumeTakesStockOnceAndIsIdempotent`
- `receiveRespectsLocationCapacity`
- `replenishmentOrdersOnceBelowReorderPointAndDeliversAfterLeadTime`
- `eventsCarryTypedPayloads`: Reihenfolge und Inhalte der Ereignisse einer Reservierung.
- `reservationNumbersHaveNoGapsAfterAShortage`

**Test (`ConcurrentReservationTest`, PostgreSQL):** 50 Threads reservieren je 1 `RM-FP12` (Bestand 30) mit eigener `requestId`: genau 30 `Success`, 20 `Shortage`, `reserved = 30`, keine Ausnahme ausser `Shortage`.

- [ ] Tests schreiben, Lauf rot.
- [ ] Umsetzen, Lauf grün.
- [ ] Commit `feat(flowpilot): Reservierung alles-oder-nichts, Verbrauch, Wareneingang, Nachbestellung`.

---

### Task 5: Transportaufträge

**Files:**
- Create: `FlowPilot/src/main/java/io/nexus/flowpilot/transport/TransportServiceImpl.java`, `PlaceResolver.java`
- Test: `FlowPilot/src/test/java/io/nexus/flowpilot/transport/TransportServiceTest.java`

**Interfaces:**
- Consumes: `TransportService` (contracts), `TransportStore`, `MaterialServiceImpl`, `IdSource`, `EventPublisher`.
- Produces:
  - `PlaceResolver.node(Place)`: `STORAGE` → `storage_location.dockNode`; `MACHINE_IN` → `inNode`; `MACHINE_OUT` → `outNode` (fehlt er: `DomainException.invalid("… übergibt an ein Band")`); `CONVEYOR` → Band-Dock; `CHARGER` → Ladeknoten; unbekannt → `NOT_FOUND`.
  - `TransportServiceImpl implements TransportService`. `requestTransport`: Validierung (Menge > 0; `SUPPLY` braucht `reservationId` einer aktiven Reservierung desselben Auftrags und Materials mit derselben Menge; `PUTAWAY` braucht Ziel `STORAGE` in Zone `FINISHED`), idempotent über `requestId`, ID `TO-nnnn`, Status `CREATED`, Ereignis `transport.created`.
  - `cancel`: nur `CREATED`, `ASSIGNED`, `TO_PICKUP` (sonst `IllegalTransitionException`); gibt das zugewiesene AGV frei; Reservierung bleibt (sie gehört dem Auftrag, SmartFactory gibt sie frei).
  - `redirect(transportId, Place)`: Ziel ändern, solange nicht `UNLOADING`/`DELIVERED`/`CANCELLED`; ein fahrendes AGV plant im nächsten Schritt neu (`route.replanned`).

**Tests:**
- `supplyRequestCreatesAnOrderWithResolvedNodes`
- `sameRequestIdReturnsTheSameTransport`
- `supplyWithoutMatchingReservationIsInvalid`
- `machineOutOfAnAssemblyCellIsInvalid`
- `cancelAfterPickupIsAnIllegalTransition`
- `redirectChangesTargetAndNode`
- `unknownPlaceIsNotFound`

- [ ] Tests schreiben, Lauf rot.
- [ ] Umsetzen, Lauf grün.
- [ ] Commit `feat(flowpilot): Transportaufträge mit fachlichen Orten`.

---

### Task 6: Flotte, Disposition, Fahren, Laden

**Files:**
- Create: `FlowPilot/src/main/java/io/nexus/flowpilot/fleet/Fleet.java`, `Vehicle.java`, `Dispatcher.java`, `AgvPhysics.java`
- Create: `FlowPilot/src/main/java/io/nexus/flowpilot/FlowPilot.java` (Fassade)
- Test: `FlowPilot/src/test/java/io/nexus/flowpilot/fleet/FleetTest.java`, `FleetInvariantTest.java`

**Interfaces (Produces):**

```java
/** Facade of the module. Every public method runs in its own unit of work and is synchronized. */
public final class FlowPilot {
  public static FlowPilot inMemory(EventPublisher events, MemoryUnitOfWork work, SimTime time, IdSource ids);
  public FlowPilot(StockStore stock, TransportStore transports, FleetStore fleet, EventPublisher events,
                   UnitOfWork work, SimTime time, IdSource ids);
  public MaterialService materials();
  public TransportService transports();
  /** One simulation step: replenishment, dispatch, movement, charging, repairs. */
  public void step(long dtMs);
  public List<VehicleView> vehicles();      // ordered by id; position, heading, speed, status, battery, load, route
  public void setAgvEnabled(String agvId, boolean enabled);
  public void sendToCharge(String agvId);
  public void injectFault(String agvId, String faultTypeId);   // Task 7
  public void setEdgeBlocked(String edgeId, boolean blocked);  // Task 7
  public PathGraph graph();
  public record VehicleView(String id, String name, AgvStatus status, double x, double z, double heading,
                            double speed, double battery, String transportId, List<String> route) {}
}
```

Verhalten je `step(dtMs)` (in dieser Reihenfolge, AGVs nach ID):
1. `Replenishment.step`.
2. Disposition: offene Aufträge in `CREATED` oder `FAILED` in der Reihenfolge von `TransportStore.open()`. Kandidaten: aktiv, nicht `FAULT`/`OFFLINE`, ohne Auftrag, Status `IDLE`, `CHARGING` oder `TO_CHARGE`, Akku ≥ `minForTask` + (Anfahrt + Fahrt) × `drainPerMeter` + 5. Gewählt wird die kürzeste Anfahrt (A*), Gleichstand nach ID. Ereignisse `transport.assigned` (bzw. `transport.reassigned` aus `FAILED`), `decision` mit allen Kandidaten und Grund („kürzeste Anfahrt 23,4 m"), `agv.state`. Findet ein Auftrag keine Route, meldet er `route.blocked` einmal bis zur nächsten Änderung.
3. Bewegung: Beschleunigung bis `maxSpeed` mit `accel`, Bremsen vor dem Zielknoten; Fahren entlang der Knotenliste. Folgeabstand (wörtlich):

```java
/** True if another vehicle is ahead within the safety distance. Lower id wins a mutual block (no deadlock). */
boolean blocked(Vehicle v, List<Vehicle> all) {
  double hx = Math.cos(v.heading), hz = Math.sin(v.heading);
  for (Vehicle o : all) {
    if (o == v) continue;
    double dx = o.x - v.x, dz = o.z - v.z;
    double ahead = dx * hx + dz * hz;
    double side = Math.abs(-dx * hz + dz * hx);
    if (ahead > 0 && ahead < SAFETY_DISTANCE && side < 0.6) {
      boolean mutual = o.blockedBy != null && o.blockedBy.equals(v.id);
      if (!mutual || o.id.compareTo(v.id) < 0) { v.blockedBy = o.id; return true; }
    }
  }
  v.blockedBy = null;
  return false;
}
```

4. Ankunft am Abholort: `LOADING` für `loadTime`; dann `materials().consume(reservationId)` (nur beim ersten Aufnehmen, siehe Task 7), Transport `IN_TRANSIT`, AGV `TO_DROP`.
5. Ankunft am Ziel: `UNLOADING` für `loadTime`; bei `PUTAWAY` `materials().receive(materialId, to.id, quantity)`; ist der Lagerplatz voll, bleibt der Transport in `UNLOADING` und versucht es jeden Schritt erneut (eine Warnung `log`-artig als `decision` mit Grund „Fertiglager voll"). Dann `DELIVERED` mit `transport.delivered` (Nutzlast `TransportDelivered` mit `Place to`).
6. Akku: Fahren zieht `drainPerMeter` je Meter, Stand `drainIdle` je Sekunde, Laden `chargeRate` je Sekunde. Unter `lowBattery` fährt ein freies AGV zur nächsten freien Ladestation (`TO_CHARGE` → `CHARGING`); `sendToCharge` lädt bis 95 %. Geladen wird bis `resumeFromCharge`, danach `IDLE` an der Station.
7. Geschäftszustand (Status, Knoten, Akku gerundet auf 0,1) wird nur bei Änderung über `FleetStore.save` geschrieben, nicht bei jeder Bewegung.

Jeder Statuswechsel prüft die Automaten aus `contracts` (`AgvStatus.RULES`, `TransportStatus.RULES`).

**Tests (`FleetTest`, Speicher, Schritt 100 ms):**
- `supplyTransportIsDeliveredAndEventsComeInOrder`: Ereignistypen in Reihenfolge `transport.created`, `decision`, `transport.assigned`, …, `stock.changed`, …, `transport.delivered`; Lieferzeit plausibel (Fahrstrecke / `maxSpeed` + 2 × `loadTime` ± 20 %).
- `nearestIdleAgvGetsTheOrder`
- `urgentOrderIsDispatchedFirst`
- `lowBatteryAgvChargesAndIsNotDispatchedBelowTheMinimum`
- `chargingAgvWithEnoughBatteryCanBeDispatched`
- `putawayIntoAFullWarehouseWaitsAndCompletesWhenSpaceFrees` (Review Focus 3)
- `disabledAgvIsNotDispatched`
- `sameSeedSameEvents`: zwei Läufe mit denselben Befehlen, identische Ereignisfolge.

**Test (`FleetInvariantTest`):** 2 Stunden Simulationszeit, alle 30 s ein zufälliger Auftrag (`SeededRandom` Seed 7) zwischen Lager und Maschinen, zufällige Sperrungen/Störungen ab Task 7 (in Task 6 ohne). Nach jedem Schritt: kein Paar fahrender AGVs näher als `SAFETY_DISTANCE − 0.05` in Fahrtrichtung, kein AGV mit zwei aktiven Transporten, jeder aktive Transport hat genau ein AGV, Akku in [0, 100], Summe `onHand` je Material plus unterwegs verbrauchter Mengen konstant (Review Focus 1). Am Ende sind mindestens 90 % der Aufträge ausgeliefert.

- [ ] Tests schreiben, Lauf rot.
- [ ] Umsetzen, Lauf grün.
- [ ] Commit `feat(flowpilot): Flotte mit Disposition, Folgeabstand und Laden`.

---

### Task 7: Störungen, Ersatzfahrzeug, Sperrungen

**Files:**
- Modify: `Fleet.java`, `Dispatcher.java`, `FlowPilot.java`
- Test: `FlowPilot/src/test/java/io/nexus/flowpilot/fleet/FaultTest.java`; `FleetInvariantTest` um Störungen und Sperrungen erweitern

**Verhalten:**
- `injectFault(agvId, faultTypeId)`: `NAV` oder `COMM` (null → `NAV`; andere → `invalid`). AGV bleibt stehen, Status `FAULT`, Ereignis `agv.fault`. Reparatur nach der Störungsdauer → `IDLE`.
- Transport eines ausgefallenen AGV:
  - unbeladen (`ASSIGNED`, `TO_PICKUP`) → `CREATED`, ohne AGV, neu disponiert.
  - beladen (`LOADING` nach dem Verbrauch, `IN_TRANSIT`, `UNLOADING`) → `FAILED` mit `transport.failed`; Abholknoten wird der letzte Knoten des ausgefallenen AGV. Ein Ersatz holt den Behälter dort ab (`transport.reassigned`), ohne die Reservierung erneut zu verbrauchen. Wird das ausgefallene AGV vorher repariert, übernimmt es den Auftrag selbst.
- Knoten mit einem AGV in `FAULT` gelten für die Routenplanung als gesperrt, ausser als Ziel des Ersatzes. Der Ersatz gilt als angekommen, wenn er bis auf den Folgeabstand herangefahren ist. Betroffene AGVs planen bei Störung und Reparatur neu (`route.replanned`).
- `setEdgeBlocked`: Sperre in `FleetStore` und `PathGraph`; AGVs, deren Restroute die Kante nutzt, planen neu. Ohne Route halten sie am nächsten Knoten und melden einmal `route.blocked` (Nutzlast `RouteChanged` mit leerer Route); nach dem Entsperren fahren sie weiter.

**Tests (`FaultTest`):**
- `unloadedFailedAgvReturnsItsOrderToTheQueue`
- `loadedFailedAgvIsReplacedAndTheLoadArrives` (Bestand nur einmal verbraucht)
- `failureDuringLoadingAfterConsumptionDoesNotConsumeTwice` (Review Focus 4)
- `repairedAgvBeforeRescueTakesItsOrderBack`
- `othersRouteAroundAFailedAgv`
- `blockedEdgeReroutesAndUnreachableWaitsWithOneEvent` (Review Focus 5)
- `unknownFaultTypeIsInvalid`

- [ ] Tests schreiben, Lauf rot.
- [ ] Umsetzen; `FleetInvariantTest` mit Störungen und Sperrungen grün.
- [ ] Commit `feat(flowpilot): AGV-Störungen mit Ersatzfahrzeug und Streckensperren`.

---

### Task 8: Spring-Verdrahtung und REST

**Files:**
- Create: `platform/app/src/main/java/io/nexus/app/flowpilot/FlowPilotConfig.java`, `FlowPilotController.java`
- Test: `platform/app/src/test/java/io/nexus/app/flowpilot/FlowPilotApiTest.java`

**Interfaces (Produces):** Beans `FlowPilot`, `MaterialService`, `TransportService` (für SmartFactory). Endpunkte unter `/api/v1`:

| Methode | Pfad | Antwort |
|---|---|---|
| GET | `/materials` | Stammdaten je Material mit Verfügbarkeit je Lagerplatz |
| GET | `/stock` | Bestandszeilen |
| GET | `/reservations?orderId=` | Reservierungen |
| POST | `/stock/receipts` | `{materialId, locationId, quantity}` → 204; 400/404/409 |
| GET | `/transports?status=` | Transportaufträge |
| GET | `/agvs` | `VehicleView` je AGV |
| POST | `/agvs/{id}/enable`, `/disable`, `/charge` | 204 |
| POST | `/agvs/{id}/fault` | `{faultTypeId?}` → 204 |
| POST | `/routes/edges/{id}/block`, `/unblock` | 204; unbekannt 404 |
| GET | `/layout` | Knoten, Kanten mit Sperrstatus, Docks, Ladestationen |

Request-Bodies als Records mit Bean Validation.

**Tests (`FlowPilotApiTest`):** je Endpunkt ein Erfolgsfall; `receipts` mit Menge 0 → 400, unbekanntes Material → 404, über Kapazität → 409; `fault` auf unbekanntes AGV → 404; OpenAPI enthält `/api/v1/transports`.

- [ ] Tests schreiben, Lauf rot.
- [ ] Umsetzen, `./mvnw -B verify` grün.
- [ ] Commit `feat(app): FlowPilot-API`.

---

### Task 9: Browser-Selbsttest

**Files:**
- Modify: `FlowPilot/src/main/java/io/nexus/flowpilot/FlowPilotSelfTest.java`
- Test: `FlowPilot/src/test/java/io/nexus/flowpilot/FlowPilotSelfTestTest.java`

**Verhalten:** `run(seed)` baut `FlowPilot.inMemory`, reserviert 5 `RM-GH200`, fordert einen Transport `RAW-A` → `CNC-01` an, sperrt danach eine Kante auf der kürzesten Route, lässt in 100-ms-Schritten bis zur Lieferung fahren (höchstens 10 min) und liefert: Ereignistypen in Reihenfolge, die Lieferzeit in ms, die Bitmuster von Akkustand und Endposition des AGV.

**Tests:** gleiche Ausgabe bei gleichem Seed; enthält `transport.delivered` und `route.replanned`. Der Node-Vergleich in `platform/web` prüft JVM gegen JavaScript.

- [ ] Test schreiben, Lauf rot.
- [ ] Umsetzen, `./mvnw -B verify` grün einschliesslich Node-Vergleich.
- [ ] Commit `feat(flowpilot): Selbsttest für den Browser-Build`.

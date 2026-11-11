# M1-C SmartFactory – Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** SmartFactory als Kernmodul: Produkte und Arbeitspläne, Produktionsaufträge mit Materialanforderung und Freigabe, Feinplanung mit endlicher Kapazität, Maschinenaufträge, Fehlerbehandlung mit Umplanung und Kennzahlen, dazu PostgreSQL-Speicher, REST-API und Browser-Selbsttest.

**Architecture:** Die Fachlogik liegt in `SmartFactory/` (reines Java, läuft im Browser). Eine Fassade `SmartFactory` bündelt Befehle und implementiert `ProductCatalog` und `MachineFeedback`. FlowPilot erreicht sie nur über `MaterialService` und `TransportService`, die Maschinen nur über `MachineControl`; Rückmeldungen kommen über Ereignisse (`transport.delivered`, `stock.changed`) und `MachineFeedback`. Die Planung ist eine reine Funktion ohne Seiteneffekte.

**Tech Stack:** Java 25, Maven, Spring Boot 4.1.2, PostgreSQL 18 (embedded), TeaVM 0.16.0, JUnit 5, AssertJ.

**Spec:** `docs/superpowers/specs/2026-11-10-nexus-industrial-os-design.md` (Revision 2), Abschnitte 2a, 4 (Ende-zu-Ende-Ablauf), 5, 6, 7, 8, 10, 12, 13. Voraussetzung: `2026-11-11-m1bc0-vorbereitung.md` ist auf `main` umgesetzt.

**Form:** Schnittstellen, Verhalten und Tests sind festgelegt; wörtlicher Code nur bei der Planung.

## Entscheidungen dieses Plans

- **Losfertigung.** Ein Arbeitsgang bearbeitet die ganze Auftragsmenge auf einer Maschine (Dauer = Rüstzeit + Menge × Grundzeit). Material und Zwischenprodukte bewegen sich als ein Behälter je Arbeitsgang und Material (siehe M1-B). Die 3D-Ansicht kann das in M2 als Einzelteile zeigen.
- **Ablauf eines Auftrags:**
  1. Anlegen → `CREATED`, Arbeitsgänge `PLANNED`, Planung.
  2. → `MATERIAL_PENDING`, eine Reservierung über alle Materialien des Auftrags (alles oder nichts).
  3. Erfolg: je (Arbeitsgang, Material) ein `SUPPLY`-Transport vom Lagerplatz der Reservierung zur geplanten Maschine; Arbeitsgänge mit Material `WAITING_MATERIAL`. Mangel: `SHORTAGE`, erneuter Versuch bei `stock.changed` desselben Materials.
  4. Erster Arbeitsgang hat sein Material → `RELEASED`.
  5. Ein Arbeitsgang ist `READY`, wenn sein Material da ist und der Vorgänger fertig und angeliefert ist; ist die Maschine frei, geht er per `MachineControl.startJob` an sie (`RUNNING`), der Auftrag beim ersten Start `IN_PROGRESS`.
  6. Fertig (`MachineFeedback.jobFinished`): Folgeschritt per `WIP`-Transport von `MACHINE_OUT` zu `MACHINE_IN`; legt die Maschine auf ein Band ab (Montagezellen), kommt das Los über `conveyorDelivered` an der Prüfzelle an. Nach dem letzten Schritt `PUTAWAY` der Gutteile ins Fertiglager.
  7. `PUTAWAY` angeliefert → `COMPLETED`.
- **Ausschuss** wird gezählt (Auftrag und Arbeitsgang), eine Nachproduktion gibt es in M1-C nicht.
- **Ein Material kommt in einem Arbeitsplan höchstens einmal vor**, damit Reservierung und Transport eines Materials dieselbe Menge haben. Ein Stammdatentest sichert das.
- **Maschinenbefehle der Oberfläche** (Start, Stopp, Störung, Reparatur, Wartung) prüft SmartFactory gegen den Maschinenautomaten und gibt sie an `MachineControl` weiter.

## Global Constraints

- Alles aus `2026-11-10-m1a2-portabler-kern.md`, Abschnitt Global Constraints, gilt weiter.
- Determinismus im Kern (Spec 2a), Listen immer sortiert.
- Rüstzeiten aus `FactoryTwin/src/sim/config.ts` (`MACHINE_PHYSICS.setupTime`): CNC 30 s, Roboter 15 s, Prüfzelle 0 s. Rüsten fällt an, wenn die Maschine zuletzt ein anderes Produkt bearbeitet hat.
- Migrationen nur `V200`..`V299`. Code nur unter `SmartFactory/` und `platform/app/src/{main,test}/java/io/nexus/app/smartfactory/`; einzige weitere Datei: `SmartFactory/src/main/java/io/nexus/smartfactory/SmartFactorySelfTest.java`.
- Ports von FlowPilot (`MaterialService`, `TransportService`) und der Simulation (`MachineControl`) über `ObjectProvider` beim ersten Aufruf beziehen; auf diesem Zweig liefern Tests Fakes.
- Commits enden mit `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.

## Review Focus

1. **Doppelte Lieferung.** `transport.delivered` für denselben Transport zweimal (erneute Zustellung) startet keinen zweiten Maschinenauftrag. Test in Task 5.
2. **Stornierung während des Laufs.** Ein Auftrag wird storniert, während ein Arbeitsgang läuft: Reservierungen frei, nicht abgeholte Transporte storniert, das spätere `jobFinished` erzeugt keinen Folgetransport. Test in Task 4 und 5.
3. **Prüfzelle fällt aus.** Aufträge vor `QC-01` warten mit einer Entscheidung „keine Ausweichmaschine", nichts wird umgeleitet, nach der Reparatur läuft alles weiter. Test in Task 6.
4. **Menge an der Grenze.** Menge 0, negative Menge, Menge über 100 und unbekanntes Produkt werden abgelehnt, ohne dass etwas gespeichert wird. Test in Task 4.
5. **Rückmeldung zu unbekanntem Auftrag.** `jobFinished` oder `conveyorDelivered` für einen unbekannten oder abgeschlossenen Auftrag ändert nichts und meldet eine Warnung. Test in Task 5.

---

### Task 1: Stammdaten im Kern, Produktkatalog, Gleichheitsprüfung

**Files:**
- Create: `SmartFactory/src/main/java/io/nexus/smartfactory/master/SmartFactoryMasterData.java`
- Create: `SmartFactory/src/main/java/io/nexus/smartfactory/catalog/Catalog.java`
- Test: `SmartFactory/src/test/java/io/nexus/smartfactory/catalog/CatalogTest.java`
- Test: `platform/app/src/test/java/io/nexus/app/smartfactory/MasterDataMatchesDatabaseTest.java`

**Interfaces (Produces):**

```java
public final class SmartFactoryMasterData {
  public record Product(String id, String name, String shortName, String color, String finishedMaterialId) {}
  public record RoutingStep(String productId, int seq, ProcessType process, int baseTimeS, String preferredMachineId) {}
  public record BomItem(String productId, int seq, String materialId, int quantity) {}
  public record Machine(String id, String name, MachineKind kind, String model, List<ProcessType> capabilities,
                        double x, double z, boolean outputToConveyor) {}
  /** A machine that hands over to a conveyor feeds exactly this machine. */
  public record ConveyorLink(String fromMachineId, String conveyorId, String toMachineId) {}

  public static List<Product> products();
  public static List<RoutingStep> routing();          // ordered by product, seq
  public static List<BomItem> bom();
  public static List<Machine> machines();
  public static List<ConveyorLink> conveyorLinks();   // ROB-01 → CV-03 → QC-01, ROB-02 → CV-03 → QC-01
  public static long setupMs(MachineKind kind);       // CNC 30000, ROBOT 15000, QC 0
}

public final class Catalog implements ProductCatalog {
  public List<MaterialDemand> demandFor(String productId, int quantity);   // per (step, material), quantity × bom
  public String finishedMaterialId(String productId);
  public List<RoutingStep> routing(String productId);
  public SmartFactoryMasterData.Product product(String productId);        // unknown: DomainException NOT_FOUND
}
```

**Tests:**
- `CatalogTest`: GH200 × 5 → `[(1, RM-GH200, 5), (2, KIT-GH200, 5)]`; FP12 hat nur Schritt 1 Material; unbekanntes Produkt → `NOT_FOUND`; Menge ≤ 0 → `INVALID`; jedes Material kommt je Produkt höchstens einmal vor; jeder Schritt hat mindestens eine fähige Maschine; jeder Montageschritt ist direkt vor einem Prüfschritt (Bandübergabe) .
- `MasterDataMatchesDatabaseTest`: Produkte, Arbeitspläne, Stücklisten und Maschinen (ohne Docks; die prüft M1-B) gleich den Tabellen; `outputToConveyor` gleich `out_dock_node is null`.

- [ ] Tests schreiben, Lauf rot.
- [ ] Umsetzen, `./mvnw -B verify` grün.
- [ ] Commit `feat(smartfactory): Stammdaten und Produktkatalog`.

---

### Task 2: Speicher-Ports, Speicher-Implementierung, JDBC

**Files:**
- Create: `SmartFactory/src/main/java/io/nexus/smartfactory/store/OrderStore.java`, `MachineStore.java`, `ProductionOrder.java`, `Operation.java`, `OperationMaterial.java`, `MachineRecord.java`, `StateInterval.java`
- Create: `SmartFactory/src/main/java/io/nexus/smartfactory/store/memory/MemoryOrderStore.java`, `MemoryMachineStore.java`
- Create: `SmartFactory/src/test/java/io/nexus/smartfactory/store/StoreContract.java`, `MemoryStoreTest.java`
- Create: `platform/app/src/main/resources/db/migration/V200__smartfactory.sql`
- Create: `platform/app/src/main/java/io/nexus/app/smartfactory/JdbcOrderStore.java`, `JdbcMachineStore.java`
- Create: `platform/app/src/test/java/io/nexus/app/smartfactory/JdbcStoreTest.java`

**Interfaces (Produces):**

```java
public record ProductionOrder(String id, String productId, int quantity, Priority priority, OrderStatus status,
                              long dueAtMs, long createdAtMs, Long completedAtMs, int good, int scrapped, int version) {}
public record Operation(String id, String orderId, int seq, ProcessType process, String machineId, Status status,
                        Long plannedStartMs, Long plannedEndMs, Long startedAtMs, Long endedAtMs, int good, int scrap) {
  public enum Status { PLANNED, WAITING_MATERIAL, READY, RUNNING, DONE, CANCELLED }
}
/** One container the operation needs: SUPPLY of a material, or WIP from the previous step. id = transport request id. */
public record OperationMaterial(String id, String orderId, int stepSeq, TransportKind kind, String materialId,
                                int quantity, String transportId, boolean delivered) {}
public record MachineRecord(String id, MachineStatus status, String lastProductId, int version) {}
public record StateInterval(String machineId, MachineStatus status, long fromMs, Long toMs) {}

public interface OrderStore {
  void insert(ProductionOrder order, List<Operation> operations);
  ProductionOrder update(ProductionOrder order);                 // optimistic, CONFLICT on stale version
  Optional<ProductionOrder> order(String id);
  List<ProductionOrder> orders();                                 // ordered by id
  List<ProductionOrder> open();                                   // not COMPLETED/CANCELLED, ordered by id
  List<Operation> operations(String orderId);                     // ordered by seq
  void saveOperation(Operation op);
  List<Operation> operationsOnMachine(String machineId);          // not DONE/CANCELLED, ordered by order id, seq
  void saveMaterial(OperationMaterial m);
  List<OperationMaterial> materials(String orderId, int stepSeq);
  Optional<OperationMaterial> materialByTransport(String transportId);
}
public interface MachineStore {
  List<MachineRecord> machines();                                 // ordered by id
  MachineRecord save(MachineRecord m);                            // optimistic
  /** Closes the open interval of the machine at nowMs and opens one with the new status. */
  void changeState(String machineId, MachineStatus status, long nowMs);
  List<StateInterval> intervals(String machineId, long fromMs, long toMs);   // clipped to the window
  List<MaintenanceWindow> maintenanceWindows(String machineId);
  record MaintenanceWindow(String machineId, long startMs, long endMs) {}
}
```

`V200__smartfactory.sql`: Tabelle `operation_material (id text primary key, order_id text not null references production_order, step_seq int not null, kind text not null check (kind in ('SUPPLY','WIP')), material_id text references material, quantity int not null check (quantity > 0), transport_id text, delivered boolean not null default false)`, Index auf `transport_id`; Spalten `operation.good`, `operation.scrap` (`int not null default 0`), `machine.last_product_id text references product`.

**Tests (`StoreContract`):** Einfügen und Lesen eines Auftrags mit Arbeitsgängen; `update` mit veralteter Version → `CONFLICT`; `open()` ohne abgeschlossene; Material nach Transport-ID finden; `changeState` schliesst das offene Intervall und `intervals` schneidet auf das Fenster zu; Rollback einer Einheit nimmt alles zurück.

- [ ] Tests schreiben, Lauf rot.
- [ ] Umsetzen, `./mvnw -B verify` grün.
- [ ] Commit `feat(smartfactory): Speicher-Ports für Aufträge und Maschinen`.

---

### Task 3: Feinplanung

**Files:**
- Create: `SmartFactory/src/main/java/io/nexus/smartfactory/planning/Scheduler.java`, `PlanInput.java`, `Plan.java`, `DispatchRule.java`
- Test: `SmartFactory/src/test/java/io/nexus/smartfactory/planning/SchedulerTest.java`

**Interfaces (Produces):**

```java
public enum DispatchRule { PRIORITY, EDD, FIFO, SPT }

public record PlanInput(long nowMs, DispatchRule rule, List<OrderToPlan> orders, List<MachineState> machines) {
  public record OrderToPlan(String orderId, String productId, int quantity, Priority priority, long dueAtMs,
                            long createdAtMs, List<OpToPlan> ops) {}
  /** fixedMachineId: running, or material already at the machine. doneAtMs set for finished operations. */
  public record OpToPlan(int seq, ProcessType process, int baseTimeS, String preferredMachineId,
                         String fixedMachineId, Long runningUntilMs, Long doneAtMs) {}
  public record MachineState(String id, MachineKind kind, List<ProcessType> capabilities, boolean available,
                             long busyUntilMs, String lastProductId, List<long[]> maintenance) {}
}

public record Plan(List<PlannedOp> ops, List<Unplannable> unplannable, List<String> lateOrders,
                   Map<String, Double> utilization, String bottleneckMachineId) {
  public record PlannedOp(String orderId, int seq, String machineId, long startMs, long endMs, long setupMs) {}
  public record Unplannable(String orderId, int seq, String reason) {}
}

public final class Scheduler {
  public static Plan plan(PlanInput input);   // pure, deterministic
}
```

Kern der Planung (wörtlich):

```java
public static Plan plan(PlanInput in) {
  Map<String, MachineCursor> cursors = new TreeMap<>();      // free time and last product per machine
  for (MachineState m : in.machines()) cursors.put(m.id(), new MachineCursor(m, Math.max(in.nowMs(), m.busyUntilMs())));
  List<PlannedOp> planned = new ArrayList<>();
  List<Unplannable> unplannable = new ArrayList<>();
  List<String> late = new ArrayList<>();
  for (OrderToPlan order : sequence(in.orders(), in.rule())) {
    long ready = in.nowMs();
    boolean blocked = false;
    for (OpToPlan op : order.ops()) {
      if (op.doneAtMs() != null) { ready = Math.max(ready, op.doneAtMs()); continue; }
      if (op.runningUntilMs() != null) { ready = Math.max(ready, op.runningUntilMs()); continue; }
      if (blocked) { unplannable.add(new Unplannable(order.orderId(), op.seq(), "Vorgänger nicht planbar")); continue; }
      Choice best = null;
      for (MachineCursor c : cursors.values()) {             // sorted by machine id
        if (!c.machine.capabilities().contains(op.process())) continue;
        if (op.fixedMachineId() != null && !op.fixedMachineId().equals(c.machine.id())) continue;
        if (!c.machine.available()) continue;
        long setup = order.productId().equals(c.lastProductId) ? 0 : SmartFactoryMasterData.setupMs(c.machine.kind());
        long duration = setup + (long) order.quantity() * op.baseTimeS() * 1000L;
        long start = c.firstGap(Math.max(ready, c.freeAt), duration);   // skips maintenance windows
        var choice = new Choice(c, start, start + duration, setup);
        if (best == null || choice.end() < best.end()
            || (choice.end() == best.end() && c.machine.id().equals(op.preferredMachineId()))) best = choice;
      }
      if (best == null) {
        unplannable.add(new Unplannable(order.orderId(), op.seq(), "keine verfügbare Maschine für " + op.process()));
        blocked = true;
        continue;
      }
      best.cursor().book(best.start(), best.end(), order.productId());
      planned.add(new PlannedOp(order.orderId(), op.seq(), best.cursor().machine.id(), best.start(), best.end(), best.setup()));
      ready = best.end();
    }
    if (!blocked && ready > order.dueAtMs()) late.add(order.orderId());
  }
  return new Plan(planned, unplannable, late, utilization(cursors, in.nowMs(), planned), bottleneck(cursors, planned));
}
```

`MachineCursor` (frei ab, letztes Produkt, gebuchte Intervalle, Wartungsfenster; `firstGap` liefert den frühesten Start ohne Überschneidung mit Buchungen und Wartung) und `Choice` (Cursor, Start, Ende, Rüstzeit) sind private Hilfstypen in `Scheduler`. `utilization` ist eine `TreeMap`, damit die Ausgabe in beiden Laufzeiten gleich geordnet ist.

`sequence`: `PRIORITY` = Priorität (EILIG, HOCH, NORMAL), dann Termin, Anlage, ID; `EDD` = Termin, ID; `FIFO` = Anlage, ID; `SPT` = Summe Menge × Grundzeit der offenen Schritte, ID. `utilization` je Maschine = gebuchte Zeit / (spätestes Planende − `nowMs`), `bottleneck` = höchste Auslastung, Gleichstand nach ID.

**Tests:**
- `stepsRunInOrderAndNeverOverlapOnAMachine` (Eigenschaftstest über 30 zufällige Aufträge, Seed fest)
- `setupOnlyWhenTheProductChanges`
- `maintenanceWindowIsSkipped`
- `unavailableMachineIsNotUsedAndAlternativeIsChosen` (CNC-01 aus → FRAESEN auf CNC-02 oder CNC-04)
- `noAlternativeMakesTheOrderUnplannable` (QC-01 aus)
- `urgentOrderIsPlannedFirstUnderPriorityButNotUnderFifo`
- `lateOrdersAreDetected`
- `bottleneckIsTheBusiestMachine`
- `preferredMachineWinsATie`
- `runningAndFinishedOperationsAreRespected`
- `sameInputSamePlan`

- [ ] Tests schreiben, Lauf rot.
- [ ] Umsetzen, Lauf grün.
- [ ] Commit `feat(smartfactory): Feinplanung mit endlicher Kapazität`.

---

### Task 4: Aufträge und Materialanforderung

**Files:**
- Create: `SmartFactory/src/main/java/io/nexus/smartfactory/SmartFactory.java` (Fassade), `orders/OrderService.java`, `orders/NewOrder.java`, `orders/OrderChange.java`
- Create: `SmartFactory/src/main/java/io/nexus/smartfactory/testkit/FakeMaterialService.java`, `FakeTransportService.java`, `FakeMachineControl.java` (im Hauptcode, weil der Selbsttest sie braucht)
- Test: `SmartFactory/src/test/java/io/nexus/smartfactory/orders/OrderServiceTest.java`

**Interfaces (Produces):**

```java
public record NewOrder(String productId, int quantity, Priority priority, long dueInMs) {}
public record OrderChange(Priority priority, Integer quantity, Long dueInMs) {}   // null = unchanged

/** Facade. Every public method runs in its own unit of work and is synchronized. */
public final class SmartFactory implements MachineFeedback {
  public static SmartFactory inMemory(EventBus bus, MemoryUnitOfWork work, SimTime time, IdSource ids,
                                      MaterialService materials, TransportService transports, MachineControl machines);
  public SmartFactory(OrderStore orders, MachineStore machineStore, EventBus bus, UnitOfWork work, SimTime time,
                      IdSource ids, Supplier<MaterialService> materials, Supplier<TransportService> transports,
                      Supplier<MachineControl> machines);
  public ProductCatalog catalog();
  public String createOrder(NewOrder order);
  public void changeOrder(String orderId, OrderChange change);
  public void cancelOrder(String orderId);
  public void setDispatchRule(DispatchRule rule);
  public Plan plan();                         // last computed plan
  public List<ProductionOrder> orders();
  public List<Operation> operations(String orderId);
  // MachineFeedback, machine commands: Tasks 5 and 6
}
```

Die Fassade registriert einen eigenen `EventConsumer` (Name `smartfactory`) am Bus für `transport.delivered` und `stock.changed`.

**Verhalten:**
- `createOrder`: Produkt bekannt, Menge 1..100, `dueInMs > 0`, sonst `DomainException.invalid`/`notFound` ohne Speichern. ID `PO-nnnn`. Ereignis `order.created`. Dann Planung (Task 3) und Materialanforderung:
  - `order.material_pending`, `materials.reserve(new ReservationRequest(orderId + "/RES", orderId, Linien je Material))`.
  - Erfolg: je Bedarf `(seq, material)` ein `OperationMaterial` (id `orderId/S<seq>/<material>`) und `transports.requestTransport(SUPPLY, from = Place.storage(locationId der Reservierung), to = Place.machineIn(geplante Maschine), reservationId)`; Arbeitsgang `WAITING_MATERIAL`.
  - Mangel: `order.shortage` (Nutzlast `OrderStatusChanged`, Grund nennt Material und Mengen).
- Verbraucher `stock.changed`: Aufträge in `SHORTAGE`, die das Material brauchen, versuchen es erneut (in Reihenfolge der Auftrags-ID).
- `changeOrder`: Priorität jederzeit vor `COMPLETED`; Menge und Termin nur in `SHORTAGE` (sonst `conflict`). Danach neu planen. Ereignis `decision` mit der Änderung.
- `cancelOrder`: `materials.release(orderId + "/RES")`; jeden noch nicht abgeholten Transport stornieren (eine `IllegalTransitionException` bedeutet „schon unterwegs" und wird als `decision` vermerkt); offene Arbeitsgänge `CANCELLED`; `order.cancelled`; neu planen.

**Tests (`OrderServiceTest`, mit Fakes und Speicher):**
- `createdOrderReservesAndRequestsOneTransportPerMaterial` (GH200: zwei Transporte, Ziele CNC-01 bzw. ROB-01 laut Plan)
- `shortageWaitsAndRetriesOnStockChange`
- `invalidInputIsRejectedWithoutStoring` (Review Focus 4: 0, −1, 101, unbekanntes Produkt, Termin 0)
- `priorityChangeReplans`
- `quantityChangeOutsideShortageIsAConflict`
- `cancelReleasesReservationAndCancelsTransportsNotPickedUp` (Review Focus 2, erster Teil)
- `eventsCarryTypedPayloadsAndTheOrderIdAsCorrelation`

- [ ] Tests schreiben, Lauf rot.
- [ ] Umsetzen, Lauf grün.
- [ ] Commit `feat(smartfactory): Aufträge mit Materialanforderung`.

---

### Task 5: Produktionsablauf

**Files:**
- Create: `SmartFactory/src/main/java/io/nexus/smartfactory/production/ProductionFlow.java`
- Modify: `SmartFactory.java` (implementiert `MachineFeedback.jobFinished`, `conveyorDelivered`, `statusChanged` für den Normalbetrieb)
- Test: `SmartFactory/src/test/java/io/nexus/smartfactory/production/ProductionFlowTest.java`

**Verhalten:**
- Verbraucher `transport.delivered`: `OperationMaterial` über die Transport-ID finden, `delivered = true`; unbekannte Transporte ignorieren; schon geliefert → keine Wirkung (Review Focus 1). Bei `PUTAWAY` → Auftrag `COMPLETED` mit `order.completed` und `completedAtMs`.
- Bereitschaft: Ein Arbeitsgang wird `READY`, wenn alle seine `OperationMaterial` geliefert sind und der Vorgänger `DONE` ist. Erster Schritt bereit → Auftrag `RELEASED` (`order.released`).
- Start: Nach jeder Änderung prüft `ProductionFlow` je Maschine (nach ID): Status `IDLE`, kein laufender Arbeitsgang, ein `READY`-Arbeitsgang mit dieser Maschine → `machines.startJob(MachineJob(…, setupMs, processMs))`, Arbeitsgang `RUNNING` (`operation.started`), Maschine merkt sich das Produkt, Auftrag beim ersten Start `IN_PROGRESS` (`order.started`). Mehrere bereit → der früheste im Plan.
- `jobFinished(machine, order, seq, good, scrap)`: Arbeitsgang `DONE` mit Gut/Ausschuss (`operation.completed`). Unbekannter oder abgeschlossener Auftrag, falscher Schritt oder Maschine → keine Wirkung, `decision` mit Warnung (Review Focus 5). Gestrichener Auftrag → keine Folgetransporte (Review Focus 2). Sonst:
  - Folgeschritt vorhanden und Maschine legt auf ein Band ab: `OperationMaterial` WIP des Folgeschritts anlegen und auf `conveyorDelivered` warten.
  - Folgeschritt vorhanden sonst: WIP-Transport `orderId/W<seq+1>` von `Place.machineOut(machine)` zu `Place.machineIn(Maschine des Folgeschritts)`, Menge `good`.
  - Letzter Schritt: Auftrag `good`/`scrapped` setzen; `good > 0` → `PUTAWAY` `orderId/P` von `Place.machineOut(machine)` nach `Place.storage("FG-A")`, Material `catalog.finishedMaterialId`; `good == 0` → direkt `COMPLETED`.
- `conveyorDelivered(machine, order, seq, qty)`: WIP des Schritts als geliefert markieren; prüft, dass die Maschine laut `conveyorLinks` das Ziel ist.
- `statusChanged`: Maschinenstatus und Intervall speichern (`MachineStore.changeState`), `machine.status`; wird eine Maschine `IDLE`, Startprüfung.

**Tests (`ProductionFlowTest`, Fakes liefern auf Knopfdruck):**
- `everyProductRunsFromOrderToStock`: je Produkt ein Auftrag; der Test spielt FlowPilot und Maschinen über die Fakes durch (Lieferungen als `transport.delivered` über den Bus, Maschinen über `jobFinished`/`conveyorDelivered`). Erwartet: Statusfolge des Auftrags `CREATED, MATERIAL_PENDING, RELEASED, IN_PROGRESS, COMPLETED`, Maschinenaufträge in Schrittfolge, richtige Transportarten und Orte.
- `secondDeliveryOfTheSameTransportHasNoEffect` (Review Focus 1)
- `machineGetsTheNextJobOnlyWhenIdle`
- `setupIsRequestedOnlyOnProductChange`
- `scrapReducesTheQuantityThatMovesOn`
- `zeroGoodPartsCompletesWithoutPutaway`
- `finishedJobOfACancelledOrderCreatesNoTransport` (Review Focus 2, zweiter Teil)
- `feedbackForUnknownOrderOnlyWarns` (Review Focus 5)

- [ ] Tests schreiben, Lauf rot.
- [ ] Umsetzen, Lauf grün.
- [ ] Commit `feat(smartfactory): Produktionsablauf von der Freigabe bis zur Einlagerung`.

---

### Task 6: Störungen und Umplanung

**Files:**
- Create: `SmartFactory/src/main/java/io/nexus/smartfactory/production/FaultHandling.java`
- Modify: `SmartFactory.java` (`faultRaised`, Maschinenbefehle)
- Test: `SmartFactory/src/test/java/io/nexus/smartfactory/production/FaultHandlingTest.java`

**Verhalten:**
- `faultRaised(machine, type, label, scrapped)`: Ereignis `machine.fault`. Bei `scrapped` und laufendem Arbeitsgang ruft die Simulation kein `jobFinished`; SmartFactory bucht das Los selbst als Ausschuss (`good = 0`, `scrap = Menge`) und verfährt wie bei `jobFinished` mit null Gutteilen (Task 5).
- Übergang einer Maschine nach `FAULT`, `REPAIR`, `MAINTENANCE` oder `STOPPED`: neu planen (Maschine nicht verfügbar). Für jeden nicht laufenden Arbeitsgang, dessen Plan nun eine andere Maschine nennt:
  - nicht gelieferte Transporte `transports.redirect(…, Place.machineIn(neu))`;
  - schon gelieferte Behälter: WIP-Transport `orderId/M<seq>/<n>` von `Place.machineIn(alt)` zu `Place.machineIn(neu)` (Material bleibt dasselbe, ohne Reservierung), altes `OperationMaterial` ersetzt;
  - `order.rescheduled` (Nutzlast `OrderRescheduled`) und `decision` mit Kandidaten und Grund.
- Laufende Arbeitsgänge bleiben auf ihrer Maschine und laufen nach der Reparatur weiter.
- Ohne Ausweichmaschine (Prüfzelle): `decision` „keine Ausweichmaschine für PRUEFEN, Auftrag wartet", Schweregrad `WARNING`, nichts wird bewegt.
- Zurück auf `IDLE`: neu planen, Startprüfung.
- Maschinenbefehle `startMachine`, `stopMachine`, `injectFault`, `repairMachine`, `maintainMachine`: prüfen mit `MachineStatus.RULES` gegen den aktuellen Status (Start nur aus `STOPPED`, Reparatur nur aus `FAULT`, Wartung aus `IDLE` oder `STOPPED`, Stopp aus Betriebszuständen), sonst `IllegalTransitionException`; dann `MachineControl`. Der Status ändert sich erst mit der Rückmeldung der Simulation.

**Tests (`FaultHandlingTest`):**
- `faultOnAMillingCenterMovesWaitingOperationsAndRedirectsTransports`
- `deliveredMaterialIsMovedToTheAlternativeMachine`
- `runningOperationStaysAndResumesAfterRepair`
- `qcFaultMakesOrdersWaitWithoutMovingAnything` (Review Focus 3)
- `scrappingFaultBooksTheBatchAsScrap`
- `machineCommandsAreValidatedAgainstTheStateMachine`
- `everyRescheduleIsTraceable`: jedes `order.rescheduled` hat ein `decision` mit derselben Auftrags-ID.

- [ ] Tests schreiben, Lauf rot.
- [ ] Umsetzen, Lauf grün.
- [ ] Commit `feat(smartfactory): Störungsbehandlung mit Umplanung und Maschinenbefehlen`.

---

### Task 7: Kennzahlen

**Files:**
- Create: `SmartFactory/src/main/java/io/nexus/smartfactory/analytics/Analytics.java`, `Kpis.java`
- Test: `SmartFactory/src/test/java/io/nexus/smartfactory/analytics/AnalyticsTest.java`

**Interfaces (Produces):**

```java
// machines: TreeMap by machine id; timeInStatusMs: EnumMap — stable order in both runtimes
public record Kpis(long fromMs, long toMs, double throughputPerHour, double avgLeadTimeS, double p90LeadTimeS,
                   double onTimeRate, Map<String, MachineKpi> machines, String bottleneckMachineId) {
  public record MachineKpi(double utilization, Map<MachineStatus, Long> timeInStatusMs,
                           double availability, double performance, double quality, double oee) {}
}
public final class Analytics {
  public Analytics(OrderStore orders, MachineStore machines, Catalog catalog);
  public Kpis compute(long fromMs, long toMs);
}
```

Definitionen (alle aus gespeicherten Zustandsänderungen):
- Durchsatz: Gutteile abgeschlossener Aufträge mit `completedAtMs` im Fenster, je Stunde.
- Durchlaufzeit: `completedAtMs − createdAtMs` abgeschlossener Aufträge; p90 nach Rang (nearest rank).
- Termintreue: Anteil abgeschlossener Aufträge mit `completedAtMs ≤ dueAtMs`.
- Zeit je Status: Summe der Intervalle, auf das Fenster zugeschnitten.
- Auslastung: `RUNNING` / Fensterlänge.
- Verfügbarkeit: (Fenster − `FAULT` − `REPAIR` − `MAINTENANCE`) / Fenster.
- Leistung: Summe Menge × Grundzeit der im Fenster beendeten Arbeitsgänge / `RUNNING`-Zeit (begrenzt auf 1, 0 ohne Laufzeit).
- Qualität: Gut / (Gut + Ausschuss) der Arbeitsgänge im Fenster (1 ohne Teile).
- OEE: Verfügbarkeit × Leistung × Qualität.
- Engpass: höchste Auslastung, Gleichstand nach ID.

**Tests:** konstruierte Intervalle und Aufträge mit von Hand gerechneten Erwartungswerten (je Kennzahl ein Test); leeres Fenster ergibt 0 bzw. 1 ohne Division durch null; Intervalle über die Fenstergrenze werden zugeschnitten.

- [ ] Tests schreiben, Lauf rot.
- [ ] Umsetzen, Lauf grün.
- [ ] Commit `feat(smartfactory): Kennzahlen aus Zustandsänderungen`.

---

### Task 8: Spring-Verdrahtung und REST

**Files:**
- Create: `platform/app/src/main/java/io/nexus/app/smartfactory/SmartFactoryConfig.java`, `SmartFactoryController.java`
- Test: `platform/app/src/test/java/io/nexus/app/smartfactory/SmartFactoryApiTest.java` (mit `@TestConfiguration`-Fakes aus `testkit` für `MaterialService`, `TransportService`, `MachineControl`)

**Interfaces (Produces):** Beans `SmartFactory`, `ProductCatalog`, `MachineFeedback`. Endpunkte unter `/api/v1`:

| Methode | Pfad | Antwort |
|---|---|---|
| POST | `/orders` | `{productId, quantity, priority, dueInMinutes}` → 201 mit `Location` und Auftrag |
| GET | `/orders`, `/orders/{id}` | Aufträge; einzeln mit Arbeitsgängen und Materialien |
| PATCH | `/orders/{id}` | `{priority?, quantity?, dueInMinutes?}` |
| POST | `/orders/{id}/cancel` | 204 |
| GET | `/schedule` | aktueller Plan mit Verspätungen, Engpass, nicht planbaren Schritten |
| PUT | `/schedule/rule` | `{rule}` |
| GET | `/products` | Produkte mit Arbeitsplan und Stückliste |
| GET | `/machines` | Stammdaten, Status, laufender Arbeitsgang |
| POST | `/machines/{id}/start`, `/stop`, `/repair`, `/maintain` | 204; ungültig 409 |
| POST | `/machines/{id}/fault` | `{faultTypeId?}` → 204 |
| GET | `/analytics?windowMinutes=60` | `Kpis` |

**Tests (`SmartFactoryApiTest`):** Anlegen 201 mit Ort; Menge 0 → 400 mit Feld; unbekanntes Produkt → 404; Stornieren eines abgeschlossenen Auftrags → 409; Reparatur einer laufenden Maschine → 409; `windowMinutes` ausserhalb 1..1440 → 400; OpenAPI enthält `/api/v1/orders`.

- [ ] Tests schreiben, Lauf rot.
- [ ] Umsetzen, `./mvnw -B verify` grün.
- [ ] Commit `feat(app): SmartFactory-API`.

---

### Task 9: Browser-Selbsttest

**Files:**
- Modify: `SmartFactory/src/main/java/io/nexus/smartfactory/SmartFactorySelfTest.java`
- Test: `SmartFactory/src/test/java/io/nexus/smartfactory/SmartFactorySelfTestTest.java`

**Verhalten:** `run(seed)` baut `SmartFactory.inMemory` mit den Fakes aus `testkit`, legt je Produkt einen Auftrag an (Menge aus `SeededRandom`, 1..10), spielt Lieferungen und Maschinenrückmeldungen bis zum Abschluss durch, löst unterwegs eine Störung an `CNC-01` aus und liefert: Statusfolge je Auftrag, die Maschinenaufträge, den Plan als `Maschine:Start-Ende` und die Kennzahlen als Bitmuster.

**Tests:** gleiche Ausgabe bei gleichem Seed; jeder Auftrag endet in `COMPLETED`; mindestens ein `order.rescheduled`. Der Node-Vergleich in `platform/web` prüft JVM gegen JavaScript.

- [ ] Test schreiben, Lauf rot.
- [ ] Umsetzen, `./mvnw -B verify` grün einschliesslich Node-Vergleich.
- [ ] Commit `feat(smartfactory): Selbsttest für den Browser-Build`.

## Nach M1-B und M1-C

M1-D verbindet beide mit der Simulation: Simulationsschleife (`FlowPilot.step`, Maschinenphysik, `EventBus.retryFailed` ausserhalb der Schritt-Transaktion), `MachineControl` und `MachineFeedback` über OPC UA beziehungsweise direkt, WebSocket-Frames, Reset, zufällige Störungen und der Ende-zu-Ende-Test über beide Module in beiden Laufzeiten.

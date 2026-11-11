# M1-A2 Portabler Kern – Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Die Ereignisverteilung und die Verträge so umbauen, dass dieselbe Java-Fachlogik auf dem Server (PostgreSQL) und im Browser (TeaVM, Speicher im Arbeitsspeicher) läuft, und dabei die offenen Review-Befunde aus M1-A erledigen.

**Architecture:** Die Ereignisverteilung wandert als `EventBus` in `nexus-contracts` und spricht nur über die Ports `EventLog` und `UnitOfWork`. Der Server implementiert sie mit JDBC und Spring-Transaktionen, der Browser mit Listen im Speicher. Ein neues Modul `platform/web` übersetzt den Kern mit TeaVM nach JavaScript und führt ihn im Build in Node aus.

**Tech Stack:** Java 25, Maven 3.10, Spring Boot 4.1.2, PostgreSQL 18 (embedded), TeaVM 0.16.0, Node 24 für den Lauf des übersetzten Moduls, JUnit 5, AssertJ.

**Spec:** `docs/superpowers/specs/2026-11-10-nexus-industrial-os-design.md` (Revision 2), vor allem Abschnitte 2a, 6, 7, 13, 16.

**Hinweis zur Form:** Dieser Plan wird in derselben Sitzung nativ ausgeführt, in der er geschrieben wurde. Er legt deshalb Schnittstellen, Verhalten und Tests vollständig fest, gibt Implementierungscode aber nur dort wörtlich vor, wo die Logik heikel ist (`EventBus`). Das weicht von der Vorgabe „jeder Schritt mit vollständigem Code" ab und spart die doppelte Niederschrift.

## Global Constraints

- Java-Release 25. Basis-Package `io.nexus`.
- **Kernmodule** (`nexus-contracts`, `nexus-sim`, `flowpilot`, `smartfactory`): nur Java-Standardbibliothek, soweit TeaVM sie trägt. Verboten: Spring, JDBC, Jackson, `java.util.UUID`-Konstruktor, `java.util.Random`, `Math.random`, Systemzeit, Reflection, eigene Threads.
- Zufall nur über `SeededRandom`, Zeit nur über `SimTime`, IDs sind Zeichenketten.
- `V1`..`V3` dürfen in diesem Plan noch geändert werden (nichts ist gemergt, jede Datenbank ist flüchtig). Mit dem Merge dieses Plans sind sie eingefroren.
- REST-Fehler als RFC-7807-Problem-JSON. Nur `DomainException` und `IllegalTransitionException` geben ihren Text an den Client.
- Alle Befehle aus dem Repo-Wurzelordner, Build mit `./mvnw -B verify` (ohne `-q`).
- Commits enden mit `Co-Authored-By: Claude Fable 5.2 <noreply@anthropic.com>`.
- `FactoryTwin/` wird nicht verändert.

## Review Focus

1. **Wirkung vor Ursache.** Veröffentlicht ein Verbraucher beim Verarbeiten von E1 das Ereignis E2, erhält jeder Verbraucher E1 vor E2. Test in Task 3.
2. **Dauerhaft fehlschlagender Verbraucher.** Nach fünf Versuchen hört die Wiederholung auf, und genau ein `system.consumer_failed` erscheint. Test in Task 3.
3. **Falsche Nutzlast zum Ereignistyp.** `publish("transport.delivered", …, new MachineFault(…))` schlägt sofort fehl und speichert nichts. Test in Task 2.
4. **Speicher und PostgreSQL verhalten sich gleich.** Dieselbe Vertragstestklasse läuft gegen beide `EventLog`-Implementierungen. Tasks 3 und 5.
5. **Interner Fehlertext im HTTP-Body.** Eine unerwartete `RuntimeException("jdbc:postgresql://…")` ergibt 500 ohne diesen Text. Test in Task 6.

---

### Task 1: Zeichenketten-IDs, Zufallsgenerator, Fachausnahme, AGV-Übergänge

**Files:**
- Modify: `platform/contracts/src/main/java/io/nexus/contracts/event/EventIds.java`
- Create: `platform/contracts/src/main/java/io/nexus/contracts/sim/SeededRandom.java`
- Create: `platform/contracts/src/main/java/io/nexus/contracts/DomainException.java`
- Modify: `platform/contracts/src/main/java/io/nexus/contracts/state/AgvStatus.java`
- Test: `EventIdsTest.java` (anpassen), `SeededRandomTest.java`, `DomainExceptionTest.java`, `TransitionsTest.java` (ergänzen)

**Interfaces (Produces):**
- `EventIds(long runId)`: `String next()` liefert `"<runId>-<zähler>"` ab Zähler 0; `long runId()`.
- `SeededRandom(long seed)`: `long nextLong()` (SplitMix64), `double nextDouble()` in `[0,1)`, `int nextInt(int bound)`, `double range(double min, double max)`, `SeededRandom fork(String label)` (unabhängiger Teilstrom, abgeleitet aus Seed und Label, damit Module sich nicht gegenseitig die Zahlenfolge verschieben).
- `DomainException(Kind kind, String message)` mit `enum Kind { NOT_FOUND, INVALID, CONFLICT }`, Fabriken `notFound(String what, String id)`, `invalid(String message)`, `conflict(String message)`.
- `AgvStatus.RULES` zusätzlich `CHARGING → TO_PICKUP` und `TO_CHARGE → TO_PICKUP` (die alte Engine disponiert ladende Fahrzeuge).

**Tests:**
- `EventIdsTest`: gleiche Lauf-ID ergibt gleiche Folge; `new EventIds(7).next()` ist `"7-0"`, danach `"7-1"`; IDs zweier Läufe überschneiden sich nicht.
- `SeededRandomTest`:
  - Referenzwerte SplitMix64 für Seed 0: `0xE220A8397B1DCDAFL`, `0x6E789E6AA1B965F4L`, `0x06C45D188009454FL`.
  - Gleicher Seed, gleiche Folge; `nextDouble` liegt in 10 000 Zügen in `[0,1)`; `nextInt(6)` trifft in 10 000 Zügen jede Zahl 0..5; `nextInt(0)` wirft `IllegalArgumentException`.
  - `fork("a")` und `fork("b")` liefern verschiedene Folgen; `fork("a")` ist unabhängig davon, wie viele Zahlen der Elterngenerator vorher gezogen hat.
- `DomainExceptionTest`: `notFound("Auftrag", "PO-9")` hat `kind() == NOT_FOUND` und nennt `PO-9`.
- `TransitionsTest`: die zwei neuen AGV-Übergänge.

- [ ] Tests schreiben, `./mvnw -B -pl platform/contracts test` muss an fehlenden Klassen scheitern.
- [ ] Implementieren, Lauf grün.
- [ ] Commit `feat(contracts): Zeichenketten-IDs, SeededRandom, DomainException`.

---

### Task 2: Typisierte Ereignisnutzlasten und präzisierte Ports

**Files:**
- Create: `platform/contracts/src/main/java/io/nexus/contracts/event/Payload.java`, `Payloads.java`
- Modify: `event/NexusEvent.java`, `event/EventTypes.java`, `event/EventPublisher.java`
- Modify: `port/MaterialService.java`, `port/TransportService.java`, `port/ProductCatalog.java`
- Create: `platform/contracts/src/main/java/io/nexus/contracts/Place.java`
- Test: `platform/contracts/src/test/java/io/nexus/contracts/event/EventTypesTest.java`

**Interfaces (Produces):**

```java
public interface Payload {}

public record NexusEvent(String eventId, long simTimeMs, String type, String source, Severity severity,
                         String correlationId, Payload payload) {}

public interface EventPublisher {
  NexusEvent publish(String type, String source, Severity severity, String correlationId, Payload payload);
}

public record Place(Kind kind, String id) {
  public enum Kind { STORAGE, MACHINE_IN, MACHINE_OUT, CONVEYOR, CHARGER }
}
```

`Payloads` (alle als `record … implements Payload`, in einer Datei):

| Record | Felder | Ereignistypen |
|---|---|---|
| `Note` | `String message` | `scenario.started`, `scenario.finished`, `opcua.connection` |
| `Decision` | `String subject, String chosen, List<String> candidates, String reason` | `decision` |
| `ConsumerFailed` | `String consumer, String eventId, String eventType, String error` | `system.consumer_failed` |
| `OrderCreated` | `String orderId, String productId, int quantity, Priority priority, long dueAtMs` | `order.created` |
| `OrderStatusChanged` | `String orderId, OrderStatus from, OrderStatus to, String reason` | `order.material_pending`, `order.shortage`, `order.released`, `order.started`, `order.completed`, `order.cancelled` |
| `OrderRescheduled` | `String orderId, int stepSeq, String fromMachineId, String toMachineId, String reason` | `order.rescheduled` |
| `OperationChanged` | `String orderId, int stepSeq, String machineId` | `operation.started`, `operation.completed` |
| `MaterialReserved` | `String orderId, String requestId, List<String> reservationIds` | `material.reserved`, `material.released` |
| `MaterialShortage` | `String orderId, String materialId, int requested, int available` | `material.shortage` |
| `StockChanged` | `String materialId, String locationId, int onHand, int reserved` | `stock.changed` |
| `TransportCreated` | `String transportId, TransportKind kind, String orderId, String materialId, int quantity, Place from, Place to` | `transport.created` |
| `TransportStateChanged` | `String transportId, TransportStatus from, TransportStatus to, String agvId` | `transport.assigned`, `transport.state`, `transport.failed`, `transport.reassigned` |
| `TransportDelivered` | `String transportId, TransportKind kind, String orderId, String materialId, int quantity, Place to` | `transport.delivered` |
| `AgvStateChanged` | `String agvId, AgvStatus from, AgvStatus to` | `agv.state` |
| `AgvFault` | `String agvId, String faultTypeId, String label` | `agv.fault` |
| `RouteChanged` | `String agvId, String edgeId, List<String> route` | `route.blocked`, `route.replanned` |
| `MachineStatusChanged` | `String machineId, MachineStatus from, MachineStatus to` | `machine.status` |
| `MachineFault` | `String machineId, String faultTypeId, String label, boolean scrappedWorkpiece` | `machine.fault` |
| `MaterialRequested` | `String machineId, String orderId, String materialId, int quantity` | `machine.material_request` |

`EventTypes` bekommt `SYSTEM_CONSUMER_FAILED = "system.consumer_failed"`, `static Class<? extends Payload> payloadType(String type)` (wirft `IllegalArgumentException` bei unbekanntem Typ) und `static Set<String> all()`.

Ports:

```java
public interface MaterialService {
  /** All lines or nothing. Idempotent on requestId for successful reservations only; a shortage is not stored. */
  ReservationResult reserve(ReservationRequest request);
  /** Releases every active reservation of the request. Unknown or already released: no-op. */
  void release(String requestId);
  /** One entry per storage location holding the material. */
  List<Availability> availability(String materialId);

  record Line(String materialId, int quantity) {}
  record ReservationRequest(String requestId, String orderId, List<Line> lines) {}
  record Reserved(String reservationId, String materialId, String locationId, int quantity) {}
  sealed interface ReservationResult {
    record Success(String requestId, List<Reserved> reservations) implements ReservationResult {}
    record Shortage(String materialId, int requested, int available) implements ReservationResult {}
  }
  record Availability(String materialId, String locationId, int onHand, int reserved) { int available() }
}

public interface TransportService {
  String requestTransport(TransportRequest request);   // idempotent on requestId
  void cancel(String transportId);
  void redirect(String transportId, Place newTo);
  record TransportRequest(String requestId, TransportKind kind, String orderId, String materialId, int quantity,
                          Place from, Place to, Priority priority, String reservationId) {}
}

public interface ProductCatalog {
  List<MaterialDemand> demandFor(String productId, int quantity);   // unknown product: DomainException NOT_FOUND
  String finishedMaterialId(String productId);
  record MaterialDemand(int stepSeq, String materialId, int quantity) {}
}
```

**Tests** (`EventTypesTest`):
- Jede öffentliche `String`-Konstante in `EventTypes` hat einen Eintrag in `payloadType` (per Schleife über `EventTypes.all()`, dessen Grösse gegen die erwartete Zahl 32 geprüft wird).
- `payloadType("transport.delivered")` ist `Payloads.TransportDelivered.class`.
- `payloadType("gibt.es.nicht")` wirft.

- [ ] Test schreiben, Lauf muss scheitern.
- [ ] Records, Registry und Ports umsetzen. `platform/app` kompiliert danach nicht mehr; das behebt Task 5. Bis dahin Lauf nur mit `-pl platform/contracts`.
- [ ] Commit `feat(contracts): typisierte Nutzlasten und präzisierte Ports`.

---

### Task 3: EventBus im Kern mit Speicher-Implementierung

**Files:**
- Create: `platform/contracts/src/main/java/io/nexus/contracts/event/EventLog.java`, `UnitOfWork.java`, `EventBus.java`
- Create: `platform/contracts/src/main/java/io/nexus/contracts/memory/MemoryEventLog.java`, `MemoryUnitOfWork.java`
- Modify: `platform/contracts/pom.xml` (Test-JAR bauen)
- Test: `platform/contracts/src/test/java/io/nexus/contracts/event/EventLogContract.java` (abstrakt), `MemoryEventLogTest.java`, `EventBusTest.java`

**Interfaces (Produces):**

```java
public interface UnitOfWork {
  /** Runs after the current transaction commits; immediately if none is active. Not run on rollback. */
  void afterCommit(Runnable action);
  /** Runs in a transaction of its own, independent of any surrounding one. */
  void inNewTransaction(Runnable action);
}

public interface EventLog {
  void append(long runId, NexusEvent event);
  /** True exactly once per (consumer, eventId). Must be called inside the consumer's transaction. */
  boolean markProcessed(String consumer, String eventId);
  /** Counts a failed delivery and returns the number of attempts so far. */
  int recordFailure(String consumer, String eventId, String error);
  void clearFailure(String consumer, String eventId);
  /** Open failures of the run with fewer than maxAttempts attempts, in publish order. */
  List<Failure> openFailures(long runId, int maxAttempts);
  Optional<NexusEvent> find(String eventId);
  record Failure(String consumer, NexusEvent event, int attempts) {}
}
```

`EventBus` (wörtlich umzusetzen):

```java
public final class EventBus implements EventPublisher {
  public static final int MAX_ATTEMPTS = 5;
  private static final String SOURCE = "nexus";

  private final EventLog log;
  private final UnitOfWork work;
  private final SimTime time;
  private final Supplier<EventIds> ids;
  private final List<EventConsumer> consumers = new ArrayList<>();
  private final Deque<NexusEvent> queue = new ArrayDeque<>();
  private boolean draining;

  public EventBus(EventLog log, UnitOfWork work, SimTime time, Supplier<EventIds> ids) { … }

  /** Consumers are called in name order. A second consumer with the same name is a configuration error. */
  public synchronized void register(EventConsumer consumer) {
    for (EventConsumer c : consumers)
      if (c.name().equals(consumer.name())) throw new IllegalStateException("Verbraucher doppelt registriert: " + c.name());
    consumers.add(consumer);
    consumers.sort(Comparator.comparing(EventConsumer::name));
  }

  @Override
  public NexusEvent publish(String type, String source, Severity severity, String correlationId, Payload payload) {
    Class<? extends Payload> expected = EventTypes.payloadType(type);
    if (!expected.isInstance(payload))
      throw new IllegalArgumentException("Ereignis " + type + " erwartet " + expected.getSimpleName());
    EventIds current = ids.get();
    var event = new NexusEvent(current.next(), time.nowMs(), type, source, severity, correlationId, payload);
    log.append(current.runId(), event);
    work.afterCommit(() -> enqueue(event));
    return event;
  }

  /** FIFO: an event published by a consumer is delivered after the event that caused it has reached everyone. */
  private synchronized void enqueue(NexusEvent event) {
    queue.addLast(event);
    if (draining) return;
    draining = true;
    try {
      NexusEvent next;
      while ((next = queue.pollFirst()) != null)
        for (EventConsumer c : List.copyOf(consumers)) deliver(c, next);
    } finally {
      draining = false;
    }
  }

  /** Returns true if the consumer has processed the event (now or earlier). */
  private boolean deliver(EventConsumer consumer, NexusEvent event) {
    try {
      work.inNewTransaction(() -> {
        if (log.markProcessed(consumer.name(), event.eventId())) consumer.on(event);
      });
      return true;
    } catch (RuntimeException e) {
      int attempts = failureCount(consumer, event, e);
      if (attempts == MAX_ATTEMPTS && !EventTypes.SYSTEM_CONSUMER_FAILED.equals(event.type()))
        work.inNewTransaction(() -> publish(EventTypes.SYSTEM_CONSUMER_FAILED, SOURCE, Severity.ERROR,
            event.correlationId(), new Payloads.ConsumerFailed(consumer.name(), event.eventId(), event.type(),
                String.valueOf(e.getMessage()))));
      return false;
    }
  }

  private int failureCount(EventConsumer consumer, NexusEvent event, RuntimeException e) {
    int[] attempts = new int[1];
    work.inNewTransaction(() -> attempts[0] = log.recordFailure(consumer.name(), event.eventId(), String.valueOf(e.getMessage())));
    return attempts[0];
  }

  /** Called at the end of every simulation step: retries deliveries that failed fewer than MAX_ATTEMPTS times. */
  public synchronized void retryFailed() {
    for (EventLog.Failure f : log.openFailures(ids.get().runId(), MAX_ATTEMPTS))
      for (EventConsumer c : List.copyOf(consumers))
        if (c.name().equals(f.consumer()) && deliver(c, f.event()))
          work.inNewTransaction(() -> log.clearFailure(f.consumer(), f.event().eventId()));
  }

  /** Delivers a stored event again; consumers that already processed it are skipped. */
  public void redeliver(String eventId) {
    NexusEvent event = log.find(eventId).orElseThrow(() -> DomainException.notFound("Ereignis", eventId));
    work.afterCommit(() -> enqueue(event));
  }
}
```

`redeliver` läuft über `afterCommit` wie `publish`; das schliesst den Sicherheitsbefund „redeliver in offener Transaktion".

`MemoryUnitOfWork`: `afterCommit` und `inNewTransaction` führen sofort aus. `MemoryEventLog`: `ArrayList` der Ereignisse mit Lauf-ID, `HashSet` der Marker, `LinkedHashMap` der Fehlversuche. Achtung: Schlägt ein Verbraucher fehl, muss der Marker verschwinden. Weil es im Speicher kein Rollback gibt, entfernt `MemoryUnitOfWork.inNewTransaction` bei einer Ausnahme die in diesem Aufruf gesetzten Marker wieder (`MemoryEventLog` führt dafür ein Journal der Marker des laufenden Aufrufs, das `MemoryUnitOfWork` über `begin()`/`rollback()`/`commit()` steuert). Fachspeicher der Module hängen sich später an dasselbe Journal.

**Tests:**

`EventLogContract` (abstrakt, `protected abstract EventLog log(); protected abstract UnitOfWork work();`), läuft hier gegen Speicher und in Task 5 gegen JDBC:
- `markProcessedIsTrueOnceThenFalse`
- `markerSetInARolledBackUnitIsGone`: in `inNewTransaction` markieren, dann werfen; danach liefert `markProcessed` wieder `true`.
- `failuresCountUpAndClear`: drei `recordFailure` liefern 1, 2, 3; `openFailures(run, 5)` enthält den Eintrag, `openFailures(run, 3)` nicht; nach `clearFailure` leer.
- `openFailuresAreScopedToTheRunAndOrderedByPublishOrder`
- `findReturnsTheStoredEventWithEqualPayload`: `TransportDelivered` mit `Place` und Enum, Vergleich per `equals`.

`EventBusTest` (mit Speicher-Implementierungen):
- `deliversToAllConsumersInNameOrder`
- `eventPublishedByAConsumerIsDeliveredAfterTheCausingEventReachedEveryone`: Verbraucher `a` veröffentlicht bei E1 ein E2; Verbraucher `z` sieht `[E1, E2]`.
- `redeliveryHasNoSecondEffect`
- `failingConsumerDoesNotBlockOthersAndIsRetried`: erster Versuch wirft, `retryFailed()` stellt zu, der Verbraucher sieht das Ereignis genau einmal, `openFailures` ist leer.
- `givesUpAfterFiveAttemptsAndReportsExactlyOnce`: Verbraucher wirft immer; nach sechs `retryFailed()` gibt es genau ein `system.consumer_failed` mit `Severity.ERROR`, und der Verbraucher wurde genau fünfmal aufgerufen.
- `aFailingConsumerOfTheFailureReportDoesNotLoop`: ein Verbraucher, der auch bei `system.consumer_failed` wirft, erzeugt keine weiteren Meldungen.
- `wrongPayloadTypeIsRejectedAndNothingIsStored`
- `duplicateConsumerNameFailsAtRegistration`
- `eventCarriesSimulationTimeAndRunScopedId`

`platform/contracts/pom.xml`: `maven-jar-plugin` mit Goal `test-jar`, damit `platform/app` `EventLogContract` wiederverwenden kann.

- [ ] Tests schreiben, Lauf muss scheitern.
- [ ] Implementieren, `./mvnw -B -pl platform/contracts test` grün.
- [ ] Commit `feat(contracts): EventBus mit FIFO-Zustellung und begrenzter Wiederholung`.

---

### Task 4: Schema nachziehen

**Files:**
- Modify: `platform/app/src/main/resources/db/migration/V1__schema.sql`
- Rename: `V3__path_graph.sql` → `V2__path_graph.sql`, `V2__master_data.sql` → `V3__master_data.sql` (Graph zuerst, damit Stammdaten Fremdschlüssel darauf haben können). Die AGV-Zeilen wandern aus der Graph-Datei in die Stammdaten.
- Modify: `platform/app/src/test/java/io/nexus/app/SchemaTest.java`

**Änderungen an V1:**
- `event.event_id` und `processed_event.event_id` werden `text`; `processed_event.event_id references event`.
- Neu: `event_delivery_failure (consumer text, event_id text references event, attempts int not null, last_error text, primary key (consumer, event_id))`.
- `material` wird vor `product` angelegt; `product.finished_material_id text not null references material`.
- `machine.out_dock_node text references graph_node` (Abholpunkt für AGVs; `null`, wenn die Maschine auf ein Band ablegt). `machine.dock_node`, `storage_location.dock_node` bekommen `references graph_node`. Die Graph-Tabellen stehen dafür am Anfang von V1.
- Neu: `conveyor (id text primary key, name text, dock_node text references graph_node, start_x, start_z, end_x, end_z double precision, speed double precision)` und `charger (id text primary key, node_id text not null references graph_node)`.
- Check-Constraints auf allen Statusspalten, passend zu den Enums: `machine.status`, `production_order.status`, `transport_order.status`, `agv.status`; `operation.status in ('PLANNED','WAITING_MATERIAL','READY','RUNNING','DONE','CANCELLED')`.

**Änderungen an den Stammdaten:**
- `product.finished_material_id`: `FG-GH200`, `FG-AW50`, `FG-FP12`, `FG-MT7`.
- `machine.out_dock_node`: CNC wie `dock_node`; `ROB-01`, `ROB-02` `null`; `QC-01` `D_CV04`.
- `conveyor`: `CV-01`..`CV-04` mit den Werten aus `FactoryTwin/src/sim/initialState.ts` (`CONVEYOR_DEFS`).
- `charger`: `CH-1`..`CH-6` auf `D_CH1`..`D_CH6`.
- AGV-Namen `Transportroboter 1`..`6`.

**Tests** (`SchemaTest`, zusätzlich):
- `masterDataIsSeeded` prüft auch 4 Förderbänder und 6 Ladestationen.
- `everyProductHasAFinishedMaterialOfKindFinished`
- `databaseRejectsUnknownStatus`: `update transport_order`/`machine`/`agv`/`production_order` mit Status `'KAPUTT'` wird abgelehnt (für `machine` und `agv` per Update einer vorhandenen Zeile).
- `transportStatusCheckMatchesTheEnum`: Die Werte des Check-Constraints (aus `pg_get_constraintdef`) enthalten jeden Namen aus `TransportStatus.values()`; ebenso für `MachineStatus`, `AgvStatus`, `OrderStatus`.
- `markerForAMissingEventIsRejected`: Insert in `processed_event` mit unbekannter `event_id` scheitert.

- [ ] Tests ergänzen. Dieser Task läuft zusammen mit Task 5 grün, weil `platform/app` erst dort wieder kompiliert. Reihenfolge: Schema und Stammdaten hier ändern, Tests hier schreiben, ausführen in Task 5.
- [ ] Commit zusammen mit Task 5.

---

### Task 5: Server-Laufzeit auf den EventBus umstellen

**Files:**
- Delete: `platform/app/src/main/java/io/nexus/app/event/JdbcEventPublisher.java`
- Create: `platform/app/src/main/java/io/nexus/app/event/JdbcEventLog.java`, `SpringUnitOfWork.java`, `EventConfig.java`
- Modify: `platform/app/src/main/java/io/nexus/app/event/SimRun.java`, `EventController.java`
- Modify: `platform/app/src/main/java/io/nexus/app/NexusApplication.java` (Embedded-DataSource über Hikari)
- Modify: `platform/app/pom.xml` (Test-JAR von `nexus-contracts`)
- Test: `JdbcEventLogTest.java` (erbt `EventLogContract`), `ServerEventBusTest.java` (ersetzt `JdbcEventPublisherTest.java`), `ApiTest.java` (anpassen)

**Interfaces (Produces):**
- Spring-Beans: `EventLog` (`JdbcEventLog`), `UnitOfWork` (`SpringUnitOfWork`), `EventBus`, `EventPublisher` (derselbe `EventBus`). Jeder Bean vom Typ `EventConsumer` wird beim Start registriert.
- `SimRun`: `EventIds ids()` und `void restart(long seed)`; `id()` und `seed()` bleiben. `ids()` liefert das Objekt, aus dem der Bus Lauf-ID und Zähler gemeinsam liest (kein halb umgeschalteter Lauf).
- `JdbcEventLog` schreibt die Nutzlast mit Jackson als JSON und liest sie über `EventTypes.payloadType(type)` zurück.
- `SpringUnitOfWork`: `afterCommit` registriert eine `TransactionSynchronization`, wenn eine Transaktion aktiv ist, sonst sofort; `inNewTransaction` nutzt `TransactionTemplate` mit `REQUIRES_NEW`.
- `EventController`: `eventId` ist `String`; `payload` weiterhin als JSON-Baum.

**Tests:**
- `JdbcEventLogTest extends EventLogContract`: alle fünf Vertragstests gegen PostgreSQL.
- `ServerEventBusTest`:
  - `storesAndDeliversAfterCommit`
  - `rolledBackTransactionLeavesNoEventAndCallsNoConsumer`
  - `failingConsumerRollsBackItsOwnWritesAndIsRetried` (echter Schreibzugriff des Verbrauchers auf `maintenance_window`)
  - `redeliverInsideAnOpenTransactionWaitsForCommit`: `redeliver` in einer Transaktion aufrufen, die danach zurückgerollt wird; der Verbraucher wird nicht aufgerufen.
  - `payloadSurvivesTheDatabaseRoundTrip`: `Decision` mit Liste und Umlaut.
- `ApiTest`: bestehende Tests mit typisierten Nutzlasten; `eventsOfEarlierRunsAreNotMixedIntoTheCurrentRun` bleibt.

- [ ] Tests schreiben bzw. umstellen, Lauf muss scheitern.
- [ ] Umsetzen. `./mvnw -B verify` grün (ohne `platform/web`, das kommt in Task 7).
- [ ] Commit `refactor(app): Server-Laufzeit nutzt den EventBus aus dem Kern; Schema nachgezogen`.

---

### Task 6: API-Fehler ohne interne Texte

**Files:**
- Modify: `platform/app/src/main/java/io/nexus/app/web/ApiErrors.java`
- Delete: `platform/app/src/main/java/io/nexus/app/web/NotFoundException.java` (ersetzt durch `DomainException.notFound`)
- Test: `ApiTest.java`

**Verhalten:**

| Ausnahme | Status | Text im Body |
|---|---|---|
| `DomainException` `NOT_FOUND` / `INVALID` / `CONFLICT` | 404 / 400 / 409 | Meldung der Ausnahme |
| `IllegalTransitionException` | 409 | Meldung der Ausnahme |
| `OptimisticLockingFailureException` | 409 | fester Text |
| `DataIntegrityViolationException` | 409 | fester Text „Die Änderung verletzt eine Datenregel." |
| Bean-Validation | 400 | Feldliste |
| alles andere (`Exception`) | 500 | fester Text „Interner Fehler", Ausnahme nur im Log |

Der Handler für beliebige `IllegalArgumentException` entfällt.

**Tests** (`ApiTest`, zusätzlich bzw. angepasst):
- `unknownIdIs404ProblemJson` nutzt `DomainException.notFound`.
- `unexpectedErrorIs500ProblemJsonWithoutInternalText`: Probe-Endpunkt wirft `new IllegalStateException("jdbc:postgresql://geheim")`; Antwort 500, `application/problem+json`, Body enthält `geheim` nicht.
- `illegalArgumentFromALibraryIsNotEchoed`: Probe wirft `new IllegalArgumentException("internes Detail")`; Antwort 500 ohne diesen Text.
- `databaseRuleViolationIs409`: Probe führt `update stock set reserved = on_hand + 1` aus; Antwort 409.

- [ ] Tests schreiben, Lauf muss scheitern.
- [ ] Umsetzen, `./mvnw -B verify` grün.
- [ ] Commit `fix(app): Fehlerantworten geben keine internen Texte mehr preis`.

---

### Task 7: Browser-Laufzeit `platform/web` und Abhängigkeitsregel

**Files:**
- Create: `platform/web/pom.xml`
- Create: `platform/web/src/main/java/io/nexus/web/WebRuntime.java`
- Create: `platform/web/src/test/js/smoke.mjs`
- Modify: `pom.xml` (Modul `platform/web`, Enforcer für die Kernmodule)
- Modify: `platform/contracts/pom.xml`, `platform/sim/pom.xml`, `FlowPilot/pom.xml`, `SmartFactory/pom.xml` (Enforcer aktivieren)

**Interfaces (Produces):**
- `platform/web/target/javascript/nexus-core.js`: ES-Modul, gebaut mit `teavm-maven-plugin` 0.16.0 (`targetType` `JAVASCRIPT`, `jsModuleType` `ES2015`, `minifying` `true`, `mainClass` `io.nexus.web.WebRuntime`).
- Exporte über `@JSExport` (spätere Pläne erweitern diese Liste):
  - `String selfTest(int seed)`: baut `EventBus` mit Speicher-Implementierungen, registriert zwei Verbraucher (einer veröffentlicht ein Folgeereignis, einer schlägt beim ersten Versuch fehl), veröffentlicht `order.created`, ruft `retryFailed()` und liefert eine Zeile je zugestelltem Ereignis im Format `<eventId> <type> <consumer>`, dazu `random=<erste drei SeededRandom-Werte als Hex>`.
- Dieselbe Methode `selfTest` ist normales Java und wird auch auf der JVM getestet.

**Tests:**
- `platform/web/src/test/java/io/nexus/web/WebRuntimeTest.java`: `selfTest(4711)` auf der JVM liefert genau die erwartete Zeilenfolge (im Test als Text festgehalten).
- `smoke.mjs`: importiert `target/javascript/nexus-core.js`, ruft `selfTest(4711)` und vergleicht mit `target/selftest-jvm.txt`, das der JVM-Test geschrieben hat. Weicht ein Zeichen ab, endet das Skript mit Exit-Code 1. Aufruf über `exec-maven-plugin` in der Phase `verify`. Das ist der erste Nachweis „Server und Browser liefern bei gleichem Seed dieselbe Ereignisfolge".
- Enforcer: `bannedDependencies` in den vier Kernmodulen lässt im Scope `compile` nur `io.nexus:*` zu. Gegenprobe von Hand: `spring-jdbc` testweise in `FlowPilot/pom.xml` eintragen, `./mvnw -B -pl FlowPilot validate` muss scheitern; Eintrag wieder entfernen.

- [ ] JVM-Test und `smoke.mjs` schreiben, Lauf muss scheitern (Modul fehlt).
- [ ] Modul, Export und Enforcer umsetzen. `./mvnw -B verify` grün einschliesslich Node-Lauf.
- [ ] Gegenprobe Enforcer durchführen und im Ledger festhalten.
- [ ] Commit `feat(web): Browser-Laufzeit mit TeaVM, Gleichheitsprüfung JVM gegen JavaScript`.

---

## Nach diesem Plan

- `m1a-fundament` und dieser Zweig gehen gemeinsam nach `main`. Danach sind `V1`..`V3` und die Verträge eingefroren.
- M1-B (FlowPilot) und M1-C (SmartFactory) starten parallel. Beide liefern je Fachobjekt einen Speicher-Port, eine Speicher-Implementierung im eigenen Modul, eine JDBC-Implementierung in `platform/app` und einen gemeinsamen Vertragstest nach dem Muster von `EventLogContract`.
- Bewusst nicht behoben: Paging über `seq` kann unter gleichzeitigen Schreibern ein spät committetes Ereignis überspringen (Review-Befund m8). Der WebSocket-Strom aus M1-D ist die verlässliche Quelle; die REST-Abfrage dient der Historie.

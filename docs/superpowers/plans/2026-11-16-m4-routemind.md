# M4 RouteMind Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** RouteMind bringt die fertige Ware vom Fertiglager über eine Verladezone in Lieferwellen zu rund 30 Kunden in Zürich und Umgebung, geplant mit Heuristik (Browser) oder OR-Tools (Server), sichtbar auf einer Karte, im Logistik-Dashboard und in FactoryTwin.

**Architecture:** Neues Kernmodul `RouteMind` (Maven-Modul, Paket `io.nexus.routemind`), nur abhängig von `contracts`, läuft unverändert im Server und per TeaVM im Browser. Es hört auf Auftrags- und Transportereignisse, fordert Ware über `MaterialService`/`TransportService` (FlowPilot) an, fragt Fertigstellungstermine über den neuen Port `ProductionEta` (SmartFactory) und plant Touren hinter dem Port `RouteOptimizer`. Strassendaten sind ein einmal über OSRM erzeugter fester Satz (`RoadData.java` für den Kern, `roads.json` für die Karte). Die Anlage (`platform/plant`) ruft RouteMind in jedem Schritt auf, ersetzt damit den bisherigen `Shipping`, und liefert Fahrzeuge und Verladezone im Frame.

**Tech Stack:** Java 25, Maven, Spring Boot 4.1 (Server), PostgreSQL/Flyway, TeaVM 0.16, Google OR-Tools `com.google.ortools:ortools-java` 9.15.6755 (nur Server), React 19 + TypeScript, MapLibre GL JS `maplibre-gl` 6.18.0, Vitest, Playwright.

**Spec:** `docs/superpowers/specs/2026-11-10-nexus-industrial-os-design.md`, Abschnitt 15a, dazu 5, 6, 7, 8, 13, 14, 16, 17, 20.

## Global Constraints

- Kernmodule (contracts, FlowPilot, SmartFactory, RouteMind, plant) bleiben TeaVM-tauglich: keine Streams, keine Reflection, kein `String.format`, keine Bibliothek ausser dem JDK und anderen Kernmodulen; der Enforcer im Modul-POM prüft das.
- Determinismus: gleicher Seed, gleiche Ereignisfolge. Zufall nur über `SeededRandom` (`fork("routemind")`), Zeit nur über `SimTime`. Für Reihenfolgen nur `TreeMap`, `LinkedHashMap` oder Listen; `HashMap` nur für Nachschlagen.
- Ein Plattform-Lock: jede öffentliche RouteMind-Methode läuft in `synchronized (lock)` mit dem `EventBus` als Lock, wie FlowPilot und SmartFactory.
- Ungültige Übergänge werfen `IllegalTransitionException` über `Transitions.move` (REST 409); unbekannte IDs `DomainException.notFound` (404); Eingabefehler `DomainException.invalid` (400).
- Oberfläche deutsch, Inter für Text, JetBrains Mono für Zahlen und IDs, Farbe nur für Zustände (`STATE_HEX`), keine Verläufe, kein Glas.
- Strassendaten: Quelle „© OpenStreetMap-Mitwirkende (ODbL), Routen berechnet mit OSRM“ in `roads.json`, in `RoadData.java` und sichtbar auf der Karte. Der OSRM-Demodienst wird nur vom einmaligen Generatorskript angesprochen, nie zur Laufzeit. Luftlinien werden nie als Fahrzeit oder Strecke ausgegeben.
- OR-Tools nur in `platform/app`; der Browser rechnet mit der Heuristik. Die Oberfläche zeigt den Löser jeder Planung.
- Versionen exakt: `com.google.ortools:ortools-java:9.15.6755`, `maplibre-gl@6.18.0`.
- Commits: Betreff und Text, keine Co-Author- oder Werkzeug-Zeile; die repo-lokale `user.email` (GitHub-noreply) bleibt. Kein Push und kein Merge ohne ausdrückliches OK.
- Vor Branch-Wechseln oder Rebases `git checkout -- FactoryTwin/tsconfig.tsbuildinfo`.

## Review Focus

1. **Ware verspätet bei Abfahrt.** Eine Tour, deren Lieferungen nicht alle verladen sind, fährt nie mit einer unverladenen Lieferung los; sie fährt ohne sie oder entfällt. Test: Task 7 `noTourStartsWithoutConfirmedLoading` und die Invariante im Langlauf.
2. **Fahrzeugausfall nach einem Teil der Stopps.** Zugestellte Lieferungen und angefahrene Stopps bleiben unverändert; nur offene gehen in `FAILED` und werden höchstens zweimal erneut versucht. Test: Task 7 `breakdownKeepsDeliveredStopsAndRetriesTheRest`, `deliveryIsGivenUpAfterThreeFailedAttempts`.
3. **Gleicher Seed in beiden Laufzeiten.** RouteMind-Ereignisse sind auf JVM und in TeaVM gleich; der Server nutzt dafür im Vergleichstest die Heuristik. Test: Task 8 `sameSeedSameDeliveryEvents`, Task 9 `RuntimeEquivalenceTest` bleibt grün.
4. **Nicht planbare Lieferungen.** Zu schwer, Zeitfenster unerreichbar, keine Schicht: jede erscheint mit Grund im Planergebnis, keine verschwindet still. Test: Task 6 `everyJobIsPlannedOrReportedWithAReason`.
5. **Reset mitten in einer Tour.** Tabellen und Speicher von RouteMind sind danach leer, alle Fahrzeuge `AVAILABLE` im Depot. Test: Task 9 `resetClearsRouteMind`.

## Entscheidungen dieses Plans (vor der Umsetzung bestätigen)

- **Verladezone `LZ-1`** ist ein neuer Ortstyp `LOADING` mit Dock `D_LZ` im Südosten der Halle. Ein Transport der neuen Art `SHIPMENT` bucht die reservierte Fertigware beim Aufnehmen aus (wie `SUPPLY`); an der Verladezone liegt sie bei RouteMind. Kein zusätzlicher Lagerplatz.
- **`Shipping` entfällt.** Die Regel `shipping` schaltet jetzt die Auslieferung durch RouteMind (Settings: „Auslieferung“).
- **Lieferwellen:** Abfahrten alle 30 min Simulationszeit zwischen 06:00 und 22:00. Welle W wird um W−30 min geplant, ab W−15 min verladen, um W gestartet. Zeitfenster eines Kunden: Fälligkeit bis Fälligkeit + 4 h.
- **Nicht enthalten:** neue Kundenorte zur Laufzeit über den Routingdienst (Spec 15a, Server). Das würde Koordinaten zur Laufzeit an einen fremden Dienst schicken; es bleibt beim festen Satz.
- **Dashboard-Verlauf** kommt aus den Datensätzen von RouteMind (die bei jedem Ereignis mitgeschrieben werden) und aus den letzten 20 Planungen im Speicher, nicht aus einer Abfrage über die Ereignistabelle.
- **Tour-Automat** bekommt `LOADING → REPLANNED` für eine Tour, deren gesamte Ware die Abfahrt verpasst.

## Dateien

| Bereich | Dateien |
|---|---|
| contracts | `state/DeliveryStatus.java`, `state/TourStatus.java`, `state/VehicleStatus.java`, `port/ProductionEta.java`, `event/EventTypes.java`, `event/Payloads.java`, `Domain.java`, `Place.java` |
| FlowPilot | `master/FlowPilotMasterData.java`, `master/PathData.java`, `transport/PlaceResolver.java`, `transport/TransportServiceImpl.java`, `fleet/Fleet.java` |
| SmartFactory | `SmartFactory.java` (Port `ProductionEta`) |
| RouteMind (neu) | `pom.xml`, `roads/places.json`, `master/RoadData.java` (erzeugt), `master/Roads.java`, `master/RouteMindMasterData.java`, `store/*`, `optimize/*`, `RouteMind.java` |
| plant | `Plant.java`, `PlantFrame.java`, `PlantJson.java`, `PlantQueries.java`, `PlantCommands.java`, `Scenarios.java`; `Shipping.java` gelöscht |
| app | `db/migration/V5__loading_zone.sql`, `V6__routemind.sql`, `routemind/JdbcRouteStore.java`, `routemind/RouteMindConfig.java`, `routemind/OrToolsOptimizer.java`, `plant/PlantReset.java`, `plant/PlantConfig.java` |
| FactoryTwin | `scripts/build-roads.mjs`, `public/routemind/roads.json` (erzeugt), `src/twin/layout.ts`, `src/nexus/types.ts`, `src/ui/format.ts`, `src/areas/routemind/*`, `src/scene/LoadingZone.tsx`, `src/scene/FactoryScene.tsx`, `src/shell/*`, `e2e/routemind.spec.ts` |

---

### Task 1: Verträge – Zustände, Ereignisse, Port `ProductionEta`

**Files:**
- Create: `platform/contracts/src/main/java/io/nexus/contracts/state/DeliveryStatus.java`
- Create: `platform/contracts/src/main/java/io/nexus/contracts/state/TourStatus.java`
- Create: `platform/contracts/src/main/java/io/nexus/contracts/state/VehicleStatus.java`
- Create: `platform/contracts/src/main/java/io/nexus/contracts/port/ProductionEta.java`
- Modify: `platform/contracts/src/main/java/io/nexus/contracts/event/EventTypes.java`
- Modify: `platform/contracts/src/main/java/io/nexus/contracts/event/Payloads.java`
- Modify: `platform/plant/src/main/java/io/nexus/plant/PlantJson.java`
- Test: `platform/contracts/src/test/java/io/nexus/contracts/state/RouteMindStatesTest.java`

**Interfaces:**
- Produces: `DeliveryStatus`, `TourStatus`, `VehicleStatus` (je `RULES`, `terminal()`); `ProductionEta.plannedFinishMs(String orderId): long`; Ereignistypen `delivery.created`, `delivery.state`, `tour.planned`, `tour.state`, `vehicle.state`, `delivery.plan`; Records `Payloads.DeliveryCreated`, `DeliveryStateChanged`, `TourPlanned`, `TourStateChanged`, `VehicleStateChanged`, `DeliveryPlanComputed`, `PlanFigures`.

- [ ] **Step 1: Write the failing test**

```java
package io.nexus.contracts.state;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class RouteMindStatesTest {
  @Test
  void deliveryFollowsSpec15a() {
    var r = DeliveryStatus.RULES;
    assertThat(r.can(DeliveryStatus.CREATED, DeliveryStatus.AWAITING_GOODS)).isTrue();
    assertThat(r.can(DeliveryStatus.AWAITING_GOODS, DeliveryStatus.READY_TO_LOAD)).isTrue();
    assertThat(r.can(DeliveryStatus.READY_TO_LOAD, DeliveryStatus.LOADED)).isTrue();
    assertThat(r.can(DeliveryStatus.LOADED, DeliveryStatus.IN_TRANSIT)).isTrue();
    assertThat(r.can(DeliveryStatus.IN_TRANSIT, DeliveryStatus.DELIVERED)).isTrue();
    assertThat(r.can(DeliveryStatus.IN_TRANSIT, DeliveryStatus.FAILED)).isTrue();
    assertThat(r.can(DeliveryStatus.FAILED, DeliveryStatus.READY_TO_LOAD)).isTrue();
    // cancelling is possible before LOADED only
    assertThat(r.can(DeliveryStatus.READY_TO_LOAD, DeliveryStatus.CANCELLED)).isTrue();
    assertThat(r.can(DeliveryStatus.LOADED, DeliveryStatus.CANCELLED)).isFalse();
    assertThat(r.can(DeliveryStatus.IN_TRANSIT, DeliveryStatus.CANCELLED)).isFalse();
    assertThat(r.can(DeliveryStatus.LOADED, DeliveryStatus.READY_TO_LOAD)).isFalse();
    assertThat(DeliveryStatus.DELIVERED.terminal()).isTrue();
    assertThat(DeliveryStatus.FAILED.terminal()).isFalse();
  }

  @Test
  void tourAndVehicle() {
    assertThat(TourStatus.RULES.can(TourStatus.PLANNED, TourStatus.REPLANNED)).isTrue();
    assertThat(TourStatus.RULES.can(TourStatus.LOADING, TourStatus.REPLANNED)).isTrue();
    assertThat(TourStatus.RULES.can(TourStatus.EN_ROUTE, TourStatus.REPLANNED)).isFalse();
    assertThat(TourStatus.RULES.can(TourStatus.EN_ROUTE, TourStatus.INTERRUPTED)).isTrue();
    assertThat(TourStatus.COMPLETED.terminal()).isTrue();
    assertThat(VehicleStatus.RULES.can(VehicleStatus.LOADING, VehicleStatus.BROKEN_DOWN)).isFalse();
    assertThat(VehicleStatus.RULES.can(VehicleStatus.EN_ROUTE, VehicleStatus.BROKEN_DOWN)).isTrue();
    assertThat(VehicleStatus.RULES.can(VehicleStatus.BROKEN_DOWN, VehicleStatus.AVAILABLE)).isTrue();
    assertThatThrownBy(() -> VehicleStatus.RULES.move("LKW-1", VehicleStatus.MAINTENANCE, VehicleStatus.LOADING))
        .isInstanceOf(IllegalTransitionException.class);
  }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw -q -pl platform/contracts test -Dtest=RouteMindStatesTest`
Expected: COMPILATION ERROR, `DeliveryStatus` not found.

- [ ] **Step 3: Write the state machines and the port**

`state/DeliveryStatus.java`:

```java
package io.nexus.contracts.state;

/** Delivery order of RouteMind (Spec 15a). FAILED is retried from READY_TO_LOAD; RouteMind limits the attempts. */
public enum DeliveryStatus {
  CREATED, AWAITING_GOODS, READY_TO_LOAD, LOADED, IN_TRANSIT, DELIVERED, FAILED, CANCELLED;

  public boolean terminal() {
    return this == DELIVERED || this == CANCELLED;
  }

  public static final Transitions<DeliveryStatus> RULES = Transitions.of(DeliveryStatus.class)
      .allow(CREATED, AWAITING_GOODS, CANCELLED)
      .allow(AWAITING_GOODS, READY_TO_LOAD, CANCELLED)
      .allow(READY_TO_LOAD, LOADED, CANCELLED)
      .allow(LOADED, IN_TRANSIT)
      .allow(IN_TRANSIT, DELIVERED, FAILED)
      .allow(FAILED, READY_TO_LOAD)
      .build();
}
```

`state/TourStatus.java`:

```java
package io.nexus.contracts.state;

/**
 * Tour of one delivery vehicle (Spec 15a). LOADING → REPLANNED: none of the tour's goods arrived before departure,
 * so the tour is dropped and its deliveries go into the next wave.
 */
public enum TourStatus {
  PLANNED, LOADING, EN_ROUTE, COMPLETED, REPLANNED, INTERRUPTED;

  public boolean terminal() {
    return this == COMPLETED || this == REPLANNED || this == INTERRUPTED;
  }

  public static final Transitions<TourStatus> RULES = Transitions.of(TourStatus.class)
      .allow(PLANNED, LOADING, REPLANNED)
      .allow(LOADING, EN_ROUTE, REPLANNED)
      .allow(EN_ROUTE, COMPLETED, INTERRUPTED)
      .build();
}
```

`state/VehicleStatus.java`:

```java
package io.nexus.contracts.state;

/** Delivery vehicle (Spec 15a). A vehicle being loaded cannot break down: its goods would have no state to go to. */
public enum VehicleStatus {
  AVAILABLE, LOADING, EN_ROUTE, RETURNING, MAINTENANCE, BROKEN_DOWN;

  public static final Transitions<VehicleStatus> RULES = Transitions.of(VehicleStatus.class)
      .allow(AVAILABLE, LOADING, MAINTENANCE, BROKEN_DOWN)
      .allow(LOADING, EN_ROUTE, AVAILABLE)
      .allow(EN_ROUTE, RETURNING, BROKEN_DOWN)
      .allow(RETURNING, AVAILABLE, BROKEN_DOWN)
      .allow(MAINTENANCE, AVAILABLE)
      .allow(BROKEN_DOWN, AVAILABLE)
      .build();
}
```

`port/ProductionEta.java`:

```java
package io.nexus.contracts.port;

/** Implemented by SmartFactory: when the finished goods of an order are expected (Spec 15a, Integration 1). */
public interface ProductionEta {
  /** Planned end of the order's last operation in simulation ms; -1 if the order has no planned operation. */
  long plannedFinishMs(String orderId);
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./mvnw -q -pl platform/contracts test -Dtest=RouteMindStatesTest`
Expected: PASS (2 tests).

- [ ] **Step 5: Add the event types and payloads**

In `EventTypes.java` after `SCENARIO_FINISHED`:

```java
  public static final String DELIVERY_CREATED = "delivery.created";
  public static final String DELIVERY_STATE = "delivery.state";
  public static final String TOUR_PLANNED = "tour.planned";
  public static final String TOUR_STATE = "tour.state";
  public static final String VEHICLE_STATE = "vehicle.state";
  public static final String DELIVERY_PLAN = "delivery.plan";
```

and in the static block, before `PAYLOADS.put(DECISION, …)`:

```java
    PAYLOADS.put(DELIVERY_CREATED, Payloads.DeliveryCreated.class);
    PAYLOADS.put(DELIVERY_STATE, Payloads.DeliveryStateChanged.class);
    PAYLOADS.put(TOUR_PLANNED, Payloads.TourPlanned.class);
    PAYLOADS.put(TOUR_STATE, Payloads.TourStateChanged.class);
    PAYLOADS.put(VEHICLE_STATE, Payloads.VehicleStateChanged.class);
    PAYLOADS.put(DELIVERY_PLAN, Payloads.DeliveryPlanComputed.class);
```

In `Payloads.java` (imports `io.nexus.contracts.state.DeliveryStatus`, `TourStatus`, `VehicleStatus`):

```java
  public record DeliveryCreated(String deliveryId, String orderId, String customerId, int quantity, int weightKg,
                                int pallets, long earliestMs, long latestMs) implements Payload {}

  public record DeliveryStateChanged(String deliveryId, DeliveryStatus from, DeliveryStatus to, String tourId,
                                     String reason) implements Payload {}

  public record TourPlanned(String tourId, String vehicleId, String planId, long departAtMs, List<String> deliveryIds,
                            double km, double cost) implements Payload {}

  public record TourStateChanged(String tourId, TourStatus from, TourStatus to, String reason) implements Payload {}

  public record VehicleStateChanged(String vehicleId, VehicleStatus from, VehicleStatus to) implements Payload {}

  /** Key figures of one plan: distance, driving time, cost (CHF), lateness against the customers' windows. */
  public record PlanFigures(double km, double driveMinutes, double cost, double lateMinutes, int tours) {}

  /** One planning run: the simple start plan (nearest neighbour) against the optimised one, and what stayed open. */
  public record DeliveryPlanComputed(String planId, String solver, long waveAtMs, String trigger, PlanFigures baseline,
                                     PlanFigures optimized, List<String> unassigned) implements Payload {}
```

- [ ] **Step 6: Give the payloads their browser JSON**

In `PlantJson.java`, the type-name function (after `MaterialRequested`):

```java
    if (p instanceof Payloads.DeliveryCreated) return "DeliveryCreated";
    if (p instanceof Payloads.DeliveryStateChanged) return "DeliveryStateChanged";
    if (p instanceof Payloads.TourPlanned) return "TourPlanned";
    if (p instanceof Payloads.TourStateChanged) return "TourStateChanged";
    if (p instanceof Payloads.VehicleStateChanged) return "VehicleStateChanged";
    if (p instanceof Payloads.DeliveryPlanComputed) return "DeliveryPlanComputed";
```

and in `payload(...)` before the final `else`:

```java
    } else if (p instanceof Payloads.DeliveryCreated x) {
      w.name("deliveryId").value(x.deliveryId()).name("orderId").value(x.orderId()).name("customerId").value(x.customerId())
          .name("quantity").value(x.quantity()).name("weightKg").value(x.weightKg()).name("pallets").value(x.pallets())
          .name("earliestMs").value(x.earliestMs()).name("latestMs").value(x.latestMs());
    } else if (p instanceof Payloads.DeliveryStateChanged x) {
      w.name("deliveryId").value(x.deliveryId()).name("from").value(name(x.from())).name("to").value(name(x.to()))
          .name("tourId").value(x.tourId()).name("reason").value(x.reason());
    } else if (p instanceof Payloads.TourPlanned x) {
      w.name("tourId").value(x.tourId()).name("vehicleId").value(x.vehicleId()).name("planId").value(x.planId())
          .name("departAtMs").value(x.departAtMs());
      strings(w.name("deliveryIds"), x.deliveryIds());
      w.name("km").value(x.km()).name("cost").value(x.cost());
    } else if (p instanceof Payloads.TourStateChanged x) {
      w.name("tourId").value(x.tourId()).name("from").value(name(x.from())).name("to").value(name(x.to()))
          .name("reason").value(x.reason());
    } else if (p instanceof Payloads.VehicleStateChanged x) {
      w.name("vehicleId").value(x.vehicleId()).name("from").value(name(x.from())).name("to").value(name(x.to()));
    } else if (p instanceof Payloads.DeliveryPlanComputed x) {
      w.name("planId").value(x.planId()).name("solver").value(x.solver()).name("waveAtMs").value(x.waveAtMs())
          .name("trigger").value(x.trigger());
      figures(w.name("baseline"), x.baseline());
      figures(w.name("optimized"), x.optimized());
      strings(w.name("unassigned"), x.unassigned());
```

and the helper next to `place(...)`:

```java
  static void figures(JsonWriter w, Payloads.PlanFigures f) {
    w.beginObject().name("km").value(f.km()).name("driveMinutes").value(f.driveMinutes()).name("cost").value(f.cost())
        .name("lateMinutes").value(f.lateMinutes()).name("tours").value(f.tours()).endObject();
  }
```

- [ ] **Step 7: Build and run the affected tests**

Run: `./mvnw -q -pl platform/contracts,platform/plant -am test -Dtest='RouteMindStatesTest,PlantJsonTest,*EventTypes*' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS. (Server-side Jackson serialises the new records without changes; Task 9 stores them.)

- [ ] **Step 8: Commit**

```bash
git add platform/contracts platform/plant/src/main/java/io/nexus/plant/PlantJson.java
git commit -m "feat(contracts): Zustände, Ereignisse und Port ProductionEta für RouteMind"
```

---

### Task 2: Verladezone in FlowPilot und im Wegenetz

**Files:**
- Modify: `platform/contracts/src/main/java/io/nexus/contracts/Domain.java` (TransportKind `SHIPMENT`)
- Modify: `platform/contracts/src/main/java/io/nexus/contracts/Place.java` (Kind `LOADING`)
- Modify: `FlowPilot/src/main/java/io/nexus/flowpilot/master/FlowPilotMasterData.java`
- Modify: `FlowPilot/src/main/java/io/nexus/flowpilot/master/PathData.java`
- Modify: `FlowPilot/src/main/java/io/nexus/flowpilot/transport/PlaceResolver.java`
- Modify: `FlowPilot/src/main/java/io/nexus/flowpilot/transport/TransportServiceImpl.java`
- Modify: `FlowPilot/src/main/java/io/nexus/flowpilot/fleet/Fleet.java:347,355`
- Create: `platform/app/src/main/resources/db/migration/V5__loading_zone.sql`
- Modify: `platform/app/src/test/java/io/nexus/app/flowpilot/MasterDataMatchesDatabaseTest.java`
- Modify: `FactoryTwin/src/twin/layout.ts` (DETOURS)
- Test: `FlowPilot/src/test/java/io/nexus/flowpilot/transport/ShipmentTest.java`, `FactoryTwin/src/twin/plantLayout.test.ts`

**Interfaces:**
- Consumes: nothing from Task 1.
- Produces: `TransportKind.SHIPMENT`; `Place.Kind.LOADING`, `Place.loading(String)`; `FlowPilotMasterData.LoadingZone(String id, String name, String dockNode)`, `FlowPilotMasterData.loadingZones()`, `FlowPilotMasterData.LOADING_ZONE = "LZ-1"`; graph nodes `D_LZ_1`, `D_LZ_2`, `D_LZ` (dock), `D_LZ_4`.

- [ ] **Step 1: Write the failing FlowPilot test**

`FlowPilot/src/test/java/io/nexus/flowpilot/transport/ShipmentTest.java` — uses the same in-memory FlowPilot as `TransportServiceTest`; look at that file's setup and copy its fields (`work`, `bus`, `time`, `fp`, helper `runUntil`). The test body:

```java
package io.nexus.flowpilot.transport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.nexus.contracts.Domain.Priority;
import io.nexus.contracts.Domain.TransportKind;
import io.nexus.contracts.DomainException;
import io.nexus.contracts.Place;
import io.nexus.contracts.event.EventBus;
import io.nexus.contracts.event.EventIds;
import io.nexus.contracts.event.EventTypes;
import io.nexus.contracts.event.NexusEvent;
import io.nexus.contracts.event.Payloads;
import io.nexus.contracts.memory.MemoryEventLog;
import io.nexus.contracts.memory.MemoryIdSource;
import io.nexus.contracts.memory.MemoryUnitOfWork;
import io.nexus.contracts.port.MaterialService.Line;
import io.nexus.contracts.port.MaterialService.ReservationRequest;
import io.nexus.contracts.port.MaterialService.ReservationResult;
import io.nexus.contracts.port.TransportService.TransportRequest;
import io.nexus.contracts.sim.SimTime;
import io.nexus.flowpilot.FlowPilot;
import io.nexus.flowpilot.master.FlowPilotMasterData;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Finished goods to the loading zone (Spec 15a, Integration 2 and 3). */
class ShipmentTest {
  private final MemoryUnitOfWork work = new MemoryUnitOfWork();
  private final MemoryEventLog log = new MemoryEventLog(work);
  private final SimTime time = new SimTime();
  private final EventIds ids = new EventIds(1);
  private final EventBus bus = new EventBus(log, work, time, () -> ids);
  private final FlowPilot fp = FlowPilot.inMemory(bus, work, time, new MemoryIdSource(work));

  private ReservationResult.Success reserveFinished(int qty) {
    return (ReservationResult.Success) fp.materials().reserve(
        new ReservationRequest("DL-0001/R", "PO-0001", List.of(new Line("FG-GH200", qty))));
  }

  private TransportRequest shipment(ReservationResult.Success r, int qty) {
    var res = r.reservations().get(0);
    return new TransportRequest("DL-0001/V", TransportKind.SHIPMENT, "PO-0001", "FG-GH200", qty,
        Place.storage(res.locationId()), Place.loading(FlowPilotMasterData.LOADING_ZONE), Priority.NORMAL,
        res.reservationId());
  }

  @Test
  void shipmentCarriesReservedGoodsToTheLoadingZoneAndBooksThemOut() {
    String id = fp.transports().requestTransport(shipment(reserveFinished(4), 4));
    for (int i = 0; i < 6000 && !delivered(id); i++) {
      time.advance(100);
      fp.step(100);
    }
    assertThat(delivered(id)).as("delivered within 10 min").isTrue();
    var event = log.all().stream().filter(e -> e.type().equals(EventTypes.TRANSPORT_DELIVERED)).findFirst().orElseThrow();
    var d = (Payloads.TransportDelivered) event.payload();
    assertThat(d.kind()).isEqualTo(TransportKind.SHIPMENT);
    assertThat(d.to()).isEqualTo(Place.loading("LZ-1"));
    // 6 at start, 4 left with the shipment, nothing stays reserved
    assertThat(fp.stock()).anySatisfy(l -> {
      assertThat(l.materialId()).isEqualTo("FG-GH200");
      assertThat(l.onHand()).isEqualTo(2);
      assertThat(l.reserved()).isZero();
    });
  }

  @Test
  void shipmentNeedsAReservationAndTheLoadingZone() {
    var r = reserveFinished(2);
    var noReservation = new TransportRequest("X/1", TransportKind.SHIPMENT, "PO-0001", "FG-GH200", 2,
        Place.storage("FG-A"), Place.loading("LZ-1"), Priority.NORMAL, null);
    assertThatThrownBy(() -> fp.transports().requestTransport(noReservation)).isInstanceOf(DomainException.class);
    var toMachine = new TransportRequest("X/2", TransportKind.SHIPMENT, "PO-0001", "FG-GH200", 2,
        Place.storage("FG-A"), Place.machineIn("CNC-01"), Priority.NORMAL, r.reservations().get(0).reservationId());
    assertThatThrownBy(() -> fp.transports().requestTransport(toMachine)).isInstanceOf(DomainException.class)
        .hasMessageContaining("Verladezone");
    var unknownZone = new TransportRequest("X/3", TransportKind.SHIPMENT, "PO-0001", "FG-GH200", 2,
        Place.storage("FG-A"), Place.loading("LZ-9"), Priority.NORMAL, r.reservations().get(0).reservationId());
    assertThatThrownBy(() -> fp.transports().requestTransport(unknownZone)).isInstanceOf(DomainException.class);
  }

  private boolean delivered(String transportId) {
    for (NexusEvent e : log.all()) {
      if (e.type().equals(EventTypes.TRANSPORT_DELIVERED)
          && ((Payloads.TransportDelivered) e.payload()).transportId().equals(transportId)) return true;
    }
    return false;
  }
}
```

If `MemoryEventLog` has no `all()`, use the accessor `TransportServiceTest` uses to read events (look it up; do not add a new one).

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw -q -pl FlowPilot -am test -Dtest=ShipmentTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: COMPILATION ERROR, `SHIPMENT` / `Place.loading` / `LOADING_ZONE` not found.

- [ ] **Step 3: Contracts**

`Domain.java`: `public enum TransportKind { SUPPLY, WIP, PUTAWAY, SHIPMENT }` — `SHIPMENT`: finished goods from the finished-goods store to the loading zone (RouteMind).

`Place.java`: add to `Kind` after `CHARGER`:

```java
    /** A loading zone where RouteMind loads its delivery vehicles, e.g. LZ-1. */
    LOADING
```

and

```java
  public static Place loading(String zoneId) {
    return new Place(Kind.LOADING, zoneId);
  }
```

- [ ] **Step 4: Master data and path network**

`FlowPilotMasterData.java`: next to the other records

```java
  /** Where RouteMind's vehicles are loaded; reached through dockNode. */
  public record LoadingZone(String id, String name, String dockNode) {}

  public static final String LOADING_ZONE = "LZ-1";
  private static final List<LoadingZone> LOADING_ZONES = List.of(new LoadingZone(LOADING_ZONE, "Verladezone Süd", "D_LZ"));

  public static List<LoadingZone> loadingZones() { return LOADING_ZONES; }
```

`PathData.java`: append to `NODES` (after the last node):

```java
      new Node("D_LZ_1", 23.2, 5.5, false),
      new Node("D_LZ_2", 23.2, 14.0, false),
      new Node("D_LZ", 22.0, 15.4, true),
      new Node("D_LZ_4", 20.8, 14.0, false)
```

and to `EDGES` (after the last edge):

```java
      new Edge("J_20_3>D_LZ_1", "J_20_3", "D_LZ_1", 4.061),
      new Edge("D_LZ_1>D_LZ_2", "D_LZ_1", "D_LZ_2", 8.500),
      new Edge("D_LZ_2>D_LZ", "D_LZ_2", "D_LZ", 1.844),
      new Edge("D_LZ>D_LZ_4", "D_LZ", "D_LZ_4", 1.844),
      new Edge("D_LZ_4>LS_19.3", "D_LZ_4", "LS_19.3", 11.102)
```

Change the header comment to `// Generated from V2__path_graph.sql, plus the loading zone of V5__loading_zone.sql; MasterDataMatchesDatabaseTest keeps both equal.`

`V5__loading_zone.sql`:

```sql
-- Loading zone (RouteMind, Spec 15a): a one-way detour from the ring at J_20_3 into the south-east corner of the
-- hall and back to LS_19.3. Coordinates match FactoryTwin/src/twin/layout.ts (DETOURS, dock D_LZ).
insert into graph_node (id, x, z, dock) values
  ('D_LZ_1', 23.2, 5.5, false),
  ('D_LZ_2', 23.2, 14, false),
  ('D_LZ', 22, 15.4, true),
  ('D_LZ_4', 20.8, 14, false);

insert into graph_edge (id, from_node, to_node, length) values
  ('J_20_3>D_LZ_1', 'J_20_3', 'D_LZ_1', 4.061),
  ('D_LZ_1>D_LZ_2', 'D_LZ_1', 'D_LZ_2', 8.500),
  ('D_LZ_2>D_LZ', 'D_LZ_2', 'D_LZ', 1.844),
  ('D_LZ>D_LZ_4', 'D_LZ', 'D_LZ_4', 1.844),
  ('D_LZ_4>LS_19.3', 'D_LZ_4', 'LS_19.3', 11.102);

create table loading_zone (
  id        text primary key,
  name      text not null,
  dock_node text not null references graph_node
);
insert into loading_zone (id, name, dock_node) values ('LZ-1', 'Verladezone Süd', 'D_LZ');

alter table transport_order
  drop constraint transport_order_kind_check,
  add constraint transport_order_kind_check check (kind in ('SUPPLY', 'WIP', 'PUTAWAY', 'SHIPMENT')),
  drop constraint transport_order_from_kind_check,
  add constraint transport_order_from_kind_check
    check (from_kind in ('STORAGE', 'MACHINE_IN', 'MACHINE_OUT', 'CONVEYOR', 'CHARGER', 'LOADING')),
  drop constraint transport_order_to_kind_check,
  add constraint transport_order_to_kind_check
    check (to_kind in ('STORAGE', 'MACHINE_IN', 'MACHINE_OUT', 'CONVEYOR', 'CHARGER', 'LOADING'));
```

If Flyway reports that a constraint name does not exist, read the real names with `select conname from pg_constraint where conrelid = 'transport_order'::regclass` in a test and use them (ruling in the ledger).

- [ ] **Step 5: Resolver, validation, fleet**

`PlaceResolver.java`: field `private final Map<String, String> loadingZones = new HashMap<>();`, in the constructor `for (var z : FlowPilotMasterData.loadingZones()) loadingZones.put(z.id(), z.dockNode());`, and in the switch `case LOADING -> found(loadingZones.get(id), "Verladezone", id);`.

`TransportServiceImpl.validate`: replace `if (r.kind() == TransportKind.SUPPLY) {` by `if (r.kind() == TransportKind.SUPPLY || r.kind() == TransportKind.SHIPMENT) {` (same reservation check; the message stays) and add after that block:

```java
    if (r.kind() == TransportKind.SHIPMENT) {
      if (r.to().kind() != Place.Kind.LOADING) throw DomainException.invalid("Verladung geht nur in die Verladezone");
      if (!places.location(r.from().id()).zone().equals("FINISHED")) {
        throw DomainException.invalid("Verladen wird nur aus dem Fertiglager");
      }
    }
```

`Fleet.java` lines 347 and 355: replace `t.kind() == TransportKind.SUPPLY` by `(t.kind() == TransportKind.SUPPLY || t.kind() == TransportKind.SHIPMENT)` in both conditions (stock leaves at pickup; a released reservation cancels the transport). Unloading at the loading zone books nothing in: only `PUTAWAY` receives (line 422 unchanged).

- [ ] **Step 6: Run the FlowPilot tests**

Run: `./mvnw -q -pl FlowPilot -am test`
Expected: PASS, including `ShipmentTest` (2 tests) and the existing FlowPilot tests.

- [ ] **Step 7: Database matches master data**

Add to `MasterDataMatchesDatabaseTest`:

```java
  @Test
  void loadingZones() {
    var db = jdbc.sql("select id, name, dock_node from loading_zone order by id collate \"C\"")
        .query((rs, i) -> new FlowPilotMasterData.LoadingZone(rs.getString(1), rs.getString(2), rs.getString(3)))
        .list();
    assertThat(sorted(FlowPilotMasterData.loadingZones(), FlowPilotMasterData.LoadingZone::id)).isEqualTo(db);
  }
```

Run: `./mvnw -q -pl platform/app -am test -Dtest='io.nexus.app.flowpilot.MasterDataMatchesDatabaseTest,SchemaTest' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS (nodes and edges compare equal with the new rows).

- [ ] **Step 8: FactoryTwin layout — failing test first**

Append to `FactoryTwin/src/twin/plantLayout.test.ts`:

```ts
import { PATH_GRAPH } from './layout';

describe('loading zone', () => {
  it('has the dock D_LZ on a one-way detour from J_20_3 back to LS_19.3, as in V5__loading_zone.sql', () => {
    const pos = (id: string) => PATH_GRAPH.nodes.get(id)?.pos;
    expect(pos('D_LZ')).toEqual({ x: 22, z: 15.4 });
    expect(PATH_GRAPH.nodes.get('D_LZ')?.dock).toBe(true);
    expect(pos('D_LZ_1')).toEqual({ x: 23.2, z: 5.5 });
    expect(pos('D_LZ_2')).toEqual({ x: 23.2, z: 14 });
    expect(pos('D_LZ_4')).toEqual({ x: 20.8, z: 14 });
    const edges = PATH_GRAPH.edges.map((e) => `${e.from}>${e.to}`);
    expect(edges).toEqual(expect.arrayContaining(['J_20_3>D_LZ_1', 'D_LZ_1>D_LZ_2', 'D_LZ_2>D_LZ', 'D_LZ>D_LZ_4', 'D_LZ_4>LS_19.3']));
  });
});
```

(Merge the `import` with the file's existing imports.)

Run: `cd FactoryTwin && npx vitest run src/twin/plantLayout.test.ts`
Expected: FAIL, `pos('D_LZ')` is undefined.

- [ ] **Step 9: Add the detour**

In `layout.ts` `DETOURS`, after the `D_CV03` entry:

```ts
  // Loading zone (RouteMind): out of the ring at the south-east corner, along the finished-goods store to the
  // truck bays and back onto the south lane.
  {
    dock: 'D_LZ',
    lane: 'S',
    at: 19.6,
    depth: 0,
    custom: {
      points: [
        { x: 20, z: 3 },
        { x: 23.2, z: 5.5 },
        { x: 23.2, z: 14 },
        { x: 22, z: 15.4 },
        { x: 20.8, z: 14 },
        { x: 19.3, z: 3 },
      ],
      dockIndex: 3,
    },
  },
```

Run: `cd FactoryTwin && npx vitest run src/twin`
Expected: PASS. If the entry node is named `LS_20` instead of `J_20_3`, `addNode` does not merge equal positions: change the custom points' entry to start from the existing junction the way `D_CV03` does and record a ruling; the SQL and `PathData` must use the generator's names.

- [ ] **Step 10: Commit**

```bash
git add platform/contracts FlowPilot platform/app/src/main/resources/db/migration/V5__loading_zone.sql platform/app/src/test FactoryTwin/src/twin
git commit -m "feat(flowpilot): Verladezone LZ-1 und Transportart SHIPMENT"
```

---

### Task 3: SmartFactory meldet die voraussichtliche Fertigstellung

**Files:**
- Modify: `SmartFactory/src/main/java/io/nexus/smartfactory/SmartFactory.java`
- Test: `platform/plant/src/test/java/io/nexus/plant/ProductionEtaTest.java`

**Interfaces:**
- Consumes: `ProductionEta` (Task 1).
- Produces: `SmartFactory.eta(): ProductionEta` — planned end of the order's last planned operation, `-1` if none.

- [ ] **Step 1: Write the failing test**

```java
package io.nexus.plant;

import static org.assertj.core.api.Assertions.assertThat;

import io.nexus.contracts.Domain.Priority;
import io.nexus.smartfactory.orders.NewOrder;
import io.nexus.smartfactory.planning.Plan;
import org.junit.jupiter.api.Test;

class ProductionEtaTest {
  @Test
  void etaIsTheEndOfTheLastPlannedOperation() {
    Plant plant = Plant.inMemory(4711, Scenario1h.rules());
    String id = plant.smartFactory().createOrder(new NewOrder("GH200", 3, Priority.NORMAL, 90 * 60_000L));
    plant.advance(5_000);
    long expected = -1;
    for (Plan.PlannedOp op : plant.smartFactory().plan().ops()) {
      if (op.orderId().equals(id)) expected = Math.max(expected, op.endMs());
    }
    assertThat(expected).isPositive();
    assertThat(plant.smartFactory().eta().plannedFinishMs(id)).isEqualTo(expected);
    assertThat(plant.smartFactory().eta().plannedFinishMs("PO-9999")).isEqualTo(-1);
  }
}
```

If `Scenario1h.rules()` is not the name of the rules factory in `Scenario1h.java`, use the method that file offers (it returns rules without automatic orders).

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw -q -pl platform/plant -am test -Dtest=ProductionEtaTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: COMPILATION ERROR, `eta()` not found.

- [ ] **Step 3: Implement**

In `SmartFactory.java` (import `io.nexus.contracts.port.ProductionEta`), next to `catalog()`:

```java
  /** ProductionEta for RouteMind: planned end of the order's last planned operation, -1 if none is planned. */
  public ProductionEta eta() {
    return orderId -> {
      synchronized (lock) {
        long end = -1;
        for (Plan.PlannedOp op : plan().ops()) if (op.orderId().equals(orderId)) end = Math.max(end, op.endMs());
        return end;
      }
    };
  }
```

(`plan()` already returns the current plan; import `io.nexus.smartfactory.planning.Plan` if not present.)

- [ ] **Step 4: Run test to verify it passes**

Run: `./mvnw -q -pl platform/plant -am test -Dtest=ProductionEtaTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add SmartFactory platform/plant/src/test/java/io/nexus/plant/ProductionEtaTest.java
git commit -m "feat(smartfactory): voraussichtliche Fertigstellung über den Port ProductionEta"
```

---
### Task 4: Modul RouteMind und Strassennetz Zürich

**Files:**
- Create: `RouteMind/pom.xml`
- Modify: `pom.xml` (Modul und dependencyManagement)
- Create: `RouteMind/roads/places.json`
- Create: `FactoryTwin/scripts/build-roads.mjs`
- Create (erzeugt): `RouteMind/src/main/java/io/nexus/routemind/master/RoadData.java`, `FactoryTwin/public/routemind/roads.json`
- Create: `RouteMind/src/main/java/io/nexus/routemind/master/Roads.java`
- Test: `RouteMind/src/test/java/io/nexus/routemind/master/RoadsTest.java`

**Interfaces:**
- Produces: `Roads.DEPOT = "D0"`, `Roads.Place(int index, String id, String name, double lat, double lon)`, `Roads.places()`, `Roads.customers()`, `Roads.place(String)`, `Roads.seconds(int,int)`, `Roads.meters(int,int)`, `Roads.attribution()`; `roads.json` = `{attribution, generated, places:[{id,name,lat,lon}], geometry:{"<a>>"<b>": "<polyline5>"}}` with one entry per unordered pair, `a` before `b` in place order.

- [ ] **Step 1: Module**

`RouteMind/pom.xml`:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
  <modelVersion>4.0.0</modelVersion>
  <parent><groupId>io.nexus</groupId><artifactId>nexus</artifactId><version>0.1.0-SNAPSHOT</version><relativePath>../pom.xml</relativePath></parent>
  <artifactId>routemind</artifactId>
  <dependencies>
    <dependency><groupId>io.nexus</groupId><artifactId>nexus-contracts</artifactId></dependency>
    <dependency><groupId>org.junit.jupiter</groupId><artifactId>junit-jupiter</artifactId><scope>test</scope></dependency>
    <dependency><groupId>org.assertj</groupId><artifactId>assertj-core</artifactId><scope>test</scope></dependency>
  </dependencies>
  <build>
    <plugins>
      <plugin><groupId>org.apache.maven.plugins</groupId><artifactId>maven-enforcer-plugin</artifactId></plugin>
      <!-- the store contract test is reused by platform/app against PostgreSQL -->
      <plugin>
        <groupId>org.apache.maven.plugins</groupId><artifactId>maven-jar-plugin</artifactId>
        <executions><execution><goals><goal>test-jar</goal></goals></execution></executions>
      </plugin>
    </plugins>
  </build>
</project>
```

Root `pom.xml`: `<module>RouteMind</module>` after `<module>SmartFactory</module>`, and in `dependencyManagement` after `smartfactory`:

```xml
      <dependency><groupId>io.nexus</groupId><artifactId>routemind</artifactId><version>${project.version}</version></dependency>
```

- [ ] **Step 2: Places**

`RouteMind/roads/places.json` — the depot at the plant and 30 customers in the region. Names are invented ("Kunde <Ort>"), positions are the village centres; OSRM snaps them to the next road.

```json
[
  { "id": "D0", "name": "Werk NEXUS, Schlieren", "lat": 47.3967, "lon": 8.4470 },
  { "id": "K01", "name": "Kunde Altstetten", "lat": 47.3913, "lon": 8.4886 },
  { "id": "K02", "name": "Kunde Oerlikon", "lat": 47.4111, "lon": 8.5442 },
  { "id": "K03", "name": "Kunde Wollishofen", "lat": 47.3420, "lon": 8.5310 },
  { "id": "K04", "name": "Kunde Seefeld", "lat": 47.3560, "lon": 8.5550 },
  { "id": "K05", "name": "Kunde Dietikon", "lat": 47.4017, "lon": 8.4005 },
  { "id": "K06", "name": "Kunde Urdorf", "lat": 47.3850, "lon": 8.4260 },
  { "id": "K07", "name": "Kunde Birmensdorf", "lat": 47.3570, "lon": 8.4380 },
  { "id": "K08", "name": "Kunde Adliswil", "lat": 47.3100, "lon": 8.5250 },
  { "id": "K09", "name": "Kunde Thalwil", "lat": 47.2920, "lon": 8.5640 },
  { "id": "K10", "name": "Kunde Horgen", "lat": 47.2600, "lon": 8.5980 },
  { "id": "K11", "name": "Kunde Wädenswil", "lat": 47.2290, "lon": 8.6720 },
  { "id": "K12", "name": "Kunde Küsnacht", "lat": 47.3180, "lon": 8.5830 },
  { "id": "K13", "name": "Kunde Meilen", "lat": 47.2700, "lon": 8.6430 },
  { "id": "K14", "name": "Kunde Stäfa", "lat": 47.2400, "lon": 8.7230 },
  { "id": "K15", "name": "Kunde Uster", "lat": 47.3480, "lon": 8.7180 },
  { "id": "K16", "name": "Kunde Dübendorf", "lat": 47.3970, "lon": 8.6180 },
  { "id": "K17", "name": "Kunde Wallisellen", "lat": 47.4150, "lon": 8.5960 },
  { "id": "K18", "name": "Kunde Kloten", "lat": 47.4510, "lon": 8.5840 },
  { "id": "K19", "name": "Kunde Bülach", "lat": 47.5220, "lon": 8.5400 },
  { "id": "K20", "name": "Kunde Regensdorf", "lat": 47.4340, "lon": 8.4680 },
  { "id": "K21", "name": "Kunde Affoltern am Albis", "lat": 47.2770, "lon": 8.4500 },
  { "id": "K22", "name": "Kunde Volketswil", "lat": 47.3880, "lon": 8.6900 },
  { "id": "K23", "name": "Kunde Wetzikon", "lat": 47.3260, "lon": 8.7980 },
  { "id": "K24", "name": "Kunde Winterthur", "lat": 47.4990, "lon": 8.7240 },
  { "id": "K25", "name": "Kunde Effretikon", "lat": 47.4260, "lon": 8.6870 },
  { "id": "K26", "name": "Kunde Opfikon", "lat": 47.4300, "lon": 8.5700 },
  { "id": "K27", "name": "Kunde Schwerzenbach", "lat": 47.3820, "lon": 8.6570 },
  { "id": "K28", "name": "Kunde Bassersdorf", "lat": 47.4440, "lon": 8.6280 },
  { "id": "K29", "name": "Kunde Dielsdorf", "lat": 47.4810, "lon": 8.4580 },
  { "id": "K30", "name": "Kunde Oberengstringen", "lat": 47.4080, "lon": 8.4650 }
]
```

- [ ] **Step 3: Generator**

`FactoryTwin/scripts/build-roads.mjs`:

```js
// One-time generator for RouteMind's road network (Spec 15a, "Strassennetz"). Not part of any build: it asks the
// public OSRM demo server (OpenStreetMap data, ODbL) for driving time and distance between every pair of places and
// for the road geometry between them, and writes
//   RouteMind/src/main/java/io/nexus/routemind/master/RoadData.java   matrix for the core (both runtimes)
//   FactoryTwin/public/routemind/roads.json                            places and geometry for the map
// Usage, from the repository root: node FactoryTwin/scripts/build-roads.mjs   (about 9 minutes, ~1 request/s)
import { mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '../..');
const OSRM = 'https://router.project-osrm.org';
const ATTRIBUTION = 'Strassendaten © OpenStreetMap-Mitwirkende (ODbL), Routen berechnet mit OSRM';
const places = JSON.parse(readFileSync(resolve(root, 'RouteMind/roads/places.json'), 'utf8'));
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const round5 = (v) => Math.round(v * 1e5) / 1e5;

async function get(path) {
  for (let attempt = 1; ; attempt++) {
    try {
      const res = await fetch(OSRM + path, { headers: { 'User-Agent': 'nexus-roads-generator/1.0' } });
      if (!res.ok) throw new Error(`HTTP ${res.status}`);
      const body = await res.json();
      if (body.code !== 'Ok') throw new Error(`${body.code} ${body.message ?? ''}`);
      return body;
    } catch (e) {
      if (attempt === 5) throw new Error(`${path}: ${e.message}`);
      await sleep(3000 * attempt);
    }
  }
}

/** Google polyline, precision 5: [[lat, lon], ...]. */
function decode(text) {
  const points = [];
  let i = 0;
  let lat = 0;
  let lon = 0;
  while (i < text.length) {
    for (let axis = 0; axis < 2; axis++) {
      let shift = 0;
      let result = 0;
      let b;
      do {
        b = text.charCodeAt(i++) - 63;
        result |= (b & 0x1f) << shift;
        shift += 5;
      } while (b >= 0x20);
      const delta = result & 1 ? ~(result >> 1) : result >> 1;
      if (axis === 0) lat += delta;
      else lon += delta;
    }
    points.push([lat / 1e5, lon / 1e5]);
  }
  return points;
}

function encode(points) {
  let out = '';
  let prevLat = 0;
  let prevLon = 0;
  const put = (value) => {
    let v = value < 0 ? ~(value << 1) : value << 1;
    while (v >= 0x20) {
      out += String.fromCharCode((0x20 | (v & 0x1f)) + 63);
      v >>= 5;
    }
    out += String.fromCharCode(v + 63);
  };
  for (const [la, lo] of points) {
    const a = Math.round(la * 1e5);
    const b = Math.round(lo * 1e5);
    put(a - prevLat);
    put(b - prevLon);
    prevLat = a;
    prevLon = b;
  }
  return out;
}

/** Distance of p from segment a-b in degrees, longitude scaled to the latitude. */
function segmentDistance(p, a, b) {
  const c = Math.cos((a[0] * Math.PI) / 180);
  const [px, py, ax, ay, bx, by] = [p[1] * c, p[0], a[1] * c, a[0], b[1] * c, b[0]];
  const dx = bx - ax;
  const dy = by - ay;
  const len = dx * dx + dy * dy;
  const t = len === 0 ? 0 : Math.max(0, Math.min(1, ((px - ax) * dx + (py - ay) * dy) / len));
  return Math.hypot(px - (ax + t * dx), py - (ay + t * dy));
}

/** Douglas-Peucker; 0.00005° is about 5 m. */
function simplify(points, tolerance) {
  if (points.length < 3) return points;
  const keep = points.map((_, k) => k === 0 || k === points.length - 1);
  const stack = [[0, points.length - 1]];
  while (stack.length > 0) {
    const [a, b] = stack.pop();
    let best = -1;
    let dist = 0;
    for (let k = a + 1; k < b; k++) {
      const d = segmentDistance(points[k], points[a], points[b]);
      if (d > dist) {
        dist = d;
        best = k;
      }
    }
    if (best >= 0 && dist > tolerance) {
      keep[best] = true;
      stack.push([a, best], [best, b]);
    }
  }
  return points.filter((_, k) => keep[k]);
}

const coords = places.map((p) => `${p.lon},${p.lat}`).join(';');
const table = await get(`/table/v1/driving/${coords}?annotations=duration,distance`);
for (const [matrix, what] of [[table.durations, 'Fahrzeit'], [table.distances, 'Strecke']]) {
  matrix.forEach((row, i) =>
    row.forEach((v, j) => {
      if (v == null || (i !== j && v <= 0)) throw new Error(`${what} ${places[i].id}>${places[j].id} fehlt`);
    }),
  );
}
const seconds = table.durations.map((row) => row.map((v) => Math.round(v)));
const meters = table.distances.map((row) => row.map((v) => Math.round(v)));
// where the road network reaches each place; the map draws them there
const snapped = table.sources.map((s, i) => ({ ...places[i], lat: round5(s.location[1]), lon: round5(s.location[0]) }));

const geometry = {};
for (let i = 0; i < snapped.length; i++) {
  for (let j = i + 1; j < snapped.length; j++) {
    const a = snapped[i];
    const b = snapped[j];
    const route = await get(`/route/v1/driving/${a.lon},${a.lat};${b.lon},${b.lat}?overview=full&geometries=polyline`);
    geometry[`${a.id}>${b.id}`] = encode(simplify(decode(route.routes[0].geometry), 0.00005));
    await sleep(1100); // the demo server allows about one request per second
  }
  console.log(`${snapped[i].id} fertig`);
}

const today = new Date().toISOString().slice(0, 10);
mkdirSync(resolve(root, 'FactoryTwin/public/routemind'), { recursive: true });
writeFileSync(
  resolve(root, 'FactoryTwin/public/routemind/roads.json'),
  JSON.stringify({ attribution: ATTRIBUTION, generated: today, places: snapped, geometry }) + '\n',
);

const strings = (xs) => xs.map((x) => JSON.stringify(x)).join(', ');
const rows = (m) => m.map((r) => `      {${r.join(', ')}}`).join(',\n');
writeFileSync(
  resolve(root, 'RouteMind/src/main/java/io/nexus/routemind/master/RoadData.java'),
  `// Generated by FactoryTwin/scripts/build-roads.mjs from RouteMind/roads/places.json on ${today}. Do not edit.
// ${ATTRIBUTION} (router.project-osrm.org).
package io.nexus.routemind.master;

/** Fixed road network of RouteMind: places (snapped to the road) with OSRM driving time and distance. */
final class RoadData {
  private RoadData() {}

  static final String ATTRIBUTION = ${JSON.stringify(ATTRIBUTION)};
  static final String[] IDS = {${strings(snapped.map((p) => p.id))}};
  static final String[] NAMES = {${strings(snapped.map((p) => p.name))}};
  static final double[] LAT = {${snapped.map((p) => p.lat).join(', ')}};
  static final double[] LON = {${snapped.map((p) => p.lon).join(', ')}};
  /** Driving time in seconds; row = from, column = to. */
  static final int[][] SECONDS = {
${rows(seconds)}
  };
  /** Driving distance in metres; row = from, column = to. */
  static final int[][] METERS = {
${rows(meters)}
  };
}
`,
);
console.log('roads.json und RoadData.java geschrieben');
```

- [ ] **Step 4: Run the generator once**

Run (in the background, it takes about 9 minutes): `node FactoryTwin/scripts/build-roads.mjs`
Expected: 31 lines `… fertig`, then `roads.json und RoadData.java geschrieben`. `roads.json` is about 100–200 KB. If the demo server refuses (HTTP 429 after five tries), wait a few minutes and run again; never fall back to straight lines.

- [ ] **Step 5: Write the failing test**

```java
package io.nexus.routemind.master;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class RoadsTest {
  @Test
  void depotFirstThenThirtyCustomers() {
    assertThat(Roads.places()).hasSize(31);
    assertThat(Roads.places().get(0).id()).isEqualTo(Roads.DEPOT);
    assertThat(Roads.customers()).hasSize(30).allSatisfy(c -> assertThat(c.id()).startsWith("K"));
    assertThat(Roads.place("K24").name()).isEqualTo("Kunde Winterthur");
    assertThat(Roads.attribution()).contains("OpenStreetMap");
  }

  /** Every time and distance comes from the road network: none is shorter than the straight line. */
  @Test
  void matrixIsCompleteAndNoAirLine() {
    int n = Roads.places().size();
    for (int i = 0; i < n; i++) {
      for (int j = 0; j < n; j++) {
        if (i == j) {
          assertThat(Roads.seconds(i, j)).isZero();
          continue;
        }
        var a = Roads.places().get(i);
        var b = Roads.places().get(j);
        double air = haversineMeters(a.lat(), a.lon(), b.lat(), b.lon());
        assertThat(Roads.meters(i, j)).as(a.id() + ">" + b.id()).isGreaterThanOrEqualTo((int) (air * 0.98));
        // no faster than 130 km/h on average
        assertThat(Roads.seconds(i, j)).as(a.id() + ">" + b.id()).isGreaterThanOrEqualTo((int) (Roads.meters(i, j) / 36.2));
      }
    }
  }

  /** The map file belongs to the same generator run: same places in the same order. */
  @Test
  void mapFileHasTheSamePlaces() throws Exception {
    String json = Files.readString(Path.of("../FactoryTwin/public/routemind/roads.json"));
    List<String> ids = new ArrayList<>();
    Matcher m = Pattern.compile("\"id\":\"(D0|K\\d\\d)\"").matcher(json);
    while (m.find()) ids.add(m.group(1));
    List<String> expected = new ArrayList<>();
    for (var p : Roads.places()) expected.add(p.id());
    assertThat(ids).isEqualTo(expected);
    assertThat(json).contains("OpenStreetMap");
  }

  private static double haversineMeters(double lat1, double lon1, double lat2, double lon2) {
    double r = 6_371_000;
    double dLat = Math.toRadians(lat2 - lat1);
    double dLon = Math.toRadians(lon2 - lon1);
    double h = Math.sin(dLat / 2) * Math.sin(dLat / 2)
        + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) * Math.sin(dLon / 2) * Math.sin(dLon / 2);
    return 2 * r * Math.asin(Math.sqrt(h));
  }
}
```

Run: `./mvnw -q -pl RouteMind -am test -Dtest=RoadsTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: COMPILATION ERROR, `Roads` not found.

- [ ] **Step 6: Implement `Roads`**

```java
package io.nexus.routemind.master;

import io.nexus.contracts.DomainException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** The fixed road network (Spec 15a): depot D0 at the plant and 30 customers, with OSRM times and distances. */
public final class Roads {
  public static final String DEPOT = "D0";

  public record Place(int index, String id, String name, double lat, double lon) {}

  private static final List<Place> PLACES;
  private static final Map<String, Place> BY_ID = new HashMap<>(); // lookups only

  static {
    List<Place> list = new ArrayList<>();
    for (int i = 0; i < RoadData.IDS.length; i++) {
      Place p = new Place(i, RoadData.IDS[i], RoadData.NAMES[i], RoadData.LAT[i], RoadData.LON[i]);
      list.add(p);
      BY_ID.put(p.id(), p);
    }
    PLACES = Collections.unmodifiableList(list);
  }

  private Roads() {}

  /** The depot first, then the customers in the order of places.json. */
  public static List<Place> places() {
    return PLACES;
  }

  public static List<Place> customers() {
    return PLACES.subList(1, PLACES.size());
  }

  public static Place place(String id) {
    Place p = id == null ? null : BY_ID.get(id);
    if (p == null) throw DomainException.notFound("Ort", id);
    return p;
  }

  public static int seconds(int from, int to) {
    return RoadData.SECONDS[from][to];
  }

  public static int meters(int from, int to) {
    return RoadData.METERS[from][to];
  }

  public static String attribution() {
    return RoadData.ATTRIBUTION;
  }
}
```

Run: `./mvnw -q -pl RouteMind -am test -Dtest=RoadsTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS (3 tests).

- [ ] **Step 7: Commit**

```bash
git add pom.xml RouteMind FactoryTwin/scripts/build-roads.mjs FactoryTwin/public/routemind/roads.json
git commit -m "feat(routemind): Modul und Strassennetz Zürich aus OSRM mit OSM-Quellenangabe"
```

---

### Task 5: RouteMind – Stammdaten, Datensätze, Speicher

**Files:**
- Create: `RouteMind/src/main/java/io/nexus/routemind/master/RouteMindMasterData.java`
- Create: `RouteMind/src/main/java/io/nexus/routemind/store/Delivery.java`, `Tour.java`, `VehicleRecord.java`, `RouteStore.java`
- Create: `RouteMind/src/main/java/io/nexus/routemind/store/memory/MemoryRouteStore.java`
- Test: `RouteMind/src/test/java/io/nexus/routemind/store/RouteStoreContract.java`, `MemoryRouteStoreTest.java`

**Interfaces:**
- Consumes: `DeliveryStatus`, `TourStatus`, `VehicleStatus` (Task 1).
- Produces:
  - `RouteMindMasterData.MINUTE/HOUR/DAY/SHIFT_END`, `VehicleSeed(id, name, maxKg, maxPallets, costPerKm, costPerHour)`, `Freight(productId, kgPerUnit, unitsPerPallet)`, `vehicles()`, `vehicle(id)`, `freight(productId)`, `pallets(Freight, int)`.
  - `Delivery(id, orderId, customerId, productId, quantity, storedQuantity, weightKg, pallets, priority, earliestMs, latestMs, status, tourId, plannedArrivalMs, deliveredAtMs, reservationId, transportId, failures, createdAtMs, version)` with `withStatus`, `withTour`, `withGoods`, `withShipment`, `withFailure`, `withDelivered`.
  - `Tour(id, planId, vehicleId, departAtMs, status, stops, km, cost, startedAtMs, endedAtMs, version)`, `Tour.Stop(seq, deliveryId, customerId, plannedArrivalMs, arrivedAtMs)`, withers `withStatus`, `withStarted`, `withEnded`, `withStops`, `withArrival`.
  - `VehicleRecord(id, status, tourId, leg, legStartMs, legEndMs, breakdownAtMs, readyAtMs, version)` with `atDepot(id)`, `withStatus`, `withTour`, `withLeg`, `withBreakdown`, `back`.
  - `RouteStore` (below), `MemoryRouteStore(MemoryUnitOfWork)`, abstract `RouteStoreContract` (test-jar).

- [ ] **Step 1: Master data**

```java
package io.nexus.routemind.master;

import io.nexus.contracts.DomainException;
import java.util.List;

/** Fleet and freight of RouteMind. Prices in CHF. */
public final class RouteMindMasterData {
  public static final long MINUTE = 60_000;
  public static final long HOUR = 60 * MINUTE;
  public static final long DAY = 24 * HOUR;
  /** The delivery shift ends at 22:00; the simulation day starts at 06:00 (time 0). */
  public static final long SHIFT_END = 16 * HOUR;

  public record VehicleSeed(String id, String name, int maxKg, int maxPallets, double costPerKm, double costPerHour) {}

  public record Freight(String productId, int kgPerUnit, int unitsPerPallet) {}

  private static final List<VehicleSeed> VEHICLES = List.of(
      new VehicleSeed("LKW-1", "LKW 7,5 t", 3000, 12, 1.40, 75),
      new VehicleSeed("LKW-2", "LKW 7,5 t", 3000, 12, 1.40, 75),
      new VehicleSeed("LW-1", "Lieferwagen", 1000, 4, 0.70, 60),
      new VehicleSeed("LW-2", "Lieferwagen", 1000, 4, 0.70, 60));

  private static final List<Freight> FREIGHT = List.of(
      new Freight("GH200", 18, 8), new Freight("AW50", 9, 20), new Freight("FP12", 4, 40), new Freight("MT7", 12, 12));

  private RouteMindMasterData() {}

  /** Ordered by id. */
  public static List<VehicleSeed> vehicles() {
    return VEHICLES;
  }

  public static VehicleSeed vehicle(String id) {
    for (VehicleSeed v : VEHICLES) if (v.id().equals(id)) return v;
    throw DomainException.notFound("Lieferfahrzeug", id);
  }

  public static Freight freight(String productId) {
    for (Freight f : FREIGHT) if (f.productId().equals(productId)) return f;
    throw DomainException.notFound("Produkt", productId);
  }

  public static int pallets(Freight f, int quantity) {
    return (quantity + f.unitsPerPallet() - 1) / f.unitsPerPallet();
  }
}
```

(`VEHICLES` must stay in id order: `LKW-1, LKW-2, LW-1, LW-2` is lexical order.)

- [ ] **Step 2: Records**

`store/Delivery.java`:

```java
package io.nexus.routemind.store;

import io.nexus.contracts.Domain.Priority;
import io.nexus.contracts.state.DeliveryStatus;

/**
 * A delivery order: the finished goods of one production order (same id as correlation) to one customer.
 *
 * @param quantity       ordered units
 * @param storedQuantity good units put away for it (0 until the putaway); what is actually shipped
 * @param reservationId  request id of the finished-goods reservation at FlowPilot, null before
 * @param transportId    FlowPilot transport to the loading zone, null before it is requested
 * @param failures       failed delivery attempts (vehicle breakdown under way)
 */
public record Delivery(String id, String orderId, String customerId, String productId, int quantity,
                       int storedQuantity, int weightKg, int pallets, Priority priority, long earliestMs,
                       long latestMs, DeliveryStatus status, String tourId, Long plannedArrivalMs,
                       Long deliveredAtMs, String reservationId, String transportId, int failures, long createdAtMs,
                       int version) {
  public Delivery withStatus(DeliveryStatus next) {
    return new Delivery(id, orderId, customerId, productId, quantity, storedQuantity, weightKg, pallets, priority,
        earliestMs, latestMs, next, tourId, plannedArrivalMs, deliveredAtMs, reservationId, transportId, failures,
        createdAtMs, version);
  }

  public Delivery withTour(String tour, Long plannedArrival) {
    return new Delivery(id, orderId, customerId, productId, quantity, storedQuantity, weightKg, pallets, priority,
        earliestMs, latestMs, status, tour, plannedArrival, deliveredAtMs, reservationId, transportId, failures,
        createdAtMs, version);
  }

  public Delivery withGoods(int stored, int kg, int palletCount) {
    return new Delivery(id, orderId, customerId, productId, quantity, stored, kg, palletCount, priority, earliestMs,
        latestMs, status, tourId, plannedArrivalMs, deliveredAtMs, reservationId, transportId, failures, createdAtMs,
        version);
  }

  public Delivery withShipment(String reservation, String transport) {
    return new Delivery(id, orderId, customerId, productId, quantity, storedQuantity, weightKg, pallets, priority,
        earliestMs, latestMs, status, tourId, plannedArrivalMs, deliveredAtMs, reservation, transport, failures,
        createdAtMs, version);
  }

  public Delivery withFailure() {
    return new Delivery(id, orderId, customerId, productId, quantity, storedQuantity, weightKg, pallets, priority,
        earliestMs, latestMs, status, tourId, plannedArrivalMs, deliveredAtMs, reservationId, transportId,
        failures + 1, createdAtMs, version);
  }

  public Delivery withDelivered(long atMs) {
    return new Delivery(id, orderId, customerId, productId, quantity, storedQuantity, weightKg, pallets, priority,
        earliestMs, latestMs, status, tourId, plannedArrivalMs, atMs, reservationId, transportId, failures,
        createdAtMs, version);
  }
}
```

`store/Tour.java`:

```java
package io.nexus.routemind.store;

import io.nexus.contracts.state.TourStatus;
import java.util.ArrayList;
import java.util.List;

/** One vehicle's round trip from the depot in one wave. Stops are ordered by seq (1..n). */
public record Tour(String id, String planId, String vehicleId, long departAtMs, TourStatus status, List<Stop> stops,
                   double km, double cost, Long startedAtMs, Long endedAtMs, int version) {
  /** arrivedAtMs is set once the vehicle reached the stop; such a stop never changes again. */
  public record Stop(int seq, String deliveryId, String customerId, long plannedArrivalMs, Long arrivedAtMs) {}

  public Tour {
    stops = List.copyOf(stops);
  }

  public Tour withStatus(TourStatus next) {
    return new Tour(id, planId, vehicleId, departAtMs, next, stops, km, cost, startedAtMs, endedAtMs, version);
  }

  public Tour withStarted(long atMs) {
    return new Tour(id, planId, vehicleId, departAtMs, status, stops, km, cost, atMs, endedAtMs, version);
  }

  public Tour withEnded(long atMs) {
    return new Tour(id, planId, vehicleId, departAtMs, status, stops, km, cost, startedAtMs, atMs, version);
  }

  public Tour withStops(List<Stop> next, double nextKm, double nextCost) {
    return new Tour(id, planId, vehicleId, departAtMs, status, next, nextKm, nextCost, startedAtMs, endedAtMs, version);
  }

  public Tour withArrival(int seq, long atMs) {
    List<Stop> next = new ArrayList<>();
    for (Stop s : stops) {
      next.add(s.seq() == seq ? new Stop(s.seq(), s.deliveryId(), s.customerId(), s.plannedArrivalMs(), atMs) : s);
    }
    return withStops(next, km, cost);
  }
}
```

`store/VehicleRecord.java`:

```java
package io.nexus.routemind.store;

import io.nexus.contracts.state.VehicleStatus;

/**
 * Where a delivery vehicle is. On a tour, leg i runs from stop i (0 = depot) to stop i+1; the last leg returns to the
 * depot. breakdownAtMs: a breakdown drawn for the current leg (EN_ROUTE, RETURNING) or the moment it happened
 * (BROKEN_DOWN). readyAtMs: when a broken-down vehicle is back in service at the depot.
 */
public record VehicleRecord(String id, VehicleStatus status, String tourId, int leg, long legStartMs, long legEndMs,
                            Long breakdownAtMs, long readyAtMs, int version) {
  public static VehicleRecord atDepot(String id) {
    return new VehicleRecord(id, VehicleStatus.AVAILABLE, null, 0, 0, 0, null, 0, 0);
  }

  public VehicleRecord withStatus(VehicleStatus next) {
    return new VehicleRecord(id, next, tourId, leg, legStartMs, legEndMs, breakdownAtMs, readyAtMs, version);
  }

  public VehicleRecord withTour(String tour) {
    return new VehicleRecord(id, status, tour, 0, 0, 0, null, readyAtMs, version);
  }

  public VehicleRecord withLeg(int index, long startMs, long endMs, Long breakdownAt) {
    return new VehicleRecord(id, status, tourId, index, startMs, endMs, breakdownAt, readyAtMs, version);
  }

  public VehicleRecord withBreakdown(long atMs, long readyAt) {
    return new VehicleRecord(id, VehicleStatus.BROKEN_DOWN, tourId, leg, legStartMs, legEndMs, atMs, readyAt, version);
  }

  /** Back at the depot, without tour, in the given state. */
  public VehicleRecord back(VehicleStatus next) {
    return new VehicleRecord(id, next, null, 0, 0, 0, null, 0, version);
  }
}
```

`store/RouteStore.java`:

```java
package io.nexus.routemind.store;

import java.util.List;
import java.util.Optional;

/** RouteMind's records. Writes take part in the caller's unit of work. */
public interface RouteStore {
  /** Fails with DomainException CONFLICT if the id or the order already has a delivery. */
  void insertDelivery(Delivery d);

  /** Fails with CONFLICT if the stored version differs; stores and returns version + 1. */
  Delivery updateDelivery(Delivery d);

  Optional<Delivery> delivery(String id);

  Optional<Delivery> deliveryOfOrder(String orderId);

  /** Ordered by id. */
  List<Delivery> deliveries();

  /** Fails with CONFLICT if the id exists. */
  void insertTour(Tour t);

  /** Fails with CONFLICT if the stored version differs; stores stops and returns version + 1. */
  Tour updateTour(Tour t);

  Optional<Tour> tour(String id);

  /** Ordered by id. */
  List<Tour> tours();

  /** Fails with CONFLICT if the stored version differs; returns version + 1. */
  VehicleRecord updateVehicle(VehicleRecord v);

  /** Ordered by id; a new store has every vehicle of the master data AVAILABLE at the depot. */
  List<VehicleRecord> vehicles();
}
```

- [ ] **Step 3: Write the contract test**

`RouteMind/src/test/java/io/nexus/routemind/store/RouteStoreContract.java`:

```java
package io.nexus.routemind.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.nexus.contracts.Domain.Priority;
import io.nexus.contracts.DomainException;
import io.nexus.contracts.event.UnitOfWork;
import io.nexus.contracts.state.DeliveryStatus;
import io.nexus.contracts.state.TourStatus;
import io.nexus.contracts.state.VehicleStatus;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Behaviour every RouteMind store must show. Runs against memory here and PostgreSQL in platform/app. */
public abstract class RouteStoreContract {
  protected abstract RouteStore store();

  protected abstract UnitOfWork work();

  public static Delivery delivery(String id, String orderId) {
    return new Delivery(id, orderId, "K01", "GH200", 4, 0, 72, 1, Priority.NORMAL, 3_600_000, 18_000_000,
        DeliveryStatus.CREATED, null, null, null, null, null, 0, 1_000, 0);
  }

  private static void assertConflict(Throwable t) {
    assertThat(t).isInstanceOfSatisfying(DomainException.class,
        e -> assertThat(e.kind()).isEqualTo(DomainException.Kind.CONFLICT));
  }

  @Test
  void deliveryIsStoredAndReadBackByIdAndOrder() {
    work().inNewTransaction(() -> store().insertDelivery(delivery("DL-0001", "PO-0001")));
    assertThat(store().delivery("DL-0001")).contains(delivery("DL-0001", "PO-0001"));
    assertThat(store().deliveryOfOrder("PO-0001")).contains(delivery("DL-0001", "PO-0001"));
    assertThat(store().deliveryOfOrder("PO-0002")).isEmpty();
  }

  @Test
  void secondDeliveryForTheSameOrderIsAConflict() {
    work().inNewTransaction(() -> store().insertDelivery(delivery("DL-0001", "PO-0001")));
    assertThatThrownBy(() -> work().inNewTransaction(() -> store().insertDelivery(delivery("DL-0002", "PO-0001"))))
        .satisfies(RouteStoreContract::assertConflict);
  }

  @Test
  void updateChecksTheVersionAndKeepsEveryField() {
    work().inNewTransaction(() -> store().insertDelivery(delivery("DL-0001", "PO-0001")));
    Delivery changed = delivery("DL-0001", "PO-0001").withStatus(DeliveryStatus.AWAITING_GOODS)
        .withGoods(3, 54, 1).withShipment("DL-0001/R", "TO-0007").withTour("TR-0001", 7_200_000L).withFailure()
        .withDelivered(9_000_000L);
    Delivery[] saved = new Delivery[1];
    work().inNewTransaction(() -> saved[0] = store().updateDelivery(changed));
    assertThat(saved[0].version()).isEqualTo(1);
    assertThat(store().delivery("DL-0001")).contains(saved[0]);
    assertThatThrownBy(() -> work().inNewTransaction(() -> store().updateDelivery(changed)))
        .satisfies(RouteStoreContract::assertConflict);
  }

  @Test
  void deliveriesAreOrderedById() {
    work().inNewTransaction(() -> {
      store().insertDelivery(delivery("DL-0002", "PO-0002"));
      store().insertDelivery(delivery("DL-0001", "PO-0001"));
    });
    assertThat(store().deliveries()).extracting(Delivery::id).containsExactly("DL-0001", "DL-0002");
  }

  @Test
  void tourWithStopsRoundTripsAndStopsAreReplacedOnUpdate() {
    var stops = List.of(new Tour.Stop(1, "DL-0001", "K01", 2_000_000, null),
        new Tour.Stop(2, "DL-0002", "K05", 3_000_000, null));
    var tour = new Tour("TR-0001", "RP-0001", "LKW-1", 1_800_000, TourStatus.PLANNED, stops, 42.5, 118.25, null, null, 0);
    work().inNewTransaction(() -> store().insertTour(tour));
    assertThat(store().tour("TR-0001")).contains(tour);
    Tour moved = tour.withStatus(TourStatus.LOADING).withStarted(1_800_000).withArrival(1, 2_100_000)
        .withStops(List.of(new Tour.Stop(1, "DL-0001", "K01", 2_000_000, 2_100_000L)), 20.0, 60.0);
    Tour[] saved = new Tour[1];
    work().inNewTransaction(() -> saved[0] = store().updateTour(moved));
    assertThat(saved[0].version()).isEqualTo(1);
    assertThat(store().tour("TR-0001")).contains(saved[0]);
    assertThat(store().tours()).extracting(Tour::id).containsExactly("TR-0001");
  }

  @Test
  void vehiclesStartAvailableAtTheDepotInIdOrder() {
    assertThat(store().vehicles()).extracting(VehicleRecord::id).containsExactly("LKW-1", "LKW-2", "LW-1", "LW-2");
    assertThat(store().vehicles()).allSatisfy(v -> {
      assertThat(v.status()).isEqualTo(VehicleStatus.AVAILABLE);
      assertThat(v.tourId()).isNull();
      assertThat(v.version()).isZero();
    });
    VehicleRecord moved = store().vehicles().get(0).withStatus(VehicleStatus.LOADING).withTour("TR-0001")
        .withLeg(1, 10, 20, 15L);
    VehicleRecord[] saved = new VehicleRecord[1];
    work().inNewTransaction(() -> saved[0] = store().updateVehicle(moved));
    assertThat(store().vehicles().get(0)).isEqualTo(saved[0]);
    assertThatThrownBy(() -> work().inNewTransaction(() -> store().updateVehicle(moved)))
        .satisfies(RouteStoreContract::assertConflict);
  }

  @Test
  void rolledBackUnitLeavesNothing() {
    assertThatThrownBy(() -> work().inNewTransaction(() -> {
      store().insertDelivery(delivery("DL-0001", "PO-0001"));
      store().updateVehicle(store().vehicles().get(0).withStatus(VehicleStatus.MAINTENANCE));
      throw new IllegalStateException("abort");
    })).isInstanceOf(IllegalStateException.class);
    assertThat(store().deliveries()).isEmpty();
    assertThat(store().vehicles().get(0).status()).isEqualTo(VehicleStatus.AVAILABLE);
  }
}
```

`MemoryRouteStoreTest.java`:

```java
package io.nexus.routemind.store;

import io.nexus.contracts.event.UnitOfWork;
import io.nexus.contracts.memory.MemoryUnitOfWork;
import io.nexus.routemind.store.memory.MemoryRouteStore;

class MemoryRouteStoreTest extends RouteStoreContract {
  private final MemoryUnitOfWork work = new MemoryUnitOfWork();
  private final MemoryRouteStore store = new MemoryRouteStore(work);

  @Override protected RouteStore store() { return store; }
  @Override protected UnitOfWork work() { return work; }
}
```

Run: `./mvnw -q -pl RouteMind -am test -Dtest=MemoryRouteStoreTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: COMPILATION ERROR, `MemoryRouteStore` not found.

- [ ] **Step 4: Memory store**

```java
package io.nexus.routemind.store.memory;

import io.nexus.contracts.DomainException;
import io.nexus.contracts.memory.MemoryUnitOfWork;
import io.nexus.routemind.master.RouteMindMasterData;
import io.nexus.routemind.store.Delivery;
import io.nexus.routemind.store.RouteStore;
import io.nexus.routemind.store.Tour;
import io.nexus.routemind.store.VehicleRecord;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/** RouteStore in memory. Every write can be undone by the unit of work. */
public final class MemoryRouteStore implements RouteStore {
  private final MemoryUnitOfWork work;
  private final TreeMap<String, Delivery> deliveries = new TreeMap<>();
  private final Map<String, String> byOrder = new HashMap<>(); // lookups only
  private final TreeMap<String, Tour> tours = new TreeMap<>();
  private final TreeMap<String, VehicleRecord> vehicles = new TreeMap<>();

  public MemoryRouteStore(MemoryUnitOfWork work) {
    this.work = work;
    for (var v : RouteMindMasterData.vehicles()) vehicles.put(v.id(), VehicleRecord.atDepot(v.id()));
  }

  @Override
  public void insertDelivery(Delivery d) {
    if (deliveries.containsKey(d.id()) || byOrder.containsKey(d.orderId())) {
      throw DomainException.conflict("Lieferauftrag für " + d.orderId() + " existiert bereits");
    }
    deliveries.put(d.id(), d);
    byOrder.put(d.orderId(), d.id());
    work.onRollback(() -> {
      deliveries.remove(d.id());
      byOrder.remove(d.orderId());
    });
  }

  @Override
  public Delivery updateDelivery(Delivery d) {
    Delivery before = deliveries.get(d.id());
    if (before == null) throw DomainException.notFound("Lieferauftrag", d.id());
    if (before.version() != d.version()) {
      throw DomainException.conflict("Lieferauftrag " + d.id() + " wurde zwischenzeitlich geändert");
    }
    Delivery saved = new Delivery(d.id(), d.orderId(), d.customerId(), d.productId(), d.quantity(),
        d.storedQuantity(), d.weightKg(), d.pallets(), d.priority(), d.earliestMs(), d.latestMs(), d.status(),
        d.tourId(), d.plannedArrivalMs(), d.deliveredAtMs(), d.reservationId(), d.transportId(), d.failures(),
        d.createdAtMs(), d.version() + 1);
    deliveries.put(d.id(), saved);
    work.onRollback(() -> deliveries.put(d.id(), before));
    return saved;
  }

  @Override
  public Optional<Delivery> delivery(String id) {
    return Optional.ofNullable(deliveries.get(id));
  }

  @Override
  public Optional<Delivery> deliveryOfOrder(String orderId) {
    String id = byOrder.get(orderId);
    return id == null ? Optional.empty() : delivery(id);
  }

  @Override
  public List<Delivery> deliveries() {
    return new ArrayList<>(deliveries.values());
  }

  @Override
  public void insertTour(Tour t) {
    if (tours.containsKey(t.id())) throw DomainException.conflict("Tour " + t.id() + " existiert bereits");
    tours.put(t.id(), t);
    work.onRollback(() -> tours.remove(t.id()));
  }

  @Override
  public Tour updateTour(Tour t) {
    Tour before = tours.get(t.id());
    if (before == null) throw DomainException.notFound("Tour", t.id());
    if (before.version() != t.version()) throw DomainException.conflict("Tour " + t.id() + " wurde zwischenzeitlich geändert");
    Tour saved = new Tour(t.id(), t.planId(), t.vehicleId(), t.departAtMs(), t.status(), t.stops(), t.km(), t.cost(),
        t.startedAtMs(), t.endedAtMs(), t.version() + 1);
    tours.put(t.id(), saved);
    work.onRollback(() -> tours.put(t.id(), before));
    return saved;
  }

  @Override
  public Optional<Tour> tour(String id) {
    return Optional.ofNullable(tours.get(id));
  }

  @Override
  public List<Tour> tours() {
    return new ArrayList<>(tours.values());
  }

  @Override
  public VehicleRecord updateVehicle(VehicleRecord v) {
    VehicleRecord before = vehicles.get(v.id());
    if (before == null) throw DomainException.notFound("Lieferfahrzeug", v.id());
    if (before.version() != v.version()) {
      throw DomainException.conflict("Lieferfahrzeug " + v.id() + " wurde zwischenzeitlich geändert");
    }
    VehicleRecord saved = new VehicleRecord(v.id(), v.status(), v.tourId(), v.leg(), v.legStartMs(), v.legEndMs(),
        v.breakdownAtMs(), v.readyAtMs(), v.version() + 1);
    vehicles.put(v.id(), saved);
    work.onRollback(() -> vehicles.put(v.id(), before));
    return saved;
  }

  @Override
  public List<VehicleRecord> vehicles() {
    return new ArrayList<>(vehicles.values());
  }
}
```

- [ ] **Step 5: Run the tests**

Run: `./mvnw -q -pl RouteMind -am test`
Expected: PASS (RoadsTest 3, MemoryRouteStoreTest 7).

- [ ] **Step 6: Commit**

```bash
git add RouteMind
git commit -m "feat(routemind): Stammdaten, Datensätze und Speicher mit Vertragstest"
```

---

### Task 6: Tourenplanung – Ausgangsplan, Heuristik, Bewertung

**Files:**
- Create in `RouteMind/src/main/java/io/nexus/routemind/optimize/`: `Matrix.java`, `Job.java`, `Truck.java`, `Problem.java`, `Route.java`, `Unassigned.java`, `Solution.java`, `RouteOptimizer.java`, `Reasons.java`, `Evaluation.java`, `Feasibility.java`, `NearestNeighbour.java`, `SavingsHeuristic.java`
- Test: `RouteMind/src/test/java/io/nexus/routemind/optimize/OptimizerTest.java`

**Interfaces:**
- Consumes: `Roads` (Task 4), `RouteMindMasterData` (Task 5).
- Produces: `RouteOptimizer.solve(Problem): Solution`; `Problem(long departMs, int depot, List<Truck> trucks, List<Job> jobs, Matrix matrix)` with `job(id)`, `truck(id)`; `Job(id, place, weightKg, pallets, earliestMs, latestMs, serviceMs)`; `Truck(id, maxKg, maxPallets, costPerKm, costPerHour, endMs)`; `Solution(solver, routes, unassigned)`; `Route(truckId, jobIds)`; `Unassigned(jobId, reason)`; `Reasons.WINDOW/CAPACITY/SHIFT`; `Evaluation.route(Problem, Truck, List<Job>): RouteFigures`, `Evaluation.of(Problem, Solution): Figures`, `Evaluation.jobs(Problem, List<String>)`; `Evaluation.Figures(km, driveMinutes, cost, lateMinutes, tours)`; `NearestNeighbour.NAME`, `SavingsHeuristic.NAME`.

- [ ] **Step 1: Write the failing test**

```java
package io.nexus.routemind.optimize;

import static org.assertj.core.api.Assertions.assertThat;

import io.nexus.contracts.sim.SeededRandom;
import io.nexus.routemind.master.RouteMindMasterData;
import io.nexus.routemind.master.Roads;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class OptimizerTest {
  static final Matrix ROADS = new Matrix() {
    @Override public int seconds(int from, int to) { return Roads.seconds(from, to); }
    @Override public int meters(int from, int to) { return Roads.meters(from, to); }
  };
  static final long DEPART = 2 * RouteMindMasterData.HOUR;
  static final long MIN = RouteMindMasterData.MINUTE;

  static List<Truck> fleet(long endMs) {
    List<Truck> out = new ArrayList<>();
    for (var v : RouteMindMasterData.vehicles()) {
      out.add(new Truck(v.id(), v.maxKg(), v.maxPallets(), v.costPerKm(), v.costPerHour(), endMs));
    }
    return out;
  }

  /** n jobs at random customers, 50..650 kg, 1..3 pallets, windows of 2..6 h starting up to 1 h after departure. */
  static Problem instance(long seed, int n) {
    var r = new SeededRandom(seed);
    List<Job> jobs = new ArrayList<>();
    for (int i = 0; i < n; i++) {
      int place = 1 + r.nextInt(30);
      long earliest = DEPART + r.nextInt(61) * MIN;
      jobs.add(new Job(String.format("J%02d", i), place, 50 + r.nextInt(601), 1 + r.nextInt(3), earliest,
          earliest + (2 + r.nextInt(5)) * RouteMindMasterData.HOUR, 10 * MIN));
    }
    return new Problem(DEPART, 0, fleet(DEPART + 10 * RouteMindMasterData.HOUR), jobs, ROADS);
  }

  static void assertValid(Problem p, Solution s) {
    Set<String> seen = new HashSet<>();
    for (Route route : s.routes()) {
      Truck t = p.truck(route.truckId());
      var f = Evaluation.route(p, t, Evaluation.jobs(p, route.jobIds()));
      assertThat(f.weightKg()).as(route.truckId()).isLessThanOrEqualTo(t.maxKg());
      assertThat(f.pallets()).as(route.truckId()).isLessThanOrEqualTo(t.maxPallets());
      assertThat(f.returnMs()).as(route.truckId()).isLessThanOrEqualTo(t.endMs());
      for (String id : route.jobIds()) assertThat(seen.add(id)).as("planned once: " + id).isTrue();
    }
    for (Unassigned u : s.unassigned()) {
      assertThat(seen.add(u.jobId())).as("planned or open, not both: " + u.jobId()).isTrue();
      assertThat(u.reason()).isIn(Reasons.WINDOW, Reasons.CAPACITY, Reasons.SHIFT);
    }
    Set<String> all = new HashSet<>();
    for (Job j : p.jobs()) all.add(j.id());
    assertThat(seen).isEqualTo(all);
    Set<String> trucks = new HashSet<>();
    for (Route route : s.routes()) assertThat(trucks.add(route.truckId())).as("one route per truck").isTrue();
  }

  @Test
  void everyJobIsPlannedOrReportedWithAReason() {
    for (long seed = 1; seed <= 20; seed++) {
      Problem p = instance(seed, 5 + (int) (seed % 20));
      assertValid(p, new NearestNeighbour().solve(p));
      assertValid(p, new SavingsHeuristic().solve(p));
    }
  }

  @Test
  void reasons() {
    long end = DEPART + 10 * RouteMindMasterData.HOUR;
    var tooHeavy = new Job("HEAVY", 1, 3500, 2, DEPART, DEPART + 4 * RouteMindMasterData.HOUR, 10 * MIN);
    var tooLate = new Job("LATE", 24, 100, 1, DEPART, DEPART + 5 * MIN, 10 * MIN); // Winterthur is not 5 min away
    var afterShift = new Job("SHIFT", 24, 100, 1, end - 10 * MIN, end + RouteMindMasterData.HOUR, 10 * MIN);
    var fine = new Job("OK", 1, 100, 1, DEPART, DEPART + 4 * RouteMindMasterData.HOUR, 10 * MIN);
    var p = new Problem(DEPART, 0, fleet(end), List.of(tooHeavy, tooLate, afterShift, fine), ROADS);
    var s = new SavingsHeuristic().solve(p);
    assertThat(s.unassigned()).containsExactlyInAnyOrder(new Unassigned("HEAVY", Reasons.CAPACITY),
        new Unassigned("LATE", Reasons.WINDOW), new Unassigned("SHIFT", Reasons.SHIFT));
    assertThat(s.routes()).hasSize(1);
    var none = new Problem(DEPART, 0, List.of(), List.of(fine), ROADS);
    assertThat(new SavingsHeuristic().solve(none).unassigned()).containsExactly(new Unassigned("OK", Reasons.SHIFT));
  }

  @Test
  void heuristicIsNotWorseThanNearestNeighbour() {
    for (long seed = 1; seed <= 10; seed++) {
      Problem p = instance(seed, 12);
      Solution nn = new NearestNeighbour().solve(p);
      Solution h = new SavingsHeuristic().solve(p);
      assertThat(h.unassigned().size()).as("seed " + seed).isLessThanOrEqualTo(nn.unassigned().size());
      if (h.unassigned().size() == nn.unassigned().size()) {
        assertThat(Evaluation.of(p, h).cost()).as("seed " + seed).isLessThanOrEqualTo(Evaluation.of(p, nn).cost() + 1e-6);
      }
    }
  }

  @Test
  void sameProblemSameSolution() {
    Problem p = instance(7, 15);
    assertThat(new SavingsHeuristic().solve(p)).isEqualTo(new SavingsHeuristic().solve(p));
    assertThat(new NearestNeighbour().solve(p)).isEqualTo(new NearestNeighbour().solve(p));
  }
}
```

Run: `./mvnw -q -pl RouteMind -am test -Dtest=OptimizerTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: COMPILATION ERROR, `Matrix` not found.

- [ ] **Step 2: Problem types**

```java
package io.nexus.routemind.optimize;

/** Driving time and distance between place indices (Roads in RouteMind, anything in tests). */
public interface Matrix {
  int seconds(int from, int to);

  int meters(int from, int to);
}
```

```java
package io.nexus.routemind.optimize;

/** One delivery to plan: where, how heavy and big, and the customer's time window (simulation ms). */
public record Job(String id, int place, int weightKg, int pallets, long earliestMs, long latestMs, long serviceMs) {}
```

```java
package io.nexus.routemind.optimize;

/** One available vehicle; it must be back at the depot by endMs (end of its shift). */
public record Truck(String id, int maxKg, int maxPallets, double costPerKm, double costPerHour, long endMs) {}
```

```java
package io.nexus.routemind.optimize;

import io.nexus.contracts.DomainException;
import java.util.List;

/** All trucks leave the depot at departMs. Jobs are given in id order; solvers keep their results deterministic. */
public record Problem(long departMs, int depot, List<Truck> trucks, List<Job> jobs, Matrix matrix) {
  public Problem {
    trucks = List.copyOf(trucks);
    jobs = List.copyOf(jobs);
  }

  public Job job(String id) {
    for (Job j : jobs) if (j.id().equals(id)) return j;
    throw DomainException.notFound("Lieferung", id);
  }

  public Truck truck(String id) {
    for (Truck t : trucks) if (t.id().equals(id)) return t;
    throw DomainException.notFound("Lieferfahrzeug", id);
  }
}
```

```java
package io.nexus.routemind.optimize;

import java.util.List;

/** The jobs one truck serves, in driving order. */
public record Route(String truckId, List<String> jobIds) {
  public Route {
    jobIds = List.copyOf(jobIds);
  }
}
```

```java
package io.nexus.routemind.optimize;

public record Unassigned(String jobId, String reason) {}
```

```java
package io.nexus.routemind.optimize;

import java.util.List;

/** Result of a solver: routes (at most one per truck) and the jobs it could not plan, each with its reason. */
public record Solution(String solver, List<Route> routes, List<Unassigned> unassigned) {
  public Solution {
    routes = List.copyOf(routes);
    unassigned = List.copyOf(unassigned);
  }
}
```

```java
package io.nexus.routemind.optimize;

/** Port of the vehicle routing (Spec 15a): heuristic in the core, OR-Tools on the server. */
public interface RouteOptimizer {
  Solution solve(Problem p);
}
```

```java
package io.nexus.routemind.optimize;

/** Why a delivery could not be planned (Spec 15a). */
public final class Reasons {
  public static final String WINDOW = "Zeitfenster nicht erreichbar";
  public static final String CAPACITY = "Kapazität reicht nicht";
  public static final String SHIFT = "kein Fahrzeug in der Schicht";

  private Reasons() {}
}
```

- [ ] **Step 3: Evaluation and feasibility**

```java
package io.nexus.routemind.optimize;

import java.util.ArrayList;
import java.util.List;

/**
 * Times and costs of routes, the same for every solver, so plans are compared on one scale. A truck waits at a
 * customer until the window opens; arriving after it closes costs LATE_COST_PER_MINUTE (soft limit). Cost in CHF:
 * distance × price per km + time away from the depot × price per hour + lateness.
 */
public final class Evaluation {
  public static final double LATE_COST_PER_MINUTE = 2.0;

  public record StopTime(String jobId, long arrivalMs, long startMs, long lateMs) {}

  public record RouteFigures(String truckId, List<StopTime> stops, long returnMs, int meters, int driveSeconds,
                             double cost, double lateMinutes, int weightKg, int pallets) {
    public boolean fits(Truck t) {
      return weightKg <= t.maxKg() && pallets <= t.maxPallets() && returnMs <= t.endMs();
    }
  }

  public record Figures(double km, double driveMinutes, double cost, double lateMinutes, int tours) {}

  private Evaluation() {}

  public static RouteFigures route(Problem p, Truck t, List<Job> jobs) {
    long time = p.departMs();
    int at = p.depot();
    int meters = 0;
    int drive = 0;
    int kg = 0;
    int pallets = 0;
    long late = 0;
    List<StopTime> stops = new ArrayList<>();
    for (Job j : jobs) {
      int s = p.matrix().seconds(at, j.place());
      drive += s;
      meters += p.matrix().meters(at, j.place());
      long arrival = time + s * 1000L;
      long start = Math.max(arrival, j.earliestMs());
      long l = Math.max(0, start - j.latestMs());
      late += l;
      stops.add(new StopTime(j.id(), arrival, start, l));
      time = start + j.serviceMs();
      at = j.place();
      kg += j.weightKg();
      pallets += j.pallets();
    }
    if (!jobs.isEmpty()) {
      int s = p.matrix().seconds(at, p.depot());
      drive += s;
      meters += p.matrix().meters(at, p.depot());
      time += s * 1000L;
    }
    double lateMinutes = late / 60_000.0;
    double cost = jobs.isEmpty() ? 0
        : meters / 1000.0 * t.costPerKm() + (time - p.departMs()) / 3_600_000.0 * t.costPerHour()
            + lateMinutes * LATE_COST_PER_MINUTE;
    return new RouteFigures(t.id(), stops, time, meters, drive, cost, lateMinutes, kg, pallets);
  }

  public static Figures of(Problem p, Solution s) {
    double km = 0;
    double drive = 0;
    double cost = 0;
    double late = 0;
    int tours = 0;
    for (Route r : s.routes()) {
      if (r.jobIds().isEmpty()) continue;
      RouteFigures f = route(p, p.truck(r.truckId()), jobs(p, r.jobIds()));
      km += f.meters() / 1000.0;
      drive += f.driveSeconds() / 60.0;
      cost += f.cost();
      late += f.lateMinutes();
      tours++;
    }
    return new Figures(km, drive, cost, late, tours);
  }

  public static List<Job> jobs(Problem p, List<String> ids) {
    List<Job> out = new ArrayList<>();
    for (String id : ids) out.add(p.job(id));
    return out;
  }

  static List<String> ids(List<Job> jobs) {
    List<String> out = new ArrayList<>();
    for (Job j : jobs) out.add(j.id());
    return out;
  }
}
```

```java
package io.nexus.routemind.optimize;

import java.util.ArrayList;
import java.util.List;

/** Jobs no plan can serve, whatever the solver: reported with their reason, the rest goes to the solver. */
public final class Feasibility {
  public record Split(List<Job> plannable, List<Unassigned> unassigned) {}

  private Feasibility() {}

  public static Split split(Problem p) {
    List<Job> ok = new ArrayList<>();
    List<Unassigned> no = new ArrayList<>();
    long latestEnd = Long.MIN_VALUE;
    for (Truck t : p.trucks()) latestEnd = Math.max(latestEnd, t.endMs());
    for (Job j : p.jobs()) {
      String reason = null;
      long arrival = p.departMs() + p.matrix().seconds(p.depot(), j.place()) * 1000L;
      if (p.trucks().isEmpty()) {
        reason = Reasons.SHIFT;
      } else if (!fitsSomeTruck(p, j)) {
        reason = Reasons.CAPACITY;
      } else if (arrival > j.latestMs()) {
        reason = Reasons.WINDOW;
      } else {
        long back = Math.max(arrival, j.earliestMs()) + j.serviceMs()
            + p.matrix().seconds(j.place(), p.depot()) * 1000L;
        if (back > latestEnd) reason = Reasons.SHIFT;
      }
      if (reason == null) ok.add(j);
      else no.add(new Unassigned(j.id(), reason));
    }
    return new Split(ok, no);
  }

  private static boolean fitsSomeTruck(Problem p, Job j) {
    for (Truck t : p.trucks()) if (j.weightKg() <= t.maxKg() && j.pallets() <= t.maxPallets()) return true;
    return false;
  }
}
```

- [ ] **Step 4: Baseline – nearest neighbour**

```java
package io.nexus.routemind.optimize;

import java.util.ArrayList;
import java.util.List;

/** The simple start plan (Spec 15a): each truck in turn drives to the nearest job that still fits. */
public final class NearestNeighbour implements RouteOptimizer {
  public static final String NAME = "Nächster Nachbar";

  @Override
  public Solution solve(Problem p) {
    var split = Feasibility.split(p);
    List<Job> open = new ArrayList<>(split.plannable());
    List<Route> routes = new ArrayList<>();
    for (Truck t : p.trucks()) {
      List<Job> route = new ArrayList<>();
      int at = p.depot();
      while (true) {
        Job best = null;
        int bestSeconds = Integer.MAX_VALUE;
        for (Job j : open) {
          int s = p.matrix().seconds(at, j.place());
          if (s >= bestSeconds) continue; // ties keep the job earlier in id order
          List<Job> tried = new ArrayList<>(route);
          tried.add(j);
          if (!Evaluation.route(p, t, tried).fits(t)) continue;
          best = j;
          bestSeconds = s;
        }
        if (best == null) break;
        route.add(best);
        open.remove(best);
        at = best.place();
      }
      if (!route.isEmpty()) routes.add(new Route(t.id(), Evaluation.ids(route)));
    }
    List<Unassigned> unassigned = new ArrayList<>(split.unassigned());
    for (Job j : open) unassigned.add(new Unassigned(j.id(), Reasons.CAPACITY));
    return new Solution(NAME, routes, unassigned);
  }
}
```

- [ ] **Step 5: Heuristic – savings, 2-opt, relocate**

```java
package io.nexus.routemind.optimize;

import java.util.ArrayList;
import java.util.List;

/**
 * Vehicle routing in plain Java (Spec 15a): Clarke-Wright savings on the largest truck, routes to the smallest truck
 * that carries them, left-overs by cheapest insertion, then first-improvement 2-opt within a route and relocation of
 * single stops between routes until nothing improves. Deterministic: every loop runs in index order.
 *
 * <p>ponytail: O(routes² · stops²) per improvement round, fine for a wave of up to ~30 deliveries; a neighbour list
 * would be the upgrade for larger instances.
 */
public final class SavingsHeuristic implements RouteOptimizer {
  public static final String NAME = "Heuristik (Savings, 2-opt, Verschieben)";
  private static final int MAX_ROUNDS = 200;
  private static final double EPS = 1e-9;

  private record Saving(int a, int b, long value) {}

  @Override
  public Solution solve(Problem p) {
    var split = Feasibility.split(p);
    List<Unassigned> unassigned = new ArrayList<>(split.unassigned());
    if (split.plannable().isEmpty()) return new Solution(NAME, List.of(), unassigned);
    Truck biggest = p.trucks().get(0);
    for (Truck t : p.trucks()) if (t.maxKg() > biggest.maxKg()) biggest = t;
    List<List<Job>> routes = savings(p, split.plannable(), biggest);
    List<List<Job>> jobs = new ArrayList<>();
    List<Truck> trucks = new ArrayList<>();
    assign(p, routes, jobs, trucks, unassigned);
    improve(p, jobs, trucks);
    Solution fromSavings = solution(jobs, trucks, unassigned);
    // the start plan, improved the same way, is the floor: the result is never worse than nearest neighbour
    Solution nn = new NearestNeighbour().solve(p);
    List<List<Job>> nnJobs = new ArrayList<>();
    List<Truck> nnTrucks = new ArrayList<>();
    for (Route r : nn.routes()) {
      nnJobs.add(new ArrayList<>(Evaluation.jobs(p, r.jobIds())));
      nnTrucks.add(p.truck(r.truckId()));
    }
    improve(p, nnJobs, nnTrucks);
    Solution fromNn = solution(nnJobs, nnTrucks, nn.unassigned());
    if (fromNn.unassigned().size() < fromSavings.unassigned().size()) return fromNn;
    if (fromNn.unassigned().size() == fromSavings.unassigned().size()
        && Evaluation.of(p, fromNn).cost() < Evaluation.of(p, fromSavings).cost() - EPS) return fromNn;
    return fromSavings;
  }

  private static Solution solution(List<List<Job>> jobs, List<Truck> trucks, List<Unassigned> unassigned) {
    List<Route> out = new ArrayList<>();
    for (int i = 0; i < jobs.size(); i++) {
      if (!jobs.get(i).isEmpty()) out.add(new Route(trucks.get(i).id(), Evaluation.ids(jobs.get(i))));
    }
    return new Solution(NAME, out, unassigned);
  }

  private static List<List<Job>> savings(Problem p, List<Job> jobs, Truck truck) {
    List<List<Job>> routes = new ArrayList<>();
    for (Job j : jobs) {
      List<Job> r = new ArrayList<>();
      r.add(j);
      routes.add(r);
    }
    Matrix m = p.matrix();
    int d = p.depot();
    List<Saving> list = new ArrayList<>();
    for (int a = 0; a < jobs.size(); a++) {
      for (int b = 0; b < jobs.size(); b++) {
        if (a == b) continue;
        int x = jobs.get(a).place();
        int y = jobs.get(b).place();
        long value = (long) m.seconds(x, d) + m.seconds(d, y) - m.seconds(x, y);
        if (value > 0) list.add(new Saving(a, b, value));
      }
    }
    list.sort((s, t) -> s.value() != t.value() ? Long.compare(t.value(), s.value())
        : s.a() != t.a() ? Integer.compare(s.a(), t.a()) : Integer.compare(s.b(), t.b()));
    for (Saving s : list) {
      List<Job> ending = null;
      List<Job> starting = null;
      for (List<Job> r : routes) {
        if (r.get(r.size() - 1) == jobs.get(s.a())) ending = r;
        if (r.get(0) == jobs.get(s.b())) starting = r;
      }
      if (ending == null || starting == null || ending == starting) continue;
      List<Job> merged = new ArrayList<>(ending);
      merged.addAll(starting);
      var f = Evaluation.route(p, truck, merged);
      if (!f.fits(truck)) continue;
      double apart = Evaluation.route(p, truck, ending).cost() + Evaluation.route(p, truck, starting).cost();
      if (f.cost() > apart + EPS) continue;
      routes.remove(starting);
      ending.clear();
      ending.addAll(merged);
    }
    return routes;
  }

  /** Heaviest route first, each on the smallest free truck that carries it within its shift. */
  private static void assign(Problem p, List<List<Job>> routes, List<List<Job>> jobs, List<Truck> trucks,
                             List<Unassigned> unassigned) {
    List<Truck> free = new ArrayList<>(p.trucks());
    free.sort((a, b) -> a.maxKg() != b.maxKg() ? Integer.compare(a.maxKg(), b.maxKg()) : a.id().compareTo(b.id()));
    routes.sort((a, b) -> {
      int wa = weight(a);
      int wb = weight(b);
      return wa != wb ? Integer.compare(wb, wa) : a.get(0).id().compareTo(b.get(0).id());
    });
    List<Job> left = new ArrayList<>();
    for (List<Job> r : routes) {
      Truck chosen = null;
      for (Truck t : free) {
        if (Evaluation.route(p, t, r).fits(t)) {
          chosen = t;
          break;
        }
      }
      if (chosen == null) {
        left.addAll(r);
        continue;
      }
      free.remove(chosen);
      jobs.add(new ArrayList<>(r));
      trucks.add(chosen);
    }
    left.sort((a, b) -> a.id().compareTo(b.id()));
    for (Job j : left) {
      if (!insertCheapest(p, jobs, trucks, j)) unassigned.add(new Unassigned(j.id(), Reasons.CAPACITY));
    }
  }

  private static boolean insertCheapest(Problem p, List<List<Job>> jobs, List<Truck> trucks, Job j) {
    int bestRoute = -1;
    int bestPos = -1;
    double bestDelta = Double.MAX_VALUE;
    for (int k = 0; k < jobs.size(); k++) {
      double before = cost(p, trucks.get(k), jobs.get(k));
      for (int pos = 0; pos <= jobs.get(k).size(); pos++) {
        List<Job> tried = new ArrayList<>(jobs.get(k));
        tried.add(pos, j);
        var f = Evaluation.route(p, trucks.get(k), tried);
        if (!f.fits(trucks.get(k))) continue;
        double delta = f.cost() - before;
        if (delta < bestDelta - EPS) {
          bestDelta = delta;
          bestRoute = k;
          bestPos = pos;
        }
      }
    }
    if (bestRoute < 0) return false;
    jobs.get(bestRoute).add(bestPos, j);
    return true;
  }

  private static void improve(Problem p, List<List<Job>> jobs, List<Truck> trucks) {
    for (int round = 0; round < MAX_ROUNDS; round++) {
      if (!twoOpt(p, jobs, trucks) && !relocate(p, jobs, trucks)) return;
    }
  }

  /** First reversal of a segment within one route that lowers its cost. */
  private static boolean twoOpt(Problem p, List<List<Job>> jobs, List<Truck> trucks) {
    for (int k = 0; k < jobs.size(); k++) {
      List<Job> route = jobs.get(k);
      double base = cost(p, trucks.get(k), route);
      for (int i = 0; i < route.size() - 1; i++) {
        for (int j = i + 1; j < route.size(); j++) {
          List<Job> c = new ArrayList<>(route);
          for (int a = i, b = j; a < b; a++, b--) {
            Job tmp = c.get(a);
            c.set(a, c.get(b));
            c.set(b, tmp);
          }
          var f = Evaluation.route(p, trucks.get(k), c);
          if (f.fits(trucks.get(k)) && f.cost() < base - EPS) {
            jobs.set(k, c);
            return true;
          }
        }
      }
    }
    return false;
  }

  /** First move of one stop to another position, in its own route or another one, that lowers the total cost. */
  private static boolean relocate(Problem p, List<List<Job>> jobs, List<Truck> trucks) {
    for (int a = 0; a < jobs.size(); a++) {
      for (int i = 0; i < jobs.get(a).size(); i++) {
        for (int b = 0; b < jobs.size(); b++) {
          List<Job> from = new ArrayList<>(jobs.get(a));
          Job moved = from.remove(i);
          List<Job> base = a == b ? from : jobs.get(b);
          double before = cost(p, trucks.get(a), jobs.get(a)) + (a == b ? 0 : cost(p, trucks.get(b), jobs.get(b)));
          for (int pos = 0; pos <= base.size(); pos++) {
            if (a == b && pos == i) continue;
            List<Job> to = new ArrayList<>(base);
            to.add(pos, moved);
            if (!Evaluation.route(p, trucks.get(b), to).fits(trucks.get(b))) continue;
            if (a != b && !Evaluation.route(p, trucks.get(a), from).fits(trucks.get(a))) continue;
            double after = a == b ? cost(p, trucks.get(a), to) : cost(p, trucks.get(a), from) + cost(p, trucks.get(b), to);
            if (after < before - EPS) {
              if (a == b) {
                jobs.set(a, to);
              } else {
                jobs.set(a, from);
                jobs.set(b, to);
              }
              return true;
            }
          }
        }
      }
    }
    return false;
  }

  private static double cost(Problem p, Truck t, List<Job> route) {
    return Evaluation.route(p, t, route).cost();
  }

  private static int weight(List<Job> route) {
    int kg = 0;
    for (Job j : route) kg += j.weightKg();
    return kg;
  }
}
```

- [ ] **Step 6: Run the tests**

Run: `./mvnw -q -pl RouteMind -am test`
Expected: PASS (OptimizerTest 4 tests plus the earlier ones). If `heuristicIsNotWorseThanNearestNeighbour` fails for a seed, the heuristic has a defect (savings merged into a worse route or relocation broke a route): debug it, do not loosen the assertion.

- [ ] **Step 7: Commit**

```bash
git add RouteMind
git commit -m "feat(routemind): Tourenplanung mit Ausgangsplan, Savings, 2-opt und Verschieben"
```

---
### Task 7: RouteMind – Lieferaufträge, Wellen, Verladung, Fahrten, Ausfälle

**Files:**
- Create: `RouteMind/src/main/java/io/nexus/routemind/RouteMind.java`
- Test: `RouteMind/src/test/java/io/nexus/routemind/Fixture.java`, `RouteMind/src/test/java/io/nexus/routemind/RouteMindTest.java`

**Interfaces:**
- Consumes: Tasks 1, 4, 5, 6; ports `MaterialService`, `TransportService`, `ProductCatalog`, `ProductionEta`.
- Produces (used by Tasks 8–11):
  - `new RouteMind(RouteStore, EventBus, UnitOfWork, SimTime, IdSource, MaterialService, TransportService, ProductionEta, ProductCatalog, RouteOptimizer, long seed)` — registers its consumer `routemind` on the bus.
  - `RouteMind.inMemory(EventBus, MemoryUnitOfWork, SimTime, IdSource, MaterialService, TransportService, ProductionEta, ProductCatalog, long seed)` (heuristic, memory store).
  - `step()`, `reset(long seed)`, `breakDown(String)`, `repair(String)`, `setMaintenance(String, boolean)`, `replan()`.
  - Queries: `deliveries(): List<Delivery>`, `deliveryOfOrder(String): Delivery`, `tours(): List<Tour>`, `trucks(): List<TruckView>`, `plans(): List<PlanRecord>`, `kpis(): Kpis`, `loadingZone(): List<Delivery>` (READY_TO_LOAD or LOADED, waiting at the zone).
  - Records `RouteMind.TruckView(id, name, status, tourId, from, to, progress)`, `RouteMind.PlanRecord(planId, waveAtMs, atMs, trigger, solver, baseline, optimized, unassigned, tourIds, replaced)`, `RouteMind.Kpis(activeTours, utilization, delivered, onTime, avgDelayMinutes, failed, open, km, cost)`.
  - Constants `WAVE_MS`, `LOADING_LEAD_MS`, `SERVICE_MS`, `READY_BUFFER_MS`, `REPAIR_MS`, `MAX_ATTEMPTS`, `LOADING_ZONE = "LZ-1"`; static `customerOf(String orderId): String`.

- [ ] **Step 1: Test fixture**

`RouteMind/src/test/java/io/nexus/routemind/Fixture.java` — RouteMind with fake ports: reservations always succeed at `FG-A`, transports are only recorded, FlowPilot's confirmation is sent by the test.

```java
package io.nexus.routemind;

import io.nexus.contracts.Domain.Priority;
import io.nexus.contracts.Domain.Severity;
import io.nexus.contracts.Domain.TransportKind;
import io.nexus.contracts.Place;
import io.nexus.contracts.event.EventBus;
import io.nexus.contracts.event.EventIds;
import io.nexus.contracts.event.EventTypes;
import io.nexus.contracts.event.NexusEvent;
import io.nexus.contracts.event.Payload;
import io.nexus.contracts.event.Payloads;
import io.nexus.contracts.memory.MemoryEventLog;
import io.nexus.contracts.memory.MemoryIdSource;
import io.nexus.contracts.memory.MemoryUnitOfWork;
import io.nexus.contracts.port.MaterialService;
import io.nexus.contracts.port.ProductCatalog;
import io.nexus.contracts.port.ProductionEta;
import io.nexus.contracts.port.TransportService;
import io.nexus.contracts.sim.SimTime;
import io.nexus.contracts.state.OrderStatus;
import io.nexus.routemind.optimize.SavingsHeuristic;
import io.nexus.routemind.store.Delivery;
import io.nexus.routemind.store.memory.MemoryRouteStore;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

final class Fixture {
  static final long MIN = 60_000;

  final MemoryUnitOfWork work = new MemoryUnitOfWork();
  final MemoryEventLog log = new MemoryEventLog(work);
  final SimTime time = new SimTime();
  final EventIds eventIds = new EventIds(1);
  final EventBus bus = new EventBus(log, work, time, () -> eventIds);
  final MemoryIdSource ids = new MemoryIdSource(work);
  final List<TransportService.TransportRequest> shipments = new ArrayList<>();
  final Map<String, Long> etas = new HashMap<>();
  final RouteMind rm;

  Fixture(long seed) {
    MaterialService materials = new MaterialService() {
      @Override public ReservationResult reserve(ReservationRequest r) {
        var line = r.lines().get(0);
        return new ReservationResult.Success(r.requestId(),
            List.of(new Reserved("RES-" + r.requestId(), line.materialId(), "FG-A", line.quantity())));
      }
      @Override public void release(String requestId) {}
      @Override public List<Availability> availability(String materialId) { return List.of(); }
    };
    TransportService transports = new TransportService() {
      @Override public String requestTransport(TransportRequest r) {
        for (int i = 0; i < shipments.size(); i++) {
          if (shipments.get(i).requestId().equals(r.requestId())) return "TO-" + (i + 1);
        }
        shipments.add(r);
        return "TO-" + shipments.size();
      }
      @Override public void cancel(String transportId) {}
      @Override public void redirect(String transportId, Place newTo) {}
    };
    ProductionEta eta = orderId -> etas.getOrDefault(orderId, -1L);
    ProductCatalog catalog = new ProductCatalog() {
      @Override public List<MaterialDemand> demandFor(String productId, int quantity) { return List.of(); }
      @Override public String finishedMaterialId(String productId) { return "FG-" + productId; }
    };
    rm = new RouteMind(new MemoryRouteStore(work), bus, work, time, ids, materials, transports, eta, catalog,
        new SavingsHeuristic(), seed);
  }

  void emit(String type, String orderId, Payload p) {
    work.inNewTransaction(() -> bus.publish(type, "test", Severity.INFO, orderId, p));
  }

  /** A released production order. */
  void order(String orderId, String product, int qty, Priority priority, long dueAtMs) {
    emit(EventTypes.ORDER_CREATED, orderId, new Payloads.OrderCreated(orderId, product, qty, priority, dueAtMs));
    emit(EventTypes.ORDER_RELEASED, orderId,
        new Payloads.OrderStatusChanged(orderId, OrderStatus.MATERIAL_PENDING, OrderStatus.RELEASED, "frei"));
  }

  /** SmartFactory's putaway of the good parts, then the completion. */
  void putAway(String orderId, int good) {
    emit(EventTypes.TRANSPORT_DELIVERED, orderId, new Payloads.TransportDelivered("TP-" + orderId,
        TransportKind.PUTAWAY, orderId, "FG-x", good, Place.storage("FG-A")));
    emit(EventTypes.ORDER_COMPLETED, orderId,
        new Payloads.OrderStatusChanged(orderId, OrderStatus.IN_PROGRESS, OrderStatus.COMPLETED, good + " Gutteile"));
  }

  /** FlowPilot reports the shipment at the loading zone. */
  void provide(String orderId) {
    Delivery d = rm.deliveryOfOrder(orderId);
    emit(EventTypes.TRANSPORT_DELIVERED, orderId, new Payloads.TransportDelivered(d.transportId(),
        TransportKind.SHIPMENT, orderId, "FG-x", d.storedQuantity(), Place.loading(RouteMind.LOADING_ZONE)));
  }

  /** Order, putaway, shipment request (within 10 s) and provision: the delivery is READY_TO_LOAD. */
  void ready(String orderId, String product, int qty, long dueAtMs) {
    order(orderId, product, qty, Priority.NORMAL, dueAtMs);
    putAway(orderId, qty);
    advance(10_000);
    provide(orderId);
  }

  void advance(long ms) {
    for (long t = 0; t < ms; t += 100) {
      time.advance(100);
      rm.step();
    }
  }

  void advanceTo(long atMs) {
    advance(atMs - time.nowMs());
  }

  List<NexusEvent> events(String type) {
    List<NexusEvent> out = new ArrayList<>();
    for (NexusEvent e : log.all()) if (e.type().equals(type)) out.add(e);
    return out;
  }

  /** An order id whose delivery goes to the given customer (RouteMind.customerOf). */
  static String orderFor(String customerId, int start) {
    for (int i = start; i < start + 10_000; i++) {
      String id = String.format("PO-%04d", i);
      if (RouteMind.customerOf(id).equals(customerId)) return id;
    }
    throw new IllegalStateException("kein Auftrag für " + customerId);
  }
}
```

- [ ] **Step 2: Write the failing tests**

`RouteMind/src/test/java/io/nexus/routemind/RouteMindTest.java`:

```java
package io.nexus.routemind;

import static io.nexus.routemind.Fixture.MIN;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.nexus.contracts.Domain.Priority;
import io.nexus.contracts.Domain.TransportKind;
import io.nexus.contracts.DomainException;
import io.nexus.contracts.Place;
import io.nexus.contracts.event.EventTypes;
import io.nexus.contracts.event.NexusEvent;
import io.nexus.contracts.event.Payloads;
import io.nexus.contracts.sim.SeededRandom;
import io.nexus.contracts.state.DeliveryStatus;
import io.nexus.contracts.state.IllegalTransitionException;
import io.nexus.contracts.state.OrderStatus;
import io.nexus.contracts.state.TourStatus;
import io.nexus.contracts.state.VehicleStatus;
import io.nexus.routemind.master.RouteMindMasterData;
import io.nexus.routemind.optimize.SavingsHeuristic;
import io.nexus.routemind.store.Delivery;
import io.nexus.routemind.store.Tour;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RouteMindTest {
  private static Tour tourOf(Fixture f, Delivery d) {
    return f.rm.tours().stream().filter(t -> t.id().equals(d.tourId())).findFirst().orElseThrow();
  }

  @Test
  void orderCreatesADeliveryForACustomerWithWindowAndFreight() {
    var f = new Fixture(1);
    f.order("PO-0001", "GH200", 4, Priority.NORMAL, 90 * MIN);
    Delivery d = f.rm.deliveryOfOrder("PO-0001");
    assertThat(d.status()).isEqualTo(DeliveryStatus.AWAITING_GOODS);
    assertThat(d.customerId()).isEqualTo(RouteMind.customerOf("PO-0001")).startsWith("K");
    assertThat(d.weightKg()).isEqualTo(72);
    assertThat(d.pallets()).isEqualTo(1);
    assertThat(d.earliestMs()).isEqualTo(30 * MIN);
    assertThat(d.latestMs()).isEqualTo(270 * MIN);
    NexusEvent created = f.events(EventTypes.DELIVERY_CREATED).get(0);
    assertThat(created.correlationId()).isEqualTo("PO-0001");
  }

  @Test
  void storedGoodsAreReservedAndSentToTheLoadingZone() {
    var f = new Fixture(1);
    f.order("PO-0001", "AW50", 6, Priority.HOCH, 90 * MIN);
    f.putAway("PO-0001", 5);
    f.advance(10_000);
    assertThat(f.shipments).hasSize(1);
    var r = f.shipments.get(0);
    assertThat(r.kind()).isEqualTo(TransportKind.SHIPMENT);
    assertThat(r.from()).isEqualTo(Place.storage("FG-A"));
    assertThat(r.to()).isEqualTo(Place.loading("LZ-1"));
    assertThat(r.quantity()).isEqualTo(5);
    assertThat(r.materialId()).isEqualTo("FG-AW50");
    assertThat(r.reservationId()).isEqualTo("RES-DL-0001/R");
    assertThat(f.rm.deliveryOfOrder("PO-0001").weightKg()).isEqualTo(45); // the 5 good parts, not the 6 ordered
    f.provide("PO-0001");
    assertThat(f.rm.deliveryOfOrder("PO-0001").status()).isEqualTo(DeliveryStatus.READY_TO_LOAD);
    f.advance(20_000);
    assertThat(f.shipments).hasSize(1); // requested once
  }

  @Test
  void waveIsPlannedLoadedStartedAndDelivered() {
    var f = new Fixture(1);
    f.rm.breakdownChance(0);
    f.ready("PO-0001", "GH200", 4, 60 * MIN);
    f.ready("PO-0002", "FP12", 10, 60 * MIN);
    f.advanceTo(30 * MIN + 100);
    Delivery d1 = f.rm.deliveryOfOrder("PO-0001");
    assertThat(d1.tourId()).isNotNull();
    assertThat(tourOf(f, d1).status()).isEqualTo(TourStatus.PLANNED);
    assertThat(tourOf(f, d1).departAtMs()).isEqualTo(60 * MIN);
    var plan = (Payloads.DeliveryPlanComputed) f.events(EventTypes.DELIVERY_PLAN).get(0).payload();
    assertThat(plan.solver()).isEqualTo(SavingsHeuristic.NAME);
    assertThat(plan.optimized().cost()).isLessThanOrEqualTo(plan.baseline().cost() + 1e-6);
    assertThat(plan.unassigned()).isEmpty();

    f.advanceTo(45 * MIN + 1_100);
    assertThat(tourOf(f, f.rm.deliveryOfOrder("PO-0001")).status()).isEqualTo(TourStatus.LOADING);
    assertThat(f.rm.deliveryOfOrder("PO-0001").status()).isEqualTo(DeliveryStatus.LOADED);
    assertThat(f.rm.deliveryOfOrder("PO-0002").status()).isEqualTo(DeliveryStatus.LOADED);

    f.advanceTo(60 * MIN + 100);
    assertThat(f.rm.deliveryOfOrder("PO-0001").status()).isEqualTo(DeliveryStatus.IN_TRANSIT);
    assertThat(tourOf(f, f.rm.deliveryOfOrder("PO-0001")).status()).isEqualTo(TourStatus.EN_ROUTE);

    f.advanceTo(6 * 60 * MIN);
    for (String o : List.of("PO-0001", "PO-0002")) {
      Delivery d = f.rm.deliveryOfOrder(o);
      assertThat(d.status()).isEqualTo(DeliveryStatus.DELIVERED);
      assertThat(d.deliveredAtMs()).isNotNull();
      assertThat(tourOf(f, d).status()).isEqualTo(TourStatus.COMPLETED);
    }
    assertThat(f.rm.trucks()).allSatisfy(t -> assertThat(t.status()).isEqualTo(VehicleStatus.AVAILABLE));
    assertThat(f.rm.kpis().delivered()).isEqualTo(2);
  }

  @Test
  void noTourStartsWithoutConfirmedLoading() {
    var f = new Fixture(1);
    f.rm.breakdownChance(0);
    f.ready("PO-0001", "GH200", 2, 60 * MIN);
    f.order("PO-0002", "GH200", 2, Priority.NORMAL, 60 * MIN);
    f.etas.put("PO-0002", 20 * MIN); // expected ready 30 min, before loading starts: planned, but never arrives
    f.advanceTo(30 * MIN + 100);
    assertThat(f.rm.deliveryOfOrder("PO-0002").tourId()).as("planned on its expected completion").isNotNull();
    f.advanceTo(60 * MIN + 100);
    Delivery late = f.rm.deliveryOfOrder("PO-0002");
    assertThat(late.status()).isEqualTo(DeliveryStatus.AWAITING_GOODS);
    assertThat(late.tourId()).isNull();
    Delivery onBoard = f.rm.deliveryOfOrder("PO-0001");
    assertThat(onBoard.status()).isEqualTo(DeliveryStatus.IN_TRANSIT);
    assertThat(tourOf(f, onBoard).stops()).extracting(Tour.Stop::deliveryId).containsExactly(onBoard.id());
    assertThat(f.events(EventTypes.DECISION)).isNotEmpty();
  }

  @Test
  void tourWithoutAnyGoodsIsDroppedAtDeparture() {
    var f = new Fixture(1);
    f.order("PO-0001", "GH200", 2, Priority.NORMAL, 60 * MIN);
    f.etas.put("PO-0001", 20 * MIN);
    f.advanceTo(60 * MIN + 100);
    Tour t = f.rm.tours().get(0);
    assertThat(t.status()).isEqualTo(TourStatus.REPLANNED);
    assertThat(f.rm.deliveryOfOrder("PO-0001").tourId()).isNull();
    assertThat(f.rm.trucks()).allSatisfy(v -> assertThat(v.status()).isEqualTo(VehicleStatus.AVAILABLE));
  }

  @Test
  void breakdownKeepsDeliveredStopsAndRetriesTheRest() {
    var f = new Fixture(1);
    f.rm.breakdownChance(0);
    // three customers close to the plant: one tour serves all of them
    String a = Fixture.orderFor("K06", 1);
    String b = Fixture.orderFor("K01", 1);
    String c = Fixture.orderFor("K30", 1);
    for (String o : List.of(a, b, c)) f.ready(o, "FP12", 4, 60 * MIN);
    f.advanceTo(60 * MIN + 100);
    Tour tour = tourOf(f, f.rm.deliveryOfOrder(a));
    assertThat(tour.stops()).as("one tour for the three").hasSize(3);
    while (f.rm.deliveries().stream().noneMatch(d -> d.status() == DeliveryStatus.DELIVERED)) f.advance(1_000);
    Delivery first = f.rm.deliveries().stream().filter(d -> d.status() == DeliveryStatus.DELIVERED).findFirst().orElseThrow();
    Long arrived = tourOf(f, first).stops().stream().filter(s -> s.deliveryId().equals(first.id())).findFirst()
        .orElseThrow().arrivedAtMs();

    f.rm.breakDown(tour.vehicleId());
    f.advance(1_000);
    assertThat(tourOf(f, first).status()).isEqualTo(TourStatus.INTERRUPTED);
    Delivery again = f.rm.deliveryOfOrder(first.orderId());
    assertThat(again.status()).isEqualTo(DeliveryStatus.DELIVERED);
    assertThat(again.deliveredAtMs()).isEqualTo(first.deliveredAtMs());
    assertThat(tourOf(f, again).stops().stream().filter(s -> s.deliveryId().equals(first.id())).findFirst()
        .orElseThrow().arrivedAtMs()).isEqualTo(arrived);
    List<Delivery> rest = f.rm.deliveries().stream().filter(d -> d != null && !d.id().equals(first.id())).toList();
    assertThat(rest).allSatisfy(d -> {
      assertThat(d.status()).isEqualTo(DeliveryStatus.FAILED);
      assertThat(d.failures()).isEqualTo(1);
    });

    long nextPlan = (f.time.nowMs() / RouteMind.WAVE_MS + 1) * RouteMind.WAVE_MS;
    f.advanceTo(nextPlan + 100);
    for (Delivery d : rest) {
      Delivery now = f.rm.deliveryOfOrder(d.orderId());
      assertThat(now.status()).isEqualTo(DeliveryStatus.READY_TO_LOAD);
      assertThat(now.tourId()).as("planned again").isNotNull();
      assertThat(tourOf(f, now).status()).isEqualTo(TourStatus.PLANNED);
    }
    assertThat(f.rm.deliveryOfOrder(first.orderId()).status()).isEqualTo(DeliveryStatus.DELIVERED);
  }

  @Test
  void deliveryIsGivenUpAfterThreeFailedAttempts() {
    var f = new Fixture(1);
    f.rm.breakdownChance(0);
    f.ready("PO-0001", "GH200", 2, 60 * MIN);
    for (int attempt = 1; attempt <= RouteMind.MAX_ATTEMPTS; attempt++) {
      while (f.rm.deliveryOfOrder("PO-0001").status() != DeliveryStatus.IN_TRANSIT) f.advance(10_000);
      String vehicle = tourOf(f, f.rm.deliveryOfOrder("PO-0001")).vehicleId();
      f.rm.breakDown(vehicle);
      f.rm.repair(vehicle);
      assertThat(f.rm.deliveryOfOrder("PO-0001").failures()).isEqualTo(attempt);
    }
    f.advance(2 * RouteMind.WAVE_MS);
    Delivery d = f.rm.deliveryOfOrder("PO-0001");
    assertThat(d.status()).isEqualTo(DeliveryStatus.FAILED);
    assertThat(f.events(EventTypes.DECISION)).anySatisfy(e ->
        assertThat(((Payloads.Decision) e.payload()).chosen()).isEqualTo("aufgegeben"));
  }

  @Test
  void vehicleCommands() {
    var f = new Fixture(1);
    f.rm.setMaintenance("LW-1", true);
    assertThat(f.rm.trucks().stream().filter(t -> t.id().equals("LW-1")).findFirst().orElseThrow().status())
        .isEqualTo(VehicleStatus.MAINTENANCE);
    assertThatThrownBy(() -> f.rm.breakDown("LW-1")).isInstanceOf(IllegalTransitionException.class);
    f.rm.setMaintenance("LW-1", false);
    assertThatThrownBy(() -> f.rm.repair("LW-1")).isInstanceOf(IllegalTransitionException.class);
    assertThatThrownBy(() -> f.rm.breakDown("LKW-9")).isInstanceOf(DomainException.class);

    f.ready("PO-0001", "GH200", 2, 60 * MIN);
    f.advanceTo(45 * MIN + 1_100);
    String loading = tourOf(f, f.rm.deliveryOfOrder("PO-0001")).vehicleId();
    assertThatThrownBy(() -> f.rm.breakDown(loading)).isInstanceOf(IllegalTransitionException.class);
  }

  @Test
  void cancelledOrderAndOrderWithoutGoodPartsCancelTheirDelivery() {
    var f = new Fixture(1);
    f.order("PO-0001", "GH200", 2, Priority.NORMAL, 60 * MIN);
    f.emit(EventTypes.ORDER_CANCELLED, "PO-0001",
        new Payloads.OrderStatusChanged("PO-0001", OrderStatus.RELEASED, OrderStatus.CANCELLED, "storniert"));
    assertThat(f.rm.deliveryOfOrder("PO-0001").status()).isEqualTo(DeliveryStatus.CANCELLED);
    f.order("PO-0002", "GH200", 2, Priority.NORMAL, 60 * MIN);
    f.emit(EventTypes.ORDER_COMPLETED, "PO-0002",
        new Payloads.OrderStatusChanged("PO-0002", OrderStatus.IN_PROGRESS, OrderStatus.COMPLETED, "0 Gutteile"));
    assertThat(f.rm.deliveryOfOrder("PO-0002").status()).isEqualTo(DeliveryStatus.CANCELLED);
  }

  @Test
  void hardRulesHoldOverALongRandomRun() {
    var f = new Fixture(42);
    f.rm.breakdownChance(0.2);
    Map<String, Long> deliveredAt = new HashMap<>();
    Map<String, Long> arrivedAt = new HashMap<>();
    randomRun(f, 42, 12 * 60 * MIN, () -> {
      for (Tour t : f.rm.tours()) {
        if (t.status() == TourStatus.REPLANNED) continue;
        var seed = RouteMindMasterData.vehicle(t.vehicleId());
        int kg = 0;
        int pallets = 0;
        for (Tour.Stop s : t.stops()) {
          Delivery d = f.rm.deliveries().stream().filter(x -> x.id().equals(s.deliveryId())).findFirst().orElseThrow();
          kg += d.weightKg();
          pallets += d.pallets();
          if (t.status() == TourStatus.EN_ROUTE) {
            assertThat(d.status()).as("on a running tour " + t.id()).isIn(DeliveryStatus.IN_TRANSIT, DeliveryStatus.DELIVERED);
          }
          if (s.arrivedAtMs() != null) {
            Long before = arrivedAt.putIfAbsent(t.id() + "/" + s.seq(), s.arrivedAtMs());
            assertThat(before == null || before.equals(s.arrivedAtMs())).as("visited stop unchanged").isTrue();
          }
        }
        assertThat(kg).as("weight on " + t.id()).isLessThanOrEqualTo(seed.maxKg());
        assertThat(pallets).as("pallets on " + t.id()).isLessThanOrEqualTo(seed.maxPallets());
      }
      for (Delivery d : f.rm.deliveries()) {
        if (deliveredAt.containsKey(d.id())) {
          assertThat(d.status()).as("delivered stays delivered").isEqualTo(DeliveryStatus.DELIVERED);
          assertThat(d.deliveredAtMs()).isEqualTo(deliveredAt.get(d.id()));
        } else if (d.status() == DeliveryStatus.DELIVERED) {
          deliveredAt.put(d.id(), d.deliveredAtMs());
        }
      }
    });
    assertThat(deliveredAt).as("deliveries happened").hasSizeGreaterThan(20);
    assertThat(f.events(EventTypes.TOUR_STATE)).as("breakdowns happened").anySatisfy(e ->
        assertThat(((Payloads.TourStateChanged) e.payload()).to()).isEqualTo(TourStatus.INTERRUPTED));
  }

  @Test
  void sameSeedSameEvents() {
    List<String> first = new ArrayList<>();
    List<String> second = new ArrayList<>();
    for (List<String> out : List.of(first, second)) {
      var f = new Fixture(7);
      randomRun(f, 7, 4 * 60 * MIN, () -> {});
      for (NexusEvent e : f.log.all()) out.add(e.simTimeMs() + " " + e.type() + " " + e.payload());
    }
    assertThat(second).isEqualTo(first);
  }

  /** Orders every 6 min; putaway after 20..50 min; FlowPilot provides 2 min after RouteMind asked for the goods. */
  private static void randomRun(Fixture f, long seed, long untilMs, Runnable check) {
    var r = new SeededRandom(seed);
    String[] products = {"GH200", "AW50", "FP12", "MT7"};
    record Pending(long atMs, String orderId, int qty, boolean provide) {}
    List<Pending> pending = new ArrayList<>();
    int n = 0;
    while (f.time.nowMs() < untilMs) {
      long now = f.time.nowMs();
      if (now % (6 * MIN) == 0) {
        String id = String.format("PO-%04d", ++n);
        int qty = 3 + r.nextInt(8);
        f.order(id, products[r.nextInt(4)], qty, Priority.NORMAL, now + (45 + r.nextInt(46)) * MIN);
        long done = now + (20 + r.nextInt(31)) * MIN;
        f.etas.put(id, done);
        pending.add(new Pending(done, id, qty, false));
      }
      List<Pending> due = new ArrayList<>();
      for (Pending p : pending) if (p.atMs() <= now) due.add(p);
      pending.removeAll(due);
      for (Pending p : due) {
        if (!p.provide()) {
          f.putAway(p.orderId(), p.qty());
          pending.add(new Pending(now + 2 * MIN, p.orderId(), p.qty(), true));
        } else if (f.rm.deliveryOfOrder(p.orderId()).transportId() != null) {
          f.provide(p.orderId());
        } else {
          pending.add(new Pending(now + MIN, p.orderId(), p.qty(), true));
        }
      }
      f.advance(100);
      if (now % 10_000 == 0) check.run();
    }
  }
}
```

Run: `./mvnw -q -pl RouteMind -am test -Dtest=RouteMindTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: COMPILATION ERROR, `RouteMind` not found.

- [ ] **Step 3: Implement `RouteMind`**

`RouteMind/src/main/java/io/nexus/routemind/RouteMind.java`:

```java
package io.nexus.routemind;

import io.nexus.contracts.Domain.Priority;
import io.nexus.contracts.Domain.Severity;
import io.nexus.contracts.Domain.TransportKind;
import io.nexus.contracts.DomainException;
import io.nexus.contracts.Place;
import io.nexus.contracts.event.EventBus;
import io.nexus.contracts.event.EventConsumer;
import io.nexus.contracts.event.EventTypes;
import io.nexus.contracts.event.NexusEvent;
import io.nexus.contracts.event.Payloads;
import io.nexus.contracts.event.UnitOfWork;
import io.nexus.contracts.memory.MemoryUnitOfWork;
import io.nexus.contracts.port.IdSource;
import io.nexus.contracts.port.MaterialService;
import io.nexus.contracts.port.ProductCatalog;
import io.nexus.contracts.port.ProductionEta;
import io.nexus.contracts.port.TransportService;
import io.nexus.contracts.sim.SeededRandom;
import io.nexus.contracts.sim.SimTime;
import io.nexus.contracts.state.DeliveryStatus;
import io.nexus.contracts.state.TourStatus;
import io.nexus.contracts.state.VehicleStatus;
import io.nexus.routemind.master.Roads;
import io.nexus.routemind.master.RouteMindMasterData;
import io.nexus.routemind.optimize.Evaluation;
import io.nexus.routemind.optimize.Job;
import io.nexus.routemind.optimize.Matrix;
import io.nexus.routemind.optimize.NearestNeighbour;
import io.nexus.routemind.optimize.Problem;
import io.nexus.routemind.optimize.Route;
import io.nexus.routemind.optimize.RouteOptimizer;
import io.nexus.routemind.optimize.SavingsHeuristic;
import io.nexus.routemind.optimize.Solution;
import io.nexus.routemind.optimize.Truck;
import io.nexus.routemind.optimize.Unassigned;
import io.nexus.routemind.store.Delivery;
import io.nexus.routemind.store.RouteStore;
import io.nexus.routemind.store.Tour;
import io.nexus.routemind.store.VehicleRecord;
import io.nexus.routemind.store.memory.MemoryRouteStore;
import java.util.ArrayList;
import java.util.List;

/**
 * Facade of RouteMind (Spec 15a): delivery orders, vehicles, tours. Listens to order and transport events, asks
 * FlowPilot for the goods and SmartFactory for completion times, plans waves with the RouteOptimizer and moves the
 * vehicles on the simulation clock.
 *
 * <p>Waves: tours leave every WAVE_MS within the shift. Wave W is planned at W − WAVE_MS, loaded from
 * W − LOADING_LEAD_MS and started at W. A tour starts only with confirmed loading (hard rule); goods that missed
 * the departure stay for the next wave. A replanning touches PLANNED tours only, never a delivered delivery or a
 * visited stop.
 *
 * <p>Threading: every public method holds the event bus monitor, the platform lock. The consumer only records facts;
 * everything that calls other modules happens in step().
 *
 * <p>ponytail: tours() and deliveries() read all records; fine for a simulated day (~30 waves). An "open" query on
 * the store is the upgrade for long server runs.
 */
public final class RouteMind {
  public static final String CONSUMER = "routemind";
  public static final String SOURCE = "routemind";
  public static final String LOADING_ZONE = "LZ-1";
  public static final long MIN = RouteMindMasterData.MINUTE;
  public static final long WAVE_MS = 30 * MIN;
  public static final long LOADING_LEAD_MS = 15 * MIN;
  public static final long SERVICE_MS = 10 * MIN;
  public static final long READY_BUFFER_MS = 10 * MIN;
  public static final long REPAIR_MS = 60 * MIN;
  /** The first attempt and at most two more (Spec 15a). */
  public static final int MAX_ATTEMPTS = 3;
  private static final long PROVISION_EVERY_MS = 10_000;
  private static final long LOAD_EVERY_MS = 1_000;
  private static final int KEEP_PLANS = 20;
  private static final Matrix ROADS = new Matrix() {
    @Override public int seconds(int from, int to) { return Roads.seconds(from, to); }
    @Override public int meters(int from, int to) { return Roads.meters(from, to); }
  };

  public record TruckView(String id, String name, VehicleStatus status, String tourId, String from, String to,
                          double progress) {}

  /** One planning run, kept for the dashboard; replaced = the optimised figures of the plan it superseded. */
  public record PlanRecord(String planId, long waveAtMs, long atMs, String trigger, String solver,
                           Payloads.PlanFigures baseline, Payloads.PlanFigures optimized, List<String> unassigned,
                           List<String> tourIds, Payloads.PlanFigures replaced) {}

  public record Kpis(int activeTours, double utilization, int delivered, int onTime, double avgDelayMinutes,
                     int failed, int open, double km, double cost) {}

  private final RouteStore store;
  private final EventBus bus;
  private final UnitOfWork work;
  private final SimTime time;
  private final IdSource ids;
  private final MaterialService materials;
  private final TransportService transports;
  private final ProductionEta eta;
  private final ProductCatalog catalog;
  private final RouteOptimizer optimizer;
  private final Object lock;
  private final List<PlanRecord> plans = new ArrayList<>(); // newest first
  private SeededRandom random;
  private double breakdownPerLeg = 0.01;
  private boolean replanNeeded;
  private String replanReason;

  public RouteMind(RouteStore store, EventBus bus, UnitOfWork work, SimTime time, IdSource ids,
                   MaterialService materials, TransportService transports, ProductionEta eta, ProductCatalog catalog,
                   RouteOptimizer optimizer, long seed) {
    this.store = store;
    this.bus = bus;
    this.work = work;
    this.time = time;
    this.ids = ids;
    this.materials = materials;
    this.transports = transports;
    this.eta = eta;
    this.catalog = catalog;
    this.optimizer = optimizer;
    this.lock = bus;
    this.random = new SeededRandom(seed).fork("routemind");
    bus.register(new EventConsumer() {
      @Override public String name() { return CONSUMER; }
      @Override public void on(NexusEvent e) { consume(e); }
    });
  }

  public static RouteMind inMemory(EventBus bus, MemoryUnitOfWork work, SimTime time, IdSource ids,
                                   MaterialService materials, TransportService transports, ProductionEta eta,
                                   ProductCatalog catalog, long seed) {
    return new RouteMind(new MemoryRouteStore(work), bus, work, time, ids, materials, transports, eta, catalog,
        new SavingsHeuristic(), seed);
  }

  /** The customer of an order's delivery: fixed per order id, spread over all customers. */
  public static String customerOf(String orderId) {
    List<Roads.Place> customers = Roads.customers();
    return customers.get(Math.floorMod(orderId.hashCode(), customers.size())).id();
  }

  /** Tests only: chance of a breakdown per leg (default 1 %). */
  void breakdownChance(double chance) {
    this.breakdownPerLeg = chance;
  }

  // ---- events: record facts only ---------------------------------------------------------------------------

  private void consume(NexusEvent e) {
    synchronized (lock) {
      switch (e.type()) {
        case EventTypes.ORDER_CREATED -> created((Payloads.OrderCreated) e.payload(), e.simTimeMs());
        case EventTypes.ORDER_RELEASED, EventTypes.ORDER_STARTED ->
            production(((Payloads.OrderStatusChanged) e.payload()).orderId());
        case EventTypes.ORDER_CANCELLED -> cancelled(((Payloads.OrderStatusChanged) e.payload()).orderId());
        case EventTypes.ORDER_COMPLETED -> completed(((Payloads.OrderStatusChanged) e.payload()).orderId());
        case EventTypes.ORDER_RESCHEDULED -> rescheduled(((Payloads.OrderRescheduled) e.payload()).orderId());
        case EventTypes.TRANSPORT_DELIVERED -> delivered((Payloads.TransportDelivered) e.payload());
        default -> { }
      }
    }
  }

  private void created(Payloads.OrderCreated o, long now) {
    if (store.deliveryOfOrder(o.orderId()).isPresent()) return;
    var freight = RouteMindMasterData.freight(o.productId());
    String customer = customerOf(o.orderId());
    long earliest = o.dueAtMs() - RouteMindMasterData.HOUR;  // window: one hour before due to three hours after
    long latest = o.dueAtMs() + 3 * RouteMindMasterData.HOUR;
    var d = new Delivery(ids.next("DL"), o.orderId(), customer, o.productId(), o.quantity(), 0,
        o.quantity() * freight.kgPerUnit(), RouteMindMasterData.pallets(freight, o.quantity()), o.priority(),
        earliest, latest, DeliveryStatus.CREATED, null, null, null, null, null, 0, now, 0);
    store.insertDelivery(d);
    bus.publish(EventTypes.DELIVERY_CREATED, SOURCE, Severity.INFO, o.orderId(), new Payloads.DeliveryCreated(d.id(),
        d.orderId(), customer, d.quantity(), d.weightKg(), d.pallets(), earliest, latest));
    if (o.priority() == Priority.EILIG) requestReplan("Expressauftrag " + o.orderId());
  }

  private void production(String orderId) {
    Delivery d = store.deliveryOfOrder(orderId).orElse(null);
    if (d != null && d.status() == DeliveryStatus.CREATED) move(d, DeliveryStatus.AWAITING_GOODS, "Produktion läuft");
  }

  private void cancelled(String orderId) {
    Delivery d = store.deliveryOfOrder(orderId).orElse(null);
    if (d == null || !DeliveryStatus.RULES.can(d.status(), DeliveryStatus.CANCELLED)) return;
    move(d, DeliveryStatus.CANCELLED, "Auftrag storniert");
    if (d.tourId() != null) requestReplan("Lieferung " + d.id() + " storniert");
  }

  /** SmartFactory completes an order with its putaway; without a good part there was none and nothing to ship. */
  private void completed(String orderId) {
    Delivery d = store.deliveryOfOrder(orderId).orElse(null);
    if (d == null || d.storedQuantity() > 0 || !DeliveryStatus.RULES.can(d.status(), DeliveryStatus.CANCELLED)) return;
    move(d, DeliveryStatus.CANCELLED, "Kein Gutteil, nichts zu liefern");
  }

  private void rescheduled(String orderId) {
    Delivery d = store.deliveryOfOrder(orderId).orElse(null);
    if (d == null || d.tourId() == null) return;
    Tour t = store.tour(d.tourId()).orElse(null);
    if (t != null && t.status() == TourStatus.PLANNED) requestReplan("Produktion von " + orderId + " verschoben");
  }

  private void delivered(Payloads.TransportDelivered t) {
    if (t.orderId() == null) return;
    Delivery d = store.deliveryOfOrder(t.orderId()).orElse(null);
    if (d == null) return;
    if (t.kind() == TransportKind.PUTAWAY && d.storedQuantity() == 0) {
      var f = RouteMindMasterData.freight(d.productId());
      store.updateDelivery(d.withGoods(t.quantity(), t.quantity() * f.kgPerUnit(),
          RouteMindMasterData.pallets(f, t.quantity())));
    } else if (t.kind() == TransportKind.SHIPMENT && t.transportId().equals(d.transportId())
        && d.status() == DeliveryStatus.AWAITING_GOODS) {
      move(d, DeliveryStatus.READY_TO_LOAD, "Bereitstellung an der Verladezone bestätigt");
    }
  }

  private void requestReplan(String reason) {
    replanNeeded = true;
    if (replanReason == null) replanReason = reason;
  }

  // ---- simulation step -------------------------------------------------------------------------------------

  /** One plant step: goods to the loading zone, waves (plan, load, depart), driving, repairs. */
  public void step() {
    synchronized (lock) {
      long now = time.nowMs();
      if (now % PROVISION_EVERY_MS == 0) provision();
      long intoWave = Math.floorMod(now, WAVE_MS);
      if (intoWave == 0) {
        depart(now);
        long next = now + WAVE_MS;
        if (inShift(next)) work.inNewTransaction(() -> plan(next, "Wellenplanung", null));
      } else if (intoWave == WAVE_MS - LOADING_LEAD_MS) {
        startLoading(now + LOADING_LEAD_MS);
      } else if (replanNeeded && intoWave < WAVE_MS - LOADING_LEAD_MS) {
        long wave = now - intoWave + WAVE_MS;
        String reason = replanReason;
        work.inNewTransaction(() -> replan(wave, reason));
      }
      if (intoWave >= WAVE_MS - LOADING_LEAD_MS && now % LOAD_EVERY_MS == 0) load();
      drive(now);
      repairs(now);
    }
  }

  private static boolean inShift(long wave) {
    return Math.floorMod(wave, RouteMindMasterData.DAY) < RouteMindMasterData.SHIFT_END;
  }

  /** Goods put away for a delivery: reserve them and order the transport to the loading zone (Integration 2). */
  private void provision() {
    for (Delivery d : store.deliveries()) {
      if (d.status() != DeliveryStatus.AWAITING_GOODS || d.storedQuantity() == 0 || d.transportId() != null) continue;
      work.inNewTransaction(() -> requestGoods(store.delivery(d.id()).orElseThrow()));
    }
  }

  private void requestGoods(Delivery d) {
    String material = catalog.finishedMaterialId(d.productId());
    var result = materials.reserve(new MaterialService.ReservationRequest(d.id() + "/R", d.orderId(),
        List.of(new MaterialService.Line(material, d.storedQuantity()))));
    if (!(result instanceof MaterialService.ReservationResult.Success ok)) return; // shortage: again in 10 s
    var r = ok.reservations().get(0);
    String transport = transports.requestTransport(new TransportService.TransportRequest(d.id() + "/V",
        TransportKind.SHIPMENT, d.orderId(), material, d.storedQuantity(), Place.storage(r.locationId()),
        Place.loading(LOADING_ZONE), d.priority(), r.reservationId()));
    store.updateDelivery(store.delivery(d.id()).orElseThrow().withShipment(d.id() + "/R", transport));
  }

  // ---- planning --------------------------------------------------------------------------------------------

  private void replan(long wave, String reason) {
    Payloads.PlanFigures before = null;
    for (PlanRecord p : plans) {
      if (p.waveAtMs() == wave) {
        before = p.optimized();
        break;
      }
    }
    for (Tour t : store.tours()) {
      if (t.departAtMs() != wave || t.status() != TourStatus.PLANNED) continue;
      moveTour(t, TourStatus.REPLANNED, reason);
      for (Tour.Stop s : t.stops()) release(s.deliveryId(), t.id());
    }
    plan(wave, "Neuplanung: " + reason, before);
  }

  /** Plans wave `wave`: ready goods and goods expected before loading starts, on the vehicles that are back. */
  private void plan(long wave, String trigger, Payloads.PlanFigures replaced) {
    replanNeeded = false;
    replanReason = null;
    long now = time.nowMs();
    for (Delivery d : store.deliveries()) {
      if (d.status() == DeliveryStatus.FAILED && d.failures() < MAX_ATTEMPTS) {
        Delivery back = move(d, DeliveryStatus.READY_TO_LOAD, "Ware zurück im Depot, Versuch " + (d.failures() + 1));
        store.updateDelivery(back.withTour(null, null));
      }
    }
    List<VehicleRecord> usable = new ArrayList<>();
    for (VehicleRecord v : store.vehicles()) {
      if (hasTourInWave(v.id(), wave)) continue;
      boolean back = v.status() == VehicleStatus.AVAILABLE
          || (v.status() == VehicleStatus.RETURNING && v.legEndMs() <= wave - LOADING_LEAD_MS);
      if (back) usable.add(v);
    }
    List<Delivery> candidates = new ArrayList<>();
    for (Delivery d : store.deliveries()) {
      if (d.tourId() != null && onOpenTour(d)) continue;
      if (d.status() == DeliveryStatus.READY_TO_LOAD
          || (d.status() == DeliveryStatus.AWAITING_GOODS && expectedReady(d, now) <= wave - LOADING_LEAD_MS)) {
        candidates.add(d);
      }
    }
    if (candidates.isEmpty()) return;
    Problem p = problem(wave, usable, candidates);
    Solution base = new NearestNeighbour().solve(p);
    Solution best = optimizer.solve(p);
    String planId = ids.next("RP");
    List<String> tourIds = new ArrayList<>();
    for (Route r : best.routes()) {
      var f = Evaluation.route(p, p.truck(r.truckId()), Evaluation.jobs(p, r.jobIds()));
      String tourId = ids.next("TR");
      List<Tour.Stop> stops = new ArrayList<>();
      List<String> deliveryIds = new ArrayList<>();
      for (var st : f.stops()) {
        Delivery d = store.delivery(st.jobId()).orElseThrow();
        stops.add(new Tour.Stop(stops.size() + 1, d.id(), d.customerId(), st.startMs(), null));
        deliveryIds.add(d.id());
        store.updateDelivery(d.withTour(tourId, st.startMs()));
      }
      var tour = new Tour(tourId, planId, r.truckId(), wave, TourStatus.PLANNED, stops, f.meters() / 1000.0, f.cost(),
          null, null, 0);
      store.insertTour(tour);
      tourIds.add(tourId);
      bus.publish(EventTypes.TOUR_PLANNED, SOURCE, Severity.INFO, null,
          new Payloads.TourPlanned(tourId, r.truckId(), planId, wave, deliveryIds, tour.km(), tour.cost()));
    }
    List<String> open = new ArrayList<>();
    for (Unassigned u : best.unassigned()) open.add(u.jobId() + ": " + u.reason());
    var record = new PlanRecord(planId, wave, now, trigger, best.solver(), figures(Evaluation.of(p, base)),
        figures(Evaluation.of(p, best)), open, tourIds, replaced);
    plans.add(0, record);
    while (plans.size() > KEEP_PLANS) plans.remove(plans.size() - 1);
    bus.publish(EventTypes.DELIVERY_PLAN, SOURCE, open.isEmpty() ? Severity.INFO : Severity.WARNING, null,
        new Payloads.DeliveryPlanComputed(planId, best.solver(), wave, trigger, record.baseline(), record.optimized(),
            open));
  }

  private long expectedReady(Delivery d, long now) {
    if (d.storedQuantity() > 0) return now + READY_BUFFER_MS;
    long finish = eta.plannedFinishMs(d.orderId());
    return finish < 0 ? Long.MAX_VALUE : finish + READY_BUFFER_MS;
  }

  private Problem problem(long wave, List<VehicleRecord> vehicles, List<Delivery> deliveries) {
    long endMs = wave - Math.floorMod(wave, RouteMindMasterData.DAY) + RouteMindMasterData.SHIFT_END;
    List<Truck> trucks = new ArrayList<>();
    for (VehicleRecord v : vehicles) {
      var seed = RouteMindMasterData.vehicle(v.id());
      trucks.add(new Truck(v.id(), seed.maxKg(), seed.maxPallets(), seed.costPerKm(), seed.costPerHour(), endMs));
    }
    List<Job> jobs = new ArrayList<>();
    for (Delivery d : deliveries) {
      jobs.add(new Job(d.id(), Roads.place(d.customerId()).index(), d.weightKg(), d.pallets(), d.earliestMs(),
          d.latestMs(), SERVICE_MS));
    }
    return new Problem(wave, 0, trucks, jobs, ROADS);
  }

  private static Payloads.PlanFigures figures(Evaluation.Figures f) {
    return new Payloads.PlanFigures(f.km(), f.driveMinutes(), f.cost(), f.lateMinutes(), f.tours());
  }

  private boolean hasTourInWave(String vehicleId, long wave) {
    for (Tour t : store.tours()) {
      if (t.vehicleId().equals(vehicleId) && t.departAtMs() == wave && !t.status().terminal()) return true;
    }
    return false;
  }

  private boolean onOpenTour(Delivery d) {
    Tour t = store.tour(d.tourId()).orElse(null);
    return t != null && !t.status().terminal();
  }

  private void release(String deliveryId, String tourId) {
    Delivery d = store.delivery(deliveryId).orElseThrow();
    if (tourId.equals(d.tourId())) store.updateDelivery(d.withTour(null, null));
  }

  // ---- loading and departure -------------------------------------------------------------------------------

  private void startLoading(long wave) {
    for (Tour t : store.tours()) {
      if (t.departAtMs() != wave || t.status() != TourStatus.PLANNED) continue;
      VehicleRecord v = vehicle(t.vehicleId());
      if (v.status() != VehicleStatus.AVAILABLE) continue; // not back: the tour is dropped at departure
      work.inNewTransaction(() -> {
        moveTour(t, TourStatus.LOADING, "Verladung beginnt");
        moveVehicle(v, v.withTour(t.id()).withStatus(VehicleStatus.LOADING));
      });
    }
  }

  /** Confirms the loading of every delivery whose goods are at the loading zone (Integration 3). */
  private void load() {
    for (Tour t : store.tours()) {
      if (t.status() != TourStatus.LOADING) continue;
      for (Tour.Stop s : t.stops()) {
        Delivery d = store.delivery(s.deliveryId()).orElseThrow();
        if (d.status() == DeliveryStatus.READY_TO_LOAD && t.id().equals(d.tourId())) {
          work.inNewTransaction(() -> move(store.delivery(d.id()).orElseThrow(), DeliveryStatus.LOADED,
              "Verladung auf " + t.vehicleId() + " bestätigt"));
        }
      }
    }
  }

  private void depart(long wave) {
    for (Tour t : store.tours()) {
      if (t.departAtMs() != wave) continue;
      if (t.status() == TourStatus.PLANNED) {
        work.inNewTransaction(() -> drop(store.tour(t.id()).orElseThrow(),
            "Fahrzeug " + t.vehicleId() + " nicht rechtzeitig bereit"));
      } else if (t.status() == TourStatus.LOADING) {
        work.inNewTransaction(() -> start(store.tour(t.id()).orElseThrow()));
      }
    }
  }

  private void drop(Tour t, String reason) {
    moveTour(t, TourStatus.REPLANNED, reason);
    for (Tour.Stop s : t.stops()) release(s.deliveryId(), t.id());
    VehicleRecord v = vehicle(t.vehicleId());
    if (v.status() == VehicleStatus.LOADING && t.id().equals(v.tourId())) moveVehicle(v, v.back(VehicleStatus.AVAILABLE));
  }

  private void start(Tour t) {
    List<Tour.Stop> kept = new ArrayList<>();
    List<String> missing = new ArrayList<>();
    for (Tour.Stop s : t.stops()) {
      Delivery d = store.delivery(s.deliveryId()).orElseThrow();
      if (d.status() == DeliveryStatus.LOADED && t.id().equals(d.tourId())) {
        kept.add(s);
      } else {
        missing.add(d.id());
        release(d.id(), t.id());
      }
    }
    if (kept.isEmpty()) {
      drop(t, "Keine Ware rechtzeitig an der Verladezone");
      return;
    }
    Tour tour = t;
    if (!missing.isEmpty()) {
      tour = store.updateTour(retimed(t, kept));
      bus.publish(EventTypes.DECISION, SOURCE, Severity.WARNING, null, new Payloads.Decision(t.id(),
          "ohne " + String.join(", ", missing), List.of(), "Ware nicht rechtzeitig verladen, die Tour fährt ohne sie"));
    }
    // hard rule (Spec 15a): a tour starts only when the loading of every one of its deliveries is confirmed
    for (Tour.Stop s : tour.stops()) {
      if (store.delivery(s.deliveryId()).orElseThrow().status() != DeliveryStatus.LOADED) {
        throw new IllegalStateException("Tour " + t.id() + " ohne bestätigte Verladung");
      }
    }
    long now = time.nowMs();
    tour = moveTour(tour.withStarted(now), TourStatus.EN_ROUTE, "Abfahrt");
    for (Tour.Stop s : tour.stops()) {
      move(store.delivery(s.deliveryId()).orElseThrow(), DeliveryStatus.IN_TRANSIT, "Unterwegs mit " + t.vehicleId());
    }
    VehicleRecord v = vehicle(t.vehicleId());
    moveVehicle(v, leg(v.withStatus(VehicleStatus.EN_ROUTE), tour, 0, now));
  }

  /** The same stops in the same order without the missing ones: new arrival times, distance and cost. */
  private Tour retimed(Tour t, List<Tour.Stop> kept) {
    List<Delivery> ds = new ArrayList<>();
    for (Tour.Stop s : kept) ds.add(store.delivery(s.deliveryId()).orElseThrow());
    Problem p = problem(t.departAtMs(), List.of(vehicle(t.vehicleId())), ds);
    var f = Evaluation.route(p, p.trucks().get(0), p.jobs());
    List<Tour.Stop> stops = new ArrayList<>();
    for (int i = 0; i < kept.size(); i++) {
      Tour.Stop s = kept.get(i);
      stops.add(new Tour.Stop(i + 1, s.deliveryId(), s.customerId(), f.stops().get(i).startMs(), null));
    }
    return t.withStops(stops, f.meters() / 1000.0, f.cost());
  }

  // ---- driving ---------------------------------------------------------------------------------------------

  /** Leg index of the tour from startMs: 0..30 % longer than OSRM for traffic, and perhaps a breakdown half-way. */
  private VehicleRecord leg(VehicleRecord v, Tour tour, int index, long startMs) {
    int from = index == 0 ? 0 : Roads.place(tour.stops().get(index - 1).customerId()).index();
    int to = index == tour.stops().size() ? 0 : Roads.place(tour.stops().get(index).customerId()).index();
    double traffic = 1.0 + 0.3 * random.nextDouble();
    long end = startMs + Math.round(Roads.seconds(from, to) * 1000.0 * traffic);
    Long breakdown = random.nextDouble() < breakdownPerLeg ? startMs + (end - startMs) / 2 : null;
    return v.withLeg(index, startMs, end, breakdown);
  }

  private void drive(long now) {
    for (VehicleRecord v : store.vehicles()) {
      if (v.status() != VehicleStatus.EN_ROUTE && v.status() != VehicleStatus.RETURNING) continue;
      if (v.breakdownAtMs() != null && now >= v.breakdownAtMs()) {
        work.inNewTransaction(() -> breakDown(vehicle(v.id()), "Panne unterwegs"));
      } else if (now >= v.legEndMs()) {
        work.inNewTransaction(() -> arrive(vehicle(v.id())));
      }
    }
  }

  private void arrive(VehicleRecord v) {
    Tour t = store.tour(v.tourId()).orElseThrow();
    long at = v.legEndMs();
    if (v.leg() == t.stops().size()) {
      moveTour(t.withEnded(at), TourStatus.COMPLETED, "Zurück im Depot");
      moveVehicle(v, v.back(VehicleStatus.AVAILABLE));
      return;
    }
    Tour.Stop s = t.stops().get(v.leg());
    Delivery d = store.delivery(s.deliveryId()).orElseThrow();
    long handover = Math.max(at, d.earliestMs());
    t = store.updateTour(t.withArrival(s.seq(), at));
    long lateMin = Math.max(0, handover - d.latestMs()) / MIN;
    Delivery done = move(d, DeliveryStatus.DELIVERED, lateMin > 0 ? "Zugestellt, " + lateMin + " min zu spät" : "Zugestellt");
    store.updateDelivery(done.withDelivered(handover));
    int next = v.leg() + 1;
    VehicleRecord moved = leg(v, t, next, handover + SERVICE_MS);
    moveVehicle(v, next == t.stops().size() ? moved.withStatus(VehicleStatus.RETURNING) : moved);
  }

  private void breakDown(VehicleRecord v, String reason) {
    long now = time.nowMs();
    moveVehicle(v, v.withBreakdown(now, now + REPAIR_MS));
    Tour running = v.tourId() == null ? null : store.tour(v.tourId()).orElse(null);
    if (running != null && running.status() == TourStatus.EN_ROUTE) {
      moveTour(running.withEnded(now), TourStatus.INTERRUPTED, reason + ": " + v.id());
      for (Tour.Stop s : running.stops()) {
        Delivery d = store.delivery(s.deliveryId()).orElseThrow();
        if (d.status() != DeliveryStatus.IN_TRANSIT) continue; // delivered stays delivered
        Delivery failed = move(d, DeliveryStatus.FAILED,
            reason + ", Zustellversuch " + (d.failures() + 1) + " gescheitert");
        failed = store.updateDelivery(failed.withFailure());
        if (failed.failures() >= MAX_ATTEMPTS) {
          bus.publish(EventTypes.DECISION, SOURCE, Severity.WARNING, d.orderId(), new Payloads.Decision(d.id(),
              "aufgegeben", List.of(), "Nach " + MAX_ATTEMPTS + " Versuchen keine weitere Zustellung"));
        }
      }
    }
    for (Tour t : store.tours()) {
      if (t.vehicleId().equals(v.id()) && t.status() == TourStatus.PLANNED) requestReplan("Ausfall " + v.id());
    }
  }

  private void repairs(long now) {
    for (VehicleRecord v : store.vehicles()) {
      if (v.status() == VehicleStatus.BROKEN_DOWN && v.readyAtMs() <= now) {
        work.inNewTransaction(() -> {
          VehicleRecord current = vehicle(v.id());
          moveVehicle(current, current.back(VehicleStatus.AVAILABLE));
        });
      }
    }
  }

  // ---- commands --------------------------------------------------------------------------------------------

  /** A breakdown now (Scenario Lab, UI); not while the vehicle is being loaded. */
  public void breakDown(String vehicleId) {
    synchronized (lock) {
      VehicleRecord v = vehicle(vehicleId);
      work.inNewTransaction(() -> breakDown(v, "Ausfall ausgelöst"));
    }
  }

  /** A broken-down vehicle is back in service at the depot at once. */
  public void repair(String vehicleId) {
    synchronized (lock) {
      VehicleRecord v = vehicle(vehicleId);
      if (v.status() != VehicleStatus.BROKEN_DOWN) VehicleStatus.RULES.move(v.id(), v.status(), VehicleStatus.AVAILABLE);
      work.inNewTransaction(() -> moveVehicle(v, v.back(VehicleStatus.AVAILABLE)));
    }
  }

  public void setMaintenance(String vehicleId, boolean on) {
    synchronized (lock) {
      VehicleRecord v = vehicle(vehicleId);
      work.inNewTransaction(() -> {
        if (on) {
          moveVehicle(v, v.withStatus(VehicleStatus.MAINTENANCE));
          for (Tour t : store.tours()) {
            if (t.vehicleId().equals(vehicleId) && t.status() == TourStatus.PLANNED) requestReplan("Wartung " + vehicleId);
          }
        } else {
          if (v.status() != VehicleStatus.MAINTENANCE) {
            VehicleStatus.RULES.move(v.id(), v.status(), VehicleStatus.AVAILABLE); // AVAILABLE → AVAILABLE throws
          }
          moveVehicle(v, v.back(VehicleStatus.AVAILABLE));
        }
      });
    }
  }

  /** Plans the next wave again in the next step, if its tours are still PLANNED. */
  public void replan() {
    synchronized (lock) {
      requestReplan("Neuplanung angefordert");
    }
  }

  /** After the server reset its tables: plans, random generator and pending replanning start afresh. */
  public void reset(long seed) {
    synchronized (lock) {
      plans.clear();
      replanNeeded = false;
      replanReason = null;
      random = new SeededRandom(seed).fork("routemind");
    }
  }

  // ---- queries ---------------------------------------------------------------------------------------------

  public List<Delivery> deliveries() {
    synchronized (lock) {
      return store.deliveries();
    }
  }

  public Delivery deliveryOfOrder(String orderId) {
    synchronized (lock) {
      return store.deliveryOfOrder(orderId).orElseThrow(() -> DomainException.notFound("Lieferauftrag zu", orderId));
    }
  }

  public List<Tour> tours() {
    synchronized (lock) {
      return store.tours();
    }
  }

  public List<PlanRecord> plans() {
    synchronized (lock) {
      return new ArrayList<>(plans);
    }
  }

  /** Deliveries whose goods wait at the loading zone or are loaded on a vehicle that has not left. */
  public List<Delivery> loadingZone() {
    synchronized (lock) {
      List<Delivery> out = new ArrayList<>();
      for (Delivery d : store.deliveries()) {
        if (d.status() == DeliveryStatus.READY_TO_LOAD || d.status() == DeliveryStatus.LOADED) out.add(d);
      }
      return out;
    }
  }

  public List<TruckView> trucks() {
    synchronized (lock) {
      long now = time.nowMs();
      List<TruckView> out = new ArrayList<>();
      for (VehicleRecord v : store.vehicles()) {
        String from = Roads.DEPOT;
        String to = Roads.DEPOT;
        double progress = 0;
        boolean onTheRoad = v.status() == VehicleStatus.EN_ROUTE || v.status() == VehicleStatus.RETURNING
            || (v.status() == VehicleStatus.BROKEN_DOWN && v.tourId() != null && v.legEndMs() > 0);
        if (onTheRoad) {
          Tour t = store.tour(v.tourId()).orElseThrow();
          from = v.leg() == 0 ? Roads.DEPOT : t.stops().get(v.leg() - 1).customerId();
          to = v.leg() == t.stops().size() ? Roads.DEPOT : t.stops().get(v.leg()).customerId();
          long at = v.status() == VehicleStatus.BROKEN_DOWN ? v.breakdownAtMs() : now;
          long span = v.legEndMs() - v.legStartMs();
          progress = span <= 0 ? 1 : Math.max(0, Math.min(1, (at - v.legStartMs()) / (double) span));
        }
        out.add(new TruckView(v.id(), RouteMindMasterData.vehicle(v.id()).name(), v.status(), v.tourId(), from, to,
            progress));
      }
      return out;
    }
  }

  public Kpis kpis() {
    synchronized (lock) {
      int active = 0;
      double km = 0;
      double cost = 0;
      for (Tour t : store.tours()) {
        if (t.status() == TourStatus.LOADING || t.status() == TourStatus.EN_ROUTE) active++;
        if (t.status() == TourStatus.EN_ROUTE || t.status() == TourStatus.COMPLETED
            || t.status() == TourStatus.INTERRUPTED) {
          km += t.km();
          cost += t.cost();
        }
      }
      int busy = 0;
      List<VehicleRecord> vehicles = store.vehicles();
      for (VehicleRecord v : vehicles) {
        if (v.status() == VehicleStatus.LOADING || v.status() == VehicleStatus.EN_ROUTE
            || v.status() == VehicleStatus.RETURNING) busy++;
      }
      int delivered = 0;
      int onTime = 0;
      int failed = 0;
      int open = 0;
      long delay = 0;
      for (Delivery d : store.deliveries()) {
        if (d.status() == DeliveryStatus.DELIVERED) {
          delivered++;
          long late = Math.max(0, d.deliveredAtMs() - d.latestMs());
          if (late == 0) onTime++;
          delay += late;
        } else if (d.status() == DeliveryStatus.FAILED) {
          failed++;
        } else if (!d.status().terminal()) {
          open++;
        }
      }
      return new Kpis(active, vehicles.isEmpty() ? 0 : busy / (double) vehicles.size(), delivered, onTime,
          delivered == 0 ? 0 : delay / 60_000.0 / delivered, failed, open, km, cost);
    }
  }

  // ---- state changes with their events ---------------------------------------------------------------------

  private Delivery move(Delivery d, DeliveryStatus to, String reason) {
    DeliveryStatus.RULES.move(d.id(), d.status(), to);
    Delivery saved = store.updateDelivery(d.withStatus(to));
    Severity severity = to == DeliveryStatus.DELIVERED ? Severity.SUCCESS
        : to == DeliveryStatus.FAILED ? Severity.WARNING : Severity.INFO;
    bus.publish(EventTypes.DELIVERY_STATE, SOURCE, severity, d.orderId(),
        new Payloads.DeliveryStateChanged(d.id(), d.status(), to, d.tourId(), reason));
    return saved;
  }

  private Tour moveTour(Tour t, TourStatus to, String reason) {
    TourStatus.RULES.move(t.id(), t.status(), to);
    Tour saved = store.updateTour(t.withStatus(to));
    Severity severity = to == TourStatus.INTERRUPTED ? Severity.WARNING
        : to == TourStatus.COMPLETED ? Severity.SUCCESS : Severity.INFO;
    bus.publish(EventTypes.TOUR_STATE, SOURCE, severity, null, new Payloads.TourStateChanged(t.id(), t.status(), to, reason));
    return saved;
  }

  /** Saves next (derived from current); a changed state is checked against the rules and reported. */
  private VehicleRecord moveVehicle(VehicleRecord current, VehicleRecord next) {
    if (current.status() != next.status()) VehicleStatus.RULES.move(current.id(), current.status(), next.status());
    VehicleRecord saved = store.updateVehicle(next);
    if (current.status() != next.status()) {
      bus.publish(EventTypes.VEHICLE_STATE, SOURCE,
          next.status() == VehicleStatus.BROKEN_DOWN ? Severity.WARNING : Severity.INFO, null,
          new Payloads.VehicleStateChanged(current.id(), current.status(), next.status()));
    }
    return saved;
  }

  private VehicleRecord vehicle(String id) {
    for (VehicleRecord v : store.vehicles()) if (v.id().equals(id)) return v;
    throw DomainException.notFound("Lieferfahrzeug", id);
  }
}
```

`breakDown` on `MAINTENANCE` or `LOADING` throws through `moveVehicle` (not in `VehicleStatus.RULES`); `repair` and the end of maintenance check their start state first, because `AVAILABLE → AVAILABLE` changes nothing that `moveVehicle` would check.

- [ ] **Step 4: Run the tests**

Run: `./mvnw -q -pl RouteMind -am test`
Expected: PASS (RouteMindTest 10 tests plus the earlier ones). Read failures against the timeline in the class comment; `hardRulesHoldOverALongRandomRun` runs 12 simulated hours and takes a few seconds.

- [ ] **Step 5: Commit**

```bash
git add RouteMind
git commit -m "feat(routemind): Lieferaufträge, Lieferwellen, Verladung, Fahrten und Ausfälle"
```

---
### Task 8: Server – Tabellen und JDBC-Speicher von RouteMind

**Files:**
- Modify: `platform/app/pom.xml`
- Create: `platform/app/src/main/resources/db/migration/V6__routemind.sql`
- Create: `platform/app/src/main/java/io/nexus/app/routemind/JdbcRouteStore.java`
- Test: `platform/app/src/test/java/io/nexus/app/routemind/RouteMindTables.java`, `JdbcRouteStoreTest.java`

**Interfaces:**
- Consumes: `RouteStore`, `RouteStoreContract` (Task 5, test-jar), `RouteMindMasterData` (Task 5).
- Produces: Spring component `JdbcRouteStore implements RouteStore`; tables `delivery_vehicle`, `delivery_order`, `tour`, `tour_stop`; `RouteMindTables.SQL_RESET` (statements that bring the tables back to master data, used by the tests and by `PlantReset` in Task 9).

- [ ] **Step 1: Dependencies**

In `platform/app/pom.xml` after the `smartfactory` dependency:

```xml
		<dependency><groupId>io.nexus</groupId><artifactId>routemind</artifactId><version>${project.version}</version></dependency>
```

and after the `smartfactory` test-jar:

```xml
		<dependency><groupId>io.nexus</groupId><artifactId>routemind</artifactId><version>${project.version}</version><type>test-jar</type><scope>test</scope></dependency>
```

- [ ] **Step 2: Migration**

`V6__routemind.sql`:

```sql
-- RouteMind (Spec 15a): delivery vehicles, delivery orders, tours with their stops.
create table delivery_vehicle (
  id              text primary key,
  name            text not null,
  max_kg          int  not null check (max_kg > 0),
  max_pallets     int  not null check (max_pallets > 0),
  status          text not null default 'AVAILABLE' check (status in
                    ('AVAILABLE', 'LOADING', 'EN_ROUTE', 'RETURNING', 'MAINTENANCE', 'BROKEN_DOWN')),
  tour_id         text,
  leg             int    not null default 0,
  leg_start_ms    bigint not null default 0,
  leg_end_ms      bigint not null default 0,
  breakdown_at_ms bigint,
  ready_at_ms     bigint not null default 0,
  version         int    not null default 0
);
insert into delivery_vehicle (id, name, max_kg, max_pallets) values
  ('LKW-1', 'LKW 7,5 t', 3000, 12),
  ('LKW-2', 'LKW 7,5 t', 3000, 12),
  ('LW-1', 'Lieferwagen', 1000, 4),
  ('LW-2', 'Lieferwagen', 1000, 4);

create table delivery_order (
  id                 text   primary key,
  order_id           text   not null unique,
  customer_id        text   not null,
  product_id         text   not null references product,
  quantity           int    not null check (quantity > 0),
  stored_quantity    int    not null default 0 check (stored_quantity >= 0),
  weight_kg          int    not null check (weight_kg >= 0),
  pallets            int    not null check (pallets >= 0),
  priority           text   not null check (priority in ('NORMAL', 'HOCH', 'EILIG')),
  earliest_ms        bigint not null,
  latest_ms          bigint not null,
  status             text   not null check (status in ('CREATED', 'AWAITING_GOODS', 'READY_TO_LOAD', 'LOADED',
                       'IN_TRANSIT', 'DELIVERED', 'FAILED', 'CANCELLED')),
  tour_id            text,
  planned_arrival_ms bigint,
  delivered_at_ms    bigint,
  reservation_id     text,
  transport_id       text,
  failures           int    not null default 0 check (failures >= 0),
  created_at_ms      bigint not null,
  version            int    not null default 0
);

create table tour (
  id            text   primary key,
  plan_id       text   not null,
  vehicle_id    text   not null references delivery_vehicle,
  depart_at_ms  bigint not null,
  status        text   not null check (status in ('PLANNED', 'LOADING', 'EN_ROUTE', 'COMPLETED', 'REPLANNED',
                  'INTERRUPTED')),
  km            double precision not null,
  cost          double precision not null,
  started_at_ms bigint,
  ended_at_ms   bigint,
  version       int    not null default 0
);

create table tour_stop (
  tour_id            text   not null references tour on delete cascade,
  seq                int    not null check (seq > 0),
  delivery_id        text   not null,
  customer_id        text   not null,
  planned_arrival_ms bigint not null,
  arrived_at_ms      bigint,
  primary key (tour_id, seq)
);
```

- [ ] **Step 3: Write the failing tests**

`platform/app/src/test/java/io/nexus/app/routemind/RouteMindTables.java`:

```java
package io.nexus.app.routemind;

import org.springframework.jdbc.core.simple.JdbcClient;

/** RouteMind's tables back to master data: no deliveries, no tours, every vehicle AVAILABLE at the depot. */
public final class RouteMindTables {
  public static final String[] SQL_RESET = {
      "delete from tour_stop",
      "delete from tour",
      "delete from delivery_order",
      "update delivery_vehicle set status = 'AVAILABLE', tour_id = null, leg = 0, leg_start_ms = 0, leg_end_ms = 0, "
          + "breakdown_at_ms = null, ready_at_ms = 0, version = 0"};

  private RouteMindTables() {}

  public static void reset(JdbcClient jdbc) {
    for (String sql : SQL_RESET) jdbc.sql(sql).update();
  }
}
```

`JdbcRouteStoreTest.java`:

```java
package io.nexus.app.routemind;

import static org.assertj.core.api.Assertions.assertThat;

import io.nexus.contracts.event.UnitOfWork;
import io.nexus.routemind.master.RouteMindMasterData;
import io.nexus.routemind.store.RouteStore;
import io.nexus.routemind.store.RouteStoreContract;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("embedded")
class JdbcRouteStoreTest extends RouteStoreContract {
  @Autowired RouteStore store;
  @Autowired UnitOfWork work;
  @Autowired JdbcClient jdbc;

  @BeforeEach
  void startFromMasterData() {
    RouteMindTables.reset(jdbc);
  }

  @Override protected RouteStore store() { return store; }
  @Override protected UnitOfWork work() { return work; }

  @Test
  void vehiclesMatchMasterData() {
    var db = jdbc.sql("select id, name, max_kg, max_pallets from delivery_vehicle order by id collate \"C\"")
        .query((rs, i) -> rs.getString(1) + "|" + rs.getString(2) + "|" + rs.getInt(3) + "|" + rs.getInt(4)).list();
    var core = RouteMindMasterData.vehicles().stream()
        .map(v -> v.id() + "|" + v.name() + "|" + v.maxKg() + "|" + v.maxPallets()).toList();
    assertThat(core).isEqualTo(db);
  }
}
```

Run: `./mvnw -q -pl platform/app -am test -Dtest=JdbcRouteStoreTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: FAIL — no bean of type `RouteStore`.

- [ ] **Step 4: Implement `JdbcRouteStore`**

```java
package io.nexus.app.routemind;

import io.nexus.contracts.Domain.Priority;
import io.nexus.contracts.DomainException;
import io.nexus.contracts.state.DeliveryStatus;
import io.nexus.contracts.state.TourStatus;
import io.nexus.contracts.state.VehicleStatus;
import io.nexus.routemind.store.Delivery;
import io.nexus.routemind.store.RouteStore;
import io.nexus.routemind.store.Tour;
import io.nexus.routemind.store.VehicleRecord;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** RouteStore on PostgreSQL with optimistic locking on version. Stops are rewritten with their tour. */
@Component
class JdbcRouteStore implements RouteStore {
  private static final String DELIVERY = """
      select id, order_id, customer_id, product_id, quantity, stored_quantity, weight_kg, pallets, priority,
             earliest_ms, latest_ms, status, tour_id, planned_arrival_ms, delivered_at_ms, reservation_id,
             transport_id, failures, created_at_ms, version
      from delivery_order
      """;
  private static final String TOUR = """
      select id, plan_id, vehicle_id, depart_at_ms, status, km, cost, started_at_ms, ended_at_ms, version from tour
      """;
  private static final String VEHICLE = """
      select id, status, tour_id, leg, leg_start_ms, leg_end_ms, breakdown_at_ms, ready_at_ms, version
      from delivery_vehicle
      """;

  private final JdbcClient jdbc;

  JdbcRouteStore(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public void insertDelivery(Delivery d) {
    try {
      jdbc.sql("""
          insert into delivery_order (id, order_id, customer_id, product_id, quantity, stored_quantity, weight_kg,
                                      pallets, priority, earliest_ms, latest_ms, status, tour_id, planned_arrival_ms,
                                      delivered_at_ms, reservation_id, transport_id, failures, created_at_ms, version)
          values (:id, :ord, :cust, :prod, :qty, :stored, :kg, :pal, :prio, :earliest, :latest, :status, :tour,
                  :planned, :delivered, :res, :transport, :failures, :created, :version)
          """).params(deliveryParams(d)).param("created", d.createdAtMs()).param("version", d.version()).update();
    } catch (DuplicateKeyException e) {
      throw DomainException.conflict("Lieferauftrag für " + d.orderId() + " existiert bereits");
    }
  }

  @Override
  public Delivery updateDelivery(Delivery d) {
    int rows = jdbc.sql("""
        update delivery_order
           set order_id = :ord, customer_id = :cust, product_id = :prod, quantity = :qty, stored_quantity = :stored,
               weight_kg = :kg, pallets = :pal, priority = :prio, earliest_ms = :earliest, latest_ms = :latest,
               status = :status, tour_id = :tour, planned_arrival_ms = :planned, delivered_at_ms = :delivered,
               reservation_id = :res, transport_id = :transport, failures = :failures, version = version + 1
         where id = :id and version = :version
        """).params(deliveryParams(d)).param("version", d.version()).update();
    if (rows == 0) {
      if (delivery(d.id()).isEmpty()) throw DomainException.notFound("Lieferauftrag", d.id());
      throw DomainException.conflict("Lieferauftrag " + d.id() + " wurde zwischenzeitlich geändert");
    }
    return new Delivery(d.id(), d.orderId(), d.customerId(), d.productId(), d.quantity(), d.storedQuantity(),
        d.weightKg(), d.pallets(), d.priority(), d.earliestMs(), d.latestMs(), d.status(), d.tourId(),
        d.plannedArrivalMs(), d.deliveredAtMs(), d.reservationId(), d.transportId(), d.failures(), d.createdAtMs(),
        d.version() + 1);
  }

  private static Map<String, Object> deliveryParams(Delivery d) {
    Map<String, Object> p = new LinkedHashMap<>();
    p.put("id", d.id());
    p.put("ord", d.orderId());
    p.put("cust", d.customerId());
    p.put("prod", d.productId());
    p.put("qty", d.quantity());
    p.put("stored", d.storedQuantity());
    p.put("kg", d.weightKg());
    p.put("pal", d.pallets());
    p.put("prio", d.priority().name());
    p.put("earliest", d.earliestMs());
    p.put("latest", d.latestMs());
    p.put("status", d.status().name());
    p.put("tour", d.tourId());
    p.put("planned", d.plannedArrivalMs());
    p.put("delivered", d.deliveredAtMs());
    p.put("res", d.reservationId());
    p.put("transport", d.transportId());
    p.put("failures", d.failures());
    return p;
  }

  @Override
  public Optional<Delivery> delivery(String id) {
    return jdbc.sql(DELIVERY + " where id = :id").param("id", id).query((rs, i) -> delivery(rs)).optional();
  }

  @Override
  public Optional<Delivery> deliveryOfOrder(String orderId) {
    return jdbc.sql(DELIVERY + " where order_id = :o").param("o", orderId).query((rs, i) -> delivery(rs)).optional();
  }

  @Override
  public List<Delivery> deliveries() {
    return jdbc.sql(DELIVERY + " order by id collate \"C\"").query((rs, i) -> delivery(rs)).list();
  }

  @Override
  public void insertTour(Tour t) {
    try {
      jdbc.sql("""
          insert into tour (id, plan_id, vehicle_id, depart_at_ms, status, km, cost, started_at_ms, ended_at_ms, version)
          values (:id, :plan, :vehicle, :depart, :status, :km, :cost, :started, :ended, :version)
          """).param("id", t.id()).param("plan", t.planId()).param("vehicle", t.vehicleId())
          .param("depart", t.departAtMs()).param("status", t.status().name()).param("km", t.km())
          .param("cost", t.cost()).param("started", t.startedAtMs()).param("ended", t.endedAtMs())
          .param("version", t.version()).update();
    } catch (DuplicateKeyException e) {
      throw DomainException.conflict("Tour " + t.id() + " existiert bereits");
    }
    insertStops(t);
  }

  @Override
  public Tour updateTour(Tour t) {
    int rows = jdbc.sql("""
        update tour set status = :status, km = :km, cost = :cost, started_at_ms = :started, ended_at_ms = :ended,
                        version = version + 1
         where id = :id and version = :version
        """).param("id", t.id()).param("status", t.status().name()).param("km", t.km()).param("cost", t.cost())
        .param("started", t.startedAtMs()).param("ended", t.endedAtMs()).param("version", t.version()).update();
    if (rows == 0) {
      if (tour(t.id()).isEmpty()) throw DomainException.notFound("Tour", t.id());
      throw DomainException.conflict("Tour " + t.id() + " wurde zwischenzeitlich geändert");
    }
    jdbc.sql("delete from tour_stop where tour_id = :id").param("id", t.id()).update();
    insertStops(t);
    return new Tour(t.id(), t.planId(), t.vehicleId(), t.departAtMs(), t.status(), t.stops(), t.km(), t.cost(),
        t.startedAtMs(), t.endedAtMs(), t.version() + 1);
  }

  private void insertStops(Tour t) {
    for (Tour.Stop s : t.stops()) {
      jdbc.sql("""
          insert into tour_stop (tour_id, seq, delivery_id, customer_id, planned_arrival_ms, arrived_at_ms)
          values (:tour, :seq, :delivery, :customer, :planned, :arrived)
          """).param("tour", t.id()).param("seq", s.seq()).param("delivery", s.deliveryId())
          .param("customer", s.customerId()).param("planned", s.plannedArrivalMs()).param("arrived", s.arrivedAtMs())
          .update();
    }
  }

  @Override
  public Optional<Tour> tour(String id) {
    List<Tour> found = tours(TOUR + " where id = :id", id);
    return found.isEmpty() ? Optional.empty() : Optional.of(found.get(0));
  }

  @Override
  public List<Tour> tours() {
    return tours(TOUR + " order by id collate \"C\"", null);
  }

  private List<Tour> tours(String sql, String id) {
    var query = jdbc.sql(sql);
    if (id != null) query = query.param("id", id);
    record Row(String id, String plan, String vehicle, long depart, TourStatus status, double km, double cost,
               Long started, Long ended, int version) {}
    List<Row> rows = query.query((rs, i) -> new Row(rs.getString("id"), rs.getString("plan_id"),
        rs.getString("vehicle_id"), rs.getLong("depart_at_ms"), TourStatus.valueOf(rs.getString("status")),
        rs.getDouble("km"), rs.getDouble("cost"), rs.getObject("started_at_ms", Long.class),
        rs.getObject("ended_at_ms", Long.class), rs.getInt("version"))).list();
    Map<String, List<Tour.Stop>> stops = new LinkedHashMap<>();
    jdbc.sql("""
        select tour_id, seq, delivery_id, customer_id, planned_arrival_ms, arrived_at_ms from tour_stop
        order by tour_id collate "C", seq
        """).query((ResultSet rs) -> {
          stops.computeIfAbsent(rs.getString("tour_id"), k -> new ArrayList<>()).add(new Tour.Stop(rs.getInt("seq"),
              rs.getString("delivery_id"), rs.getString("customer_id"), rs.getLong("planned_arrival_ms"),
              rs.getObject("arrived_at_ms", Long.class)));
        });
    List<Tour> out = new ArrayList<>();
    for (Row r : rows) {
      out.add(new Tour(r.id(), r.plan(), r.vehicle(), r.depart(), r.status(), stops.getOrDefault(r.id(), List.of()),
          r.km(), r.cost(), r.started(), r.ended(), r.version()));
    }
    return out;
  }

  @Override
  public VehicleRecord updateVehicle(VehicleRecord v) {
    int rows = jdbc.sql("""
        update delivery_vehicle
           set status = :status, tour_id = :tour, leg = :leg, leg_start_ms = :start, leg_end_ms = :end,
               breakdown_at_ms = :breakdown, ready_at_ms = :ready, version = version + 1
         where id = :id and version = :version
        """).param("id", v.id()).param("status", v.status().name()).param("tour", v.tourId()).param("leg", v.leg())
        .param("start", v.legStartMs()).param("end", v.legEndMs()).param("breakdown", v.breakdownAtMs())
        .param("ready", v.readyAtMs()).param("version", v.version()).update();
    if (rows == 0) {
      boolean exists = jdbc.sql("select count(*) from delivery_vehicle where id = :id").param("id", v.id())
          .query(Integer.class).single() > 0;
      if (!exists) throw DomainException.notFound("Lieferfahrzeug", v.id());
      throw DomainException.conflict("Lieferfahrzeug " + v.id() + " wurde zwischenzeitlich geändert");
    }
    return new VehicleRecord(v.id(), v.status(), v.tourId(), v.leg(), v.legStartMs(), v.legEndMs(), v.breakdownAtMs(),
        v.readyAtMs(), v.version() + 1);
  }

  @Override
  public List<VehicleRecord> vehicles() {
    return jdbc.sql(VEHICLE + " order by id collate \"C\"").query((rs, i) -> new VehicleRecord(rs.getString("id"),
        VehicleStatus.valueOf(rs.getString("status")), rs.getString("tour_id"), rs.getInt("leg"),
        rs.getLong("leg_start_ms"), rs.getLong("leg_end_ms"), rs.getObject("breakdown_at_ms", Long.class),
        rs.getLong("ready_at_ms"), rs.getInt("version"))).list();
  }

  private static Delivery delivery(ResultSet rs) throws SQLException {
    return new Delivery(rs.getString("id"), rs.getString("order_id"), rs.getString("customer_id"),
        rs.getString("product_id"), rs.getInt("quantity"), rs.getInt("stored_quantity"), rs.getInt("weight_kg"),
        rs.getInt("pallets"), Priority.valueOf(rs.getString("priority")), rs.getLong("earliest_ms"),
        rs.getLong("latest_ms"), DeliveryStatus.valueOf(rs.getString("status")), rs.getString("tour_id"),
        rs.getObject("planned_arrival_ms", Long.class), rs.getObject("delivered_at_ms", Long.class),
        rs.getString("reservation_id"), rs.getString("transport_id"), rs.getInt("failures"),
        rs.getLong("created_at_ms"), rs.getInt("version"));
  }
}
```

If the PostgreSQL driver rejects a `null` `Long` parameter ("could not determine data type"), pass it as `new SqlParameterValue(Types.BIGINT, value)` for the nullable `bigint` columns (ruling).

- [ ] **Step 5: Run the tests**

Run: `./mvnw -q -pl platform/app -am test -Dtest='JdbcRouteStoreTest,SchemaTest' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS (7 contract tests + `vehiclesMatchMasterData`).

- [ ] **Step 6: Commit**

```bash
git add platform/app
git commit -m "feat(app): Tabellen und JDBC-Speicher für RouteMind mit Vertragstest"
```

---

### Task 9: RouteMind in der Anlage – Schritt, Frame, Abfragen, Befehle, Server

**Files:**
- Modify: `platform/plant/pom.xml`, `platform/plant/src/main/java/io/nexus/plant/Plant.java`, `PlantFrame.java`, `PlantJson.java`, `PlantQueries.java`, `PlantCommands.java`, `PlantRules.java` (Javadoc)
- Delete: `platform/plant/src/main/java/io/nexus/plant/Shipping.java`
- Create: `platform/app/src/main/java/io/nexus/app/routemind/RouteMindConfig.java`
- Modify: `platform/app/src/main/java/io/nexus/app/plant/PlantConfig.java`, `PlantReset.java`
- Modify: `FactoryTwin/src/areas/settings/SettingsArea.tsx:123`
- Test: `platform/plant/src/test/java/io/nexus/plant/PlantEndToEndTest.java`, `PlantQueriesTest.java`, `PlantCommandsTest.java`, `PlantLongRunTest.java`, `platform/app/src/test/java/io/nexus/app/plant/ServerResetTest.java`

**Interfaces:**
- Consumes: `RouteMind` (Task 7), `JdbcRouteStore` (Task 8), `SmartFactory.eta()` (Task 3).
- Produces:
  - `Plant(EventBus, SimTime, long seed, FlowPilot, SmartFactory, RouteMind, MachineGateway, PlantRules)`, `Plant.routeMind()`; phase `RouteMind` after `FlowPilot`, run only while `rules.shipping()`.
  - `PlantFrame` gains `List<RouteMind.TruckView> trucks` and `List<DockView> dock`, `DockView(String deliveryId, String status, int pallets)`; frame JSON fields `trucks` (`id,name,status,tourId,from,to,progress`) and `dock` (`deliveryId,status,pallets`).
  - Query `{"query":"routemind"}` → `{attribution, deliveries[], tours[], vehicles[], plans[], kpis{}}` (fields below).
  - Commands `vehicleBreakdown{vehicleId}`, `vehicleRepair{vehicleId}`, `vehicleMaintenance{vehicleId,on}`, `replanDeliveries{}`.
  - Server bean `RouteMind`; `RouteOptimizer` bean (heuristic here, OR-Tools in Task 10).

- [ ] **Step 1: Write the failing end-to-end test**

Add to `PlantEndToEndTest.java` (imports `io.nexus.contracts.state.DeliveryStatus`, `io.nexus.contracts.event.Payloads`, `io.nexus.contracts.Domain.TransportKind`, `io.nexus.contracts.Place`, `java.util.ArrayList`):

```java
  @Test
  void productionOrderIsDeliveredToTheCustomer() {
    Plant plant = Plant.inMemory(4711, Scenario1h.rules());
    String id = plant.smartFactory().createOrder(new NewOrder("FP12", 4, Priority.HOCH, 60 * 60_000L));
    for (int minute = 0; minute < 8 * 60; minute++) {
      if (plant.routeMind().deliveryOfOrder(id).status() == DeliveryStatus.DELIVERED) break;
      plant.advance(60_000);
    }
    assertThat(plant.routeMind().deliveryOfOrder(id).status()).isEqualTo(DeliveryStatus.DELIVERED);
    List<String> chain = new ArrayList<>();
    for (NexusEvent e : plant.events()) {
      if (id.equals(e.correlationId()) && e.type().equals(EventTypes.DELIVERY_STATE)) {
        chain.add(((Payloads.DeliveryStateChanged) e.payload()).to().name());
      }
    }
    assertThat(chain).containsSubsequence("AWAITING_GOODS", "READY_TO_LOAD", "LOADED", "IN_TRANSIT", "DELIVERED");
    assertThat(plant.events()).anySatisfy(e -> {
      assertThat(e.type()).isEqualTo(EventTypes.TRANSPORT_DELIVERED);
      var d = (Payloads.TransportDelivered) e.payload();
      assertThat(d.kind()).isEqualTo(TransportKind.SHIPMENT);
      assertThat(d.orderId()).isEqualTo(id);
      assertThat(d.to()).isEqualTo(Place.loading("LZ-1"));
    });
  }

  @Test
  void sameSeedSameDeliveryEvents() {
    List<String> a = new ArrayList<>();
    List<String> b = new ArrayList<>();
    for (List<String> out : List.of(a, b)) {
      Plant plant = Plant.inMemory(99, PlantRules.DEFAULT);
      plant.advance(3 * 3_600_000L);
      for (NexusEvent e : plant.events()) {
        if (e.source().equals("routemind")) out.add(PlantJson.event(e, false));
      }
    }
    assertThat(a).isNotEmpty().isEqualTo(b);
  }
```

Run: `./mvnw -q -pl platform/plant -am test -Dtest=PlantEndToEndTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: COMPILATION ERROR, `routeMind()` not found.

- [ ] **Step 2: Plant**

`platform/plant/pom.xml`: add `<dependency><groupId>io.nexus</groupId><artifactId>routemind</artifactId></dependency>` after `smartfactory`.

`Plant.java`:
- Remove the field `shipping` and the phase line `phase("Versand", …)`; delete `Shipping.java`.
- Add the field `private final RouteMind routeMind;`.
- In `inMemory(seed, rules, runId)` after `machines[0] = new LocalMachines(smartFactory);`:

```java
    var routeMind = RouteMind.inMemory(bus, work, time, ids, flowPilot.materials(), flowPilot.transports(),
        smartFactory.eta(), smartFactory.catalog(), seed);
    return new Plant(bus, time, seed, flowPilot, smartFactory, routeMind, machines[0], rules, log);
```

- Both constructors get `RouteMind routeMind` after `SmartFactory smartFactory`; the private one stores it.
- In `step`, after `phase("FlowPilot", …)`:

```java
      phase("RouteMind", () -> {
        if (rules.shipping()) routeMind.step();
      });
```

- In `reset(long seed)` after `scenarios.reset();`: `routeMind.reset(seed);`.
- Accessor `public RouteMind routeMind() { return routeMind; }`.
- Class Javadoc: replace "shipping" in the phase list by "RouteMind (deliveries, while the rule shipping is on)".
- `frame()`: before `return new PlantFrame(…)`:

```java
      List<PlantFrame.DockView> dock = new ArrayList<>();
      for (Delivery d : routeMind.loadingZone()) dock.add(new PlantFrame.DockView(d.id(), d.status().name(), d.pallets()));
      return new PlantFrame(time.nowMs(), machineViews, machines.conveyors(), vehicles, stock, orderViews, transports,
          routeMind.trucks(), dock);
```

`PlantFrame.java`: add `List<RouteMind.TruckView> trucks, List<DockView> dock` as the last components and `public record DockView(String deliveryId, String status, int pallets) {}`. Fix every other `new PlantFrame(` (`grep -rn "new PlantFrame(" --include=*.java .`; at the moment only `Plant.frame`).

`PlantJson.frame`: replace the final `return w.endArray().endObject().toString();` by

```java
    w.endArray().name("trucks").beginArray();
    for (RouteMind.TruckView t : f.trucks()) {
      w.beginObject().name("id").value(t.id()).name("name").value(t.name()).name("status").value(t.status().name())
          .name("tourId").value(t.tourId()).name("from").value(t.from()).name("to").value(t.to())
          .name("progress").value(t.progress()).endObject();
    }
    w.endArray().name("dock").beginArray();
    for (PlantFrame.DockView d : f.dock()) {
      w.beginObject().name("deliveryId").value(d.deliveryId()).name("status").value(d.status())
          .name("pallets").value(d.pallets()).endObject();
    }
    return w.endArray().endObject().toString();
```

`PlantRules.java` Javadoc of `shipping`: `RouteMind delivers finished goods to the customers (Spec 15a); off: goods stay in the finished-goods store`.

- [ ] **Step 3: Query and commands — failing tests first**

Add to `PlantQueriesTest.java`:

```java
  @Test
  @SuppressWarnings("unchecked")
  void routemindListsDeliveriesToursVehiclesPlansAndKpis() {
    Plant p = Plant.inMemory(4711, PlantRules.DEFAULT);
    p.advance(2 * 3_600_000L);
    var answer = (Map<String, Object>) JsonReader.parse(PlantQueries.apply(p, "{\"query\":\"routemind\"}"));
    assertThat(answer).containsEntry("ok", true);
    var r = (Map<String, Object>) answer.get("result");
    assertThat((String) r.get("attribution")).contains("OpenStreetMap");
    assertThat((List<Object>) r.get("vehicles")).hasSize(4);
    assertThat((List<Map<String, Object>>) r.get("deliveries")).isNotEmpty()
        .allSatisfy(d -> assertThat(d).containsKeys("id", "orderId", "customerId", "customerName", "status",
            "earliestMs", "latestMs", "plannedArrivalMs", "deliveredAtMs", "weightKg", "pallets"));
    assertThat((Map<String, Object>) r.get("kpis")).containsKeys("activeTours", "utilization", "delivered", "onTime",
        "avgDelayMinutes", "failed", "open", "km", "cost");
    assertThat((List<Object>) r.get("plans")).isNotEmpty();
  }
```

Add to `PlantCommandsTest.java`:

```java
  @Test
  void vehicleCommandsReachRouteMind() {
    Plant p = Plant.inMemory(7, PlantRules.DEFAULT);
    assertThat(PlantCommands.apply(p, "{\"command\":\"vehicleMaintenance\",\"vehicleId\":\"LW-1\",\"on\":true}"))
        .contains("\"ok\":true");
    assertThat(p.routeMind().trucks().stream().filter(t -> t.id().equals("LW-1")).findFirst().orElseThrow().status())
        .isEqualTo(io.nexus.contracts.state.VehicleStatus.MAINTENANCE);
    assertThat(PlantCommands.apply(p, "{\"command\":\"vehicleBreakdown\",\"vehicleId\":\"LW-1\"}")).contains("409");
    assertThat(PlantCommands.apply(p, "{\"command\":\"vehicleBreakdown\",\"vehicleId\":\"LKW-9\"}")).contains("404");
    assertThat(PlantCommands.apply(p, "{\"command\":\"replanDeliveries\"}")).contains("\"ok\":true");
  }
```

(Use the same `contains("409")` style the other tests in `PlantCommandsTest` use for error answers; if they parse the JSON, do the same.)

Run: `./mvnw -q -pl platform/plant -am test -Dtest='PlantQueriesTest,PlantCommandsTest' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: FAIL — unknown query `routemind`, unknown command `vehicleMaintenance`.

- [ ] **Step 4: Implement query and commands**

`PlantCommands.apply` switch, after `stockReceipt`:

```java
      case "vehicleBreakdown" -> plant.routeMind().breakDown(text(c, "vehicleId"));
      case "vehicleRepair" -> plant.routeMind().repair(text(c, "vehicleId"));
      case "vehicleMaintenance" -> plant.routeMind().setMaintenance(text(c, "vehicleId"), bool(c, "on"));
      case "replanDeliveries" -> plant.routeMind().replan();
```

`PlantQueries.apply` switch: `case "routemind" -> routeMind(plant);` and the method (imports `io.nexus.routemind.RouteMind`, `io.nexus.routemind.master.Roads`, `io.nexus.routemind.master.RouteMindMasterData`, `io.nexus.routemind.store.Delivery`, `io.nexus.routemind.store.Tour`, `io.nexus.contracts.event.Payloads`):

```java
  private static String routeMind(Plant plant) {
    RouteMind rm = plant.routeMind();
    var w = new JsonWriter().beginObject().name("attribution").value(Roads.attribution());
    w.name("deliveries").beginArray();
    for (Delivery d : rm.deliveries()) {
      w.beginObject().name("id").value(d.id()).name("orderId").value(d.orderId()).name("customerId").value(d.customerId())
          .name("customerName").value(Roads.place(d.customerId()).name()).name("productId").value(d.productId())
          .name("quantity").value(d.quantity()).name("storedQuantity").value(d.storedQuantity())
          .name("weightKg").value(d.weightKg()).name("pallets").value(d.pallets())
          .name("priority").value(d.priority().name()).name("earliestMs").value(d.earliestMs())
          .name("latestMs").value(d.latestMs()).name("status").value(d.status().name()).name("tourId").value(d.tourId());
      nullable(w.name("plannedArrivalMs"), d.plannedArrivalMs());
      nullable(w.name("deliveredAtMs"), d.deliveredAtMs());
      w.name("failures").value(d.failures()).endObject();
    }
    w.endArray().name("tours").beginArray();
    for (Tour t : rm.tours()) {
      w.beginObject().name("id").value(t.id()).name("planId").value(t.planId()).name("vehicleId").value(t.vehicleId())
          .name("departAtMs").value(t.departAtMs()).name("status").value(t.status().name()).name("km").value(t.km())
          .name("cost").value(t.cost());
      nullable(w.name("startedAtMs"), t.startedAtMs());
      nullable(w.name("endedAtMs"), t.endedAtMs());
      w.name("stops").beginArray();
      for (Tour.Stop s : t.stops()) {
        w.beginObject().name("seq").value(s.seq()).name("deliveryId").value(s.deliveryId())
            .name("customerId").value(s.customerId()).name("plannedArrivalMs").value(s.plannedArrivalMs());
        nullable(w.name("arrivedAtMs"), s.arrivedAtMs());
        w.endObject();
      }
      w.endArray().endObject();
    }
    w.endArray().name("vehicles").beginArray();
    for (RouteMind.TruckView v : rm.trucks()) {
      var seed = RouteMindMasterData.vehicle(v.id());
      w.beginObject().name("id").value(v.id()).name("name").value(v.name()).name("status").value(v.status().name())
          .name("tourId").value(v.tourId()).name("from").value(v.from()).name("to").value(v.to())
          .name("progress").value(v.progress()).name("maxKg").value(seed.maxKg())
          .name("maxPallets").value(seed.maxPallets()).endObject();
    }
    w.endArray().name("plans").beginArray();
    for (RouteMind.PlanRecord p : rm.plans()) {
      w.beginObject().name("planId").value(p.planId()).name("waveAtMs").value(p.waveAtMs()).name("atMs").value(p.atMs())
          .name("trigger").value(p.trigger()).name("solver").value(p.solver());
      figures(w.name("baseline"), p.baseline());
      figures(w.name("optimized"), p.optimized());
      figures(w.name("replaced"), p.replaced());
      w.name("unassigned").beginArray();
      for (String u : p.unassigned()) w.value(u);
      w.endArray().name("tourIds").beginArray();
      for (String id : p.tourIds()) w.value(id);
      w.endArray().endObject();
    }
    var k = rm.kpis();
    w.endArray().name("kpis").beginObject().name("activeTours").value(k.activeTours())
        .name("utilization").value(k.utilization()).name("delivered").value(k.delivered()).name("onTime").value(k.onTime())
        .name("avgDelayMinutes").value(k.avgDelayMinutes()).name("failed").value(k.failed()).name("open").value(k.open())
        .name("km").value(k.km()).name("cost").value(k.cost()).endObject();
    return w.endObject().toString();
  }

  private static void figures(JsonWriter w, Payloads.PlanFigures f) {
    if (f == null) {
      w.nullValue();
      return;
    }
    PlantJson.figures(w, f);
  }
```

- [ ] **Step 5: Run the plant tests**

Run: `./mvnw -q -pl platform/plant -am test`
Expected: PASS, including the two new end-to-end tests, the query and command tests, `ScenariosTest`, `PlantJsonTest` and `PlantLongRunTest`. A failing `PlantLongRunTest` means RouteMind breaks a plant invariant: find the cause.

- [ ] **Step 6: Long-run check for RouteMind**

In `PlantLongRunTest.fourHoursWithRandomFaultsKeepEveryInvariantAndNoOrderHangs`, after the existing `completed` assertion:

```java
    assertThat(plant.events()).as("no phase failed").noneSatisfy(e -> {
      assertThat(e.type()).isEqualTo(EventTypes.DECISION);
      assertThat(((Payloads.Decision) e.payload()).subject()).isEqualTo("Simulationsschritt");
    });
    assertThat(plant.routeMind().kpis().delivered()).as("deliveries in four hours").isGreaterThanOrEqualTo(5);
```

Run: `./mvnw -q -pl platform/plant -am test -Dtest=PlantLongRunTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS for all four seeds.

- [ ] **Step 7: Server wiring**

`platform/app/src/main/java/io/nexus/app/routemind/RouteMindConfig.java`:

```java
package io.nexus.app.routemind;

import io.nexus.app.event.SimRun;
import io.nexus.contracts.event.EventBus;
import io.nexus.contracts.event.UnitOfWork;
import io.nexus.contracts.port.IdSource;
import io.nexus.contracts.sim.SimTime;
import io.nexus.flowpilot.FlowPilot;
import io.nexus.routemind.RouteMind;
import io.nexus.routemind.optimize.RouteOptimizer;
import io.nexus.routemind.optimize.SavingsHeuristic;
import io.nexus.routemind.store.RouteStore;
import io.nexus.smartfactory.SmartFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class RouteMindConfig {
  @Bean
  RouteMind routeMind(RouteStore store, EventBus bus, UnitOfWork work, SimTime time, IdSource ids, FlowPilot flowPilot,
                      SmartFactory smartFactory, RouteOptimizer optimizer, SimRun run) {
    return new RouteMind(store, bus, work, time, ids, flowPilot.materials(), flowPilot.transports(),
        smartFactory.eta(), smartFactory.catalog(), optimizer, run.seed());
  }

  @Bean
  RouteOptimizer routeOptimizer() {
    return new SavingsHeuristic();
  }
}
```

`PlantConfig.java`: inject `RouteMind routeMind` into the `plant` bean method and pass it: `new Plant(bus, time, run.seed(), flowPilot, smartFactory, routeMind, machines, PlantRules.DEFAULT)`.

`PlantReset.clearMovementTables()`: at the start of the `for` array add the RouteMind statements (they must run before `delete from production_order` only for readability; there is no foreign key between them):

```java
        "delete from tour_stop",
        "delete from tour",
        "delete from delivery_order",
        "update delivery_vehicle set status = 'AVAILABLE', tour_id = null, leg = 0, leg_start_ms = 0, leg_end_ms = 0, "
            + "breakdown_at_ms = null, ready_at_ms = 0, version = 0",
```

(`plant.reset(seed)` resets RouteMind's memory.)

Settings: `FactoryTwin/src/areas/settings/SettingsArea.tsx:123` → `<Toggle label="Auslieferung" hint="RouteMind liefert Fertigware an die Kunden" …>`; update the matching expectation in `rulesForm.test.ts` / Settings tests if one checks the label.

- [ ] **Step 8: Server reset test**

Add to `ServerResetTest.java` (it already has a running plant and `PlantReset reset`; reuse its fields):

```java
  @Test
  void resetClearsRouteMind() {
    plant.advance(2 * 3_600_000L); // orders, putaways, a first wave
    assertThat(routeMind.deliveries()).isNotEmpty();
    reset.reset(4711);
    assertThat(routeMind.deliveries()).isEmpty();
    assertThat(routeMind.tours()).isEmpty();
    assertThat(routeMind.plans()).isEmpty();
    assertThat(routeMind.trucks()).allSatisfy(t -> {
      assertThat(t.status()).isEqualTo(io.nexus.contracts.state.VehicleStatus.AVAILABLE);
      assertThat(t.tourId()).isNull();
    });
  }
```

with `@Autowired RouteMind routeMind;`. If `ServerResetTest` drives the plant through its loop instead of `plant.advance`, do it the way the file does.

Run: `./mvnw -q -pl platform/app -am test -Dtest='ServerResetTest,RuntimeEquivalenceTest,SimApiTest,ContextLoadsTest' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS. `RuntimeEquivalenceTest` compares the server with the browser plant and now covers RouteMind events too (both use the heuristic).

- [ ] **Step 9: Browser runtime**

Run: `cd FactoryTwin && npm run core` then `./mvnw -q -pl platform/web -am verify`
Expected: TeaVM compiles RouteMind; the JVM/Node comparison stays identical. A TeaVM error names the unsupported method: rewrite that call with plain JDK means (Spec 20), do not exclude the module.

- [ ] **Step 10: Commit**

```bash
git add platform FactoryTwin/src/areas/settings
git rm platform/plant/src/main/java/io/nexus/plant/Shipping.java
git commit -m "feat(plant): RouteMind ersetzt den Versand, Frame mit Lieferfahrzeugen und Verladezone"
```

---

### Task 10: OR-Tools auf dem Server

**Files:**
- Modify: `platform/app/pom.xml`
- Create: `platform/app/src/main/java/io/nexus/app/routemind/OrToolsOptimizer.java`
- Modify: `platform/app/src/main/java/io/nexus/app/routemind/RouteMindConfig.java`
- Modify: `platform/app/src/main/resources/application.properties` (or `.yml`, whichever exists)
- Modify: `platform/app/src/test/java/io/nexus/app/plant/RuntimeEquivalenceTest.java`
- Test: `platform/app/src/test/java/io/nexus/app/routemind/OrToolsOptimizerTest.java`

**Interfaces:**
- Consumes: `RouteOptimizer`, `Problem`, `Feasibility`, `Evaluation`, `OptimizerTest.instance/assertValid` (Task 6, test-jar — make `OptimizerTest` and these two methods `public` in this task).
- Produces: `OrToolsOptimizer.NAME = "OR-Tools (Routing)"`; property `nexus.routemind.solver` = `ortools` (default) | `heuristic`.

- [ ] **Step 1: Dependency and API check**

In `platform/app/pom.xml`:

```xml
		<dependency><groupId>com.google.ortools</groupId><artifactId>ortools-java</artifactId><version>9.15.6755</version></dependency>
```

Run: `./mvnw -q -pl platform/app dependency:resolve` then list the routing classes:
`jar tf ~/.m2/repository/com/google/ortools/ortools-java/9.15.6755/ortools-java-9.15.6755.jar | grep -E "routing/(RoutingModel|RoutingIndexManager|Globals|RoutingSearchParameters|FirstSolutionStrategy|LocalSearchMetaheuristic)\.class|constraintsolver/Assignment\.class"`
Expected: classes under `com/google/ortools/routing/` (since OR-Tools 9.12) and `Assignment` under `com/google/ortools/constraintsolver/`. If they are still under `constraintsolver`, use that package for all imports below and `main.defaultRoutingSearchParameters()` instead of `Globals.defaultRoutingSearchParameters()` (ruling).

- [ ] **Step 2: Write the failing test**

Make `RouteMind/src/test/java/io/nexus/routemind/optimize/OptimizerTest.java` public, and `instance(long, int)` and `assertValid(Problem, Solution)` `public static`.

`OrToolsOptimizerTest.java`:

```java
package io.nexus.app.routemind;

import static org.assertj.core.api.Assertions.assertThat;

import io.nexus.routemind.optimize.Evaluation;
import io.nexus.routemind.optimize.OptimizerTest;
import io.nexus.routemind.optimize.Problem;
import io.nexus.routemind.optimize.SavingsHeuristic;
import io.nexus.routemind.optimize.Solution;
import org.junit.jupiter.api.Test;

/** Spec 17: heuristic against OR-Tools on fixed instances. */
class OrToolsOptimizerTest {
  private final OrToolsOptimizer ortools = new OrToolsOptimizer();

  @Test
  void solutionsKeepEveryRule() {
    for (long seed = 1; seed <= 10; seed++) {
      Problem p = OptimizerTest.instance(seed, 5 + (int) (seed % 20));
      Solution s = ortools.solve(p);
      assertThat(s.solver()).isEqualTo(OrToolsOptimizer.NAME);
      OptimizerTest.assertValid(p, s);
    }
  }

  @Test
  void heuristicAndOrToolsAreClose() {
    double ratioSum = 0;
    int compared = 0;
    for (long seed = 1; seed <= 10; seed++) {
      Problem p = OptimizerTest.instance(seed, 12);
      Solution o = ortools.solve(p);
      Solution h = new SavingsHeuristic().solve(p);
      if (o.unassigned().size() != h.unassigned().size()) continue;
      double oc = Evaluation.of(p, o).cost();
      double hc = Evaluation.of(p, h).cost();
      System.out.printf("seed %d: OR-Tools %.2f CHF, Heuristik %.2f CHF%n", seed, oc, hc);
      ratioSum += hc / oc;
      compared++;
    }
    assertThat(compared).isGreaterThanOrEqualTo(8);
    assertThat(ratioSum / compared).as("mean heuristic/OR-Tools cost").isBetween(0.8, 1.25);
  }

  @Test
  void sameProblemSameSolution() {
    Problem p = OptimizerTest.instance(3, 15);
    assertThat(ortools.solve(p)).isEqualTo(ortools.solve(p));
  }
}
```

Run: `./mvnw -q -pl platform/app -am test -Dtest=OrToolsOptimizerTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: COMPILATION ERROR, `OrToolsOptimizer` not found.

- [ ] **Step 3: Implement**

```java
package io.nexus.app.routemind;

import com.google.ortools.Loader;
import com.google.ortools.constraintsolver.Assignment;
import com.google.ortools.routing.FirstSolutionStrategy;
import com.google.ortools.routing.Globals;
import com.google.ortools.routing.LocalSearchMetaheuristic;
import com.google.ortools.routing.RoutingDimension;
import com.google.ortools.routing.RoutingIndexManager;
import com.google.ortools.routing.RoutingModel;
import com.google.ortools.routing.RoutingSearchParameters;
import com.google.protobuf.Duration;
import io.nexus.routemind.optimize.Evaluation;
import io.nexus.routemind.optimize.Feasibility;
import io.nexus.routemind.optimize.Job;
import io.nexus.routemind.optimize.Problem;
import io.nexus.routemind.optimize.Reasons;
import io.nexus.routemind.optimize.Route;
import io.nexus.routemind.optimize.RouteOptimizer;
import io.nexus.routemind.optimize.SavingsHeuristic;
import io.nexus.routemind.optimize.Solution;
import io.nexus.routemind.optimize.Truck;
import io.nexus.routemind.optimize.Unassigned;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * RouteOptimizer on Google OR-Tools (server only, Spec 15a): capacitated vehicle routing with time windows. Weight and
 * pallets are hard capacities, every vehicle is back by the end of its shift, lateness costs as in Evaluation (soft
 * upper bound), time away costs per vehicle (span cost). Each delivery may be dropped at a high penalty, so an
 * overfull wave still gets a plan. Costs in milli-CHF. Greedy descent, time limit as a guard: the same problem gives
 * the same plan.
 */
public final class OrToolsOptimizer implements RouteOptimizer {
  public static final String NAME = "OR-Tools (Routing)";
  private static final long DROP_PENALTY = 1_000_000_000L;
  private static final long LATE_PER_SECOND = Math.round(Evaluation.LATE_COST_PER_MINUTE * 1000 / 60);
  private static final int TIME_LIMIT_SECONDS = 5;

  static {
    Loader.loadNativeLibraries();
  }

  @Override
  public Solution solve(Problem p) {
    var split = Feasibility.split(p);
    List<Unassigned> unassigned = new ArrayList<>(split.unassigned());
    List<Job> jobs = split.plannable();
    if (jobs.isEmpty()) return new Solution(NAME, List.of(), unassigned);
    int n = jobs.size() + 1; // node 0 is the depot
    int[] place = new int[n];
    place[0] = p.depot();
    for (int i = 1; i < n; i++) place[i] = jobs.get(i - 1).place();
    int vehicles = p.trucks().size();
    RoutingIndexManager manager = new RoutingIndexManager(n, vehicles, 0);
    RoutingModel routing = new RoutingModel(manager);

    for (int v = 0; v < vehicles; v++) {
      Truck t = p.trucks().get(v);
      int distance = routing.registerTransitCallback((long from, long to) -> Math.round(
          p.matrix().meters(place[manager.indexToNode(from)], place[manager.indexToNode(to)]) * t.costPerKm()));
      routing.setArcCostEvaluatorOfVehicle(distance, v);
    }
    long[] maxKg = new long[vehicles];
    long[] maxPallets = new long[vehicles];
    long horizon = 0;
    for (int v = 0; v < vehicles; v++) {
      maxKg[v] = p.trucks().get(v).maxKg();
      maxPallets[v] = p.trucks().get(v).maxPallets();
      horizon = Math.max(horizon, (p.trucks().get(v).endMs() - p.departMs()) / 1000);
    }
    int kg = routing.registerUnaryTransitCallback((long i) -> {
      int node = manager.indexToNode(i);
      return node == 0 ? 0 : jobs.get(node - 1).weightKg();
    });
    routing.addDimensionWithVehicleCapacity(kg, 0, maxKg, true, "Gewicht");
    int pallets = routing.registerUnaryTransitCallback((long i) -> {
      int node = manager.indexToNode(i);
      return node == 0 ? 0 : jobs.get(node - 1).pallets();
    });
    routing.addDimensionWithVehicleCapacity(pallets, 0, maxPallets, true, "Paletten");
    int seconds = routing.registerTransitCallback((long from, long to) -> {
      int a = manager.indexToNode(from);
      int b = manager.indexToNode(to);
      long service = a == 0 ? 0 : jobs.get(a - 1).serviceMs() / 1000;
      return service + p.matrix().seconds(place[a], place[b]);
    });
    routing.addDimension(seconds, horizon, horizon, false, "Zeit"); // waiting at a customer is allowed
    RoutingDimension time = routing.getMutableDimension("Zeit");
    for (int v = 0; v < vehicles; v++) {
      Truck t = p.trucks().get(v);
      time.cumulVar(routing.start(v)).setRange(0, 0);
      time.cumulVar(routing.end(v)).setMax((t.endMs() - p.departMs()) / 1000);
      time.setSpanCostCoefficientForVehicle(Math.round(t.costPerHour() * 1000 / 3600), v);
    }
    for (int i = 1; i < n; i++) {
      Job j = jobs.get(i - 1);
      long index = manager.nodeToIndex(i);
      time.cumulVar(index).setMin(Math.max(0, (j.earliestMs() - p.departMs()) / 1000));
      time.setCumulVarSoftUpperBound(index, Math.max(0, (j.latestMs() - p.departMs()) / 1000), LATE_PER_SECOND);
      routing.addDisjunction(new long[] {index}, DROP_PENALTY);
    }

    RoutingSearchParameters params = Globals.defaultRoutingSearchParameters().toBuilder()
        .setFirstSolutionStrategy(FirstSolutionStrategy.Value.PATH_CHEAPEST_ARC)
        .setLocalSearchMetaheuristic(LocalSearchMetaheuristic.Value.GREEDY_DESCENT)
        .setTimeLimit(Duration.newBuilder().setSeconds(TIME_LIMIT_SECONDS).build())
        .build();
    Assignment solution = routing.solveWithParameters(params);
    if (solution == null) {
      Solution h = new SavingsHeuristic().solve(p);
      return new Solution("Heuristik (OR-Tools ohne Lösung)", h.routes(), h.unassigned());
    }
    List<Route> routes = new ArrayList<>();
    Set<String> served = new HashSet<>();
    for (int v = 0; v < vehicles; v++) {
      List<String> ids = new ArrayList<>();
      long index = solution.value(routing.nextVar(routing.start(v)));
      while (!routing.isEnd(index)) {
        String id = jobs.get(manager.indexToNode(index) - 1).id();
        ids.add(id);
        served.add(id);
        index = solution.value(routing.nextVar(index));
      }
      if (!ids.isEmpty()) routes.add(new Route(p.trucks().get(v).id(), ids));
    }
    for (Job j : jobs) if (!served.contains(j.id())) unassigned.add(new Unassigned(j.id(), Reasons.CAPACITY));
    return new Solution(NAME, routes, unassigned);
  }
}
```

`RouteMindConfig.routeOptimizer`:

```java
  @Bean
  RouteOptimizer routeOptimizer(@Value("${nexus.routemind.solver:ortools}") String solver) {
    return switch (solver) {
      case "ortools" -> new OrToolsOptimizer();
      case "heuristic" -> new SavingsHeuristic();
      default -> throw new IllegalArgumentException("nexus.routemind.solver: ortools oder heuristic, nicht " + solver);
    };
  }
```

(import `org.springframework.beans.factory.annotation.Value`). In the application properties add `nexus.routemind.solver=ortools` with a comment `# ortools (Server) or heuristic (same plans as the browser)`.

`RuntimeEquivalenceTest`: it compares the server with the browser plant, so the server must plan like the browser. Add the property to the test's Spring setup — `@SpringBootTest(properties = "nexus.routemind.solver=heuristic")` (merge with properties already listed there).

- [ ] **Step 4: Run the tests**

Run: `./mvnw -q -pl RouteMind,platform/app -am test -Dtest='OrToolsOptimizerTest,RuntimeEquivalenceTest,ContextLoadsTest,OptimizerTest' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS; the printed lines show both costs per instance. If the mean ratio is outside 0.8–1.25, report the figures and find out which solver misbehaves (wrong cost scale in a callback is the usual cause); do not widen the bounds.

- [ ] **Step 5: Commit**

```bash
git add RouteMind/src/test platform/app
git commit -m "feat(app): OR-Tools-Löser für RouteMind auf dem Server"
```

---

### Task 11: Szenarien F bis J

**Files:**
- Modify: `platform/plant/src/main/java/io/nexus/plant/Scenarios.java`, `Plant.java` (constructor passes RouteMind)
- Test: `platform/plant/src/test/java/io/nexus/plant/ScenariosTest.java`
- Modify: `FactoryTwin/e2e/scenarios.spec.ts` only if it asserts the number of scenarios

**Interfaces:**
- Consumes: `RouteMind` queries and commands (Task 7), `Plant.routeMind()` (Task 9).
- Produces: definitions `F`..`J` in `Scenarios.DEFINITIONS`; the `scenarios` query lists ten definitions.

| | Titel | Auslöser | Erwartet | Grenze |
|---|---|---|---|---|
| F | Produktionsverzögerung | Die letzte Maschine eines Auftrags mit geplanter Lieferung steht, bis die Tour abfährt | Die Tour fährt nicht mit der unfertigen Ware: Neuplanung oder Abfahrt ohne sie | 6 h |
| G | Ausfall eines Lieferfahrzeugs | Panne eines beladenen Lieferfahrzeugs unterwegs | Tour unterbrochen, offene Lieferungen in einer neuen Tour geplant | 6 h |
| H | Expressauftrag bis zur Lieferplanung | Eilauftrag | Lieferung in einer der nächsten Wellen geplant | 3 h |
| I | Materialengpass und Liefertermin | Auftrag über Bestand | Auftrag in SHORTAGE, die Lieferung bekommt keinen Termin | 2 h |
| J | Vollständige Lieferkette | Neuer Auftrag | Reservierung, Fertigung, Einlagerung, Verladung, Zustellung als Schrittfolge | 10 h |

- [ ] **Step 1: Write the failing tests**

In `ScenariosTest`, change `listsTheFiveScenarios` to `listsTheTenScenarios` with `.containsExactly("A", "B", "C", "D", "E", "F", "G", "H", "I", "J")`, and add:

```java
  @Test
  void aProductionDelayKeepsTheUnfinishedGoodsOffTheTour() {
    Plant p = Plant.inMemory(7, PlantRules.DEFAULT);
    assertThat(start(p, "F")).containsEntry("ok", true);
    var run = untilDone(p, 6 * 60 * MIN);
    assertThat(run).containsEntry("state", "PASSED");
    assertThat(texts(run)).anyMatch(t -> t.contains("angehalten"));
    assertThat(p.machines().views()).noneSatisfy(m ->
        assertThat(m.status()).isEqualTo(io.nexus.contracts.state.MachineStatus.STOPPED)); // restarted at the end
  }

  @Test
  void aVehicleBreakdownLeadsToANewTour() {
    Plant p = Plant.inMemory(7, PlantRules.DEFAULT);
    assertThat(start(p, "G")).containsEntry("ok", true);
    var run = untilDone(p, 6 * 60 * MIN);
    assertThat(run).containsEntry("state", "PASSED");
    assertThat(texts(run)).anyMatch(t -> t.contains("Panne"));
  }

  @Test
  void anExpressOrderGetsADeliveryPlan() {
    Plant p = Plant.inMemory(7, PlantRules.DEFAULT);
    assertThat(start(p, "H")).containsEntry("ok", true);
    var run = untilDone(p, 3 * 60 * MIN);
    assertThat(run).containsEntry("state", "PASSED");
    assertThat(texts(run)).anyMatch(t -> t.contains("Tour"));
  }

  @Test
  void aShortageLeavesTheDeliveryWithoutDate() {
    Plant p = Plant.inMemory(7, PlantRules.DEFAULT);
    assertThat(start(p, "I")).containsEntry("ok", true);
    var run = untilDone(p, 2 * 60 * MIN);
    assertThat(run).containsEntry("state", "PASSED");
  }

  @Test
  void theWholeSupplyChainEndsWithTheDelivery() {
    Plant p = Plant.inMemory(7, PlantRules.DEFAULT);
    assertThat(start(p, "J")).containsEntry("ok", true);
    var run = untilDone(p, 10 * 60 * MIN);
    assertThat(run).containsEntry("state", "PASSED");
    assertThat(texts(run)).anyMatch(t -> t.contains("eingelagert")).anyMatch(t -> t.contains("verladen"))
        .anyMatch(t -> t.contains("zugestellt"));
  }
```

Run: `./mvnw -q -pl platform/plant -am test -Dtest=ScenariosTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: FAIL — `listsTheTenScenarios` sees five, `F` is not found (404).

- [ ] **Step 2: Implement**

`Plant` constructor: `new Scenarios(smartFactory, flowPilot, routeMind, bus)`. In `Scenarios`: field `private final RouteMind rm;`, constructor parameter after `fp`; imports `io.nexus.routemind.RouteMind`, `io.nexus.routemind.store.Delivery`, `io.nexus.routemind.store.Tour`, `io.nexus.contracts.state.DeliveryStatus`, `io.nexus.contracts.state.TourStatus`, `io.nexus.contracts.state.VehicleStatus`.

Append to `DEFINITIONS`:

```java
      new Definition("F", "Produktionsverzögerung",
          "Die letzte Maschine eines Auftrags mit geplanter Lieferung steht, bis die Tour abfährt",
          "Die Tour fährt nicht mit der unfertigen Ware: Neuplanung oder Abfahrt ohne sie", 6 * 60 * MIN),
      new Definition("G", "Ausfall eines Lieferfahrzeugs", "Panne eines beladenen Lieferfahrzeugs unterwegs",
          "Tour unterbrochen, offene Lieferungen in einer neuen Tour geplant", 6 * 60 * MIN),
      new Definition("H", "Expressauftrag bis zur Lieferplanung", "Eilauftrag",
          "Lieferung in einer der nächsten Wellen geplant", 3 * 60 * MIN),
      new Definition("I", "Materialengpass und Liefertermin", "Auftrag über Bestand",
          "Auftrag in SHORTAGE, die Lieferung bekommt keinen Termin", 2 * 60 * MIN),
      new Definition("J", "Vollständige Lieferkette", "Neuer Auftrag",
          "Reservierung, Fertigung, Einlagerung, Verladung, Zustellung als Schrittfolge", 10 * 60 * MIN)
```

In `start`: `case "F" -> new ProductionDelay(); case "G" -> new VehicleBreakdown(); case "H" -> new ExpressDelivery(); case "I" -> new ShortageDelivery(); case "J" -> new SupplyChain();`.

`Run` gets a hook `void end() {}` and `finish(...)` calls `run.end();` as its first statement, so a scenario can undo its intervention also when it times out.

The five runs (inner classes next to `FullRun`):

```java
  /** F: an order whose delivery is already planned; its last machine is stopped until that tour leaves. */
  final class ProductionDelay extends Run {
    private String tourId;
    private String machineId;

    @Override
    void begin(long now) {
      String product = SmartFactoryMasterData.products().get(0).id();
      String id = sf.createOrder(new NewOrder(product, 2, Priority.HOCH, 90 * MIN));
      started(this, now, id, "Auftrag " + id + " angelegt, wartet auf seine Lieferplanung");
    }

    @Override
    void check(long now) {
      Delivery d = rm.deliveryOfOrder(orderId);
      if (tourId == null) {
        if (d.tourId() == null || d.status() != DeliveryStatus.AWAITING_GOODS) return;
        List<Operation> ops = sf.operations(orderId);
        Operation last = ops.get(ops.size() - 1);
        if (last.machineId() == null || last.status() == Operation.Status.DONE) return;
        tourId = d.tourId();
        machineId = last.machineId();
        sf.stopMachine(machineId);
        note(this, now, "Lieferung " + d.id() + " in Tour " + tourId + " geplant; " + machineId + " angehalten");
        return;
      }
      Tour t = tour(tourId);
      if (t.status() == TourStatus.PLANNED || t.status() == TourStatus.LOADING) return;
      boolean onBoard = tourId.equals(d.tourId()) && d.status() != DeliveryStatus.AWAITING_GOODS;
      if (onBoard) finish(this, now, false, "Tour " + tourId + " fuhr mit Lieferung " + d.id() + " ab");
      else if (t.status() == TourStatus.REPLANNED) finish(this, now, true, "Tour " + tourId + " neu geplant");
      else finish(this, now, true, "Tour " + tourId + " fuhr ohne die unfertige Lieferung " + d.id() + " ab");
    }

    @Override
    void end() {
      if (machineId != null) sf.startMachine(machineId);
    }

    @Override
    String timeoutReason() {
      return tourId == null ? "Lieferung von " + orderId + " wurde nie vor der Fertigstellung geplant"
          : "Tour " + tourId + " fuhr nicht ab";
    }
  }

  /** G: the first loaded vehicle under way breaks down; its open deliveries must get a new tour. */
  final class VehicleBreakdown extends Run {
    private String tourId;
    private final List<String> open = new ArrayList<>();

    @Override
    void begin(long now) {
      // waits in check() for a vehicle under way
    }

    @Override
    void check(long now) {
      if (tourId == null) {
        for (RouteMind.TruckView v : rm.trucks()) {
          if (v.status() != VehicleStatus.EN_ROUTE) continue;
          Tour t = tour(v.tourId());
          for (Tour.Stop s : t.stops()) {
            Delivery d = delivery(s.deliveryId());
            if (d.status() == DeliveryStatus.IN_TRANSIT) open.add(d.id());
          }
          if (open.isEmpty()) continue;
          tourId = t.id();
          rm.breakDown(v.id());
          started(this, now, delivery(open.get(0)).orderId(), "Panne an " + v.id() + " auf Tour " + tourId + ", "
              + open.size() + " Lieferungen offen");
          return;
        }
        return;
      }
      for (String id : open) {
        Delivery d = delivery(id);
        boolean replanned = d.status() == DeliveryStatus.DELIVERED
            || (d.tourId() != null && !d.tourId().equals(tourId));
        if (!replanned) return;
      }
      finish(this, now, true, "Offene Lieferungen in neuen Touren: " + String.join(", ", open));
    }

    @Override
    String timeoutReason() {
      return tourId == null ? "Kein Lieferfahrzeug war unterwegs; läuft die Simulation mit Aufträgen?"
          : "Lieferungen von Tour " + tourId + " wurden nicht neu geplant";
    }
  }

  /** H: an urgent order; passes once its delivery is on a tour. */
  final class ExpressDelivery extends Run {
    @Override
    void begin(long now) {
      String product = SmartFactoryMasterData.products().get(0).id();
      String id = sf.createOrder(new NewOrder(product, 2, Priority.EILIG, 45 * MIN));
      started(this, now, id, "Eilauftrag " + id + " angelegt");
    }

    @Override
    void check(long now) {
      Delivery d = rm.deliveryOfOrder(orderId);
      if (d.tourId() == null) return;
      Tour t = tour(d.tourId());
      finish(this, now, true, "Lieferung " + d.id() + " in Tour " + t.id() + ", Abfahrt " + (t.departAtMs() / MIN)
          + " min, Ankunft geplant " + (d.plannedArrivalMs() / MIN) + " min");
    }

    @Override
    String timeoutReason() {
      return "Lieferung von " + orderId + " wurde nicht eingeplant";
    }
  }

  /** I: the order of C waits in SHORTAGE; its delivery gets no tour through the next wave. */
  final class ShortageDelivery extends Run {
    private Long shortageAt;

    @Override
    void begin(long now) {
      lowerStockAndOrder(this, now); // the intervention of C, reported under this run
    }

    @Override
    void check(long now) {
      if (shortageAt == null) {
        if (sf.order(orderId).status() != OrderStatus.SHORTAGE) return;
        shortageAt = now;
        note(this, now, "Auftrag in SHORTAGE");
        return;
      }
      long nextWave = (shortageAt / RouteMind.WAVE_MS + 1) * RouteMind.WAVE_MS;
      if (now < nextWave + CHECK_EVERY_MS) return;
      Delivery d = rm.deliveryOfOrder(orderId);
      if (d.tourId() == null) {
        finish(this, now, true, "Lieferung " + d.id() + " ohne Termin, sie wartet auf den Wareneingang");
      } else {
        finish(this, now, false, "Lieferung " + d.id() + " wurde trotz Engpass geplant");
      }
    }

    @Override
    String timeoutReason() {
      return "Auftrag " + orderId + " kam nicht in SHORTAGE";
    }
  }

  /** J: the whole chain of one order up to the customer. */
  final class SupplyChain extends Run {
    private DeliveryStatus last;

    @Override
    void begin(long now) {
      String product = SmartFactoryMasterData.products().get(0).id();
      String id = sf.createOrder(new NewOrder(product, 2, Priority.HOCH, 90 * MIN));
      started(this, now, id, "Auftrag " + id + " angelegt");
    }

    @Override
    void check(long now) {
      Delivery d = rm.deliveryOfOrder(orderId);
      if (d.status() != last) {
        last = d.status();
        String text = switch (d.status()) {
          case CREATED -> null;
          case AWAITING_GOODS -> "Auftrag freigegeben, Lieferung " + d.id() + " für " + d.customerId();
          case READY_TO_LOAD -> "Ware eingelagert und an der Verladezone";
          case LOADED -> "Ware verladen";
          case IN_TRANSIT -> "Tour " + d.tourId() + " abgefahren";
          case DELIVERED -> null;
          case FAILED -> "Zustellung gescheitert, neuer Versuch";
          case CANCELLED -> null;
        };
        if (text != null) note(this, now, text);
      }
      if (d.status() == DeliveryStatus.DELIVERED) {
        finish(this, now, true, "Ware zugestellt bei " + d.customerId());
      } else if (d.status() == DeliveryStatus.CANCELLED) {
        finish(this, now, false, "Lieferung storniert");
      }
    }

    @Override
    String timeoutReason() {
      return "Lieferung von " + orderId + " steht bei " + last;
    }
  }

  private Tour tour(String id) {
    for (Tour t : rm.tours()) if (t.id().equals(id)) return t;
    throw DomainException.notFound("Tour", id);
  }

  private Delivery delivery(String id) {
    for (Delivery d : rm.deliveries()) if (d.id().equals(id)) return d;
    throw DomainException.notFound("Lieferauftrag", id);
  }
```

Move the body of `Shortage.begin` unchanged into a method of `Scenarios`, `private void lowerStockAndOrder(Run run, long now)`, with `started(this, …)` becoming `started(run, …)` and the field `material` becoming a local variable whose name goes into the started text (it already does). `Shortage.begin` then is `lowerStockAndOrder(this, now);`, and `Shortage.check` reads the material from its started text no longer: change its pass text to `"Keine Reservierung, Auftrag wartet auf den Wareneingang"` and update the C assertion in `ScenariosTest` if it checks the material name. The J text "eingelagert" is checked by the test; keep the wording.

- [ ] **Step 3: Run the tests**

Run: `./mvnw -q -pl platform/plant -am test -Dtest=ScenariosTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS (old and new scenario tests). If F fails with "fuhr mit Lieferung … ab" for seed 7, the stopped machine is not the order's bottleneck: debug with the run's steps, do not weaken the criterion.

- [ ] **Step 4: Frontend list**

Run: `grep -n "'E'\|\"E\"\|toHaveCount(5)\|length).toBe(5)" FactoryTwin/e2e/*.ts FactoryTwin/src/areas/scenarios/*.ts`
If a test pins five scenarios, change it to ten. The Scenario Lab renders the definitions from the query, so no UI code changes.

- [ ] **Step 5: Commit**

```bash
git add platform/plant FactoryTwin/e2e FactoryTwin/src/areas/scenarios
git commit -m "feat(plant): Szenarien F bis J für die Lieferkette"
```

---
### Task 12: Oberfläche – Daten, Zustände, Strassengeometrie

**Files:**
- Modify: `FactoryTwin/package.json` (`maplibre-gl` exakt `6.18.0`)
- Modify: `FactoryTwin/src/nexus/types.ts`
- Modify: `FactoryTwin/src/ui/format.ts`
- Create: `FactoryTwin/src/areas/routemind/roads.ts`
- Create: `FactoryTwin/src/areas/routemind/routemindView.ts`
- Test: `FactoryTwin/src/areas/routemind/roads.test.ts`, `FactoryTwin/src/areas/routemind/routemindView.test.ts`

**Interfaces:**
- Consumes: frame fields `trucks`, `dock` and query `routemind` (Task 9), `roads.json` (Task 4).
- Produces:
  - Types `FrameTruck {id,name,status,tourId,from,to,progress}`, `FrameDock {deliveryId,status,pallets}`, `Frame.trucks`, `Frame.dock`; `RouteMindData {attribution, deliveries: RmDelivery[], tours: RmTour[], vehicles: RmVehicle[], plans: RmPlan[], kpis: RmKpis}` with the fields of the Task 9 query.
  - `DELIVERY_STATE`, `TOUR_STATE`, `VEHICLE_STATE: StateMap`.
  - `roads.ts`: `Roads`, `RoadPlace`, `LngLat`, `loadRoads()`, `decodePolyline(text)`, `legPath(roads, from, to)`, `pointAlong(path, t)`.
  - `routemindView.ts`: `TOUR_COLORS`, `tourColor(tours, tourId)`, `shownTours(tours)`, `routesGeoJson(roads, tours, selected)`, `placesGeoJson(roads, deliveries)`, `trucksGeoJson(roads, trucks)`, `delayMinutes(d)`, `planDelta(plan)`.

- [ ] **Step 1: Dependency**

Run: `cd FactoryTwin && npm install --save-exact maplibre-gl@6.18.0`
Expected: `package.json` lists `"maplibre-gl": "6.18.0"`; `npm ci --ignore-scripts` still works (CI).

- [ ] **Step 2: Types and state maps**

`types.ts` — after `FrameTransport`:

```ts
export type DeliveryState = 'CREATED' | 'AWAITING_GOODS' | 'READY_TO_LOAD' | 'LOADED' | 'IN_TRANSIT' | 'DELIVERED' | 'FAILED' | 'CANCELLED';
export type TourState = 'PLANNED' | 'LOADING' | 'EN_ROUTE' | 'COMPLETED' | 'REPLANNED' | 'INTERRUPTED';
export type VehicleState = 'AVAILABLE' | 'LOADING' | 'EN_ROUTE' | 'RETURNING' | 'MAINTENANCE' | 'BROKEN_DOWN';

/** A delivery vehicle: on the road from `from` to `to` (place ids), progress 0..1 along that leg. */
export interface FrameTruck {
  id: string;
  name: string;
  status: VehicleState;
  tourId: string | null;
  from: string;
  to: string;
  progress: number;
}

/** Goods of a delivery waiting at the loading zone or loaded on a vehicle that has not left. */
export interface FrameDock {
  deliveryId: string;
  status: DeliveryState;
  pallets: number;
}

export interface RmDelivery {
  id: string;
  orderId: string;
  customerId: string;
  customerName: string;
  productId: string;
  quantity: number;
  storedQuantity: number;
  weightKg: number;
  pallets: number;
  priority: 'NORMAL' | 'HOCH' | 'EILIG';
  earliestMs: number;
  latestMs: number;
  status: DeliveryState;
  tourId: string | null;
  plannedArrivalMs: number | null;
  deliveredAtMs: number | null;
  failures: number;
}

export interface RmStop {
  seq: number;
  deliveryId: string;
  customerId: string;
  plannedArrivalMs: number;
  arrivedAtMs: number | null;
}

export interface RmTour {
  id: string;
  planId: string;
  vehicleId: string;
  departAtMs: number;
  status: TourState;
  km: number;
  cost: number;
  startedAtMs: number | null;
  endedAtMs: number | null;
  stops: RmStop[];
}

export interface RmVehicle extends FrameTruck {
  maxKg: number;
  maxPallets: number;
}

export interface RmFigures {
  km: number;
  driveMinutes: number;
  cost: number;
  lateMinutes: number;
  tours: number;
}

export interface RmPlan {
  planId: string;
  waveAtMs: number;
  atMs: number;
  trigger: string;
  solver: string;
  baseline: RmFigures;
  optimized: RmFigures;
  replaced: RmFigures | null;
  unassigned: string[];
  tourIds: string[];
}

export interface RmKpis {
  activeTours: number;
  utilization: number;
  delivered: number;
  onTime: number;
  avgDelayMinutes: number;
  failed: number;
  open: number;
  km: number;
  cost: number;
}

export interface RouteMindData {
  attribution: string;
  deliveries: RmDelivery[];
  tours: RmTour[];
  vehicles: RmVehicle[];
  plans: RmPlan[];
  kpis: RmKpis;
}
```

and in `Frame` after `transports`: `trucks: FrameTruck[];` and `dock: FrameDock[];`. Fix every test fixture that builds a `Frame` literal (`npm run typecheck` lists them): give them `trucks: [], dock: []`.

`format.ts` — after `TRANSPORT_STATE`:

```ts
export const DELIVERY_STATE: StateMap = {
  CREATED: { text: 'Angelegt', color: STATE_HEX.idle },
  AWAITING_GOODS: { text: 'Wartet auf Ware', color: STATE_HEX.wait },
  READY_TO_LOAD: { text: 'Bereit zur Verladung', color: STATE_HEX.setup },
  LOADED: { text: 'Verladen', color: STATE_HEX.setup },
  IN_TRANSIT: { text: 'Unterwegs', color: STATE_HEX.run },
  DELIVERED: { text: 'Zugestellt', color: STATE_HEX.idle },
  FAILED: { text: 'Gescheitert', color: STATE_HEX.fault },
  CANCELLED: { text: 'Storniert', color: STATE_HEX.unknown },
};

export const TOUR_STATE: StateMap = {
  PLANNED: { text: 'Geplant', color: STATE_HEX.idle },
  LOADING: { text: 'Verladung', color: STATE_HEX.setup },
  EN_ROUTE: { text: 'Unterwegs', color: STATE_HEX.run },
  COMPLETED: { text: 'Abgeschlossen', color: STATE_HEX.idle },
  REPLANNED: { text: 'Ersetzt', color: STATE_HEX.unknown },
  INTERRUPTED: { text: 'Unterbrochen', color: STATE_HEX.fault },
};

export const VEHICLE_STATE: StateMap = {
  AVAILABLE: { text: 'Bereit', color: STATE_HEX.idle },
  LOADING: { text: 'Verladung', color: STATE_HEX.setup },
  EN_ROUTE: { text: 'Unterwegs', color: STATE_HEX.run },
  RETURNING: { text: 'Rückfahrt', color: STATE_HEX.run },
  MAINTENANCE: { text: 'Wartung', color: STATE_HEX.maint },
  BROKEN_DOWN: { text: 'Panne', color: STATE_HEX.fault },
};
```

- [ ] **Step 3: Write the failing tests**

`roads.test.ts`:

```ts
import { describe, expect, it } from 'vitest';
import roadsFile from '../../../public/routemind/roads.json';
import { decodePolyline, legPath, pointAlong, type Roads } from './roads';

const roads = roadsFile as Roads;

describe('roads.json', () => {
  it('names its source and has a road geometry for every pair of places', () => {
    expect(roads.attribution).toContain('OpenStreetMap');
    expect(roads.places[0].id).toBe('D0');
    expect(roads.places).toHaveLength(31);
    for (let i = 0; i < roads.places.length; i++) {
      for (let j = i + 1; j < roads.places.length; j++) {
        const key = `${roads.places[i].id}>${roads.places[j].id}`;
        expect(decodePolyline(roads.geometry[key]).length, key).toBeGreaterThanOrEqual(2);
      }
    }
  });
});

describe('geometry', () => {
  it('decodes Google polylines as [lon, lat]', () => {
    // the example of the format description: (38.5, -120.2), (40.7, -120.95), (43.252, -126.453)
    expect(decodePolyline('_p~iF~ps|U_ulLnnqC_mqNvxq`@')).toEqual([
      [-120.2, 38.5],
      [-120.95, 40.7],
      [-126.453, 43.252],
    ]);
  });

  it('runs a leg in the asked direction', () => {
    const there = legPath(roads, 'D0', 'K24');
    const back = legPath(roads, 'K24', 'D0');
    expect(back).toEqual([...there].reverse());
    expect(legPath(roads, 'D0', 'D0')).toHaveLength(1);
  });

  it('finds the point at a share of the length', () => {
    const path: [number, number][] = [
      [8, 47],
      [8, 47.1],
      [8, 47.3],
    ];
    expect(pointAlong(path, 0)).toEqual([8, 47]);
    expect(pointAlong(path, 1)).toEqual([8, 47.3]);
    const mid = pointAlong(path, 0.5);
    expect(mid[0]).toBe(8);
    expect(mid[1]).toBeCloseTo(47.15, 6);
  });
});
```

`routemindView.test.ts`:

```ts
import { describe, expect, it } from 'vitest';
import roadsFile from '../../../public/routemind/roads.json';
import type { FrameTruck, RmDelivery, RmPlan, RmTour } from '../../nexus/types';
import type { Roads } from './roads';
import { delayMinutes, planDelta, placesGeoJson, routesGeoJson, shownTours, tourColor, trucksGeoJson } from './routemindView';

const roads = roadsFile as Roads;
const MIN = 60_000;

const tour = (id: string, status: RmTour['status'], stops: string[]): RmTour => ({
  id,
  planId: 'RP-0001',
  vehicleId: 'LKW-1',
  departAtMs: 60 * MIN,
  status,
  km: 40,
  cost: 120,
  startedAtMs: null,
  endedAtMs: null,
  stops: stops.map((c, i) => ({ seq: i + 1, deliveryId: `DL-000${i + 1}`, customerId: c, plannedArrivalMs: (70 + i * 20) * MIN, arrivedAtMs: null })),
});

const delivery = (over: Partial<RmDelivery>): RmDelivery => ({
  id: 'DL-0001',
  orderId: 'PO-0001',
  customerId: 'K01',
  customerName: 'Kunde Altstetten',
  productId: 'GH200',
  quantity: 4,
  storedQuantity: 4,
  weightKg: 72,
  pallets: 1,
  priority: 'NORMAL',
  earliestMs: 30 * MIN,
  latestMs: 270 * MIN,
  status: 'DELIVERED',
  tourId: 'TR-0001',
  plannedArrivalMs: 100 * MIN,
  deliveredAtMs: 112 * MIN,
  failures: 0,
  ...over,
});

describe('RouteMind view', () => {
  it('shows planned, loading and running tours only', () => {
    const tours = [tour('TR-0001', 'COMPLETED', ['K01']), tour('TR-0002', 'EN_ROUTE', ['K05']), tour('TR-0003', 'REPLANNED', ['K06']), tour('TR-0004', 'PLANNED', ['K07'])];
    expect(shownTours(tours).map((t) => t.id)).toEqual(['TR-0002', 'TR-0004']);
  });

  it('gives every shown tour its own colour, the same on every call', () => {
    const tours = [tour('TR-0002', 'EN_ROUTE', ['K05']), tour('TR-0004', 'PLANNED', ['K07'])];
    expect(tourColor(tours, 'TR-0002')).not.toBe(tourColor(tours, 'TR-0004'));
    expect(tourColor(tours, 'TR-0002')).toBe(tourColor(tours, 'TR-0002'));
  });

  it('draws a tour along the roads from the depot over its stops and back', () => {
    const fc = routesGeoJson(roads, [tour('TR-0002', 'EN_ROUTE', ['K05', 'K06'])], null);
    expect(fc.features).toHaveLength(1);
    const line = fc.features[0].geometry.coordinates;
    const d0 = roads.places[0];
    expect(line[0]).toEqual([d0.lon, d0.lat]);
    expect(line[line.length - 1]).toEqual([d0.lon, d0.lat]);
    expect(line.length).toBeGreaterThan(6); // road shape, not three straight lines
    expect(fc.features[0].properties).toMatchObject({ tourId: 'TR-0002', selected: false, dim: false });
    expect(routesGeoJson(roads, [tour('TR-0002', 'EN_ROUTE', ['K05'])], 'TR-0009').features[0].properties.dim).toBe(true);
  });

  it('puts a truck on the road between two places', () => {
    const t: FrameTruck = { id: 'LKW-1', name: 'LKW', status: 'EN_ROUTE', tourId: 'TR-0002', from: 'D0', to: 'K24', progress: 0.5 };
    const [lon, lat] = trucksGeoJson(roads, [t]).features[0].geometry.coordinates;
    const a = roads.places.find((p) => p.id === 'D0')!;
    const b = roads.places.find((p) => p.id === 'K24')!;
    expect(lon).toBeGreaterThan(Math.min(a.lon, b.lon));
    expect(lon).toBeLessThan(Math.max(a.lon, b.lon));
    expect(lat).toBeGreaterThan(Math.min(a.lat, b.lat) - 0.05);
  });

  it('marks customers with an open delivery', () => {
    const fc = placesGeoJson(roads, [delivery({ status: 'IN_TRANSIT', customerId: 'K05' })]);
    expect(fc.features.find((f) => f.properties.id === 'K05')!.properties.open).toBe(true);
    expect(fc.features.find((f) => f.properties.id === 'D0')!.properties.depot).toBe(true);
  });

  it('measures lateness against the window and the change of a replanning', () => {
    expect(delayMinutes(delivery({ deliveredAtMs: 280 * MIN }))).toBe(10);
    expect(delayMinutes(delivery({}))).toBe(0);
    expect(delayMinutes(delivery({ deliveredAtMs: null }))).toBeNull();
    const plan = { replaced: { km: 50, driveMinutes: 60, cost: 200, lateMinutes: 0, tours: 2 }, optimized: { km: 40, driveMinutes: 55, cost: 180, lateMinutes: 5, tours: 2 } } as RmPlan;
    expect(planDelta(plan)).toEqual({ km: -10, cost: -20, lateMinutes: 5 });
    expect(planDelta({ ...plan, replaced: null })).toBeNull();
  });
});
```

Run: `cd FactoryTwin && npx vitest run src/areas/routemind`
Expected: FAIL — `./roads` and `./routemindView` not found.

- [ ] **Step 4: Implement `roads.ts`**

```ts
/** RouteMind's fixed road network (Spec 15a): places and OSRM road shapes from public/routemind/roads.json. */
export interface RoadPlace {
  id: string;
  name: string;
  lat: number;
  lon: number;
}

export interface Roads {
  attribution: string;
  generated: string;
  places: RoadPlace[];
  /** Google polyline (precision 5) per pair "a>b", a before b in place order. */
  geometry: Record<string, string>;
}

export type LngLat = [number, number];

export async function loadRoads(): Promise<Roads> {
  const res = await fetch(`${import.meta.env.BASE_URL}routemind/roads.json`);
  if (!res.ok) throw new Error(`Strassendaten fehlen (HTTP ${res.status})`);
  return (await res.json()) as Roads;
}

export function decodePolyline(text: string): LngLat[] {
  const out: LngLat[] = [];
  let i = 0;
  let lat = 0;
  let lon = 0;
  while (i < text.length) {
    for (let axis = 0; axis < 2; axis++) {
      let shift = 0;
      let result = 0;
      let b: number;
      do {
        b = text.charCodeAt(i++) - 63;
        result |= (b & 0x1f) << shift;
        shift += 5;
      } while (b >= 0x20);
      const delta = result & 1 ? ~(result >> 1) : result >> 1;
      if (axis === 0) lat += delta;
      else lon += delta;
    }
    out.push([lon / 1e5, lat / 1e5]);
  }
  return out;
}

const decoded = new WeakMap<Roads, Map<string, LngLat[]>>();

/** The road from one place to another as [lon, lat] points; a single point if both are the same. */
export function legPath(roads: Roads, from: string, to: string): LngLat[] {
  const a = roads.places.findIndex((p) => p.id === from);
  const b = roads.places.findIndex((p) => p.id === to);
  if (a < 0 || b < 0) throw new Error(`Unbekannter Ort ${a < 0 ? from : to}`);
  if (a === b) return [[roads.places[a].lon, roads.places[a].lat]];
  const key = a < b ? `${from}>${to}` : `${to}>${from}`;
  let cache = decoded.get(roads);
  if (!cache) decoded.set(roads, (cache = new Map()));
  let path = cache.get(key);
  if (!path) cache.set(key, (path = decodePolyline(roads.geometry[key])));
  return a < b ? path : [...path].reverse();
}

/** The point at share t (0..1) of the path's length; longitude scaled to the latitude. */
export function pointAlong(path: LngLat[], t: number): LngLat {
  if (path.length === 1 || t <= 0) return path[0];
  if (t >= 1) return path[path.length - 1];
  const c = Math.cos((path[0][1] * Math.PI) / 180);
  const len = (p: LngLat, q: LngLat) => Math.hypot((q[0] - p[0]) * c, q[1] - p[1]);
  let total = 0;
  for (let i = 1; i < path.length; i++) total += len(path[i - 1], path[i]);
  let left = total * t;
  for (let i = 1; i < path.length; i++) {
    const l = len(path[i - 1], path[i]);
    if (left <= l && l > 0) {
      const f = left / l;
      return [path[i - 1][0] + (path[i][0] - path[i - 1][0]) * f, path[i - 1][1] + (path[i][1] - path[i - 1][1]) * f];
    }
    left -= l;
  }
  return path[path.length - 1];
}
```

- [ ] **Step 5: Implement `routemindView.ts`**

```ts
import type { FrameTruck, RmDelivery, RmPlan, RmTour } from '../../nexus/types';
import { DELIVERY_STATE, VEHICLE_STATE, stateOf } from '../../ui/format';
import { legPath, pointAlong, type LngLat, type Roads } from './roads';

/**
 * Tours must be told apart on the map (Spec 15a), so they get categorical colours. They are not state colours and
 * are used for the route lines only; states keep STATE_HEX.
 */
export const TOUR_COLORS = ['#7dd3fc', '#fcd34d', '#c4b5fd', '#86efac', '#fda4af', '#5eead4'];

interface Feature<G, P> {
  type: 'Feature';
  geometry: G;
  properties: P;
}
interface Collection<G, P> {
  type: 'FeatureCollection';
  features: Feature<G, P>[];
}
type Line = { type: 'LineString'; coordinates: LngLat[] };
type Point = { type: 'Point'; coordinates: LngLat };

/** What the map draws: tours that are planned, being loaded or under way. */
export function shownTours(tours: RmTour[]): RmTour[] {
  return tours.filter((t) => t.status === 'PLANNED' || t.status === 'LOADING' || t.status === 'EN_ROUTE');
}

export function tourColor(tours: RmTour[], tourId: string): string {
  const shown = shownTours(tours);
  const i = shown.findIndex((t) => t.id === tourId);
  return TOUR_COLORS[(i < 0 ? 0 : i) % TOUR_COLORS.length];
}

export function routesGeoJson(roads: Roads, tours: RmTour[], selected: string | null) {
  const shown = shownTours(tours);
  const features = shown.map((t, i) => {
    const stops = ['D0', ...t.stops.map((s) => s.customerId), 'D0'];
    const coordinates: LngLat[] = [];
    for (let k = 1; k < stops.length; k++) {
      const leg = legPath(roads, stops[k - 1], stops[k]);
      coordinates.push(...(coordinates.length > 0 ? leg.slice(1) : leg));
    }
    return {
      type: 'Feature' as const,
      geometry: { type: 'LineString' as const, coordinates },
      properties: { tourId: t.id, color: TOUR_COLORS[i % TOUR_COLORS.length], selected: t.id === selected, dim: selected !== null && t.id !== selected },
    };
  });
  return { type: 'FeatureCollection', features } as Collection<Line, { tourId: string; color: string; selected: boolean; dim: boolean }>;
}

export function placesGeoJson(roads: Roads, deliveries: RmDelivery[]) {
  const openAt = new Map<string, RmDelivery>();
  for (const d of deliveries) {
    if (d.status !== 'DELIVERED' && d.status !== 'CANCELLED') openAt.set(d.customerId, d);
  }
  const features = roads.places.map((p, i) => {
    const d = openAt.get(p.id);
    return {
      type: 'Feature' as const,
      geometry: { type: 'Point' as const, coordinates: [p.lon, p.lat] as LngLat },
      properties: { id: p.id, name: p.name, depot: i === 0, open: d !== undefined, color: d ? stateOf(DELIVERY_STATE, d.status).color : '#94a3b8' },
    };
  });
  return { type: 'FeatureCollection', features } as Collection<Point, { id: string; name: string; depot: boolean; open: boolean; color: string }>;
}

export function trucksGeoJson(roads: Roads, trucks: FrameTruck[]) {
  const features = trucks
    .filter((t) => t.from !== t.to || t.status === 'BROKEN_DOWN')
    .map((t) => ({
      type: 'Feature' as const,
      geometry: { type: 'Point' as const, coordinates: pointAlong(legPath(roads, t.from, t.to), t.progress) },
      properties: { id: t.id, color: stateOf(VEHICLE_STATE, t.status).color },
    }));
  return { type: 'FeatureCollection', features } as Collection<Point, { id: string; color: string }>;
}

/** Minutes after the customer's window closed; 0 on time; null while not delivered. */
export function delayMinutes(d: RmDelivery): number | null {
  if (d.deliveredAtMs === null) return null;
  return Math.max(0, Math.round((d.deliveredAtMs - d.latestMs) / 60_000));
}

/** New plan against the plan it replaced: negative = better. */
export function planDelta(p: RmPlan): { km: number; cost: number; lateMinutes: number } | null {
  if (!p.replaced) return null;
  const r = (v: number) => Math.round(v * 10) / 10;
  return { km: r(p.optimized.km - p.replaced.km), cost: r(p.optimized.cost - p.replaced.cost), lateMinutes: r(p.optimized.lateMinutes - p.replaced.lateMinutes) };
}
```

- [ ] **Step 6: Run the tests and the typecheck**

Run: `cd FactoryTwin && npx vitest run src/areas/routemind && npm run typecheck`
Expected: PASS; typecheck clean (fixtures with `trucks: [], dock: []`).

- [ ] **Step 7: Commit**

```bash
git add FactoryTwin/package.json FactoryTwin/package-lock.json FactoryTwin/src
git commit -m "feat(ui): Daten, Zustände und Strassengeometrie für RouteMind"
```

---

### Task 13: Bereich RouteMind – Karte, Touren, Planvergleich, Dashboard

**Files:**
- Create: `FactoryTwin/src/areas/routemind/RouteMindArea.tsx`, `FactoryTwin/src/areas/routemind/RouteMap.tsx`
- Modify: `FactoryTwin/src/shell/areas.ts` (`comesWith: null`), `FactoryTwin/src/shell/Shell.tsx`
- Test: `FactoryTwin/src/shell/areas.test.ts` (if it lists placeholders), manual check in the browser pane

**Interfaces:**
- Consumes: Task 12, `usePlantQuery`, `send`, `twin`, kit (`Section`, `Stat`, `DataTable`, `CodeTag`), `Button`, `clock`, `nf`, `pct`, `hrefOf`.
- Produces: route `#/routemind/<tourId>` selects a tour; commands from the vehicle table.

- [ ] **Step 1: Map component**

`RouteMap.tsx` (loaded lazily, so MapLibre is not in the main bundle):

```tsx
import maplibregl, { type GeoJSONSource, type StyleSpecification } from 'maplibre-gl';
import 'maplibre-gl/dist/maplibre-gl.css';
import { useEffect, useRef, useState } from 'react';
import type { RmDelivery, RmTour } from '../../nexus/types';
import { twin } from '../../store/factoryStore';
import type { Roads } from './roads';
import { placesGeoJson, routesGeoJson, trucksGeoJson } from './routemindView';

const STYLE: StyleSpecification = {
  version: 8,
  sources: {
    osm: {
      type: 'raster',
      tiles: ['https://tile.openstreetmap.org/{z}/{x}/{y}.png'],
      tileSize: 256,
      maxzoom: 19,
      attribution: '© <a href="https://www.openstreetmap.org/copyright">OpenStreetMap</a>-Mitwirkende',
    },
  },
  // dimmed and desaturated, so the tours stand out on the dark surface
  layers: [{ id: 'osm', type: 'raster', source: 'osm', paint: { 'raster-saturation': -0.7, 'raster-brightness-max': 0.5 } }],
};
const EMPTY = { type: 'FeatureCollection' as const, features: [] };

/** Depot, customers, tours along the roads and the vehicles; a click on a tour line selects it. */
export default function RouteMap({ roads, tours, deliveries, selected, onSelect }: {
  roads: Roads;
  tours: RmTour[];
  deliveries: RmDelivery[];
  selected: string | null;
  onSelect: (tourId: string | null) => void;
}) {
  const box = useRef<HTMLDivElement>(null);
  const map = useRef<maplibregl.Map | null>(null);
  const select = useRef(onSelect);
  select.current = onSelect;
  const [ready, setReady] = useState(false);

  useEffect(() => {
    const d0 = roads.places[0];
    const m = new maplibregl.Map({ container: box.current!, style: STYLE, center: [d0.lon + 0.12, d0.lat - 0.02], zoom: 9.3, attributionControl: { compact: false } });
    m.addControl(new maplibregl.NavigationControl({ showCompass: false }), 'top-right');
    m.on('load', () => {
      m.addSource('routes', { type: 'geojson', data: EMPTY });
      m.addLayer({
        id: 'routes',
        type: 'line',
        source: 'routes',
        layout: { 'line-join': 'round', 'line-cap': 'round' },
        paint: { 'line-color': ['get', 'color'], 'line-width': ['case', ['get', 'selected'], 5, 3], 'line-opacity': ['case', ['get', 'dim'], 0.2, 0.9] },
      });
      m.addSource('places', { type: 'geojson', data: EMPTY });
      m.addLayer({
        id: 'places',
        type: 'circle',
        source: 'places',
        paint: {
          'circle-radius': ['case', ['get', 'depot'], 8, ['get', 'open'], 6, 3.5],
          'circle-color': ['case', ['get', 'depot'], '#e5e7eb', ['get', 'color']],
          'circle-stroke-color': '#0b0f14',
          'circle-stroke-width': 1.5,
        },
      });
      m.addSource('trucks', { type: 'geojson', data: EMPTY });
      m.addLayer({
        id: 'trucks',
        type: 'circle',
        source: 'trucks',
        paint: { 'circle-radius': 7, 'circle-color': ['get', 'color'], 'circle-stroke-color': '#f8fafc', 'circle-stroke-width': 2 },
      });
      m.on('click', 'routes', (e) => select.current((e.features?.[0]?.properties?.tourId as string | undefined) ?? null));
      m.on('mouseenter', 'routes', () => (m.getCanvas().style.cursor = 'pointer'));
      m.on('mouseleave', 'routes', () => (m.getCanvas().style.cursor = ''));
      m.on('click', 'places', (e) => {
        const p = e.features?.[0]?.properties;
        if (p) new maplibregl.Popup({ closeButton: false }).setLngLat(e.lngLat).setText(`${p.id} · ${p.name}`).addTo(m);
      });
      setReady(true);
    });
    map.current = m;
    return () => m.remove();
  }, [roads]);

  useEffect(() => {
    if (!ready || !map.current) return;
    (map.current.getSource('routes') as GeoJSONSource).setData(routesGeoJson(roads, tours, selected));
    (map.current.getSource('places') as GeoJSONSource).setData(placesGeoJson(roads, deliveries));
  }, [ready, roads, tours, deliveries, selected]);

  // vehicles follow the frames, four times a second
  useEffect(() => {
    if (!ready) return;
    const id = window.setInterval(() => {
      const trucks = twin.latest?.trucks ?? [];
      (map.current?.getSource('trucks') as GeoJSONSource | undefined)?.setData(trucksGeoJson(roads, trucks));
    }, 250);
    return () => window.clearInterval(id);
  }, [ready, roads]);

  return <div ref={box} role="region" aria-label="Karte der Touren" className="h-[480px] w-full overflow-hidden rounded-md border border-line" />;
}
```

If `twin.latest` has another name in `twin/twin.ts` (the runtime object holding the newest frame), use that name.

- [ ] **Step 2: The area**

`RouteMindArea.tsx`:

```tsx
import { lazy, Suspense, useEffect, useState } from 'react';
import type { RmDelivery, RmPlan, RmTour, RmVehicle, RouteMindData } from '../../nexus/types';
import { usePlantQuery } from '../../nexus/usePlantQuery';
import { hrefOf } from '../../shell/route';
import { send } from '../../store/factoryStore';
import { clock, DELIVERY_STATE, nf, pct, TOUR_STATE, VEHICLE_STATE } from '../../ui/format';
import { CodeTag, DataTable, Section, Stat, type Column } from '../../ui/kit';
import { Button } from '../../ui/primitives';
import { loadRoads, type Roads } from './roads';
import { delayMinutes, planDelta, tourColor } from './routemindView';

const RouteMap = lazy(() => import('./RouteMap'));
const time = (ms: number | null) => (ms === null ? '–' : clock(ms / 1000, false));

const deliveryColumns: Column<RmDelivery>[] = [
  { key: 'id', label: 'Lieferung', value: (d) => d.id, mono: true, width: '96px' },
  { key: 'order', label: 'Auftrag', value: (d) => d.orderId, mono: true, width: '96px',
    render: (d) => <a href={hrefOf('smartfactory', d.orderId)} className="underline decoration-line-2 underline-offset-4">{d.orderId}</a> },
  { key: 'customer', label: 'Kunde', value: (d) => d.customerName },
  { key: 'status', label: 'Stand', value: (d) => d.status, render: (d) => <CodeTag map={DELIVERY_STATE} code={d.status} /> },
  { key: 'load', label: 'Ladung', value: (d) => d.weightKg, align: 'right', mono: true, render: (d) => `${nf(d.weightKg)} kg · ${d.pallets} Pal.` },
  { key: 'window', label: 'Zeitfenster', value: (d) => d.earliestMs, mono: true, render: (d) => `${time(d.earliestMs)}–${time(d.latestMs)}` },
  { key: 'planned', label: 'Geplant', value: (d) => d.plannedArrivalMs, mono: true, render: (d) => time(d.plannedArrivalMs) },
  { key: 'actual', label: 'Zugestellt', value: (d) => d.deliveredAtMs, mono: true, render: (d) => time(d.deliveredAtMs) },
  { key: 'late', label: 'Verspätung', value: (d) => delayMinutes(d), align: 'right', mono: true,
    render: (d) => { const m = delayMinutes(d); return m === null ? '–' : m === 0 ? 'pünktlich' : `${m} min`; } },
];

function tourColumns(tours: RmTour[]): Column<RmTour>[] {
  return [
    { key: 'id', label: 'Tour', value: (t) => t.id, mono: true, width: '104px',
      render: (t) => (
        <span className="inline-flex items-center gap-2">
          <span aria-hidden className="h-2.5 w-2.5 rounded-full" style={{ background: tourColor(tours, t.id) }} />
          {t.id}
        </span>
      ) },
    { key: 'vehicle', label: 'Fahrzeug', value: (t) => t.vehicleId, mono: true },
    { key: 'status', label: 'Stand', value: (t) => t.status, render: (t) => <CodeTag map={TOUR_STATE} code={t.status} /> },
    { key: 'depart', label: 'Abfahrt', value: (t) => t.departAtMs, mono: true, render: (t) => time(t.departAtMs) },
    { key: 'stops', label: 'Stopps', value: (t) => t.stops.length, align: 'right', mono: true },
    { key: 'km', label: 'Strecke', value: (t) => t.km, align: 'right', mono: true, render: (t) => `${nf(t.km, 1)} km` },
    { key: 'cost', label: 'Kosten', value: (t) => t.cost, align: 'right', mono: true, render: (t) => `${nf(t.cost, 2)} CHF` },
  ];
}

const planColumns: Column<RmPlan>[] = [
  { key: 'wave', label: 'Welle', value: (p) => p.waveAtMs, mono: true, render: (p) => time(p.waveAtMs) },
  { key: 'trigger', label: 'Anlass', value: (p) => p.trigger },
  { key: 'solver', label: 'Löser', value: (p) => p.solver },
  { key: 'base', label: 'Nächster Nachbar', value: (p) => p.baseline.cost, align: 'right', mono: true,
    render: (p) => `${nf(p.baseline.km, 1)} km · ${nf(p.baseline.driveMinutes)} min · ${nf(p.baseline.cost, 2)} CHF · ${nf(p.baseline.lateMinutes)} min spät` },
  { key: 'best', label: 'Optimiert', value: (p) => p.optimized.cost, align: 'right', mono: true,
    render: (p) => `${nf(p.optimized.km, 1)} km · ${nf(p.optimized.driveMinutes)} min · ${nf(p.optimized.cost, 2)} CHF · ${nf(p.optimized.lateMinutes)} min spät` },
  { key: 'delta', label: 'Gegen vorigen Plan', value: (p) => planDelta(p)?.cost ?? null, align: 'right', mono: true,
    render: (p) => { const d = planDelta(p); return d ? `${d.km >= 0 ? '+' : ''}${nf(d.km, 1)} km · ${d.cost >= 0 ? '+' : ''}${nf(d.cost, 2)} CHF` : '–'; } },
  { key: 'open', label: 'Nicht planbar', value: (p) => p.unassigned.length, render: (p) => (p.unassigned.length ? p.unassigned.join('; ') : '–') },
];

function VehicleActions({ v }: { v: RmVehicle }) {
  const act = (command: string, extra: Record<string, unknown> = {}) => void send({ command, vehicleId: v.id, ...extra });
  return (
    <span className="flex gap-2">
      {(v.status === 'EN_ROUTE' || v.status === 'RETURNING' || v.status === 'AVAILABLE') && (
        <Button size="sm" onClick={() => act('vehicleBreakdown')} aria-label={`Panne an ${v.id} auslösen`}>Panne</Button>
      )}
      {v.status === 'BROKEN_DOWN' && <Button size="sm" onClick={() => act('vehicleRepair')} aria-label={`${v.id} reparieren`}>Reparieren</Button>}
      {v.status === 'AVAILABLE' && <Button size="sm" onClick={() => act('vehicleMaintenance', { on: true })} aria-label={`${v.id} in Wartung`}>Wartung</Button>}
      {v.status === 'MAINTENANCE' && <Button size="sm" onClick={() => act('vehicleMaintenance', { on: false })} aria-label={`${v.id} freigeben`}>Freigeben</Button>}
    </span>
  );
}

const vehicleColumns: Column<RmVehicle>[] = [
  { key: 'id', label: 'Fahrzeug', value: (v) => v.id, mono: true, width: '88px' },
  { key: 'name', label: 'Typ', value: (v) => v.name },
  { key: 'status', label: 'Zustand', value: (v) => v.status, render: (v) => <CodeTag map={VEHICLE_STATE} code={v.status} /> },
  { key: 'tour', label: 'Tour', value: (v) => v.tourId, mono: true, render: (v) => v.tourId ?? '–' },
  { key: 'leg', label: 'Strecke', value: (v) => `${v.from}→${v.to}`, mono: true, render: (v) => (v.from === v.to ? 'Depot' : `${v.from} → ${v.to} · ${pct(v.progress)}`) },
  { key: 'cap', label: 'Kapazität', value: (v) => v.maxKg, align: 'right', mono: true, render: (v) => `${nf(v.maxKg)} kg · ${v.maxPallets} Pal.` },
  { key: 'act', label: 'Befehle', value: () => null, render: (v) => <VehicleActions v={v} /> },
];

/** Deliveries to the customers (Spec 15a): map, tours, vehicles, plan comparison and the logistics dashboard. */
export function RouteMindArea({ tourId }: { tourId: string | null }) {
  const { data } = usePlantQuery<RouteMindData>({ query: 'routemind' }, 2000);
  const [roads, setRoads] = useState<Roads | null>(null);
  const [roadsError, setRoadsError] = useState<string | null>(null);
  const [selected, setSelected] = useState<string | null>(tourId);
  useEffect(() => setSelected(tourId), [tourId]);
  useEffect(() => {
    loadRoads().then(setRoads, (e: Error) => setRoadsError(e.message));
  }, []);

  const tours = data?.tours ?? [];
  const tour = tours.find((t) => t.id === selected) ?? null;
  const k = data?.kpis;
  const solver = data?.plans[0]?.solver ?? '–';
  return (
    <div className="grid grid-cols-12 gap-x-8 gap-y-10 px-4 py-6 lg:px-6 lg:py-8">
      <Section title="Logistik" className="col-span-12" action={<span className="text-[12px] text-txt-3">Löser: {solver}</span>}>
        <div className="grid grid-cols-2 gap-6 md:grid-cols-4 xl:grid-cols-8">
          <Stat label="Aktive Touren" value={k ? k.activeTours : '–'} />
          <Stat label="Auslastung" value={k ? pct(k.utilization) : '–'} hint="Fahrzeuge in Verladung oder unterwegs" />
          <Stat label="Zugestellt" value={k ? k.delivered : '–'} />
          <Stat label="Pünktlich" value={k && k.delivered > 0 ? pct(k.onTime / k.delivered) : '–'} />
          <Stat label="Ø Verspätung" value={k ? nf(k.avgDelayMinutes, 1) : '–'} unit="min" />
          <Stat label="Offen" value={k ? k.open : '–'} />
          <Stat label="Strecke" value={k ? nf(k.km) : '–'} unit="km" />
          <Stat label="Kosten" value={k ? nf(k.cost) : '–'} unit="CHF" />
        </div>
      </Section>

      <Section title="Karte" className="col-span-12 xl:col-span-8"
        action={<Button size="sm" onClick={() => void send({ command: 'replanDeliveries' })}>Neu planen</Button>}>
        {roadsError && <p role="alert" className="text-[13px] text-txt-1">{roadsError}</p>}
        {roads && data && (
          <Suspense fallback={<div className="h-[480px] rounded-md border border-line" />}>
            <RouteMap roads={roads} tours={tours} deliveries={data.deliveries} selected={selected} onSelect={setSelected} />
          </Suspense>
        )}
        <p className="mt-2 text-[12px] text-txt-3">
          {data?.attribution ?? ''}. Kartenkacheln © OpenStreetMap-Mitwirkende, zur Laufzeit von openstreetmap.org geladen.
        </p>
      </Section>

      <Section title={tour ? `Tour ${tour.id}` : 'Tour'} className="col-span-12 xl:col-span-4"
        action={tour && <Button size="sm" variant="ghost" onClick={() => setSelected(null)}>Alle Touren</Button>}>
        {!tour && <p className="text-[13px] text-txt-3">Eine Tour in der Karte oder in der Liste wählen.</p>}
        {tour && (
          <ol className="space-y-2 text-[13px]">
            <li className="text-txt-3">Abfahrt {time(tour.departAtMs)} mit {tour.vehicleId} · <CodeTag map={TOUR_STATE} code={tour.status} /></li>
            {tour.stops.map((s) => {
              const d = data?.deliveries.find((x) => x.id === s.deliveryId);
              return (
                <li key={s.seq} className="flex items-baseline justify-between gap-3">
                  <span><span className="font-mono">{s.seq}.</span> {d?.customerName ?? s.customerId}</span>
                  <span className="font-mono text-txt-2">
                    {s.arrivedAtMs !== null ? `an ${time(s.arrivedAtMs)}` : `ETA ${time(s.plannedArrivalMs)}`}
                  </span>
                </li>
              );
            })}
          </ol>
        )}
      </Section>

      <Section title="Touren" className="col-span-12">
        <DataTable label="Touren" rows={[...tours].reverse()} columns={tourColumns(tours)} rowKey={(t) => t.id}
          onRowClick={(t) => setSelected(t.id)} empty="Noch keine Tour geplant." />
      </Section>

      <Section title="Lieferfahrzeuge" className="col-span-12">
        <DataTable label="Lieferfahrzeuge" rows={data?.vehicles ?? []} columns={vehicleColumns} rowKey={(v) => v.id} />
      </Section>

      <Section title="Lieferungen" className="col-span-12">
        <DataTable label="Lieferungen" rows={[...(data?.deliveries ?? [])].reverse()} columns={deliveryColumns}
          rowKey={(d) => d.id} empty="Noch keine Lieferung." />
      </Section>

      <Section title="Planungen" className="col-span-12">
        <DataTable label="Planungen" rows={data?.plans ?? []} columns={planColumns} rowKey={(p) => p.planId}
          empty="Noch keine Planung. Die erste Welle wird 30 min nach dem Start geplant." />
      </Section>
    </div>
  );
}
```

If `Button` has no `ghost` variant, use the variant the other areas use for secondary actions.

`areas.ts`: `routemind` → `comesWith: null`. `Shell.tsx`: import `RouteMindArea` and add `case 'routemind': return <RouteMindArea tourId={param} />;`.

- [ ] **Step 3: Tests and checks**

Run: `cd FactoryTwin && npm run typecheck && npx vitest run`
Expected: PASS. A test that expects `routemind` as placeholder (`areas.test.ts`, `Shell` tests): change it to expect no placeholder area left.

Check in the browser pane (dev server from `.claude/launch.json`, browser mode): open `#/routemind`, start the simulation at 25×; after 30 simulated minutes the first plan appears with both solvers' figures, tours are coloured lines along roads, vehicles move after departure, a click on a tour line shows its stops with ETA, "Panne" on a moving vehicle turns its tour to "Unterbrochen". The map's attribution line is visible. Below 1024 px everything stacks in one column. Take one screenshot for the ledger.

- [ ] **Step 4: Commit**

```bash
git add FactoryTwin/src
git commit -m "feat(ui): Bereich RouteMind mit Karte, Touren, Planvergleich und Logistik-Dashboard"
```

---

### Task 14: FactoryTwin – Verladezone, wartende Fahrzeuge, Übergabe

**Files:**
- Create: `FactoryTwin/src/scene/LoadingZone.tsx`
- Modify: `FactoryTwin/src/scene/FactoryScene.tsx`
- Test: `FactoryTwin/src/scene/loadingZone.test.ts`

**Interfaces:**
- Consumes: frame `trucks`, `dock` (Task 9/12), `nodePos('D_LZ')` (Task 2).
- Produces: `dockSlots(dock: FrameDock[]): {x,z}[]`, `baySlots(trucks: FrameTruck[]): {id, x, z, loading}[]` (pure, tested), `<LoadingZone />` in the scene.

- [ ] **Step 1: Write the failing test**

```ts
import { describe, expect, it } from 'vitest';
import type { FrameDock, FrameTruck } from '../nexus/types';
import { baySlots, dockSlots, MAX_PALLETS } from './LoadingZone';

const truck = (id: string, status: FrameTruck['status'], from = 'D0', to = 'D0'): FrameTruck => ({ id, name: id, status, tourId: null, from, to, progress: 0 });

describe('loading zone', () => {
  it('shows one pallet per pallet of the waiting deliveries, at most MAX_PALLETS, inside the zone', () => {
    const dock: FrameDock[] = [
      { deliveryId: 'DL-0001', status: 'READY_TO_LOAD', pallets: 2 },
      { deliveryId: 'DL-0002', status: 'LOADED', pallets: 1 },
    ];
    const slots = dockSlots(dock);
    expect(slots).toHaveLength(2); // loaded goods are on the truck, not on the floor
    for (const s of slots) {
      expect(s.x).toBeGreaterThan(19);
      expect(s.x).toBeLessThan(24);
      expect(s.z).toBeGreaterThan(16);
      expect(s.z).toBeLessThan(18);
    }
    expect(dockSlots([{ deliveryId: 'X', status: 'READY_TO_LOAD', pallets: 99 }])).toHaveLength(MAX_PALLETS);
  });

  it('parks the vehicles that are at the depot in their own bays', () => {
    const bays = baySlots([truck('LKW-1', 'AVAILABLE'), truck('LKW-2', 'EN_ROUTE', 'D0', 'K01'), truck('LW-1', 'LOADING'), truck('LW-2', 'MAINTENANCE')]);
    expect(bays.map((b) => b.id)).toEqual(['LKW-1', 'LW-1', 'LW-2']);
    expect(bays.find((b) => b.id === 'LW-1')!.loading).toBe(true);
    expect(new Set(bays.map((b) => b.x)).size).toBe(3);
  });
});
```

Run: `cd FactoryTwin && npx vitest run src/scene/loadingZone.test.ts`
Expected: FAIL — module not found.

- [ ] **Step 2: Implement**

`LoadingZone.tsx`:

```tsx
import { useFrame } from '@react-three/fiber';
import { useMemo, useRef } from 'react';
import * as THREE from 'three';
import type { FrameDock, FrameTruck } from '../nexus/types';
import { twin } from '../store/factoryStore';
import { nodePos } from '../twin/layout';
import { MAT } from './materials';

export const MAX_PALLETS = 12;
/** Truck bays along the south wall, east of the dock; one per delivery vehicle (four). */
const BAYS_X = [24.6, 26.6, 28.6, 30.6];
const BAY_Z = 16.2;

/** Floor places for the pallets waiting for loading: two rows between the dock and the wall. */
export function dockSlots(dock: FrameDock[]): { x: number; z: number }[] {
  let pallets = 0;
  for (const d of dock) if (d.status === 'READY_TO_LOAD') pallets += d.pallets;
  const n = Math.min(pallets, MAX_PALLETS);
  return Array.from({ length: n }, (_, i) => ({ x: 19.6 + (i % 6) * 0.7, z: i < 6 ? 16.9 : 17.6 }));
}

/** Vehicles at the depot (not on the road) in fixed bays, in the order of their ids. */
export function baySlots(trucks: FrameTruck[]): { id: string; x: number; z: number; loading: boolean }[] {
  return trucks
    .map((t, i) => ({ t, i }))
    .filter(({ t }) => t.from === t.to && t.status !== 'EN_ROUTE' && t.status !== 'RETURNING')
    .map(({ t, i }) => ({ id: t.id, x: BAYS_X[i % BAYS_X.length], z: BAY_Z, loading: t.status === 'LOADING' }));
}

/** Loading zone of RouteMind: floor marking, waiting pallets and the delivery vehicles at their bays. */
export function LoadingZone() {
  const dock = nodePos('D_LZ');
  const pallets = useRef<THREE.InstancedMesh>(null);
  const trucks = useRef<THREE.Group>(null);
  const box = useMemo(() => new THREE.BoxGeometry(1, 1, 1), []);
  const tmp = useMemo(() => new THREE.Object3D(), []);
  const last = useRef('');

  useFrame(() => {
    const frame = twin.latest;
    if (!frame) return;
    const slots = dockSlots(frame.dock ?? []);
    const bays = baySlots(frame.trucks ?? []);
    const key = `${slots.length}|${bays.map((b) => `${b.id}${b.loading}`).join(',')}`;
    if (key === last.current) return;
    last.current = key;
    const mesh = pallets.current!;
    slots.forEach((s, i) => {
      tmp.position.set(s.x, 0.35, s.z);
      tmp.scale.set(0.6, 0.6, 0.6);
      tmp.updateMatrix();
      mesh.setMatrixAt(i, tmp.matrix);
    });
    mesh.count = slots.length;
    mesh.instanceMatrix.needsUpdate = true;
    mesh.computeBoundingSphere();
    const g = trucks.current!;
    g.children.forEach((child, i) => {
      const bay = bays.find((b) => BAYS_X.indexOf(b.x) === i);
      child.visible = bay !== undefined;
    });
  });

  return (
    <group>
      {/* floor marking of the zone */}
      <mesh rotation-x={-Math.PI / 2} position={[25.2, 0.011, 16.1]}>
        <planeGeometry args={[12.4, 3.6]} />
        <meshStandardMaterial color="#1c2533" roughness={0.95} transparent opacity={0.85} />
      </mesh>
      <mesh rotation-x={-Math.PI / 2} position={[dock.x, 0.013, dock.z]}>
        <planeGeometry args={[1.6, 1.6]} />
        <meshStandardMaterial color="#13261d" roughness={0.9} transparent opacity={0.8} />
      </mesh>
      <instancedMesh ref={pallets} args={[box, MAT.pallet, MAX_PALLETS]} castShadow />
      <group ref={trucks}>
        {BAYS_X.map((x) => (
          <group key={x} position={[x, 0, BAY_Z]} visible={false}>
            {/* box body and cab of a delivery vehicle, nose towards the wall */}
            <mesh position={[0, 1.1, -0.3]} material={MAT.steel} castShadow>
              <boxGeometry args={[1.7, 1.8, 2.6]} />
            </mesh>
            <mesh position={[0, 0.8, 1.35]} material={MAT.machineDark} castShadow>
              <boxGeometry args={[1.7, 1.3, 0.9]} />
            </mesh>
          </group>
        ))}
      </group>
    </group>
  );
}
```

Use the material names that exist in `scene/materials.ts` (`MAT.pallet`, `MAT.steel`, `MAT.machineDark` are used in `Logistics.tsx`). If the runtime object holding the frame is not `twin.latest`, use what `FlowPilotArea` uses.

`FactoryScene.tsx`: import `LoadingZone` and render `<LoadingZone />` after `<Chargers />`.

- [ ] **Step 3: Tests and look**

Run: `cd FactoryTwin && npx vitest run src/scene && npm run typecheck`
Expected: PASS.

In the browser pane (`#/twin`, 25×): after the first putaways an AGV drives the D_LZ detour into the south-east corner and pallets appear at the dock; vehicles stand in their bays and vanish when their tour leaves; nothing intersects the finished-goods racks, the CV-04 outfeed or the scrap bin. If the detour runs through a model, move the custom points in `layout.ts` and the same numbers in `PathData.java` and `V5__loading_zone.sql` (and record a ruling). Frame rate check: `npx playwright test e2e/fps.spec.ts` still reports ≥ 55 fps.

- [ ] **Step 4: Commit**

```bash
git add FactoryTwin/src
git commit -m "feat(twin): Verladezone mit wartender Ware und Lieferfahrzeugen"
```

---

### Task 15: Ende-zu-Ende, Dokumentation, Gesamtprüfung

**Files:**
- Create: `FactoryTwin/e2e/routemind.spec.ts`
- Modify: `FactoryTwin/e2e/scenarios.spec.ts` (Szenario H)
- Modify: `README.md`, `FactoryTwin/README.md`

**Interfaces:**
- Consumes: everything above.

- [ ] **Step 1: E2E test for the area**

`routemind.spec.ts`:

```ts
import { expect, test } from '@playwright/test';

function expectedMode(baseURL: string | undefined): 'Browser' | 'Server' {
  return baseURL?.includes(':5179') ? 'Server' : 'Browser';
}

test('RouteMind zeigt Karte, Fahrzeuge und die erste Planung', async ({ page, baseURL }) => {
  test.setTimeout(6 * 60_000);
  await page.goto('/#/routemind');
  await expect(page.getByText(`${expectedMode(baseURL)} · verbunden`)).toBeVisible({ timeout: 60_000 });
  await expect(page.getByRole('region', { name: 'Karte der Touren' })).toBeVisible();
  await expect(page.locator('.maplibregl-canvas')).toBeVisible();
  await expect(page.getByText(/OpenStreetMap-Mitwirkende/).first()).toBeVisible();
  await expect(page.getByRole('table', { name: 'Lieferfahrzeuge' }).getByRole('row')).toHaveCount(5); // head + 4
  await page.getByRole('button', { name: '25×', exact: true }).click();
  const start = page.getByRole('button', { name: 'Start', exact: true });
  if (await start.isVisible()) await start.click();
  // waves are planned from 30 simulated minutes on; the first plan needs goods, about one simulated hour
  await expect(page.getByRole('table', { name: 'Planungen' }).getByRole('row')).not.toHaveCount(1, { timeout: 5 * 60_000 });
  await expect(page.getByText(/Löser: (Heuristik|OR-Tools)/)).toBeVisible();
});
```

If `DataTable` puts its `label` on the scroll container rather than the `<table>`, select by the role it actually renders (look at `DataTable.tsx`).

In `scenarios.spec.ts` add `['H', 'Expressauftrag bis zur Lieferplanung']` to `SCENARIOS` (3 simulated hours at 25× stay under 8 minutes).

Run: `cd FactoryTwin && npx playwright test e2e/routemind.spec.ts e2e/scenarios.spec.ts -g "RouteMind|Szenario H"` and the same with `-c playwright.server.config.ts`
Expected: PASS in both modes. The server mode needs the backend started by the config (it builds the jar; OR-Tools loads its native library on the first plan).

- [ ] **Step 2: README**

Root `README.md`:
- In the architecture diagram add the module `RouteMind` next to FlowPilot and SmartFactory (edges `plant → RouteMind`, `RouteMind → contracts`), and `OR-Tools` beside `app`.
- New section `## RouteMind` (German, short): what it does (Lieferaufträge, Wellen alle 30 min, Verladezone LZ-1, Karte), the two solvers (`nexus.routemind.solver=ortools|heuristic`, Browser immer Heuristik), the road data (`RouteMind/roads/places.json`, regenerated with `node FactoryTwin/scripts/build-roads.mjs`, about nine minutes against the public OSRM demo server, only by hand), and the sources: „Strassendaten © OpenStreetMap-Mitwirkende, ODbL; Routen OSRM; Kartenkacheln von tile.openstreetmap.org zur Laufzeit (Internet nötig)“.
- Scenario Lab table: add F–J.

`FactoryTwin/README.md`: one paragraph on the RouteMind area and that MapLibre loads lazily.

- [ ] **Step 3: Full verification**

Run, one after the other, and read every result:
- `./mvnw -q verify` → BUILD SUCCESS (all modules, embedded PostgreSQL, OR-Tools, TeaVM comparison).
- `cd FactoryTwin && npm run typecheck && npx vitest run && npm run build` → clean; the build output shows MapLibre in its own chunk.
- `cd FactoryTwin && npx playwright test` and `npx playwright test -c playwright.server.config.ts` → all green.
- `git log --format=%B main..HEAD | grep -ci "co-authored"` → `0`.
Expected: everything green; any failure is fixed before the task ends.

- [ ] **Step 4: Commit**

```bash
git add README.md FactoryTwin/README.md FactoryTwin/e2e
git commit -m "docs: RouteMind in README, E2E für Karte und Szenario H"
```
